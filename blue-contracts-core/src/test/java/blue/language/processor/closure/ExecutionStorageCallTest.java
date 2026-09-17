package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ExactEventIdentityEvidenceStorageCodec;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class ExecutionStorageCallTest {
    private static final int BYTES = 16 * 1024 * 1024;
    private static final DocumentId ROOT = new DocumentId("frame-root");
    private static final ClosureExecutionEvidenceStorageCodec CODEC = new ClosureExecutionEvidenceStorageCodec(BYTES, 128);
    private static final ClosureProcessResultStorageCodec RESULTS = new ClosureProcessResultStorageCodec(BYTES, 128);
    private static final AffectedClosureSnapshotStorageCodec SNAPSHOTS = new AffectedClosureSnapshotStorageCodec(BYTES, 128);
    private static final ExactNodeStorageCodec NODES = new ExactNodeStorageCodec(BYTES, 128);

    @Test void outerCanonicalCheckReusesFullyDecodedFramesWithoutChangingSuccessfulOrRejectedResultBytes() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = graph();
            ClosureInvocationInput first = input(fixture, graph, 100_000L);
            ClosureAttemptResult successful = fixture.admit(first);
            assertTrue(successful.processResult().commits());
            long gas = successful.processResult().totalGas();
            for (long limit : new long[] {gas, gas - 1}) {
                ClosureInvocationInput input = input(fixture, graph, limit);
                ClosureAttemptResult attempt = fixture.admit(input);
                assertTrue(attempt.isComplete());
                assertEquals(limit == gas, attempt.processResult().commits());
                byte[] bytes = CODEC.encodeAttempt(input, null, attempt, null);
                int processed = fixture.initialized.size();
                ClosureExecutionEvidenceStorageCodec.StoredAttempt restored;
                ExecutionStorageCall reuse = new ExecutionStorageCall(BYTES, 32);
                try (ExecutionStorageCall closeReuse = reuse;
                        ExecutionStorageCall disabled = new ExecutionStorageCall(0, 0)) {
                    restored = CODEC.decodeAttemptInCall(bytes, reuse);
                    ClosureExecutionEvidenceStorageCodec.StoredAttempt uncached = CODEC.decodeAttemptInCall(bytes, disabled);
                    assertEquals(1, reuse.snapshotDecodes()); assertEquals(1, reuse.resultDecodes());
                    assertEquals(1, disabled.snapshotDecodes()); assertEquals(1, disabled.resultDecodes());
                    assertEquals(0, reuse.snapshotEncodes()); assertEquals(0, reuse.resultEncodes());
                    assertEquals(1, reuse.snapshotEncodeReuses()); assertEquals(1, reuse.resultEncodeReuses());
                    assertEquals(1, disabled.snapshotEncodes()); assertEquals(1, disabled.resultEncodes());
                    assertEquals(0, disabled.snapshotEncodeReuses()); assertEquals(0, disabled.resultEncodeReuses());
                    assertNotSame(restored.input().snapshot(), restored.attempt().processResult().storageInputSnapshot(),
                            "Separate nested envelopes must not acquire new shared witness aliases");
                    assertNotSame(restored.input().snapshot(), uncached.input().snapshot());
                    assertArrayEquals(bytes, encode(restored));
                    assertArrayEquals(bytes, encode(uncached));
                    assertArrayEquals(RESULTS.encode(attempt.processResult()), RESULTS.encode(restored.attempt().processResult()));
                    assertEquals(attempt.processResult().gasTraceIdentity(), restored.attempt().processResult().gasTraceIdentity());
                    assertEquals(attempt.processResult().managedTransitionReceiptsIdentity(),
                            restored.attempt().processResult().managedTransitionReceiptsIdentity());
                    assertTrue(reuse.peakBytes() <= bytes.length); assertEquals(2, reuse.peakEntries());
                    assertEquals(0, disabled.retainedBytes()); assertEquals(0, disabled.retainedEntries());
                }
                assertEquals(0, reuse.retainedBytes()); assertEquals(0, reuse.retainedEntries());
                assertThrows(IllegalStateException.class, () -> reuse.encodeResult(attempt.processResult(), RESULTS));
                assertNotSame(restored.input().snapshot(), CODEC.decodeAttempt(bytes).input().snapshot());
                restored.input().snapshot().managedDocument(ROOT).document().name("caller mutation");
                assertArrayEquals(bytes, encode(restored));
                assertEquals(processed, fixture.initialized.size(), "Physical restoration never re-executes initialization");
            }
        }
    }

    @Test void boundedOrDisabledMemoFallsBackToOrdinaryEncodingWithoutChangingAcceptance() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput input = input(fixture, graph(), 100_000L);
            ClosureAttemptResult attempt = fixture.admit(input);
            byte[] bytes = CODEC.encodeAttempt(input, null, attempt, null);
            for (int[] bounds : new int[][] {{BYTES, 1}, {1, 32}, {0, 0}}) {
                try (ExecutionStorageCall call = new ExecutionStorageCall(bounds[0], bounds[1])) {
                    ClosureExecutionEvidenceStorageCodec.StoredAttempt restored = CODEC.decodeAttemptInCall(bytes, call);
                    assertArrayEquals(bytes, encode(restored));
                    assertTrue(call.peakBytes() <= bounds[0]); assertTrue(call.peakEntries() <= bounds[1]);
                    assertEquals(1, call.resultEncodes(), "An uncached result takes the unchanged full encoder path");
                    assertEquals(bounds[1] == 1 ? 1 : 0, call.snapshotEncodeReuses());
                    assertEquals(0, call.resultEncodeReuses());
                }
            }
        }
    }

    @Test void separateEqualFramesStaySeparateAndEncodeAloneNeverGrantsADecodeCertificate() {
        AffectedClosureSnapshot original = graph();
        byte[] bytes = SNAPSHOTS.encode(original);
        try (ExecutionStorageCall call = new ExecutionStorageCall(BYTES, 32)) {
            assertArrayEquals(bytes, call.encodeSnapshot(original, SNAPSHOTS));
            assertArrayEquals(bytes, call.encodeSnapshot(original, SNAPSHOTS));
            assertEquals(2, call.snapshotEncodes()); assertEquals(0, call.retainedEntries());
            AffectedClosureSnapshot first = call.decodeSnapshot(bytes.clone(), SNAPSHOTS);
            AffectedClosureSnapshot second = call.decodeSnapshot(bytes.clone(), SNAPSHOTS);
            assertNotSame(first, second, "Only encode bytes are reused; decoding never interns equivalent objects");
            assertEquals(2, call.snapshotDecodes()); assertEquals(2, call.retainedEntries());
            assertArrayEquals(bytes, call.encodeSnapshot(first, SNAPSHOTS));
            assertArrayEquals(bytes, call.encodeSnapshot(second, SNAPSHOTS));
            assertEquals(2, call.snapshotEncodeReuses());
            assertArrayEquals(bytes, call.encodeSnapshot(original, SNAPSHOTS));
            assertEquals(3, call.snapshotEncodes(), "Equal logical identities do not grant object identity reuse");
        }
    }

    @Test void nestedCorruptionAndSelfChecksummedWrongOutputFailBeforeResultMemoAdmission() throws Exception {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput input = input(fixture, graph(), 100_000L);
            ClosureAttemptResult attempt = fixture.admit(input);
            byte[] result = RESULTS.encode(attempt.processResult());
            byte[] damaged = result.clone(); damaged[damaged.length / 2] ^= 1;
            byte[] wrongOutput = replaceOutputWithInput(result);
            CODEC.decodeAttempt(CODEC.encodeAttempt(input, null, attempt, null));
            for (byte[] invalidResult : new byte[][] {damaged, wrongOutput}) {
                byte[] packet = packet(input, invalidResult);
                for (int pass = 0; pass < 2; pass++) {
                    ExecutionStorageCall call = new ExecutionStorageCall(BYTES, 32);
                    try (ExecutionStorageCall closeCall = call) {
                        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeAttemptInCall(packet, call));
                        assertEquals(1, call.snapshotDecodes()); assertEquals(1, call.resultDecodes());
                        assertEquals(1, call.retainedEntries(), "Only the independently valid original input was accepted");
                        assertEquals(0, call.resultEncodeReuses()); assertEquals(0, call.resultEncodes());
                    }
                    assertEquals(0, call.retainedEntries()); assertEquals(0, call.retainedBytes());
                }
            }
            assertArrayEquals(result, RESULTS.encode(CODEC.decodeAttempt(
                    CODEC.encodeAttempt(input, null, attempt, null)).attempt().processResult()));
        }
    }

    @Test void invalidBoundsAndClosedScopesCannotBeUsed() {
        for (int[] bounds : new int[][] {{-1, 1}, {1, -1}, {0, 1}, {1, 0}})
            assertThrows(IllegalArgumentException.class, () -> new ExecutionStorageCall(bounds[0], bounds[1]));
        ExecutionStorageCall call = new ExecutionStorageCall(BYTES, 32);
        call.close();
        assertThrows(IllegalStateException.class, () -> call.decodeSnapshot(SNAPSHOTS.encode(graph()), SNAPSHOTS));
        assertThrows(IllegalStateException.class, () -> call.encodeSnapshot(graph(), SNAPSHOTS));
    }

    private static AffectedClosureSnapshot graph() {
        return snapshot(Collections.singletonMap(ROOT, document("frame-root").properties("seed", new Node().value(0))),
                Collections.emptyList(), ROOT);
    }

    private static ClosureInvocationInput input(CompositionCampaignFixture fixture, AffectedClosureSnapshot graph, long gas) {
        return fixture.admission(graph, gas).withRootedContext(
                RootedProcessingContext.derive(graph, ROOT, Collections.singletonMap(ROOT, hash('c'))), hash('d'));
    }

    private static byte[] encode(ClosureExecutionEvidenceStorageCodec.StoredAttempt attempt) {
        return CODEC.encodeAttempt(attempt.input(), attempt.retry(), attempt.attempt(), attempt.selectedDemand());
    }

    private static byte[] packet(ClosureInvocationInput input, byte[] result) {
        return NODES.encodeEnvelope("blue-contracts/execution-storage/attempt/1", out -> {
            ClosureResultStorageValues.Writer w = new ClosureResultStorageValues.Writer(out, NODES,
                    new ExactEventIdentityEvidenceStorageCodec(BYTES, 128), CODEC);
            w.fields("StoredAttempt", input, null);
            w.fields("ClosureAttemptResult", true);
            ExactNodeStorageCodec.writeText(out, "ClosureProcessResult");
            ExactNodeStorageCodec.writeBytes(out, result);
            w.w(Collections.emptyList()); w.w(-1);
        });
    }

    private static byte[] replaceOutputWithInput(byte[] result) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(result, 0, result.length - 32));
        in.readInt(); ExactNodeStorageCodec.requiredText(in);
        byte[] input = ExactNodeStorageCodec.readBytes(in);
        int outputPosition = result.length - 32 - in.available();
        byte[] output = ExactNodeStorageCodec.readBytes(in);
        assertFalse(Arrays.equals(input, output));
        int remainderPosition = result.length - 32 - in.available();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(result, 0, outputPosition);
        ExactNodeStorageCodec.writeBytes(out, input);
        out.write(result, remainderPosition, result.length - 32 - remainderPosition);
        out.write(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        return buffer.toByteArray();
    }
}
