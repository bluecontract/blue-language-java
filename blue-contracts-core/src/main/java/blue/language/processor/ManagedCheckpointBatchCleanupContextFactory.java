package blue.language.processor;

/**
 * Supplies exact gas attribution for one cleanup removal in a closure-wide
 * checkpoint settlement batch.
 */
@FunctionalInterface
public interface ManagedCheckpointBatchCleanupContextFactory {

    /**
     * Returns attribution for one actual cleanup removal.
     *
     * @param targetManagedScopeIdentity exact target managed-scope identity
     * @param rawChannelKey exact raw Root Channel key
     * @param checkpointWriteOrdinal canonical ordinal in the complete batch
     * @return non-null exact cleanup-write attribution
     */
    GasChargeContext contextFor(
            String targetManagedScopeIdentity,
            String rawChannelKey,
            long checkpointWriteOrdinal);
}
