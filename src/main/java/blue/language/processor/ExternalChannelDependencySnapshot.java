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
 * Every resulting identity participates in checkpoint-domain derivation and
 * retained-subscription validation.</p>
 */
public final class ExternalChannelDependencySnapshot {

    private static final ExternalChannelDependencySnapshot NONE =
            new ExternalChannelDependencySnapshot(
                    Collections.<String>emptyList(),
                    Collections.<Entry>emptyList(),
                    false);

    private final List<String> intrinsicNodeBlueIds;
    private final List<Entry> entries;
    private final List<TypeFamily> typeFamilies;
    private final boolean wholeSameScopeExternalSurface;
    private final List<String> deterministicDependencyNodeBlueIds;

    public ExternalChannelDependencySnapshot(
            List<String> intrinsicNodeBlueIds,
            List<Entry> entries,
            boolean wholeSameScopeExternalSurface) {
        this(
                intrinsicNodeBlueIds,
                entries,
                Collections.<TypeFamily>emptyList(),
                wholeSameScopeExternalSurface);
    }

    public ExternalChannelDependencySnapshot(
            List<String> intrinsicNodeBlueIds,
            List<Entry> entries,
            List<TypeFamily> typeFamilies,
            boolean wholeSameScopeExternalSurface) {
        this.intrinsicNodeBlueIds = immutableText(
                intrinsicNodeBlueIds, "intrinsic dependency");
        this.entries = immutableEntries(entries);
        this.typeFamilies = immutableTypeFamilies(typeFamilies);
        this.wholeSameScopeExternalSurface =
                wholeSameScopeExternalSurface;
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
                && !wholeSameScopeExternalSurface;
    }

    boolean covers(ExternalChannelDependencySnapshot demanded) {
        if (demanded == null || demanded.isEmpty()) {
            return true;
        }
        if (demanded.wholeSameScopeExternalSurface
                && !wholeSameScopeExternalSurface) {
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
                == snapshot.wholeSameScopeExternalSurface;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                intrinsicNodeBlueIds,
                entries,
                typeFamilies,
                wholeSameScopeExternalSurface);
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
