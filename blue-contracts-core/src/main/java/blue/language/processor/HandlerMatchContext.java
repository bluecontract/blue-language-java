package blue.language.processor;

import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalTypeIdentityLookup;
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
    private final Node occurrenceEvent;
    private final FrozenNode occurrenceEventFrozen;
    private final String occurrenceEventBlueId;
    private final Map<String, MarkerContract> markers;
    private final ContractMatchingService matchingService;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
    private final RuntimeWorkSession runtimeWorkSession;
    private final ExternalChannelFunctionEvaluation.MatcherSession
            matcherSession;

    HandlerMatchContext(String scopePath,
                        String handlerKey,
                        String channelKey,
                        Node event,
                        Map<String, MarkerContract> markers,
                        ContractMatchingService matchingService,
                        CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this(scopePath,
                handlerKey,
                channelKey,
                event,
                event,
                null,
                markers,
                matchingService,
                canonicalTypeIdentities,
                null,
                null);
    }

    HandlerMatchContext(String scopePath,
                        String handlerKey,
                        String channelKey,
                        Node event,
                        Map<String, MarkerContract> markers,
                        ContractMatchingService matchingService,
                        CanonicalTypeIdentityLookup canonicalTypeIdentities,
                        RuntimeWorkSession runtimeWorkSession) {
        this(scopePath,
                handlerKey,
                channelKey,
                event,
                event,
                null,
                markers,
                matchingService,
                canonicalTypeIdentities,
                runtimeWorkSession,
                null);
    }

    HandlerMatchContext(String scopePath,
                        String handlerKey,
                        String channelKey,
                        Node event,
                        Node occurrenceEvent,
                        String occurrenceEventBlueId,
                        Map<String, MarkerContract> markers,
                        ContractMatchingService matchingService,
                        CanonicalTypeIdentityLookup canonicalTypeIdentities,
                        RuntimeWorkSession runtimeWorkSession,
                        ExternalChannelFunctionEvaluation.MatcherSession
                                matcherSession) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.handlerKey = handlerKey;
        this.channelKey = channelKey;
        this.event = event != null ? event.clone() : null;
        this.eventFrozen = event != null ? FrozenNode.fromResolvedNode(event) : null;
        this.occurrenceEvent =
                occurrenceEvent != null
                        ? occurrenceEvent.clone()
                        : null;
        this.occurrenceEventFrozen =
                occurrenceEvent != null
                        ? FrozenNode.fromResolvedNode(
                                occurrenceEvent)
                        : null;
        this.occurrenceEventBlueId = occurrenceEventBlueId != null
                ? BlueIds.requireBlueIdOrCyclicMember(
                        occurrenceEventBlueId,
                        "occurrenceEventBlueId")
                : null;
        if (occurrenceEvent == null
                && this.occurrenceEventBlueId != null) {
            throw new IllegalArgumentException(
                    "occurrenceEventBlueId requires occurrenceEvent");
        }
        this.markers = markers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(markers));
        this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        this.runtimeWorkSession = runtimeWorkSession;
        this.matcherSession = matcherSession;
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
     * Returns the semantic event occurrence offered to the current Channel.
     *
     * <p>For ordinary deliveries this is identical to {@link #event()}. An
     * adapter Channel may retain its own wire payload while exposing the exact
     * originating occurrence here for semantic matching.</p>
     *
     * @return detached occurrence event, or {@code null}
     */
    public Node occurrenceEvent() {
        return occurrenceEvent != null
                ? occurrenceEvent.clone()
                : null;
    }

    /**
     * Returns the immutable semantic occurrence used for exact matching.
     *
     * @return frozen occurrence event, or {@code null}
     */
    public FrozenNode occurrenceEventFrozen() {
        return occurrenceEventFrozen;
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
        if (matcherSession != null) {
            FrozenNode candidateType =
                    occurrenceEventFrozen != null
                            ? occurrenceEventFrozen.getType()
                            : null;
            FrozenNode expected =
                    expectedType != null
                            ? FrozenNode.fromResolvedNode(
                                    expectedType)
                            : null;
            if (candidateType == null
                    || expected == null) {
                return false;
            }
            String candidateTypeBlueId =
                    CanonicalIdentityEvidence.resolvedTypeBlueId(
                            candidateType,
                            canonicalTypeIdentities,
                            "Handler event declared type");
            String expectedTypeBlueId =
                    CanonicalIdentityEvidence.resolvedTypeBlueId(
                            expected,
                            canonicalTypeIdentities,
                            "Handler expected declared type");
            return matcherSession.isAssignableToType(
                    candidateTypeBlueId,
                    expectedTypeBlueId);
        }
        return matchingService.eventDeclaredTypeIsSameOrDescendantOf(
                occurrenceEvent != null
                        ? occurrenceEvent.getType()
                        : null,
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
        if (occurrenceEventFrozen == null) {
            return false;
        }
        FrozenNode frozenPattern =
                FrozenNode.fromResolvedNode(pattern);
        if (occurrenceEventBlueId != null
                && frozenPattern.isReferenceOnly()
                && occurrenceEventBlueId.equals(
                        frozenPattern.getReferenceBlueId())) {
            return true;
        }
        return matcherSession != null
                ? matcherSession.matches(
                        occurrenceEventFrozen,
                        frozenPattern)
                : matchingService.matches(
                        occurrenceEventFrozen,
                        frozenPattern);
    }

    /**
     * Materializes one exact reference through the invocation-owned verified
     * provider boundary used by this handler match.
     *
     * <p>Inline exact content is returned as a detached copy. Referenced
     * content is never preprocessed or re-inferred at this boundary.</p>
     *
     * @param value exact inline content or pure reference
     * @return detached exact content
     * @throws IllegalStateException when out-of-band matching has no verified
     *                               materializer
     */
    public Node materializeExactReference(Node value) {
        if (value == null) {
            return null;
        }
        if (!value.isReferenceOnly()) {
            return value.clone();
        }
        if (matcherSession == null) {
            throw new IllegalStateException(
                    "Exact reference materialization is unavailable "
                            + "in this out-of-band handler match");
        }
        FrozenNode materialized =
                matcherSession.materializeExactReference(
                        FrozenNode.fromNode(value));
        return Objects.requireNonNull(
                materialized,
                "materialized exact reference")
                .toNode();
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
