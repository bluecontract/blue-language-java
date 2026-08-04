package blue.language.processor;

import blue.language.provider.NodeProvider;

import java.util.Objects;

/**
 * Immutable execution environment for one platform-commit PROCESS call.
 *
 * <p>The Root and event passed to {@link BlueContracts} remain the only Blue
 * semantic inputs. This value carries out-of-band, revision-bound delivery
 * evidence and the exact request-local provider through which every referenced
 * value used by that attempt must be established.</p>
 *
 * <p>The provider is borrowed. Closing the invocation scope releases only
 * invocation-owned caches and never closes this provider.</p>
 */
public final class PlatformProcessInvocation {

    private final ExternalDeliveryPlan deliveryPlan;
    private final NodeProvider nodeProvider;
    private final VerifiedExecutionEvidence verifiedEvidence;

    private PlatformProcessInvocation(Builder builder) {
        this.deliveryPlan = Objects.requireNonNull(
                builder.deliveryPlan, "deliveryPlan");
        this.nodeProvider = Objects.requireNonNull(
                builder.nodeProvider, "nodeProvider");
        this.verifiedEvidence = deliveryPlan.verifiedBinding();
        if (verifiedEvidence == null) {
            throw new IllegalArgumentException(
                    "Platform delivery plan must be produced by the public "
                            + "indexed delivery evaluator");
        }
    }

    /**
     * Starts a builder for one platform invocation environment.
     *
     * @return empty invocation builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the independently evaluated exact delivery plan.
     *
     * @return immutable revision-bound delivery plan
     */
    public ExternalDeliveryPlan deliveryPlan() {
        return deliveryPlan;
    }

    /**
     * Returns the exact provider graph selected for this PROCESS attempt.
     *
     * @return borrowed invocation-local provider
     */
    public NodeProvider nodeProvider() {
        return nodeProvider;
    }

    /** Returns the evaluator-established binding retained with the plan. */
    VerifiedExecutionEvidence verifiedEvidence() {
        return verifiedEvidence;
    }

    /** Mutable builder that creates immutable platform invocation values. */
    public static final class Builder {

        private ExternalDeliveryPlan deliveryPlan;
        private NodeProvider nodeProvider;

        private Builder() {
        }

        /**
         * Selects a plan returned by
         * {@link IndexedDeliveryPreparation#deliveryPlan()}.
         *
         * @param plan independently evaluated exact plan
         * @return this builder
         */
        public Builder deliveryPlan(ExternalDeliveryPlan plan) {
            this.deliveryPlan = Objects.requireNonNull(
                    plan, "deliveryPlan");
            return this;
        }

        /**
         * Selects the strict request-local provider for all referenced reads.
         * No service-construction provider is appended as a fallback.
         *
         * @param provider exact invocation provider
         * @return this builder
         */
        public Builder nodeProvider(NodeProvider provider) {
            this.nodeProvider = Objects.requireNonNull(
                    provider, "nodeProvider");
            return this;
        }

        /**
         * Builds the immutable invocation environment.
         *
         * @return complete platform invocation
         * @throws IllegalArgumentException if the plan has no verified indexed
         *         evaluator binding
         */
        public PlatformProcessInvocation build() {
            return new PlatformProcessInvocation(this);
        }
    }
}
