package blue.language;

/**
 * Immutable bounds for reloadable acceleration data owned by one {@link Blue}
 * runtime. Explicitly registered authoritative snapshots are not evicted by
 * these limits; they remain pinned until clear or close.
 */
public final class BlueCachePolicy {

    private static final long MIB = 1024L * 1024L;

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
        this.derivedSnapshotMaxEntries = positive(builder.derivedSnapshotMaxEntries,
                "derivedSnapshotMaxEntries");
        this.derivedSnapshotMaxWeightBytes = positive(builder.derivedSnapshotMaxWeightBytes,
                "derivedSnapshotMaxWeightBytes");
        this.canonicalAliasMaxEntries = positive(builder.canonicalAliasMaxEntries,
                "canonicalAliasMaxEntries");
        this.canonicalAliasMaxWeightBytes = positive(builder.canonicalAliasMaxWeightBytes,
                "canonicalAliasMaxWeightBytes");
        this.resolvedStructuralMaxEntries = positive(builder.resolvedStructuralMaxEntries,
                "resolvedStructuralMaxEntries");
        this.resolvedStructuralMaxWeightBytes = positive(builder.resolvedStructuralMaxWeightBytes,
                "resolvedStructuralMaxWeightBytes");
        this.transientReferenceMaxEntries = positive(builder.transientReferenceMaxEntries,
                "transientReferenceMaxEntries");
        this.transientReferenceMaxWeightBytes = positive(builder.transientReferenceMaxWeightBytes,
                "transientReferenceMaxWeightBytes");
        this.conformancePlanMaxEntries = positive(builder.conformancePlanMaxEntries,
                "conformancePlanMaxEntries");
        this.conformancePlanMaxWeightBytes = positive(builder.conformancePlanMaxWeightBytes,
                "conformancePlanMaxWeightBytes");
        this.maximumDerivedEntryWeightBytes = positive(builder.maximumDerivedEntryWeightBytes,
                "maximumDerivedEntryWeightBytes");
    }

    public static BlueCachePolicy boundedDefaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int derivedSnapshotMaxEntries() {
        return derivedSnapshotMaxEntries;
    }

    public long derivedSnapshotMaxWeightBytes() {
        return derivedSnapshotMaxWeightBytes;
    }

    public int canonicalAliasMaxEntries() {
        return canonicalAliasMaxEntries;
    }

    public long canonicalAliasMaxWeightBytes() {
        return canonicalAliasMaxWeightBytes;
    }

    public int resolvedStructuralMaxEntries() {
        return resolvedStructuralMaxEntries;
    }

    public long resolvedStructuralMaxWeightBytes() {
        return resolvedStructuralMaxWeightBytes;
    }

    public int transientReferenceMaxEntries() {
        return transientReferenceMaxEntries;
    }

    public long transientReferenceMaxWeightBytes() {
        return transientReferenceMaxWeightBytes;
    }

    public int conformancePlanMaxEntries() {
        return conformancePlanMaxEntries;
    }

    public long conformancePlanMaxWeightBytes() {
        return conformancePlanMaxWeightBytes;
    }

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

    public static final class Builder {
        private int derivedSnapshotMaxEntries = 256;
        private long derivedSnapshotMaxWeightBytes = 256L * MIB;
        private int canonicalAliasMaxEntries = 512;
        private long canonicalAliasMaxWeightBytes = 64L * MIB;
        private int resolvedStructuralMaxEntries = 16_384;
        private long resolvedStructuralMaxWeightBytes = 256L * MIB;
        private int transientReferenceMaxEntries = 4_096;
        private long transientReferenceMaxWeightBytes = 128L * MIB;
        private int conformancePlanMaxEntries = 4_096;
        private long conformancePlanMaxWeightBytes = 128L * MIB;
        private long maximumDerivedEntryWeightBytes = 64L * MIB;

        private Builder() {
        }

        public Builder derivedSnapshots(int maxEntries, long maxWeightBytes) {
            this.derivedSnapshotMaxEntries = maxEntries;
            this.derivedSnapshotMaxWeightBytes = maxWeightBytes;
            return this;
        }

        public Builder canonicalAliases(int maxEntries, long maxWeightBytes) {
            this.canonicalAliasMaxEntries = maxEntries;
            this.canonicalAliasMaxWeightBytes = maxWeightBytes;
            return this;
        }

        public Builder resolvedStructuralEntries(int maxEntries, long maxWeightBytes) {
            this.resolvedStructuralMaxEntries = maxEntries;
            this.resolvedStructuralMaxWeightBytes = maxWeightBytes;
            return this;
        }

        public Builder transientReferences(int maxEntries, long maxWeightBytes) {
            this.transientReferenceMaxEntries = maxEntries;
            this.transientReferenceMaxWeightBytes = maxWeightBytes;
            return this;
        }

        public Builder conformancePlans(int maxEntries, long maxWeightBytes) {
            this.conformancePlanMaxEntries = maxEntries;
            this.conformancePlanMaxWeightBytes = maxWeightBytes;
            return this;
        }

        public Builder maximumDerivedEntryWeightBytes(long maxWeightBytes) {
            this.maximumDerivedEntryWeightBytes = maxWeightBytes;
            return this;
        }

        public BlueCachePolicy build() {
            return new BlueCachePolicy(this);
        }
    }
}
