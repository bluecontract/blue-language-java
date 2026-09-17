package blue.language.processor.closure;

import blue.language.runtime.BlueLanguage;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.ExactEventIdentityEvidenceStorageCodec;
import blue.language.snapshot.ExactNodeStorageCodec;
import blue.language.snapshot.FrozenNodeStorageCodec;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class ClosureExecutionEvidenceStorageCodecTest {
    private static final int BYTES = 16 * 1024 * 1024;
    private static final DocumentId ROOT = new DocumentId("root");
    private static final ClosureExecutionEvidenceStorageCodec CODEC = new ClosureExecutionEvidenceStorageCodec(BYTES, 128);
    private static final ClosureProcessResultStorageCodec RESULTS = new ClosureProcessResultStorageCodec(BYTES, 128);
    private static final ExactNodeStorageCodec NODES = new ExactNodeStorageCodec(BYTES, 128);

    @Test void issuedNestedBirthSuspensionReopensWithoutProcessingAndContinuesWithExactAuthority() {
        Node leaf = document("leaf").properties("seed", new Node().value(0));
        Node child = parent("child", leaf);
        byte[] bytes;
        ClosureProcessResult expected;
        try (CompositionCampaignFixture producer = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput input = birthInput(producer, child, 100_000L);
            ClosureAttemptResult attempt = producer.admit(input);
            assertFalse(attempt.isComplete());
            ManagedOccurrenceEvidenceDemand selected = demand(attempt);
            int invocations = producer.initialized.size();
            bytes = CODEC.encodeAttempt(input, null, attempt, selected);
            ClosureExecutionEvidenceStorageCodec.StoredAttempt cold = CODEC.decodeAttempt(bytes);
            assertEquals(invocations, producer.initialized.size(), "Storage does not run the parent again");
            assertSame(cold.attempt().resourceDemands().get(0), cold.selectedDemand());
            assertNotSame(selected, cold.selectedDemand());
            assertArrayEquals(NODES.encode(selected.suppliedExactValue().get()),
                    NODES.encode(((ManagedOccurrenceEvidenceDemand) cold.selectedDemand()).suppliedExactValue().get()));
            assertEquals(input.rootedBinding().entryInvocationIdentity, cold.input().rootedBinding().entryInvocationIdentity);
            expected = finish(producer, input, selected, child, leaf);
        }
        // The original producer is gone. Decode has no runtime or provider port.
        try (CompositionCampaignFixture consumer = new CompositionCampaignFixture(true)) {
            ClosureExecutionEvidenceStorageCodec.StoredAttempt cold = CODEC.decodeAttempt(bytes);
            assertTrue(consumer.initialized.isEmpty());
            ClosureProcessResult actual = finish(consumer, cold.input(), (ManagedOccurrenceEvidenceDemand) cold.selectedDemand(), child, leaf);
            assertArrayEquals(RESULTS.encode(expected), RESULTS.encode(actual));
            assertEquals(expected.totalGas(), actual.totalGas());
            assertEquals(expected.rootedProjection().companionIdentity(), actual.rootedProjection().companionIdentity());
        }
    }

    @Test void publicDemandCopiesCannotBecomeSelectedContinuationAuthority() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            Node child = document("child");
            ClosureInvocationInput input = birthInput(fixture, child, 100_000L);
            ClosureAttemptResult issued = fixture.admit(input);
            ManagedOccurrenceEvidenceDemand original = demand(issued);
            ManagedOccurrenceEvidenceDemand copy = new ManagedOccurrenceEvidenceDemand(original.demandIdentity(),
                    original.logicalCauseIdentity(), original.inputClosureIdentity(), original.inputGraphGeneration(),
                    original.sourceDocumentId(), original.sourcePath(), original.processEmbeddedDeclarationIdentity(),
                    original.suppliedValueBlueId(), original.demandOrdinal(), child);
            assertEquals(original, copy);
            assertThrows(IllegalArgumentException.class, () -> CODEC.encodeAttempt(input, null, issued, copy));
            ClosureAttemptResult invented = ClosureAttemptResult.needsResources(Collections.singletonList(copy));
            assertThrows(IllegalArgumentException.class, () -> CODEC.encodeAttempt(input, null, invented, copy));
            ClosureExecutionEvidenceStorageCodec.StoredAttempt unselected = CODEC.decodeAttempt(CODEC.encodeAttempt(input, null, invented, null));
            ManagedOccurrenceEvidenceDemand restored = demand(unselected.attempt());
            assertFalse(restored.wasEmittedBy(input.invocationIdentity()));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.withProspectiveBirths(unselected.input(),
                    Collections.singletonList(new ManagedDocumentBirth(restored, new DocumentId("child"), child))));
            ClosureInvocationInput changedGas = fixture.admission(input.snapshot(), 200_000L);
            assertThrows(IllegalArgumentException.class, () -> CODEC.encodeAttempt(changedGas, null, issued, original));
        }
    }

    @Test void consumedStandaloneDemandKeepsOnlyItsOriginalIssuerAndExactConstruction() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            Node child = document("child");
            ClosureInvocationInput input = birthInput(fixture, child, 100_000L);
            ManagedOccurrenceEvidenceDemand original = demand(fixture.admit(input));
            int calls = fixture.initialized.size();
            byte[] stored = CODEC.encodeResourceDemand(original);
            ManagedOccurrenceEvidenceDemand cold = (ManagedOccurrenceEvidenceDemand) CODEC.decodeResourceDemand(stored);
            assertEquals(calls, fixture.initialized.size());
            assertTrue(cold.wasEmittedBy(input.invocationIdentity()));
            assertArrayEquals(stored, CODEC.encodeResourceDemand(cold));
            assertArrayEquals(NODES.encode(original.suppliedExactValue().get()), NODES.encode(cold.suppliedExactValue().get()));
            ClosureInvocationInput expanded = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(cold, new DocumentId("child"), child)));
            assertFalse(cold.wasEmittedBy(expanded.invocationIdentity()), "A consumed demand is not reissued for the expanded input");
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.withProspectiveBirths(expanded,
                    Collections.singletonList(new ManagedDocumentBirth(cold, new DocumentId("child"), child))));
            ManagedOccurrenceEvidenceDemand copy = ManagedOccurrenceEvidenceDemand.derived(original.logicalCauseIdentity(),
                    original.inputClosureIdentity(), original.inputGraphGeneration(), original.sourceDocumentId(), original.sourcePath(),
                    original.processEmbeddedDeclarationIdentity(), original.suppliedValueBlueId(), original.demandOrdinal());
            ManagedOccurrenceEvidenceDemand unissued = (ManagedOccurrenceEvidenceDemand) CODEC.decodeResourceDemand(CODEC.encodeResourceDemand(copy));
            assertFalse(unissued.wasEmittedBy(input.invocationIdentity()));
            assertFalse(unissued.suppliedExactValue().isPresent());
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(unissued, new DocumentId("child"), child))));
            ExactNodeDemand exact = ExactNodeDemand.derived(id(document("missing")), ROOT, "/missing");
            assertEquals(exact, CODEC.decodeResourceDemand(CODEC.encodeResourceDemand(exact)));
            assertTrue(fixture.admit(expanded).processResult().commits());
        }
    }

    @Test void standaloneKindsBoundsAndCorruptionCannotBeConfusedWithAssociatedAttempts() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput input = birthInput(fixture, document("child"), 100_000L);
            ManagedOccurrenceEvidenceDemand demand = demand(fixture.admit(input));
            byte[] bytes = CODEC.encodeResourceDemand(demand);
            byte[] damaged = bytes.clone(); damaged[damaged.length / 2] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeResourceDemand(damaged));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeResourceDemand(Arrays.copyOf(bytes, bytes.length - 1)));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeResourceDemand(Arrays.copyOf(bytes, bytes.length + 1)));
            assertThrows(IllegalArgumentException.class, () -> new ClosureExecutionEvidenceStorageCodec(bytes.length - 1, 128).decodeResourceDemand(bytes));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeAttempt(bytes));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeRetry(bytes));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeProcessingCause(bytes));
            byte[] cause = CODEC.encodeProcessingCause(input.cause());
            assertEquals(input.cause().causeIdentity(), CODEC.decodeProcessingCause(cause).causeIdentity());
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeResourceDemand(cause));
            byte[] wrongPayload = NODES.encodeEnvelope("blue-contracts/execution-storage/resource-demand/1", out ->
                    new ClosureResultStorageValues.Writer(out, NODES, new ExactEventIdentityEvidenceStorageCodec(BYTES, 128), CODEC).w(input));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeResourceDemand(wrongPayload));
        }
    }

    @Test void completeInputResultAndTightGasFailureKeepOriginalRootBinding() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(ROOT, document("root").properties("seed", new Node().value(0))),
                    Collections.emptyList(), ROOT);
            ClosureInvocationInput input = rooted(fixture.admission(graph, 100_000L));
            ClosureAttemptResult complete = fixture.admit(input);
            assertTrue(complete.processResult().commits());
            ClosureInvocationInput restored = CODEC.decodeInvocation(CODEC.encodeInvocation(input));
            assertArrayEquals(CODEC.encodeInvocation(input), CODEC.encodeInvocation(restored));
            assertNotSame(input.snapshot(), restored.snapshot());
            ClosureExecutionEvidenceStorageCodec.StoredAttempt stored = CODEC.decodeAttempt(CODEC.encodeAttempt(input, null, complete, null));
            assertArrayEquals(RESULTS.encode(complete.processResult()), RESULTS.encode(stored.attempt().processResult()));
            long gas = complete.processResult().totalGas();
            ClosureInvocationInput exact = rooted(fixture.admission(graph, gas));
            ClosureAttemptResult atLimit = fixture.admit(exact);
            assertTrue(atLimit.processResult().commits());
            CODEC.decodeAttempt(CODEC.encodeAttempt(exact, null, atLimit, null));
            ClosureInvocationInput tight = rooted(fixture.admission(graph, gas - 1));
            ClosureAttemptResult failure = fixture.admit(tight);
            rollback(tight, failure.processResult());
            ClosureProcessResult coldFailure = CODEC.decodeAttempt(CODEC.encodeAttempt(tight, null, failure, null)).attempt().processResult();
            assertArrayEquals(RESULTS.encode(failure.processResult()), RESULTS.encode(coldFailure));
            assertNotNull(coldFailure.rejectedCharge());
            assertThrows(IllegalArgumentException.class, () -> CODEC.encodeAttempt(tight, null, complete, null));
            ClosureInvocationInput ordinary = fixture.admission(graph, 100_000L);
            assertNull(CODEC.decodeInvocation(CODEC.encodeInvocation(ordinary)).rootedBinding());
            assertNull(CODEC.originalRootedBinding(CODEC.decodeInvocation(CODEC.encodeInvocation(ordinary))));
            assertThrows(IllegalArgumentException.class, () -> CODEC.encodeAttempt(ordinary, null, complete, null));
            ClosureAttemptResult unrootedResult = fixture.admit(ordinary);
            assertTrue(unrootedResult.processResult().commits());
            assertNull(unrootedResult.processResult().rootedProjection());
            assertEquals(input.invocationIdentity(), ordinary.invocationIdentity());
            assertThrows(IllegalArgumentException.class, () -> CODEC.encodeAttempt(input, null, unrootedResult, null),
                    "A successful rooted input cannot discard its private publication ownership");
        }
    }

    @Test void orderedMixedDemandsAndSelectedIndexRemainExactAndDefensive() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput input = birthInput(fixture, document("child"), 100_000L);
            ManagedOccurrenceEvidenceDemand managed = demand(fixture.admit(input));
            ExactNodeDemand exact = ExactNodeDemand.derived(id(document("unavailable")), ROOT, "/a");
            ClosureAttemptResult mixed = ClosureAttemptResult.needsResources(Arrays.asList(managed, exact));
            byte[] bytes = CODEC.encodeAttempt(input, null, mixed, managed);
            ClosureExecutionEvidenceStorageCodec.StoredAttempt restored = CODEC.decodeAttempt(bytes);
            assertEquals(mixed.resourceDemands(), restored.attempt().resourceDemands());
            assertEquals(mixed.requiredExactBlueIds(), restored.attempt().requiredExactBlueIds());
            int selected = mixed.resourceDemands().indexOf(managed);
            assertSame(restored.attempt().resourceDemands().get(selected), restored.selectedDemand());
            ((ManagedOccurrenceEvidenceDemand) restored.selectedDemand()).suppliedExactValue().get().value("mutated copy");
            assertArrayEquals(bytes, CODEC.encodeAttempt(restored.input(), restored.retry(), restored.attempt(), restored.selectedDemand()));
            assertThrows(UnsupportedOperationException.class, () -> restored.attempt().resourceDemands().clear());
        }
    }

    @Test void malformedOverBoundAndRechecksummedForeignBindingFailClosed() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput input = birthInput(fixture, document("child"), 100_000L);
            ClosureAttemptResult attempt = fixture.admit(input);
            byte[] bytes = CODEC.encodeAttempt(input, null, attempt, demand(attempt));
            byte[] damaged = bytes.clone(); damaged[damaged.length / 2] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeAttempt(damaged));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeAttempt(Arrays.copyOf(bytes, bytes.length - 1)));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeAttempt(Arrays.copyOf(bytes, bytes.length + 1)));
            assertThrows(IllegalArgumentException.class, () -> new ClosureExecutionEvidenceStorageCodec(bytes.length - 1, 128).decodeAttempt(bytes));
            assertThrows(IllegalArgumentException.class, () -> new ClosureExecutionEvidenceStorageCodec(128, 128).encodeInvocation(input));
            assertThrows(IllegalArgumentException.class, () -> new ClosureExecutionEvidenceStorageCodec(BYTES, 1).decodeAttempt(bytes));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeInvocation(bytes));
            RootedInvocationBinding binding = input.rootedBinding();
            RootedProcessingContext wrong = new RootedProcessingContext(binding.context.entryOwners(), binding.context.ownerDescriptor(), hash('f'));
            ClosureInvocationInput corrupt = input.withRootedBinding(new RootedInvocationBinding(wrong, binding.deliveryIdentity,
                    binding.entryInvocationIdentity, binding.birthParents));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeInvocation(CODEC.encodeInvocation(corrupt)));
        }
    }

    @Test void standaloneTypedSourceReceiptRestoresOriginalFrozenEvidenceAfterProviderCloses() {
        Node parent = new Node().name("Standalone receipt nominal parent");
        String parentId = id(parent);
        Node event = new Node().type(new Node().type(new Node().blueId(parentId)).name("Inline receipt type"))
                .properties("payload", new Node().value("stored"));
        AtomicInteger reads = new AtomicInteger();
        ExactEventIdentityEvidence original;
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(key -> {
            reads.incrementAndGet(); return key.equals(parentId) ? Collections.singletonList(parent.clone()) : Collections.emptyList();
        }).build(); BlueContracts contracts = BlueContracts.builder(language.processing()).build()) {
            String identity = contracts.runtimeAccess().languageRuntime().calculateSourceDocumentBlueId(event);
            original = ExactEventIdentityEvidence.verify(contracts.runtimeAccess(), event, identity, null);
        }
        assertTrue(reads.get() > 0);
        String source = hash('a');
        ManagedRootEventOccurrence occurrence = new ManagedRootEventOccurrence(0, 0, ROOT,
                ClosureIdentityService.INSTANCE.eventOccurrenceIdentity(source, 0, original.eventBlueId()), original, true);
        ManagedDocumentTransitionReceipt receipt = ManagedDocumentTransitionReceipt.identified(source, 0, ROOT,
                hash('b'), id(document("before")), id(document("after")), Collections.singletonList(occurrence), 100);
        byte[] bytes = CODEC.encodeTransitionReceipt(receipt);
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(key -> {
            throw new AssertionError("No source provider on stored receipt decode " + key);
        }).build(); BlueContracts contracts = BlueContracts.builder(language.processing()).build()) {
            ManagedDocumentTransitionReceipt restored = CODEC.decodeTransitionReceipt(bytes);
            assertArrayEquals(bytes, CODEC.encodeTransitionReceipt(restored));
            FrozenNodeStorageCodec frozen = new FrozenNodeStorageCodec(BYTES, 128);
            assertArrayEquals(frozen.encode(original.frozenEvent()),
                    frozen.encode(restored.emittedRootEvents().get(0).exactEventIdentityEvidence().frozenEvent()));
            assertThrows(AssertionError.class, () -> ExactEventIdentityEvidence.verify(contracts.runtimeAccess(), event, original.eventBlueId(), null));
        }
    }

    @Test void selfChecksummedWrongSelectionAndEmitterDoNotCreateContinuationAuthority() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput input = birthInput(fixture, document("child"), 100_000L);
            ClosureAttemptResult actual = fixture.admit(input);
            for (int index : new int[]{-2, 1, Integer.MAX_VALUE})
                assertThrows(IllegalArgumentException.class, () -> CODEC.decodeAttempt(packet(input, actual, index)));
            ManagedOccurrenceEvidenceDemand wrong = demand(actual).emittedBy(hash('f'));
            ClosureAttemptResult wrongIssuer = ClosureAttemptResult.needsResources(Collections.singletonList(wrong));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeAttempt(packet(input, wrongIssuer, 0)));
            ManagedOccurrenceEvidenceDemand original = demand(actual);
            ManagedOccurrenceEvidenceDemand unissued = ManagedOccurrenceEvidenceDemand.derived(original.logicalCauseIdentity(),
                    original.inputClosureIdentity(), original.inputGraphGeneration(), original.sourceDocumentId(), original.sourcePath(),
                    original.processEmbeddedDeclarationIdentity(), original.suppliedValueBlueId(), original.demandOrdinal());
            ClosureAttemptResult publicCopy = ClosureAttemptResult.needsResources(Collections.singletonList(unissued));
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodeAttempt(packet(input, publicCopy, 0)));
            assertSame(CODEC.decodeAttempt(packet(input, actual, 0)).selectedDemand().getClass(), ManagedOccurrenceEvidenceDemand.class);
        }
    }

    private static byte[] packet(ClosureInvocationInput input, ClosureAttemptResult attempt, int selected) {
        return NODES.encodeEnvelope("blue-contracts/execution-storage/attempt/1", out ->
                new ClosureResultStorageValues.Writer(out, NODES, new ExactEventIdentityEvidenceStorageCodec(BYTES, 128), CODEC)
                        .fields("StoredAttempt", input, null, attempt, selected));
    }

    private static ClosureProcessResult finish(CompositionCampaignFixture fixture, ClosureInvocationInput input,
            ManagedOccurrenceEvidenceDemand selected, Node child, Node leaf) {
        ClosureInvocationInput expanded = ClosureEvidenceFactory.withProspectiveBirths(input,
                Collections.singletonList(new ManagedDocumentBirth(selected, new DocumentId("child"), child)));
        // A second genuine suspension exercises the original private entry binding
        // and authenticated birth-parent map after the first expansion.
        ClosureAttemptResult second = fixture.admit(expanded);
        assertFalse(second.isComplete());
        ClosureExecutionEvidenceStorageCodec.StoredAttempt cold = CODEC.decodeAttempt(CODEC.encodeAttempt(expanded, null, second, demand(second)));
        assertEquals(input.rootedBinding().entryInvocationIdentity, cold.input().rootedBinding().entryInvocationIdentity);
        assertEquals(ROOT, cold.input().rootedBinding().birthParents.get(new DocumentId("child")));
        ClosureExecutionEvidenceStorageCodec.OriginalRootedBinding original = CODEC.originalRootedBinding(cold.input());
        assertEquals(input.rootedBinding().context.identity(), original.context().identity());
        assertEquals(input.invocationIdentity(), original.entryInvocationIdentity());
        assertEquals(input.rootedBinding().deliveryIdentity, original.deliveryBasisIdentity());
        assertNotEquals(cold.input().invocationIdentity(), original.entryInvocationIdentity());
        ClosureInvocationInput complete = ClosureEvidenceFactory.withProspectiveBirths(cold.input(),
                Collections.singletonList(new ManagedDocumentBirth((ManagedOccurrenceEvidenceDemand) cold.selectedDemand(), new DocumentId("leaf"), leaf)));
        ClosureProcessResult result = fixture.admit(complete).processResult();
        assertTrue(result.commits(), diagnostic(result));
        assertArrayEquals(RESULTS.encode(result), RESULTS.encode(RESULTS.decode(RESULTS.encode(result))),
                "The completed expanded invocation retains its ORIGINAL rooted entry binding too");
        assertArrayEquals(RESULTS.encode(result), RESULTS.encode(CODEC.decodeAttempt(
                CODEC.encodeAttempt(complete, null, ClosureAttemptResult.complete(result), null)).attempt().processResult()));
        return result;
    }
    private static ClosureInvocationInput birthInput(CompositionCampaignFixture fixture, Node child, long gas) {
        return rooted(fixture.admission(snapshot(Collections.singletonMap(ROOT, parent("root", child)), Collections.emptyList(), ROOT), gas));
    }
    private static Node parent(String label, Node child) {
        Node result = document(label).properties("children", new Node().properties(Collections.emptyMap()))
                .properties("install", new Node().properties("child", child));
        result.getContracts().properties("embedded", process("collectionPaths", "/children"));
        return result;
    }
    private static ClosureInvocationInput rooted(ClosureInvocationInput input) {
        return input.withRootedContext(RootedProcessingContext.derive(input.snapshot(), ROOT, Collections.singletonMap(ROOT, hash('c'))), hash('d'));
    }
    private static ManagedOccurrenceEvidenceDemand demand(ClosureAttemptResult attempt) {
        assertEquals(1, attempt.resourceDemands().size());
        return (ManagedOccurrenceEvidenceDemand) attempt.resourceDemands().get(0);
    }
}
