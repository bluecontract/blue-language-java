package blue.language.processor;

import blue.language.processor.util.PointerUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Revision-bound feeder-derived evidence for one preselected External Channel
 * occurrence.
 *
 * <p>The snapshot binds exact source contributions, subscription keys,
 * checkpoint subject/domain, and activation interval. It is immutable input to
 * core verification rather than permission to re-read feeder state.</p>
 */
public final class ExternalDeliverySnapshot {

    private final String scopePath;
    private final String channelKey;
    private final int order;
    private final List<String> sourceContributionNodeBlueIds;
    private final String effectiveTypeBlueId;
    private final List<String> subscriptionKeys;
    private final String checkpointDomainBlueId;
    private final String checkpointSubjectBlueId;
    private final ExternalOrderKey activationStartExclusive;
    private final ExternalOrderKey activationEndInclusive;

    private ExternalDeliverySnapshot(Builder builder) {
        this.scopePath = PointerUtils.normalizeScope(builder.scopePath);
        this.channelKey = requireText(builder.channelKey, "channelKey");
        this.order = builder.order;
        this.sourceContributionNodeBlueIds = immutableUnique(
                builder.sourceContributionNodeBlueIds, "source contribution");
        this.effectiveTypeBlueId = requireText(builder.effectiveTypeBlueId,
                "effectiveTypeBlueId");
        this.subscriptionKeys = immutableUnique(builder.subscriptionKeys,
                "subscription key");
        this.checkpointDomainBlueId = requireText(builder.checkpointDomainBlueId,
                "checkpointDomainBlueId");
        this.checkpointSubjectBlueId = requireText(builder.checkpointSubjectBlueId,
                "checkpointSubjectBlueId");
        this.activationStartExclusive = builder.activationStartExclusive;
        this.activationEndInclusive = builder.activationEndInclusive;
        if (activationStartExclusive != null && activationEndInclusive != null
                && activationStartExclusive.compareTo(activationEndInclusive) >= 0) {
            throw new IllegalArgumentException(
                    "External delivery activation interval must be non-empty");
        }
    }

    /**
     * Creates a mutable accumulator for one scope-local delivery.
     *
     * @param scopePath owning scope
     * @param channelKey exact channel key
     * @return a new delivery builder
     * @throws NullPointerException if {@code scopePath} or
     *         {@code channelKey} is {@code null}
     */
    public static Builder builder(String scopePath, String channelKey) {
        return new Builder(scopePath, channelKey);
    }

    /**
     * Returns the normalized scope that owns the selected channel.
     *
     * @return normalized absolute owning scope
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the selected channel's scope-local key.
     *
     * @return exact non-empty channel key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns the delivery's stable dispatch position.
     *
     * @return deterministic delivery order
     */
    public int order() {
        return order;
    }

    /**
     * Returns identities of exact nodes contributing to this delivery.
     *
     * @return immutable unique contribution identities in source order
     */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /**
     * Returns the effective type that selected the external runtime.
     *
     * @return exact effective runtime type BlueId
     */
    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    /**
     * Returns the finite subscription keys matched by the delivery.
     *
     * @return immutable unique keys in runtime-defined order
     */
    public List<String> subscriptionKeys() {
        return subscriptionKeys;
    }

    /**
     * Returns the domain that makes checkpoint subjects comparable.
     *
     * @return exact checkpoint-domain BlueId
     */
    public String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    /**
     * Returns the exact checkpoint position for this occurrence.
     *
     * @return exact checkpoint-subject BlueId
     */
    public String checkpointSubjectBlueId() {
        return checkpointSubjectBlueId;
    }

    /**
     * Returns the lower activation bound.
     *
     * @return exclusive activation start, or {@code null} when unbounded
     */
    public ExternalOrderKey activationStartExclusive() {
        return activationStartExclusive;
    }

    /**
     * Returns the upper activation bound.
     *
     * @return inclusive activation end, or {@code null} when unbounded
     */
    public ExternalOrderKey activationEndInclusive() {
        return activationEndInclusive;
    }

    /**
     * Tests the event position against this half-open/closed activation
     * interval.
     *
     * @param eventOrderKey exact event order key
     * @return whether the event lies in the activation interval
     * @throws NullPointerException if {@code eventOrderKey} is {@code null}
     */
    public boolean activeAt(ExternalOrderKey eventOrderKey) {
        Objects.requireNonNull(eventOrderKey, "eventOrderKey");
        return (activationStartExclusive == null
                || eventOrderKey.compareTo(activationStartExclusive) > 0)
                && (activationEndInclusive == null
                || eventOrderKey.compareTo(activationEndInclusive) <= 0);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }

    private static List<String> immutableUnique(List<String> values, String label) {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isEmpty() || !unique.add(value)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate " + label + ": " + value);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    /** Mutable, single-use accumulator for one delivery snapshot. */
    public static final class Builder {
        private final String scopePath;
        private final String channelKey;
        private int order;
        private final List<String> sourceContributionNodeBlueIds = new ArrayList<>();
        private String effectiveTypeBlueId;
        private final List<String> subscriptionKeys = new ArrayList<>();
        private String checkpointDomainBlueId;
        private String checkpointSubjectBlueId;
        private ExternalOrderKey activationStartExclusive;
        private ExternalOrderKey activationEndInclusive;

        private Builder(String scopePath, String channelKey) {
            this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
            this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        }

        /**
         * Sets the deterministic delivery position.
         *
         * @param order deterministic delivery order
         * @return this builder
         */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        /**
         * Appends one exact source contribution identity.
         *
         * @param blueId source contribution BlueId
         * @return this builder
         */
        public Builder sourceContribution(String blueId) {
            sourceContributionNodeBlueIds.add(blueId);
            return this;
        }

        /**
         * Sets the exact effective runtime type.
         *
         * @param blueId effective runtime type BlueId
         * @return this builder
         */
        public Builder effectiveTypeBlueId(String blueId) {
            this.effectiveTypeBlueId = blueId;
            return this;
        }

        /**
         * Appends one finite subscription key.
         *
         * @param key subscription key
         * @return this builder
         */
        public Builder subscriptionKey(String key) {
            subscriptionKeys.add(key);
            return this;
        }

        /**
         * Sets the exact checkpoint domain.
         *
         * @param blueId checkpoint-domain BlueId
         * @return this builder
         */
        public Builder checkpointDomainBlueId(String blueId) {
            this.checkpointDomainBlueId = blueId;
            return this;
        }

        /**
         * Sets the exact checkpoint subject.
         *
         * @param blueId checkpoint-subject BlueId
         * @return this builder
         */
        public Builder checkpointSubjectBlueId(String blueId) {
            this.checkpointSubjectBlueId = blueId;
            return this;
        }

        /**
         * Sets or clears the exclusive lower activation bound.
         *
         * @param key exclusive activation start, or {@code null} for no lower
         *        bound
         * @return this builder
         */
        public Builder activationStartExclusive(ExternalOrderKey key) {
            this.activationStartExclusive = key;
            return this;
        }

        /**
         * Sets or clears the inclusive upper activation bound.
         *
         * @param key inclusive activation end, or {@code null} for no upper
         *        bound
         * @return this builder
         */
        public Builder activationEndInclusive(ExternalOrderKey key) {
            this.activationEndInclusive = key;
            return this;
        }

        /**
         * Validates all accumulated evidence and freezes the snapshot.
         *
         * @return validated immutable delivery snapshot
         * @throws IllegalArgumentException for missing, empty, duplicate
         *         identities or an empty activation interval
         */
        public ExternalDeliverySnapshot build() {
            return new ExternalDeliverySnapshot(this);
        }
    }
}
