package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;

/**
 * Internal orchestration kernel for one initialization or PROCESS invocation.
 *
 * <p>The engine owns phase ordering, scope traversal, gas, checkpoints,
 * buffered effects, and rollback. Public entry points retain the supplied
 * document on deterministic pre-execution failures and publish state only
 * through a completed {@link ProcessorInvocationState}.</p>
 */
final class ProcessorEngine {
    private ProcessorEngine() {
    }
    static DocumentProcessingResult initializeDocument(DocumentProcessor owner, Node document) {
        return ProcessorInvocationOrchestrator.initialize(owner, document);
    }

    static DocumentProcessingResult initializeDocument(DocumentProcessor owner, ResolvedSnapshot snapshot) {
        return ProcessorInvocationOrchestrator.initialize(owner, snapshot);
    }

    static DocumentProcessingResult processDocument(DocumentProcessor owner, Node document, Node event) {
        return processDocument(owner, document, event, null);
    }

    static DocumentProcessingResult processDocument(
            DocumentProcessor owner, Node document, Node event,
            VerifiedExecutionEvidence evidence) {
        return processDocumentWithTrace(owner, document, event, evidence).processResult();
    }

    static ProcessingDebugResult processDocumentWithTrace(
            DocumentProcessor owner, Node document, Node event,
            VerifiedExecutionEvidence evidence) {
        return ProcessorInvocationOrchestrator.process(owner, document, event, evidence);
    }

    static String deterministicMessage(
            Throwable throwable,
            String fallback) {
        String message = throwable != null ? throwable.getMessage() : null;
        return message != null && !message.isEmpty() ? message : fallback;
    }

    static DocumentProcessingResult processDocument(
            DocumentProcessor owner, ResolvedSnapshot snapshot, Node event) {
        return processDocument(owner, snapshot, event, null);
    }

    static DocumentProcessingResult processDocument(
            DocumentProcessor owner, ResolvedSnapshot snapshot, Node event,
            VerifiedExecutionEvidence evidence) {
        return processDocumentWithTrace(owner, snapshot, event, evidence).processResult();
    }

    static ProcessingDebugResult processDocumentWithTrace(
            DocumentProcessor owner, ResolvedSnapshot snapshot, Node event,
            VerifiedExecutionEvidence evidence) {
        return ProcessorInvocationOrchestrator.process(owner, snapshot, event, evidence);
    }

    static boolean isInitialized(DocumentProcessor owner, Node document) {
        return ProcessorMarkerStore.isInitialized(document);
    }

    static boolean isInitialized(DocumentProcessor owner, ResolvedSnapshot snapshot) {
        return ProcessorMarkerStore.isInitialized(snapshot);
    }

    static String resolvePointer(String scopePath, String relativePointer) {
        return PointerUtils.resolvePointer(scopePath, relativePointer);
    }

    static String normalizeScope(String scopePath) {
        return PointerUtils.normalizeScope(scopePath);
    }

    static String normalizePointer(String pointer) {
        return PointerUtils.normalizePointer(pointer);
    }

    static String relativizePointer(String scopePath, String absolutePath) {
        return PointerUtils.relativizePointer(scopePath, absolutePath);
    }

    static Node createLifecycleInitiatedEvent(FrozenNode document) {
        return LifecycleEventFactory.initiated(document);
    }

    static String canonicalSignature(Node node) {
        return CheckpointIdentityCalculator.canonicalSignature(node);
    }

    static Node createDocumentUpdateEvent(
            DocumentUpdateData data,
            String scopePath) {
        return LifecycleEventFactory.documentUpdate(data, scopePath);
    }

    static boolean matchesDocumentUpdate(String scopePath, String watchPath, String changedPath) {
        if (watchPath == null || watchPath.isEmpty()) {
            return false;
        }
        String watch = PointerUtils.normalizePointer(PointerUtils.resolvePointer(scopePath, watchPath));
        String changed = PointerUtils.normalizePointer(changedPath);
        return PointerUtils.descendantOrEqual(changed, watch);
    }

    static Node nodeAt(Node root, String pointer) {
        return ProcessorMarkerStore.nodeAt(root, pointer);
    }

    static TerminationMarker terminationMarker(Node root, String scopePath) {
        ProcessorMarkerStore.TerminationMarker marker =
                ProcessorMarkerStore.terminationMarker(root, scopePath);
        return marker != null
                ? new TerminationMarker(marker.cause, marker.reason)
                : null;
    }

    static boolean hasDirectRootTerminationEntry(Node root) {
        return ProcessorMarkerStore.hasDirectRootTerminationEntry(root);
    }

    static void validateInitializationMarker(Node marker, String pointer) {
        ProcessorMarkerStore.validateInitializationMarker(marker, pointer);
    }

    static TerminationMarker validateTerminationMarker(Node marker, String pointer) {
        ProcessorMarkerStore.TerminationMarker validated =
                ProcessorMarkerStore.validateTerminationMarker(
                        marker, pointer);
        return validated != null
                ? new TerminationMarker(validated.cause, validated.reason)
                : null;
    }

    /**
     * First graceful-termination request retained for deterministic replay and
     * marker publication.
     */
    static final class TerminationMarker {
        final String cause;
        final String reason;

        TerminationMarker(String cause, String reason) {
            this.cause = cause;
            this.reason = reason;
        }
    }

    /** Freezes one process-event source at the runtime's evidence boundary. */
    @FunctionalInterface
    interface ProcessEventSnapshotFactory {

        /**
         * Returns the immutable process-event snapshot used for one attempt.
         *
         * @param processEventSource mutable event source
         * @return immutable frozen event snapshot
         */
        FrozenNode freeze(Node processEventSource);
    }

    @SuppressWarnings("unchecked")
    static void executeHandler(DocumentProcessor owner, HandlerContract contract, ProcessorExecutionContext context) {
        HandlerProcessor<? extends HandlerContract> processor = owner.registry()
                .lookupHandler(contract)
                .orElseThrow(() -> new IllegalStateException(
                        "No processor registered for contract type " + contract.getTypeBlueId()));
        HandlerProcessor<HandlerContract> typed = (HandlerProcessor<HandlerContract>) processor;
        typed.execute(contract, context);
    }

    @SuppressWarnings("unchecked")
    static boolean matchesHandler(DocumentProcessor owner,
                                  HandlerContract contract,
                                  HandlerMatchContext context) {
        HandlerProcessor<? extends HandlerContract> processor = owner.registry()
                .lookupHandler(contract)
                .orElseThrow(() -> new IllegalStateException(
                        "No processor registered for contract type " + contract.getTypeBlueId()));
        HandlerProcessor<HandlerContract> typed = (HandlerProcessor<HandlerContract>) processor;
        return typed.matches(contract, context);
    }

    static final class BoundaryViolationException extends RuntimeException {
        BoundaryViolationException(String message) {
            super(message);
        }
    }
}
