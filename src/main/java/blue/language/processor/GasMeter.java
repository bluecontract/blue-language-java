package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.processor.util.PointerUtils;

/**
 * Tracks and charges gas usage for a processing run.
 */
final class GasMeter {

    private long totalGas;

    long totalGas() {
        return totalGas;
    }

    void add(long amount) {
        totalGas += amount;
    }

    void chargeScopeEntry(String scopePath) {
        add(GasCharges.scopeEntry(scopeDepth(scopePath)));
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
        long bytes = NodeCanonicalizer.canonicalSize(node);
        return (bytes + 99L) / 100L;
    }

    private int scopeDepth(String scopePath) {
        String normalized = PointerUtils.normalizeScope(scopePath);
        if ("/".equals(normalized)) {
            return 0;
        }
        String trimmed = normalized.substring(1);
        if (trimmed.isEmpty()) {
            return 0;
        }
        int depth = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static final class GasCharges {
        private static final long INITIALIZATION = 1_000L;
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
