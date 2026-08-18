package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One closed Root-scoped subscription ADD, REMOVE, or REPLACE receipt. */
public final class SubscriptionDelta {

    /** Closed subscription operation. */
    public enum Operation {
        /** Add an absent subscription. */
        ADD,
        /** Remove a present subscription. */
        REMOVE,
        /** Replace a present subscription with different exact state. */
        REPLACE
    }

    private final long subscriptionDeltaOrdinal;
    private final Operation operation;
    private final String targetManagedScopeIdentity;
    private final String channelOccurrenceIdentity;
    private final SubscriptionState beforeSubscription;
    private final SubscriptionState afterSubscription;

    /**
     * Creates one complete closed subscription delta.
     *
     * @param subscriptionDeltaOrdinal contiguous canonical ordinal
     * @param operation closed operation
     * @param targetManagedScopeIdentity exact target Root identity
     * @param channelOccurrenceIdentity exact shared occurrence identity
     * @param beforeSubscription complete before side, or {@code null}
     * @param afterSubscription complete after side, or {@code null}
     */
    public SubscriptionDelta(
            long subscriptionDeltaOrdinal,
            Operation operation,
            String targetManagedScopeIdentity,
            String channelOccurrenceIdentity,
            SubscriptionState beforeSubscription,
            SubscriptionState afterSubscription) {
        this.subscriptionDeltaOrdinal =
                ClosureValueSupport.requireSafeInteger(
                        subscriptionDeltaOrdinal,
                        "subscriptionDeltaOrdinal");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.channelOccurrenceIdentity =
                ClosureValueSupport.requireSha256Identity(
                        channelOccurrenceIdentity,
                        "channelOccurrenceIdentity");
        this.beforeSubscription = beforeSubscription;
        this.afterSubscription = afterSubscription;
        validateClosedSides();
        ChannelOccurrence target = afterSubscription != null
                ? afterSubscription.channelOccurrence()
                : beforeSubscription.channelOccurrence();
        String asserted = ClosureValueSupport.requireSha256Identity(
                targetManagedScopeIdentity, "targetManagedScopeIdentity");
        String computed = ClosureIdentityService.INSTANCE
                .managedScopeKeyIdentity(
                        ManagedScopeKey.root(target.managedDocumentId()));
        if (!asserted.equals(computed)) {
            throw new IllegalArgumentException(
                    "targetManagedScopeIdentity does not identify the Channel Root");
        }
        this.targetManagedScopeIdentity = asserted;
    }

    /**
     * Returns contiguous canonical delta ordinal.
     *
     * @return contiguous canonical delta ordinal
     */
    public long subscriptionDeltaOrdinal() {
        return subscriptionDeltaOrdinal;
    }

    /**
     * Returns closed operation.
     *
     * @return closed operation
     */
    public Operation operation() {
        return operation;
    }

    /**
     * Returns exact target Root identity.
     *
     * @return exact target Root identity
     */
    public String targetManagedScopeIdentity() {
        return targetManagedScopeIdentity;
    }

    /**
     * Returns exact Channel occurrence identity.
     *
     * @return exact Channel occurrence identity
     */
    public String channelOccurrenceIdentity() {
        return channelOccurrenceIdentity;
    }

    /**
     * Returns before subscription identity, or {@code null}.
     *
     * @return before subscription identity, or {@code null}
     */
    public String beforeSubscriptionIdentity() {
        return beforeSubscription == null
                ? null : beforeSubscription.subscriptionIdentity();
    }

    /**
     * Returns after subscription identity, or {@code null}.
     *
     * @return after subscription identity, or {@code null}
     */
    public String afterSubscriptionIdentity() {
        return afterSubscription == null
                ? null : afterSubscription.subscriptionIdentity();
    }

    /**
     * Returns before document BlueId, or {@code null}.
     *
     * @return before document BlueId, or {@code null}
     */
    public String beforeDocumentBlueId() {
        return beforeSubscription == null
                ? null : beforeSubscription.documentBlueId();
    }

    /**
     * Returns after document BlueId, or {@code null}.
     *
     * @return after document BlueId, or {@code null}
     */
    public String afterDocumentBlueId() {
        return afterSubscription == null
                ? null : afterSubscription.documentBlueId();
    }

    /**
     * Returns before graph generation, or {@code null}.
     *
     * @return before graph generation, or {@code null}
     */
    public Long beforeGraphGeneration() {
        return beforeSubscription == null ? null
                : Long.valueOf(beforeSubscription.graphGeneration());
    }

    /**
     * Returns after graph generation, or {@code null}.
     *
     * @return after graph generation, or {@code null}
     */
    public Long afterGraphGeneration() {
        return afterSubscription == null ? null
                : Long.valueOf(afterSubscription.graphGeneration());
    }

    /**
     * Returns before component generation, or {@code null}.
     *
     * @return before component generation, or {@code null}
     */
    public Long beforeComponentGeneration() {
        return beforeSubscription == null ? null
                : Long.valueOf(beforeSubscription.componentGeneration());
    }

    /**
     * Returns after component generation, or {@code null}.
     *
     * @return after component generation, or {@code null}
     */
    public Long afterComponentGeneration() {
        return afterSubscription == null ? null
                : Long.valueOf(afterSubscription.componentGeneration());
    }

    /**
     * Returns complete before subscription, or {@code null}.
     *
     * @return complete before subscription, or {@code null}
     */
    public SubscriptionState beforeSubscription() {
        return beforeSubscription;
    }

    /**
     * Returns complete after subscription, or {@code null}.
     *
     * @return complete after subscription, or {@code null}
     */
    public SubscriptionState afterSubscription() {
        return afterSubscription;
    }

    Map<String, Object> identityValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("subscriptionDeltaOrdinal",
                Long.valueOf(subscriptionDeltaOrdinal));
        value.put("operation", operation.name());
        value.put("targetManagedScopeIdentity", targetManagedScopeIdentity);
        value.put("channelOccurrenceIdentity", channelOccurrenceIdentity);
        value.put("beforeSubscriptionIdentity",
                beforeSubscriptionIdentity());
        value.put("afterSubscriptionIdentity", afterSubscriptionIdentity());
        value.put("beforeDocumentBlueId", beforeDocumentBlueId());
        value.put("afterDocumentBlueId", afterDocumentBlueId());
        value.put("beforeGraphGeneration", beforeGraphGeneration());
        value.put("afterGraphGeneration", afterGraphGeneration());
        value.put("beforeComponentGeneration", beforeComponentGeneration());
        value.put("afterComponentGeneration", afterComponentGeneration());
        return value;
    }

    private void validateClosedSides() {
        if ((operation == Operation.ADD
                && (beforeSubscription != null
                || afterSubscription == null))
                || (operation == Operation.REMOVE
                && (beforeSubscription == null
                || afterSubscription != null))
                || (operation == Operation.REPLACE
                && (beforeSubscription == null
                || afterSubscription == null))) {
            throw new IllegalArgumentException(
                    "Subscription operation disagrees with its closed sides");
        }
        if (beforeSubscription != null) {
            requireOccurrence(beforeSubscription);
        }
        if (afterSubscription != null) {
            requireOccurrence(afterSubscription);
        }
        if (beforeSubscription != null && afterSubscription != null) {
            if (!beforeSubscription.channelOccurrence()
                    .managedDocumentId().equals(
                            afterSubscription.channelOccurrence()
                                    .managedDocumentId())) {
                throw new IllegalArgumentException(
                        "Subscription delta crosses managed documents");
            }
            if (beforeSubscription.subscriptionIdentity().equals(
                    afterSubscription.subscriptionIdentity())) {
                throw new IllegalArgumentException(
                        "REPLACE cannot retain identical subscription state");
            }
        }
    }

    private void requireOccurrence(SubscriptionState state) {
        if (!channelOccurrenceIdentity.equals(
                state.channelOccurrence().channelOccurrenceIdentity())) {
            throw new IllegalArgumentException(
                    "Subscription side has a different Channel occurrence");
        }
    }
}
