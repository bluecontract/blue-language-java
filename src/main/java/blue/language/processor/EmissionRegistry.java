package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Invocation-local owner of scope state and pending event occurrences.
 *
 * <p>The deque is the single global FIFO across scopes. Root emissions retain
 * public output order separately, and removing a scope never rewrites already
 * queued occurrence order.</p>
 */
final class EmissionRegistry {

    private final Map<String, ScopeRuntimeContext> scopes = new LinkedHashMap<>();
    private final List<Node> rootEmissions = new ArrayList<>();
    private final Deque<EventOccurrence> eventQueue = new ArrayDeque<>();
    private long enqueuedOccurrences;

    Map<String, ScopeRuntimeContext> scopes() {
        return scopes;
    }

    ScopeRuntimeContext scope(String scopePath) {
        return scopes.computeIfAbsent(scopePath, ScopeRuntimeContext::new);
    }

    ScopeRuntimeContext existingScope(String scopePath) {
        return scopes.get(scopePath);
    }

    List<Node> rootEmissions() {
        return rootEmissions;
    }

    void recordRootEmission(Node emission) {
        rootEmissions.add(Objects.requireNonNull(emission, "emission"));
    }

    void enqueue(EventOccurrence occurrence) {
        eventQueue.addLast(
                Objects.requireNonNull(occurrence, "occurrence"));
        enqueuedOccurrences++;
    }

    EventOccurrence poll() {
        return eventQueue.pollFirst();
    }

    boolean hasPendingOccurrences() {
        return !eventQueue.isEmpty();
    }

    int pendingOccurrenceCount() {
        return eventQueue.size();
    }

    long enqueuedOccurrenceCount() {
        return enqueuedOccurrences;
    }

    boolean isScopeTerminated(String scopePath) {
        ScopeRuntimeContext context = scopes.get(scopePath);
        return context != null && context.isTerminated();
    }

    void clearScope(String scopePath) {
        scopes.remove(scopePath);
    }
}
