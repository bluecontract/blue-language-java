package blue.language.processor;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete, revision-bound environmental preselection for one PROCESS
 * invocation.
 *
 * <p>The plan is derived outside the semantic Root/event inputs.  A deriver
 * must set {@link Builder#exactRuntimeState()} only after it has evaluated the
 * complete runtime subscription surface, checkpoint subjects, and activation
 * intervals for the indexed Root revision.</p>
 */
public final class ExternalDeliveryPlan {

    private final long managedRootRevision;
    private final long indexedRootRevision;
    private final ExternalOrderKey eventOrderKey;
    private final List<ExternalDeliverySnapshot> deliveries;
    private final List<SubscriptionDelta.Entry> activeSubscriptionIntervals;
    private final boolean activeSubscriptionIntervalsSupplied;
    private final Set<String> availableExactNodeBlueIds;
    private final Set<String> requiredExactNodeBlueIds;
    private final boolean exactRuntimeState;

    private ExternalDeliveryPlan(Builder builder) {
        if (builder.managedRootRevision < 0L
                || builder.indexedRootRevision < 0L) {
            throw new IllegalArgumentException(
                    "Root revisions must be non-negative");
        }
        this.managedRootRevision = builder.managedRootRevision;
        this.indexedRootRevision = builder.indexedRootRevision;
        this.eventOrderKey = Objects.requireNonNull(
                builder.eventOrderKey, "eventOrderKey");
        this.deliveries = Collections.unmodifiableList(
                new ArrayList<>(builder.deliveries));
        this.activeSubscriptionIntervals =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                builder.activeSubscriptionIntervals));
        this.activeSubscriptionIntervalsSupplied =
                builder.activeSubscriptionIntervalsSupplied;
        this.availableExactNodeBlueIds = immutableSet(
                builder.availableExactNodeBlueIds);
        this.requiredExactNodeBlueIds = immutableSet(
                builder.requiredExactNodeBlueIds);
        this.exactRuntimeState = builder.exactRuntimeState;
        if (managedRootRevision != indexedRootRevision) {
            throw new IllegalArgumentException(
                    "External delivery plan is not revision-complete");
        }
        for (ExternalDeliverySnapshot delivery : deliveries) {
            if (!delivery.activeAt(eventOrderKey)) {
                throw new IllegalArgumentException(
                        "Delivery is outside its activation interval: "
                                + delivery.scopePath() + "/"
                                + delivery.channelKey());
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public long managedRootRevision() {
        return managedRootRevision;
    }

    public long indexedRootRevision() {
        return indexedRootRevision;
    }

    public ExternalOrderKey eventOrderKey() {
        return eventOrderKey;
    }

    public List<ExternalDeliverySnapshot> deliveries() {
        return deliveries;
    }

    public List<SubscriptionDelta.Entry> activeSubscriptionIntervals() {
        return activeSubscriptionIntervals;
    }

    public boolean hasActiveSubscriptionIntervals() {
        return activeSubscriptionIntervalsSupplied;
    }

    public Set<String> availableExactNodeBlueIds() {
        return availableExactNodeBlueIds;
    }

    public Set<String> requiredExactNodeBlueIds() {
        return requiredExactNodeBlueIds;
    }

    public boolean exactRuntimeState() {
        return exactRuntimeState;
    }

    VerifiedExecutionEvidence bind(Node root,
                                   Node event,
                                   String runtimeRegistryIdentity) {
        VerifiedExecutionEvidence.Builder evidence =
                VerifiedExecutionEvidence.builder(
                                BlueIdCalculator.calculateBlueId(root),
                                BlueIdCalculator.calculateBlueId(event))
                        .revisions(
                                managedRootRevision,
                                indexedRootRevision)
                        .runtimeRegistryIdentity(
                                Objects.requireNonNull(
                                        runtimeRegistryIdentity,
                                        "runtimeRegistryIdentity"))
                        .eventOrderKey(eventOrderKey);
        for (ExternalDeliverySnapshot delivery : deliveries) {
            evidence.delivery(delivery);
        }
        if (activeSubscriptionIntervalsSupplied) {
            evidence.activeSubscriptionIntervals(
                    activeSubscriptionIntervals);
        }
        for (String blueId : availableExactNodeBlueIds) {
            evidence.availableExactNode(blueId);
        }
        for (String blueId : requiredExactNodeBlueIds) {
            evidence.requiredExactNode(blueId);
        }
        return evidence.build();
    }

    private static Set<String> immutableSet(Set<String> source) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(source));
    }

    public static final class Builder {
        private long managedRootRevision;
        private long indexedRootRevision;
        private ExternalOrderKey eventOrderKey;
        private final List<ExternalDeliverySnapshot> deliveries =
                new ArrayList<>();
        private final List<SubscriptionDelta.Entry>
                activeSubscriptionIntervals = new ArrayList<>();
        private boolean activeSubscriptionIntervalsSupplied;
        private final Set<String> availableExactNodeBlueIds =
                new LinkedHashSet<>();
        private final Set<String> requiredExactNodeBlueIds =
                new LinkedHashSet<>();
        private boolean exactRuntimeState;

        private Builder() {
        }

        public Builder revisions(long managed, long indexed) {
            this.managedRootRevision = managed;
            this.indexedRootRevision = indexed;
            return this;
        }

        public Builder eventOrderKey(ExternalOrderKey key) {
            this.eventOrderKey = key;
            return this;
        }

        public Builder delivery(ExternalDeliverySnapshot snapshot) {
            deliveries.add(Objects.requireNonNull(snapshot, "snapshot"));
            return this;
        }

        public Builder activeSubscriptionInterval(
                SubscriptionDelta.Entry interval) {
            activeSubscriptionIntervalsSupplied = true;
            activeSubscriptionIntervals.add(Objects.requireNonNull(
                    interval, "active subscription interval"));
            return this;
        }

        /**
         * Supplies the complete retained active subscription-index surface,
         * including an exact empty surface.
         */
        public Builder activeSubscriptionIntervals(
                Iterable<SubscriptionDelta.Entry> intervals) {
            Objects.requireNonNull(intervals, "intervals");
            activeSubscriptionIntervals.clear();
            activeSubscriptionIntervalsSupplied = true;
            for (SubscriptionDelta.Entry interval : intervals) {
                activeSubscriptionIntervals.add(Objects.requireNonNull(
                        interval, "active subscription interval"));
            }
            return this;
        }

        public Builder availableExactNode(String blueId) {
            availableExactNodeBlueIds.add(
                    requireText(blueId, "available exact BlueId"));
            return this;
        }

        public Builder requiredExactNode(String blueId) {
            requiredExactNodeBlueIds.add(
                    requireText(blueId, "required exact BlueId"));
            return this;
        }

        /**
         * Certifies that the deriver evaluated the complete environmental
         * subscription and activation state, including an exact empty result.
         */
        public Builder exactRuntimeState() {
            this.exactRuntimeState = true;
            return this;
        }

        public ExternalDeliveryPlan build() {
            return new ExternalDeliveryPlan(this);
        }

        private static String requireText(String value, String label) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(
                        label + " must be non-empty");
            }
            return value;
        }
    }
}
