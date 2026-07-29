package blue.language.processor;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                    Collections.<String>emptyList(),
                    Collections.<Entry>emptyList(),
                    Collections.<TypeFamily>emptyList(),
                    false,
                    Collections.<ChannelEntry>emptyList(),
                    false,
                    Collections.<String>emptyList());

    private final List<String> intrinsicNodeBlueIds;
    private final List<Entry> entries;
    private final List<TypeFamily> typeFamilies;
    private final boolean wholeSameScopeExternalSurface;
    private final List<ChannelEntry> channelEntries;
    private final boolean wholeSameScopeChannelCatalog;
    private final List<String> channelCatalogContractKeys;
    private final List<String> deterministicDependencyNodeBlueIds;

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
        this.intrinsicNodeBlueIds = immutableText(
                intrinsicNodeBlueIds, "intrinsic dependency");
        this.entries = immutableEntries(entries);
        this.typeFamilies = immutableTypeFamilies(typeFamilies);
        this.wholeSameScopeExternalSurface =
                wholeSameScopeExternalSurface;
        this.channelEntries =
                immutableChannelEntries(channelEntries);
        this.wholeSameScopeChannelCatalog =
                wholeSameScopeChannelCatalog;
        this.channelCatalogContractKeys =
                wholeSameScopeChannelCatalog
                        ? immutableCatalogKeys(
                                channelCatalogContractKeys)
                        : requireNoCatalogKeys(
                                channelCatalogContractKeys);
        if (wholeSameScopeChannelCatalog
                && !this.channelCatalogContractKeys.containsAll(
                channelEntryKeys(this.channelEntries))) {
            throw new IllegalArgumentException(
                    "Channel catalog raw-key membership omits a Channel "
                            + "entry");
        }
        List<String> identities = new ArrayList<>(
                this.intrinsicNodeBlueIds);
        for (Entry entry : this.entries) {
            identities.add(entry.identityBlueId());
        }
        for (TypeFamily family : this.typeFamilies) {
            identities.add(family.identityBlueId());
        }
        if (wholeSameScopeExternalSurface) {
            identities.add(surfaceIdentity(identities));
        }
        for (ChannelEntry entry : this.channelEntries) {
            identities.add(entry.identityBlueId());
        }
        if (wholeSameScopeChannelCatalog) {
            identities.add(channelCatalogIdentity(
                    this.channelEntries,
                    this.channelCatalogContractKeys));
        }
        this.deterministicDependencyNodeBlueIds =
                Collections.unmodifiableList(identities);
    }

    /**
     * Returns the dependency snapshot used when no evidence was consulted.
     *
     * @return the shared immutable empty dependency snapshot
     */
    public static ExternalChannelDependencySnapshot none() {
        return NONE;
    }

    /**
     * Returns exact identities intrinsic to the owning runtime function.
     *
     * @return immutable intrinsic dependency identities
     */
    public List<String> intrinsicNodeBlueIds() {
        return intrinsicNodeBlueIds;
    }

    /**
     * Returns the exact External Channel members consulted directly.
     *
     * @return immutable consulted External Channel entries
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Returns the shallow type families consulted during derivation.
     *
     * @return immutable shallow type-family dependencies
     */
    public List<TypeFamily> typeFamilies() {
        return typeFamilies;
    }

    /**
     * Reports whether derivation consulted the complete same-scope External
     * Channel membership surface.
     *
     * @return whether complete same-scope External membership was consulted
     */
    public boolean wholeSameScopeExternalSurface() {
        return wholeSameScopeExternalSurface;
    }

    /**
     * Exact read-only same-scope Channel headers captured by this dependency.
     *
     * @return immutable Channel header entries
     */
    public List<ChannelEntry> channelEntries() {
        return channelEntries;
    }

    /**
     * Whether the exact complete same-scope Channel-header catalog was
     * declared, including an empty catalog.
     *
     * @return whether whole-catalog evidence is present
     */
    public boolean wholeSameScopeChannelCatalog() {
        return wholeSameScopeChannelCatalog;
    }

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
        return channelCatalogContractKeys;
    }

    /**
     * Returns the exact ordered identities committed into checkpoint-domain
     * derivation.
     *
     * @return immutable deterministic dependency identities
     */
    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

    /**
     * Reports whether this snapshot carries no dependency evidence.
     *
     * @return whether this snapshot carries no dependency evidence
     */
    public boolean isEmpty() {
        return intrinsicNodeBlueIds.isEmpty()
                && entries.isEmpty()
                && typeFamilies.isEmpty()
                && !wholeSameScopeExternalSurface
                && channelEntries.isEmpty()
                && !wholeSameScopeChannelCatalog
                && channelCatalogContractKeys.isEmpty();
    }

    boolean covers(ExternalChannelDependencySnapshot demanded) {
        if (demanded == null || demanded.isEmpty()) {
            return true;
        }
        if (demanded.wholeSameScopeExternalSurface
                && !wholeSameScopeExternalSurface) {
            return false;
        }
        if (demanded.wholeSameScopeChannelCatalog
                && !wholeSameScopeChannelCatalog) {
            return false;
        }
        if (!intrinsicNodeBlueIds.containsAll(
                demanded.intrinsicNodeBlueIds)) {
            return false;
        }
        Map<String, Entry> available = new LinkedHashMap<>();
        for (Entry entry : entries) {
            available.put(entry.channelKey(), entry);
        }
        for (Entry entry : demanded.entries) {
            if (!entry.equals(available.get(entry.channelKey()))) {
                return false;
            }
        }
        Map<String, TypeFamily> availableFamilies =
                new LinkedHashMap<>();
        for (TypeFamily family : typeFamilies) {
            availableFamilies.put(family.selectorKey(), family);
        }
        for (TypeFamily family : demanded.typeFamilies) {
            if (!family.equals(
                    availableFamilies.get(family.selectorKey()))) {
                return false;
            }
        }
        Map<String, ChannelEntry> availableChannels =
                new LinkedHashMap<>();
        for (ChannelEntry entry : channelEntries) {
            availableChannels.put(entry.channelKey(), entry);
        }
        for (ChannelEntry entry : demanded.channelEntries) {
            if (!entry.equals(
                    availableChannels.get(entry.channelKey()))) {
                return false;
            }
        }
        if (demanded.wholeSameScopeChannelCatalog
                && (!channelEntries.equals(
                demanded.channelEntries)
                || !channelCatalogContractKeys.equals(
                demanded.channelCatalogContractKeys))) {
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ExternalChannelDependencySnapshot)) {
            return false;
        }
        ExternalChannelDependencySnapshot snapshot =
                (ExternalChannelDependencySnapshot) other;
        return intrinsicNodeBlueIds.equals(
                snapshot.intrinsicNodeBlueIds)
                && entries.equals(snapshot.entries)
                && typeFamilies.equals(snapshot.typeFamilies)
                && wholeSameScopeExternalSurface
                == snapshot.wholeSameScopeExternalSurface
                && channelEntries.equals(snapshot.channelEntries)
                && wholeSameScopeChannelCatalog
                == snapshot.wholeSameScopeChannelCatalog
                && channelCatalogContractKeys.equals(
                snapshot.channelCatalogContractKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                intrinsicNodeBlueIds,
                entries,
                typeFamilies,
                wholeSameScopeExternalSurface,
                channelEntries,
                wholeSameScopeChannelCatalog,
                channelCatalogContractKeys);
    }

    private static List<Entry> immutableEntries(
            List<Entry> supplied) {
        Objects.requireNonNull(supplied, "entries");
        List<Entry> copy = new ArrayList<>(supplied.size());
        Set<String> keys = new LinkedHashSet<>();
        for (Entry entry : supplied) {
            Entry exact = Objects.requireNonNull(
                    entry, "dependency entry");
            if (!keys.add(exact.channelKey())) {
                throw new IllegalArgumentException(
                        "Duplicate External Channel dependency key: "
                                + exact.channelKey());
            }
            copy.add(exact);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<TypeFamily> immutableTypeFamilies(
            List<TypeFamily> supplied) {
        Objects.requireNonNull(supplied, "typeFamilies");
        List<TypeFamily> copy = new ArrayList<>(
                supplied.size());
        Set<String> selectors = new LinkedHashSet<>();
        for (TypeFamily family : supplied) {
            TypeFamily exact = Objects.requireNonNull(
                    family, "type family");
            if (!selectors.add(exact.selectorKey())) {
                throw new IllegalArgumentException(
                        "Duplicate External Channel dependency type-family "
                                + "selector: " + exact.selectorKey());
            }
            copy.add(exact);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<ChannelEntry> immutableChannelEntries(
            List<ChannelEntry> supplied) {
        Objects.requireNonNull(supplied, "channelEntries");
        List<ChannelEntry> copy = new ArrayList<>(
                supplied.size());
        Set<String> keys = new LinkedHashSet<>();
        for (ChannelEntry entry : supplied) {
            ChannelEntry exact = Objects.requireNonNull(
                    entry, "Channel dependency entry");
            if (!keys.add(exact.channelKey())) {
                throw new IllegalArgumentException(
                        "Duplicate Channel dependency key: "
                                + exact.channelKey());
            }
            copy.add(exact);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<String> channelEntryKeys(
            List<ChannelEntry> supplied) {
        Objects.requireNonNull(supplied, "channelEntries");
        List<String> keys = new ArrayList<>(supplied.size());
        for (ChannelEntry entry : supplied) {
            keys.add(Objects.requireNonNull(
                    entry, "Channel dependency entry")
                    .channelKey());
        }
        keys.sort(ExternalOrderKey::compareTextCodePoints);
        return keys;
    }

    private static List<String> immutableCatalogKeys(
            List<String> supplied) {
        List<String> keys = new ArrayList<>(
                immutableText(
                        supplied,
                        "Channel catalog contract key"));
        keys.sort(ExternalOrderKey::compareTextCodePoints);
        return Collections.unmodifiableList(keys);
    }

    private static List<String> requireNoCatalogKeys(
            List<String> supplied) {
        Objects.requireNonNull(
                supplied, "channelCatalogContractKeys");
        if (!supplied.isEmpty()) {
            throw new IllegalArgumentException(
                    "Channel catalog contract keys require a whole "
                            + "same-scope Channel catalog declaration");
        }
        return Collections.emptyList();
    }

    private static List<String> immutableText(
            List<String> supplied,
            String label) {
        Objects.requireNonNull(supplied, label);
        List<String> copy = new ArrayList<>(supplied.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String value : supplied) {
            if (value == null || value.isEmpty()
                    || !unique.add(value)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate " + label + ": " + value);
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static String surfaceIdentity(
            List<String> orderedIdentities) {
        List<Node> items = new ArrayList<>(
                orderedIdentities.size());
        for (String identity : orderedIdentities) {
            items.add(new Node().value(identity));
        }
        Node descriptor = new Node()
                .properties(
                        ProcessorIdentityConstants.Field.KIND,
                        new Node().value(
                                ProcessorIdentityConstants.Kind
                                        .WHOLE_SAME_SCOPE_EXTERNAL_SURFACE))
                .properties(
                        ProcessorIdentityConstants.Field
                                .ORDERED_DEPENDENCY_NODE_BLUE_IDS,
                        new Node().items(items));
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

    private static String channelCatalogIdentity(
            List<ChannelEntry> channelEntries,
            List<String> contractKeys) {
        List<Node> items = new ArrayList<>(
                channelEntries.size());
        for (ChannelEntry entry : channelEntries) {
            items.add(new Node().value(
                    entry.identityBlueId()));
        }
        Node descriptor = new Node()
                .properties(
                        ProcessorIdentityConstants.Field.KIND,
                        new Node().value(
                                ProcessorIdentityConstants.Kind
                                        .WHOLE_SAME_SCOPE_CHANNEL_CATALOG))
                .properties(
                        ProcessorIdentityConstants.Field
                                .ORDERED_CHANNEL_ENTRY_IDENTITY_BLUE_IDS,
                        new Node().items(items))
                .properties(
                        ProcessorIdentityConstants.Field
                                .EFFECTIVE_CONTRACT_KEYS,
                        Entry.textList(contractKeys));
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

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
            this.channelKey = requireText(
                    channelKey, "channelKey");
            this.order = order;
            this.effectiveTypeBlueId = requireText(
                    effectiveTypeBlueId, "effectiveTypeBlueId");
            this.sourceContributionNodeBlueIds = immutableText(
                    sourceContributionNodeBlueIds,
                    "source contribution");
            this.deterministicDependencyNodeBlueIds =
                    immutableText(
                            deterministicDependencyNodeBlueIds,
                            "deterministic dependency");
            this.checkpointDomainBlueId = requireText(
                    checkpointDomainBlueId,
                    "checkpointDomainBlueId");
            this.identityBlueId = calculateIdentity();
        }

        /**
         * Returns the exact key of the consulted same-scope channel.
         *
         * @return exact same-scope channel key
         */
        public String channelKey() {
            return channelKey;
        }

        /**
         * Returns the effective order used for deterministic dispatch.
         *
         * @return deterministic channel order
         */
        public int order() {
            return order;
        }

        /**
         * Returns the exact effective runtime type used for dispatch.
         *
         * @return exact effective runtime type identity
         */
        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        /**
         * Returns the Source contribution identities in effective order.
         *
         * @return immutable ordered source identities
         */
        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        /**
         * Returns identities of dependencies consulted while deriving this
         * member.
         *
         * @return immutable ordered nested dependency identities
         */
        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds;
        }

        /**
         * Returns the exact checkpoint domain derived for this member.
         *
         * @return exact checkpoint-domain identity
         */
        public String checkpointDomainBlueId() {
            return checkpointDomainBlueId;
        }

        /**
         * Returns the canonical identity committing every descriptor field.
         *
         * @return canonical identity of this complete descriptor
         */
        public String identityBlueId() {
            return identityBlueId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) other;
            return channelKey.equals(entry.channelKey)
                    && order == entry.order
                    && effectiveTypeBlueId.equals(
                    entry.effectiveTypeBlueId)
                    && sourceContributionNodeBlueIds.equals(
                    entry.sourceContributionNodeBlueIds)
                    && deterministicDependencyNodeBlueIds.equals(
                    entry.deterministicDependencyNodeBlueIds)
                    && checkpointDomainBlueId.equals(
                    entry.checkpointDomainBlueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    channelKey,
                    order,
                    effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds,
                    checkpointDomainBlueId);
        }

        private String calculateIdentity() {
            Node descriptor = new Node()
                    .properties(
                            ProcessorIdentityConstants.Field.CHANNEL_KEY,
                            new Node().value(channelKey))
                    .properties(
                            ProcessorIdentityConstants.Field.ORDER,
                            new Node().value(
                                    BigInteger.valueOf(order)))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .EFFECTIVE_TYPE_BLUE_ID,
                            new Node().value(
                                    effectiveTypeBlueId))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                            textList(
                                    sourceContributionNodeBlueIds))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                            textList(
                                    deterministicDependencyNodeBlueIds))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .CHECKPOINT_DOMAIN_BLUE_ID,
                            new Node().value(
                                    checkpointDomainBlueId));
            return BlueIdCalculator.calculateBlueId(descriptor);
        }

        private static Node textList(List<String> values) {
            List<Node> items = new ArrayList<>(values.size());
            for (String value : values) {
                items.add(new Node().value(value));
            }
            return new Node().items(items);
        }

        private static String requireText(
                String value, String label) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(
                        label + " must be non-empty");
            }
            return value;
        }
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
            this.channelKey = Entry.requireText(
                    channelKey, "channelKey");
            this.order = order;
            this.effectiveTypeBlueId = Entry.requireText(
                    effectiveTypeBlueId, "effectiveTypeBlueId");
            this.role = Entry.requireText(role, "role");
            if (!EffectiveContractSnapshotConstants
                    .Role.EXTERNAL_CHANNEL.equals(role)
                    && !EffectiveContractSnapshotConstants
                    .Role.PROCESSOR_CHANNEL.equals(role)) {
                throw new IllegalArgumentException(
                        "Unsupported Channel runtime role: " + role);
            }
            this.sourceContributionNodeBlueIds = immutableText(
                    sourceContributionNodeBlueIds,
                    "source contribution");
            this.deterministicDependencyNodeBlueIds = immutableText(
                    deterministicDependencyNodeBlueIds,
                    "deterministic dependency");
            this.headerIdentityBlueId = Entry.requireText(
                    headerIdentityBlueId,
                    "headerIdentityBlueId");
            this.identityBlueId = calculateIdentity();
        }

        /**
         * Returns the exact raw key of the same-scope Channel contract.
         *
         * @return the exact raw same-scope contract key
         */
        public String channelKey() {
            return channelKey;
        }

        /**
         * Returns the effective Channel order used for deterministic lookup.
         *
         * @return the effective Channel order
         */
        public int order() {
            return order;
        }

        /**
         * Returns the exact effective runtime type of the Channel header.
         *
         * @return the exact effective runtime type BlueId
         */
        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        /**
         * Returns the runtime role proven by the effective Channel header.
         *
         * @return {@code external-channel} or {@code processor-channel}
         */
        public String role() {
            return role;
        }

        /**
         * Reports whether this header may source External occurrences.
         *
         * @return whether the header also has External-source semantics
         */
        public boolean externalSource() {
            return EffectiveContractSnapshotConstants
                    .Role.EXTERNAL_CHANNEL.equals(role);
        }

        /**
         * Returns exact Source contribution identities in effective order.
         *
         * @return ordered exact Source contribution identities
         */
        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        /**
         * Returns deterministic dependencies retained by the effective header.
         *
         * @return deterministic dependencies carried by the header
         */
        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds;
        }

        /**
         * Returns the exact identity of the sanitized effective header.
         *
         * @return the exact sanitized effective-header identity
         */
        public String headerIdentityBlueId() {
            return headerIdentityBlueId;
        }

        /**
         * Returns the canonical identity committing this dependency descriptor.
         *
         * @return the canonical identity of this dependency descriptor
         */
        public String identityBlueId() {
            return identityBlueId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ChannelEntry)) {
                return false;
            }
            ChannelEntry entry = (ChannelEntry) other;
            return channelKey.equals(entry.channelKey)
                    && order == entry.order
                    && effectiveTypeBlueId.equals(
                    entry.effectiveTypeBlueId)
                    && role.equals(entry.role)
                    && sourceContributionNodeBlueIds.equals(
                    entry.sourceContributionNodeBlueIds)
                    && deterministicDependencyNodeBlueIds.equals(
                    entry.deterministicDependencyNodeBlueIds)
                    && headerIdentityBlueId.equals(
                    entry.headerIdentityBlueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    channelKey,
                    order,
                    effectiveTypeBlueId,
                    role,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds,
                    headerIdentityBlueId);
        }

        private String calculateIdentity() {
            Node descriptor = new Node()
                    .properties(
                            ProcessorIdentityConstants.Field.KIND,
                            new Node().value(
                                    ProcessorIdentityConstants.Kind
                                            .SAME_SCOPE_CHANNEL_HEADER))
                    .properties(
                            ProcessorIdentityConstants.Field.CHANNEL_KEY,
                            new Node().value(channelKey))
                    .properties(
                            ProcessorIdentityConstants.Field.ORDER,
                            new Node().value(
                                    BigInteger.valueOf(order)))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .EFFECTIVE_TYPE_BLUE_ID,
                            new Node().value(
                                    effectiveTypeBlueId))
                    .properties(
                            ProcessorIdentityConstants.Field.ROLE,
                            new Node().value(role))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                            Entry.textList(
                                    sourceContributionNodeBlueIds))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                            Entry.textList(
                                    deterministicDependencyNodeBlueIds))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .HEADER_IDENTITY_BLUE_ID,
                            new Node().value(
                                    headerIdentityBlueId));
            return BlueIdCalculator.calculateBlueId(
                    descriptor);
        }
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
            this.excludingChannelKey = Entry.requireText(
                    excludingChannelKey,
                    "excludingChannelKey");
            this.effectiveTypeBlueId = Entry.requireText(
                    effectiveTypeBlueId,
                    "effectiveTypeBlueId");
            this.matchMode = Objects.requireNonNull(
                    matchMode, "matchMode");
            this.members = immutableMembers(
                    members,
                    this.matchMode == TypeMatchMode.EXACT
                            ? this.effectiveTypeBlueId
                            : null);
            this.identityBlueId = calculateIdentity();
        }

        /**
         * The context owner omitted from this same-scope enumeration.
         *
         * @return exact omitted channel key
         */
        public String excludingChannelKey() {
            return excludingChannelKey;
        }

        /**
         * Returns the type selected by this exact or assignable family.
         *
         * @return selected exact or base type identity
         */
        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        /**
         * Alias that describes the selector role for assignable families.
         *
         * @return selected base type identity
         */
        public String baseTypeBlueId() {
            return effectiveTypeBlueId;
        }

        /**
         * Returns how member effective types are compared with the selector.
         *
         * @return exact or assignable family matching mode
         */
        public TypeMatchMode matchMode() {
            return matchMode;
        }

        /**
         * Reports whether the family includes verified subtype members.
         *
         * @return whether verified subtype members are included
         */
        public boolean includesSubtypes() {
            return matchMode == TypeMatchMode.ASSIGNABLE;
        }

        /**
         * Returns shallow member headers without evaluating member functions.
         *
         * @return immutable shallow members in deterministic order
         */
        public List<Member> members() {
            return members;
        }

        /**
         * Returns the canonical identity committing the selector and members.
         *
         * @return canonical identity of this complete family descriptor
         */
        public String identityBlueId() {
            return identityBlueId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TypeFamily)) {
                return false;
            }
            TypeFamily family = (TypeFamily) other;
            return excludingChannelKey.equals(
                    family.excludingChannelKey)
                    && effectiveTypeBlueId.equals(
                    family.effectiveTypeBlueId)
                    && matchMode == family.matchMode
                    && members.equals(family.members);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    excludingChannelKey,
                    effectiveTypeBlueId,
                    matchMode,
                    members);
        }

        private String calculateIdentity() {
            List<Node> identities =
                    new ArrayList<>(members.size());
            for (Member member : members) {
                identities.add(
                        new Node().value(
                                member.identityBlueId()));
            }
            Node descriptor = new Node()
                    .properties(
                            ProcessorIdentityConstants.Field.KIND,
                            new Node().value(
                                    matchMode == TypeMatchMode.EXACT
                                            ? ProcessorIdentityConstants.Kind
                                            .SAME_SCOPE_EXTERNAL_TYPE_FAMILY
                                            : ProcessorIdentityConstants.Kind
                                            .SAME_SCOPE_EXTERNAL_ASSIGNABLE_TYPE_FAMILY))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .EXCLUDING_CHANNEL_KEY,
                            new Node().value(
                                    excludingChannelKey))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .EFFECTIVE_TYPE_BLUE_ID,
                            new Node().value(
                                    effectiveTypeBlueId))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .ORDERED_MEMBER_IDENTITY_BLUE_IDS,
                            new Node().items(identities));
            if (matchMode == TypeMatchMode.ASSIGNABLE) {
                List<Node> actualTypes =
                        new ArrayList<>(members.size());
                for (Member member : members) {
                    actualTypes.add(
                            new Node().value(
                                    member.effectiveTypeBlueId()));
                }
                descriptor.properties(
                        ProcessorIdentityConstants.Field
                                .ORDERED_MEMBER_EFFECTIVE_TYPE_BLUE_IDS,
                        new Node().items(actualTypes));
            }
            return BlueIdCalculator.calculateBlueId(descriptor);
        }

        private String selectorKey() {
            return excludingChannelKey
                    + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                    + matchMode.name()
                    + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                    + effectiveTypeBlueId;
        }

        private static List<Member> immutableMembers(
                List<Member> supplied,
                String inferredExactTypeBlueId) {
            Objects.requireNonNull(supplied, "members");
            List<Member> copy = new ArrayList<>(
                    supplied.size());
            Set<String> keys = new LinkedHashSet<>();
            for (Member member : supplied) {
                Member exact = Objects.requireNonNull(
                        member, "family member");
                if (!keys.add(exact.channelKey())) {
                    throw new IllegalArgumentException(
                            "Duplicate External Channel family member: "
                                    + exact.channelKey());
                }
                if (exact.effectiveTypeBlueId() == null) {
                    if (inferredExactTypeBlueId == null) {
                        throw new IllegalArgumentException(
                                "Assignable External Channel family member "
                                        + "must declare its actual effective "
                                        + "type: " + exact.channelKey());
                    }
                    exact = exact.withEffectiveTypeBlueId(
                            inferredExactTypeBlueId);
                }
                copy.add(exact);
            }
            return Collections.unmodifiableList(copy);
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
            this.channelKey = Entry.requireText(
                    channelKey, "channelKey");
            this.order = order;
            this.effectiveTypeBlueId =
                    effectiveTypeBlueId != null
                            ? Entry.requireText(
                                    effectiveTypeBlueId,
                                    "effectiveTypeBlueId")
                            : null;
            this.sourceContributionNodeBlueIds = immutableText(
                    sourceContributionNodeBlueIds,
                    "source contribution");
            this.deterministicDependencyNodeBlueIds =
                    immutableText(
                            deterministicDependencyNodeBlueIds,
                            "deterministic dependency");
            this.identityBlueId = calculateIdentity();
        }

        /**
         * Returns the exact key of this shallow family member.
         *
         * @return exact channel key
         */
        public String channelKey() {
            return channelKey;
        }

        /**
         * Returns the effective order used for deterministic enumeration.
         *
         * @return deterministic channel order
         */
        public int order() {
            return order;
        }

        /**
         * Returns the member's actual effective type. Members obtained from a
         * {@link TypeFamily} always provide this value.
         *
         * @return actual effective type identity, or {@code null} before family binding
         */
        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        /**
         * Returns Source contribution identities in effective order.
         *
         * @return immutable ordered source identities
         */
        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        /**
         * Returns deterministic dependencies carried by the member header.
         *
         * @return immutable ordered dependency identities
         */
        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds;
        }

        /**
         * Returns the canonical identity committing the shallow member header.
         *
         * @return canonical identity of this shallow member descriptor
         */
        public String identityBlueId() {
            return identityBlueId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Member)) {
                return false;
            }
            Member member = (Member) other;
            return channelKey.equals(member.channelKey)
                    && order == member.order
                    && Objects.equals(
                    effectiveTypeBlueId,
                    member.effectiveTypeBlueId)
                    && sourceContributionNodeBlueIds.equals(
                    member.sourceContributionNodeBlueIds)
                    && deterministicDependencyNodeBlueIds.equals(
                    member.deterministicDependencyNodeBlueIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    channelKey,
                    order,
                    effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds);
        }

        private Member withEffectiveTypeBlueId(
                String suppliedEffectiveTypeBlueId) {
            return new Member(
                    channelKey,
                    order,
                    suppliedEffectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds);
        }

        private String calculateIdentity() {
            Node descriptor = new Node()
                    .properties(
                            ProcessorIdentityConstants.Field.CHANNEL_KEY,
                            new Node().value(channelKey))
                    .properties(
                            ProcessorIdentityConstants.Field.ORDER,
                            new Node().value(
                                    BigInteger.valueOf(order)))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                            Entry.textList(
                                    sourceContributionNodeBlueIds))
                    .properties(
                            ProcessorIdentityConstants.Field
                                    .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                            Entry.textList(
                                    deterministicDependencyNodeBlueIds));
            return BlueIdCalculator.calculateBlueId(descriptor);
        }
    }
}
