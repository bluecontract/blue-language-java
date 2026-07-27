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
 * physical map iteration. A type-family dependency records the exact shallow
 * membership selected by one effective runtime type, including an empty
 * family, without resolving unrelated families. A whole-surface dependency
 * records that any same-scope External Channel addition or removal can change
 * the subscription even when none of the previously present entries changed.
 * The separate Channel catalog records read-only External and
 * processor-managed Channel headers without granting External-source
 * capabilities. Every resulting identity participates in checkpoint-domain
 * derivation and retained-subscription validation.</p>
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

    public static ExternalChannelDependencySnapshot none() {
        return NONE;
    }

    public List<String> intrinsicNodeBlueIds() {
        return intrinsicNodeBlueIds;
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<TypeFamily> typeFamilies() {
        return typeFamilies;
    }

    public boolean wholeSameScopeExternalSurface() {
        return wholeSameScopeExternalSurface;
    }

    /**
     * Exact read-only same-scope Channel headers captured by this dependency.
     */
    public List<ChannelEntry> channelEntries() {
        return channelEntries;
    }

    /**
     * Whether the exact complete same-scope Channel-header catalog was
     * declared, including an empty catalog.
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
     */
    public List<String> channelCatalogContractKeys() {
        return channelCatalogContractKeys;
    }

    /**
     * Returns the exact ordered identities committed into checkpoint-domain
     * derivation.
     */
    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

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
                        "kind",
                        new Node().value(
                                "whole-same-scope-external-surface"))
                .properties(
                        "orderedDependencyNodeBlueIds",
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
                        "kind",
                        new Node().value(
                                "whole-same-scope-channel-catalog"))
                .properties(
                        "orderedChannelEntryIdentityBlueIds",
                        new Node().items(items))
                .properties(
                        "effectiveContractKeys",
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

        public String channelKey() {
            return channelKey;
        }

        public int order() {
            return order;
        }

        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds;
        }

        public String checkpointDomainBlueId() {
            return checkpointDomainBlueId;
        }

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
                            "channelKey",
                            new Node().value(channelKey))
                    .properties(
                            "order",
                            new Node().value(
                                    BigInteger.valueOf(order)))
                    .properties(
                            "effectiveTypeBlueId",
                            new Node().value(
                                    effectiveTypeBlueId))
                    .properties(
                            "sourceContributionNodeBlueIds",
                            textList(
                                    sourceContributionNodeBlueIds))
                    .properties(
                            "deterministicDependencyNodeBlueIds",
                            textList(
                                    deterministicDependencyNodeBlueIds))
                    .properties(
                            "checkpointDomainBlueId",
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
            if (!"external-channel".equals(role)
                    && !"processor-channel".equals(role)) {
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

        /** Returns the exact raw same-scope contract key. */
        public String channelKey() {
            return channelKey;
        }

        /** Returns the effective Channel order. */
        public int order() {
            return order;
        }

        /** Returns the exact effective runtime type BlueId. */
        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        /** Returns {@code external-channel} or {@code processor-channel}. */
        public String role() {
            return role;
        }

        /** Returns whether the header also has External-source semantics. */
        public boolean externalSource() {
            return "external-channel".equals(role);
        }

        /** Returns ordered exact Source contribution identities. */
        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        /** Returns deterministic dependencies carried by the header. */
        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds;
        }

        /** Returns the exact sanitized effective-header identity. */
        public String headerIdentityBlueId() {
            return headerIdentityBlueId;
        }

        /** Returns the canonical identity of this dependency descriptor. */
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
                            "kind",
                            new Node().value(
                                    "same-scope-channel-header"))
                    .properties(
                            "channelKey",
                            new Node().value(channelKey))
                    .properties(
                            "order",
                            new Node().value(
                                    BigInteger.valueOf(order)))
                    .properties(
                            "effectiveTypeBlueId",
                            new Node().value(
                                    effectiveTypeBlueId))
                    .properties(
                            "role",
                            new Node().value(role))
                    .properties(
                            "sourceContributionNodeBlueIds",
                            Entry.textList(
                                    sourceContributionNodeBlueIds))
                    .properties(
                            "deterministicDependencyNodeBlueIds",
                            Entry.textList(
                                    deterministicDependencyNodeBlueIds))
                    .properties(
                            "headerIdentityBlueId",
                            new Node().value(
                                    headerIdentityBlueId));
            return BlueIdCalculator.calculateBlueId(
                    descriptor);
        }
    }

    /**
     * Exact membership snapshot for one same-scope External Channel runtime
     * type. Member headers are not recursively evaluated to create this
     * snapshot.
     */
    public static final class TypeFamily {
        private final String excludingChannelKey;
        private final String effectiveTypeBlueId;
        private final List<Member> members;
        private final String identityBlueId;

        public TypeFamily(
                String excludingChannelKey,
                String effectiveTypeBlueId,
                List<Member> members) {
            this.excludingChannelKey = Entry.requireText(
                    excludingChannelKey,
                    "excludingChannelKey");
            this.effectiveTypeBlueId = Entry.requireText(
                    effectiveTypeBlueId,
                    "effectiveTypeBlueId");
            this.members = immutableMembers(members);
            this.identityBlueId = calculateIdentity();
        }

        /**
         * The context owner omitted from this same-scope enumeration.
         */
        public String excludingChannelKey() {
            return excludingChannelKey;
        }

        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        public List<Member> members() {
            return members;
        }

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
                    && members.equals(family.members);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    excludingChannelKey,
                    effectiveTypeBlueId,
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
                            "kind",
                            new Node().value(
                                    "same-scope-external-type-family"))
                    .properties(
                            "excludingChannelKey",
                            new Node().value(
                                    excludingChannelKey))
                    .properties(
                            "effectiveTypeBlueId",
                            new Node().value(
                                    effectiveTypeBlueId))
                    .properties(
                            "orderedMemberIdentityBlueIds",
                            new Node().items(identities));
            return BlueIdCalculator.calculateBlueId(descriptor);
        }

        private String selectorKey() {
            return excludingChannelKey + "\u0000"
                    + effectiveTypeBlueId;
        }

        private static List<Member> immutableMembers(
                List<Member> supplied) {
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
        private final List<String> sourceContributionNodeBlueIds;
        private final List<String> deterministicDependencyNodeBlueIds;
        private final String identityBlueId;

        public Member(
                String channelKey,
                int order,
                List<String> sourceContributionNodeBlueIds,
                List<String> deterministicDependencyNodeBlueIds) {
            this.channelKey = Entry.requireText(
                    channelKey, "channelKey");
            this.order = order;
            this.sourceContributionNodeBlueIds = immutableText(
                    sourceContributionNodeBlueIds,
                    "source contribution");
            this.deterministicDependencyNodeBlueIds =
                    immutableText(
                            deterministicDependencyNodeBlueIds,
                            "deterministic dependency");
            this.identityBlueId = calculateIdentity();
        }

        public String channelKey() {
            return channelKey;
        }

        public int order() {
            return order;
        }

        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        public List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds;
        }

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
                    sourceContributionNodeBlueIds,
                    deterministicDependencyNodeBlueIds);
        }

        private String calculateIdentity() {
            Node descriptor = new Node()
                    .properties(
                            "channelKey",
                            new Node().value(channelKey))
                    .properties(
                            "order",
                            new Node().value(
                                    BigInteger.valueOf(order)))
                    .properties(
                            "sourceContributionNodeBlueIds",
                            Entry.textList(
                                    sourceContributionNodeBlueIds))
                    .properties(
                            "deterministicDependencyNodeBlueIds",
                            Entry.textList(
                                    deterministicDependencyNodeBlueIds));
            return BlueIdCalculator.calculateBlueId(descriptor);
        }
    }
}
