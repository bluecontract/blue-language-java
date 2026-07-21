package blue.language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueCachePolicyTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void boundedDefaultsAreConservativePerRuntimeBounds() {
        BlueCachePolicy policy = BlueCachePolicy.boundedDefaults();

        assertEquals(128, policy.derivedSnapshotMaxEntries());
        assertEquals(64L * MIB, policy.derivedSnapshotMaxWeightBytes());
        assertEquals(256, policy.canonicalAliasMaxEntries());
        assertEquals(16L * MIB, policy.canonicalAliasMaxWeightBytes());
        assertEquals(8_192, policy.resolvedStructuralMaxEntries());
        assertEquals(64L * MIB, policy.resolvedStructuralMaxWeightBytes());
        assertEquals(2_048, policy.transientReferenceMaxEntries());
        assertEquals(32L * MIB, policy.transientReferenceMaxWeightBytes());
        assertEquals(4_096, policy.conformancePlanMaxEntries());
        assertEquals(32L * MIB, policy.conformancePlanMaxWeightBytes());
        assertEquals(16L * MIB, policy.maximumDerivedEntryWeightBytes());
    }

    @Test
    void namedProfilesCoverLowMemoryHighThroughputAndDisabledModes() {
        BlueCachePolicy lowMemory = BlueCachePolicy.lowMemoryDefaults();
        BlueCachePolicy defaults = BlueCachePolicy.boundedDefaults();
        BlueCachePolicy highThroughput = BlueCachePolicy.highThroughputDefaults();
        BlueCachePolicy disabled = BlueCachePolicy.disabled();

        assertTrue(lowMemory.derivedSnapshotMaxWeightBytes()
                < defaults.derivedSnapshotMaxWeightBytes());
        assertTrue(defaults.derivedSnapshotMaxWeightBytes()
                < highThroughput.derivedSnapshotMaxWeightBytes());
        assertEquals(256L * MIB, highThroughput.derivedSnapshotMaxWeightBytes());
        assertEquals(128L * MIB, highThroughput.transientReferenceMaxWeightBytes());
        assertEquals(0, disabled.derivedSnapshotMaxEntries());
        assertEquals(0L, disabled.derivedSnapshotMaxWeightBytes());
        assertEquals(0, disabled.transientReferenceMaxEntries());
        assertEquals(0L, disabled.maximumDerivedEntryWeightBytes());
    }

    @Test
    void builderProducesImmutableExplicitBounds() {
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(3, 1_000L)
                .canonicalAliases(4, 2_000L)
                .resolvedStructuralEntries(5, 3_000L)
                .transientReferences(6, 4_000L)
                .conformancePlans(7, 5_000L)
                .maximumDerivedEntryWeightBytes(700L)
                .build();

        assertEquals(3, policy.derivedSnapshotMaxEntries());
        assertEquals(1_000L, policy.derivedSnapshotMaxWeightBytes());
        assertEquals(4, policy.canonicalAliasMaxEntries());
        assertEquals(5, policy.resolvedStructuralMaxEntries());
        assertEquals(6, policy.transientReferenceMaxEntries());
        assertEquals(7, policy.conformancePlanMaxEntries());
        assertEquals(700L, policy.maximumDerivedEntryWeightBytes());
    }

    @Test
    void rejectsNonPositiveBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> BlueCachePolicy.builder().derivedSnapshots(0, 1L).build());
        assertThrows(IllegalArgumentException.class,
                () -> BlueCachePolicy.builder().canonicalAliases(1, 0L).build());
        assertThrows(IllegalArgumentException.class,
                () -> BlueCachePolicy.builder().maximumDerivedEntryWeightBytes(0L).build());
    }
}
