package blue.language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueCachePolicyTest {

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
