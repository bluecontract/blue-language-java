package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ManagedDocumentStepRuntime;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Complete owned decode certificates only; neither public objects nor derived views inherit them. */
final class DecodedSnapshotVerificationCertificateTest {
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final DocumentId A = new DocumentId("A"), B = new DocumentId("B"), C = new DocumentId("C");

    @Test void wideOwnedDecodeVerifiesEachRecordOnceAcrossColdCanonicalEncodingAndWarmParsing() {
        AffectedClosureSnapshot original = wideWitnesses(42);
        byte[] bytes = codec().encode(original);
        AtomicInteger full = new AtomicInteger();
        AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(LIMIT, 128,
                LIMIT, 32, full::incrementAndGet);
        AffectedClosureSnapshot cold;
        try (SnapshotStorageCall call = codec.newCall()) {
            cold = codec.decodeEnvelopeInCall(bytes, call, null).snapshot;
            assertEquals(43, full.get(), "Every owned record takes one cold pure check, not another after the 32-entry memo evicts it");
            assertEquals(43, call.verificationAttempts());
            assertEquals(32, call.retainedVerifications(), "Generic call reuse remains bounded; it is not enlarged for this decode");
            assertEquals(0, call.retainedEntries());
        }
        assertEquals(42, cold.rootedWitnesses().storedOriginals().size());
        assertTrue(cold.hasVerifiedStorageSnapshot());
        cold.rootedWitnesses().storedOriginals().values().forEach(child -> assertTrue(child.hasVerifiedStorageSnapshot()));
        assertArrayEquals(bytes, codec.encode(cold));
        full.set(0);
        AffectedClosureSnapshot warm = codec.decode(bytes);
        assertEquals(0, full.get(), "Accepted private bytes require no new codec pure checks");
        assertNotSame(cold, warm);
        for (DocumentId source : cold.rootedWitnesses().sources())
            assertNotSame(cold.rootedWitnesses().storedOriginals().get(source), warm.rootedWitnesses().storedOriginals().get(source));
        assertArrayEquals(bytes, codec.encode(warm));
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 1).decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshotStorageCodec(bytes.length - 1, 128).decode(bytes));
        assertFalse(original.hasVerifiedStorageSnapshot());
    }

    @Test void malformedParentAfterFortyTwoVerifiedChildrenCannotPublishAnyEnvelopeProof() throws Exception {
        AffectedClosureSnapshot original = wideWitnesses(42);
        byte[] bytes = codec().encode(original), wrongIdentity = bytes.clone();
        DataInputStream in = payload(wrongIdentity);
        in.readInt(); ExactNodeStorageCodec.requiredText(in); in.readByte();
        int identityPosition = wrongIdentity.length - 32 - in.available();
        int hexUnit = identityPosition + 4 + 2 * 7 + 1;
        wrongIdentity[hexUnit] = wrongIdentity[hexUnit] == 'a' ? (byte) 'b' : (byte) 'a'; seal(wrongIdentity);
        AtomicInteger full = new AtomicInteger();
        AffectedClosureSnapshotStorageCodec codec = new AffectedClosureSnapshotStorageCodec(LIMIT, 128,
                LIMIT, 32, full::incrementAndGet);
        for (int repeat = 0; repeat < 2; repeat++) {
            full.set(0);
            try (SnapshotStorageCall call = codec.newCall()) {
                assertThrows(IllegalArgumentException.class, () -> codec.decodeEnvelopeInCall(wrongIdentity, call, null));
                assertEquals(43, full.get(), "Parent rejection follows the independently verified child records");
                assertEquals(0, call.retainedEntries());
            }
            assertEquals(0, codec.acceptedByteStatistics().retainedEntries);
        }
        full.set(0);
        AffectedClosureSnapshot accepted = codec.decode(bytes);
        assertEquals(43, full.get(), "Failed envelope-local proof cannot seed the later valid decode");
        assertArrayEquals(bytes, codec.encode(accepted));
        assertFalse(original.hasVerifiedStorageSnapshot());
        original.rootedWitnesses().storedOriginals().values().forEach(child -> assertFalse(child.hasVerifiedStorageSnapshot()));
    }

    @Test void coldCompleteRootCertifiesOnlyExactRecipientAndOwnsBodiesAndCyclicProofs() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot original = CompositionReactionCycleTest.ring(fixture, "certificate-cycle", 3, 1);
            AffectedClosureSnapshotStorageCodec codec = codec();
            byte[] bytes = codec.encode(original), supplied = bytes.clone();
            assertFalse(original.hasVerifiedStorageSnapshot(), "Encoding public input must not certify that input");
            AffectedClosureSnapshotStorageCodec.DecodedSnapshot certificate;
            try (SnapshotStorageCall call = codec.newCall()) {
                certificate = codec.decodeEnvelopeInCall(supplied, call, null);
            }
            AffectedClosureSnapshot restored = certificate.snapshot;
            assertTrue(certificate.certifies(restored));
            assertTrue(restored.hasVerifiedStorageSnapshot());
            assertNotSame(original, restored);
            AffectedClosureSnapshot independentlyDecoded = codec().decode(bytes);
            assertEquals(original.closureIdentity(), restored.closureIdentity());
            assertEquals(independentlyDecoded.closureIdentity(), restored.closureIdentity());
            assertFalse(certificate.certifies(original));
            assertFalse(certificate.certifies(independentlyDecoded));
            assertThrows(IllegalArgumentException.class, () -> original.acceptStorageVerification(certificate));
            assertThrows(IllegalArgumentException.class, () -> independentlyDecoded.acceptStorageVerification(certificate));
            assertFalse(original.hasVerifiedStorageSnapshot());
            assertTrue(independentlyDecoded.hasVerifiedStorageSnapshot(), "A foreign certificate cannot erase its own valid one");
            AtomicInteger full = new AtomicInteger();
            ClosureEvidenceVerifier.verifySnapshot(restored, full::incrementAndGet);
            ClosureEvidenceVerifier.verifySnapshot(restored, full::incrementAndGet);
            assertEquals(0, full.get(), "The completed cold root skips repeated pure structural verification after call close");
            Arrays.fill(supplied, (byte) 0);
            restored.managedDocuments().get(0).document().name("caller body mutation");
            restored.components().stream().filter(component -> component.kind() == ComponentKind.CYCLIC)
                    .findFirst().orElseThrow(() -> new AssertionError("Expected cyclic component"))
                    .completeCyclicProof().declaredPlaceholderSet().get(0)
                    .name("caller proof mutation");
            byte[] returned = codec.encode(restored); Arrays.fill(returned, (byte) 0);
            assertArrayEquals(bytes, codec.encode(restored));
        }
    }

    @Test void completeWitnessDecodeCertifiesOwnedSnapshotsButNewRoleViewsStartUncertified() {
        AffectedClosureSnapshot selected = witnesses();
        AffectedClosureSnapshotStorageCodec codec = codec();
        AffectedClosureSnapshot restored = codec.decode(codec.encode(selected));
        assertTrue(restored.hasVerifiedStorageSnapshot());
        Map<DocumentId, AffectedClosureSnapshot> sources = restored.rootedWitnesses().storedOriginals();
        assertSame(sources.get(B), sources.get(C));
        assertTrue(sources.get(B).hasVerifiedStorageSnapshot(), "Every actual decoded record belongs to the accepted envelope");
        AtomicInteger full = new AtomicInteger();
        ClosureEvidenceVerifier.verifySnapshot(restored, full::incrementAndGet);
        ClosureEvidenceVerifier.verifySnapshot(sources.get(B), full::incrementAndGet);
        ClosureEvidenceVerifier.verifySnapshot(sources.get(C), full::incrementAndGet);
        assertEquals(0, full.get());
        AffectedClosureSnapshot derived = new AffectedClosureSnapshot(restored.closureIdentity(), restored.graphGeneration(),
                restored.managedDocuments(), restored.occurrences(), restored.occurrenceBindingSetIdentity(),
                restored.components(), restored.publicRootDocumentIds(), restored.rootedWitnesses());
        assertEquals(restored.closureIdentity(), derived.closureIdentity());
        assertFalse(derived.hasVerifiedStorageSnapshot());
        ClosureEvidenceVerifier.verifySnapshot(derived, full::incrementAndGet);
        assertEquals(1, full.get());
        assertFalse(derived.hasVerifiedStorageSnapshot(), "Ordinary successful verification cannot issue codec provenance");
        assertThrows(UnsupportedOperationException.class, sources::clear);
    }

    @Test void acceptedWarmBytesCertifyOnlyFreshOwnedDecodedInstances() {
        AffectedClosureSnapshotStorageCodec codec = codec();
        AffectedClosureSnapshot original = witnesses();
        byte[] bytes = codec.encode(original), supplied = bytes.clone();
        AffectedClosureSnapshot cold = codec.decode(bytes);
        AffectedClosureSnapshotStorageCodec.DecodedSnapshot warmCertificate;
        try (SnapshotStorageCall call = codec.newCall()) {
            warmCertificate = codec.decodeEnvelopeInCall(supplied, call, () -> Arrays.fill(supplied, (byte) 0));
            assertEquals(0, call.verificationAttempts());
        }
        AffectedClosureSnapshot warm = warmCertificate.snapshot;
        assertNotSame(cold, warm);
        assertTrue(cold.hasVerifiedStorageSnapshot());
        assertTrue(warm.hasVerifiedStorageSnapshot());
        assertTrue(warmCertificate.certifies(warm));
        assertFalse(warmCertificate.certifies(cold));
        assertFalse(warmCertificate.certifies(original));
        assertThrows(IllegalArgumentException.class, () -> cold.acceptStorageVerification(warmCertificate));
        assertThrows(IllegalArgumentException.class, () -> original.acceptStorageVerification(warmCertificate));
        assertFalse(original.hasVerifiedStorageSnapshot());
        AffectedClosureSnapshot warmChild = warm.rootedWitnesses().storedOriginals().get(B);
        assertSame(warmChild, warm.rootedWitnesses().storedOriginals().get(C));
        assertNotSame(cold.rootedWitnesses().storedOriginals().get(B), warmChild);
        assertTrue(warmChild.hasVerifiedStorageSnapshot());
        assertTrue(warmCertificate.certifies(warmChild));
        assertFalse(warmCertificate.certifies(cold.rootedWitnesses().storedOriginals().get(B)));
        AtomicInteger full = new AtomicInteger();
        ClosureEvidenceVerifier.verifySnapshot(warm, full::incrementAndGet);
        ClosureEvidenceVerifier.verifySnapshot(warm, full::incrementAndGet);
        ClosureEvidenceVerifier.verifySnapshot(warmChild, full::incrementAndGet);
        ClosureEvidenceVerifier.verifySnapshot(warmChild, full::incrementAndGet);
        assertEquals(0, full.get(), "Private accepted exact bytes prove every freshly parsed owned snapshot");
        warm.managedDocuments().get(0).document().name("caller warm-body mutation");
        warmChild.managedDocuments().get(0).document().name("caller warm-witness mutation");
        byte[] returned = codec.encode(warm); Arrays.fill(returned, (byte) 0);
        assertArrayEquals(bytes, codec.encode(warm));
        assertArrayEquals(bytes, codec.encode(cold));
        assertTrue(warm.hasVerifiedStorageSnapshot());
        assertEquals(1, codec.acceptedByteStatistics().hits);
        assertEquals(1, codec.acceptedByteStatistics().fullDecodeAttempts);
    }

    @Test void completeNestedDagRetainsSharedAndDistinctProofsAndStandaloneBounds() {
        AffectedClosureSnapshot nested = witnesses();
        AffectedClosureSnapshot original = nested.rootedWitnesses().storedOriginals().get(B);
        Map<DocumentId, AffectedClosureSnapshot> shared = new LinkedHashMap<>();
        shared.put(B, nested); shared.put(C, original);
        Map<DocumentId, AffectedClosureSnapshot> distinct = new LinkedHashMap<>(shared);
        distinct.put(C, codec().decode(codec().encode(original)));
        byte[] sharedBytes = codec().encode(withWitnesses(nested, shared));
        byte[] distinctBytes = codec().encode(withWitnesses(nested, distinct));
        assertFalse(Arrays.equals(sharedBytes, distinctBytes), "Equal values retain different exact witness alias topology");
        AffectedClosureSnapshotStorageCodec codec = codec();
        for (byte[] bytes : Arrays.asList(sharedBytes, distinctBytes)) {
            AffectedClosureSnapshot cold = codec.decode(bytes), warm = codec.decode(bytes);
            assertNotSame(cold, warm);
            for (AffectedClosureSnapshot root : Arrays.asList(cold, warm)) {
                AffectedClosureSnapshot child = root.rootedWitnesses().storedOriginals().get(B);
                AffectedClosureSnapshot grandchild = child.rootedWitnesses().storedOriginals().get(B);
                AffectedClosureSnapshot peer = root.rootedWitnesses().storedOriginals().get(C);
                assertSame(grandchild, child.rootedWitnesses().storedOriginals().get(C));
                if (bytes == sharedBytes) assertSame(grandchild, peer);
                else assertNotSame(grandchild, peer);
                AtomicInteger full = new AtomicInteger();
                for (AffectedClosureSnapshot value : Arrays.asList(root, child, grandchild, peer)) {
                    assertTrue(value.hasVerifiedStorageSnapshot());
                    ClosureEvidenceVerifier.verifySnapshot(value, full::incrementAndGet);
                }
                assertEquals(0, full.get());
                grandchild.managedDocuments().get(0).document().name("caller grandchild mutation");
                assertArrayEquals(bytes, codec.encode(root));
                byte[] standalone = codec.encode(child);
                assertArrayEquals(standalone, codec.encode(codec.decode(standalone)));
                assertThrows(IllegalArgumentException.class,
                        () -> new AffectedClosureSnapshotStorageCodec(standalone.length - 1, 128).encode(child));
                assertThrows(IllegalArgumentException.class,
                        () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 1).encode(child));
            }
            assertNotSame(cold.rootedWitnesses().storedOriginals().get(B), warm.rootedWitnesses().storedOriginals().get(B));
        }
        assertFalse(nested.hasVerifiedStorageSnapshot(), "Encoding does not certify the supplied role projection");
        assertFalse(original.hasVerifiedStorageSnapshot());
    }

    @Test void publicNodeCloneAliasNeverEarnsCertificateAndMutationStillFailsEveryVerification() {
        AffectedClosureSnapshot original = simple("public clone alias");
        ManagedDocumentSnapshot before = original.managedDocument(A);
        Node callerOwned = before.document();
        Node aliasesCaller = new Node() {
            @Override public Node clone() { return callerOwned; }
        };
        ManagedDocumentSnapshot aliasedDocument = new ManagedDocumentSnapshot(A, before.blueId(), aliasesCaller,
                before.initialized(), before.terminated(), before.publicRoot(), before.epoch(), before.componentGeneration());
        AffectedClosureSnapshot aliased = new AffectedClosureSnapshot(original.closureIdentity(), original.graphGeneration(),
                Collections.singletonList(aliasedDocument), original.occurrences(), original.occurrenceBindingSetIdentity(),
                original.components(), original.publicRootDocumentIds());
        AtomicInteger full = new AtomicInteger();
        ClosureEvidenceVerifier.verifySnapshot(aliased, full::incrementAndGet);
        ClosureEvidenceVerifier.verifySnapshot(aliased, full::incrementAndGet);
        assertEquals(2, full.get());
        assertFalse(aliased.hasVerifiedStorageSnapshot());
        callerOwned.properties("injected", new Node().value(1));
        assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceVerifier.verifySnapshot(aliased, full::incrementAndGet));
        assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceVerifier.verifySnapshot(aliased, full::incrementAndGet));
        assertEquals(4, full.get());
        assertFalse(aliased.hasVerifiedStorageSnapshot());
    }

    @Test void disabledReuseStillCertifiesCompleteColdRootsWithoutBypassingPhysicalBounds() {
        byte[] bytes = codec().encode(simple("disabled retention"));
        AffectedClosureSnapshotStorageCodec noByteRetention = new AffectedClosureSnapshotStorageCodec(LIMIT, 128, 0, 0);
        AffectedClosureSnapshot first = noByteRetention.decode(bytes), second = noByteRetention.decode(bytes);
        assertNotSame(first, second);
        assertTrue(first.hasVerifiedStorageSnapshot()); assertTrue(second.hasVerifiedStorageSnapshot());
        assertEquals(2, noByteRetention.acceptedByteStatistics().fullDecodeAttempts);
        assertEquals(0, noByteRetention.acceptedByteStatistics().retainedEntries);
        AffectedClosureSnapshotStorageCodec codec = codec();
        codec.decode(bytes);
        try (SnapshotStorageCall disabled = new SnapshotStorageCall(codec, 0, 0)) {
            AffectedClosureSnapshotStorageCodec.DecodedSnapshot certificate = codec.decodeEnvelopeInCall(bytes, disabled, null);
            assertTrue(certificate.certifies(certificate.snapshot));
            assertTrue(certificate.snapshot.hasVerifiedStorageSnapshot());
            assertEquals(0, disabled.retainedVerifications());
        }
        assertEquals(2, codec.acceptedByteStatistics().fullDecodeAttempts);
        assertEquals(0, codec.acceptedByteStatistics().hits);
        assertThrows(IllegalArgumentException.class,
                () -> new AffectedClosureSnapshotStorageCodec(bytes.length - 1, 128).decode(bytes));
        assertThrows(IllegalArgumentException.class,
                () -> new AffectedClosureSnapshotStorageCodec(bytes.length - 1, 128).encode(first));
        assertThrows(IllegalArgumentException.class,
                () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 1).decode(bytes));
        assertThrows(IllegalArgumentException.class,
                () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 1).encode(first));
    }

    @Test void damagedAndNoncanonicalCompleteEnvelopesCannotReturnCertificatesOrPublishAcceptance() throws Exception {
        AffectedClosureSnapshot selected = witnesses();
        AffectedClosureSnapshotStorageCodec codec = codec();
        byte[] bytes = codec.encode(selected);
        byte[] corrupt = bytes.clone(); corrupt[corrupt.length - 1] ^= 1;
        byte[] wrongIdentity = bytes.clone();
        DataInputStream in = payload(wrongIdentity);
        in.readInt(); ExactNodeStorageCodec.requiredText(in); in.readByte();
        int identityPosition = wrongIdentity.length - 32 - in.available();
        int hexUnit = identityPosition + 4 + 2 * 7 + 1;
        wrongIdentity[hexUnit] = wrongIdentity[hexUnit] == 'a' ? (byte) 'b' : (byte) 'a'; seal(wrongIdentity);
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1); seal(trailing);
        byte[] unsorted = bytes.clone();
        byte[] original = codec.encode(selected.rootedWitnesses().storedOriginals().get(B));
        int originalPayload = original.length - 32 - (8 + 2 * AffectedClosureSnapshotStorageCodec.FORMAT.length());
        int firstSource = bytes.length - 32 - 11 - originalPayload - 1;
        int secondSource = bytes.length - 32 - 6;
        assertEquals((byte) 'B', unsorted[firstSource]); assertEquals((byte) 'C', unsorted[secondSource]);
        unsorted[firstSource] = 'C'; unsorted[secondSource] = 'B'; seal(unsorted);
        for (byte[] damaged : Arrays.asList(corrupt, wrongIdentity, trailing, unsorted)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (SnapshotStorageCall call = codec.newCall()) {
                    assertThrows(IllegalArgumentException.class, () -> codec.decodeEnvelopeInCall(damaged, call, null));
                    assertEquals(0, call.retainedEntries());
                }
                assertEquals(0, codec.acceptedByteStatistics().retainedEntries);
            }
        }
        assertEquals(8, codec.acceptedByteStatistics().fullDecodeAttempts);
        assertFalse(selected.hasVerifiedStorageSnapshot());
        assertFalse(selected.rootedWitnesses().storedOriginals().get(B).hasVerifiedStorageSnapshot());
        assertTrue(codec.decode(bytes).hasVerifiedStorageSnapshot());
        assertEquals(1, codec.acceptedByteStatistics().retainedEntries);
    }

    @Test void certifiedRootStillChecksContextOwnerBasisAndProfileAndPreservesInputPolicy() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true);
                CompositionCampaignFixture nonrooted = new CompositionCampaignFixture(false)) {
            AffectedClosureSnapshotStorageCodec codec = codec();
            AffectedClosureSnapshot restored = codec.decode(codec.encode(simple("rooted recipient")));
            RootedProcessingContext context = RootedProcessingContext.derive(restored, A, Collections.singletonMap(A, hash('a')));
            assertThrows(IllegalArgumentException.class,
                    () -> RootedProcessingContext.derive(restored, B, Collections.singletonMap(A, hash('a'))));
            assertThrows(IllegalArgumentException.class,
                    () -> RootedProcessingContext.derive(restored, A, Collections.emptyMap()));
            ClosureInvocationInput base = fixture.admission(restored, 100_000L);
            ClosureInvocationInput rooted = base.withRootedContext(context, hash('d'));
            assertSame(restored, rooted.snapshot(), "No witnesses means the exact certified snapshot survives binding");
            assertSame(base.executionPolicy(), rooted.executionPolicy());
            assertSame(base.cause(), rooted.cause());
            assertSame(base.environment(), rooted.environment());
            assertThrows(IllegalArgumentException.class, () -> rooted.withRootedContext(context, hash('e')));
            assertThrows(IllegalArgumentException.class,
                    () -> nonrooted.admission(restored, 100_000L).withRootedContext(context, hash('d')));
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.admission(simple("different entry"), 100_000L).withRootedContext(context, hash('d')));
        }
    }

    @Test void actualRootedProcessMatchesOrdinaryExecutionAtExactGasAndOneBelow() {
        Node channelType = new Node().name("Decoded snapshot parity channel");
        Node handlerType = new Node().name("Decoded snapshot parity handler");
        Map<String, Node> exact = new LinkedHashMap<>();
        AtomicInteger executions = new AtomicInteger();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .registerContractProcessor(id(channelType), channelType, new CertificateChannelProcessor())
                .registerContractProcessor(id(handlerType), handlerType, new HandlerProcessor<CertificateHandler>() {
                    public Class<CertificateHandler> contractType() { return CertificateHandler.class; }
                    public void execute(CertificateHandler handler, ProcessorExecutionContext context) {
                        executions.incrementAndGet();
                        long before = ((Number) context.documentAt("/count").getValue()).longValue();
                        context.applyPatch(JsonPatch.replace("/count", new Node().value(before + 1L)));
                        context.emitEvent(new Node().properties("ordinal", new Node().value(0L)));
                        context.emitEvent(new Node().properties("ordinal", new Node().value(1L)));
                    }
                }).nodeProvider(key -> exact.containsKey(key)
                        ? Collections.singletonList(exact.get(key).clone()) : Collections.emptyList()).build()) {
            ClosureEnvironment environment = ClosureEvidenceFactory.environment(owner, hash('a'),
                    RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY, "certificate-lineage", "certificate-binding",
                    "certificate-provider", "certificate-order", "certificate-limits", GasSchedule.contracts10().portableLimits());
            Node body = new Node().name("certified PROCESS root").properties("count", new Node().value(0L))
                    .contracts(new Node().properties("source", typed(id(channelType)))
                            .properties("react", typed(id(handlerType)).properties("channel", new Node().value("source"))));
            exact.put(id(body), body.clone());
            body = initialized(body);
            exact.put(id(body), body.clone());
            AffectedClosureSnapshot raw = snapshot(Collections.singletonMap(A, body), Collections.emptyList(), A);
            ManagedDocumentSnapshot before = raw.managedDocument(A);
            AffectedClosureSnapshot ordinary = ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(),
                    Collections.singletonList(new ManagedDocumentSnapshot(A, before.blueId(), before.document(),
                            true, false, true, 1L, before.componentGeneration())),
                    raw.occurrences(), raw.components(), raw.publicRootDocumentIds());
            AffectedClosureSnapshotStorageCodec snapshots = codec();
            byte[] snapshotBytes = snapshots.encode(ordinary);
            AffectedClosureSnapshot cold = snapshots.decode(snapshotBytes), warm = snapshots.decode(snapshotBytes);
            assertNotSame(cold, warm);
            assertFalse(ordinary.hasVerifiedStorageSnapshot());
            assertTrue(cold.hasVerifiedStorageSnapshot()); assertTrue(warm.hasVerifiedStorageSnapshot());
            assertEquals(1, snapshots.acceptedByteStatistics().hits);
            Node event = new Node().name("process certificate parity").properties("subscriptionKey", new Node().value("update"));
            exact.put(id(event), event.clone());
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, id(event),
                    ExternalOrderKey.of(Arrays.<Object>asList(1L, "certificate-process")), environment.externalOrderPolicyIdentity());
            ClosureProcessResult measured = process(owner, processInput(owner, environment, ordinary, cause, 100_000L));
            assertTrue(measured.commits(), diagnostic(measured));
            assertEquals(1, executions.get()); assertTrue(measured.totalGas() > 0L);
            ClosureProcessResultStorageCodec results = new ClosureProcessResultStorageCodec(16 * 1024 * 1024, 128);
            for (long budget : new long[] {measured.totalGas(), measured.totalGas() - 1L}) {
                executions.set(0);
                ClosureInvocationInput ordinaryInput = processInput(owner, environment, ordinary, cause, budget);
                ClosureProcessResult expected = process(owner, ordinaryInput);
                int ordinaryCalls = executions.get();
                for (AffectedClosureSnapshot certified : Arrays.asList(cold, warm)) {
                    executions.set(0);
                    ClosureInvocationInput certifiedInput = processInput(owner, environment, certified, cause, budget);
                    assertSame(certified, certifiedInput.snapshot(), "PROCESS binding preserves this no-witness certified root");
                    assertEquals(ClosureInvocationInput.Operation.PROCESS_CLOSURE, certifiedInput.operation());
                    ClosureProcessResult actual = process(owner, certifiedInput);
                    assertEquals(ordinaryCalls, executions.get(), "Verification reuse cannot skip or add handler execution");
                    assertEquals(expected.status(), actual.status()); assertEquals(expected.totalGas(), actual.totalGas());
                    assertEquals(expected.gasTrace().stream().map(GasTraceEntry::identityValue).collect(Collectors.toList()),
                            actual.gasTrace().stream().map(GasTraceEntry::identityValue).collect(Collectors.toList()));
                    assertEquals(expected.gasTrace().stream().map(GasTraceEntry::reason).collect(Collectors.toList()),
                            actual.gasTrace().stream().map(GasTraceEntry::reason).collect(Collectors.toList()));
                    assertEquals(expected.publicEvents().stream().map(PublicEventOccurrence::identityValue).collect(Collectors.toList()),
                            actual.publicEvents().stream().map(PublicEventOccurrence::identityValue).collect(Collectors.toList()));
                    assertEquals(expected.checkpointWrites().stream().map(CheckpointWrite::identityValue).collect(Collectors.toList()),
                            actual.checkpointWrites().stream().map(CheckpointWrite::identityValue).collect(Collectors.toList()));
                    assertArrayEquals(results.encode(expected), results.encode(actual), "Complete ordered result transport is unchanged");
                    ClosureResultStorageReuseTest.assertTerminalReuseParity(actual);
                    if (budget == measured.totalGas()) {
                        assertTrue(actual.commits(), diagnostic(actual)); assertEquals(budget, actual.totalGas());
                        assertEquals(1, ordinaryCalls); assertEquals(2, actual.publicEvents().size());
                        assertFalse(actual.checkpointWrites().isEmpty()); assertNotNull(actual.rootedProjection());
                    } else {
                        assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, actual.status(), diagnostic(actual));
                        assertNotNull(expected.rejectedCharge()); assertNotNull(actual.rejectedCharge());
                        assertEquals(expected.rejectedCharge().rejectedChargeIdentity(), actual.rejectedCharge().rejectedChargeIdentity());
                        rollback(ordinaryInput, expected); rollback(certifiedInput, actual);
                    }
                }
            }
            assertFalse(ordinary.hasVerifiedStorageSnapshot());
            assertTrue(cold.hasVerifiedStorageSnapshot()); assertTrue(warm.hasVerifiedStorageSnapshot());
        }
    }

    private static ClosureInvocationInput processInput(DocumentProcessor owner, ClosureEnvironment environment,
            AffectedClosureSnapshot snapshot, ExternalEventCause cause, long gas) {
        RootedProcessingContext context = RootedProcessingContext.derive(snapshot, A, Collections.singletonMap(A, hash('c')));
        String delivery;
        try (ManagedDocumentStepRuntime step = new ManagedDocumentStepRuntime(owner)) {
            blue.language.processor.ManagedRootChannelOccurrence selected = step.projectRootSubscriptionSurface(
                    snapshot.managedDocument(A).document()).channelOccurrences().stream()
                    .filter(channel -> "source".equals(channel.rawChannelKey())).findFirst()
                    .orElseThrow(() -> new AssertionError("Expected source channel"));
            ChannelOccurrence receiving = ChannelOccurrence.root(A, selected.rawChannelKey(),
                    selected.effectiveRuntimeContributionBlueId(), selected.subscriptionHeaderBlueId());
            delivery = context.deliveryBasisIdentity(cause.causeIdentity(), "LIVE", Collections.singletonList(receiving), null);
        }
        return ClosureEvidenceFactory.processClosure(snapshot, cause,
                Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "update", 0L)),
                ClosureEvidenceFactory.executionPolicy(gas, Collections.emptyMap(), "certificate-process-gas"), environment)
                .withRootedContext(context, delivery);
    }
    private static ClosureProcessResult process(DocumentProcessor owner, ClosureInvocationInput input) {
        try (BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureAttemptResult attempt = contracts.processClosure(input);
            assertTrue(attempt.isComplete(), () -> "PROCESS parity fixture suspended: " + attempt.resourceDemands());
            return attempt.processResult();
        }
    }
    public static final class CertificateHandler extends HandlerContract { }
    public static final class CertificateChannel extends ChannelContract { }
    private static final class CertificateChannelProcessor implements ChannelProcessor<CertificateChannel> {
        public Class<CertificateChannel> contractType() { return CertificateChannel.class; }
        public ExternalChannelSubscriptionFunctions<CertificateChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<CertificateChannel>() {
                public List<String> channelKeys(CertificateChannel channel) { return Collections.singletonList("update"); }
                public boolean preselects(CertificateChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                public boolean accepts(CertificateChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                public String logicalDeliveryKey(CertificateChannel channel, Node event, Node payload,
                        ExternalChannelFunctionContext context) { return "update"; }
                public String checkpointDomainDiscriminator(CertificateChannel channel) { return "certificate-process"; }
            };
        }
    }

    private static AffectedClosureSnapshotStorageCodec codec() { return new AffectedClosureSnapshotStorageCodec(LIMIT, 128); }
    private static AffectedClosureSnapshot simple(String label) {
        return snapshot(Collections.singletonMap(A, document(label)), Collections.emptyList(), A);
    }
    private static AffectedClosureSnapshot witnesses() {
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        for (DocumentId member : Arrays.asList(A, B, C)) bodies.put(member, initialized(document(member.value())));
        AffectedClosureSnapshot raw = snapshot(bodies, Collections.emptyList(), A);
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ManagedDocumentSnapshot document : raw.managedDocuments()) documents.add(new ManagedDocumentSnapshot(
                document.documentId(), document.blueId(), document.document(), true, false, document.publicRoot(),
                document.epoch(), document.componentGeneration()));
        AffectedClosureSnapshot original = ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(), documents,
                raw.occurrences(), raw.components(), raw.publicRootDocumentIds());
        Map<DocumentId, AffectedClosureSnapshot> originals = new LinkedHashMap<>(); originals.put(C, original); originals.put(B, original);
        return new AffectedClosureSnapshot(original.closureIdentity(), original.graphGeneration(), original.managedDocuments(),
                original.occurrences(), original.occurrenceBindingSetIdentity(), original.components(), original.publicRootDocumentIds(),
                RootedWitnessFrame.State.fromStoredOriginals(originals));
    }
    private static AffectedClosureSnapshot wideWitnesses(int children) {
        Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(A, initialized(document("wide owner")));
        Map<DocumentId, AffectedClosureSnapshot> proofs = new LinkedHashMap<>();
        for (int index = 0; index < children; index++) {
            DocumentId id = new DocumentId(String.format("w%02d", index));
            Node body = initialized(document(id.value())); bodies.put(id, body);
            proofs.put(id, initializedSnapshot(Collections.singletonMap(id, body), id));
        }
        return withWitnesses(initializedSnapshot(bodies, A), proofs);
    }
    private static AffectedClosureSnapshot initializedSnapshot(Map<DocumentId, Node> bodies, DocumentId root) {
        AffectedClosureSnapshot raw = snapshot(bodies, Collections.emptyList(), root);
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ManagedDocumentSnapshot document : raw.managedDocuments()) documents.add(new ManagedDocumentSnapshot(
                document.documentId(), document.blueId(), document.document(), true, false, document.publicRoot(),
                document.epoch(), document.componentGeneration()));
        return ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(), documents,
                raw.occurrences(), raw.components(), raw.publicRootDocumentIds());
    }
    private static AffectedClosureSnapshot withWitnesses(AffectedClosureSnapshot source,
            Map<DocumentId, AffectedClosureSnapshot> originals) {
        return new AffectedClosureSnapshot(source.closureIdentity(), source.graphGeneration(), source.managedDocuments(),
                source.occurrences(), source.occurrenceBindingSetIdentity(), source.components(), source.publicRootDocumentIds(),
                RootedWitnessFrame.State.fromStoredOriginals(originals));
    }
    private static Node initialized(Node source) {
        String authored = id(source);
        return source.clone().contracts(source.getContracts().clone().properties("initialized",
                typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER).properties("document", new Node().blueId(authored))));
    }
    private static DataInputStream payload(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 32));
    }
    private static void seal(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
    }
}
