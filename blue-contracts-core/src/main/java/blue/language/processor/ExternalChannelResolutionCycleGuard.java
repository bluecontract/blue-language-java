package blue.language.processor;

import java.util.ArrayDeque;
import java.util.Deque;

/** Maintains independent deterministic stacks for header and event recursion. */
final class ExternalChannelResolutionCycleGuard {

    private final Deque<String> resolvingHeaders = new ArrayDeque<>();
    private final Deque<String> evaluatingEvents = new ArrayDeque<>();

    void enterHeader(String key) {
        enter(resolvingHeaders, key, "dependency");
    }

    void leaveHeader() {
        resolvingHeaders.removeLast();
    }

    void enterEvent(String key) {
        enter(evaluatingEvents, key, "event-evaluation");
    }

    void leaveEvent() {
        evaluatingEvents.removeLast();
    }

    IllegalStateException cycle(
            String from,
            String to,
            String phase) {
        return new IllegalStateException(
                "Cyclic same-scope External Channel dependency during "
                        + phase + ": " + from + " -> " + to);
    }

    private void enter(
            Deque<String> stack,
            String key,
            String phase) {
        long depthLimit = ExternalChannelFunctionRules.portableLimit(
                GasScheduleConstants.PortableLimit.EMBEDDED_DEPTH);
        if (stack.size() >= depthLimit) {
            throw new IllegalStateException(
                    "External Channel " + phase
                            + " depth exceeds " + depthLimit);
        }
        if (stack.contains(key)) {
            throw cycle(stack.peekLast(), key, phase);
        }
        stack.addLast(key);
    }
}
