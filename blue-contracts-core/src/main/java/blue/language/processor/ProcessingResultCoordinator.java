package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owns deterministic invocation outcome state, final validation, and result
 * publication.
 *
 * <p>Only this component chooses a terminal status or publishes a canonical
 * snapshot. A failure is first-wins and always marks the invocation
 * non-committing.</p>
 */
final class ProcessingResultCoordinator {

    private final DocumentProcessor owner;
    private final DocumentProcessingRuntime runtime;
    private final Node inputDocument;
    private final ResolvedSnapshot inputSnapshot;
    private final boolean hasProcessEvent;
    private final Supplier<VerifiedExecutionEvidence> evidenceSupplier;
    private ProcessorStatus failureStatus;
    private ProcessorDiagnostic failureDiagnostic;
    private ResolvedSnapshot resultSnapshot;
    private boolean directRootTerminated;
    private boolean acceptedDelivery;
    private boolean staleDelivery;
    private boolean completedDelivery;
    private SubscriptionDelta subscriptionDelta = SubscriptionDelta.empty();

    ProcessingResultCoordinator(
            DocumentProcessor owner,
            DocumentProcessingRuntime runtime,
            Node inputDocument,
            ResolvedSnapshot inputSnapshot,
            boolean hasProcessEvent,
            Supplier<VerifiedExecutionEvidence> evidenceSupplier) {
        this.owner = owner;
        this.runtime = runtime;
        this.inputDocument = inputDocument;
        this.inputSnapshot = inputSnapshot;
        this.hasProcessEvent = hasProcessEvent;
        this.evidenceSupplier = evidenceSupplier;
    }

    boolean admitDirectRootState() {
        try {
            ProcessorEngine.TerminationMarker marker =
                    ProcessorEngine.terminationMarker(
                            inputDocument,
                            JsonPointer.ROOT);
            if (marker == null) {
                return false;
            }
            runtime.scope(JsonPointer.ROOT)
                    .finalizeTermination(marker.reason);
            directRootTerminated = true;
            return true;
        } catch (RuntimeException exception) {
            fail(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    ProcessorDiagnostic.of(
                            ProcessorErrorCategory.InvalidReservedRuntimeState,
                            ProcessorEngine.deterministicMessage(
                                    exception,
                                    "Invalid direct Root terminated state")));
            return true;
        }
    }

    void performFinalSoundnessValidation(ScopeExecutor scopeExecutor) {
        if (!hasFailure() && completedDelivery) {
            scopeExecutor.cleanupCheckpointState();
        }
    }

    void validateSubscriptionDelta() {
        if (hasFailure() || !completedDelivery) {
            return;
        }
        SubscriptionSurfaceValidationContext.Builder validation =
                SubscriptionSurfaceValidationContext.builder(
                        inputDocument,
                        runtime.document(),
                        runtime.changedPaths(),
                        owner.gasSchedule())
                        .snapshots(inputSnapshot, runtime.snapshot())
                        .runtimeWorkSessions(() -> runtime
                                .newRuntimeWorkSession(
                                        owner.matchingService().blue()));
        VerifiedExecutionEvidence evidence = evidenceSupplier.get();
        if (evidence != null) {
            long revision = evidence.managedRootRevision();
            if (revision == Long.MAX_VALUE) {
                throw new SubscriptionSurfaceInvalidException(
                        "Committing Root revision overflows",
                        JsonPointer.ROOT,
                        null);
            }
            validation.committingInterval(
                    evidence.eventOrderKey(),
                    revision + 1L);
            if (evidence.hasActiveSubscriptionIntervals()) {
                validation.activeSubscriptionIntervals(
                        evidence.activeSubscriptionIntervals());
            }
        }
        subscriptionDelta = owner.subscriptionSurfaceValidator()
                .validate(validation.build());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(
                ProcessingTraceConstants.FIELD_ADDED,
                subscriptionDelta.added().size());
        details.put(
                ProcessingTraceConstants.FIELD_REMOVED,
                subscriptionDelta.removed().size());
        runtime.recordTrace(
                ProcessingTraceRecord.Kind.SUBSCRIPTION_DELTA,
                JsonPointer.ROOT,
                null,
                null,
                details,
                null);
    }

    SubscriptionDelta subscriptionDelta() {
        return subscriptionDelta;
    }

    DocumentProcessingResult result() {
        ProcessorStatus status = selectStatus();
        if (!status.commits()) {
            resultSnapshot = inputSnapshot;
            return DocumentProcessingResult.nonCommitting(
                    inputDocument.clone(),
                    runtime.totalGas(),
                    status,
                    failureDiagnostic);
        }
        ResolvedSnapshot snapshot = runtime.snapshot();
        if (snapshot != null) {
            ResolvedSnapshot publishedSnapshot = publishableSnapshot(
                    snapshot,
                    owner.observer());
            resultSnapshot = publishedSnapshot;
            return DocumentProcessingResult.completed(
                    publishedSnapshot.canonicalRoot(),
                    runtime.rootEmissions(),
                    runtime.totalGas(),
                    status,
                    null);
        }
        resultSnapshot = null;
        return DocumentProcessingResult.completed(
                runtime.document(),
                runtime.rootEmissions(),
                runtime.totalGas(),
                status,
                null);
    }

    ProcessingDebugResult debugResult() {
        DocumentProcessingResult completed = result();
        VerifiedExecutionEvidence evidence = evidenceSupplier.get();
        PlatformCommitCompanion companion = evidence != null
                ? PlatformCommitCompanion.of(
                        evidence,
                        completed,
                        subscriptionDelta)
                : null;
        return new ProcessingDebugResult(
                completed,
                runtime.conformanceTrace(),
                companion,
                resultSnapshot);
    }

    DocumentProcessingResult partialResult() {
        try {
            return result();
        } catch (RuntimeException ignored) {
            return DocumentProcessingResult.nonCommitting(
                    inputDocument.clone(),
                    runtime.totalGas(),
                    ProcessorStatus.RUNTIME_FATAL,
                    ProcessorDiagnostic.of(
                            ProcessorErrorCategory.RuntimeExecutionFailure,
                            "Runtime processing failed"));
        }
    }

    void fail(
            ProcessorStatus status,
            ProcessorDiagnostic diagnostic) {
        if (failureStatus != null) {
            return;
        }
        if (status == null
                || status.commits()
                || status == ProcessorStatus.NO_MATCH
                || status == ProcessorStatus.STALE
                || status == ProcessorStatus.TERMINATED) {
            throw new IllegalArgumentException(
                    "Invalid deterministic failure status: " + status);
        }
        failureStatus = status;
        failureDiagnostic = Objects.requireNonNull(
                diagnostic,
                "diagnostic");
        runtime.markRunTerminated();
    }

    void recordAcceptedDelivery() {
        acceptedDelivery = true;
    }

    void recordStaleDelivery() {
        acceptedDelivery = true;
        staleDelivery = true;
    }

    void recordCompletedDelivery() {
        acceptedDelivery = true;
        completedDelivery = true;
    }

    boolean hasFailure() {
        return failureStatus != null;
    }

    private ProcessorStatus selectStatus() {
        if (failureStatus != null) {
            return failureStatus;
        }
        if (!hasProcessEvent) {
            return ProcessorStatus.SUCCESS;
        }
        if (directRootTerminated) {
            return ProcessorStatus.TERMINATED;
        }
        if (completedDelivery) {
            return ProcessorStatus.SUCCESS;
        }
        if (staleDelivery) {
            return ProcessorStatus.STALE;
        }
        return ProcessorStatus.NO_MATCH;
    }

    private ResolvedSnapshot publishableSnapshot(
            ResolvedSnapshot snapshot,
            ProcessingObserver observer) {
        ProcessingObserver sink = observer != null
                ? observer
                : NoOpProcessingObserver.INSTANCE;
        ProcessingObservations.record(
                sink,
                ProcessingMetricId.PROCESSOR_PUBLICATION_INVARIANT_CHECKS,
                1L);
        ResolvedSnapshot published = snapshot;
        if (!isStrictPublishable(published)) {
            ProcessingObservations.record(
                    sink,
                    ProcessingMetricId.PROCESSOR_PUBLICATION_CANONICALIZATIONS,
                    1L);
            ProcessingObservations.record(
                    sink,
                    ProcessingMetricId
                            .PROCESSOR_PUBLICATION_CANONICAL_MATERIALIZATIONS,
                    1L);
            ProcessingObservations.record(
                    sink,
                    ProcessingMetricId
                            .PROCESSOR_PUBLICATION_STRICT_BLUE_ID_CALCULATIONS,
                    1L);
            long canonicalizationStart = System.nanoTime();
            try {
                published = published.toStrictBlueIdValidatedCanonical();
            } catch (RuntimeException exception) {
                recordPublicationMismatch(sink);
                throw exception;
            } finally {
                ProcessingObservations.record(
                        sink,
                        ProcessingMetricId
                                .PROCESSOR_PUBLICATION_CANONICALIZATION_NANOS,
                        Math.max(
                                1L,
                                System.nanoTime() - canonicalizationStart));
            }
        }
        if (!isStrictPublishable(published)) {
            recordPublicationMismatch(sink);
            throw new IllegalStateException(
                    "Processor result snapshot must be strict canonical "
                            + "with strict BlueId validation.");
        }
        String snapshotBlueId = published.blueId();
        String canonicalBlueId = published.frozenCanonicalRoot().blueId();
        if (!Objects.equals(snapshotBlueId, canonicalBlueId)) {
            ProcessingObservations.record(
                    sink,
                    ProcessingMetricId
                            .PROCESSOR_PUBLICATION_IDENTITY_MISMATCHES,
                    1L);
            throw new IllegalStateException(
                    "Processor result snapshot BlueId must match canonical "
                            + "root BlueId.");
        }
        ProcessingObservations.record(
                sink,
                ProcessingMetricId.PROCESSOR_PUBLISHED_STRICT_CANONICAL,
                1L);
        return published;
    }

    private void recordPublicationMismatch(ProcessingObserver observer) {
        ProcessingObservations.record(
                observer,
                ProcessingMetricId.PROCESSOR_PUBLISHED_UNCHECKED_CANONICAL,
                1L);
        ProcessingObservations.record(
                observer,
                ProcessingMetricId.PROCESSOR_PUBLICATION_IDENTITY_MISMATCHES,
                1L);
    }

    private boolean isStrictPublishable(ResolvedSnapshot snapshot) {
        FrozenNode canonicalRoot = snapshot.frozenCanonicalRoot();
        return canonicalRoot.isStrictCanonical()
                && canonicalRoot.isStrictBlueIdValidation();
    }
}
