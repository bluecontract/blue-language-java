package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic pre-commit change to the managed external subscription index.
 *
 * <p>An entry includes immutable External Channel dependency evidence.
 * Dependency changes retire and re-add the occurrence even when its raw key
 * and subscription keys remain unchanged.</p>
 */
public final class SubscriptionDelta {

    private static final SubscriptionDelta EMPTY =
            new SubscriptionDelta(Collections.emptyList(), Collections.emptyList());

    private final List<Entry> added;
    private final List<Entry> removed;

    /**
     * Creates a canonically ordered immutable delta.
     *
     * @param added newly active subscription occurrences
     * @param removed retired subscription occurrences
     * @throws NullPointerException when either list or one of its entries is null
     * @throws IllegalArgumentException when an occurrence is duplicated
     */
    public SubscriptionDelta(List<Entry> added, List<Entry> removed) {
        this.added = immutable(added);
        this.removed = immutable(removed);
    }

    /**
     * Returns the allocation-free delta used when no subscriptions changed.
     *
     * @return shared empty immutable delta
     */
    public static SubscriptionDelta empty() {
        return EMPTY;
    }

    /**
     * Returns occurrences that become active at commit.
     *
     * @return canonically ordered immutable additions
     */
    public List<Entry> added() {
        return added;
    }

    /**
     * Returns occurrences that retire at commit.
     *
     * @return canonically ordered immutable removals
     */
    public List<Entry> removed() {
        return removed;
    }

    /**
     * Reports whether committing this delta changes no subscription.
     *
     * @return whether both sides of the delta are empty
     */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty();
    }

    private static List<Entry> immutable(List<Entry> source) {
        Objects.requireNonNull(source, "source");
        List<Entry> copy = new ArrayList<>(source);
        copy.sort(Entry.CANONICAL_ORDER);
        Set<String> occurrences = new HashSet<>();
        for (Entry entry : copy) {
            Objects.requireNonNull(entry, "subscription delta entry");
            if (!occurrences.add(entry.occurrenceKey())) {
                throw new IllegalArgumentException(
                        "Duplicate subscription occurrence: "
                                + entry.scopePath + "/" + entry.channelKey);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Immutable canonical subscription occurrence and optional active interval.
     */
    public static final class Entry {
        private static final Comparator<Entry> CANONICAL_ORDER =
                (left, right) -> {
                    int comparison =
                            ExternalOrderKey.compareTextCodePoints(
                                    left.scopePath, right.scopePath);
                    if (comparison != 0) return comparison;
                    comparison = Integer.compare(left.order, right.order);
                    if (comparison != 0) return comparison;
                    comparison =
                            ExternalOrderKey.compareTextCodePoints(
                                    left.channelKey, right.channelKey);
                    if (comparison != 0) return comparison;
                    return ExternalOrderKey.compareTextCodePoints(
                            left.effectiveTypeBlueId,
                            right.effectiveTypeBlueId);
                };

        private final String scopePath;
        private final String channelKey;
        private final String effectiveTypeBlueId;
        private final List<String> sourceContributionNodeBlueIds;
        private final int order;
        private final List<String> subscriptionKeys;
        private final String checkpointDomainBlueId;
        private final ExternalChannelDependencySnapshot dependencies;
        private final Long activationRootRevision;
        private final ExternalOrderKey startAfterExternalOrderKey;
        private final Long endAtRootRevision;

        /**
         * Creates an unversioned occurrence without dependency evidence.
         *
         * @param scopePath absolute scope path
         * @param channelKey raw channel key
         * @param effectiveTypeBlueId effective external-channel type
         * @param subscriptionKeys immutable logical subscription keys
         * @param checkpointDomainBlueId checkpoint-domain identity
         * @throws NullPointerException when a required identity or list is null
         * @throws IllegalArgumentException when a key is empty or duplicated
         */
        public Entry(String scopePath,
                     String channelKey,
                     String effectiveTypeBlueId,
                     List<String> subscriptionKeys,
                     String checkpointDomainBlueId) {
            this(scopePath,
                    channelKey,
                    effectiveTypeBlueId,
                    Collections.<String>emptyList(),
                    0,
                    subscriptionKeys,
                    checkpointDomainBlueId,
                    ExternalChannelDependencySnapshot.none(),
                    null,
                    null,
                    null);
        }

        /**
         * Creates an ordered unversioned occurrence.
         *
         * @param scopePath absolute scope path
         * @param channelKey raw channel key
         * @param effectiveTypeBlueId effective external-channel type
         * @param sourceContributionNodeBlueIds ordered exact source identities
         * @param order canonical contract order
         * @param subscriptionKeys logical subscription keys
         * @param checkpointDomainBlueId checkpoint-domain identity
         * @param startAfterExternalOrderKey lower exclusive delivery order
         * @throws NullPointerException when a required identity or list is null
         * @throws IllegalArgumentException when an identity list is invalid
         */
        public Entry(String scopePath,
                     String channelKey,
                     String effectiveTypeBlueId,
                     List<String> sourceContributionNodeBlueIds,
                     int order,
                     List<String> subscriptionKeys,
                     String checkpointDomainBlueId,
                     ExternalOrderKey startAfterExternalOrderKey) {
            this(scopePath,
                    channelKey,
                    effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    order,
                    subscriptionKeys,
                    checkpointDomainBlueId,
                    ExternalChannelDependencySnapshot.none(),
                    null,
                    startAfterExternalOrderKey,
                    null);
        }

        /**
         * Creates a revision-bounded occurrence without dependency evidence.
         *
         * @param scopePath absolute scope path
         * @param channelKey raw channel key
         * @param effectiveTypeBlueId effective external-channel type
         * @param sourceContributionNodeBlueIds ordered exact source identities
         * @param order canonical contract order
         * @param subscriptionKeys logical subscription keys
         * @param checkpointDomainBlueId checkpoint-domain identity
         * @param activationRootRevision activation revision, or {@code null}
         * @param startAfterExternalOrderKey lower exclusive delivery order
         * @param endAtRootRevision retirement revision, or {@code null}
         * @throws NullPointerException when a required identity or list is null
         * @throws IllegalArgumentException when identities or interval bounds are invalid
         */
        public Entry(String scopePath,
                     String channelKey,
                     String effectiveTypeBlueId,
                     List<String> sourceContributionNodeBlueIds,
                     int order,
                     List<String> subscriptionKeys,
                     String checkpointDomainBlueId,
                     Long activationRootRevision,
                     ExternalOrderKey startAfterExternalOrderKey,
                     Long endAtRootRevision) {
            this(scopePath,
                    channelKey,
                    effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    order,
                    subscriptionKeys,
                    checkpointDomainBlueId,
                    ExternalChannelDependencySnapshot.none(),
                    activationRootRevision,
                    startAfterExternalOrderKey,
                    endAtRootRevision);
        }

        /**
         * Creates a fully evidenced revision-bounded occurrence.
         *
         * @param scopePath absolute scope path
         * @param channelKey raw channel key
         * @param effectiveTypeBlueId effective external-channel type
         * @param sourceContributionNodeBlueIds ordered exact source identities
         * @param order canonical contract order
         * @param subscriptionKeys logical subscription keys
         * @param checkpointDomainBlueId checkpoint-domain identity
         * @param dependencies immutable deterministic dependency evidence
         * @param activationRootRevision activation revision, or {@code null}
         * @param startAfterExternalOrderKey lower exclusive delivery order
         * @param endAtRootRevision retirement revision, or {@code null}
         * @throws NullPointerException when a required identity, list, or
         *         dependency snapshot is null
         * @throws IllegalArgumentException when identities or interval bounds are invalid
         */
        public Entry(
                String scopePath,
                String channelKey,
                String effectiveTypeBlueId,
                List<String> sourceContributionNodeBlueIds,
                int order,
                List<String> subscriptionKeys,
                String checkpointDomainBlueId,
                ExternalChannelDependencySnapshot dependencies,
                Long activationRootRevision,
                ExternalOrderKey startAfterExternalOrderKey,
                Long endAtRootRevision) {
            this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
            this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
            this.effectiveTypeBlueId =
                    Objects.requireNonNull(effectiveTypeBlueId, "effectiveTypeBlueId");
            this.sourceContributionNodeBlueIds =
                    immutableText(sourceContributionNodeBlueIds,
                            "source contribution");
            this.order = order;
            this.subscriptionKeys =
                    immutableText(subscriptionKeys, "subscription key");
            this.checkpointDomainBlueId =
                    Objects.requireNonNull(
                            checkpointDomainBlueId,
                            "checkpointDomainBlueId");
            this.dependencies = Objects.requireNonNull(
                    dependencies, "dependencies");
            requireRevision(
                    activationRootRevision, "activationRootRevision");
            this.startAfterExternalOrderKey = startAfterExternalOrderKey;
            requireRevision(endAtRootRevision, "endAtRootRevision");
            this.activationRootRevision = activationRootRevision;
            this.endAtRootRevision = endAtRootRevision;
            if (activationRootRevision != null
                    && endAtRootRevision != null
                    && endAtRootRevision.longValue()
                    < activationRootRevision.longValue()) {
                throw new IllegalArgumentException(
                        "Subscription interval ends before activation");
            }
        }

        /**
         * Returns the absolute scope that owns this occurrence.
         *
         * @return absolute participating scope path
         */
        public String scopePath() {
            return scopePath;
        }

        /**
         * Returns the exact raw key of the External Channel contract.
         *
         * @return raw channel contract key
         */
        public String channelKey() {
            return channelKey;
        }

        /**
         * Returns the effective runtime type used to derive the occurrence.
         *
         * @return effective external-channel type BlueId
         */
        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        /**
         * Returns exact Source identities in effective contribution order.
         *
         * @return immutable ordered exact source contribution identities
         */
        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        /**
         * Returns the order used when occurrences are canonically sorted.
         *
         * @return canonical contract order
         */
        public int order() {
            return order;
        }

        /**
         * Returns the finite logical keys selected by the channel runtime.
         *
         * @return immutable logical subscription keys
         */
        public List<String> subscriptionKeys() {
            return subscriptionKeys;
        }

        /**
         * Returns the identity of the domain that isolates checkpoint state.
         *
         * @return checkpoint-domain BlueId
         */
        public String checkpointDomainBlueId() {
            return checkpointDomainBlueId;
        }

        /**
         * Returns the exact dependencies consulted during subscription
         * derivation.
         *
         * @return immutable deterministic dependency evidence
         */
        public ExternalChannelDependencySnapshot dependencies() {
            return dependencies;
        }

        /**
         * Returns the Root revision at which this interval became active.
         *
         * @return activation root revision, or {@code null}
         */
        public Long activationRootRevision() {
            return activationRootRevision;
        }

        /**
         * Returns the exclusive event-order boundary for activation.
         *
         * @return exclusive lower external order bound, or {@code null}
         */
        public ExternalOrderKey startAfterExternalOrderKey() {
            return startAfterExternalOrderKey;
        }

        /**
         * Returns the Root revision at which this interval retired.
         *
         * @return retirement root revision, or {@code null}
         */
        public Long endAtRootRevision() {
            return endAtRootRevision;
        }

        /**
         * Returns whether this entry describes an interval that remains active
         * at the retained index revision.
         *
         * @return whether no retirement revision is present
         */
        public boolean isActiveInterval() {
            return endAtRootRevision == null;
        }

        /**
         * Compares the canonical subscription snapshot independently of its
         * activation/retirement interval metadata.
         */
        boolean sameSubscriptionSnapshot(Entry other) {
            return other != null
                    && scopePath.equals(other.scopePath)
                    && channelKey.equals(other.channelKey)
                    && effectiveTypeBlueId.equals(other.effectiveTypeBlueId)
                    && sourceContributionNodeBlueIds.equals(
                    other.sourceContributionNodeBlueIds)
                    && order == other.order
                    && subscriptionKeys.equals(other.subscriptionKeys)
                    && checkpointDomainBlueId.equals(
                    other.checkpointDomainBlueId)
                    && dependencies.equals(other.dependencies);
        }

        Entry activatedAt(long rootRevision,
                          ExternalOrderKey eventOrderKey) {
            return new Entry(
                    scopePath,
                    channelKey,
                    effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    order,
                    subscriptionKeys,
                    checkpointDomainBlueId,
                    dependencies,
                    rootRevision,
                    Objects.requireNonNull(
                            eventOrderKey, "eventOrderKey"),
                    null);
        }

        Entry retiredAt(long rootRevision) {
            return new Entry(
                    scopePath,
                    channelKey,
                    effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    order,
                    subscriptionKeys,
                    checkpointDomainBlueId,
                    dependencies,
                    activationRootRevision,
                    startAfterExternalOrderKey,
                    rootRevision);
        }

        String occurrenceKey() {
            return scopePath
                    + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                    + channelKey;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) other;
            return scopePath.equals(entry.scopePath)
                    && channelKey.equals(entry.channelKey)
                    && effectiveTypeBlueId.equals(entry.effectiveTypeBlueId)
                    && sourceContributionNodeBlueIds.equals(
                    entry.sourceContributionNodeBlueIds)
                    && order == entry.order
                    && subscriptionKeys.equals(entry.subscriptionKeys)
                    && checkpointDomainBlueId.equals(
                    entry.checkpointDomainBlueId)
                    && dependencies.equals(entry.dependencies)
                    && Objects.equals(activationRootRevision,
                    entry.activationRootRevision)
                    && Objects.equals(startAfterExternalOrderKey,
                    entry.startAfterExternalOrderKey)
                    && Objects.equals(endAtRootRevision,
                    entry.endAtRootRevision);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    scopePath,
                    channelKey,
                    effectiveTypeBlueId,
                    sourceContributionNodeBlueIds,
                    order,
                    subscriptionKeys,
                    checkpointDomainBlueId,
                    dependencies,
                    activationRootRevision,
                    startAfterExternalOrderKey,
                    endAtRootRevision);
        }

        private static void requireRevision(Long revision,
                                            String label) {
            if (revision != null && revision.longValue() < 0L) {
                throw new IllegalArgumentException(
                        label + " must be non-negative");
            }
        }

        private static List<String> immutableText(List<String> source,
                                                  String label) {
            Objects.requireNonNull(source, label);
            List<String> copy = new ArrayList<>(source.size());
            Set<String> unique = new HashSet<>();
            for (String value : source) {
                if (value == null || value.isEmpty()
                        || !unique.add(value)) {
                    throw new IllegalArgumentException(
                            "Invalid or duplicate " + label + ": " + value);
                }
                copy.add(value);
            }
            return Collections.unmodifiableList(copy);
        }
    }
}
