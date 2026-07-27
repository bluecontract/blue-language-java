package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorPointerConstants;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Handles termination requests and defers their marker commit until the
 * invocation event FIFO reaches quiescence.
 */
final class TerminationService {

    private final DocumentProcessingRuntime runtime;
    private final Deque<PendingTermination> pending =
            new ArrayDeque<>();

    TerminationService(DocumentProcessingRuntime runtime) {
        this.runtime = runtime;
    }

    void terminateScope(ProcessorEngine.Execution execution,
                        String scopePath,
                        ContractBundle bundle,
                        String cause,
                        String reason) {
        String normalized = execution.normalizeScope(scopePath);
        if (cause == null || cause.isEmpty()) {
            execution.abortRuntimeFailure(
                    normalized,
                    bundle,
                    ProcessorErrorCategory.RuntimeExecutionFailure,
                    "Termination cause must be non-empty Text");
            return;
        }
        ContractBundle bundleRef = bundle != null ? bundle : execution.bundleForScope(normalized);
        pending.addLast(new PendingTermination(
                normalized,
                bundleRef,
                cause,
                reason));
        Node lifecycleEvent = createTerminationLifecycleEvent(cause, reason);
        execution.deliverTerminationLifecycle(normalized, bundleRef, lifecycleEvent);
        /*
         * The accepted occurrence is a completed business transition even
         * when lifecycle work cuts off the old scope before its marker can be
         * written. Any later deterministic failure still wins in result
         * selection and rolls the invocation back.
         */
        execution.recordCompletedDelivery();
        execution.requestInternalEventDrain();
    }

    void completePendingTerminations(
            ProcessorEngine.Execution execution) {
        while (!pending.isEmpty()) {
            PendingTermination transition = pending.pollFirst();
            if (!execution.canCompleteTermination(
                    transition.scopePath)) {
                continue;
            }
        /*
         * The termination marker is the commit point for the transition.
         * Lifecycle handlers and the FIFO they populate must finish first so
         * observers never see a terminated marker while termination effects
         * are still pending.
         */
            Node marker = createTerminationMarker(
                    transition.cause,
                    transition.reason);
            runtime.chargeTerminationMarker();
            if (!writeTerminationMarker(
                    transition.scopePath, marker)) {
                execution.abortRuntimeFailure(
                        transition.scopePath,
                        transition.bundle,
                        ProcessorErrorCategory.RuntimeExecutionFailure,
                        "Unable to write terminated marker at scope "
                                + transition.scopePath);
                return;
            }

            ScopeRuntimeContext scopeContext =
                    runtime.scope(transition.scopePath);
            scopeContext.finalizeTermination(
                    transition.reason);

            if ("/".equals(transition.scopePath)) {
                execution.recordRootTermination();
                runtime.markRunTerminated();
                throw new RunTerminationException();
            }
        }
    }

    private boolean writeTerminationMarker(String scopePath, Node marker) {
        String markerPointer = ProcessorEngine.resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_TERMINATED);
        try {
            runtime.directWrite(markerPointer, marker);
            return true;
        } catch (RuntimeException markerFailure) {
            return false;
        }
    }

    private Node createTerminationMarker(String cause, String reason) {
        Node marker = new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESSING_TERMINATED_MARKER))
                .properties("cause", new Node().value(cause));
        if (reason != null && !reason.isEmpty()) {
            marker.properties("reason", new Node().value(reason));
        }
        return marker;
    }

    private Node createTerminationLifecycleEvent(String cause, String reason) {
        Node event = new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));
        event.properties("cause", new Node().value(cause));
        if (reason != null && !reason.isEmpty()) {
            event.properties("reason", new Node().value(reason));
        }
        return event;
    }

    private static final class PendingTermination {
        private final String scopePath;
        private final ContractBundle bundle;
        private final String cause;
        private final String reason;

        private PendingTermination(String scopePath,
                                   ContractBundle bundle,
                                   String cause,
                                   String reason) {
            this.scopePath = scopePath;
            this.bundle = bundle;
            this.cause = cause;
            this.reason = reason;
        }
    }
}
