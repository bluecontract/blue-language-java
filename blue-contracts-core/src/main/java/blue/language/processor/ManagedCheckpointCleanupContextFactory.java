package blue.language.processor;

/**
 * Supplies exact attribution for canonical inactive-checkpoint cleanup.
 *
 * <p>The processor invokes this factory only for an actual removal, in
 * canonical raw-key order, after all completed-source writes. The ordinal is
 * local to that ordered cleanup sequence; a closure orchestrator may combine
 * it with its already-frozen global settlement offset.</p>
 */
@FunctionalInterface
public interface ManagedCheckpointCleanupContextFactory {

    /**
     * Returns the exact attribution for one actual inactive-entry removal.
     *
     * @param rawChannelKey exact retired raw Channel key
     * @param canonicalCleanupOrdinal zero-based lexical cleanup ordinal
     * @return non-null exact removal attribution
     */
    GasChargeContext contextFor(
            String rawChannelKey,
            int canonicalCleanupOrdinal);
}
