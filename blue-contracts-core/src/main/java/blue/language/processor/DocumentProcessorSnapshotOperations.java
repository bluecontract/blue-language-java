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
            Node canonicalRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                return ProcessorEngine.processDocument(
                        processor, snapshot, event, null);
            }
            Node admittedEvent = support.admission()
                    .materializeTopLevel(
                            event, PROCESSING_EVENT_LABEL)
                    .node();
            VerifiedExecutionEvidence evidence =
                    support.deriveExternalDeliveryEvidence(
                            canonicalRoot, admittedEvent);
            return ProcessorEngine.processDocument(
                    processor, snapshot, admittedEvent, evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return support.invalidExternalDeliveryResult(
                    snapshot.canonicalRoot(), exception);
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
            Node canonicalRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                return ProcessorEngine.processDocument(
                        processor, snapshot, event, null);
            }
            Node admittedEvent = support.admission()
                    .materializeTopLevel(
                            event, PROCESSING_EVENT_LABEL)
                    .node();
            evidence.revalidate(
                    canonicalRoot,
                    admittedEvent,
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier());
            return ProcessorEngine.processDocument(
                    processor, snapshot, admittedEvent, evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return support.invalidExternalDeliveryResult(
                    snapshot.canonicalRoot(), exception);
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
            Node canonicalRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                evidence.revalidateBinding(
                        canonicalRoot,
                        event,
                        processor.runtimeRegistryIdentity());
            } else {
                event = support.admission()
                        .materializeTopLevel(
                                event, PROCESSING_EVENT_LABEL)
                        .node();
                evidence.revalidate(
                        canonicalRoot,
                        event,
                        processor.runtimeRegistryIdentity(),
                        processor.deliveryEvidenceVerifier());
            }
            return support.platformResult(
                    ProcessorEngine.processDocumentWithTrace(
                            processor,
                            snapshot,
                            event,
                            evidence));
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
            Node canonicalRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                return ProcessorEngine.processDocumentWithTrace(
                        processor, snapshot, event, null);
            }
            Node admittedEvent = support.admission()
                    .materializeTopLevel(
                            event, PROCESSING_EVENT_LABEL)
                    .node();
            VerifiedExecutionEvidence evidence =
                    support.deriveExternalDeliveryEvidence(
                            canonicalRoot, admittedEvent);
            return ProcessorEngine.processDocumentWithTrace(
                    processor, snapshot, admittedEvent, evidence);
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
            Node canonicalRoot =
                    support.requireProcessableSnapshotRoot(snapshot);
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                return ProcessorEngine.processDocumentWithTrace(
                        processor, snapshot, event, null);
            }
            Node admittedEvent = support.admission()
                    .materializeTopLevel(
                            event, PROCESSING_EVENT_LABEL)
                    .node();
            evidence.revalidate(
                    canonicalRoot,
                    admittedEvent,
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier());
            return ProcessorEngine.processDocumentWithTrace(
                    processor, snapshot, admittedEvent, evidence);
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
        return new ProcessingDebugResult(
                support.invalidExternalDeliveryResult(
                        snapshot.canonicalRoot(), exception),
                ProcessingConformanceTrace.empty(),
                null,
                snapshot);
    }
}
