package blue.language.processor;

/**
 * Optional metrics hook for document-processing instrumentation.
 *
 * <p>Implementations should be cheap and thread-safe. All methods are no-ops
 * by default so callers can record fine-grained timings without branching.</p>
 */
public interface ProcessingMetricsSink {
    ProcessingMetricsSink NOOP = new ProcessingMetricsSink() {
    };

    default void addProcessDocumentNanos(long nanos) {
    }

    default void addBlueProcessDocumentNanos(long nanos) {
    }

    default void addEventPreprocessNanos(long nanos) {
    }

    default void addResultSnapshotAttachNanos(long nanos) {
    }

    default void addBlueIdCalculationNanos(long nanos) {
    }

    default void addBundleLoadNanos(long nanos) {
    }

    default void addBundleLoadCacheKeyBuildNanos(long nanos) {
    }

    default void addBundleLoadActualBuildNanos(long nanos) {
    }

    default void addBundleLoadReuseNanos(long nanos) {
    }

    default void incrementBundleLoadCacheHits() {
    }

    default void incrementBundleLoadCacheMisses() {
    }

    default void incrementBundlesBuilt() {
    }

    default void incrementBundlesReused() {
    }

    default void addChannelDiscoveryNanos(long nanos) {
    }

    default void addChannelMatchNanos(long nanos) {
    }

    default void incrementChannelEvaluations() {
    }

    default void addHandlerDiscoveryNanos(long nanos) {
    }

    default void addHandlerMatchNanos(long nanos) {
    }

    default void incrementHandlerMatchAttempts() {
    }

    default void addHandlerExecutionNanos(long nanos) {
    }

    default void incrementHandlersExecuted() {
    }

    default void addTriggeredEventRoutingNanos(long nanos) {
    }

    default void incrementTriggeredEventsRouted() {
    }

    default void addCheckpointUpdateNanos(long nanos) {
    }

    default void addSnapshotCommitNanos(long nanos) {
    }

    default void addPostProcessingNanos(long nanos) {
    }

    default void addPatchBoundaryNanos(long nanos) {
    }

    default void addPatchGasNanos(long nanos) {
    }

    default void addDocumentUpdateRoutingNanos(long nanos) {
    }

    default void incrementDocumentUpdateEventsBuilt() {
    }

    default void incrementDocumentUpdateEventsSkippedNoChannel() {
    }

    default void addBatchPatchPlanningNanos(long nanos) {
    }

    default void addBatchPatchConformanceNanos(long nanos) {
    }

    default void addBatchPatchBuildUpdatesNanos(long nanos) {
    }

    default void addBatchPatchCommitNanos(long nanos) {
    }

    default void incrementDocumentUpdateBeforeMaterializations() {
    }

    default void incrementDocumentUpdateAfterMaterializations() {
    }
}
