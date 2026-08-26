package blue.language.processor;

import java.util.Objects;

/** Immutable pre-commit result of one contract-surface reconciliation. */
final class ContractSurfaceReconciliation {

    private final ContractSurfaceDelta surfaceDelta;
    private final SubscriptionDelta subscriptionDelta;

    ContractSurfaceReconciliation(
            ContractSurfaceDelta surfaceDelta,
            SubscriptionDelta subscriptionDelta) {
        this.surfaceDelta = Objects.requireNonNull(
                surfaceDelta, "surfaceDelta");
        this.subscriptionDelta = Objects.requireNonNull(
                subscriptionDelta, "subscriptionDelta");
    }

    ContractSurfaceDelta surfaceDelta() {
        return surfaceDelta;
    }

    SubscriptionDelta subscriptionDelta() {
        return subscriptionDelta;
    }
}
