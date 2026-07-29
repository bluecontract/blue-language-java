package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

/**
 * Establishes the deterministic identity used for checkpoint newness.
 *
 * <p>Exact BlueId input is preferred. When a {@link Blue} context is
 * available, authored values may fall back to semantic canonicalization and
 * finally to the processor's canonical signature. Each path is timed
 * independently for production diagnostics.</p>
 */
final class CheckpointIdentityCalculator {

    private CheckpointIdentityCalculator() {
    }

    static String identity(Node event) {
        return identity(event, null);
    }

    static String identity(Node event, Blue blue) {
        return identity(event, blue, ProcessingMetricsSink.NOOP);
    }

    static String identity(Node event, Blue blue, ProcessingMetricsSink metrics) {
        if (event == null) {
            return null;
        }
        ProcessingMetricsSink sink = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
        long directStart = System.nanoTime();
        try {
            String identity = BlueIdCalculator.calculateBlueId(event);
            sink.addCheckpointDirectBlueIdNanos(System.nanoTime() - directStart);
            return identity;
        } catch (RuntimeException directFailure) {
            sink.addCheckpointDirectBlueIdNanos(System.nanoTime() - directStart);
            if (blue == null) {
                throw new IllegalStateException(
                        "Checkpoint event identity requires valid BlueId Input or a Blue canonicalization context",
                        directFailure);
            }
            long contentStart = System.nanoTime();
            try {
                String identity = blue.calculateSemanticBlueId(event.clone());
                sink.addCheckpointContentBlueIdNanos(System.nanoTime() - contentStart);
                return identity;
            } catch (RuntimeException semanticFailure) {
                sink.addCheckpointContentBlueIdNanos(System.nanoTime() - contentStart);
                long fallbackStart = System.nanoTime();
                try {
                    return ProcessorEngine.canonicalSignature(event.clone());
                } finally {
                    sink.addCheckpointFallbackNanos(System.nanoTime() - fallbackStart);
                }
            }
        }
    }
}
