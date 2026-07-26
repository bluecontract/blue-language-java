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
 */
public final class SubscriptionDelta {

    private static final SubscriptionDelta EMPTY =
            new SubscriptionDelta(Collections.emptyList(), Collections.emptyList());

    private final List<Entry> added;
    private final List<Entry> removed;

    public SubscriptionDelta(List<Entry> added, List<Entry> removed) {
        this.added = immutable(added);
        this.removed = immutable(removed);
    }

    public static SubscriptionDelta empty() {
        return EMPTY;
    }

    public List<Entry> added() {
        return added;
    }

    public List<Entry> removed() {
        return removed;
    }

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
        private final Long activationRootRevision;
        private final ExternalOrderKey startAfterExternalOrderKey;
        private final Long endAtRootRevision;

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
                    null,
                    null,
                    null);
        }

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
                    null,
                    startAfterExternalOrderKey,
                    null);
        }

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

        public String scopePath() {
            return scopePath;
        }

        public String channelKey() {
            return channelKey;
        }

        public String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        public List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        public int order() {
            return order;
        }

        public List<String> subscriptionKeys() {
            return subscriptionKeys;
        }

        public String checkpointDomainBlueId() {
            return checkpointDomainBlueId;
        }

        public Long activationRootRevision() {
            return activationRootRevision;
        }

        public ExternalOrderKey startAfterExternalOrderKey() {
            return startAfterExternalOrderKey;
        }

        public Long endAtRootRevision() {
            return endAtRootRevision;
        }

        /**
         * Returns whether this entry describes an interval that remains active
         * at the retained index revision.
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
                    other.checkpointDomainBlueId);
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
                    activationRootRevision,
                    startAfterExternalOrderKey,
                    rootRevision);
        }

        String occurrenceKey() {
            return scopePath + "\u0000" + channelKey;
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
