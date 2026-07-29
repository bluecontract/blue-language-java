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
    private final RuntimeWorkSession runtimeWorkSession;

    HandlerMatchContext(String scopePath,
                        String handlerKey,
                        String channelKey,
                        Node event,
                        Map<String, MarkerContract> markers,
                        ContractMatchingService matchingService) {
        this(scopePath,
                handlerKey,
                channelKey,
                event,
                markers,
                matchingService,
                null);
    }

    HandlerMatchContext(String scopePath,
                        String handlerKey,
                        String channelKey,
                        Node event,
                        Map<String, MarkerContract> markers,
                        ContractMatchingService matchingService,
                        RuntimeWorkSession runtimeWorkSession) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.handlerKey = handlerKey;
        this.channelKey = channelKey;
        this.event = event != null ? event.clone() : null;
        this.eventFrozen = event != null ? FrozenNode.fromResolvedNode(event) : null;
        this.markers = markers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(markers));
        this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
        this.runtimeWorkSession = runtimeWorkSession;
    }

    /**
     * Returns the absolute scope containing the Handler.
     *
     * @return normalized scope path
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the Handler's raw same-scope contract key.
     *
     * @return Handler key, or {@code null} for synthetic invocations
     */
    public String handlerKey() {
        return handlerKey;
    }

    /**
     * Returns the Channel key through which the event was delivered.
     *
     * @return delivery Channel key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns a detached mutable copy of the event used for matching.
     *
     * @return event copy, or {@code null}
     */
    public Node event() {
        return event != null ? event.clone() : null;
    }

    /**
     * Returns the immutable event used for matching.
     *
     * @return frozen event, or {@code null}
     */
    public FrozenNode eventFrozen() {
        return eventFrozen;
    }

    /**
     * Returns the immutable same-scope Marker snapshot.
     *
     * @return immutable marker map
     */
    public Map<String, MarkerContract> markers() {
        return markers;
    }

    /**
     * Tests whether the event's declared type has the expected declared identity
     * or names it in a complete, provider-verified ancestry chain.
     *
     * <p>This operation does not infer ancestry from structural compatibility.
     * Missing events, declared identities, expected identities, or required
     * provider content are incompatible.</p>
     *
     * @param expectedType exact expected type node or pure reference
     * @return {@code true} when the event's declared type is equal to or
     *         descends from {@code expectedType}
     */
    public boolean eventDeclaredTypeIsSameOrDescendantOf(Node expectedType) {
        return matchingService.eventDeclaredTypeIsSameOrDescendantOf(
                event != null ? event.getType() : null,
                expectedType);
    }

    /**
     * Matches the frozen event against an exact structural pattern.
     *
     * @param pattern pattern to match; {@code null} matches every event
     * @return {@code true} when the event satisfies the pattern
     */
    public boolean matchesEventPattern(Node pattern) {
        if (pattern == null) {
            return true;
        }
        if (eventFrozen == null) {
            return false;
        }
        return matchingService.matches(eventFrozen, FrozenNode.fromResolvedNode(pattern));
    }

    /**
     * Returns the live hosted-runtime work session for this match.
     *
     * @return invocation-owned runtime work session
     * @throws IllegalStateException for a legacy out-of-band match
     */
    public RuntimeWorkSession runtimeWorkSession() {
        if (runtimeWorkSession == null) {
            throw new IllegalStateException(
                    "Runtime work is unavailable in this out-of-band handler match");
        }
        return runtimeWorkSession;
    }
}
