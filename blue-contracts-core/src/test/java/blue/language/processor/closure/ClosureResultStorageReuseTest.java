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
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Physical reuse controls; the host separately qualifies its weighted retention policy. */
final class ClosureResultStorageReuseTest {
    private static final int BYTES = 16 * 1024 * 1024;
    private static final int DEPTH = 128;
    private static final DocumentId ROOT = new DocumentId("reusable-result");

    @Test void coldOuterCanonicalWriterKeepsVerifiedOutputBytesAfterTheObjectMemoEvictsOutput() throws Exception {
        ClosureProcessResult produced = result(0, false);
        ClosureProcessResultStorageCodec results = codec(null);
        byte[] bytes = results.encode(produced);
        AtomicInteger full = new AtomicInteger();
        AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(BYTES, DEPTH,
                0, 0, full::incrementAndGet);
        try (SnapshotStorageCall call = new SnapshotStorageCall(snapshots, 1, 1)) {
            ClosureProcessResult restored = results.decodeInCall(bytes, call);
            assertEquals(3, full.get(), "Cold input, asserted output and independently derived output each verify once");
            assertFalse(call.hasVerified(restored.storageVerifiedOutput()),
                    "Writing input evicted the derived output before its outer canonical slot");
            assertEquals(1, call.retainedVerifications()); assertEquals(0, call.retainedEntries());
            assertArrayEquals(bytes, results.encode(restored));
            assertEquals(3, full.get());
        }
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(trailing, trailing.length - 32));
        System.arraycopy(checksum, 0, trailing, trailing.length - 32, 32);
        for (int repeat = 0; repeat < 2; repeat++) {
            full.set(0);
            try (SnapshotStorageCall call = new SnapshotStorageCall(snapshots, 1, 1)) {
                assertThrows(IllegalArgumentException.class, () -> results.decodeInCall(trailing, call));
                assertEquals(3, full.get(), "A rejected outer envelope still completed the ordinary derived-output check");
                assertEquals(0, call.retainedEntries());
            }
        }
        assertNull(produced.storageVerifiedOutput());
        full.set(0);
        try (SnapshotStorageCall call = new SnapshotStorageCall(snapshots, 1, 1)) {
            assertArrayEquals(bytes, results.encode(results.decodeInCall(bytes, call)));
            assertEquals(3, full.get(), "Failed enclosing acceptance cannot lend its local output proof to a later call");
        }
    }

    @Test void fullPublicEncoderFallbackRetainsOnlyTheExactDecodedOutputWithoutFrameRetention() {
        for (boolean rooted : new boolean[] {false, true}) {
            ClosureProcessResult produced = result(0, rooted);
            byte[] bytes = codec(null).encode(produced);
            assertNull(produced.storageVerifiedOutput(), "Successful producer encoding grants no decoder ownership");
            for (int capacity : new int[] {-1, 0, 1}) {
                Memo memo = capacity < 0 ? null : new Memo(capacity);
                AtomicInteger full = new AtomicInteger();
                ClosureProcessResultStorageCodec codec = new ClosureProcessResultStorageCodec(BYTES, DEPTH, memo,
                        full::incrementAndGet);
                ClosureProcessResult restored = codec.decode(bytes);
                AffectedClosureSnapshot output = restored.storageVerifiedOutput();
                assertNotNull(output); assertTrue(output.hasVerifiedOwnedState());
                assertFalse(output.hasVerifiedStorageSnapshot(), "The output is independently derived, not the asserted input record");
                if (capacity == 1) codec.decode(codec(null).encode(result(1, rooted)));
                if (memo != null) assertNull(memo.findEncoded(restored, BYTES, DEPTH), "No complete frame can shortcut the writer");
                full.set(0);
                assertArrayEquals(bytes, codec.encode(restored));
                assertArrayEquals(bytes, codec.encode(restored));
                assertEquals(0, full.get(), "Full fallback serialization reuses the exact owned output's pure verification");
                assertSame(output, restored.storageVerifiedOutput());
                restored.resultingDocuments().get(0).document().name("caller result edit");
                output.managedDocuments().get(0).document().name("caller saved output edit");
                assertArrayEquals(bytes, codec.encode(restored));
                assertEquals(0, full.get());
                ClosureProcessResult independent = codec(null).decode(bytes);
                assertNotSame(output, independent.storageVerifiedOutput(), "Independent envelopes do not share derived aliases");
                assertThrows(IllegalArgumentException.class,
                        () -> new ClosureProcessResultStorageCodec(bytes.length - 1, DEPTH).encode(restored));
                assertThrows(IllegalArgumentException.class,
                        () -> new ClosureProcessResultStorageCodec(BYTES, 1).encode(restored));
                full.set(0);
                assertArrayEquals(bytes, codec.encode(produced));
                int first = full.get(); assertTrue(first > 0, "Public/fresh results still take their ordinary pure checks");
                assertArrayEquals(bytes, codec.encode(produced));
                assertEquals(first * 2, full.get());
                assertNull(produced.storageVerifiedOutput());
            }
        }
    }

    @Test void completeDecodeIssuesTheOnlyHandleAndSharesItAcrossCodecOwners() {
        ClosureProcessResult produced = result(0);
        Memo memo = new Memo(4);
        ClosureProcessResultStorageCodec first = codec(memo), second = codec(memo);
        byte[] bytes = first.encode(produced);
        assertEquals(0, memo.loads); assertTrue(memo.frames.isEmpty());
        ClosureProcessResult decoded = first.decode(bytes);
        assertNotSame(produced, decoded);
        assertSame(decoded, second.decode(bytes.clone()));
        assertEquals(1, memo.loads);
        assertArrayEquals(bytes, second.encode(decoded));
        assertArrayEquals(bytes, codec(null).encode(decoded));
        assertEquals(produced.invocationIdentity(), decoded.invocationIdentity());
        assertEquals(produced.gasTraceIdentity(), decoded.gasTraceIdentity());
        assertEquals(produced.managedTransitionReceiptsIdentity(), decoded.managedTransitionReceiptsIdentity());
        assertNull(memo.findEncoded(produced, BYTES, DEPTH), "Equal output is not exact decoded-object provenance");
    }

    @Test void completeFramesReuseAcrossByteBoundsInBothDirectionsIncludingTheExactBoundary() {
        assertCompatibleByteProfileReuse(result(0, false));
        assertCompatibleByteProfileReuse(result(0, true));
    }

    @Test void compatibleByteBoundsPreserveCyclicSnapshotsOwnershipEventsAndTerminalFailure() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = CompositionReactionCycleTest.ring(fixture, "byte-profile-cycle", 3, 2);
            Map<DocumentId, String> histories = new LinkedHashMap<>();
            graph.managedDocuments().forEach(d -> histories.put(d.documentId(), hash('c')));
            RootedProcessingContext context = RootedProcessingContext.derive(graph, new DocumentId("d0"), histories);
            ClosureProcessResult measured = fixture.admit(fixture.admission(graph, 100_000L)
                    .withRootedContext(context, hash('d'))).processResult();
            assertTrue(measured.commits(), diagnostic(measured));
            assertNotNull(measured.rootedProjection());
            assertFalse(measured.publicEvents().isEmpty());
            assertCompatibleByteProfileReuse(measured);
            fixture.reactions.clear(); fixture.initialized.clear();
            ClosureInvocationInput input = fixture.admission(graph, measured.totalGas() - 1L)
                    .withRootedContext(context, hash('d'));
            ClosureProcessResult rejected = fixture.admit(input).processResult();
            rollback(input, rejected);
            assertNotNull(rejected.rejectedCharge());
            assertCompatibleByteProfileReuse(rejected);
        }
    }

    @Test void ownedKeysReturnedBytesAndPublicNodeCopiesCannotMutateRetainedEvidence() {
        Memo memo = new Memo(4);
        ClosureProcessResultStorageCodec codec = codec(memo);
        byte[] original = codec.encode(result(0));
        byte[] caller = original.clone();
        ClosureProcessResult restored = codec.decode(caller);
        caller[caller.length / 2] ^= 1;
        byte[] exposedKey = memo.last.key().bytes(); exposedKey[0] ^= 1;
        byte[] exposedEncoding = codec.encode(restored); exposedEncoding[0] ^= 1;
        restored.storageInputSnapshot().managedDocument(ROOT).document().name("caller edit");
        restored.resultingDocuments().get(0).document().name("caller result edit");
        assertArrayEquals(original, codec.encode(restored));
        assertArrayEquals(original, codec(null).encode(restored));
        assertSame(restored, codec.decode(original)); assertEquals(1, memo.loads);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(caller));
        assertEquals(1, memo.frames.size());
    }

    @Test void malformedAndSelfChecksummedWrongOutputNeverIssueAReusableHandle() throws Exception {
        byte[] valid = codec(null).encode(result(0));
        byte[] damaged = valid.clone(); damaged[damaged.length / 2] ^= 1;
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] wrongOutput = replaceOutputWithInput(valid);
        for (byte[] rejected : new byte[][] {damaged, truncated, trailing, wrongOutput}) {
            Memo memo = new Memo(4); ClosureProcessResultStorageCodec codec = codec(memo);
            for (int pass = 0; pass < 2; pass++) {
                assertThrows(IllegalArgumentException.class, () -> codec.decode(rejected));
                assertTrue(memo.frames.isEmpty()); assertNull(memo.last);
            }
            ClosureProcessResult accepted = codec.decode(valid);
            assertArrayEquals(valid, codec.encode(accepted));
            assertEquals(3, memo.loads); assertEquals(1, memo.frames.size());
        }
    }

    @Test void hostCannotSubstituteAnotherFrameDepthOrEqualResultObject() {
        byte[] first = codec(null).encode(result(0));
        byte[] other = codec(null).encode(result(1));
        assertFalse(Arrays.equals(first, other));
        Memo memo = new Memo(4); ClosureProcessResultStorageCodec normal = codec(memo);
        ClosureProcessResult restored = normal.decode(first);
        ClosureProcessResultStorageCodec.VerifiedFrame issued = memo.last;
        ClosureProcessResultStorageCodec wrong = codec((key, decode) -> issued);
        assertThrows(IllegalArgumentException.class, () -> wrong.decode(other));
        assertThrows(IllegalArgumentException.class, () -> codec((key, decode) -> null).decode(first));
        assertThrows(IllegalArgumentException.class, () ->
                new ClosureProcessResultStorageCodec(BYTES, DEPTH - 1, (key, decode) -> issued).decode(first));
        ClosureProcessResultStorageCodec.Reuse substituted = new ClosureProcessResultStorageCodec.Reuse() {
            @Override public ClosureProcessResultStorageCodec.VerifiedFrame getOrDecode(
                    ClosureProcessResultStorageCodec.FrameKey key,
                    Supplier<ClosureProcessResultStorageCodec.VerifiedFrame> decode) { return issued; }
            @Override public ClosureProcessResultStorageCodec.VerifiedFrame findEncoded(
                    ClosureProcessResult result, int bytes, int depth) { return issued; }
        };
        ClosureProcessResultStorageCodec wrongEncoder = codec(substituted);
        assertArrayEquals(first, wrongEncoder.encode(restored));
        assertThrows(IllegalArgumentException.class, () -> wrongEncoder.encode(codec(null).decode(first)));
        assertThrows(IllegalArgumentException.class, () -> new ClosureProcessResultStorageCodec(
                BYTES + 1024, DEPTH, substituted).decode(other));
        assertThrows(IllegalArgumentException.class, () -> new ClosureProcessResultStorageCodec(
                BYTES, DEPTH - 1, substituted).encode(restored));
        assertEquals(0, ClosureProcessResultStorageCodec.VerifiedFrame.class.getConstructors().length);
        assertTrue(Arrays.stream(ClosureProcessResultStorageCodec.VerifiedFrame.class.getDeclaredConstructors())
                .filter(constructor -> !constructor.isSynthetic()) // Java 8 emits a nested-private access bridge.
                .allMatch(constructor -> java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())),
                "Retention cannot manufacture a verified handle through a public constructor");
    }

    @Test void physicalProfilesRemainEnforcedAndExecutionInjectionRequiresExactCompatibility() {
        Memo memo = new Memo(4); ClosureProcessResultStorageCodec codec = codec(memo);
        byte[] bytes = codec.encode(result(0)); ClosureProcessResult restored = codec.decode(bytes);
        int loads = memo.loads;
        assertThrows(IllegalArgumentException.class, () ->
                new ClosureProcessResultStorageCodec(bytes.length - 1, DEPTH, memo).decode(bytes));
        assertEquals(loads, memo.loads, "Oversized selections fail before consulting retention");
        assertThrows(IllegalArgumentException.class, () ->
                new ClosureProcessResultStorageCodec(bytes.length - 1, DEPTH, memo).encode(restored));
        assertThrows(IllegalArgumentException.class, () ->
                new ClosureProcessResultStorageCodec(BYTES, 1, memo).encode(restored));
        assertThrows(IllegalArgumentException.class, () ->
                new ClosureProcessResultStorageCodec(BYTES, 1, memo).decode(bytes));
        assertEquals(1, memo.frames.size(), "A wider-profile hit cannot seed a failing narrower-profile decode");
        assertThrows(IllegalArgumentException.class, () ->
                new ClosureExecutionEvidenceStorageCodec(BYTES - 1, DEPTH, codec));
        assertThrows(IllegalArgumentException.class, () ->
                new ClosureExecutionEvidenceStorageCodec(BYTES, DEPTH - 1, codec));
        assertThrows(NullPointerException.class, () ->
                new ClosureExecutionEvidenceStorageCodec(BYTES, DEPTH, null));
    }

    @Test void disabledEvictedClearedAndFailedLoadsFallBackWithoutChangingCanonicalBytes() {
        byte[] first = codec(null).encode(result(0)), other = codec(null).encode(result(1));
        Memo disabled = new Memo(0); ClosureProcessResultStorageCodec noRetention = codec(disabled);
        assertNotSame(noRetention.decode(first), noRetention.decode(first));
        assertEquals(2, disabled.loads); assertTrue(disabled.frames.isEmpty());
        assertNotSame(codec(null).decode(first), codec(null).decode(first));
        Memo memo = new Memo(1); ClosureProcessResultStorageCodec codec = codec(memo);
        ClosureProcessResult beforeEviction = codec.decode(first); codec.decode(other);
        assertNull(memo.findEncoded(beforeEviction, BYTES, DEPTH));
        assertArrayEquals(first, codec.encode(beforeEviction));
        assertNotSame(beforeEviction, codec.decode(first)); assertEquals(3, memo.loads);
        memo.frames.clear(); codec.decode(first); assertEquals(4, memo.loads);
        ClosureProcessResultStorageCodec failed = codec((key, decode) -> { throw new IllegalStateException("host unavailable"); });
        assertThrows(IllegalStateException.class, () -> failed.decode(first));
        assertArrayEquals(first, codec.encode(codec.decode(first)));
    }

    /** Real transition evidence with distinct physical receiver identities, not two executed receiver invocations. */
    static void assertDifferentCausesShareResult(ManagedRepresentationCause first, ManagedRepresentationCause second) {
        ClosureExecutionEvidenceStorageCodec cold = new ClosureExecutionEvidenceStorageCodec(BYTES, DEPTH);
        byte[] firstBytes = cold.encodeProcessingCause(first), secondBytes = cold.encodeProcessingCause(second);
        assertFalse(Arrays.equals(firstBytes, secondBytes));
        byte[] nestedResult = codec(null).encode(first.transition().originalResult());
        assertArrayEquals(nestedResult, codec(null).encode(second.transition().originalResult()));
        Memo memo = new Memo(64, true);
        ClosureExecutionEvidenceStorageCodec one = new ClosureExecutionEvidenceStorageCodec(BYTES, DEPTH, codec(memo));
        ClosureExecutionEvidenceStorageCodec two = new ClosureExecutionEvidenceStorageCodec(BYTES + 1024, DEPTH,
                new ClosureProcessResultStorageCodec(BYTES + 1024, DEPTH, memo));
        ManagedRepresentationCause a = (ManagedRepresentationCause) one.decodeProcessingCause(firstBytes);
        int firstLoads = memo.loads;
        ManagedRepresentationCause b = (ManagedRepresentationCause) two.decodeProcessingCause(secondBytes);
        assertNotSame(a, b); assertNotEquals(a.causeIdentity(), b.causeIdentity());
        assertSame(a.transition().originalResult(), b.transition().originalResult());
        assertEquals(firstLoads, memo.loads, "Different outer causes must not reconstruct the same complete result twice");
        assertTrue(firstLoads >= 1);
        assertEquals(1, memo.rawDecodes(nestedResult), "This exact shared result is reconstructed once, including nested paths");
        Memo disabled = new Memo(0);
        ClosureExecutionEvidenceStorageCodec ordinary = new ClosureExecutionEvidenceStorageCodec(BYTES, DEPTH, codec(disabled));
        ordinary.decodeProcessingCause(firstBytes); int firstColdLoads = disabled.loads;
        ordinary.decodeProcessingCause(secondBytes);
        assertEquals(firstColdLoads * 2, disabled.loads);
        assertEquals(2, disabled.rawDecodes(nestedResult), "The cold control reconstructs this same result in each outer cause");
        assertArrayEquals(firstBytes, one.encodeProcessingCause(a));
        assertArrayEquals(secondBytes, two.encodeProcessingCause(b));
        assertNotSame(a.transition().originalInput(), b.transition().originalInput(), "No invocation or demand handle is interned");
        ExactNodeStorageCodec nodes = new ExactNodeStorageCodec(BYTES, DEPTH);
        byte[] invalidOuter = nodes.encodeEnvelope("blue-contracts/execution-storage/processing-cause/1", out -> {
            ClosureResultStorageValues.Writer writer = new ClosureResultStorageValues.Writer(out, nodes,
                    new ExactEventIdentityEvidenceStorageCodec(BYTES, DEPTH), cold);
            writer.fields("ManagedRepresentationCause", "not-an-occurrence-identity", first.transition(),
                    first.targetPositionIdentity(), first.nextRevisionReceiptIdentity(), null);
        });
        for (int pass = 0; pass < 2; pass++)
            assertThrows(IllegalArgumentException.class, () -> one.decodeProcessingCause(invalidOuter),
                    "An accepted nested result cannot validate a malformed enclosing cause");
        assertArrayEquals(firstBytes, one.encodeProcessingCause(one.decodeProcessingCause(firstBytes)));
        assertCompatibleByteProfileReuse(first.transition().originalResult());
    }

    /** Runs on actual successful PROCESS and G-1 terminal results in the certificate parity fixture. */
    static void assertTerminalReuseParity(ClosureProcessResult actual) {
        byte[] bytes = codec(null).encode(actual); Memo memo = new Memo(2);
        ClosureProcessResultStorageCodec configured = codec(memo);
        assertArrayEquals(bytes, configured.encode(actual)); assertTrue(memo.frames.isEmpty());
        ClosureProcessResult restored = configured.decode(bytes);
        assertSame(restored, configured.decode(bytes)); assertEquals(1, memo.loads);
        assertArrayEquals(bytes, configured.encode(restored)); assertArrayEquals(bytes, codec(null).encode(restored));
        assertEquals(actual.status(), restored.status()); assertEquals(actual.totalGas(), restored.totalGas());
        assertEquals(actual.gasTraceIdentity(), restored.gasTraceIdentity());
        assertEquals(actual.publicEventsIdentity(), restored.publicEventsIdentity());
        assertEquals(actual.checkpointWritesIdentity(), restored.checkpointWritesIdentity());
    }

    private static void assertCompatibleByteProfileReuse(ClosureProcessResult actual) {
        byte[] bytes = codec(null).encode(actual);
        assertTrue(bytes.length < BYTES);
        // Independent cold decoders establish that both profiles accept the complete
        // nested/cyclic frame before either retention direction is exercised.
        ClosureProcessResultStorageCodec strict = new ClosureProcessResultStorageCodec(bytes.length, DEPTH);
        ClosureProcessResultStorageCodec loose = codec(null);
        ClosureProcessResult coldStrict = strict.decode(bytes), coldLoose = loose.decode(bytes);
        assertNotSame(coldStrict, coldLoose);
        assertArrayEquals(bytes, strict.encode(coldStrict));
        assertArrayEquals(bytes, loose.encode(coldLoose));
        for (boolean strictFirst : new boolean[] {true, false}) {
            int issuedBound = strictFirst ? bytes.length : BYTES;
            int requestedBound = strictFirst ? BYTES : bytes.length;
            Memo memo = new Memo(4, true);
            ClosureProcessResultStorageCodec issuer = new ClosureProcessResultStorageCodec(issuedBound, DEPTH, memo);
            AtomicInteger full = new AtomicInteger();
            ClosureProcessResultStorageCodec requester = new ClosureProcessResultStorageCodec(requestedBound, DEPTH, memo,
                    full::incrementAndGet);
            ClosureProcessResult restored = issuer.decode(bytes);
            assertSame(restored, requester.decode(bytes.clone()));
            assertEquals(1, memo.loads); assertEquals(1, memo.frames.size());
            assertEquals(issuedBound, memo.last.key().maximumBytes(), "Reuse preserves original issuance provenance");
            assertArrayEquals(bytes, requester.encode(restored));
            assertEquals(0, full.get(), "A matching complete handle avoids repeated snapshot verification");
            assertArrayEquals(bytes, strict.encode(restored));
            assertArrayEquals(bytes, loose.encode(restored));
            assertEquals(actual.status(), restored.status());
            assertEquals(actual.totalGas(), restored.totalGas());
            assertEquals(actual.gasTraceIdentity(), restored.gasTraceIdentity());
            assertEquals(actual.publicEventsIdentity(), restored.publicEventsIdentity());
            assertEquals(actual.checkpointWritesIdentity(), restored.checkpointWritesIdentity());
            ClosureProcessResultStorageCodec below = new ClosureProcessResultStorageCodec(bytes.length - 1, DEPTH, memo);
            assertThrows(IllegalArgumentException.class, () -> below.decode(bytes));
            // Memo intentionally returns the retained exact object even when too large;
            // the codec, not a trusted retention policy, must enforce this encode bound.
            assertThrows(IllegalArgumentException.class, () -> below.encode(restored));
            assertEquals(1, memo.loads); assertEquals(1, memo.frames.size());
        }
    }

    private static ClosureProcessResultStorageCodec codec(ClosureProcessResultStorageCodec.Reuse reuse) {
        return new ClosureProcessResultStorageCodec(BYTES, DEPTH, reuse);
    }

    private static ClosureProcessResult result(long seed) {
        return result(seed, true);
    }

    private static ClosureProcessResult result(long seed, boolean rooted) {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(ROOT,
                    document("reusable-result").properties("seed", new Node().value(seed))), Collections.emptyList(), ROOT);
            ClosureInvocationInput input = fixture.admission(graph, 100_000L);
            if (rooted) input = input.withRootedContext(
                    RootedProcessingContext.derive(graph, ROOT, Collections.singletonMap(ROOT, hash('c'))), hash('d'));
            ClosureProcessResult result = fixture.admit(input).processResult();
            assertTrue(result.commits()); return result;
        }
    }

    private static byte[] replaceOutputWithInput(byte[] result) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(result, 0, result.length - 32));
        in.readInt(); ExactNodeStorageCodec.requiredText(in);
        byte[] input = ExactNodeStorageCodec.readBytes(in);
        int outputPosition = result.length - 32 - in.available();
        byte[] output = ExactNodeStorageCodec.readBytes(in); assertFalse(Arrays.equals(input, output));
        int remainderPosition = result.length - 32 - in.available();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
        out.write(result, 0, outputPosition); ExactNodeStorageCodec.writeBytes(out, input);
        out.write(result, remainderPosition, result.length - 32 - remainderPosition);
        out.write(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())); return buffer.toByteArray();
    }

    private static final class Memo implements ClosureProcessResultStorageCodec.Reuse {
        final int capacity;
        final boolean shareByteProfiles;
        final Map<String, ClosureProcessResultStorageCodec.VerifiedFrame> frames = new LinkedHashMap<>();
        final Map<String, Integer> decodes = new LinkedHashMap<>();
        int loads;
        ClosureProcessResultStorageCodec.VerifiedFrame last;
        Memo(int capacity) { this(capacity, false); }
        Memo(int capacity, boolean shareByteProfiles) {
            this.capacity = capacity; this.shareByteProfiles = shareByteProfiles;
        }
        @Override public ClosureProcessResultStorageCodec.VerifiedFrame getOrDecode(
                ClosureProcessResultStorageCodec.FrameKey key,
                Supplier<ClosureProcessResultStorageCodec.VerifiedFrame> decode) {
            String selection = key.format() + ":" + (shareByteProfiles ? "compatible-bytes" : Integer.toString(key.maximumBytes()))
                    + ":" + key.maximumDepth()
                    + ":" + Base64.getEncoder().encodeToString(key.bytes());
            ClosureProcessResultStorageCodec.VerifiedFrame hit = frames.get(selection);
            if (hit != null) return hit;
            loads++;
            String bytes = Base64.getEncoder().encodeToString(key.bytes());
            decodes.put(bytes, decodes.getOrDefault(bytes, 0) + 1);
            ClosureProcessResultStorageCodec.VerifiedFrame decoded = decode.get(); last = decoded;
            if (capacity > 0) {
                if (frames.size() == capacity) frames.remove(frames.keySet().iterator().next());
                frames.put(selection, decoded);
            }
            return decoded;
        }
        int rawDecodes(byte[] bytes) { return decodes.getOrDefault(Base64.getEncoder().encodeToString(bytes), 0); }
        @Override public ClosureProcessResultStorageCodec.VerifiedFrame findEncoded(ClosureProcessResult result, int bytes, int depth) {
            for (ClosureProcessResultStorageCodec.VerifiedFrame frame : frames.values())
                if (frame.result() == result && (shareByteProfiles || frame.key().maximumBytes() == bytes)
                        && frame.key().maximumDepth() == depth) return frame;
            return null;
        }
    }
}
