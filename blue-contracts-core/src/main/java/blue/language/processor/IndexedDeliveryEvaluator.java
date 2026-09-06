package blue.language.processor;

import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.runtime.LanguageRuntimeAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Evaluates a complete indexed subscription surface into an exact delivery
 * plan and immutable per-occurrence diagnostics.
 *
 * <p>The caller supplies the complete retained active interval surface and,
 * for feeder-backed preparation, the exact ordered physical candidate list.
 * Registered selection functions are re-evaluated under processor-owned
 * runtime admission sessions, and the resulting plan is independently
 * verified before it is returned.</p>
 */
public final class IndexedDeliveryEvaluator {

    private static final String SNAPSHOT_GENERATION_EXPIRED =
            "Indexed delivery snapshot generation is no longer current";
    private static final String RELEASED_GAS_SCHEDULE_REQUIRED =
            "Indexed delivery evaluation requires the released Contracts 1.0 gas package";

    private static final Comparator<SubscriptionDelta.Entry>
            CANONICAL_INTERVAL_ORDER =
            new Comparator<SubscriptionDelta.Entry>() {
                @Override
                public int compare(
                        SubscriptionDelta.Entry left,
                        SubscriptionDelta.Entry right) {
                    int comparison = ExternalOrderKey.compareTextCodePoints(
                            left.scopePath(), right.scopePath());
                    if (comparison != 0) {
                        return comparison;
                    }
                    comparison = Integer.compare(
                            left.order(), right.order());
                    if (comparison != 0) {
                        return comparison;
                    }
                    comparison = ExternalOrderKey.compareTextCodePoints(
                            left.channelKey(), right.channelKey());
                    return comparison != 0
                            ? comparison
                            : ExternalOrderKey.compareTextCodePoints(
                                    left.effectiveTypeBlueId(),
                                    right.effectiveTypeBlueId());
                }
            };

    private final DocumentProcessor processor;
    private final DocumentProcessorLifecycle lifecycle;

    IndexedDeliveryEvaluator(
            DocumentProcessor processor,
            DocumentProcessorLifecycle lifecycle) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * Evaluates and verifies one complete indexed Root/event surface.
     *
     * @param root exact Root at {@code rootRevision}
     * @param event exact incoming event
     * @param rootRevision non-negative managed and indexed Root revision
     * @param eventOrderKey exact external event order
     * @param completeActiveIntervals complete retained active interval surface
     * @param orderedCandidateOccurrenceKeys exact feeder physical candidates
     *        in canonical delivery order
     * @return exact verified plan and complete immutable diagnostics
     * @throws NullPointerException when a required argument or collection
     *         element is {@code null}
     * @throws IllegalArgumentException when the revision or an occurrence key
     *         is invalid
     * @throws IllegalStateException when the processor is closed, its snapshot
     *         generation is stale, or its gas package is not Contracts 1.0
     * @throws ExecutionEvidenceUnavailableException when exact provider
     *         evidence is unavailable
     * @throws InvalidExecutionEvidenceException when intervals, candidates,
     *         registered functions, or derived evidence disagree
     * @throws PortableLimitExceededException when a portable manifest limit is
     *         exceeded
     * @throws GasLimitExceededException when evaluation exhausts its gas budget
     */
    public IndexedDeliveryPreparation prepare(
            Node root,
            Node event,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals,
            List<ExternalSubscriptionOccurrenceKey>
                    orderedCandidateOccurrenceKeys) {
        return prepareInternal(
                root,
                event,
                rootRevision,
                eventOrderKey,
                completeActiveIntervals,
                Objects.requireNonNull(
                        orderedCandidateOccurrenceKeys,
                        "orderedCandidateOccurrenceKeys"));
    }

    /**
     * Creates a current-Root plan deriver that computes physical candidates
     * internally from the fixed complete interval surface.
     *
     * @param rootRevision non-negative managed and indexed Root revision
     * @param eventOrderKey exact external event order
     * @param completeActiveIntervals complete retained active interval surface
     * @return immutable current-Root plan deriver
     */
    ExternalDeliveryPlanDeriver currentRootDeriver(
            final long rootRevision,
            final ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals) {
        if (rootRevision < 0L) {
            throw new IllegalArgumentException(
                    "rootRevision must be non-negative");
        }
        final ExternalOrderKey fixedOrder = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        final List<SubscriptionDelta.Entry> fixedIntervals =
                canonicalActiveIntervals(completeActiveIntervals);
        return new ExternalDeliveryPlanDeriver() {
            @Override
            public ExternalDeliveryPlan derive(Node root, Node event) {
                return prepareInternal(
                        root,
                        event,
                        rootRevision,
                        fixedOrder,
                        fixedIntervals,
                        null)
                        .deliveryPlan();
            }
        };
    }

    private IndexedDeliveryPreparation prepareInternal(
            Node root,
            Node event,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals,
            List<ExternalSubscriptionOccurrenceKey>
                    orderedCandidateOccurrenceKeys) {
        if (rootRevision < 0L) {
            throw new IllegalArgumentException(
                    "rootRevision must be non-negative");
        }
        final Node exactRoot = Objects.requireNonNull(
                root, "root").clone();
        final Node exactEvent = Objects.requireNonNull(
                event, "event").clone();
        final ExternalOrderKey exactOrder = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        final List<SubscriptionDelta.Entry> activeIntervals =
                canonicalActiveIntervals(completeActiveIntervals);
        final List<String> activeScopePaths = new ArrayList<>();
        for (SubscriptionDelta.Entry interval : activeIntervals) {
            activeScopePaths.add(interval.scopePath());
        }
        final List<ExternalSubscriptionOccurrenceKey> candidateKeys =
                orderedCandidateOccurrenceKeys != null
                        ? immutableCandidateKeys(
                                orderedCandidateOccurrenceKeys)
                        : null;

        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            final ProcessingSnapshotManager snapshotManager =
                    processor.snapshotManager();
            final LanguageRuntimeAccess languageRuntime =
                    processor.languageRuntimeAccess();
            if (snapshotManager != null
                    && !snapshotManager.isTransientStateCurrent()) {
                throw new IllegalStateException(
                        SNAPSHOT_GENERATION_EXPIRED);
            }
            requireReleasedGasSchedule();
            final DocumentProcessorProcessingSupport.SourceIdentityBinding identities =
                    new DocumentProcessorProcessingSupport(processor)
                            .sourceIdentities(exactRoot, exactEvent,
                                    languageRuntime, snapshotManager);
            final String rootBlueId = identities.rootBlueId();
            final String eventBlueId = identities.eventBlueId();
            ProcessingInputAdmission admission =
                    new ProcessingInputAdmission(snapshotManager, true);
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            exactRoot,
                            ProcessingInputAdmission.PROCESSING_ROOT_LABEL);
            final Node evaluationRoot = admission.materializeScopePaths(
                    admittedRoot, activeScopePaths).node();
            final Node evaluationEvent = admission.materializeTopLevel(
                    exactEvent,
                    ProcessingInputAdmission.PROCESSING_EVENT_LABEL).node();
            ExternalPreselectionVerifier preselectionVerifier =
                    new ExternalPreselectionVerifier(
                            processor.contractLoader(),
                            snapshotManager,
                            processor.registry(),
                            processor.contractConverter());
            ExternalDeliveryPlanVerifier planVerifier =
                    new ExternalDeliveryPlanVerifier(
                            preselectionVerifier);
            preselectionVerifier.verifyCompleteActiveSurface(
                    evaluationRoot, activeIntervals);
            GasMeter invocationMeter = processor.newGasMeter();
            ProcessingGasContext invocationGas =
                    new ProcessingGasContext(invocationMeter);
            ExternalPreselectionVerifier.RuntimeWorkSessionFactory
                    derivationSessions = runtimeWorkSessions(
                            evaluationEvent,
                            eventBlueId,
                            languageRuntime,
                            snapshotManager,
                            invocationGas);

            ExternalPreselectionVerifier.EvaluationResult evaluated =
                    preselectionVerifier.evaluate(
                            evaluationRoot,
                            evaluationEvent,
                            eventBlueId,
                            rootRevision,
                            exactOrder,
                            activeIntervals,
                            derivationSessions);
            if (candidateKeys != null) {
                verifyExactCandidates(
                        candidateKeys,
                        evaluated.candidates());
            }
            enforcePreselectedOccurrenceLimit(
                    evaluated.deliveries().size());

            ExternalDeliveryPlan.Builder planBuilder =
                    ExternalDeliveryPlan.builder()
                            .revisions(rootRevision, rootRevision)
                            .eventOrderKey(exactOrder)
                            .activeSubscriptionIntervals(activeIntervals)
                            .exactRuntimeState();
            for (ExternalDeliverySnapshot delivery
                    : evaluated.deliveries()) {
                planBuilder.delivery(delivery);
            }
            ExternalDeliveryPlan plan = planBuilder.build()
                    .withVerifiedBinding(
                            rootBlueId,
                            eventBlueId,
                            processor.runtimeRegistryIdentity());
            VerifiedExecutionEvidence evidence =
                    plan.verifiedBinding();

            /*
             * The replay proves determinism against the same aggregate budget,
             * but remains diagnostic: only invocationMeter is authoritative
             * admission gas for this public call.
             */
            GasMeter replayMeter = new GasMeter(
                    invocationMeter.schedule(),
                    invocationMeter.gasLimit());
            ProcessingGasContext replayGas =
                    new ProcessingGasContext(replayMeter);
            ExternalPreselectionVerifier.EvaluationResult replayed =
                    preselectionVerifier.evaluate(
                            evaluationRoot,
                            evaluationEvent,
                            eventBlueId,
                            rootRevision,
                            exactOrder,
                            activeIntervals,
                            runtimeWorkSessions(
                                    evaluationEvent,
                                    eventBlueId,
                                    languageRuntime,
                                    snapshotManager,
                                    replayGas));
            preselectionVerifier.verifyExactEvaluation(
                    evaluated, replayed);
            verifyExactGasTrace(
                    invocationMeter.trace(),
                    replayMeter.trace());
            planVerifier.verify(
                    evaluationRoot,
                    evaluationEvent,
                    evidence,
                    plan,
                    replayed);
            return new IndexedDeliveryPreparation(
                    plan, evaluated.diagnostics());
        }
    }

    private ExternalPreselectionVerifier.RuntimeWorkSessionFactory
    runtimeWorkSessions(
            final Node exactEvent,
            final String eventBlueId,
            final LanguageRuntimeAccess languageRuntime,
            final ProcessingSnapshotManager snapshotManager,
            final ProcessingGasContext gasContext) {
        final Node event = Objects.requireNonNull(
                exactEvent, "exactEvent");
        final String exactEventBlueId =
                BlueIds.requireBlueIdOrCyclicMember(
                        eventBlueId,
                        "indexed delivery event");
        Objects.requireNonNull(gasContext, "gasContext");
        return new ExternalPreselectionVerifier
                .RuntimeWorkSessionFactory() {
            @Override
            public RuntimeWorkSession open() {
                RuntimeWorkSession session = gasContext
                        .newAdmissionRuntimeWorkSession(
                                languageRuntime,
                                snapshotManager);
                session.carryExactInput(
                        event, exactEventBlueId);
                return session;
            }
        };
    }

    private void verifyExactCandidates(
            List<ExternalSubscriptionOccurrenceKey> supplied,
            List<ExternalSubscriptionOccurrenceKey> expected) {
        if (!supplied.equals(expected)) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Indexed physical candidate occurrence list does not "
                            + "match the complete evaluated subscription surface");
        }
    }

    private List<ExternalSubscriptionOccurrenceKey> immutableCandidateKeys(
            List<ExternalSubscriptionOccurrenceKey> supplied) {
        List<ExternalSubscriptionOccurrenceKey> exactSupplied =
                new ArrayList<>(Objects.requireNonNull(
                        supplied, "orderedCandidateOccurrenceKeys"));
        Set<ExternalSubscriptionOccurrenceKey> unique =
                new LinkedHashSet<>();
        for (ExternalSubscriptionOccurrenceKey key : exactSupplied) {
            ExternalSubscriptionOccurrenceKey exact =
                    Objects.requireNonNull(
                            key, "orderedCandidateOccurrenceKey");
            if (!unique.add(exact)) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Duplicate indexed physical candidate occurrence: "
                                + exact);
            }
        }
        return Collections.unmodifiableList(exactSupplied);
    }

    private List<SubscriptionDelta.Entry> canonicalActiveIntervals(
            List<SubscriptionDelta.Entry> supplied) {
        List<SubscriptionDelta.Entry> canonical =
                new ArrayList<>(Objects.requireNonNull(
                        supplied, "completeActiveIntervals"));
        Set<ExternalSubscriptionOccurrenceKey> unique =
                new LinkedHashSet<>();
        for (SubscriptionDelta.Entry entry : canonical) {
            SubscriptionDelta.Entry exact = Objects.requireNonNull(
                    entry, "active subscription interval");
            ExternalSubscriptionOccurrenceKey occurrence =
                    ExternalSubscriptionOccurrenceKey.of(
                            exact.scopePath(), exact.channelKey());
            if (!unique.add(occurrence)) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Duplicate retained External Channel occurrence at "
                                + occurrence);
            }
        }
        canonical.sort(CANONICAL_INTERVAL_ORDER);
        return Collections.unmodifiableList(canonical);
    }

    private void verifyExactGasTrace(
            List<GasTraceEntry> expected,
            List<GasTraceEntry> actual) {
        if (expected.size() != actual.size()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "Indexed delivery gas trace changed during independent "
                            + "verification");
        }
        for (int index = 0; index < expected.size(); index++) {
            GasTraceEntry left = expected.get(index);
            GasTraceEntry right = actual.get(index);
            if (!left.namespace().equals(right.namespace())
                    || !left.counter().equals(right.counter())
                    || left.quantity() != right.quantity()
                    || left.weight() != right.weight()
                    || left.subtotal() != right.subtotal()
                    || !Objects.equals(
                            left.scopePath(), right.scopePath())
                    || !Objects.equals(
                            left.contractKey(), right.contractKey())
                    || !Objects.equals(
                            left.logicalPath(), right.logicalPath())
                    || !Objects.equals(
                            left.reason(), right.reason())) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Indexed delivery gas trace changed during independent "
                                + "verification at index " + index);
            }
        }
    }

    private void enforcePreselectedOccurrenceLimit(long observed) {
        String limitName = GasScheduleConstants.PortableLimit
                .PRESELECTED_EXTERNAL_OCCURRENCES;
        long limit = processor.gasSchedule().portableLimit(limitName);
        if (observed > limit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.MatchingDeliveryLimitExceeded,
                    limitName,
                    observed,
                    limit);
        }
    }

    private void requireReleasedGasSchedule() {
        GasSchedule released = GasSchedule.contracts10();
        GasSchedule configured = processor.gasSchedule();
        if (!released.packageIdentity().equals(
                configured.packageIdentity())) {
            throw new IllegalStateException(
                    RELEASED_GAS_SCHEDULE_REQUIRED);
        }
    }

}
