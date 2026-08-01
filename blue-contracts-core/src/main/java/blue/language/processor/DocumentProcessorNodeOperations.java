package blue.language.processor;

import blue.language.model.Node;

import java.util.List;
import java.util.Objects;

import static blue.language.processor.ProcessingInputAdmission.PROCESSING_EVENT_LABEL;
import static blue.language.processor.ProcessingInputAdmission.PROCESSING_ROOT_LABEL;

/** Implements mutable-node processor entry points behind the public facade. */
final class DocumentProcessorNodeOperations {

    private final DocumentProcessor processor;
    private final DocumentProcessorLifecycle lifecycle;
    private final DocumentProcessorProcessingSupport support;

    DocumentProcessorNodeOperations(
            DocumentProcessor processor,
            DocumentProcessorLifecycle lifecycle,
            DocumentProcessorProcessingSupport support) {
        this.processor = processor;
        this.lifecycle = lifecycle;
        this.support = support;
    }

    /** Initializes one caller-owned mutable document. */
    DocumentProcessingResult initializeDocument(Node document) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            return ProcessorEngine.initializeDocument(
                    processor, document);
        }
    }

    /** Processes a root/event pair using a derived exact delivery plan. */
    DocumentProcessingResult processDocument(
            Node document,
            Node event) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireProcessableEvent(event);
            ProcessingInputAdmission admission = support.admission();
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, PROCESSING_ROOT_LABEL);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return support.processAdmitted(
                        admission, admittedRoot, event, null);
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, PROCESSING_EVENT_LABEL).node();
            ExternalDeliveryPlan plan =
                    support.deriveExternalDeliveryPlan(
                            admittedRoot.node(), admittedEvent);
            admittedRoot = support.admitDeliveryScopes(
                    admission, admittedRoot, plan.deliveries());
            VerifiedExecutionEvidence evidence =
                    support.bindAndVerifyDerived(
                            admittedRoot.node(), admittedEvent, plan);
            return support.processAdmitted(
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return support.invalidExternalDeliveryResult(
                    document, exception);
        }
    }

    /** Processes a root/event pair with explicit verified evidence. */
    DocumentProcessingResult processDocument(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireProcessableEvent(event);
            ProcessingInputAdmission admission = support.admission();
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, PROCESSING_ROOT_LABEL);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return support.processAdmitted(
                        admission, admittedRoot, event, null);
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, PROCESSING_EVENT_LABEL).node();
            admittedRoot = support.admitDeliveryScopes(
                    admission,
                    admittedRoot,
                    evidence.deliveries());
            evidence.revalidate(
                    admittedRoot.node(),
                    admittedEvent,
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier());
            return support.processAdmitted(
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidExplicitEvidenceResult(document, exception);
        }
    }

    /** Processes with explicit evidence and returns the atomic host companion. */
    PlatformProcessingResult processDocumentForPlatformCommit(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireProcessableEvent(event);
            ProcessingInputAdmission admission = support.admission();
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, PROCESSING_ROOT_LABEL);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                evidence.revalidateBinding(
                        admittedRoot.node(),
                        event,
                        processor.runtimeRegistryIdentity());
            } else {
                Node admittedEvent = admission.materializeTopLevel(
                        event, PROCESSING_EVENT_LABEL).node();
                admittedRoot = support.admitDeliveryScopes(
                        admission,
                        admittedRoot,
                        evidence.deliveries());
                evidence.revalidate(
                        admittedRoot.node(),
                        admittedEvent,
                        processor.runtimeRegistryIdentity(),
                        processor.deliveryEvidenceVerifier());
                event = admittedEvent;
            }
            return support.platformResult(
                    support.processAdmittedWithTrace(
                            admission,
                            admittedRoot,
                            event,
                            evidence));
        }
    }

    /** Processes with derived evidence and returns an out-of-band trace. */
    ProcessingDebugResult processDocumentWithTrace(
            Node document,
            Node event) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireProcessableEvent(event);
            ProcessingInputAdmission admission = support.admission();
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, PROCESSING_ROOT_LABEL);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return support.processAdmittedWithTrace(
                        admission, admittedRoot, event, null);
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, PROCESSING_EVENT_LABEL).node();
            ExternalDeliveryPlan plan =
                    support.deriveExternalDeliveryPlan(
                            admittedRoot.node(), admittedEvent);
            admittedRoot = support.admitDeliveryScopes(
                    admission, admittedRoot, plan.deliveries());
            VerifiedExecutionEvidence evidence =
                    support.bindAndVerifyDerived(
                            admittedRoot.node(), admittedEvent, plan);
            return support.processAdmittedWithTrace(
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return new ProcessingDebugResult(
                    support.invalidExternalDeliveryResult(
                            document, exception),
                    ProcessingConformanceTrace.empty());
        }
    }

    /** Processes with explicit evidence and returns an out-of-band trace. */
    ProcessingDebugResult processDocumentWithTrace(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireProcessableEvent(event);
            ProcessingInputAdmission admission = support.admission();
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, PROCESSING_ROOT_LABEL);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return support.processAdmittedWithTrace(
                        admission, admittedRoot, event, null);
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, PROCESSING_EVENT_LABEL).node();
            admittedRoot = support.admitDeliveryScopes(
                    admission,
                    admittedRoot,
                    evidence.deliveries());
            evidence.revalidate(
                    admittedRoot.node(),
                    admittedEvent,
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier());
            return support.processAdmittedWithTrace(
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return new ProcessingDebugResult(
                    invalidExplicitEvidenceResult(document, exception),
                    ProcessingConformanceTrace.empty());
        }
    }

    /** Attempts processing and reports exact missing resources when possible. */
    ProcessAttemptResult processAttempt(Node document, Node event) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireProcessableEvent(event);
            ProcessingInputAdmission admission = support.admission();
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, PROCESSING_ROOT_LABEL);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return ProcessAttemptResult.complete(
                        support.processAdmitted(
                                admission,
                                admittedRoot,
                                event,
                                null));
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, PROCESSING_EVENT_LABEL).node();
            ExternalDeliveryPlan plan =
                    support.deriveExternalDeliveryPlan(
                            admittedRoot.node(), admittedEvent);
            VerifiedExecutionEvidence evidence = plan.bind(
                    admittedRoot.node(),
                    admittedEvent,
                    processor.runtimeRegistryIdentity());
            return support.completeAttempt(
                    document,
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence,
                    plan);
        } catch (ExecutionEvidenceUnavailableException exception) {
            return support.needsResources(exception);
        } catch (InvalidExecutionEvidenceException exception) {
            return support.invalidAttempt(document, exception);
        }
    }

    /** Attempts processing with a previously captured evidence envelope. */
    ProcessAttemptResult processAttempt(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireProcessableEvent(event);
            support.admission().requireProcessableTopLevel(
                    document, PROCESSING_ROOT_LABEL);
            if (ProcessorEngine.hasDirectRootTerminationEntry(document)) {
                return ProcessAttemptResult.complete(
                        ProcessorEngine.processDocument(
                                processor,
                                document,
                                event,
                                null));
            }
            try {
                evidence.revalidateBinding(
                        document,
                        event,
                        processor.runtimeRegistryIdentity());
            } catch (InvalidExecutionEvidenceException exception) {
                return support.invalidAttempt(document, exception);
            }
            List<String> missing =
                    evidence.missingRequiredExactNodeBlueIds();
            if (!missing.isEmpty()) {
                return ProcessAttemptResult.needsResources(missing);
            }
            return completeExplicitAttempt(document, event, evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return support.invalidAttempt(document, exception);
        }
    }

    /** Inspects the direct initialization marker on a mutable root. */
    boolean isInitialized(Node document) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            return ProcessorEngine.isInitialized(
                    processor, document);
        }
    }

    private ProcessAttemptResult completeExplicitAttempt(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        try {
            ProcessingInputAdmission admission = support.admission();
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, PROCESSING_ROOT_LABEL);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return ProcessAttemptResult.complete(
                        support.processAdmitted(
                                admission,
                                admittedRoot,
                                event,
                                null));
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, PROCESSING_EVENT_LABEL).node();
            admittedRoot = support.admitDeliveryScopes(
                    admission,
                    admittedRoot,
                    evidence.deliveries());
            evidence.revalidate(
                    admittedRoot.node(),
                    admittedEvent,
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier());
            return ProcessAttemptResult.complete(
                    support.processAdmitted(
                            admission,
                            admittedRoot,
                            admittedEvent,
                            evidence));
        } catch (ExecutionEvidenceUnavailableException exception) {
            return support.needsResources(exception);
        } catch (InvalidExecutionEvidenceException exception) {
            return support.invalidAttempt(document, exception);
        }
    }

    private DocumentProcessingResult invalidExplicitEvidenceResult(
            Node document,
            InvalidExecutionEvidenceException exception) {
        return DocumentProcessingResult.nonCommitting(
                document,
                0L,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorDiagnostic.of(
                        exception.errorCategory(),
                        exception.getMessage()));
    }
}
