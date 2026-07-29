package blue.language;

import org.junit.jupiter.api.Test;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueCachePolicyTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void shouldUseConservativePerRuntimeBoundsForBoundedDefaults() {
        // given
        int expectedDerivedEntries = 128;
        long expectedDerivedWeight = 64L * MIB;
        int expectedAliasEntries = 256;
        long expectedAliasWeight = 16L * MIB;

        // when
        BlueCachePolicy policy = BlueCachePolicy.boundedDefaults();

        // then
        assertEquals(expectedDerivedEntries, policy.derivedSnapshotMaxEntries());
        assertEquals(expectedDerivedWeight, policy.derivedSnapshotMaxWeightBytes());
        assertEquals(expectedAliasEntries, policy.canonicalAliasMaxEntries());
        assertEquals(expectedAliasWeight, policy.canonicalAliasMaxWeightBytes());
        assertEquals(8_192, policy.resolvedStructuralMaxEntries());
        assertEquals(64L * MIB, policy.resolvedStructuralMaxWeightBytes());
        assertEquals(2_048, policy.transientReferenceMaxEntries());
        assertEquals(32L * MIB, policy.transientReferenceMaxWeightBytes());
        assertEquals(4_096, policy.conformancePlanMaxEntries());
        assertEquals(32L * MIB, policy.conformancePlanMaxWeightBytes());
        assertEquals(16L * MIB, policy.maximumDerivedEntryWeightBytes());
    }

    @Test
    void shouldCoverLowMemoryHighThroughputAndDisabledModesWithNamedProfiles() {
        // given
        long expectedHighThroughputDerivedWeight = 256L * MIB;
        long expectedHighThroughputReferenceWeight = 128L * MIB;

        // when
        BlueCachePolicy lowMemory = BlueCachePolicy.lowMemoryDefaults();
        BlueCachePolicy defaults = BlueCachePolicy.boundedDefaults();
        BlueCachePolicy highThroughput = BlueCachePolicy.highThroughputDefaults();
        BlueCachePolicy disabled = BlueCachePolicy.disabled();

        // then
        assertTrue(lowMemory.derivedSnapshotMaxWeightBytes()
                < defaults.derivedSnapshotMaxWeightBytes());
        assertTrue(defaults.derivedSnapshotMaxWeightBytes()
                < highThroughput.derivedSnapshotMaxWeightBytes());
        assertEquals(expectedHighThroughputDerivedWeight,
                highThroughput.derivedSnapshotMaxWeightBytes());
        assertEquals(expectedHighThroughputReferenceWeight,
                highThroughput.transientReferenceMaxWeightBytes());
        assertEquals(0, disabled.derivedSnapshotMaxEntries());
        assertEquals(0L, disabled.derivedSnapshotMaxWeightBytes());
        assertEquals(0, disabled.transientReferenceMaxEntries());
        assertEquals(0L, disabled.maximumDerivedEntryWeightBytes());
    }

    @Test
    void shouldProduceImmutableExplicitBoundsFromBuilder() {
        // given
        int derivedEntries = 3;
        long derivedWeight = 1_000L;
        int aliasEntries = 4;
        long aliasWeight = 2_000L;

        // when
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(derivedEntries, derivedWeight)
                .canonicalAliases(aliasEntries, aliasWeight)
                .resolvedStructuralEntries(5, 3_000L)
                .transientReferences(6, 4_000L)
                .conformancePlans(7, 5_000L)
                .maximumDerivedEntryWeightBytes(700L)
                .build();

        // then
        assertEquals(derivedEntries, policy.derivedSnapshotMaxEntries());
        assertEquals(derivedWeight, policy.derivedSnapshotMaxWeightBytes());
        assertEquals(aliasEntries, policy.canonicalAliasMaxEntries());
        assertEquals(5, policy.resolvedStructuralMaxEntries());
        assertEquals(6, policy.transientReferenceMaxEntries());
        assertEquals(7, policy.conformancePlanMaxEntries());
        assertEquals(700L, policy.maximumDerivedEntryWeightBytes());
    }

    @Test
    void shouldRejectNonPositiveBounds() {
        // given
        int invalidEntryCount = 0;
        long invalidWeight = 0L;

        // when
        Throwable derivedSnapshotFailure = captureFailure(() ->
                BlueCachePolicy.builder()
                        .derivedSnapshots(invalidEntryCount, 1L)
                        .build());
        Throwable aliasFailure = captureFailure(() ->
                BlueCachePolicy.builder()
                        .canonicalAliases(1, invalidWeight)
                        .build());
        Throwable maximumEntryWeightFailure = captureFailure(() ->
                BlueCachePolicy.builder()
                        .maximumDerivedEntryWeightBytes(invalidWeight)
                        .build());

        // then
        assertEquals(IllegalArgumentException.class,
                derivedSnapshotFailure.getClass());
        assertEquals(IllegalArgumentException.class, aliasFailure.getClass());
        assertEquals(IllegalArgumentException.class,
                maximumEntryWeightFailure.getClass());
    }
}
