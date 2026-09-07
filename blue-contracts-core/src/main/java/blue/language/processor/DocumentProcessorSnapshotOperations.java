package blue.language.processor;

import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;

import java.util.Objects;

import static blue.language.processor.ProcessingInputAdmission.PROCESSING_EVENT_LABEL;

/** Implements verified-snapshot entry points behind the public facade. */
final class DocumentProcessorSnapshotOperations {

    private final DocumentProcessor processor;
    private final DocumentProcessorLifecycle lifecycle;
    private final DocumentProcessorProcessingSupport support;

    DocumentProcessorSnapshotOperations(
            DocumentProcessor processor,
            DocumentProcessorLifecycle lifecycle,
            DocumentProcessorProcessingSupport support) {
        this.processor = processor;
        this.lifecycle = lifecycle;
        this.support = support;
    }

    /** Initializes the resolved view while retaining its canonical companion. */
    DocumentProcessingResult initializeDocument(
            ResolvedSnapshot snapshot) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireSnapshotManager();
            support.requireProcessableSnapshotRoot(snapshot);
            return ProcessorEngine.initializeDocument(
                    processor, snapshot);
        }
    }

    /** Processes a snapshot with a derived exact delivery plan. */
    DocumentProcessingResult processDocument(
            ResolvedSnapshot snapshot,
            Node event) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireSnapshotManager();
            support.requireProcessableEvent(event);
            Node selectedRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    selectedRoot)) {
                return ProcessorEngine.processDocument(
                        processor, snapshot, event, null);
            }
            DocumentProcessorProcessingSupport.SourceIdentityBinding
                    sourceIdentities = support.sourceIdentities(
                            snapshot,
                            event,
                            processor.languageRuntimeAccess(),
                            processor.snapshotManager());
            Node admittedEvent = support.admission()
                    .materializeTopLevel(
                            event, PROCESSING_EVENT_LABEL)
                    .node();
            VerifiedExecutionEvidence evidence =
                    support.deriveExternalDeliveryEvidence(
                            selectedRoot,
                            admittedEvent,
                            sourceIdentities);
            return ProcessorEngine.processDocument(
                    processor, snapshot, admittedEvent, evidence);
        } catch (SubscriptionSurfaceInvalidException exception) {
            return support.subscriptionSurfaceInvalidResult(
                    snapshot.sourceRoot(), exception);
        } catch (PortableLimitExceededException exception) {
            return support.portableLimitResult(
                    snapshot.sourceRoot(), exception);
        } catch (InvalidExecutionEvidenceException exception) {
            return support.invalidExternalDeliveryResult(
                    snapshot.sourceRoot(), exception);
        }
    }

    /** Processes a snapshot with explicit revision-bound evidence. */
    DocumentProcessingResult processDocument(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evidence, "evidence");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireSnapshotManager();
            support.requireProcessableEvent(event);
            Node selectedRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    selectedRoot)) {
                return ProcessorEngine.processDocument(
                        processor, snapshot, event, null);
            }
            DocumentProcessorProcessingSupport.SourceIdentityBinding
                    sourceIdentities = support.sourceIdentities(
                            snapshot,
                            event,
                            processor.languageRuntimeAccess(),
                            processor.snapshotManager());
            Node admittedEvent = support.admission()
                    .materializeTopLevel(
                            event, PROCESSING_EVENT_LABEL)
                    .node();
            evidence.revalidateEstablished(
                    selectedRoot,
                    admittedEvent,
                    sourceIdentities.rootBlueId(),
                    sourceIdentities.eventBlueId(),
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier());
            return ProcessorEngine.processDocument(
                    processor, snapshot, admittedEvent, evidence);
        } catch (SubscriptionSurfaceInvalidException exception) {
            return support.subscriptionSurfaceInvalidResult(
                    snapshot.sourceRoot(), exception);
        } catch (PortableLimitExceededException exception) {
            return support.portableLimitResult(
                    snapshot.sourceRoot(), exception);
        } catch (InvalidExecutionEvidenceException exception) {
            return support.invalidExternalDeliveryResult(
                    snapshot.sourceRoot(), exception);
        }
    }

    /** Processes a snapshot and returns its atomic host commit companion. */
    PlatformProcessingResult processDocumentForPlatformCommit(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evidence, "evidence");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireSnapshotManager();
            support.requireProcessableEvent(event);
            Node selectedRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            DocumentProcessorProcessingSupport.SourceIdentityBinding
                    sourceIdentities = support.sourceIdentities(
                            snapshot,
                            event,
                            processor.languageRuntimeAccess(),
                            processor.snapshotManager());
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    selectedRoot)) {
                evidence.revalidateBinding(
                        sourceIdentities.rootBlueId(),
                        sourceIdentities.eventBlueId(),
                        processor.runtimeRegistryIdentity());
            } else {
                event = support.admission()
                        .materializeTopLevel(
                                event, PROCESSING_EVENT_LABEL)
                        .node();
                evidence.revalidateEstablished(
                        selectedRoot,
                        event,
                        sourceIdentities.rootBlueId(),
                        sourceIdentities.eventBlueId(),
                        processor.runtimeRegistryIdentity(),
                        processor.deliveryEvidenceVerifier());
            }
            return support.platformResult(
                    ProcessorEngine.processDocumentWithTrace(
                            processor,
                            snapshot,
                            event,
                            evidence));
        } catch (SubscriptionSurfaceInvalidException exception) {
            return support.platformFailure(
                    evidence,
                    support.subscriptionSurfaceInvalidResult(
                            snapshot.sourceRoot(), exception));
        } catch (PortableLimitExceededException exception) {
            return support.platformFailure(
                    evidence,
                    support.portableLimitResult(
                            snapshot.sourceRoot(), exception));
        }
    }

    /** Processes a snapshot with derived evidence and returns a trace. */
    ProcessingDebugResult processDocumentWithTrace(
            ResolvedSnapshot snapshot,
            Node event) {
        Objects.requireNonNull(snapshot, "snapshot");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireSnapshotManager();
            support.requireProcessableEvent(event);
            Node selectedRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    selectedRoot)) {
                return ProcessorEngine.processDocumentWithTrace(
                        processor, snapshot, event, null);
            }
            DocumentProcessorProcessingSupport.SourceIdentityBinding
                    sourceIdentities = support.sourceIdentities(
                            snapshot,
                            event,
                            processor.languageRuntimeAccess(),
                            processor.snapshotManager());
            Node admittedEvent = support.admission()
                    .materializeTopLevel(
                            event, PROCESSING_EVENT_LABEL)
                    .node();
            VerifiedExecutionEvidence evidence =
                    support.deriveExternalDeliveryEvidence(
                            selectedRoot,
                            admittedEvent,
                            sourceIdentities);
            return ProcessorEngine.processDocumentWithTrace(
                    processor, snapshot, admittedEvent, evidence);
        } catch (SubscriptionSurfaceInvalidException exception) {
            return failureTrace(
                    snapshot,
                    support.subscriptionSurfaceInvalidResult(
                            snapshot.sourceRoot(), exception));
        } catch (PortableLimitExceededException exception) {
            return failureTrace(
                    snapshot,
                    support.portableLimitResult(
                            snapshot.sourceRoot(), exception));
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidTrace(snapshot, exception);
        }
    }

    /** Processes a snapshot with explicit evidence and returns a trace. */
    ProcessingDebugResult processDocumentWithTrace(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evidence, "evidence");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            support.requireSnapshotManager();
            support.requireProcessableEvent(event);
            Node selectedRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    selectedRoot)) {
                return ProcessorEngine.processDocumentWithTrace(
                        processor, snapshot, event, null);
            }
            DocumentProcessorProcessingSupport.SourceIdentityBinding
                    sourceIdentities = support.sourceIdentities(
                            snapshot,
                            event,
                            processor.languageRuntimeAccess(),
                            processor.snapshotManager());
            Node admittedEvent = support.admission()
                    .materializeTopLevel(
                            event, PROCESSING_EVENT_LABEL)
                    .node();
            evidence.revalidateEstablished(
                    selectedRoot,
                    admittedEvent,
                    sourceIdentities.rootBlueId(),
                    sourceIdentities.eventBlueId(),
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier());
            return ProcessorEngine.processDocumentWithTrace(
                    processor, snapshot, admittedEvent, evidence);
        } catch (SubscriptionSurfaceInvalidException exception) {
            return failureTrace(
                    snapshot,
                    support.subscriptionSurfaceInvalidResult(
                            snapshot.sourceRoot(), exception));
        } catch (PortableLimitExceededException exception) {
            return failureTrace(
                    snapshot,
                    support.portableLimitResult(
                            snapshot.sourceRoot(), exception));
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidTrace(snapshot, exception);
        }
    }

    /** Inspects the direct initialization marker on a snapshot root. */
    boolean isInitialized(ResolvedSnapshot snapshot) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            return ProcessorEngine.isInitialized(
                    processor, snapshot);
        }
    }

    private ProcessingDebugResult invalidTrace(
            ResolvedSnapshot snapshot,
            InvalidExecutionEvidenceException exception) {
        return failureTrace(
                snapshot,
                support.invalidExternalDeliveryResult(
                        snapshot.sourceRoot(), exception));
    }

    private ProcessingDebugResult failureTrace(
            ResolvedSnapshot snapshot,
            DocumentProcessingResult result) {
        return new ProcessingDebugResult(
                result,
                ProcessingConformanceTrace.empty(),
                null,
                snapshot);
    }
}
