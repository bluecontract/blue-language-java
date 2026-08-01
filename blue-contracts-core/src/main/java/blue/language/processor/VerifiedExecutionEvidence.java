package blue.language.processor;

import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Verified revision-bound execution evidence. It is environment metadata, not
 * a third semantic input to PROCESS.
 */
public final class VerifiedExecutionEvidence {

    private final String rootBlueId;
    private final String eventBlueId;
    private final long managedRootRevision;
    private final long indexedRootRevision;
    private final String runtimeRegistryIdentity;
    private final ExternalOrderKey eventOrderKey;
    private final List<ExternalDeliverySnapshot> deliveries;
    private final List<SubscriptionDelta.Entry> activeSubscriptionIntervals;
    private final boolean activeSubscriptionIntervalsSupplied;
    private final Set<String> availableExactNodeBlueIds;
    private final Set<String> requiredExactNodeBlueIds;

    private VerifiedExecutionEvidence(Builder builder) {
        this.rootBlueId = requireText(builder.rootBlueId, "rootBlueId");
        this.eventBlueId = requireText(builder.eventBlueId, "eventBlueId");
        if (builder.managedRootRevision < 0L || builder.indexedRootRevision < 0L) {
            throw new IllegalArgumentException("Root revisions must be non-negative");
        }
        this.managedRootRevision = builder.managedRootRevision;
        this.indexedRootRevision = builder.indexedRootRevision;
        this.runtimeRegistryIdentity =
                requireText(builder.runtimeRegistryIdentity, "runtimeRegistryIdentity");
        this.eventOrderKey = Objects.requireNonNull(builder.eventOrderKey, "eventOrderKey");
        this.deliveries = Collections.unmodifiableList(
                new ArrayList<>(builder.deliveries));
        this.activeSubscriptionIntervals =
                immutableActiveIntervals(
                        builder.activeSubscriptionIntervals);
        this.activeSubscriptionIntervalsSupplied =
                builder.activeSubscriptionIntervalsSupplied;
        this.availableExactNodeBlueIds = immutableSet(builder.availableExactNodeBlueIds);
        this.requiredExactNodeBlueIds = immutableSet(builder.requiredExactNodeBlueIds);
        if (managedRootRevision != indexedRootRevision) {
            throw new IllegalArgumentException(
                    "Execution evidence is not revision-complete");
        }
        for (ExternalDeliverySnapshot delivery : deliveries) {
            if (!delivery.activeAt(eventOrderKey)) {
                throw new IllegalArgumentException(
                        "Delivery is outside its activation interval: "
                                + delivery.scopePath() + "/" + delivery.channelKey());
            }
        }
    }

    /**
     * Creates a builder bound to exact semantic input identities.
     *
     * @param rootBlueId exact Root BlueId
     * @param eventBlueId exact event BlueId
     * @return new evidence builder
     */
    public static Builder builder(String rootBlueId, String eventBlueId) {
        return new Builder(rootBlueId, eventBlueId);
    }

    /**
     * Returns the exact Root identity bound by this evidence.
     *
     * @return non-empty Root BlueId
     */
    public String rootBlueId() {
        return rootBlueId;
    }

    /**
     * Returns the exact event identity bound by this evidence.
     *
     * @return non-empty event BlueId
     */
    public String eventBlueId() {
        return eventBlueId;
    }

    /**
     * Returns the feeder's managed Root revision.
     *
     * @return non-negative managed revision
     */
    public long managedRootRevision() {
        return managedRootRevision;
    }

    /**
     * Returns the subscription-index Root revision.
     *
     * @return non-negative indexed revision equal to the managed revision
     */
    public long indexedRootRevision() {
        return indexedRootRevision;
    }

    /**
     * Returns the identity of the runtime registry used to derive evidence.
     *
     * @return non-empty runtime registry identity
     */
    public String runtimeRegistryIdentity() {
        return runtimeRegistryIdentity;
    }

    /**
     * Returns the exact total-order position of the event.
     *
     * @return immutable event order key
     */
    public ExternalOrderKey eventOrderKey() {
        return eventOrderKey;
    }

    /**
     * Returns the revision-bound preselected deliveries.
     *
     * @return immutable delivery list in deterministic order
     */
    public List<ExternalDeliverySnapshot> deliveries() {
        return deliveries;
    }

    /**
     * Complete active subscription-index surface retained at
     * {@link #indexedRootRevision()}, when supplied by the feeder.
     *
     * @return immutable retained interval list
     */
    public List<SubscriptionDelta.Entry> activeSubscriptionIntervals() {
        return activeSubscriptionIntervals;
    }

    /**
     * Reports whether the complete active interval surface was supplied.
     *
     * @return {@code true} for supplied evidence, including an empty surface
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
     * Returns exact node identities required by execution.
     *
     * @return immutable insertion-ordered identity set
     */
    public Set<String> requiredExactNodeBlueIds() {
        return requiredExactNodeBlueIds;
    }

    /**
     * Calculates required identities absent from the available set.
     *
     * @return immutable sorted list of missing exact BlueIds
     */
    public List<String> missingRequiredExactNodeBlueIds() {
        List<String> missing = new ArrayList<>();
        for (String required : requiredExactNodeBlueIds) {
            if (!availableExactNodeBlueIds.contains(required)) {
                missing.add(required);
            }
        }
        Collections.sort(missing);
        return Collections.unmodifiableList(missing);
    }

    /**
     * Revalidates binding to the exact semantic inputs.
     *
     * @param root exact Root to verify
     * @param event exact event to verify
     * @param expectedRuntimeRegistryIdentity expected registry identity, or
     *        {@code null} to skip that comparison
     * @throws NullPointerException if {@code root} or {@code event} is
     *         {@code null}
     * @throws InvalidExecutionEvidenceException if any identity or revision
     *         binding is invalid
     */
    public void revalidate(Node root, Node event, String expectedRuntimeRegistryIdentity) {
        revalidate(root,
                event,
                expectedRuntimeRegistryIdentity,
                RootExternalDeliveryEvidenceVerifier.INSTANCE);
    }

    /**
     * Revalidates semantic bindings and delegates environmental verification.
     *
     * @param root exact Root to verify
     * @param event exact event to verify
     * @param expectedRuntimeRegistryIdentity expected registry identity, or
     *        {@code null}
     * @param deliveryVerifier non-null environmental evidence verifier
     * @throws NullPointerException if a required input or verifier is
     *         {@code null}
     * @throws InvalidExecutionEvidenceException if binding or environmental
     *         verification fails
     */
    public void revalidate(Node root,
                           Node event,
                           String expectedRuntimeRegistryIdentity,
                           ExternalDeliveryEvidenceVerifier deliveryVerifier) {
        revalidateBinding(root, event, expectedRuntimeRegistryIdentity);
        Objects.requireNonNull(deliveryVerifier, "deliveryVerifier")
                .verify(root, event, this);
    }

    void revalidateDerived(Node root,
                           Node event,
                           String expectedRuntimeRegistryIdentity,
                           ExternalDeliveryEvidenceVerifier deliveryVerifier,
                           ExternalDeliveryPlan derivedPlan) {
        revalidateBinding(root, event, expectedRuntimeRegistryIdentity);
        Objects.requireNonNull(deliveryVerifier, "deliveryVerifier")
                .verifyDerived(root, event, this,
                        Objects.requireNonNull(derivedPlan, "derivedPlan"));
    }

    void revalidateBinding(Node root,
                           Node event,
                           String expectedRuntimeRegistryIdentity) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        String actualRoot = DirectBlueIdCalculator.calculateBlueId(root);
        String actualEvent = DirectBlueIdCalculator.calculateBlueId(event);
        if (!rootBlueId.equals(actualRoot) || !eventBlueId.equals(actualEvent)) {
            throw new InvalidExecutionEvidenceException(
                    "Execution evidence does not bind to the exact Root and event");
        }
        if (expectedRuntimeRegistryIdentity != null
                && !runtimeRegistryIdentity.equals(expectedRuntimeRegistryIdentity)) {
            throw new InvalidExecutionEvidenceException(
                    "Execution evidence runtime registry identity mismatch");
        }
        if (managedRootRevision != indexedRootRevision) {
            throw new InvalidExecutionEvidenceException(
                    "Execution evidence index is not revision-complete");
        }
    }

    private static Set<String> immutableSet(Set<String> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private static List<SubscriptionDelta.Entry> immutableActiveIntervals(
            List<SubscriptionDelta.Entry> source) {
        List<SubscriptionDelta.Entry> copy =
                new ArrayList<>(source);
        Set<String> occurrences = new LinkedHashSet<>();
        for (SubscriptionDelta.Entry interval : copy) {
            Objects.requireNonNull(
                    interval, "active subscription interval");
            if (!interval.isActiveInterval()) {
                throw new IllegalArgumentException(
                        "Execution evidence contains a retired subscription "
                                + "interval");
            }
            String occurrence =
                    interval.scopePath()
                            + ProcessorIdentityConstants
                                    .SELECTOR_COMPONENT_DELIMITER
                            + interval.channelKey();
            if (!occurrences.add(occurrence)) {
                throw new IllegalArgumentException(
                        "Execution evidence contains duplicate active "
                                + "subscription occurrence: "
                                + interval.scopePath() + "/"
                                + interval.channelKey());
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }

    private static int scopeDepth(String scope) {
        if (JsonPointer.ROOT.equals(scope)) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < scope.length(); i++) {
            if (scope.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    /** Mutable accumulator for one immutable evidence bundle. */
    public static final class Builder {
        private final String rootBlueId;
        private final String eventBlueId;
        private long managedRootRevision;
        private long indexedRootRevision;
        private String runtimeRegistryIdentity;
        private ExternalOrderKey eventOrderKey;
        private final List<ExternalDeliverySnapshot> deliveries = new ArrayList<>();
        private final List<SubscriptionDelta.Entry>
                activeSubscriptionIntervals = new ArrayList<>();
        private boolean activeSubscriptionIntervalsSupplied;
        private final Set<String> availableExactNodeBlueIds = new LinkedHashSet<>();
        private final Set<String> requiredExactNodeBlueIds = new LinkedHashSet<>();

        private Builder(String rootBlueId, String eventBlueId) {
            this.rootBlueId = rootBlueId;
            this.eventBlueId = eventBlueId;
        }

        /**
         * Sets the managed and indexed revisions that must agree.
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
         * Sets the runtime registry identity.
         *
         * @param identity non-empty registry identity
         * @return this builder
         */
        public Builder runtimeRegistryIdentity(String identity) {
            this.runtimeRegistryIdentity = identity;
            return this;
        }

        /**
         * Sets the immutable event order key.
         *
         * @param key event order key
         * @return this builder
         */
        public Builder eventOrderKey(ExternalOrderKey key) {
            this.eventOrderKey = key;
            return this;
        }

        /**
         * Appends one revision-bound delivery.
         *
         * @param snapshot non-null delivery snapshot
         * @return this builder
         * @throws NullPointerException if {@code snapshot} is {@code null}
         */
        public Builder delivery(ExternalDeliverySnapshot snapshot) {
            deliveries.add(Objects.requireNonNull(snapshot, "snapshot"));
            return this;
        }

        /**
         * Appends one retained active subscription interval.
         *
         * @param interval non-null active interval
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
         * @param intervals complete interval surface
         * @return this builder
         * @throws NullPointerException if {@code intervals} or an interval is
         *         {@code null}
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
         * Adds one exact identity available to execution.
         *
         * @param blueId non-empty available BlueId
         * @return this builder
         * @throws IllegalArgumentException if {@code blueId} is empty or
         *         {@code null}
         */
        public Builder availableExactNode(String blueId) {
            availableExactNodeBlueIds.add(requireText(blueId, "available exact BlueId"));
            return this;
        }

        /**
         * Adds one exact identity required by execution.
         *
         * @param blueId non-empty required BlueId
         * @return this builder
         * @throws IllegalArgumentException if {@code blueId} is empty or
         *         {@code null}
         */
        public Builder requiredExactNode(String blueId) {
            requiredExactNodeBlueIds.add(requireText(blueId, "required exact BlueId"));
            return this;
        }

        /**
         * Validates and freezes the evidence bundle.
         *
         * @return immutable verified execution evidence
         * @throws IllegalArgumentException for invalid identities, revisions,
         *         deliveries, or active intervals
         * @throws NullPointerException if the event order key is absent
         */
        public VerifiedExecutionEvidence build() {
            return new VerifiedExecutionEvidence(this);
        }
    }
}
