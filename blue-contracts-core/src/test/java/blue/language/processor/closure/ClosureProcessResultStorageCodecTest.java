package blue.language.processor.closure;

import org.junit.jupiter.api.Test;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.*;
import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class ClosureProcessResultStorageCodecTest {
    static final ClosureProcessResultStorageCodec CODEC = new ClosureProcessResultStorageCodec(16 * 1024 * 1024, 128);

    @Test void rootedCycleAtExactGasAndOneBelowRetainsCompleteTraceAndRejectedCharge() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = CompositionReactionCycleTest.ring(fixture, "stored-rooted", 3, 2);
            Map<DocumentId, String> histories = new LinkedHashMap<>();
            graph.managedDocuments().forEach(d -> histories.put(d.documentId(), hash('c')));
            RootedProcessingContext context = RootedProcessingContext.derive(graph, new DocumentId("d0"), histories);
            ClosureProcessResult measured = fixture.admit(fixture.admission(graph, 100_000L).withRootedContext(context, hash('d'))).processResult();
            assertTrue(measured.commits(), diagnostic(measured));
            fixture.reactions.clear(); fixture.initialized.clear();
            ClosureProcessResult exact = fixture.admit(fixture.admission(graph, measured.totalGas()).withRootedContext(context, hash('d'))).processResult();
            assertTrue(exact.commits(), diagnostic(exact));
            int calls = fixture.reactions.size();
            ClosureProcessResult restored = assertRoundTrip(exact);
            assertEquals(calls, fixture.reactions.size(), "Storage restoration never invokes a handler");
            assertEquals(exact.rootedProjection().ownedDocumentIds(), restored.rootedProjection().ownedDocumentIds());
            assertEquals(exact.rootedProjection().topologyBoundaries().size(), restored.rootedProjection().topologyBoundaries().size());
            assertNotSame(exact.rootedProjection(), restored.rootedProjection());
            assertNotSame(exact.rootedProjection().storageOwnership(), restored.rootedProjection().storageOwnership());
            fixture.reactions.clear(); fixture.initialized.clear();
            ClosureInvocationInput belowInput = fixture.admission(graph, measured.totalGas() - 1L).withRootedContext(context, hash('d'));
            ClosureProcessResult below = fixture.admit(belowInput).processResult();
            rollback(belowInput, below);
            assertNotNull(below.rejectedCharge()); assertNull(below.rootedProjection());
            calls = fixture.reactions.size();
            ClosureProcessResult coldFailure = assertRoundTrip(below);
            assertEquals(calls, fixture.reactions.size());
            assertNull(coldFailure.rootedProjection());
            assertEquals(below.rejectedCharge().rejectedChargeIdentity(), coldFailure.rejectedCharge().rejectedChargeIdentity());
        }
    }

    @Test void ordinaryCompatibilityResultDoesNotGainRootedAuthority() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(new DocumentId("one"), document("one")),
                    Collections.emptyList(), new DocumentId("one"));
            ClosureProcessResult result = fixture.admit(fixture.admission(graph, 100_000L)).processResult();
            assertTrue(result.commits(), diagnostic(result));
            assertNull(assertRoundTrip(result).rootedProjection());
        }
    }

    @Test void corruptOrOverBoundResultBytesCannotYieldAPartialResult() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            DocumentId id = new DocumentId("one");
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(id, document("one")), Collections.emptyList(), id);
            ClosureProcessResult result = fixture.admit(fixture.admission(graph, 100_000L).withRootedContext(
                    RootedProcessingContext.derive(graph, id, Collections.singletonMap(id, hash('c'))), hash('d'))).processResult();
            byte[] bytes = CODEC.encode(result); byte[] damaged = bytes.clone(); damaged[damaged.length / 2] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> CODEC.decode(damaged, null));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decode(Arrays.copyOf(bytes, bytes.length - 1), null));
            assertThrows(IllegalArgumentException.class, () -> new ClosureProcessResultStorageCodec(bytes.length - 1, 128).encode(result));
            assertThrows(IllegalArgumentException.class, () -> new ClosureProcessResultStorageCodec(bytes.length - 1, 128).decode(bytes, null));
            assertArrayEquals(bytes, CODEC.encode(result));
        }
    }

    @Test void checksummedButMismatchedPrivateRootContextCannotBeRestored() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            DocumentId id = new DocumentId("one");
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(id, document("one")), Collections.emptyList(), id);
            RootedProcessingContext context = RootedProcessingContext.derive(graph, id, Collections.singletonMap(id, hash('c')));
            ClosureProcessResult result = fixture.admit(fixture.admission(graph, 100_000L)
                    .withRootedContext(context, hash('d'))).processResult();
            RootedOwnershipTracker.Snapshot original = result.rootedProjection().storageOwnership();
            AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(16 * 1024 * 1024, 128);
            // Package-only fixture injection models a self-checksummed malformed storage record.
            // No public owner-list or invocation-authority construction API is introduced.
            for (RootedProcessingContext wrong : Arrays.asList(
                    new RootedProcessingContext(context.entryOwners(), context.ownerDescriptor(), hash('a')),
                    new RootedProcessingContext(Arrays.asList(id, id), context.ownerDescriptor(), graph.closureIdentity()))) {
                RootedInvocationBinding binding = new RootedInvocationBinding(wrong, original.binding.deliveryIdentity,
                        original.binding.entryInvocationIdentity, original.binding.birthParents);
                ClosureProcessResult forged = result.withRootedProjection(new RootedOwnershipTracker.Snapshot(binding,
                        original.inputSnapshot, new LinkedHashSet<>(original.owners), original.boundaries,
                        original.checkpointPredecessors, original.checkpointSuccessors));
                byte[] checksummed = CODEC.encode(forged);
                assertThrows(IllegalArgumentException.class, () -> CODEC.decode(checksummed, null));
                try (SnapshotStorageCall call = snapshots.newCall()) {
                    assertThrows(IllegalArgumentException.class, () -> CODEC.decodeInCall(checksummed, call));
                }
                long hits = snapshots.acceptedByteStatistics().hits;
                assertTrue(snapshots.acceptedByteStatistics().retainedEntries > 0,
                        "The individual snapshots are valid even though the enclosing rooted context is not");
                try (SnapshotStorageCall warm = snapshots.newCall()) {
                    assertThrows(IllegalArgumentException.class, () -> CODEC.decodeInCall(checksummed, warm));
                }
                assertTrue(snapshots.acceptedByteStatistics().hits > hits,
                        "Snapshot certificates cannot grant acceptance to the failed outer result");
            }
            assertRoundTrip(result);
        }
    }

    @Test void oneResultCallReusesExactSnapshotsWithoutChangingCanonicalBytesOrGas() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            DocumentId id = new DocumentId("one");
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(id, document("one")), Collections.emptyList(), id);
            ClosureProcessResult original = fixture.admit(fixture.admission(graph, 100_000L).withRootedContext(
                    RootedProcessingContext.derive(graph, id, Collections.singletonMap(id, hash('c'))), hash('d'))).processResult();
            assertTrue(original.commits(), diagnostic(original));
            byte[] bytes = CODEC.encode(original);
            AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(16 * 1024 * 1024, 128);
            int calls = fixture.reactions.size();
            ClosureProcessResult restored;
            int savedVerifications;
            try (SnapshotStorageCall reuse = snapshots.newCall();
                    SnapshotStorageCall disabled = new SnapshotStorageCall(snapshots, 0, 0)) {
                restored = CODEC.decodeInCall(bytes, reuse);
                ClosureProcessResult uncached = CODEC.decodeInCall(bytes, disabled);
                assertTrue(reuse.decodeHits() > 0, "Repeated exact nested input/ownership envelopes reuse a full decode");
                assertTrue(reuse.encodeHits() > 0, "Cross-field comparisons and canonical wrapper reuse exact bytes");
                savedVerifications = disabled.verificationAttempts() - reuse.verificationAttempts();
                assertTrue(savedVerifications > 0, "The control executes the same codec with reuse disabled");
                assertArrayEquals(bytes, CODEC.encodeInCall(uncached, disabled));
                assertArrayEquals(bytes, CODEC.encode(restored));
                assertEquals(original.totalGas(), restored.totalGas());
                assertEquals(original.gasTraceIdentity(), restored.gasTraceIdentity());
                assertEquals(original.managedTransitionReceiptsIdentity(), restored.managedTransitionReceiptsIdentity());
                restored.resultingDocuments().get(0).document().name("caller mutation");
                assertArrayEquals(bytes, CODEC.encode(restored));
                assertTrue(reuse.peakBytes() <= 8 * 1024 * 1024); assertTrue(reuse.peakEntries() <= 32);
            }
            assertTrue(savedVerifications > 0);
            ClosureProcessResult nextCall = CODEC.decode(bytes);
            assertNotSame(restored, nextCall);
            assertNotSame(restored.storageInputSnapshot(), nextCall.storageInputSnapshot());
            assertEquals(calls, fixture.reactions.size(), "Physical reuse neither invokes nor skips processor work");
        }
    }

    @Test void repeatedValidatedSnapshotsCannotSubstituteAWrongDerivedOutput() throws Exception {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            DocumentId id = new DocumentId("one");
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(id, document("one")), Collections.emptyList(), id);
            ClosureProcessResult result = fixture.admit(fixture.admission(graph, 100_000L).withRootedContext(
                    RootedProcessingContext.derive(graph, id, Collections.singletonMap(id, hash('c'))), hash('d'))).processResult();
            assertTrue(result.commits(), diagnostic(result));
            byte[] bytes = CODEC.encode(result);
            byte[] wrongOutput = replaceOutputWithInput(bytes);
            assertFalse(Arrays.equals(bytes, wrongOutput));
            CODEC.decode(bytes); // Prime valid nested snapshots without certifying the altered result.
            assertThrows(IllegalArgumentException.class, () -> CODEC.decode(wrongOutput));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decode(wrongOutput));
            AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(16 * 1024 * 1024, 128);
            try (SnapshotStorageCall call = snapshots.newCall()) {
                for (int attempt = 1; attempt <= 2; attempt++) {
                    assertThrows(IllegalArgumentException.class, () -> CODEC.decodeInCall(wrongOutput, call));
                    assertEquals(attempt, call.resultInputVerificationReuses(),
                            "A proven input cannot certify the mismatched complete result");
                }
            }
            assertArrayEquals(bytes, CODEC.encode(CODEC.decode(bytes)));
        }
    }

    @Test void storageInputProofAvoidsOneColdCheckButKeepsWarmAndDisabledFallbacks() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            DocumentId id = new DocumentId("input-proof");
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(id, document("input-proof")),
                    Collections.emptyList(), id);
            ClosureProcessResult original = fixture.admit(fixture.admission(graph, 100_000L).withRootedContext(
                    RootedProcessingContext.derive(graph, id, Collections.singletonMap(id, hash('c'))), hash('d'))).processResult();
            assertTrue(original.commits(), diagnostic(original));
            byte[] bytes = CODEC.encode(original);
            AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(16 * 1024 * 1024, 128);
            ClosureProcessResult cold;
            try (SnapshotStorageCall call = snapshots.newCall()) {
                cold = CODEC.decodeInCall(bytes, call);
                assertEquals(1, call.resultInputVerificationReuses());
                assertEquals(0, call.resultInputVerificationAttempts());
                assertArrayEquals(bytes, CODEC.encodeInCall(cold, call));
            }
            long hits = snapshots.acceptedByteStatistics().hits;
            try (SnapshotStorageCall warm = snapshots.newCall();
                    SnapshotStorageCall disabled = new SnapshotStorageCall(snapshots, 0, 0)) {
                ClosureProcessResult restored = CODEC.decodeInCall(bytes, warm);
                ClosureProcessResult uncached = CODEC.decodeInCall(bytes, disabled);
                assertTrue(snapshots.acceptedByteStatistics().hits > hits);
                assertEquals(0, warm.resultInputVerificationReuses(), "Accepted bytes are not a current-call object proof");
                assertEquals(1, warm.resultInputVerificationAttempts());
                assertEquals(0, disabled.resultInputVerificationReuses());
                assertEquals(1, disabled.resultInputVerificationAttempts());
                assertEquals(0, disabled.retainedVerifications());
                assertNotSame(cold.storageInputSnapshot(), restored.storageInputSnapshot());
                assertNotSame(restored.storageInputSnapshot(), uncached.storageInputSnapshot());
                restored.storageInputSnapshot().managedDocument(id).document().name("caller change");
                assertArrayEquals(bytes, CODEC.encode(restored));
                assertArrayEquals(bytes, CODEC.encode(uncached));
                assertEquals(original.gasTraceIdentity(), restored.gasTraceIdentity());
                assertEquals(original.managedTransitionReceiptsIdentity(), restored.managedTransitionReceiptsIdentity());
            }
        }
    }

    @Test void storageInputProofRequiresExactLiveIdentityAndNeverAdmitsFailedChecks() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            DocumentId id = new DocumentId("input-proof");
            AffectedClosureSnapshot input = snapshot(Collections.singletonMap(id, document("input-proof")),
                    Collections.emptyList(), id);
            ClosureProcessResult original = fixture.admit(fixture.admission(input, 100_000L)).processResult();
            assertTrue(original.commits(), diagnostic(original));
            byte[] bytes = CODEC.encode(original);
            AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(16 * 1024 * 1024, 128);
            AffectedClosureSnapshot equivalent = snapshots.decode(snapshots.encode(input));
            ManagedDocumentSnapshot before = input.managedDocument(id);
            ManagedDocumentSnapshot wrong = new ManagedDocumentSnapshot(id, before.blueId(),
                    before.document().properties("forged", new blue.language.model.Node().value(1)),
                    before.initialized(), before.terminated(), before.publicRoot(), before.epoch(), before.componentGeneration());
            AffectedClosureSnapshot forged = new AffectedClosureSnapshot(input.closureIdentity(), input.graphGeneration(),
                    Collections.singletonList(wrong), input.occurrences(), input.occurrenceBindingSetIdentity(),
                    input.components(), input.publicRootDocumentIds());
            try (SnapshotStorageCall call = snapshots.newCall()) {
                call.verify(input);
                assertTrue(call.hasVerified(input));
                assertArrayEquals(bytes, CODEC.encode(copyForStorage(original, input, call)));
                assertEquals(1, call.resultInputVerificationReuses());
                assertFalse(call.hasVerified(equivalent));
                assertArrayEquals(bytes, CODEC.encode(copyForStorage(original, equivalent, call)));
                assertEquals(1, call.resultInputVerificationAttempts());
                assertFalse(call.hasVerified(equivalent), "Semantic fallback must not manufacture a full storage proof");
                assertThrows(IllegalArgumentException.class, () -> call.verify(forged));
                assertFalse(call.hasVerified(forged));
                IllegalArgumentException ordinary = assertThrows(IllegalArgumentException.class,
                        () -> copyForStorage(original, forged, null));
                for (int attempt = 0; attempt < 2; attempt++) {
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                            () -> copyForStorage(original, forged, call));
                    assertEquals(ordinary.getMessage(), failure.getMessage());
                    assertFalse(call.hasVerified(forged));
                }
                assertEquals(3, call.resultInputVerificationAttempts());
                assertEquals(1, call.resultInputVerificationReuses());
                assertArrayEquals(bytes, CODEC.encode(copyForStorage(original, input, call)));
            }
            try (SnapshotStorageCall evicted = new SnapshotStorageCall(snapshots, 1024 * 1024, 1)) {
                evicted.verify(input);
                evicted.verify(equivalent);
                assertFalse(evicted.hasVerified(input));
                assertArrayEquals(bytes, CODEC.encode(copyForStorage(original, input, evicted)));
                assertEquals(0, evicted.resultInputVerificationReuses());
                assertEquals(1, evicted.resultInputVerificationAttempts());
                assertEquals(1, evicted.retainedVerifications());
            }
            SnapshotStorageCall closed = snapshots.newCall();
            closed.verify(input);
            closed.close();
            assertEquals(0, closed.retainedVerifications());
            assertThrows(IllegalStateException.class, () -> closed.hasVerified(input));
            assertThrows(IllegalStateException.class, () -> copyForStorage(original, input, closed));
        }
    }

    private static ClosureProcessResult copyForStorage(ClosureProcessResult result,
            AffectedClosureSnapshot input, SnapshotStorageCall call) {
        return new ClosureProcessResult(input, result.status(), result.invocationIdentity(), result.outputClosureIdentity(),
                result.graphGeneration(), result.resultingDocuments(), result.resultingComponents(), result.occurrenceBindings(),
                result.occurrenceBindingSetIdentity(), result.graphChanges(), result.graphChangesIdentity(),
                result.subscriptionDeltas(), result.subscriptionDeltasIdentity(), result.checkpointWrites(),
                result.checkpointWritesIdentity(), result.publicEvents(), result.publicEventsIdentity(), result.totalGas(),
                result.gasTrace(), result.gasTraceIdentity(), result.rejectedCharge(), result.rejectedWorkOccurrence(),
                result.platformCommitCompanion(), result.diagnostic(), null, result.documentTransitionEvidence(),
                result.managedTransitionReceipts(), result.storageReceiptSurfacePresent(), result.storageRejectedCandidate(),
                result.storageResolutions(), call);
    }

    private static byte[] replaceOutputWithInput(byte[] bytes) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 32));
        in.readInt(); ExactNodeStorageCodec.requiredText(in);
        int inputPosition = bytes.length - 32 - in.available();
        byte[] input = ExactNodeStorageCodec.readBytes(in);
        int outputPosition = bytes.length - 32 - in.available();
        byte[] output = ExactNodeStorageCodec.readBytes(in);
        assertFalse(Arrays.equals(input, output), "A successful first admission changes the exact snapshot");
        int remainderPosition = bytes.length - 32 - in.available();
        assertTrue(outputPosition > inputPosition);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(bytes, 0, outputPosition);
        ExactNodeStorageCodec.writeBytes(out, input);
        out.write(bytes, remainderPosition, bytes.length - 32 - remainderPosition);
        byte[] payload = buffer.toByteArray();
        out.write(MessageDigest.getInstance("SHA-256").digest(payload));
        return buffer.toByteArray();
    }

    static ClosureProcessResult assertRoundTrip(ClosureProcessResult original) {
        byte[] bytes = CODEC.encode(original);
        ClosureProcessResult restored = new ClosureProcessResultStorageCodec(16 * 1024 * 1024, 128).decode(bytes, null);
        assertNotSame(original, restored);
        assertNotSame(original.storageInputSnapshot(), restored.storageInputSnapshot());
        assertEquals(original.status(), restored.status());
        assertEquals(original.invocationIdentity(), restored.invocationIdentity());
        assertEquals(original.inputClosureIdentity(), restored.inputClosureIdentity());
        assertEquals(original.outputClosureIdentity(), restored.outputClosureIdentity());
        assertEquals(original.totalGas(), restored.totalGas());
        assertEquals(original.gasTraceIdentity(), restored.gasTraceIdentity());
        assertEquals(original.managedTransitionReceiptsIdentity(), restored.managedTransitionReceiptsIdentity());
        assertEquals(original.storageReceiptSurfacePresent(), restored.storageReceiptSurfacePresent());
        assertArrayEquals(bytes, CODEC.encode(restored));
        return restored;
    }
}
