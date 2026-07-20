package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.snapshot.FrozenNode;

/**
 * Tracks and charges gas usage for a processing run.
 */
final class GasMeter {

    private long totalGas;

    long totalGas() {
        return totalGas;
    }

    void add(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Gas amount must be non-negative");
        }
        totalGas += amount;
    }

    void chargeScopeEntry(int embeddedDepth) {
        if (embeddedDepth < 0) {
            throw new IllegalArgumentException("Scope embedded depth must be non-negative");
        }
        add(GasCharges.scopeEntry(embeddedDepth));
    }

    void chargeInitialization() {
        add(GasCharges.INITIALIZATION);
    }

    void chargeChannelMatchAttempt() {
        add(GasCharges.CHANNEL_MATCH_ATTEMPT);
    }

    void chargeHandlerOverhead() {
        add(GasCharges.HANDLER_OVERHEAD);
    }

    void chargeBoundaryCheck() {
        add(GasCharges.BOUNDARY_CHECK);
    }

    void chargePatchAddOrReplace(Node value) {
        add(GasCharges.patchAddOrReplace(payloadSizeCharge(value)));
    }

    void chargeFrozenPatchAddOrReplace(FrozenNode value) {
        add(GasCharges.patchAddOrReplace(frozenPayloadSizeCharge(value)));
    }

    void chargeFrozenPatchAddOrReplace(long authoredCanonicalSizeBytes) {
        if (authoredCanonicalSizeBytes < 0L) {
            throw new IllegalArgumentException("Authored canonical size must be non-negative");
        }
        add(GasCharges.patchAddOrReplace(payloadSizeCharge(authoredCanonicalSizeBytes)));
    }

    void chargePatchRemove() {
        add(GasCharges.PATCH_REMOVE);
    }

    void chargeCascadeRouting(int scopeCount) {
        if (scopeCount > 0) {
            add(GasCharges.cascadeRouting(scopeCount));
        }
    }

    void chargeEmitEvent(Node event) {
        add(GasCharges.emitEvent(payloadSizeCharge(event)));
    }

    void chargeBridge(Node event) {
        add(GasCharges.BRIDGE_NODE);
    }

    void chargeDrainEvent() {
        add(GasCharges.DRAIN_EVENT);
    }

    void chargeCheckpointUpdate() {
        add(GasCharges.CHECKPOINT_UPDATE);
    }

    void chargeTerminationMarker() {
        add(GasCharges.TERMINATION_MARKER);
    }

    void chargeLifecycleDelivery() {
        add(GasCharges.LIFECYCLE_DELIVERY);
    }

    void chargeFatalTerminationOverhead() {
        add(GasCharges.FATAL_TERMINATION_OVERHEAD);
    }

    private long payloadSizeCharge(Node node) {
        return payloadSizeCharge(NodeCanonicalizer.canonicalSize(node));
    }

    private long frozenPayloadSizeCharge(FrozenNode node) {
        return payloadSizeCharge(NodeCanonicalizer.canonicalFrozenSize(node));
    }

    private long payloadSizeCharge(long bytes) {
        return (bytes + 99L) / 100L;
    }

    private static final class GasCharges {
        private static final long INITIALIZATION = 1001L;
        private static final long CHANNEL_MATCH_ATTEMPT = 5L;
        private static final long HANDLER_OVERHEAD = 50L;
        private static final long BOUNDARY_CHECK = 2L;
        private static final long PATCH_REMOVE = 10L;
        private static final long BRIDGE_NODE = 10L;
        private static final long DRAIN_EVENT = 10L;
        private static final long CHECKPOINT_UPDATE = 20L;
        private static final long TERMINATION_MARKER = 20L;
        private static final long LIFECYCLE_DELIVERY = 30L;
        private static final long FATAL_TERMINATION_OVERHEAD = 100L;

        private static long scopeEntry(int depth) {
            return 50L + 10L * depth;
        }

        private static long patchAddOrReplace(long sizeCharge) {
            return 20L + sizeCharge;
        }

        private static long cascadeRouting(int scopeCount) {
            return 10L * scopeCount;
        }

        private static long emitEvent(long sizeCharge) {
            return 20L + sizeCharge;
        }
    }
}
