package blue.language.api;

/**
 * Immutable bounds for reloadable acceleration data owned by one
 * {@link blue.language.runtime.BlueLanguageRuntime}
 * runtime. These limits are not process-wide budgets. Explicitly registered
 * authoritative snapshots are not evicted by these limits; they remain pinned
 * until clear or close.
 */
public final class BlueCachePolicy {

    private static final long MIB = 1024L * 1024L;

    private static final int DEFAULT_DERIVED_SNAPSHOT_ENTRIES = 128;
    private static final long DEFAULT_DERIVED_SNAPSHOT_WEIGHT = 64L * MIB;
    private static final int DEFAULT_CANONICAL_ALIAS_ENTRIES = 256;
    private static final long DEFAULT_CANONICAL_ALIAS_WEIGHT = 16L * MIB;
    private static final int DEFAULT_RESOLVED_STRUCTURAL_ENTRIES = 8_192;
    private static final long DEFAULT_RESOLVED_STRUCTURAL_WEIGHT = 64L * MIB;
    private static final int DEFAULT_TRANSIENT_REFERENCE_ENTRIES = 2_048;
    private static final long DEFAULT_TRANSIENT_REFERENCE_WEIGHT = 32L * MIB;
    private static final int DEFAULT_CONFORMANCE_PLAN_ENTRIES = 4_096;
    private static final long DEFAULT_CONFORMANCE_PLAN_WEIGHT = 32L * MIB;
    private static final long DEFAULT_MAXIMUM_DERIVED_ENTRY_WEIGHT = 16L * MIB;

    private static final int LOW_MEMORY_DERIVED_SNAPSHOT_ENTRIES = 64;
    private static final long LOW_MEMORY_DERIVED_SNAPSHOT_WEIGHT = 32L * MIB;
    private static final int LOW_MEMORY_CANONICAL_ALIAS_ENTRIES = 128;
    private static final long LOW_MEMORY_CANONICAL_ALIAS_WEIGHT = 8L * MIB;
    private static final int LOW_MEMORY_RESOLVED_STRUCTURAL_ENTRIES = 4_096;
    private static final long LOW_MEMORY_RESOLVED_STRUCTURAL_WEIGHT = 32L * MIB;
    private static final int LOW_MEMORY_TRANSIENT_REFERENCE_ENTRIES = 1_024;
    private static final long LOW_MEMORY_TRANSIENT_REFERENCE_WEIGHT = 16L * MIB;
    private static final int LOW_MEMORY_CONFORMANCE_PLAN_ENTRIES = 1_024;
    private static final long LOW_MEMORY_CONFORMANCE_PLAN_WEIGHT = 16L * MIB;
    private static final long LOW_MEMORY_MAXIMUM_DERIVED_ENTRY_WEIGHT = 8L * MIB;

    private static final int HIGH_THROUGHPUT_DERIVED_SNAPSHOT_ENTRIES = 256;
    private static final long HIGH_THROUGHPUT_DERIVED_SNAPSHOT_WEIGHT = 256L * MIB;
    private static final int HIGH_THROUGHPUT_CANONICAL_ALIAS_ENTRIES = 512;
    private static final long HIGH_THROUGHPUT_CANONICAL_ALIAS_WEIGHT = 64L * MIB;
    private static final int HIGH_THROUGHPUT_RESOLVED_STRUCTURAL_ENTRIES = 16_384;
    private static final long HIGH_THROUGHPUT_RESOLVED_STRUCTURAL_WEIGHT = 256L * MIB;
    private static final int HIGH_THROUGHPUT_TRANSIENT_REFERENCE_ENTRIES = 4_096;
    private static final long HIGH_THROUGHPUT_TRANSIENT_REFERENCE_WEIGHT = 128L * MIB;
    private static final int HIGH_THROUGHPUT_CONFORMANCE_PLAN_ENTRIES = 4_096;
    private static final long HIGH_THROUGHPUT_CONFORMANCE_PLAN_WEIGHT = 128L * MIB;
    private static final long HIGH_THROUGHPUT_MAXIMUM_DERIVED_ENTRY_WEIGHT = 64L * MIB;

    private final int derivedSnapshotMaxEntries;
    private final long derivedSnapshotMaxWeightBytes;
    private final int canonicalAliasMaxEntries;
    private final long canonicalAliasMaxWeightBytes;
    private final int resolvedStructuralMaxEntries;
    private final long resolvedStructuralMaxWeightBytes;
    private final int transientReferenceMaxEntries;
    private final long transientReferenceMaxWeightBytes;
    private final int conformancePlanMaxEntries;
    private final long conformancePlanMaxWeightBytes;
    private final long maximumDerivedEntryWeightBytes;

    private BlueCachePolicy(Builder builder) {
        this(
                positive(builder.derivedSnapshotMaxEntries, "derivedSnapshotMaxEntries"),
                positive(builder.derivedSnapshotMaxWeightBytes, "derivedSnapshotMaxWeightBytes"),
                positive(builder.canonicalAliasMaxEntries, "canonicalAliasMaxEntries"),
                positive(builder.canonicalAliasMaxWeightBytes, "canonicalAliasMaxWeightBytes"),
                positive(builder.resolvedStructuralMaxEntries, "resolvedStructuralMaxEntries"),
                positive(builder.resolvedStructuralMaxWeightBytes, "resolvedStructuralMaxWeightBytes"),
                positive(builder.transientReferenceMaxEntries, "transientReferenceMaxEntries"),
                positive(builder.transientReferenceMaxWeightBytes, "transientReferenceMaxWeightBytes"),
                positive(builder.conformancePlanMaxEntries, "conformancePlanMaxEntries"),
                positive(builder.conformancePlanMaxWeightBytes, "conformancePlanMaxWeightBytes"),
                positive(builder.maximumDerivedEntryWeightBytes, "maximumDerivedEntryWeightBytes"));
    }

    private BlueCachePolicy(int derivedSnapshotMaxEntries,
                            long derivedSnapshotMaxWeightBytes,
                            int canonicalAliasMaxEntries,
                            long canonicalAliasMaxWeightBytes,
                            int resolvedStructuralMaxEntries,
                            long resolvedStructuralMaxWeightBytes,
                            int transientReferenceMaxEntries,
                            long transientReferenceMaxWeightBytes,
                            int conformancePlanMaxEntries,
                            long conformancePlanMaxWeightBytes,
                            long maximumDerivedEntryWeightBytes) {
        this.derivedSnapshotMaxEntries = nonNegative(
                derivedSnapshotMaxEntries, "derivedSnapshotMaxEntries");
        this.derivedSnapshotMaxWeightBytes = nonNegative(
                derivedSnapshotMaxWeightBytes, "derivedSnapshotMaxWeightBytes");
        this.canonicalAliasMaxEntries = nonNegative(
                canonicalAliasMaxEntries, "canonicalAliasMaxEntries");
        this.canonicalAliasMaxWeightBytes = nonNegative(
                canonicalAliasMaxWeightBytes, "canonicalAliasMaxWeightBytes");
        this.resolvedStructuralMaxEntries = nonNegative(
                resolvedStructuralMaxEntries, "resolvedStructuralMaxEntries");
        this.resolvedStructuralMaxWeightBytes = nonNegative(
                resolvedStructuralMaxWeightBytes, "resolvedStructuralMaxWeightBytes");
        this.transientReferenceMaxEntries = nonNegative(
                transientReferenceMaxEntries, "transientReferenceMaxEntries");
        this.transientReferenceMaxWeightBytes = nonNegative(
                transientReferenceMaxWeightBytes, "transientReferenceMaxWeightBytes");
        this.conformancePlanMaxEntries = nonNegative(
                conformancePlanMaxEntries, "conformancePlanMaxEntries");
        this.conformancePlanMaxWeightBytes = nonNegative(
                conformancePlanMaxWeightBytes, "conformancePlanMaxWeightBytes");
        this.maximumDerivedEntryWeightBytes = nonNegative(
                maximumDerivedEntryWeightBytes, "maximumDerivedEntryWeightBytes");
    }

    /**
     * Conservative production default for one runtime. Use
     * {@link #highThroughputDefaults()} only when the host has made an explicit
     * memory/throughput tradeoff.
     *
     * @return bounded production policy
     */
    public static BlueCachePolicy boundedDefaults() {
        return builder().build();
    }

    /**
     * Returns smaller per-runtime bounds for low-memory service profiles.
     *
     * @return low-memory policy
     */
    public static BlueCachePolicy lowMemoryDefaults() {
        return new BlueCachePolicy(
                LOW_MEMORY_DERIVED_SNAPSHOT_ENTRIES,
                LOW_MEMORY_DERIVED_SNAPSHOT_WEIGHT,
                LOW_MEMORY_CANONICAL_ALIAS_ENTRIES,
                LOW_MEMORY_CANONICAL_ALIAS_WEIGHT,
                LOW_MEMORY_RESOLVED_STRUCTURAL_ENTRIES,
                LOW_MEMORY_RESOLVED_STRUCTURAL_WEIGHT,
                LOW_MEMORY_TRANSIENT_REFERENCE_ENTRIES,
                LOW_MEMORY_TRANSIENT_REFERENCE_WEIGHT,
                LOW_MEMORY_CONFORMANCE_PLAN_ENTRIES,
                LOW_MEMORY_CONFORMANCE_PLAN_WEIGHT,
                LOW_MEMORY_MAXIMUM_DERIVED_ENTRY_WEIGHT);
    }

    /**
     * Returns high-memory defaults for hosts favoring throughput.
     *
     * @return high-throughput policy
     */
    public static BlueCachePolicy highThroughputDefaults() {
        return new BlueCachePolicy(
                HIGH_THROUGHPUT_DERIVED_SNAPSHOT_ENTRIES,
                HIGH_THROUGHPUT_DERIVED_SNAPSHOT_WEIGHT,
                HIGH_THROUGHPUT_CANONICAL_ALIAS_ENTRIES,
                HIGH_THROUGHPUT_CANONICAL_ALIAS_WEIGHT,
                HIGH_THROUGHPUT_RESOLVED_STRUCTURAL_ENTRIES,
                HIGH_THROUGHPUT_RESOLVED_STRUCTURAL_WEIGHT,
                HIGH_THROUGHPUT_TRANSIENT_REFERENCE_ENTRIES,
                HIGH_THROUGHPUT_TRANSIENT_REFERENCE_WEIGHT,
                HIGH_THROUGHPUT_CONFORMANCE_PLAN_ENTRIES,
                HIGH_THROUGHPUT_CONFORMANCE_PLAN_WEIGHT,
                HIGH_THROUGHPUT_MAXIMUM_DERIVED_ENTRY_WEIGHT);
    }

    /**
     * Disables retention of reloadable acceleration data. Authoritative
     * snapshots explicitly cached by the caller remain pinned.
     *
     * @return zero-retention acceleration policy
     */
    public static BlueCachePolicy disabled() {
        return new BlueCachePolicy(0, 0L, 0, 0L, 0, 0L, 0, 0L, 0, 0L, 0L);
    }

    /**
     * Starts a builder with bounded production defaults.
     *
     * @return mutable policy builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the derived-snapshot entry bound.
     * @return maximum retained entries */
    public int derivedSnapshotMaxEntries() {
        return derivedSnapshotMaxEntries;
    }

    /** Returns the derived-snapshot weight bound.
     * @return maximum retained weight in bytes */
    public long derivedSnapshotMaxWeightBytes() {
        return derivedSnapshotMaxWeightBytes;
    }

    /** Returns the canonical-alias entry bound.
     * @return maximum retained entries */
    public int canonicalAliasMaxEntries() {
        return canonicalAliasMaxEntries;
    }

    /** Returns the canonical-alias weight bound.
     * @return maximum retained weight in bytes */
    public long canonicalAliasMaxWeightBytes() {
        return canonicalAliasMaxWeightBytes;
    }

    /** Returns the resolved-structural entry bound.
     * @return maximum retained entries */
    public int resolvedStructuralMaxEntries() {
        return resolvedStructuralMaxEntries;
    }

    /** Returns the resolved-structural weight bound.
     * @return maximum retained weight in bytes */
    public long resolvedStructuralMaxWeightBytes() {
        return resolvedStructuralMaxWeightBytes;
    }

    /** Returns the transient-reference entry bound.
     * @return maximum retained entries */
    public int transientReferenceMaxEntries() {
        return transientReferenceMaxEntries;
    }

    /** Returns the transient-reference weight bound.
     * @return maximum retained weight in bytes */
    public long transientReferenceMaxWeightBytes() {
        return transientReferenceMaxWeightBytes;
    }

    /** Returns the conformance-plan entry bound.
     * @return maximum retained entries */
    public int conformancePlanMaxEntries() {
        return conformancePlanMaxEntries;
    }

    /** Returns the conformance-plan weight bound.
     * @return maximum retained weight in bytes */
    public long conformancePlanMaxWeightBytes() {
        return conformancePlanMaxWeightBytes;
    }

    /** Returns the individual derived-entry weight bound.
     * @return maximum admitted weight in bytes */
    public long maximumDerivedEntryWeightBytes() {
        return maximumDerivedEntryWeightBytes;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /** Mutable builder for independently sizing each cache region. */
    public static final class Builder {
        private int derivedSnapshotMaxEntries = DEFAULT_DERIVED_SNAPSHOT_ENTRIES;
        private long derivedSnapshotMaxWeightBytes = DEFAULT_DERIVED_SNAPSHOT_WEIGHT;
        private int canonicalAliasMaxEntries = DEFAULT_CANONICAL_ALIAS_ENTRIES;
        private long canonicalAliasMaxWeightBytes = DEFAULT_CANONICAL_ALIAS_WEIGHT;
        private int resolvedStructuralMaxEntries = DEFAULT_RESOLVED_STRUCTURAL_ENTRIES;
        private long resolvedStructuralMaxWeightBytes = DEFAULT_RESOLVED_STRUCTURAL_WEIGHT;
        private int transientReferenceMaxEntries = DEFAULT_TRANSIENT_REFERENCE_ENTRIES;
        private long transientReferenceMaxWeightBytes = DEFAULT_TRANSIENT_REFERENCE_WEIGHT;
        private int conformancePlanMaxEntries = DEFAULT_CONFORMANCE_PLAN_ENTRIES;
        private long conformancePlanMaxWeightBytes = DEFAULT_CONFORMANCE_PLAN_WEIGHT;
        private long maximumDerivedEntryWeightBytes = DEFAULT_MAXIMUM_DERIVED_ENTRY_WEIGHT;

        private Builder() {
        }

        /**
         * Configures derived-snapshot retention.
         *
         * @param maxEntries maximum retained entries
         * @param maxWeightBytes maximum retained weight in bytes
         * @return this builder
         */
        public Builder derivedSnapshots(int maxEntries, long maxWeightBytes) {
            this.derivedSnapshotMaxEntries = maxEntries;
            this.derivedSnapshotMaxWeightBytes = maxWeightBytes;
            return this;
        }

        /**
         * Configures canonical-alias retention.
         *
         * @param maxEntries maximum retained entries
         * @param maxWeightBytes maximum retained weight in bytes
         * @return this builder
         */
        public Builder canonicalAliases(int maxEntries, long maxWeightBytes) {
            this.canonicalAliasMaxEntries = maxEntries;
            this.canonicalAliasMaxWeightBytes = maxWeightBytes;
            return this;
        }

        /**
         * Configures resolved-structural retention.
         *
         * @param maxEntries maximum retained entries
         * @param maxWeightBytes maximum retained weight in bytes
         * @return this builder
         */
        public Builder resolvedStructuralEntries(int maxEntries, long maxWeightBytes) {
            this.resolvedStructuralMaxEntries = maxEntries;
            this.resolvedStructuralMaxWeightBytes = maxWeightBytes;
            return this;
        }

        /**
         * Configures transient-reference retention.
         *
         * @param maxEntries maximum retained entries
         * @param maxWeightBytes maximum retained weight in bytes
         * @return this builder
         */
        public Builder transientReferences(int maxEntries, long maxWeightBytes) {
            this.transientReferenceMaxEntries = maxEntries;
            this.transientReferenceMaxWeightBytes = maxWeightBytes;
            return this;
        }

        /**
         * Configures conformance-plan retention.
         *
         * @param maxEntries maximum retained entries
         * @param maxWeightBytes maximum retained weight in bytes
         * @return this builder
         */
        public Builder conformancePlans(int maxEntries, long maxWeightBytes) {
            this.conformancePlanMaxEntries = maxEntries;
            this.conformancePlanMaxWeightBytes = maxWeightBytes;
            return this;
        }

        /**
         * Sets the largest weight admitted for one derived entry.
         *
         * @param maxWeightBytes maximum individual derived-entry weight
         * @return this builder
         */
        public Builder maximumDerivedEntryWeightBytes(long maxWeightBytes) {
            this.maximumDerivedEntryWeightBytes = maxWeightBytes;
            return this;
        }

        /**
         * Validates and creates an immutable policy.
         *
         * @return configured cache policy
         * @throws IllegalArgumentException when any configured bound is not
         *                                  positive
         */
        public BlueCachePolicy build() {
            return new BlueCachePolicy(this);
        }
    }
}
