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

    /** @return number of verified evidence entries */
    public int verifiedEntries() {
        return verifiedEntries;
    }

    /** @return number of caller-pinned verified entries */
    public int pinnedVerifiedEntries() {
        return pinnedVerifiedEntries;
    }

    /** @return current approximate verified-entry weight in bytes */
    public long verifiedCurrentWeightBytes() {
        return verifiedCurrentWeightBytes;
    }

    /** @return largest observed approximate verified-entry weight in bytes */
    public long verifiedHighWaterWeightBytes() {
        return verifiedHighWaterWeightBytes;
    }

    /** @return verified entries evicted by the bounded policy */
    public long verifiedEvictions() {
        return verifiedEvictions;
    }

    /** @return oversized verified entries rejected by the bounded policy */
    public long verifiedOversizedRejections() {
        return verifiedOversizedRejections;
    }

    /** @return legacy transient-trust entry count, always zero */
    public int transientTrustedEntries() {
        return transientTrustedEntries;
    }

    /** @return legacy transient-trust current weight, always zero */
    public long transientTrustedCurrentWeightBytes() {
        return transientTrustedCurrentWeightBytes;
    }

    /** @return legacy transient-trust high-water weight, always zero */
    public long transientTrustedHighWaterWeightBytes() {
        return transientTrustedHighWaterWeightBytes;
    }

    /** @return legacy transient-trust eviction count, always zero */
    public long transientTrustedEvictions() {
        return transientTrustedEvictions;
    }

    /** @return legacy transient-trust oversized rejection count, always zero */
    public long transientTrustedOversizedRejections() {
        return transientTrustedOversizedRejections;
    }

    /** @return number of retained structural-interner entries */
    public int structuralEntries() {
        return structuralEntries;
    }

    /** @return current approximate structural-interner weight in bytes */
    public long structuralCurrentWeightBytes() {
        return structuralCurrentWeightBytes;
    }

    /** @return structural-interner high-water weight in bytes */
    public long structuralHighWaterWeightBytes() {
        return structuralHighWaterWeightBytes;
    }

    /** @return structural entries evicted by the bounded policy */
    public long structuralEvictions() {
        return structuralEvictions;
    }

    /** @return oversized structural entries rejected by the bounded policy */
    public long structuralOversizedRejections() {
        return structuralOversizedRejections;
    }
}
