package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.ProcessorPointerConstants;

/**
 * Handles termination markers, lifecycle emission, and run termination bookkeeping.
 */
final class TerminationService {

    private final DocumentProcessingRuntime runtime;

    TerminationService(DocumentProcessingRuntime runtime) {
        this.runtime = runtime;
    }

    void terminateScope(ProcessorEngine.Execution execution,
                        String scopePath,
                        ContractBundle bundle,
                        ScopeRuntimeContext.TerminationKind kind,
                        String reason) {
        execution.recordPendingTermination(scopePath, kind, reason);

        String normalized = execution.normalizeScope(scopePath);
        String pointer = ProcessorEngine.resolvePointer(normalized, ProcessorPointerConstants.RELATIVE_TERMINATED);
        runtime.directWrite(pointer, createTerminationMarker(kind, reason));
        runtime.chargeTerminationMarker();

        ContractBundle bundleRef = bundle != null ? bundle : execution.bundleForScope(normalized);
        Node lifecycleEvent = createTerminationLifecycleEvent(kind, reason);
        execution.deliverLifecycle(normalized, bundleRef, lifecycleEvent, false);

        ScopeRuntimeContext scopeContext = runtime.scope(normalized);
        scopeContext.finalizeTermination(kind, reason);
        execution.clearPendingTermination(scopePath);

        if (ScopeRuntimeContext.TerminationKind.FATAL.equals(kind)) {
            runtime.chargeFatalTerminationOverhead();
        }

        if (ScopeRuntimeContext.TerminationKind.FATAL.equals(kind) && "/".equals(normalized)) {
            runtime.recordRootEmission(createFatalOutboxEvent(normalized, reason));
            runtime.markRunTerminated();
            throw new RunTerminationException(true);
        }

        if (ScopeRuntimeContext.TerminationKind.GRACEFUL.equals(kind) && "/".equals(normalized)) {
            runtime.markRunTerminated();
            throw new RunTerminationException(false);
        }
    }

    private Node createTerminationMarker(ScopeRuntimeContext.TerminationKind kind, String reason) {
        Node marker = new Node()
                .type(new Node().blueId("ProcessingTerminatedMarker"))
                .properties("cause", new Node().value(kind == ScopeRuntimeContext.TerminationKind.GRACEFUL ? "graceful" : "fatal"));
        if (reason != null && !reason.isEmpty()) {
            marker.properties("reason", new Node().value(reason));
        }
        return marker;
    }

    private Node createTerminationLifecycleEvent(ScopeRuntimeContext.TerminationKind kind, String reason) {
        Node event = new Node().properties("type", new Node().value("Document Processing Terminated"));
        event.properties("cause", new Node().value(kind == ScopeRuntimeContext.TerminationKind.GRACEFUL ? "graceful" : "fatal"));
        if (reason != null && !reason.isEmpty()) {
            event.properties("reason", new Node().value(reason));
        }
        return event;
    }

    private Node createFatalOutboxEvent(String scopePath, String reason) {
        Node event = new Node().properties("type", new Node().value("Document Processing Fatal Error"));
        event.properties("domain", new Node().value(scopePath));
        event.properties("code", new Node().value("RuntimeFatal"));
        if (reason != null && !reason.isEmpty()) {
            event.properties("reason", new Node().value(reason));
        }
        return event;
    }
}
