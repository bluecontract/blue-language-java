package blue.language.merge;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SemanticTypeEvidenceKeyTest {

    @Test
    void treatsListsAndArraysAsTheSameSemanticSequence() {
        Node listBacked = new Node().value(Arrays.asList(
                BigInteger.ONE,
                Arrays.asList(BigInteger.valueOf(2L),
                        BigInteger.valueOf(3L))));
        Node arrayBacked = new Node().value(new Object[]{
                Long.valueOf(1L),
                new int[]{2, 3}
        });

        SemanticTypeEvidenceKey listKey =
                SemanticTypeEvidenceKey.of(listBacked);
        SemanticTypeEvidenceKey arrayKey =
                SemanticTypeEvidenceKey.of(arrayBacked);

        assertEquals(listKey, arrayKey);
        assertEquals(listKey.hashCode(), arrayKey.hashCode());
        assertEvidenceLookupParity(listBacked, arrayBacked);
    }

    @Test
    void normalizesEquivalentJavaNumericWrappers() {
        Node byteValue = new Node().value(Byte.valueOf((byte) 7));
        Node shortValue = new Node().value(Short.valueOf((short) 7));
        Node integerValue = new Node().value(Integer.valueOf(7));
        Node longValue = new Node().value(Long.valueOf(7L));
        Node bigIntegerValue = new Node().value(BigInteger.valueOf(7L));

        SemanticTypeEvidenceKey integralKey =
                SemanticTypeEvidenceKey.of(byteValue);

        assertEquals(integralKey, SemanticTypeEvidenceKey.of(shortValue));
        assertEquals(integralKey, SemanticTypeEvidenceKey.of(integerValue));
        assertEquals(integralKey, SemanticTypeEvidenceKey.of(longValue));
        assertEquals(integralKey,
                SemanticTypeEvidenceKey.of(bigIntegerValue));
        assertEvidenceLookupParity(byteValue, bigIntegerValue);

        Node floatValue = new Node().value(Float.valueOf(1.25F));
        Node doubleValue = new Node().value(Double.valueOf(1.25D));
        Node decimalValue = new Node().value(new BigDecimal("1.2500"));
        SemanticTypeEvidenceKey floatingKey =
                SemanticTypeEvidenceKey.of(floatValue);

        assertEquals(floatingKey, SemanticTypeEvidenceKey.of(doubleValue));
        assertEquals(floatingKey, SemanticTypeEvidenceKey.of(decimalValue));
        assertEvidenceLookupParity(floatValue, decimalValue);
    }

    @Test
    void deferredWeightKeepsImmutableSharedGraphAcrossConcurrentReaders()
            throws Exception {
        Node shared = new Node().name("Original child")
                .value(Arrays.asList("retained", BigInteger.ONE));
        Node source = new Node().name("Original type").type(shared)
                .properties("first", shared).properties("second", shared);
        long expectedWeight = SemanticTypeEvidenceKey.of(source)
                .approximateRetainedWeightBytes();
        SemanticTypeEvidenceKey captured = SemanticTypeEvidenceKey.of(source);
        int expectedHash = captured.hashCode();
        source.name("Changed type").properties("later", keyHeavyType());
        shared.name("Changed child").value("replacement");
        java.util.concurrent.ExecutorService readers =
                java.util.concurrent.Executors.newFixedThreadPool(4);

        try {
            java.util.List<java.util.concurrent.Future<Long>> weights =
                    new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) {
                weights.add(readers.submit(
                        captured::approximateRetainedWeightBytes));
            }

            for (java.util.concurrent.Future<Long> weight : weights) {
                assertEquals(expectedWeight, weight.get().longValue());
            }
            assertEquals(expectedHash, captured.hashCode());
            assertEquals(expectedWeight,
                    captured.approximateRetainedWeightBytes());
        } finally {
            readers.shutdownNow();
        }
    }

    @Test
    void includesSemanticKeysInEvidenceSnapshotRetainedWeight() {
        CanonicalTypeIdentityIndex.EvidenceSnapshot shallow =
                evidenceSnapshot(new Node().name("Shallow type"));
        CanonicalTypeIdentityIndex.EvidenceSnapshot keyHeavy =
                evidenceSnapshot(keyHeavyType());

        assertTrue(
                keyHeavy.approximateRetainedWeightBytes()
                        > shallow.approximateRetainedWeightBytes(),
                "a larger semantic lookup key must increase snapshot weight");
    }

    @Test
    void rejectsKeyHeavyVerifiedEntryWhenOnlyShallowEntryFits() {
        CanonicalTypeIdentityIndex.EvidenceSnapshot shallowEvidence =
                evidenceSnapshot(new Node().name("Shallow type"));
        CanonicalTypeIdentityIndex.EvidenceSnapshot heavyEvidence =
                evidenceSnapshot(keyHeavyType());
        VerifiedReferenceEntry shallowEntry = cachedEntry(shallowEvidence);
        VerifiedReferenceEntry heavyEntry = cachedEntry(heavyEvidence);
        String shallowBlueId = blueId("Shallow cache entry");
        String heavyBlueId = blueId("Key-heavy cache entry");
        long shallowWeight = retainedWeight(shallowBlueId, shallowEntry);
        long heavyWeight = retainedWeight(heavyBlueId, heavyEntry);

        assertTrue(heavyWeight > shallowWeight);
        long admissionBudget = shallowWeight
                + (heavyWeight - shallowWeight) / 2L;
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .transientReferences(2, admissionBudget)
                .maximumDerivedEntryWeightBytes(admissionBudget)
                .build();

        Map<String, VerifiedReferenceEntry> shallowEntries = new HashMap<>();
        ResolvedReferenceCacheAccounting shallowAccounting =
                new ResolvedReferenceCacheAccounting(policy, true);
        shallowEntries.put(shallowBlueId, shallowEntry);
        shallowAccounting.recordVerifiedInsertion(
                shallowBlueId, shallowEntry, shallowEntries);

        Map<String, VerifiedReferenceEntry> heavyEntries = new HashMap<>();
        ResolvedReferenceCacheAccounting heavyAccounting =
                new ResolvedReferenceCacheAccounting(policy, true);
        heavyEntries.put(heavyBlueId, heavyEntry);
        heavyAccounting.recordVerifiedInsertion(
                heavyBlueId, heavyEntry, heavyEntries);

        assertTrue(shallowEntries.containsKey(shallowBlueId));
        assertFalse(heavyEntries.containsKey(heavyBlueId));
        assertEquals(0L, shallowAccounting.snapshot(1, 0)
                .verifiedOversizedRejections());
        assertEquals(1L, heavyAccounting.snapshot(0, 0)
                .verifiedOversizedRejections());
    }

    @Test
    void evictsOlderEntryWhenKeyWeightExceedsAggregateBudget() {
        CanonicalTypeIdentityIndex.EvidenceSnapshot shallowEvidence =
                evidenceSnapshot(new Node().name("Shallow type"));
        CanonicalTypeIdentityIndex.EvidenceSnapshot heavyEvidence =
                evidenceSnapshot(keyHeavyType());
        VerifiedReferenceEntry shallowEntry = cachedEntry(shallowEvidence);
        VerifiedReferenceEntry heavyEntry = cachedEntry(heavyEvidence);
        String shallowBlueId = blueId("First cache entry");
        String heavyBlueId = blueId("Second cache entry");
        long heavyWeight = retainedWeight(heavyBlueId, heavyEntry);
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .transientReferences(2, heavyWeight)
                .maximumDerivedEntryWeightBytes(heavyWeight)
                .build();
        Map<String, VerifiedReferenceEntry> entries = new HashMap<>();
        ResolvedReferenceCacheAccounting accounting =
                new ResolvedReferenceCacheAccounting(policy, true);

        entries.put(shallowBlueId, shallowEntry);
        accounting.recordVerifiedInsertion(
                shallowBlueId, shallowEntry, entries);
        entries.put(heavyBlueId, heavyEntry);
        accounting.recordVerifiedInsertion(
                heavyBlueId, heavyEntry, entries);

        ResolvedReferenceCache.CacheStats stats =
                accounting.snapshot(entries.size(), 0);
        assertFalse(entries.containsKey(shallowBlueId));
        assertTrue(entries.containsKey(heavyBlueId));
        assertEquals(1L, stats.verifiedEvictions());
        assertEquals(0L, stats.verifiedOversizedRejections());
        assertEquals(heavyWeight, stats.verifiedCurrentWeightBytes());
    }

    @Test
    void scalarValueEqualityChecksContentDespiteHashCollisions() {
        SemanticValueEvidenceKey absent = SemanticValueEvidenceKey.copyOf(null);
        assertEquals(absent, SemanticValueEvidenceKey.copyOf(null));
        assertNotEquals(absent, SemanticValueEvidenceKey.copyOf(""));
        assertEquals(SemanticValueEvidenceKey.copyOf(Integer.valueOf(7)),
                SemanticValueEvidenceKey.copyOf(BigInteger.valueOf(7L)));
        assertNotEquals(SemanticValueEvidenceKey.copyOf(Long.valueOf(7L)),
                SemanticValueEvidenceKey.copyOf("7"));
        SemanticValueEvidenceKey first = SemanticValueEvidenceKey.copyOf("Aa");
        SemanticValueEvidenceKey collision = SemanticValueEvidenceKey.copyOf("BB");
        assertEquals(first.hashCode(), collision.hashCode());
        assertNotEquals(first, collision);
        assertNotEquals(collision, first);
    }

    @Test
    void sharedRawValueChecksEveryDistinctRightAfterPairMapGrowth() {
        Map<String, Object> shared = new HashMap<>();
        shared.put("value", "Aa");
        java.util.List<Object> left = new java.util.ArrayList<>();
        java.util.List<Object> right = new java.util.ArrayList<>();
        for (int index = 0; index < 40; index++) {
            left.add(shared);
            right.add(new HashMap<>(shared));
        }
        SemanticValueEvidenceKey captured = SemanticValueEvidenceKey.copyOf(left);
        assertEquals(captured, SemanticValueEvidenceKey.copyOf(right));
        // Iterative comparison visits this first list slot last. Its value
        // has the same String hash, so hash filtering cannot hide a bad pair.
        right.set(0, java.util.Collections.singletonMap("value", "BB"));
        SemanticValueEvidenceKey collision = SemanticValueEvidenceKey.copyOf(right);
        assertEquals(captured.hashCode(), collision.hashCode());
        assertNotEquals(captured, collision);
        assertNotEquals(collision, captured);
        shared.put("value", "BB");
        assertNotEquals(captured, SemanticValueEvidenceKey.copyOf(left));
        java.util.List<Object> cycle = new java.util.ArrayList<>();
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class,
                () -> SemanticValueEvidenceKey.copyOf(cycle));
    }

    @Test
    void sharedTypeChecksEveryDistinctRightAfterPairMapGrowth() {
        Node shared = new Node().name("Aa");
        Node left = new Node();
        Node right = new Node();
        for (int index = 0; index < 40; index++) {
            String key = String.format(java.util.Locale.ROOT, "child%02d", index);
            left.properties(key, shared);
            right.properties(key, new Node().name("Aa"));
        }
        SemanticTypeEvidenceKey captured = SemanticTypeEvidenceKey.of(left);
        assertEquals(captured, SemanticTypeEvidenceKey.of(right));
        // Sorted child00 is visited last after the shared left node has
        // already matched the other 39 independent but equivalent children.
        right.properties("child00", new Node().name("BB"));
        SemanticTypeEvidenceKey collision = SemanticTypeEvidenceKey.of(right);
        assertEquals(captured.hashCode(), collision.hashCode());
        assertNotEquals(captured, collision);
        assertNotEquals(collision, captured);
    }

    private static void assertEvidenceLookupParity(
            Node recorded,
            Node equivalent) {
        String canonicalBlueId = blueId("Canonical evidence identity");
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        identities.record(
                recorded,
                canonicalBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.VERIFIED_REFERENCE,
                null,
                null);

        assertEquals(
                canonicalBlueId,
                identities.requireCanonicalTypeBlueId(equivalent));
    }

    private static CanonicalTypeIdentityIndex.EvidenceSnapshot
    evidenceSnapshot(Node completedType) {
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        identities.record(
                completedType,
                blueId("Canonical type identity"),
                CanonicalTypeIdentityIndex.EvidenceKind.VERIFIED_REFERENCE,
                null,
                null);
        return identities.completeSnapshotForResolvedReference(
                new Node().type(completedType));
    }

    private static Node keyHeavyType() {
        Node type = new Node().name("Key-heavy type");
        for (int index = 0; index < 128; index++) {
            type.properties(
                    "property-" + index + "-with-retained-key-material",
                    new Node().value(Arrays.asList(
                            "retained-value-" + index,
                            BigInteger.valueOf(index))));
        }
        return type;
    }

    private static VerifiedReferenceEntry cachedEntry(
            CanonicalTypeIdentityIndex.EvidenceSnapshot evidence) {
        FrozenNode content = FrozenNode.fromNode(
                new Node().name("Shared cached content"));
        return new VerifiedReferenceEntry(content, content, evidence);
    }

    private static long retainedWeight(
            String blueId,
            VerifiedReferenceEntry entry) {
        BlueCachePolicy policy = BlueCachePolicy.boundedDefaults();
        ResolvedReferenceCacheAccounting accounting =
                new ResolvedReferenceCacheAccounting(policy, true);
        Map<String, VerifiedReferenceEntry> entries = new HashMap<>();
        entries.put(blueId, entry);
        accounting.recordVerifiedInsertion(blueId, entry, entries);
        return accounting.snapshot(entries.size(), 0)
                .verifiedCurrentWeightBytes();
    }

    private static String blueId(String name) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().name(name));
    }
}
