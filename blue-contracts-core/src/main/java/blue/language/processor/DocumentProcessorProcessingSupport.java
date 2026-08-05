package blue.language.processor;

import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static blue.language.processor.ProcessingInputAdmission.PROCESSING_EVENT_LABEL;
import static blue.language.processor.ProcessingInputAdmission.PROCESSING_ROOT_LABEL;

/** Shared evidence, admission, and result mechanics for PROCESS entry points. */
final class DocumentProcessorProcessingSupport {

    private static final String INVALID_EXTERNAL_DELIVERY_MESSAGE =
            "Invalid external delivery evidence";
    private static final String INCOMPLETE_DELIVERY_PLAN_MESSAGE =
            "External delivery plan is not certified complete";
    private static final String MISSING_SNAPSHOT_MANAGER_MESSAGE =
            "Snapshot-native processing requires a ProcessingSnapshotManager";

    private final DocumentProcessor processor;

    DocumentProcessorProcessingSupport(DocumentProcessor processor) {
        this.processor = processor;
    }

    ProcessingInputAdmission admission() {
        return new ProcessingInputAdmission(
                processor.snapshotManager());
    }

    ProcessingInputAdmission admission(
            ProcessorInvocationServices services) {
        return new ProcessingInputAdmission(
                Objects.requireNonNull(services, "services")
                        .snapshotManager());
    }

    ProcessingSnapshotManager requireSnapshotManager() {
        ProcessingSnapshotManager manager =
                processor.snapshotManager();
        if (manager == null) {
            throw new IllegalStateException(
                    MISSING_SNAPSHOT_MANAGER_MESSAGE);
        }
        return manager;
    }

    void requireProcessableEvent(Node event) {
        admission().requireProcessableTopLevel(
                event, PROCESSING_EVENT_LABEL);
    }

    Node requireProcessableSnapshotRoot(ResolvedSnapshot snapshot) {
        Node canonicalRoot = Objects.requireNonNull(
                snapshot, "snapshot").canonicalRoot();
        admission().requireProcessableTopLevel(
                canonicalRoot, PROCESSING_ROOT_LABEL);
        return canonicalRoot;
    }

    VerifiedExecutionEvidence deriveExternalDeliveryEvidence(
            Node document,
            Node event) {
        ExternalDeliveryPlan plan =
                deriveExternalDeliveryPlan(document, event);
        return bindAndVerifyDerived(document, event, plan);
    }

    VerifiedExecutionEvidence bindAndVerifyDerived(
            Node document,
            Node event,
            ExternalDeliveryPlan plan) {
        VerifiedExecutionEvidence evidence = plan.bind(
                document,
                event,
                processor.runtimeRegistryIdentity());
        evidence.revalidateDerived(
                document,
                event,
                processor.runtimeRegistryIdentity(),
                processor.deliveryEvidenceVerifier(),
                plan);
        return evidence;
    }

    VerifiedExecutionEvidence verifySuppliedPlan(
            Node document,
            Node event,
            ExternalDeliveryPlan plan,
            VerifiedExecutionEvidence evidence,
            ProcessorInvocationServices services) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(evidence, "evidence")
                .revalidateBinding(
                        document,
                        event,
                        services.runtimeRegistryIdentity());
        establishRequiredExactResources(plan, services);
        ExternalDeliveryEvidenceVerifier verifier =
                services.deliveryEvidenceVerifier();
        if (verifier instanceof RootExternalDeliveryEvidenceVerifier) {
            ((RootExternalDeliveryEvidenceVerifier) verifier)
                    .verifyDerived(
                            document,
                            event,
                            evidence,
                            plan,
                            services.externalPlanVerificationSessions(
                                    event));
        } else {
            verifier.verifyDerived(
                    document, event, evidence, plan);
        }
        return evidence;
    }

    /**
     * Establishes the plan's declared exact-resource closure through this
     * invocation's isolated provider domain before semantic execution.
     */
    private void establishRequiredExactResources(
            ExternalDeliveryPlan plan,
            ProcessorInvocationServices services) {
        List<String> required = new ArrayList<>(
                plan.requiredExactNodeBlueIds());
        Collections.sort(required);
        ProcessingSnapshotManager manager =
                Objects.requireNonNull(
                        services.snapshotManager(),
                        "invocation snapshotManager");
        for (String blueId : required) {
            FrozenNode established = manager.materializeVerifiedExactReference(
                    FrozenNode.fromNode(new Node().blueId(blueId)));
            if (established == null) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Required exact provider content is definitively "
                                + "absent for " + blueId);
            }
        }
    }

    ExternalDeliveryPlan deriveExternalDeliveryPlan(
            Node document,
            Node event) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(event, "event");
        ExternalDeliveryEvidenceVerifier verifier =
                processor.deliveryEvidenceVerifier();
        ExternalDeliveryPlan plan =
                verifier instanceof RootExternalDeliveryEvidenceVerifier
                        ? ((RootExternalDeliveryEvidenceVerifier) verifier)
                                .derivePlan(document, event)
                        : processor.externalDeliveryPlanDeriver().derive(
                                document.clone(), event.clone());
        if (plan == null || !plan.exactRuntimeState()) {
            throw new InvalidExecutionEvidenceException(
                    INCOMPLETE_DELIVERY_PLAN_MESSAGE);
        }
        return plan;
    }

    ProcessingInputAdmission.AdmittedNode admitDeliveryScopes(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            List<ExternalDeliverySnapshot> deliveries) {
        List<String> scopePaths = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery : deliveries) {
            scopePaths.add(delivery.scopePath());
        }
        return admission.materializeScopePaths(
                admittedRoot, scopePaths);
    }

    DocumentProcessingResult processAdmitted(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocument(
                    processor,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocument(
                processor, admittedRoot.node(), event, evidence);
    }

    DocumentProcessingResult processAdmitted(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence,
            ProcessorInvocationServices services) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocument(
                    services,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocument(
                services,
                admittedRoot.node(),
                event,
                evidence);
    }

    ProcessingDebugResult processAdmittedWithTrace(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocumentWithTrace(
                    processor,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocumentWithTrace(
                processor, admittedRoot.node(), event, evidence);
    }

    ProcessingDebugResult processAdmittedWithTrace(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence,
            ProcessorInvocationServices services) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocumentWithTrace(
                    services,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocumentWithTrace(
                services,
                admittedRoot.node(),
                event,
                evidence);
    }

    ProcessAttemptResult completeAttempt(
            Node originalDocument,
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan derivedPlan) {
        try {
            evidence.revalidateBinding(
                    admittedRoot.node(),
                    event,
                    processor.runtimeRegistryIdentity());
            List<String> missing =
                    evidence.missingRequiredExactNodeBlueIds();
            if (!missing.isEmpty()) {
                return ProcessAttemptResult.needsResources(missing);
            }
            admittedRoot = admitDeliveryScopes(
                    admission,
                    admittedRoot,
                    derivedPlan.deliveries());
            evidence.revalidateDerived(
                    admittedRoot.node(),
                    event,
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier(),
                    derivedPlan);
            return ProcessAttemptResult.complete(
                    processAdmitted(
                            admission,
                            admittedRoot,
                            event,
                            evidence));
        } catch (ExecutionEvidenceUnavailableException exception) {
            return needsResources(exception);
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidAttempt(originalDocument, exception);
        }
    }

    ProcessAttemptResult needsResources(
            ExecutionEvidenceUnavailableException exception) {
        if (exception.requiredExactBlueIds().isEmpty()) {
            throw exception;
        }
        return ProcessAttemptResult.needsResources(
                exception.requiredExactBlueIds());
    }

    ProcessAttemptResult invalidAttempt(
            Node document,
            InvalidExecutionEvidenceException exception) {
        return ProcessAttemptResult.complete(
                DocumentProcessingResult.nonCommitting(
                        document,
                        0L,
                        ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                        ProcessorDiagnostic.of(
                                exception.errorCategory(),
                                exception.getMessage())));
    }

    DocumentProcessingResult invalidExternalDeliveryResult(
            Node document,
            InvalidExecutionEvidenceException exception) {
        return DocumentProcessingResult.nonCommitting(
                Objects.requireNonNull(document, "document"),
                0L,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorDiagnostic.of(
                        exception.errorCategory(),
                        ProcessorEngine.deterministicMessage(
                                exception,
                                INVALID_EXTERNAL_DELIVERY_MESSAGE)));
    }

    DocumentProcessingResult subscriptionSurfaceInvalidResult(
            Node document,
            SubscriptionSurfaceInvalidException exception) {
        return DocumentProcessingResult.nonCommitting(
                Objects.requireNonNull(document, "document"),
                0L,
                ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                exception.diagnostic());
    }

    DocumentProcessingResult portableLimitResult(
            Node document,
            PortableLimitExceededException exception) {
        return DocumentProcessingResult.nonCommitting(
                Objects.requireNonNull(document, "document"),
                0L,
                ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                exception.diagnostic());
    }

    PlatformProcessingResult platformFailure(
            VerifiedExecutionEvidence evidence,
            DocumentProcessingResult result) {
        return new PlatformProcessingResult(
                result,
                PlatformCommitCompanion.of(
                        Objects.requireNonNull(evidence, "evidence"),
                        result,
                        SubscriptionDelta.empty()));
    }

    PlatformProcessingResult platformResult(ProcessingDebugResult debug) {
        PlatformCommitCompanion companion =
                debug.platformCommitCompanion();
        if (companion == null) {
            throw new IllegalStateException(
                    "Revision-bound execution produced no platform commit companion");
        }
        return new PlatformProcessingResult(
                debug.processResult(), companion);
    }
}
