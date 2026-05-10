package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.MarkerContract;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only context used to decide whether a handler should run for an event.
 */
public final class HandlerMatchContext {

    private final String scopePath;
    private final Node event;
    private final FrozenNode eventFrozen;
    private final Map<String, MarkerContract> markers;
    private final ContractMatchingService matchingService;

    HandlerMatchContext(String scopePath,
                        Node event,
                        Map<String, MarkerContract> markers,
                        ContractMatchingService matchingService) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.event = event != null ? event.clone() : null;
        this.eventFrozen = event != null ? FrozenNode.fromResolvedNode(event) : null;
        this.markers = markers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(markers));
        this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
    }

    public String scopePath() {
        return scopePath;
    }

    public Node event() {
        return event != null ? event.clone() : null;
    }

    public FrozenNode eventFrozen() {
        return eventFrozen;
    }

    public Map<String, MarkerContract> markers() {
        return markers;
    }

    public boolean matchesEventPattern(Node pattern) {
        if (pattern == null) {
            return true;
        }
        if (eventFrozen == null) {
            return false;
        }
        return matchingService.matches(eventFrozen, FrozenNode.fromResolvedNode(pattern));
    }
}
