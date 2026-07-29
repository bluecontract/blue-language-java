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

    /**
     * Creates an empty mutable accumulator for one plan.
     *
     * @return new delivery-plan builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the managed Root revision observed during derivation.
     *
     * @return non-negative managed Root revision
     */
    public long managedRootRevision() {
        return managedRootRevision;
    }

    /**
     * Returns the Root revision represented by the subscription index.
     *
     * @return non-negative indexed Root revision
     */
    public long indexedRootRevision() {
        return indexedRootRevision;
    }

    /**
     * Returns the total-order position of the incoming event.
     *
     * @return immutable event order key
     */
    public ExternalOrderKey eventOrderKey() {
        return eventOrderKey;
    }

    /**
     * Returns the complete preselected delivery surface.
     *
     * @return immutable delivery snapshots in derivation order
     */
    public List<ExternalDeliverySnapshot> deliveries() {
        return deliveries;
    }

    /**
     * Returns retained subscription intervals active in the indexed revision.
     *
     * @return immutable active interval list
     */
    public List<SubscriptionDelta.Entry> activeSubscriptionIntervals() {
        return activeSubscriptionIntervals;
    }

    /**
     * Reports whether the deriver supplied the complete interval surface,
     * including an explicitly empty surface.
     *
     * @return {@code true} when interval evidence was supplied
     */
    public boolean hasActiveSubscriptionIntervals() {
        return activeSubscriptionIntervalsSupplied;
    }

    /**
     * Returns exact node identities available to execution.
     *
     * @return immutable insertion-ordered identity set
     */
    public Set<String> availableExactNodeBlueIds() {
        return availableExactNodeBlueIds;
    }

    /**
     * Returns exact node identities execution must be able to open.
     *
     * @return immutable insertion-ordered identity set
     */
    public Set<String> requiredExactNodeBlueIds() {
        return requiredExactNodeBlueIds;
    }

    /**
     * Reports whether complete environmental runtime state was certified.
     *
     * @return {@code true} when the deriver set the completeness certificate
     */
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

    /** Mutable, single-use accumulator for a revision-bound delivery plan. */
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

        /**
         * Records the managed and subscription-index revisions that must
         * agree when the plan is built.
         *
         * @param managed managed Root revision
         * @param indexed subscription-index Root revision
         * @return this builder
         */
        public Builder revisions(long managed, long indexed) {
            this.managedRootRevision = managed;
            this.indexedRootRevision = indexed;
            return this;
        }

        /**
         * Binds the incoming event's immutable total-order position.
         *
         * @param key immutable total-order event key
         * @return this builder
         */
        public Builder eventOrderKey(ExternalOrderKey key) {
            this.eventOrderKey = key;
            return this;
        }

        /**
         * Appends one preselected delivery in deterministic derivation order.
         *
         * @param snapshot immutable preselected delivery
         * @return this builder
         * @throws NullPointerException if {@code snapshot} is {@code null}
         */
        public Builder delivery(ExternalDeliverySnapshot snapshot) {
            deliveries.add(Objects.requireNonNull(snapshot, "snapshot"));
            return this;
        }

        /**
         * Appends one retained subscription interval and marks the interval
         * surface as supplied.
         *
         * @param interval one retained active subscription interval
         * @return this builder
         * @throws NullPointerException if {@code interval} is {@code null}
         */
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
         *
         * @param intervals complete retained interval surface
         * @return this builder
         * @throws NullPointerException if {@code intervals} or any contained
         *         interval is {@code null}
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

        /**
         * Adds one exact node identity available to execution.
         *
         * @param blueId exact node identity available to execution
         * @return this builder
         * @throws IllegalArgumentException if {@code blueId} is {@code null}
         *         or empty
         */
        public Builder availableExactNode(String blueId) {
            availableExactNodeBlueIds.add(
                    requireText(blueId, "available exact BlueId"));
            return this;
        }

        /**
         * Adds one exact node identity required by execution.
         *
         * @param blueId exact node identity required by execution
         * @return this builder
         * @throws IllegalArgumentException if {@code blueId} is {@code null}
         *         or empty
         */
        public Builder requiredExactNode(String blueId) {
            requiredExactNodeBlueIds.add(
                    requireText(blueId, "required exact BlueId"));
            return this;
        }

        /**
         * Certifies that the deriver evaluated the complete environmental
         * subscription and activation state, including an exact empty result.
         *
         * @return this builder
         */
        public Builder exactRuntimeState() {
            this.exactRuntimeState = true;
            return this;
        }

        /**
         * Validates revision completeness and freezes the plan.
         *
         * @return immutable plan
         * @throws IllegalArgumentException for revision or activation mismatch
         * @throws NullPointerException when no event order key was supplied
         */
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
