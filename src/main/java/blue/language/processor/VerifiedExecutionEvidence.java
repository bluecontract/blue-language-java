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

    public static Builder builder(String rootBlueId, String eventBlueId) {
        return new Builder(rootBlueId, eventBlueId);
    }

    public String rootBlueId() {
        return rootBlueId;
    }

    public String eventBlueId() {
        return eventBlueId;
    }

    public long managedRootRevision() {
        return managedRootRevision;
    }

    public long indexedRootRevision() {
        return indexedRootRevision;
    }

    public String runtimeRegistryIdentity() {
        return runtimeRegistryIdentity;
    }

    public ExternalOrderKey eventOrderKey() {
        return eventOrderKey;
    }

    public List<ExternalDeliverySnapshot> deliveries() {
        return deliveries;
    }

    /**
     * Complete active subscription-index surface retained at
     * {@link #indexedRootRevision()}, when supplied by the feeder.
     */
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
     */
    public void revalidate(Node root, Node event, String expectedRuntimeRegistryIdentity) {
        revalidate(root,
                event,
                expectedRuntimeRegistryIdentity,
                RootExternalDeliveryEvidenceVerifier.INSTANCE);
    }

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
        String actualRoot = BlueIdCalculator.calculateBlueId(root);
        String actualEvent = BlueIdCalculator.calculateBlueId(event);
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
                    interval.scopePath() + "\u0000"
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
        if ("/".equals(scope)) {
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

        public Builder revisions(long managed, long indexed) {
            this.managedRootRevision = managed;
            this.indexedRootRevision = indexed;
            return this;
        }

        public Builder runtimeRegistryIdentity(String identity) {
            this.runtimeRegistryIdentity = identity;
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
            availableExactNodeBlueIds.add(requireText(blueId, "available exact BlueId"));
            return this;
        }

        public Builder requiredExactNode(String blueId) {
            requiredExactNodeBlueIds.add(requireText(blueId, "required exact BlueId"));
            return this;
        }

        public VerifiedExecutionEvidence build() {
            return new VerifiedExecutionEvidence(this);
        }
    }
}
