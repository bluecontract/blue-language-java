package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.ExactNodeStorageCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class AffectedClosureSnapshotStorageCodecTest {
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final AffectedClosureSnapshotStorageCodec CODEC = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
    private static final DocumentId A = new DocumentId("A"), B = new DocumentId("B"), C = new DocumentId("C");

    @Test void exactAcyclicSnapshotIsDetachedAndRetainsEveryBodyField() {
        Node body = document("exact").name("supplementary pair \uD83D\uDE80").description("description")
                .properties("metadata", new Node().name("retained metadata").value("exact value")
                        .preprocessingTransformationConfiguration(true));
        AffectedClosureSnapshot original = snapshot(Collections.singletonMap(A, body), Collections.emptyList(), A);
        byte[] stored = CODEC.encode(original);
        AffectedClosureSnapshot restored = new AffectedClosureSnapshotStorageCodec(LIMIT, 128).decode(stored);
        assertNotSame(original, restored);
        assertArrayEquals(stored, CODEC.encode(restored));
        assertEquals(original.closureIdentity(), restored.closureIdentity());
        assertEquals(original.graphGeneration(), restored.graphGeneration());
        assertEquals(original.managedDocument(A).document().getName(), restored.managedDocument(A).document().getName());
        restored.managedDocument(A).document().properties("injected", new Node().value(1));
        assertArrayEquals(stored, CODEC.encode(restored));
        Arrays.fill(stored, (byte) 0);
        assertEquals(original.closureIdentity(), restored.closureIdentity());
        assertNull(restored.rootedWitnesses());
    }

    @Test void completeCyclicProofAndMemberMappingSurviveStorage() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot original = CompositionReactionCycleTest.ring(fixture, "stored-cycle", 3, 1);
            assertTrue(original.components().stream().anyMatch(c -> c.kind() == ComponentKind.CYCLIC));
            byte[] bytes = CODEC.encode(original);
            AffectedClosureSnapshot restored = CODEC.decode(bytes);
            assertArrayEquals(bytes, CODEC.encode(restored));
            ComponentSnapshot before = original.components().get(0), after = restored.components().get(0);
            assertEquals(before.cyclicProofIdentity(), after.cyclicProofIdentity());
            assertEquals(before.orderedMemberBlueIds(), after.orderedMemberBlueIds());
            assertNotSame(before.completeCyclicProof(), after.completeCyclicProof());
            after.completeCyclicProof().declaredPlaceholderSet().get(0).name("changed");
            assertArrayEquals(bytes, CODEC.encode(restored));
        }
    }

    @Test void representationCursorAndRetiredRowsRemainExact() {
        Node child = document("child");
        Node parent = document("parent").properties("child", new Node().blueId(id(child)));
        ManagedRepresentationCursor cursor = new ManagedRepresentationCursor(hash('a'), hash('b'), hash('c'), hash('d'));
        ManagedOccurrenceBinding row = ManagedOccurrenceBinding.derived(hash('a'), A, ScopeAddress.embedded("/child", 3L),
                B, id(child), false, 2L).withRepresentationCursor(cursor);
        Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(A, parent); bodies.put(B, child);
        AffectedClosureSnapshot original = snapshot(bodies, Collections.singletonList(row), A);
        AffectedClosureSnapshot restored = CODEC.decode(CODEC.encode(original));
        ManagedOccurrenceBinding actual = restored.occurrences().get(0);
        assertFalse(actual.active()); assertEquals(Long.valueOf(2L), actual.pendingHistoricalEpoch());
        assertEquals(cursor, actual.pendingRepresentationCursor());
        assertEquals(row.occurrenceIdentity(), actual.occurrenceIdentity());
        assertEquals(row.bindingIdentity(), actual.bindingIdentity());
    }

    @Test void originalWitnessSnapshotsUseDetachedSharedBackReferences() throws Exception {
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        for (DocumentId id : Arrays.asList(A, B, C)) bodies.put(id, initialized(document(id.value())));
        AffectedClosureSnapshot raw = snapshot(bodies, Collections.emptyList(), A);
        List<ManagedDocumentSnapshot> initialized = new ArrayList<>();
        for (ManagedDocumentSnapshot d : raw.managedDocuments()) initialized.add(new ManagedDocumentSnapshot(d.documentId(),
                d.blueId(), d.document(), true, false, d.publicRoot(), d.epoch(), d.componentGeneration()));
        AffectedClosureSnapshot original = ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(), initialized,
                raw.occurrences(), raw.components(), raw.publicRootDocumentIds());
        Map<DocumentId, AffectedClosureSnapshot> witnesses = new LinkedHashMap<>(); witnesses.put(C, original); witnesses.put(B, original);
        AffectedClosureSnapshot selected = new AffectedClosureSnapshot(original.closureIdentity(), original.graphGeneration(),
                original.managedDocuments(), original.occurrences(), original.occurrenceBindingSetIdentity(), original.components(),
                original.publicRootDocumentIds(), RootedWitnessFrame.State.fromStoredOriginals(witnesses));
        byte[] bytes = CODEC.encode(selected);
        AffectedClosureSnapshot restored = CODEC.decode(bytes);
        Map<DocumentId, AffectedClosureSnapshot> decoded = restored.rootedWitnesses().storedOriginals();
        assertEquals(Arrays.asList(B, C), new ArrayList<>(decoded.keySet()));
        assertSame(decoded.get(B), decoded.get(C));
        assertNotSame(original, decoded.get(B));
        assertArrayEquals(bytes, CODEC.encode(restored));
        assertThrows(UnsupportedOperationException.class, () -> decoded.clear());
        assertThrows(IllegalArgumentException.class, () -> RootedWitnessFrame.State.fromStoredOriginals(
                Collections.singletonMap(new DocumentId("missing"), original)));
        AffectedClosureSnapshotStorageCodec independent = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
        AffectedClosureSnapshot cold = independent.decode(bytes), warm = independent.decode(bytes.clone());
        assertEquals(1, independent.acceptedByteStatistics().hits);
        assertNotSame(cold, warm);
        assertNotSame(cold.rootedWitnesses().storedOriginals().get(B), warm.rootedWitnesses().storedOriginals().get(B));
        assertSame(warm.rootedWitnesses().storedOriginals().get(B), warm.rootedWitnesses().storedOriginals().get(C));
        assertArrayEquals(bytes, independent.encode(warm));
        byte[] originalBytes = independent.encode(original);
        Map<DocumentId, AffectedClosureSnapshot> separate = new LinkedHashMap<>();
        separate.put(B, independent.decode(originalBytes));
        separate.put(C, independent.decode(originalBytes));
        assertNotSame(separate.get(B), separate.get(C), "A warm certificate must not intern equal roots across calls");
        AffectedClosureSnapshot separatelySelected = new AffectedClosureSnapshot(original.closureIdentity(), original.graphGeneration(),
                original.managedDocuments(), original.occurrences(), original.occurrenceBindingSetIdentity(), original.components(),
                original.publicRootDocumentIds(), RootedWitnessFrame.State.fromStoredOriginals(separate));
        byte[] separateBytes = independent.encode(separatelySelected);
        assertFalse(Arrays.equals(bytes, separateBytes), "Distinct exact proofs retain their different wire alias topology");
        independent.decode(separateBytes);
        AffectedClosureSnapshot separateWarm = independent.decode(separateBytes);
        assertNotSame(separateWarm.rootedWitnesses().storedOriginals().get(B), separateWarm.rootedWitnesses().storedOriginals().get(C));
        assertArrayEquals(separateBytes, independent.encode(separateWarm));
        try (SnapshotStorageCall call = CODEC.newCall()) {
            byte[] withoutWitnesses = call.encode(original);
            byte[] withWitnesses = call.encode(selected);
            assertEquals(original.closureIdentity(), selected.closureIdentity());
            assertFalse(Arrays.equals(withoutWitnesses, withWitnesses), "Closure identity is not a storage reuse key");
            AffectedClosureSnapshot retained = call.decode(withWitnesses);
            assertSame(retained, call.decode(withWitnesses.clone()));
            assertSame(retained.rootedWitnesses().storedOriginals().get(B),
                    retained.rootedWitnesses().storedOriginals().get(C));
            assertArrayEquals(bytes, call.encode(retained));
            // This fixture ends in B + one complete original, then C + a backward
            // reference. Swap only the source labels: the same valid roles remain,
            // but their physical ordering is no longer canonical.
            int originalPayload = withoutWitnesses.length - 32
                    - (8 + 2 * AffectedClosureSnapshotStorageCodec.FORMAT.length());
            byte[] unsorted = bytes.clone();
            int firstSource = bytes.length - 32 - 11 - originalPayload - 1;
            int secondSource = bytes.length - 32 - 6;
            assertEquals((byte) 'B', unsorted[firstSource]); assertEquals((byte) 'C', unsorted[secondSource]);
            unsorted[firstSource] = 'C'; unsorted[secondSource] = 'B'; seal(unsorted);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> call.decode(unsorted));
            assertEquals("Noncanonical affected-closure snapshot storage", failure.getMessage());
            assertThrows(IllegalArgumentException.class, () -> independent.decode(unsorted));
            assertThrows(IllegalArgumentException.class, () -> independent.decode(unsorted));
            assertSame(retained, call.decode(bytes));
        }
    }

    @Test void checksumValidSemanticDamageAndUnboundedCountAreRejected() throws Exception {
        AffectedClosureSnapshot original = snapshot(Collections.singletonMap(A, document("integrity")), Collections.emptyList(), A);
        byte[] bytes = CODEC.encode(original);
        byte[] changedIdentity = bytes.clone();
        DataInputStream in = payload(changedIdentity);
        in.readInt(); ExactNodeStorageCodec.requiredText(in); in.readByte();
        int position = changedIdentity.length - 32 - in.available();
        // Keep a syntactically valid sha256 identity, but contradict its exact snapshot.
        int hexUnit = position + 4 + 2 * 7 + 1;
        changedIdentity[hexUnit] = changedIdentity[hexUnit] == 'a' ? (byte) 'b' : (byte) 'a';
        seal(changedIdentity);
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(changedIdentity));
        byte[] badCount = bytes.clone();
        in = payload(badCount); in.readInt(); ExactNodeStorageCodec.requiredText(in); in.readByte();
        ExactNodeStorageCodec.requiredText(in); in.readLong();
        position = badCount.length - 32 - in.available();
        ByteBuffer.wrap(badCount).putInt(position, Integer.MAX_VALUE); seal(badCount);
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(badCount));
        byte[] badReference = new ExactNodeStorageCodec(LIMIT, 128).encodeEnvelope(AffectedClosureSnapshotStorageCodec.FORMAT,
                out -> { out.writeByte(0); out.writeInt(0); });
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(badReference));
    }

    @Test void byteDepthFramingAndTrailingDataBoundsFailClosed() throws Exception {
        AffectedClosureSnapshot original = snapshot(Collections.singletonMap(A, document("bounds")), Collections.emptyList(), A);
        byte[] bytes = CODEC.encode(original);
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(bytes.length - 1, 128).encode(original));
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(bytes.length - 1, 128).decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 1).encode(original));
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 1).decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        byte[] corrupt = bytes.clone(); corrupt[corrupt.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(corrupt));
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1); seal(trailing);
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(trailing));
        assertArrayEquals(bytes, CODEC.encode(original));
    }

    @Test void oneCallReusesOnlyFullyValidatedImmutableSnapshotsAndOwnsItsBytes() {
        AffectedClosureSnapshot original = snapshot(Collections.singletonMap(A, document("reuse")), Collections.emptyList(), A);
        byte[] canonical = CODEC.encode(original);
        byte[] supplied = canonical.clone();
        SnapshotStorageCall call = CODEC.newCall();
        AffectedClosureSnapshot restored;
        try {
            restored = call.decode(supplied);
            assertNotSame(original, restored);
            assertEquals(1, call.verificationAttempts(), "Canonical re-encoding does not repeat semantic verification");
            assertSame(restored, call.decode(canonical.clone()));
            assertEquals(1, call.decodeHits());
            Arrays.fill(supplied, (byte) 0);
            restored.managedDocument(A).document().properties("injected", new Node().value(1));
            byte[] returned = call.encode(restored);
            Arrays.fill(returned, (byte) 0);
            assertArrayEquals(canonical, call.encode(restored));
            assertEquals(1, call.verificationAttempts());
            assertEquals(2, call.encodeHits());
        } finally { call.close(); }
        assertEquals(0, call.retainedBytes()); assertEquals(0, call.retainedEntries());
        assertEquals(0, call.retainedVerifications());
        assertThrows(IllegalStateException.class, () -> call.decode(canonical));
        assertThrows(IllegalStateException.class, () -> call.encode(original));
        try (SnapshotStorageCall next = CODEC.newCall()) {
            assertNotSame(restored, next.decode(canonical));
            assertEquals(0, next.verificationAttempts(), "A separate call parses fresh objects from accepted bytes");
            assertEquals(0, next.decodeHits(), "The decoded-object memo remains call-local");
        }
    }

    @Test void acceptedBytesRequireADecodeAndNeverRetainCallerArraysOrNodeClones() {
        AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
        AffectedClosureSnapshot original = snapshot(Collections.singletonMap(A, document("accepted bytes")), Collections.emptyList(), A);
        byte[] bytes = codec.encode(original), supplied = bytes.clone();
        assertEquals(0, codec.acceptedByteStatistics().retainedEntries, "Encode alone is not decode acceptance");
        AffectedClosureSnapshot cold = codec.decode(supplied);
        Arrays.fill(supplied, (byte) 0);
        cold.managedDocument(A).document().properties("injected", new Node().value(42));
        byte[] returned = codec.encode(cold); Arrays.fill(returned, (byte) 0);
        AffectedClosureSnapshot warm;
        try (SnapshotStorageCall call = codec.newCall()) {
            warm = codec.decodeInCall(bytes.clone(), call);
            assertEquals(0, call.verificationAttempts());
        }
        assertNotSame(cold, warm);
        assertArrayEquals(bytes, codec.encode(warm));
        assertEquals(1, codec.acceptedByteStatistics().fullDecodeAttempts);
        assertEquals(1, codec.acceptedByteStatistics().hits);
        assertEquals(bytes.length, codec.acceptedByteStatistics().retainedBytes);
        AffectedClosureSnapshotStorageCodec other = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
        assertArrayEquals(bytes, other.encode(other.decode(bytes)));
        assertEquals(1, other.acceptedByteStatistics().fullDecodeAttempts);
        assertEquals(0, other.acceptedByteStatistics().hits, "A new codec starts cold");
    }

    @Test void acceptedByteBoundsEvictAndDisabledCallsBypassBothLookupAndAdmission() {
        byte[] first = CODEC.encode(snapshot(Collections.singletonMap(A, document("byte first")), Collections.emptyList(), A));
        byte[] second = CODEC.encode(snapshot(Collections.singletonMap(A, document("byte other")), Collections.emptyList(), A));
        int one = Math.max(first.length, second.length);
        for (int[] bounds : new int[][] {{one * 2, 1}, {one, 2}}) {
            AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(LIMIT, 128, bounds[0], bounds[1]);
            codec.decode(first); codec.decode(second); codec.decode(first);
            assertEquals(3, codec.acceptedByteStatistics().fullDecodeAttempts);
            assertEquals(0, codec.acceptedByteStatistics().hits);
            assertArrayEquals(first, codec.encode(codec.decode(first)));
            assertEquals(1, codec.acceptedByteStatistics().hits);
            assertTrue(codec.acceptedByteStatistics().peakBytes <= bounds[0]);
            assertTrue(codec.acceptedByteStatistics().peakEntries <= bounds[1]);
        }
        for (int[] bounds : new int[][] {{first.length - 1, 2}, {0, 0}}) {
            AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(LIMIT, 128, bounds[0], bounds[1]);
            assertNotSame(codec.decode(first), codec.decode(first));
            assertEquals(2, codec.acceptedByteStatistics().fullDecodeAttempts);
            assertEquals(0, codec.acceptedByteStatistics().hits);
            assertEquals(0, codec.acceptedByteStatistics().retainedEntries);
            assertEquals(0, codec.acceptedByteStatistics().retainedBytes);
        }
        AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
        codec.decode(first);
        try (SnapshotStorageCall disabled = new SnapshotStorageCall(codec, 0, 0)) {
            assertNotSame(disabled.decode(first), disabled.decode(first));
            assertArrayEquals(second, disabled.encode(disabled.decode(second)));
        }
        assertEquals(4, codec.acceptedByteStatistics().fullDecodeAttempts);
        assertEquals(0, codec.acceptedByteStatistics().hits);
        assertEquals(1, codec.acceptedByteStatistics().retainedEntries);
        assertEquals(first.length, codec.acceptedByteStatistics().retainedBytes, "Disabled decoding did not admit the second value");
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 128, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 128, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 128, LIMIT + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 128, 1, 33));
    }

    @Test void callLocalMemoUsesThePrivateDecodedEnvelopeAfterCallerMutation() {
        byte[] bytes = CODEC.encode(snapshot(Collections.singletonMap(A, document("memo handoff")), Collections.emptyList(), A));
        AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
        for (int pass = 0; pass < 2; pass++) {
            byte[] caller = bytes.clone();
            try (SnapshotStorageCall call = codec.newCall()) {
                AffectedClosureSnapshot restored = call.decode(caller, () -> Arrays.fill(caller, (byte) 0));
                assertArrayEquals(bytes, codec.encode(restored));
                assertThrows(IllegalArgumentException.class, () -> call.decode(caller),
                        "Mutated caller bytes must not become a memo key for the original snapshot");
                assertSame(restored, call.decode(bytes), "Memo identity belongs to the private validated bytes");
                assertEquals(1, call.decodeHits());
            }
        }
        assertEquals(1, codec.acceptedByteStatistics().hits, "The second complete call exercises a warm certificate");
    }

    @Test void inputBoundsAreCheckedBeforeThePrivateCopyHandoff() {
        AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(128, 128);
        for (byte[] bytes : Arrays.asList(null, new byte[35], new byte[129])) {
            try (SnapshotStorageCall call = codec.newCall()) {
                assertThrows(IllegalArgumentException.class, () -> codec.decodeInCall(bytes, call,
                        () -> fail("Out-of-bound input reached the private-copy handoff")));
            }
        }
        assertEquals(0, codec.acceptedByteStatistics().fullDecodeAttempts);
        assertEquals(0, codec.acceptedByteStatistics().retainedBytes);
    }

    @Test void concurrentColdAndWarmHandoffsKeepPrivateBytesFreshGraphsAndOneBoundedCertificate() throws Exception {
        byte[] bytes = CODEC.encode(snapshot(Collections.singletonMap(A, document("concurrent certificate")), Collections.emptyList(), A));
        AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(LIMIT, 128, bytes.length, 1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int pass = 0; pass < 2; pass++) {
                CountDownLatch selected = new CountDownLatch(2), release = new CountDownLatch(1);
                List<byte[]> supplied = Arrays.asList(bytes.clone(), bytes.clone());
                List<Future<AffectedClosureSnapshot>> results = new ArrayList<>();
                try {
                    for (byte[] input : supplied) results.add(workers.submit(() -> {
                        try (SnapshotStorageCall call = codec.newCall()) {
                            return codec.decodeInCall(input, call, () -> {
                                selected.countDown(); await(release);
                            });
                        }
                    }));
                    assertTrue(selected.await(10, TimeUnit.SECONDS));
                    assertEquals(pass == 0 ? 0 : 1, codec.acceptedByteStatistics().retainedEntries,
                            "A cold decode cannot publish acceptance before validation completes");
                    supplied.forEach(input -> Arrays.fill(input, (byte) 0));
                    release.countDown();
                    AffectedClosureSnapshot first = results.get(0).get(10, TimeUnit.SECONDS);
                    AffectedClosureSnapshot second = results.get(1).get(10, TimeUnit.SECONDS);
                    assertNotSame(first, second);
                    assertArrayEquals(bytes, codec.encode(first)); assertArrayEquals(bytes, codec.encode(second));
                    assertEquals(2, codec.acceptedByteStatistics().fullDecodeAttempts);
                    assertEquals(pass == 0 ? 0 : 2, codec.acceptedByteStatistics().hits);
                    assertEquals(1, codec.acceptedByteStatistics().peakEntries);
                    assertEquals(bytes.length, codec.acceptedByteStatistics().peakBytes);
                } finally { release.countDown(); }
            }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void byteAndEntryBoundsEvictBeforeRetentionAndFallBackWithoutChangingBytes() {
        AffectedClosureSnapshot first = snapshot(Collections.singletonMap(A, document("first")), Collections.emptyList(), A);
        AffectedClosureSnapshot second = snapshot(Collections.singletonMap(A, document("other")), Collections.emptyList(), A);
        byte[] firstBytes = CODEC.encode(first), secondBytes = CODEC.encode(second);
        int onePayload = Math.max(firstBytes.length, secondBytes.length);
        for (int[] bounds : new int[][] {{onePayload * 2, 1}, {onePayload, 2}}) {
            try (SnapshotStorageCall call = new SnapshotStorageCall(CODEC, bounds[0], bounds[1])) {
                AffectedClosureSnapshot prior = call.decode(firstBytes);
                call.decode(secondBytes);
                assertNotSame(prior, call.decode(firstBytes), "Eviction forces a complete fresh decode");
                assertEquals(0, call.decodeHits());
                assertArrayEquals(firstBytes, call.encode(first));
                assertTrue(call.peakBytes() <= bounds[0]); assertTrue(call.peakEntries() <= bounds[1]);
                assertTrue(call.retainedVerifications() <= bounds[1]);
            }
        }
        for (int[] bounds : new int[][] {{firstBytes.length - 1, 2}, {0, 0}}) {
            try (SnapshotStorageCall call = new SnapshotStorageCall(CODEC, bounds[0], bounds[1])) {
                assertNotSame(call.decode(firstBytes), call.decode(firstBytes));
                assertArrayEquals(firstBytes, call.encode(first));
                assertEquals(0, call.retainedBytes()); assertEquals(0, call.retainedEntries());
                assertEquals(0, call.decodeHits()); assertEquals(0, call.encodeHits());
            }
        }
    }

    @Test void sameIdentityWrongBodyAndFailedValidationNeverBecomeReuseAuthority() {
        AffectedClosureSnapshot original = snapshot(Collections.singletonMap(A, document("body")), Collections.emptyList(), A);
        ManagedDocumentSnapshot before = original.managedDocument(A);
        ManagedDocumentSnapshot wrong = new ManagedDocumentSnapshot(A, before.blueId(),
                before.document().properties("injected", new Node().value(1)), before.initialized(), before.terminated(),
                before.publicRoot(), before.epoch(), before.componentGeneration());
        AffectedClosureSnapshot forged = new AffectedClosureSnapshot(original.closureIdentity(), original.graphGeneration(),
                Collections.singletonList(wrong), original.occurrences(), original.occurrenceBindingSetIdentity(),
                original.components(), original.publicRootDocumentIds());
        try (SnapshotStorageCall call = CODEC.newCall()) {
            byte[] bytes = call.encode(original);
            assertTrue(call.hasVerified(original));
            assertFalse(call.hasVerified(forged));
            int attempts = call.verificationAttempts();
            assertThrows(IllegalArgumentException.class, () -> call.encode(forged));
            assertThrows(IllegalArgumentException.class, () -> call.encode(forged));
            assertEquals(attempts + 2, call.verificationAttempts());
            assertFalse(call.hasVerified(forged));
            assertEquals(1, call.retainedEntries()); assertEquals(1, call.retainedVerifications());
            assertArrayEquals(bytes, call.encode(original));
        }
    }

    @Test void warmedDecodeStillRejectsChecksumSemanticAndFramingDamage() throws Exception {
        byte[] canonical = CODEC.encode(snapshot(Collections.singletonMap(A, document("damaged")), Collections.emptyList(), A));
        byte[] corrupt = canonical.clone(); corrupt[corrupt.length / 2] ^= 1;
        byte[] wrongHead = canonical.clone();
        DataInputStream in = payload(wrongHead);
        in.readInt(); ExactNodeStorageCodec.requiredText(in); in.readByte();
        ExactNodeStorageCodec.requiredText(in); in.readLong(); in.readInt(); ExactNodeStorageCodec.requiredText(in);
        int headCharacter = wrongHead.length - 32 - in.available() + 4 + 1;
        wrongHead[headCharacter] = wrongHead[headCharacter] == 'A' ? (byte) 'B' : (byte) 'A';
        seal(wrongHead);
        byte[] wrongBody = canonical.clone(), label = "damaged".getBytes(StandardCharsets.UTF_16BE);
        int changes = 0;
        for (int offset = 0; offset <= canonical.length - 32 - label.length; offset++) {
            if (Arrays.equals(label, Arrays.copyOfRange(canonical, offset, offset + label.length))) {
                wrongBody[offset + 1] = 'x'; changes++;
            }
        }
        assertEquals(1, changes, "Change the exact stored label while retaining its asserted head and identities");
        seal(wrongBody);
        byte[] trailing = Arrays.copyOf(canonical, canonical.length + 1); seal(trailing);
        AffectedClosureSnapshotStorageCodec independent = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
        independent.decode(canonical);
        for (byte[] damaged : Arrays.asList(corrupt, wrongHead, wrongBody, trailing)) {
            assertThrows(IllegalArgumentException.class, () -> independent.decode(damaged));
            assertThrows(IllegalArgumentException.class, () -> independent.decode(damaged));
        }
        assertEquals(9, independent.acceptedByteStatistics().fullDecodeAttempts);
        assertEquals(1, independent.acceptedByteStatistics().retainedEntries, "Failed decodes never enter the certificate cache");
        assertEquals(canonical.length, independent.acceptedByteStatistics().retainedBytes);
        assertArrayEquals(canonical, independent.encode(independent.decode(canonical)));
        assertEquals(1, independent.acceptedByteStatistics().hits);
        try (SnapshotStorageCall call = CODEC.newCall()) {
            AffectedClosureSnapshot valid = call.decode(canonical);
            for (byte[] damaged : Arrays.asList(corrupt, wrongHead, wrongBody, trailing)) {
                assertThrows(IllegalArgumentException.class, () -> call.decode(damaged));
                assertThrows(IllegalArgumentException.class, () -> call.decode(damaged));
            }
            assertEquals(1, call.retainedEntries()); assertEquals(0, call.decodeHits());
            assertSame(valid, call.decode(canonical));
        }
    }

    private static DataInputStream payload(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 32));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static void seal(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
    }

    private static Node initialized(Node source) {
        String authored = id(source);
        return source.clone().contracts(source.getContracts().clone().properties("initialized",
                typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER).properties("document", new Node().blueId(authored))));
    }
}
