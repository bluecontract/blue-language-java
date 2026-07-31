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
        /*
         * Processor events may be captured from a resolved snapshot, where a
         * nominal type carries both its requested BlueId and materialized
         * definition. Project that trusted view back to valid Source form so
         * checkpoint identity never depends on resolved representation.
         */
        Node sourceProjection = event.clone();
        MaterializationProvenance.clear(sourceProjection);
        ProcessingMetricsSink sink = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
        long directStart = System.nanoTime();
        try {
            String identity = BlueIdCalculator.calculateBlueId(
                    sourceProjection);
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
                String identity = blue.calculateSemanticBlueId(
                        sourceProjection.clone());
                sink.addCheckpointContentBlueIdNanos(System.nanoTime() - contentStart);
                return identity;
            } catch (RuntimeException semanticFailure) {
                sink.addCheckpointContentBlueIdNanos(System.nanoTime() - contentStart);
                long fallbackStart = System.nanoTime();
                try {
                    return ProcessorEngine.canonicalSignature(
                            sourceProjection.clone());
                } finally {
                    sink.addCheckpointFallbackNanos(System.nanoTime() - fallbackStart);
                }
            }
        }
    }
}
