package blue.language.merge;

/**
 * Immutable accounting value shared by the public compatibility view and the
 * cache's internal accounting collaborator.
 */
class ResolvedReferenceCacheStatistics {

    private final int verifiedEntries;
    private final int pinnedVerifiedEntries;
    private final long verifiedCurrentWeightBytes;
    private final long verifiedHighWaterWeightBytes;
    private final long verifiedEvictions;
    private final long verifiedOversizedRejections;
    private final int transientTrustedEntries;
    private final long transientTrustedCurrentWeightBytes;
    private final long transientTrustedHighWaterWeightBytes;
    private final long transientTrustedEvictions;
    private final long transientTrustedOversizedRejections;
    private final int structuralEntries;
    private final long structuralCurrentWeightBytes;
    private final long structuralHighWaterWeightBytes;
    private final long structuralEvictions;
    private final long structuralOversizedRejections;

    ResolvedReferenceCacheStatistics(
            int verifiedEntries,
            int pinnedVerifiedEntries,
            long verifiedCurrentWeightBytes,
            long verifiedHighWaterWeightBytes,
            long verifiedEvictions,
            long verifiedOversizedRejections,
            int transientTrustedEntries,
            long transientTrustedCurrentWeightBytes,
            long transientTrustedHighWaterWeightBytes,
            long transientTrustedEvictions,
            long transientTrustedOversizedRejections,
            int structuralEntries,
            long structuralCurrentWeightBytes,
            long structuralHighWaterWeightBytes,
            long structuralEvictions,
            long structuralOversizedRejections) {
        this.verifiedEntries = verifiedEntries;
        this.pinnedVerifiedEntries = pinnedVerifiedEntries;
        this.verifiedCurrentWeightBytes = verifiedCurrentWeightBytes;
        this.verifiedHighWaterWeightBytes = verifiedHighWaterWeightBytes;
        this.verifiedEvictions = verifiedEvictions;
        this.verifiedOversizedRejections = verifiedOversizedRejections;
        this.transientTrustedEntries = transientTrustedEntries;
        this.transientTrustedCurrentWeightBytes =
                transientTrustedCurrentWeightBytes;
        this.transientTrustedHighWaterWeightBytes =
                transientTrustedHighWaterWeightBytes;
        this.transientTrustedEvictions = transientTrustedEvictions;
        this.transientTrustedOversizedRejections =
                transientTrustedOversizedRejections;
        this.structuralEntries = structuralEntries;
        this.structuralCurrentWeightBytes = structuralCurrentWeightBytes;
        this.structuralHighWaterWeightBytes = structuralHighWaterWeightBytes;
        this.structuralEvictions = structuralEvictions;
        this.structuralOversizedRejections = structuralOversizedRejections;
    }

    /**
     * Returns the number of retained verified-evidence entries.
     *
     * @return number of verified-evidence entries
     */
    public int verifiedEntries() {
        return verifiedEntries;
    }

    /**
     * Returns the number of verified entries pinned by the caller.
     *
     * @return number of caller-pinned verified entries
     */
    public int pinnedVerifiedEntries() {
        return pinnedVerifiedEntries;
    }

    /**
     * Returns the current approximate weight of verified entries.
     *
     * @return current approximate verified-entry weight in bytes
     */
    public long verifiedCurrentWeightBytes() {
        return verifiedCurrentWeightBytes;
    }

    /**
     * Returns the largest observed approximate weight of verified entries.
     *
     * @return largest observed approximate verified-entry weight in bytes
     */
    public long verifiedHighWaterWeightBytes() {
        return verifiedHighWaterWeightBytes;
    }

    /**
     * Returns the cumulative number of verified-entry evictions.
     *
     * @return verified entries evicted by the bounded policy
     */
    public long verifiedEvictions() {
        return verifiedEvictions;
    }

    /**
     * Returns the cumulative number of oversized verified-entry rejections.
     *
     * @return oversized verified entries rejected by the bounded policy
     */
    public long verifiedOversizedRejections() {
        return verifiedOversizedRejections;
    }

    /**
     * Returns the retired transient-trust entry count.
     *
     * @return legacy transient-trust entry count, always zero
     */
    public int transientTrustedEntries() {
        return transientTrustedEntries;
    }

    /**
     * Returns the retired transient-trust current weight.
     *
     * @return legacy transient-trust current weight in bytes, always zero
     */
    public long transientTrustedCurrentWeightBytes() {
        return transientTrustedCurrentWeightBytes;
    }

    /**
     * Returns the retired transient-trust high-water weight.
     *
     * @return legacy transient-trust high-water weight in bytes, always zero
     */
    public long transientTrustedHighWaterWeightBytes() {
        return transientTrustedHighWaterWeightBytes;
    }

    /**
     * Returns the retired transient-trust eviction count.
     *
     * @return legacy transient-trust eviction count, always zero
     */
    public long transientTrustedEvictions() {
        return transientTrustedEvictions;
    }

    /**
     * Returns the retired transient-trust oversized-rejection count.
     *
     * @return legacy transient-trust oversized-rejection count, always zero
     */
    public long transientTrustedOversizedRejections() {
        return transientTrustedOversizedRejections;
    }

    /**
     * Returns the number of retained structural-interner entries.
     *
     * @return number of retained structural-interner entries
     */
    public int structuralEntries() {
        return structuralEntries;
    }

    /**
     * Returns the current approximate weight of the structural interner.
     *
     * @return current approximate structural-interner weight in bytes
     */
    public long structuralCurrentWeightBytes() {
        return structuralCurrentWeightBytes;
    }

    /**
     * Returns the largest observed approximate structural-interner weight.
     *
     * @return structural-interner high-water weight in bytes
     */
    public long structuralHighWaterWeightBytes() {
        return structuralHighWaterWeightBytes;
    }

    /**
     * Returns the cumulative number of structural-entry evictions.
     *
     * @return structural entries evicted by the bounded policy
     */
    public long structuralEvictions() {
        return structuralEvictions;
    }

    /**
     * Returns the cumulative number of oversized structural-entry rejections.
     *
     * @return oversized structural entries rejected by the bounded policy
     */
    public long structuralOversizedRejections() {
        return structuralOversizedRejections;
    }
}
