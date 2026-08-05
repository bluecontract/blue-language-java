package blue.language.processor;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable same-scope dependencies consulted while deriving one External
 * Channel subscription snapshot.
 *
 * <p>Entries are ordered by deterministic semantic consultation, not by
 * physical map iteration. A type-family dependency records either exact-type
 * or bounded subtype-compatible shallow membership, including an empty
 * family, without resolving unrelated member functions. A whole-surface
 * dependency records that any same-scope External Channel addition or
 * removal can change the subscription even when none of the previously
 * present entries changed. The separate Channel catalog records read-only
 * External and processor-managed Channel headers without granting
 * External-source capabilities. Every resulting identity participates in
 * checkpoint-domain derivation and retained-subscription validation.</p>
 */
public final class ExternalChannelDependencySnapshot {
    private static final ExternalChannelDependencySnapshot NONE =
            new ExternalChannelDependencySnapshot(
                    Collections.<String>emptyList(), Collections.<Entry>emptyList(),
                    Collections.<TypeFamily>emptyList(), false,
                    Collections.<ChannelEntry>emptyList(), false,
                    Collections.<String>emptyList());

    private final ExternalChannelDependencyState state;

    /**
     * Creates a snapshot without type-family or Channel-catalog dependencies.
     *
     * @param intrinsicNodeBlueIds exact intrinsic dependency identities
     * @param entries exact consulted External Channel entries
     * @param wholeSameScopeExternalSurface whether the entire External surface was consulted
     */
    public ExternalChannelDependencySnapshot(
            List<String> intrinsicNodeBlueIds,
            List<Entry> entries,
            boolean wholeSameScopeExternalSurface) {
        this(
                intrinsicNodeBlueIds,
                entries,
                Collections.<TypeFamily>emptyList(),
                wholeSameScopeExternalSurface,
                Collections.<ChannelEntry>emptyList(),
                false,
                Collections.<String>emptyList());
    }

    /**
     * Creates a snapshot without read-only Channel-catalog dependencies.
     *
     * @param intrinsicNodeBlueIds exact intrinsic dependency identities
     * @param entries exact consulted External Channel entries
     * @param typeFamilies shallow consulted type families
     * @param wholeSameScopeExternalSurface whether the entire External surface was consulted
     */
    public ExternalChannelDependencySnapshot(
            List<String> intrinsicNodeBlueIds,
            List<Entry> entries,
            List<TypeFamily> typeFamilies,
            boolean wholeSameScopeExternalSurface) {
        this(
                intrinsicNodeBlueIds,
                entries,
                typeFamilies,
                wholeSameScopeExternalSurface,
                Collections.<ChannelEntry>emptyList(),
                false,
                Collections.<String>emptyList());
    }

    /**
     * Creates a dependency snapshot with the complete effective raw-key
     * membership that accompanied a declared Channel catalog.
     *
     * <p>Exact Channel entries may be supplied with
     * {@code wholeSameScopeChannelCatalog == false} and an empty raw-key list.
     * Whole-catalog evidence must supply every effective raw contract key,
     * including keys whose contracts are not Channels.</p>
     *
     * <p>Non-Channel keys carry no header data here. Their membership exists
     * solely so an event-time exact lookup can distinguish semantic absence
     * from a present non-Channel contract without recognizing that unrelated
     * header.</p>
     *
     * @param intrinsicNodeBlueIds exact intrinsic dependency identities
     * @param entries exact consulted External Channel entries
     * @param typeFamilies shallow consulted type families
     * @param wholeSameScopeExternalSurface whether the entire External surface was consulted
     * @param channelEntries exact read-only Channel header entries
     * @param wholeSameScopeChannelCatalog whether the complete Channel catalog was consulted
     * @param channelCatalogContractKeys complete raw keys when the catalog is declared
     * @throws IllegalArgumentException for duplicate, malformed, or incomplete evidence
     */
    public ExternalChannelDependencySnapshot(
            List<String> intrinsicNodeBlueIds,
            List<Entry> entries,
            List<TypeFamily> typeFamilies,
            boolean wholeSameScopeExternalSurface,
            List<ChannelEntry> channelEntries,
            boolean wholeSameScopeChannelCatalog,
            List<String> channelCatalogContractKeys) {
        this.state = new ExternalChannelDependencyState(
                intrinsicNodeBlueIds,
                entries,
                typeFamilies,
                wholeSameScopeExternalSurface,
                channelEntries,
                wholeSameScopeChannelCatalog,
                channelCatalogContractKeys);
    }

    /**
     * Returns the dependency snapshot used when no evidence was consulted.
     *
     * @return the shared immutable empty dependency snapshot
     */
    public static ExternalChannelDependencySnapshot none() { return NONE; }

    /**
     * Returns exact identities intrinsic to the owning runtime function.
     *
     * @return immutable intrinsic dependency identities
     */
    public List<String> intrinsicNodeBlueIds() {
        return state.intrinsicNodeBlueIds(); }

    /**
     * Returns the exact External Channel members consulted directly.
     *
     * @return immutable consulted External Channel entries
     */
    public List<Entry> entries() { return state.entries(); }

    /**
     * Returns the shallow type families consulted during derivation.
     *
     * @return immutable shallow type-family dependencies
     */
    public List<TypeFamily> typeFamilies() { return state.typeFamilies(); }

    /**
     * Reports whether derivation consulted the complete same-scope External
     * Channel membership surface.
     *
     * @return whether complete same-scope External membership was consulted
     */
    public boolean wholeSameScopeExternalSurface() {
        return state.wholeSameScopeExternalSurface(); }

    /**
     * Exact read-only same-scope Channel headers captured by this dependency.
     *
     * @return immutable Channel header entries
     */
    public List<ChannelEntry> channelEntries() { return state.channelEntries(); }

    /**
     * Whether the exact complete same-scope Channel-header catalog was
     * declared, including an empty catalog.
     *
     * @return whether whole-catalog evidence is present
     */
    public boolean wholeSameScopeChannelCatalog() {
        return state.wholeSameScopeChannelCatalog(); }

    /**
     * Returns the complete canonical raw-key membership captured with a
     * declared whole Channel catalog.
     *
     * <p>Keys naming non-Channel contracts intentionally expose no contract
     * content or runtime role beyond their proven presence.</p>
     *
     * @return immutable complete raw-key membership, or an empty list
     */
    public List<String> channelCatalogContractKeys() {
        return state.channelCatalogContractKeys(); }

    /**
     * Returns the exact ordered identities committed into checkpoint-domain
     * derivation.
     *
     * @return immutable deterministic dependency identities
     */
    public List<String> deterministicDependencyNodeBlueIds() {
        return state.deterministicDependencyNodeBlueIds(); }

    /**
     * Reports whether this snapshot carries no dependency evidence.
     *
     * @return whether this snapshot carries no dependency evidence
     */
    public boolean isEmpty() { return state.isEmpty(); }

    boolean covers(ExternalChannelDependencySnapshot demanded) {
        return demanded == null || state.covers(demanded.state);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExternalChannelDependencySnapshot
                && state.equals(
                ((ExternalChannelDependencySnapshot) other).state);
    }

    @Override
    public int hashCode() { return state.hashCode(); }

    /**
     * Exact immutable identity of one consulted same-scope External Channel.
     */
    public static final class Entry {
        private final String channelKey;
        private final int order;
        private final String effectiveTypeBlueId;
        private final List<String> sourceContributionNodeBlueIds;
        private final List<String> deterministicDependencyNodeBlueIds;
        private final String checkpointDomainBlueId;
        private final String identityBlueId;

        /**
         * Creates an identity-bearing External Channel dependency descriptor.
         *
         * @param channelKey exact same-scope channel key
         * @param order deterministic channel order
         * @param effectiveTypeBlueId exact effective runtime type identity
         * @param sourceContributionNodeBlueIds ordered source identities
         * @param deterministicDependencyNodeBlueIds ordered nested dependency identities
         * @param checkpointDomainBlueId exact checkpoint-domain identity
         * @throws IllegalArgumentException for empty or duplicate identity data
         */
        public Entry(
                String channelKey,
                int order,
                String effectiveTypeBlueId,
                List<String> sourceContributionNodeBlueIds,
                List<String> deterministicDependencyNodeBlueIds,
                String checkpointDomainBlueId) {
            this.channelKey = ExternalChannelDependencyValidation.requireText(
                    channelKey, "channelKey");
            this.order = order;
            this.effectiveTypeBlueId =
                    ExternalChannelDependencyValidation.requireText(
                    effectiveTypeBlueId, "effectiveTypeBlueId");
            this.sourceContributionNodeBlueIds =
                    ExternalChannelDependencyValidation.immutableText(
                    sourceContributionNodeBlueIds,
                    "source contribution");
            this.deterministicDependencyNodeBlueIds =
                    ExternalChannelDependencyValidation.immutableText(
                            deterministicDependencyNodeBlueIds,
                            "deterministic dependency");
            this.checkpointDomainBlueId =
                    ExternalChannelDependencyValidation.requireText(
                    checkpointDomainBlueId,
                    "checkpointDomainBlueId");
            this.identityBlueId = ExternalChannelDependencyIdentities.entry(
                    this.channelKey,
                    this.order,
                    this.effectiveTypeBlueId,
                    this.sourceContributionNodeBlueIds,
                    this.deterministicDependencyNodeBlueIds,
                    this.checkpointDomainBlueId);
        }

        /**
         * Returns the exact key of the consulted same-scope channel.
         *
         * @return exact same-scope channel key
         */
        public String channelKey() { return channelKey; }

        /**
         * Returns the effective order used for deterministic dispatch.
         *
         * @return deterministic channel order
         */
        public int order() { return order; }

        /**
         * Returns the exact effective runtime type used for dispatch.
         *
         * @return exact effective runtime type identity
         */
        public String effectiveTypeBlueId() { return effectiveTypeBlueId; }

        /**
         * Returns the Source contribution identities in effective order.
         *
         * @return immutable ordered source identities
         */
        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds; }

        /**
         * Returns identities of dependencies consulted while deriving this
         * member.
         *
         * @return immutable ordered nested dependency identities
         */
        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds; }

        /**
         * Returns the exact checkpoint domain derived for this member.
         *
         * @return exact checkpoint-domain identity
         */
        public String checkpointDomainBlueId() { return checkpointDomainBlueId; }

        /**
         * Returns the canonical identity committing every descriptor field.
         *
         * @return canonical identity of this complete descriptor
         */
        public String identityBlueId() { return identityBlueId; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Entry)) { return false; }
            Entry entry = (Entry) other;
            return channelKey.equals(entry.channelKey)
                    && order == entry.order
                    && effectiveTypeBlueId.equals(entry.effectiveTypeBlueId)
                    && sourceContributionNodeBlueIds.equals(
                            entry.sourceContributionNodeBlueIds)
                    && deterministicDependencyNodeBlueIds.equals(
                            entry.deterministicDependencyNodeBlueIds)
                    && checkpointDomainBlueId.equals(
                            entry.checkpointDomainBlueId);
        }

        @Override
        public int hashCode() { return Objects.hash(
                    channelKey, order, effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds,
                    checkpointDomainBlueId); }
    }

    /**
     * Exact immutable identity of one read-only same-scope Channel header.
     *
     * <p>This entry records no External subscription keys, checkpoint domain,
     * event evaluator, or handler capability.</p>
     */
    public static final class ChannelEntry {
        private final String channelKey;
        private final int order;
        private final String effectiveTypeBlueId;
        private final String role;
        private final List<String> sourceContributionNodeBlueIds;
        private final List<String> deterministicDependencyNodeBlueIds;
        private final String headerIdentityBlueId;
        private final String identityBlueId;

        /**
         * Creates one exact read-only Channel-header dependency entry.
         *
         * @param channelKey exact same-scope channel key
         * @param order deterministic channel order
         * @param effectiveTypeBlueId exact effective runtime type identity
         * @param role effective Channel role
         * @param sourceContributionNodeBlueIds ordered source identities
         * @param deterministicDependencyNodeBlueIds ordered dependency identities
         * @param headerIdentityBlueId exact sanitized-header identity
         * @throws IllegalArgumentException for malformed identity or role data
         */
        public ChannelEntry(
                String channelKey,
                int order,
                String effectiveTypeBlueId,
                String role,
                List<String> sourceContributionNodeBlueIds,
                List<String> deterministicDependencyNodeBlueIds,
                String headerIdentityBlueId) {
            this.channelKey = ExternalChannelDependencyValidation.requireText(
                    channelKey, "channelKey");
            this.order = order;
            this.effectiveTypeBlueId =
                    ExternalChannelDependencyValidation.requireText(
                    effectiveTypeBlueId, "effectiveTypeBlueId");
            this.role = ExternalChannelDependencyValidation
                    .requireChannelRole(role);
            this.sourceContributionNodeBlueIds =
                    ExternalChannelDependencyValidation.immutableText(
                    sourceContributionNodeBlueIds,
                    "source contribution");
            this.deterministicDependencyNodeBlueIds =
                    ExternalChannelDependencyValidation.immutableText(
                    deterministicDependencyNodeBlueIds,
                    "deterministic dependency");
            this.headerIdentityBlueId =
                    ExternalChannelDependencyValidation.requireText(
                    headerIdentityBlueId,
                    "headerIdentityBlueId");
            this.identityBlueId =
                    ExternalChannelDependencyIdentities.channelEntry(
                            this.channelKey,
                            this.order,
                            this.effectiveTypeBlueId,
                            this.role,
                            this.sourceContributionNodeBlueIds,
                            this.deterministicDependencyNodeBlueIds,
                            this.headerIdentityBlueId);
        }

        /**
         * Returns the exact raw key of the same-scope Channel contract.
         *
         * @return the exact raw same-scope contract key
         */
        public String channelKey() { return channelKey; }

        /**
         * Returns the effective Channel order used for deterministic lookup.
         *
         * @return the effective Channel order
         */
        public int order() { return order; }

        /**
         * Returns the exact effective runtime type of the Channel header.
         *
         * @return the exact effective runtime type BlueId
         */
        public String effectiveTypeBlueId() { return effectiveTypeBlueId; }

        /**
         * Returns the runtime role proven by the effective Channel header.
         *
         * @return {@code external-channel} or {@code processor-channel}
         */
        public String role() { return role; }

        /**
         * Reports whether this header may source External occurrences.
         *
         * @return whether the header also has External-source semantics
         */
        public boolean externalSource() {
            return EffectiveContractSnapshotConstants
                    .Role.EXTERNAL_CHANNEL.equals(role); }

        /**
         * Returns exact Source contribution identities in effective order.
         *
         * @return ordered exact Source contribution identities
         */
        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds; }

        /**
         * Returns deterministic dependencies retained by the effective header.
         *
         * @return deterministic dependencies carried by the header
         */
        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds; }

        /**
         * Returns the exact identity of the sanitized effective header.
         *
         * @return the exact sanitized effective-header identity
         */
        public String headerIdentityBlueId() { return headerIdentityBlueId; }

        /**
         * Returns the canonical identity committing this dependency descriptor.
         *
         * @return the canonical identity of this dependency descriptor
         */
        public String identityBlueId() { return identityBlueId; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ChannelEntry)) { return false; }
            ChannelEntry entry = (ChannelEntry) other;
            return channelKey.equals(entry.channelKey)
                    && order == entry.order
                    && effectiveTypeBlueId.equals(entry.effectiveTypeBlueId)
                    && role.equals(entry.role)
                    && sourceContributionNodeBlueIds.equals(
                            entry.sourceContributionNodeBlueIds)
                    && deterministicDependencyNodeBlueIds.equals(
                            entry.deterministicDependencyNodeBlueIds)
                    && headerIdentityBlueId.equals(entry.headerIdentityBlueId);
        }

        @Override
        public int hashCode() { return Objects.hash(
                    channelKey, order, effectiveTypeBlueId, role,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds,
                    headerIdentityBlueId); }
    }

    /**
     * Type selector used by a same-scope External Channel family dependency.
     */
    public enum TypeMatchMode {
        /** Only the requested exact effective type is selected. */
        EXACT,
        /** The requested type and all of its verified Blue subtypes select. */
        ASSIGNABLE
    }

    /**
     * Exact or subtype-compatible membership snapshot for one same-scope
     * External Channel runtime type. Member headers are not recursively
     * evaluated to create this snapshot.
     */
    public static final class TypeFamily {
        private final String excludingChannelKey;
        private final String effectiveTypeBlueId;
        private final TypeMatchMode matchMode;
        private final List<Member> members;
        private final String identityBlueId;

        /**
         * Creates an exact-type family, preserving the original public API.
         *
         * @param excludingChannelKey context owner omitted from enumeration
         * @param effectiveTypeBlueId exact family type identity
         * @param members shallow family members in deterministic order
         */
        public TypeFamily(
                String excludingChannelKey,
                String effectiveTypeBlueId,
                List<Member> members) {
            this(
                    excludingChannelKey,
                    effectiveTypeBlueId,
                    TypeMatchMode.EXACT,
                    members);
        }

        /**
         * Creates an exact or assignable shallow type-family dependency.
         *
         * @param excludingChannelKey context owner omitted from enumeration
         * @param effectiveTypeBlueId selected exact or base type identity
         * @param matchMode exact or assignable matching mode
         * @param members shallow family members in deterministic order
         * @throws IllegalArgumentException for malformed or duplicate members
         */
        public TypeFamily(
                String excludingChannelKey,
                String effectiveTypeBlueId,
                TypeMatchMode matchMode,
                List<Member> members) {
            this.excludingChannelKey =
                    ExternalChannelDependencyValidation.requireText(
                    excludingChannelKey,
                    "excludingChannelKey");
            this.effectiveTypeBlueId =
                    ExternalChannelDependencyValidation.requireText(
                    effectiveTypeBlueId,
                    "effectiveTypeBlueId");
            this.matchMode = Objects.requireNonNull(
                    matchMode, "matchMode");
            this.members = ExternalChannelDependencyValidation
                    .immutableMembers(
                    members,
                    this.matchMode == TypeMatchMode.EXACT
                            ? this.effectiveTypeBlueId
                            : null);
            this.identityBlueId =
                    ExternalChannelDependencyIdentities.typeFamily(
                            this.excludingChannelKey,
                            this.effectiveTypeBlueId,
                            this.matchMode,
                            this.members);
        }

        /**
         * The context owner omitted from this same-scope enumeration.
         *
         * @return exact omitted channel key
         */
        public String excludingChannelKey() { return excludingChannelKey; }

        /**
         * Returns the type selected by this exact or assignable family.
         *
         * @return selected exact or base type identity
         */
        public String effectiveTypeBlueId() { return effectiveTypeBlueId; }

        /**
         * Alias that describes the selector role for assignable families.
         *
         * @return selected base type identity
         */
        public String baseTypeBlueId() { return effectiveTypeBlueId; }

        /**
         * Returns how member effective types are compared with the selector.
         *
         * @return exact or assignable family matching mode
         */
        public TypeMatchMode matchMode() { return matchMode; }

        /**
         * Reports whether the family includes verified subtype members.
         *
         * @return whether verified subtype members are included
         */
        public boolean includesSubtypes() {
            return matchMode == TypeMatchMode.ASSIGNABLE; }

        /**
         * Returns shallow member headers without evaluating member functions.
         *
         * @return immutable shallow members in deterministic order
         */
        public List<Member> members() { return members; }

        /**
         * Returns the canonical identity committing the selector and members.
         *
         * @return canonical identity of this complete family descriptor
         */
        public String identityBlueId() { return identityBlueId; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TypeFamily)) { return false; }
            TypeFamily family = (TypeFamily) other;
            return excludingChannelKey.equals(family.excludingChannelKey)
                    && effectiveTypeBlueId.equals(family.effectiveTypeBlueId)
                    && matchMode == family.matchMode
                    && members.equals(family.members);
        }

        @Override
        public int hashCode() { return Objects.hash(
                    excludingChannelKey, effectiveTypeBlueId,
                    matchMode, members); }

        String selectorKey() {
            return excludingChannelKey
                    + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                    + matchMode.name()
                    + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                    + effectiveTypeBlueId;
        }

    }

    /**
     * Shallow exact header identity inside a type-family dependency.
     */
    public static final class Member {
        private final String channelKey;
        private final int order;
        private final String effectiveTypeBlueId;
        private final List<String> sourceContributionNodeBlueIds;
        private final List<String> deterministicDependencyNodeBlueIds;
        private final String identityBlueId;

        /**
         * Compatibility constructor for exact-type families. The enclosing
         * exact {@link TypeFamily} supplies the member's effective type.
         *
         * @param channelKey exact channel key
         * @param order deterministic channel order
         * @param sourceContributionNodeBlueIds ordered source identities
         * @param deterministicDependencyNodeBlueIds ordered dependency identities
         */
        public Member(
                String channelKey,
                int order,
                List<String> sourceContributionNodeBlueIds,
                List<String> deterministicDependencyNodeBlueIds) {
            this(
                    channelKey,
                    order,
                    null,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds);
        }

        /**
         * Creates a shallow member with its actual effective type.
         *
         * @param channelKey exact channel key
         * @param order deterministic channel order
         * @param effectiveTypeBlueId actual effective type, or {@code null}
         * @param sourceContributionNodeBlueIds ordered source identities
         * @param deterministicDependencyNodeBlueIds ordered dependency identities
         */
        public Member(
                String channelKey,
                int order,
                String effectiveTypeBlueId,
                List<String> sourceContributionNodeBlueIds,
                List<String> deterministicDependencyNodeBlueIds) {
            this.channelKey = ExternalChannelDependencyValidation.requireText(
                    channelKey, "channelKey");
            this.order = order;
            this.effectiveTypeBlueId =
                    effectiveTypeBlueId != null
                            ? ExternalChannelDependencyValidation.requireText(
                                    effectiveTypeBlueId,
                                    "effectiveTypeBlueId")
                            : null;
            this.sourceContributionNodeBlueIds =
                    ExternalChannelDependencyValidation.immutableText(
                    sourceContributionNodeBlueIds,
                    "source contribution");
            this.deterministicDependencyNodeBlueIds =
                    ExternalChannelDependencyValidation.immutableText(
                            deterministicDependencyNodeBlueIds,
                            "deterministic dependency");
            this.identityBlueId = ExternalChannelDependencyIdentities.member(
                    this.channelKey,
                    this.order,
                    this.sourceContributionNodeBlueIds,
                    this.deterministicDependencyNodeBlueIds);
        }

        /**
         * Returns the exact key of this shallow family member.
         *
         * @return exact channel key
         */
        public String channelKey() { return channelKey; }

        /**
         * Returns the effective order used for deterministic enumeration.
         *
         * @return deterministic channel order
         */
        public int order() { return order; }

        /**
         * Returns the member's actual effective type. Members obtained from a
         * {@link TypeFamily} always provide this value.
         *
         * @return actual effective type identity, or {@code null} before family binding
         */
        public String effectiveTypeBlueId() { return effectiveTypeBlueId; }

        /**
         * Returns Source contribution identities in effective order.
         *
         * @return immutable ordered source identities
         */
        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds; }

        /**
         * Returns deterministic dependencies carried by the member header.
         *
         * @return immutable ordered dependency identities
         */
        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds; }

        /**
         * Returns the canonical identity committing the shallow member header.
         *
         * @return canonical identity of this shallow member descriptor
         */
        public String identityBlueId() { return identityBlueId; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Member)) { return false; }
            Member member = (Member) other;
            return channelKey.equals(member.channelKey)
                    && order == member.order
                    && Objects.equals(effectiveTypeBlueId,
                            member.effectiveTypeBlueId)
                    && sourceContributionNodeBlueIds.equals(
                            member.sourceContributionNodeBlueIds)
                    && deterministicDependencyNodeBlueIds.equals(
                            member.deterministicDependencyNodeBlueIds);
        }

        @Override
        public int hashCode() { return Objects.hash(
                    channelKey, order, effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds); }

        Member withEffectiveTypeBlueId(
                String suppliedEffectiveTypeBlueId) {
            return new Member(
                    channelKey, order, suppliedEffectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds);
        }
    }
}
