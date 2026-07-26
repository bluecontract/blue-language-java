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

    public static Builder builder(String scopePath, String channelKey) {
        return new Builder(scopePath, channelKey);
    }

    public String scopePath() {
        return scopePath;
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

    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    public List<String> subscriptionKeys() {
        return subscriptionKeys;
    }

    public String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    public String checkpointSubjectBlueId() {
        return checkpointSubjectBlueId;
    }

    public ExternalOrderKey activationStartExclusive() {
        return activationStartExclusive;
    }

    public ExternalOrderKey activationEndInclusive() {
        return activationEndInclusive;
    }

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

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder sourceContribution(String blueId) {
            sourceContributionNodeBlueIds.add(blueId);
            return this;
        }

        public Builder effectiveTypeBlueId(String blueId) {
            this.effectiveTypeBlueId = blueId;
            return this;
        }

        public Builder subscriptionKey(String key) {
            subscriptionKeys.add(key);
            return this;
        }

        public Builder checkpointDomainBlueId(String blueId) {
            this.checkpointDomainBlueId = blueId;
            return this;
        }

        public Builder checkpointSubjectBlueId(String blueId) {
            this.checkpointSubjectBlueId = blueId;
            return this;
        }

        public Builder activationStartExclusive(ExternalOrderKey key) {
            this.activationStartExclusive = key;
            return this;
        }

        public Builder activationEndInclusive(ExternalOrderKey key) {
            this.activationEndInclusive = key;
            return this;
        }

        public ExternalDeliverySnapshot build() {
            return new ExternalDeliverySnapshot(this);
        }
    }
}
