package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
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
        Node marker = createTerminationMarker(kind, reason);
        try {
            runtime.directWrite(pointer, marker);
        } catch (RuntimeException ex) {
            String contractsPointer = ProcessorEngine.resolvePointer(normalized,
                    ProcessorPointerConstants.RELATIVE_CONTRACTS);
            Node contracts = new Node().properties(ProcessorContractConstants.KEY_TERMINATED, marker);
            try {
                runtime.directWrite(contractsPointer, contracts);
            } catch (RuntimeException fallbackFailure) {
                Node replacement = runtime.document().clone();
                replacement.contracts(contracts);
                runtime.replaceDocument(replacement);
            }
        }
        if (runtime.nodeAt(pointer) == null) {
            Node replacement = runtime.document().clone();
            replacement.contracts(new Node().properties(ProcessorContractConstants.KEY_TERMINATED, marker));
            runtime.replaceDocument(replacement);
        }
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
                .type(new Node().blueId(RuntimeBlueIds.PROCESSING_TERMINATED_MARKER))
                .properties("cause", new Node().value(kind == ScopeRuntimeContext.TerminationKind.GRACEFUL ? "graceful" : "fatal"));
        if (reason != null && !reason.isEmpty()) {
            marker.properties("reason", new Node().value(reason));
        }
        return marker;
    }

    private Node createTerminationLifecycleEvent(ScopeRuntimeContext.TerminationKind kind, String reason) {
        Node event = new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));
        event.properties("cause", new Node().value(kind == ScopeRuntimeContext.TerminationKind.GRACEFUL ? "graceful" : "fatal"));
        if (reason != null && !reason.isEmpty()) {
            event.properties("reason", new Node().value(reason));
        }
        return event;
    }

    private Node createFatalOutboxEvent(String scopePath, String reason) {
        Node event = new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR));
        if (reason != null && !reason.isEmpty()) {
            event.properties("reason", new Node().value(reason));
        }
        return event;
    }
}
