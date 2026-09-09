package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, same-scope view supplied to registered External Channel
 * functions.
 *
 * <p>Every member returned by this context has an exact frozen effective
 * contract header. Explicit {@link #member(String)} and {@link #members()}
 * access resolves registered subscription functions immediately.
 * {@link #membersByEffectiveType(String)} and
 * {@link #membersAssignableToType(String)} are shallow: they resolve a
 * selected member only when derived fields or event evaluation are requested.
 * All consultations are recorded as deterministic dependencies of the owning
 * channel. Whole-surface and type-family enumeration selectors additionally
 * retain membership, including an empty result, so later additions, removals,
 * replacements, and retyping invalidate the owning subscription.</p>
 */
public final class ExternalChannelFunctionContext {

    /**
     * Processor-owned boundary that supplies same-scope evidence and records
     * every dependency consulted by a registered function.
     */
    interface Access {

        /** Returns one resolved External Channel member by raw contract key. */
        ExternalChannelMemberSnapshot member(String key);

        /** Returns the complete canonical-order External Channel surface. */
        List<ExternalChannelMemberSnapshot> members();

        /** Returns shallow members having exactly the requested effective type. */
        List<ExternalChannelMemberSnapshot> membersByEffectiveType(
                String effectiveTypeBlueId);

        /** Returns shallow members assignable to the requested base type. */
        List<ExternalChannelMemberSnapshot> membersAssignableToType(
                String baseTypeBlueId);

        /** Records and returns one same-scope Channel header dependency. */
        ChannelMemberSnapshot dependOnSameScopeChannel(
                String key);

        /** Records a dependency on the complete same-scope Channel catalog. */
        void dependOnSameScopeChannelCatalog();

        /** Looks up one previously declared same-scope Channel header. */
        ChannelLookupResult lookupChannel(String key);

        /** Matches exact frozen values through the captured matcher session. */
        boolean matchesPattern(
                FrozenNode candidate,
                FrozenNode pattern);

        /** Materializes an exact reference through verified snapshot evidence. */
        FrozenNode materializeExactReference(
                FrozenNode reference);
    }

    private final String scopePath;
    private final String channelKey;
    private final Access access;
    private final RuntimeWorkSession runtimeWorkSession;
    private final RuntimeWorkSession.ExactInputLookup exactEvent;

    ExternalChannelFunctionContext(
            String scopePath,
            String channelKey,
            Access access) {
        this(scopePath, channelKey, access, null, null);
    }

    ExternalChannelFunctionContext(
            String scopePath,
            String channelKey,
            Access access,
            RuntimeWorkSession runtimeWorkSession,
            Node exactEvent) {
        this.scopePath = Objects.requireNonNull(
                scopePath, "scopePath");
        this.channelKey = Objects.requireNonNull(
                channelKey, "channelKey");
        this.access = Objects.requireNonNull(access, "access");
        this.runtimeWorkSession = runtimeWorkSession;
        this.exactEvent = exactEvent != null
                ? new RuntimeWorkSession.ExactInputLookup(
                        FrozenNode.fromResolvedNode(exactEvent.clone()))
                : null;
    }

    /**
     * Returns the absolute scope containing the owning External Channel.
     *
     * @return normalized scope path
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the raw key of the owning External Channel.
     *
     * @return Channel key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns the processor-owned runtime work session for this deterministic
     * function pass.
     *
     * @return invocation-owned runtime work session
     * @throws IllegalStateException for a legacy out-of-band pass
     */
    public RuntimeWorkSession runtimeWorkSession() {
        if (runtimeWorkSession == null) {
            throw new IllegalStateException(
                    "Runtime work is unavailable in this legacy out-of-band context");
        }
        return runtimeWorkSession;
    }

    /**
     * Returns the exact identity proved when the event entered this function
     * pass.
     *
     * <p>The lookup returns the identity of a matching processor-carried exact
     * capability. It compares the complete captured structure in the carried
     * representation mode; an ordinary canonical Source view may be verified
     * against an already admitted strict capability. A pure reference requires
     * a matching carried reference representation. The returned identity is
     * never derived from hashing a resolved inline-type or cyclic-member
     * cursor, which could establish a different ordinary content identity.</p>
     *
     * @return unique admission-proved event BlueId, including a cyclic-member
     *         identity when applicable
     * @throws IllegalStateException when runtime admission evidence is absent,
     *         no longer active, missing, or ambiguous
     */
    public String exactEventBlueId() {
        if (exactEvent == null) {
            throw new IllegalStateException(
                    "External Channel exact event identity is available only "
                            + "during event evaluation");
        }
        RuntimeWorkSession session = runtimeWorkSession();
        if (!session.isOpen()) {
            throw new IllegalStateException(
                    "External Channel exact event identity is no longer active");
        }
        final ExactBlueValue matched;
        try {
            matched = session.carriedExactInput(exactEvent);
        } catch (InvalidExecutionEvidenceException ambiguous) {
            throw new IllegalStateException(
                    "External Channel exact event identity is ambiguous", ambiguous);
        }
        if (matched == null) {
            throw new IllegalStateException(
                    "External Channel exact event identity was not admitted");
        }
        return matched.blueId();
    }

    /**
     * Returns one required same-scope External Channel or fails closed when
     * the key is missing, non-external, unsupported, or cyclic.
     *
     * @param key raw same-scope contract key
     * @return immutable resolved External Channel snapshot
     * @throws IllegalArgumentException if {@code key} is empty
     */
    public ExternalChannelMemberSnapshot member(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(
                    "External Channel dependency key must be non-empty");
        }
        return access.member(key);
    }

    /**
     * Returns every other same-scope External Channel in canonical
     * {@code (order, key, effectiveTypeBlueId)} order.
     *
     * <p>This is an eager whole-surface dependency. It resolves every returned
     * member's subscription header and can therefore expose a dependency cycle
     * between peer aggregate channels. Prefer
     * {@link #membersByEffectiveType(String)} when the runtime depends on one
     * exact member family.</p>
     *
     * @return immutable External Channel snapshots in canonical order
     */
    public List<ExternalChannelMemberSnapshot> members() {
        return access.members();
    }

    /**
     * Returns shallow immutable snapshots of every other same-scope External
     * Channel with the exact effective runtime type, in canonical order.
     *
     * <p>Enumeration itself does not recursively evaluate member subscription
     * functions. Accessing a returned member's derived keys/domain or calling
     * {@link ExternalChannelMemberSnapshot#evaluate} resolves only that
     * selected member. The exact type-family membership is retained as a
     * dependency, so additions, removals, replacements, and retyping rotate
     * the owning subscription without depending on unrelated runtime
     * types.</p>
     *
     * @param effectiveTypeBlueId exact runtime type identity
     * @return immutable matching header snapshots in canonical order
     */
    public List<ExternalChannelMemberSnapshot> membersByEffectiveType(
            String effectiveTypeBlueId) {
        if (effectiveTypeBlueId == null
                || effectiveTypeBlueId.isEmpty()) {
            throw new IllegalArgumentException(
                    "effectiveTypeBlueId must be non-empty");
        }
        return access.membersByEffectiveType(
                effectiveTypeBlueId);
    }

    /**
     * Returns shallow immutable snapshots of every other same-scope External
     * Channel whose exact effective type is equal to or a Blue subtype of the
     * requested base type, in canonical member order.
     *
     * <p>The bounded type lineage is resolved only through the processor's
     * captured verified snapshot boundary. Enumeration does not evaluate
     * member subscription functions or load executable bodies. The complete
     * subtype-family membership, including an empty result, is retained as a
     * distinct dependency from exact-type enumeration.</p>
     *
     * @param baseTypeBlueId exact BlueId of the requested base type
     * @return immutable matching header snapshots in canonical order
     */
    public List<ExternalChannelMemberSnapshot>
    membersAssignableToType(String baseTypeBlueId) {
        if (baseTypeBlueId == null || baseTypeBlueId.isEmpty()) {
            throw new IllegalArgumentException(
                    "baseTypeBlueId must be non-empty");
        }
        return access.membersAssignableToType(baseTypeBlueId);
    }

    /**
     * Declares that this External Channel's immutable subscription header
     * depends on the complete bounded same-scope Channel-header catalog.
     *
     * <p>This operation is available only while subscription-header functions
     * are evaluated. It captures External and processor-managed Channel
     * headers without evaluating any peer as an External source and without
     * loading handler or executable-body content. A later event-time
     * {@link #lookupChannel(String)} lookup is permitted only when this
     * declaration
     * was present in the exact retained header dependency snapshot.</p>
     *
     * @throws IllegalStateException outside subscription-header evaluation
     */
    public void dependOnSameScopeChannelCatalog() {
        access.dependOnSameScopeChannelCatalog();
    }

    /**
     * Declares and returns one required same-scope Channel header during
     * immutable subscription-header evaluation.
     *
     * <p>This exact-key form is preferred when the target key is known from
     * the contract header. It captures only that effective Channel header,
     * does not evaluate an External peer, and does not load executable-body
     * content. Missing and non-Channel keys fail closed.</p>
     *
     * @param rawContractKey exact same-scope raw contract key
     * @return the immutable declared Channel header
     */
    public ChannelMemberSnapshot dependOnSameScopeChannel(
            String rawContractKey) {
        if (rawContractKey == null || rawContractKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "Channel dependency key must be non-empty");
        }
        return access.dependOnSameScopeChannel(
                rawContractKey);
    }

    /**
     * Looks up one exact raw key in the declared same-scope Channel surface.
     *
     * <p>This operation is available only during event evaluation and fails
     * closed unless the subscription header declared that exact key with
     * {@link #dependOnSameScopeChannel(String)} or declared the complete
     * catalog with {@link #dependOnSameScopeChannelCatalog()}. An empty result
     * is available only under the complete catalog and proves semantic
     * absence from the effective contract map. A missing exact dependency,
     * present non-Channel contract, incomplete evidence, or unavailable exact
     * header is reported as an error rather than as absence.</p>
     *
     * @param rawContractKey exact same-scope raw contract key
     * @return an immutable read-only Channel header, or empty only for proven
     *         semantic absence
     */
    public ChannelLookupResult lookupChannel(
            String rawContractKey) {
        if (rawContractKey == null || rawContractKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "Channel catalog lookup key must be non-empty");
        }
        return access.lookupChannel(rawContractKey);
    }

    /**
     * Compatibility view of {@link #lookupChannel(String)}.
     *
     * <p>Semantic absence remains an empty result. A present non-Channel
     * Contract keeps the historical fail-closed behavior; runtimes that need
     * to distinguish it from absence use the typed lookup directly.</p>
     *
     * @param rawContractKey exact same-scope raw contract key
     * @return immutable Channel snapshot, or empty for proven absence
     */
    public Optional<ChannelMemberSnapshot> channel(
            String rawContractKey) {
        ChannelLookupResult result =
                lookupChannel(rawContractKey);
        if (result.isNonChannel()) {
            throw new IllegalStateException(
                    "Same-scope contract is not a Channel: "
                            + rawContractKey);
        }
        return result.channel();
    }

    /**
     * Tests one exact candidate against an exact Blue pattern through the
     * processor's event-scoped matcher.
     *
     * <p>This operation is available only while immutable event functions are
     * being evaluated. Subscription-header functions such as
     * {@code channelKeys} and {@code checkpointDomainDiscriminator} fail closed
     * if they attempt to use it, either directly or indirectly through
     * {@link ExternalChannelMemberSnapshot#evaluate(Node)}. Candidate and
     * pattern are cloned and frozen at this call boundary. Candidate and
     * type-lineage pure references needed for structural comparison are
     * materialized only by the event-scoped matcher and its captured verified
     * snapshot-manager context. A pure reference pattern remains an exact
     * nominal identity check. If no such manager owns the evaluation, inline
     * matching remains available but any materialization demand fails
     * closed.</p>
     *
     * @param candidate exact candidate node; {@code null} never matches a
     *                  non-null pattern
     * @param pattern exact pattern; {@code null} matches every candidate
     * @return whether the frozen candidate conforms to the frozen pattern
     */
    public boolean matchesPattern(
            Node candidate,
            Node pattern) {
        FrozenNode frozenCandidate = candidate != null
                ? FrozenNode.fromResolvedNode(
                candidate.clone())
                : null;
        FrozenNode frozenPattern = pattern != null
                ? FrozenNode.fromResolvedNode(
                pattern.clone())
                : null;
        return access.matchesPattern(
                frozenCandidate,
                frozenPattern);
    }

    /**
     * Materializes one exact pure-reference fragment through the verified
     * Processing Snapshot Manager captured for this event-evaluation pass.
     *
     * <p>This operation is unavailable during subscription-header evaluation
     * and after the event-function session closes. It returns the exact direct
     * provider content for the supplied identity; it does not recursively
     * expand the referenced graph.</p>
     *
     * @param reference exact pure BlueId reference
     * @return a defensive exact direct-content node
     */
    public Node materializeExactReference(
            Node reference) {
        if (reference == null) {
            throw new IllegalArgumentException(
                    "Exact event fragment reference is required");
        }
        FrozenNode frozen = FrozenNode.fromNode(
                reference.clone());
        if (!frozen.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Exact event fragment must be a pure BlueId reference");
        }
        return access.materializeExactReference(
                frozen).toNode();
    }
}
