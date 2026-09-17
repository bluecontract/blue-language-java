package blue.language.processor.closure;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class OwnedSnapshotEncodeAcceptanceTest {
    private static final int LIMIT = 16 * 1024 * 1024;
    private static final DocumentId ROOT = new DocumentId("d0");

    @Test void completeProcessorEncodeAvoidsColdReadbackButStillBuildsFreshCyclicGraphs() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = CompositionReactionCycleTest.ring(fixture, "encoded-owned-cycle", 3, 1);
            Map<DocumentId, String> histories = new LinkedHashMap<>();
            graph.managedDocuments().forEach(d -> histories.put(d.documentId(), hash('c')));
            ClosureProcessResult result = fixture.admit(fixture.admission(graph, 100_000L).withRootedContext(
                    RootedProcessingContext.derive(graph, ROOT, histories), hash('d'))).processResult();
            assertTrue(result.commits(), diagnostic(result));
            assertReadback(result.rootedProjection().resultingSnapshot(), 1);
        }
    }

    @Test void publicMutableAliasesAndOrdinaryPublicCopiesCannotSeedAcceptedBytes() {
        AffectedClosureSnapshot original = snapshot(Collections.singletonMap(ROOT, document("public")), Collections.emptyList(), ROOT);
        AtomicInteger full = new AtomicInteger();
        AffectedClosureSnapshotStorageCodec codec = codec(full, LIMIT, 32);
        byte[] bytes = codec.encode(original);
        assertEquals(0, codec.acceptedByteStatistics().retainedEntries);
        full.set(0);
        AffectedClosureSnapshot decoded = codec.decode(bytes);
        assertEquals(1, full.get());
        AffectedClosureSnapshot copy = new AffectedClosureSnapshot(decoded.closureIdentity(), decoded.graphGeneration(),
                decoded.managedDocuments(), decoded.occurrences(), decoded.occurrenceBindingSetIdentity(),
                decoded.components(), decoded.publicRootDocumentIds(), decoded.rootedWitnesses());
        AffectedClosureSnapshotStorageCodec separate = codec(new AtomicInteger(), LIMIT, 32);
        assertArrayEquals(bytes, separate.encode(copy));
        assertEquals(0, separate.acceptedByteStatistics().retainedEntries);
        Node alias = original.managedDocument(ROOT).document();
        ManagedDocumentSnapshot unsafe = new ManagedDocumentSnapshot(ROOT, original.managedDocument(ROOT).blueId(),
                new AliasNode(alias), false, false, true, 0, original.managedDocument(ROOT).componentGeneration());
        AffectedClosureSnapshot mutable = new AffectedClosureSnapshot(original.closureIdentity(), original.graphGeneration(),
                Collections.singletonList(unsafe), original.occurrences(), original.occurrenceBindingSetIdentity(),
                original.components(), original.publicRootDocumentIds());
        separate.encode(mutable);
        assertEquals(0, separate.acceptedByteStatistics().retainedEntries);
        alias.properties("caller mutation", new Node().value(1));
        assertThrows(IllegalArgumentException.class, () -> separate.encode(mutable));
        assertEquals(0, separate.acceptedByteStatistics().retainedEntries);
    }

    @Test void encodeFailureBoundsDisabledRetentionAndReturnedByteMutationPreserveColdFallback() {
        AffectedClosureSnapshot original = snapshot(Collections.singletonMap(ROOT, document("bounds")), Collections.emptyList(), ROOT);
        byte[] baseline = new AffectedClosureSnapshotStorageCodec(LIMIT, 128).encode(original);
        AffectedClosureSnapshot owned = new AffectedClosureSnapshotStorageCodec(LIMIT, 128).decode(baseline);
        AffectedClosureSnapshotStorageCodec bounded = new AffectedClosureSnapshotStorageCodec(baseline.length - 1, 128);
        assertThrows(IllegalArgumentException.class, () -> bounded.encode(owned));
        assertEquals(0, bounded.acceptedByteStatistics().retainedEntries);
        AffectedClosureSnapshotStorageCodec shallow = new AffectedClosureSnapshotStorageCodec(LIMIT, 1);
        assertThrows(IllegalArgumentException.class, () -> shallow.encode(owned));
        assertEquals(0, shallow.acceptedByteStatistics().retainedEntries);
        for (int retainedBytes : new int[] {0, baseline.length - 1}) {
            AtomicInteger full = new AtomicInteger();
            AffectedClosureSnapshotStorageCodec limited = codec(full, retainedBytes, retainedBytes == 0 ? 0 : 1);
            assertArrayEquals(baseline, limited.encode(owned));
            assertEquals(0, limited.acceptedByteStatistics().retainedEntries);
            limited.decode(baseline);
            assertEquals(1, full.get());
        }
        AffectedClosureSnapshotStorageCodec codec = codec(new AtomicInteger(), LIMIT, 32);
        byte[] returned = codec.encode(owned);
        assertEquals(1, codec.acceptedByteStatistics().retainedEntries);
        Arrays.fill(returned, (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(returned));
        assertArrayEquals(baseline, codec.encode(codec.decode(baseline)));
        assertEquals(1, codec.acceptedByteStatistics().hits);
    }

    static void assertHistoricalReadback(ClosureProcessResult result) {
        if (result.rootedProjection() == null) return;
        AffectedClosureSnapshot output = result.rootedProjection().resultingSnapshot();
        assertNotNull(output.rootedWitnesses());
        assertFalse(output.rootedWitnesses().sources().isEmpty());
        assertReadback(output, 2);
    }

    private static void assertReadback(AffectedClosureSnapshot output, int expectedColdChecks) {
        assertTrue(output.hasVerifiedOwnedState());
        assertTrue(output.hasDetachedRepresentation());
        AtomicInteger fastChecks = new AtomicInteger(), coldChecks = new AtomicInteger();
        AffectedClosureSnapshotStorageCodec fast = codec(fastChecks, LIMIT, 32);
        AffectedClosureSnapshotStorageCodec control = codec(coldChecks, 0, 0);
        byte[] expected = control.encode(output), actual = fast.encode(output);
        assertArrayEquals(expected, actual);
        assertEquals(1, fast.acceptedByteStatistics().retainedEntries);
        fastChecks.set(0); coldChecks.set(0);
        AffectedClosureSnapshot ordinary = control.decode(expected), accepted = fast.decode(actual);
        assertEquals(expectedColdChecks, coldChecks.get());
        assertEquals(0, fastChecks.get());
        assertEquals(0, fast.acceptedByteStatistics().fullDecodeAttempts);
        assertNotSame(output, accepted);
        assertNotSame(output.managedDocuments().get(0), accepted.managedDocuments().get(0));
        assertArrayEquals(expected, fast.encode(accepted));
        assertArrayEquals(expected, control.encode(ordinary));
        if (output.rootedWitnesses() != null) for (DocumentId source : output.rootedWitnesses().sources()) {
            assertNotSame(output.rootedWitnesses().storedOriginals().get(source),
                    accepted.rootedWitnesses().storedOriginals().get(source));
            accepted.rootedWitnesses().storedOriginals().get(source).managedDocument(source)
                    .document().name("caller historical mutation");
            assertArrayEquals(expected, fast.encode(accepted));
        }
        System.out.println("Owned encode/readback full snapshot verification: cold=" + coldChecks.get()
                + ", owned=" + fastChecks.get() + ", managed=" + output.managedDocuments().size());
    }

    private static AffectedClosureSnapshotStorageCodec codec(AtomicInteger checks, int bytes, int entries) {
        return new AffectedClosureSnapshotStorageCodec(LIMIT, 128, Math.min(bytes, 8 * 1024 * 1024), entries, checks::incrementAndGet);
    }
    private static final class AliasNode extends Node {
        private final Node alias;
        AliasNode(Node alias) { this.alias = alias; }
        @Override public Node clone() { return alias; }
    }
}
