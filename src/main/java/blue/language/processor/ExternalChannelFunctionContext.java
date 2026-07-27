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
 * {@link #membersByEffectiveType(String)} is shallow: it resolves a selected
 * member only when derived fields or event evaluation are requested. All
 * consultations are recorded as deterministic dependencies of the owning
 * channel. Whole-surface and type-family enumeration selectors additionally
 * retain membership, including an empty result, so later additions, removals,
 * replacements, and retyping invalidate the owning subscription.</p>
 */
public final class ExternalChannelFunctionContext {

    interface Access {
        ExternalChannelMemberSnapshot member(String key);

        List<ExternalChannelMemberSnapshot> members();

        List<ExternalChannelMemberSnapshot> membersByEffectiveType(
                String effectiveTypeBlueId);

        ChannelMemberSnapshot dependOnSameScopeChannel(
                String key);

        void dependOnSameScopeChannelCatalog();

        Optional<ChannelMemberSnapshot> channel(String key);

        boolean matchesPattern(
                FrozenNode candidate,
                FrozenNode pattern);

        FrozenNode materializeExactReference(
                FrozenNode reference);
    }

    private final String scopePath;
    private final String channelKey;
    private final Access access;

    ExternalChannelFunctionContext(
            String scopePath,
            String channelKey,
            Access access) {
        this.scopePath = Objects.requireNonNull(
                scopePath, "scopePath");
        this.channelKey = Objects.requireNonNull(
                channelKey, "channelKey");
        this.access = Objects.requireNonNull(access, "access");
    }

    public String scopePath() {
        return scopePath;
    }

    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns one required same-scope External Channel or fails closed when
     * the key is missing, non-external, unsupported, or cyclic.
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
     * Declares that this External Channel's immutable subscription header
     * depends on the complete bounded same-scope Channel-header catalog.
     *
     * <p>This operation is available only while subscription-header functions
     * are evaluated. It captures External and processor-managed Channel
     * headers without evaluating any peer as an External source and without
     * loading handler or executable-body content. A later event-time
     * {@link #channel(String)} lookup is permitted only when this declaration
     * was present in the exact retained header dependency snapshot.</p>
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
    public Optional<ChannelMemberSnapshot> channel(
            String rawContractKey) {
        if (rawContractKey == null || rawContractKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "Channel catalog lookup key must be non-empty");
        }
        return access.channel(rawContractKey);
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
