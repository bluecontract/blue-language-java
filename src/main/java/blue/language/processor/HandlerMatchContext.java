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
    private final String handlerKey;
    private final String channelKey;
    private final Node event;
    private final FrozenNode eventFrozen;
    private final Map<String, MarkerContract> markers;
    private final ContractMatchingService matchingService;

    HandlerMatchContext(String scopePath,
                        String handlerKey,
                        String channelKey,
                        Node event,
                        Map<String, MarkerContract> markers,
                        ContractMatchingService matchingService) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.handlerKey = handlerKey;
        this.channelKey = channelKey;
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

    public String handlerKey() {
        return handlerKey;
    }

    public String channelKey() {
        return channelKey;
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

    /**
     * Tests the event's declared type against the configured formal subtype relation.
     *
     * <p>This operation does not use structural payload conformance as a fallback.
     * Missing events, declared types, or expected types are incompatible.</p>
     */
    public boolean eventTypeIsSubtypeOf(Node expectedType) {
        return matchingService.eventTypeIsSubtypeOf(
                event != null ? event.getType() : null,
                expectedType);
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
