package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Selection changes an existing immutable primary, never the admitted operation or inventory. */
final class RootedWitnessSelectionTest {
    private static final DocumentId ROOT = new DocumentId("B");
    private static final DocumentId A = new DocumentId("A");
    private static final DocumentId D = new DocumentId("D");
    private static final DocumentId LIVE = new DocumentId("live");
    private static final DocumentId BORN = new DocumentId("born");
    private static final Node CHANNEL = new Node().name("Witness selection channel");
    private static final Node HANDLER = new Node().name("Witness selection handler");

    @Test
    void selectingAnIndependentPrimaryPreservesTheOlderSourceProofAndEveryOwnerOperand() {
        // given
        try (Runtime runtime = new Runtime()) {
            Fixture f = mixed(runtime, false, false);
            AffectedClosureSnapshot before = f.input.snapshot();
            Map<DocumentId, AffectedClosureSnapshot> choices = new LinkedHashMap<>();
            choices.put(D, f.newProof);
            // when
            AffectedClosureSnapshot selected = ClosureEvidenceFactory.rootedWitnessSelection(before, choices);
            choices.clear();
            AffectedClosureSnapshot equivalent = select(before, D, copy(f.newProof));
            AffectedClosureSnapshot repeated = select(selected, D, copy(f.newProof));
            // then
            assertSelection(f, selected);
            assertDocument(before.managedDocument(ROOT), selected.managedDocument(ROOT));
            assertEquals(selected.closureIdentity(), equivalent.closureIdentity());
            assertEquals(selected.closureIdentity(), repeated.closureIdentity());
            assertEquals(selected.occurrenceBindingSetIdentity(), equivalent.occurrenceBindingSetIdentity());
            assertNotEquals(before.managedDocument(D).blueId(), selected.managedDocument(D).blueId());
            assertNotEquals(row(before, D, "/retired").bindingIdentity(),
                    row(selected, D, "/retired").bindingIdentity());
            assertEquals(row(before, D, "/retired").occurrenceIdentity(),
                    row(selected, D, "/retired").occurrenceIdentity());
            assertEquals(f.oldProof.managedDocument(D).blueId(), before.managedDocument(D).blueId());
            assertEquals(f.oldProof.managedDocument(D).blueId(), row(selected, A, "/d").expectedTargetBlueId());
            assertFalse(selected.graph().hasEdge(A, D));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(
                    f.input, selected.graphGeneration(), selected.managedDocuments(), selected.occurrences(),
                    Collections.singletonList(f.newProof)));
            RootedProcessingContext oldContext = f.input.rootedBinding().context;
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.processClosure(selected,
                    f.input.cause(), f.input.directDeliveries(), f.input.executionPolicy(), runtime.environment)
                    .withRootedContext(oldContext, hash('d')));
        }
    }

    @Test
    void emptyAndEquivalentSelectionsDoNotChangeSameContextEvidence() {
        // given
        try (Runtime runtime = new Runtime()) {
            Fixture f = mixed(runtime, false, false);
            AffectedClosureSnapshot before = f.input.snapshot();
            Node plain = f.entry.managedDocument(ROOT).document();
            plain.getContracts().getProperties().remove("embedded");
            AffectedClosureSnapshot plainState = initializedSnapshot(Collections.singletonMap(ROOT, plain),
                    Collections.emptyList(), ROOT, 2L);
            // when
            AffectedClosureSnapshot empty = ClosureEvidenceFactory.rootedWitnessSelection(before, Collections.emptyMap());
            AffectedClosureSnapshot unchanged = select(before, D, copy(f.oldProof));
            AffectedClosureSnapshot noWitness = ClosureEvidenceFactory.rootedWitnessSelection(f.entry, Collections.emptyMap());
            ClosureProcessResult sameContext = complete(runtime.process(invocation(f, before, 100_000L)));
            ClosureProcessResult sameContextSelected = complete(runtime.process(invocation(f, unchanged, 100_000L)));
            ClosureProcessResult ordinary = complete(runtime.process(invocation(f, plainState, 100_000L)));
            ClosureProcessResult ordinaryNoOp = complete(runtime.process(invocation(f,
                    ClosureEvidenceFactory.rootedWitnessSelection(plainState, Collections.emptyMap()), 100_000L)));
            // then
            assertEquals(before.closureIdentity(), empty.closureIdentity());
            assertSame(f.entry, noWitness);
            assertEquals(before.closureIdentity(), unchanged.closureIdentity());
            assertEquals(before.occurrenceBindingSetIdentity(), unchanged.occurrenceBindingSetIdentity());
            assertTrue(unchanged.graph().hasEdge(A, D));
            assertDocument(before.managedDocument(A), unchanged.managedDocument(A));
            assertDocument(before.managedDocument(D), unchanged.managedDocument(D));
            ClosureEvidenceVerifier.verifySnapshot(unchanged);
            assertEquals(sameContext.outputClosureIdentity(), sameContextSelected.outputClosureIdentity());
            assertEquals(sameContext.totalGas(), sameContextSelected.totalGas());
            assertEquals(trace(sameContext), trace(sameContextSelected));
            assertEquals(ordinary.outputClosureIdentity(), ordinaryNoOp.outputClosureIdentity());
            assertEquals(ordinary.totalGas(), ordinaryNoOp.totalGas());
            assertEquals(trace(ordinary), trace(ordinaryNoOp));
        }
    }

    @Test
    void selectionRequiresAuthenticatedWitnessRolesAndCannotChooseAnOwnerLiveOrPendingTarget() {
        // given
        try (Runtime runtime = new Runtime()) {
            Fixture f = mixed(runtime, false, true);
            AffectedClosureSnapshot before = f.input.snapshot();
            AffectedClosureSnapshot unauthenticated = copy(before);
            // when
            List<DocumentId> protectedIds = Arrays.asList(ROOT, LIVE, A, BORN);
            // then
            assertNull(unauthenticated.rootedWitnesses());
            assertThrows(IllegalArgumentException.class,
                    () -> select(unauthenticated, D, f.newProof));
            assertTrue(before.rootedWitnesses().sources().contains(A));
            assertFalse(before.rootedWitnesses().sources().contains(LIVE));
            for (DocumentId id : protectedIds) {
                AffectedClosureSnapshot proof = id.equals(A) ? f.oldProof
                        : id.equals(BORN) ? single(BORN, 1L) : f.entry;
                assertThrows(IllegalArgumentException.class, () -> select(before, id, proof), id.value());
            }
            assertSelection(f, select(before, D, f.newProof));
        }
    }

    @Test
    void forgedBodyEpochGenerationOwnRowsAndComponentEvidenceAreRejected() {
        // given
        try (Runtime runtime = new Runtime()) {
            Fixture f = mixed(runtime, false, false);
            ManagedDocumentSnapshot exact = f.newProof.managedDocument(D);
            // when
            List<ManagedDocumentSnapshot> forgeries = Arrays.asList(
                    changed(exact, exact.document().properties("count", new Node().value(99L)), exact.epoch(), exact.componentGeneration()),
                    changed(exact, exact.document(), exact.epoch() + 1L, exact.componentGeneration()),
                    changed(exact, exact.document(), exact.epoch(), exact.componentGeneration() + 1L));
            // then
            for (ManagedDocumentSnapshot forged : forgeries) assertThrows(IllegalArgumentException.class,
                    () -> select(f.input.snapshot(), D, asserted(f.newProof, replace(f.newProof.managedDocuments(), forged),
                            f.newProof.occurrences(), f.newProof.components())));
            assertThrows(IllegalArgumentException.class, () -> select(f.input.snapshot(), D,
                    asserted(f.newProof, f.newProof.managedDocuments(), Collections.emptyList(), f.newProof.components())));
            List<ComponentSnapshot> components = new ArrayList<>(f.newProof.components());
            ComponentSnapshot component = components.get(0);
            components.set(0, new ComponentSnapshot(component.componentIdentity(), hash('f'), component.componentGeneration(),
                    component.kind(), component.orderedMemberDocumentIds(), component.orderedMemberBlueIds(),
                    component.masterBlueId(), component.completeCyclicProof(), component.cyclicProofIdentity()));
            assertThrows(IllegalArgumentException.class, () -> select(f.input.snapshot(), D,
                    asserted(f.newProof, f.newProof.managedDocuments(), f.newProof.occurrences(), components)));
            Node escaped = f.newProof.managedDocument(D).document();
            escaped.properties("count", new Node().value(500L));
            assertSelection(f, select(f.input.snapshot(), D, f.newProof));
        }
    }

    @Test
    void evenRealSelectedProofsCannotIntroduceOccurrenceIdsEndpointsOrPrimaryDocuments() {
        // given
        try (Runtime runtime = new Runtime()) {
            Fixture f = mixed(runtime, false, false);
            Node peer = initialized(new Node().name("proof-only peer"));
            Node newer = f.newProof.managedDocument(D).document();
            // when
            AffectedClosureSnapshot newOccurrence = proof(runtime, D, newer, A, peer, "/new-occurrence", 14L);
            AffectedClosureSnapshot newEndpoint = proof(runtime, D, newer, ROOT, peer, "/retired", 14L);
            AffectedClosureSnapshot newDocument = single(BORN, 1L);
            // then
            ClosureEvidenceVerifier.verifySnapshot(newOccurrence);
            ClosureEvidenceVerifier.verifySnapshot(newEndpoint);
            assertThrows(IllegalArgumentException.class, () -> select(f.input.snapshot(), D, newOccurrence));
            assertThrows(IllegalArgumentException.class, () -> select(f.input.snapshot(), D, newEndpoint));
            assertThrows(IllegalArgumentException.class, () -> select(f.input.snapshot(), BORN, newDocument));
            assertEquals(f.input.snapshot().managedDocuments().size(), select(f.input.snapshot(), D, f.newProof).managedDocuments().size());
        }
    }

    @Test
    void sameContextSccAndSelfCycleKeepCompleteProofsAndARealJoinReleasesSelectedRoles() {
        // given
        try (Runtime runtime = new Runtime()) {
            Fixture mixed = mixed(runtime, false, false);
            AffectedClosureSnapshot selected = select(mixed.input.snapshot(), D, mixed.newProof);
            // when
            for (boolean self : Arrays.asList(false, true)) {
                DocumentId target = self ? D : A;
                Map<DocumentId, Node> bodies = new LinkedHashMap<>();
                bodies.put(D, initialized(new Node().name("cyclic D").properties("peer", new Node().value("seed"))));
                if (!self) bodies.put(A, initialized(new Node().name("cyclic A").properties("peer", new Node().value("seed"))));
                List<ManagedOccurrenceBinding> rows = new ArrayList<>();
                rows.add(runtime.binding(D, "/peer", target, id(bodies.get(target)), true, null));
                if (!self) rows.add(runtime.binding(A, "/peer", D, id(bodies.get(D)), true, null));
                AffectedClosureSnapshot cyclic = initializedSnapshot(bodies, rows, D, 11L);
                // An inactive non-pending read does not protect a source as a frozen catch-up anchor.
                Fixture f = fixture(runtime, cyclic, cyclic, self ? D : A, false, false, !self);
                AffectedClosureSnapshot unchanged = select(f.input.snapshot(), D, copy(cyclic));
                ComponentSnapshot expected = cyclic.components().get(0);
                ComponentSnapshot actual = unchanged.components().stream().filter(c -> c.orderedMemberDocumentIds().contains(D))
                        .findFirst().orElseThrow(AssertionError::new);
                assertEquals(ComponentKind.CYCLIC, actual.kind());
                assertEquals(expected.componentStateIdentity(), actual.componentStateIdentity());
                assertEquals(expected.orderedMemberBlueIds(), actual.orderedMemberBlueIds());
                assertNotNull(actual.completeCyclicProof());
                assertTrue(unchanged.graph().hasEdge(D, target));
            }
            List<ManagedOccurrenceBinding> joined = new ArrayList<>();
            for (ManagedOccurrenceBinding row : selected.occurrences()) joined.add(row.sourceDocumentId().equals(ROOT)
                    && row.targetDocumentId().equals(A) ? runtime.binding(ROOT, row.sourcePath(), A,
                    row.expectedTargetBlueId(), true, null) : row);
            joined.add(runtime.binding(A, "/return", ROOT, selected.managedDocument(ROOT).blueId(), true, null));
            RootedWitnessFrame frame = new RootedWitnessFrame(invocation(mixed, selected, 100_000L));
            RootedWitnessFrame.State released = frame.at(joined, selected.graph().documentIds());
            // then
            assertTrue(released.sources().isEmpty());
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            Map<DocumentId, Long> generations = new LinkedHashMap<>();
            for (ManagedDocumentSnapshot doc : selected.managedDocuments()) {
                bodies.put(doc.documentId(), doc.document()); generations.put(doc.documentId(), doc.componentGeneration());
            }
            bodies.get(A).properties("return", new Node().blueId(selected.managedDocument(ROOT).blueId()));
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                    new ComponentFinalizationInput(selected.graph(), generations, bodies, joined, released));
            assertTrue(finalized.finalizedGraph().hasEdge(A, D));
            assertEquals(selected.managedDocument(D).blueId(), finalized.document(A).document().getNode("/d").getBlueId());
            assertTrue(frame.at(selected.occurrences(), selected.graph().documentIds()).sources().isEmpty());
        }
    }

    @Test
    void selectedContextsSurviveProcessExactGasRollbackAndRetainedResults() {
        // given
        try (Runtime runtime = new Runtime()) {
            Fixture f = mixed(runtime, false, false);
            AffectedClosureSnapshot selected = select(f.input.snapshot(), D, f.newProof);
            ClosureInvocationInput input = invocation(f, selected, 100_000L);
            // when
            ClosureProcessResult result = complete(runtime.process(input));
            ClosureProcessResult repeated = complete(runtime.process(invocation(f, select(f.input.snapshot(), D, copy(f.newProof)), 100_000L)));
            ClosureInvocationInput tight = invocation(f, selected, result.totalGas() - 1L);
            ClosureProcessResult failed = runtime.process(tight).processResult();
            ClosureProcessResult failedAgain = runtime.process(tight).processResult();
            // then
            assertSelection(f, result.rootedProjection().resultingSnapshot());
            assertEquals(Collections.singletonList(ROOT), result.rootedProjection().ownedDocumentIds());
            assertEquals(1L, ((Number) result.rootedProjection().resultingSnapshot().managedDocument(ROOT)
                    .document().getNode("/count").getValue()).longValue());
            assertEquals(result.outputClosureIdentity(), repeated.outputClosureIdentity());
            assertEquals(trace(result), trace(repeated));
            assertEquals(result.gasTraceIdentity(), repeated.gasTraceIdentity());
            assertNotNull(failed); assertNotNull(failedAgain);
            rollback(tight, failed);
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.status());
            assertNull(failed.rootedProjection());
            assertNotNull(failed.rejectedCharge());
            assertEquals(failed.rejectedCharge().rejectedChargeIdentity(), failedAgain.rejectedCharge().rejectedChargeIdentity());
            assertEquals(trace(failed), trace(failedAgain));
            assertEquals(failed.gasTraceIdentity(), failedAgain.gasTraceIdentity());
            long epoch = result.rootedProjection().resultingSnapshot().managedDocument(ROOT).epoch();
            assertSelection(f, ClosureEvidenceFactory.rootedRetainedSnapshot(result, Collections.singletonMap(ROOT, epoch)));
        }
    }

    @Test
    void selectedIndependentContextsSurviveColdStorageAndExactGasReexecution() {
        // given
        AffectedClosureSnapshotStorageCodec snapshots =
                new AffectedClosureSnapshotStorageCodec(16 * 1024 * 1024, 128);
        ClosureExecutionEvidenceStorageCodec executions =
                new ClosureExecutionEvidenceStorageCodec(16 * 1024 * 1024, 128);
        ClosureProcessResultStorageCodec results =
                new ClosureProcessResultStorageCodec(16 * 1024 * 1024, 128);
        Fixture fixture;
        Map<String, Node> exact = new LinkedHashMap<>();
        byte[] selectedBytes, invocationBytes, resultBytes, tightBytes, failedBytes;
        try (Runtime producer = new Runtime()) {
            fixture = mixed(producer, false, false);
            AffectedClosureSnapshot selected = select(fixture.input.snapshot(), D, fixture.newProof);
            ClosureInvocationInput input = invocation(fixture, selected, 100_000L);
            ClosureProcessResult result = complete(producer.process(input));
            ClosureInvocationInput tight = invocation(fixture, selected, result.totalGas() - 1L);
            ClosureProcessResult failed = producer.process(tight).processResult();
            selectedBytes = snapshots.encode(selected);
            invocationBytes = executions.encodeInvocation(input);
            resultBytes = results.encode(result);
            tightBytes = executions.encodeInvocation(tight);
            failedBytes = results.encode(failed);
            producer.exact.forEach((id, node) -> exact.put(id, node.clone()));
        }
        // when: the issuing processor/provider is closed before any decode.
        AffectedClosureSnapshot restoredSnapshot = snapshots.decode(selectedBytes);
        long snapshotHits = snapshots.acceptedByteStatistics().hits;
        AffectedClosureSnapshot warmSnapshot = snapshots.decode(selectedBytes.clone());
        assertEquals(snapshotHits + 1, snapshots.acceptedByteStatistics().hits);
        assertNotSame(restoredSnapshot, warmSnapshot);
        assertNotSame(restoredSnapshot.rootedWitnesses().storedOriginals().get(A),
                warmSnapshot.rootedWitnesses().storedOriginals().get(A));
        assertSelection(fixture, warmSnapshot);
        assertArrayEquals(selectedBytes, snapshots.encode(warmSnapshot));
        ClosureInvocationInput restoredInput = executions.decodeInvocation(invocationBytes);
        ClosureInvocationInput restoredTight = executions.decodeInvocation(tightBytes);
        ClosureProcessResult restoredResult = results.decode(resultBytes);
        ClosureProcessResult restoredFailure = results.decode(failedBytes);
        // The same real A11/D2/D14 and G-1 evidence also crosses the cold
        // result-input proof handoff, without replacing any witness object.
        for (byte[] storedResult : Arrays.asList(resultBytes, failedBytes)) {
            AffectedClosureSnapshotStorageCodec coldSnapshots =
                    new AffectedClosureSnapshotStorageCodec(16 * 1024 * 1024, 128);
            try (SnapshotStorageCall proven = coldSnapshots.newCall();
                    SnapshotStorageCall disabled = new SnapshotStorageCall(coldSnapshots, 0, 0)) {
                ClosureProcessResult reused = results.decodeInCall(storedResult, proven);
                ClosureProcessResult uncached = results.decodeInCall(storedResult, disabled);
                assertEquals(1, proven.resultInputVerificationReuses());
                assertEquals(0, proven.resultInputVerificationAttempts());
                assertEquals(0, disabled.resultInputVerificationReuses());
                assertEquals(1, disabled.resultInputVerificationAttempts());
                assertNotSame(reused.storageInputSnapshot(), uncached.storageInputSnapshot());
                assertSelection(fixture, reused.storageInputSnapshot());
                assertSelection(fixture, uncached.storageInputSnapshot());
                assertArrayEquals(storedResult, results.encodeInCall(reused, disabled));
                assertArrayEquals(storedResult, results.encodeInCall(uncached, proven));
                assertEquals(reused.gasTraceIdentity(), uncached.gasTraceIdentity());
                assertEquals(reused.managedTransitionReceiptsIdentity(), uncached.managedTransitionReceiptsIdentity());
                assertTrue(proven.retainedVerifications() <= 32);
                assertTrue(proven.peakEntries() <= 32);
                assertTrue(proven.peakBytes() <= 8 * 1024 * 1024);
            }
        }
        // Repeated result envelopes may share a decoded root object, but must
        // preserve the complete independent/nested witness DAG's wire aliases.
        try (SnapshotStorageCall reuse = snapshots.newCall();
                SnapshotStorageCall disabled = new SnapshotStorageCall(snapshots, 0, 0)) {
            ClosureProcessResult reused = results.decodeInCall(resultBytes, reuse);
            ClosureProcessResult uncached = results.decodeInCall(resultBytes, disabled);
            assertTrue(reuse.decodeHits() > 0, "The real historical result exercises repeated nested envelopes");
            assertTrue(reuse.encodeHits() > 0);
            assertTrue(reuse.verificationAttempts() < disabled.verificationAttempts());
            assertSelection(fixture, reused.storageInputSnapshot());
            assertSelection(fixture, reused.rootedProjection().resultingSnapshot());
            assertArrayEquals(resultBytes, results.encodeInCall(reused, disabled));
            assertArrayEquals(resultBytes, results.encodeInCall(uncached, reuse));
            assertArrayEquals(failedBytes, results.encodeInCall(results.decodeInCall(failedBytes, reuse), disabled));
        }
        long resultHits = snapshots.acceptedByteStatistics().hits;
        try (SnapshotStorageCall warm = snapshots.newCall();
                SnapshotStorageCall disabled = new SnapshotStorageCall(snapshots, 0, 0)) {
            ClosureProcessResult warmResult = results.decodeInCall(resultBytes, warm);
            assertNotSame(restoredResult.storageInputSnapshot(), warmResult.storageInputSnapshot());
            assertSelection(fixture, warmResult.storageInputSnapshot());
            assertSelection(fixture, warmResult.rootedProjection().resultingSnapshot());
            assertArrayEquals(resultBytes, results.encodeInCall(warmResult, disabled));
        }
        assertTrue(snapshots.acceptedByteStatistics().hits > resultHits,
                "The complete independent-witness result exercises cross-call accepted bytes");
        long failureHits = snapshots.acceptedByteStatistics().hits;
        try (SnapshotStorageCall warm = snapshots.newCall();
                SnapshotStorageCall disabled = new SnapshotStorageCall(snapshots, 0, 0)) {
            ClosureProcessResult warmFailure = results.decodeInCall(failedBytes, warm);
            assertNotSame(restoredFailure.storageInputSnapshot(), warmFailure.storageInputSnapshot());
            assertEquals(restoredFailure.gasTraceIdentity(), warmFailure.gasTraceIdentity());
            assertEquals(restoredFailure.rejectedCharge().rejectedChargeIdentity(), warmFailure.rejectedCharge().rejectedChargeIdentity());
            assertArrayEquals(failedBytes, results.encodeInCall(warmFailure, disabled));
            rollback(restoredTight, warmFailure);
        }
        assertTrue(snapshots.acceptedByteStatistics().hits > failureHits, "The G-1 result uses accepted bytes without result reuse");
        // then: A11 still refers to D2 while the independently selected primary is D14.
        assertSelection(fixture, restoredSnapshot);
        assertSelection(fixture, restoredInput.snapshot());
        assertSelection(fixture, restoredResult.rootedProjection().resultingSnapshot());
        assertEquals(11L, restoredSnapshot.managedDocument(A).epoch());
        assertEquals(14L, restoredSnapshot.managedDocument(D).epoch());
        assertEquals(fixture.oldProof.managedDocument(D).blueId(),
                row(restoredSnapshot, A, "/d").expectedTargetBlueId());
        assertFalse(restoredSnapshot.graph().hasEdge(A, D));
        assertEquals(Collections.singletonList(ROOT), restoredResult.rootedProjection().ownedDocumentIds());
        assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, restoredFailure.status());
        assertNull(restoredFailure.rootedProjection());
        assertNotNull(restoredFailure.rejectedCharge());
        rollback(restoredTight, restoredFailure);
        assertArrayEquals(selectedBytes, snapshots.encode(restoredSnapshot));
        assertArrayEquals(invocationBytes, executions.encodeInvocation(restoredInput));
        assertArrayEquals(resultBytes, results.encode(restoredResult));
        assertArrayEquals(failedBytes, results.encode(restoredFailure));
        try (Runtime consumer = new Runtime()) {
            exact.forEach((id, node) -> consumer.exact.put(id, node.clone()));
            ClosureProcessResult repeated = complete(consumer.process(restoredInput));
            ClosureProcessResult repeatedFailure = consumer.process(restoredTight).processResult();
            assertArrayEquals(resultBytes, results.encode(repeated),
                    "Cold PROCESS must retain complete receipts, events, context, owners and gas trace");
            assertArrayEquals(failedBytes, results.encode(repeatedFailure),
                    "The same tight budget must preserve the rejected charge and rollback trace");
        }
    }

    @Test
    void prospectiveBirthRetryPreservesSelectionAndReplaysTheOwnerPrefixOnlyOnce() {
        // given
        try (Runtime runtime = new Runtime()) {
            Fixture f = mixed(runtime, true, false);
            ClosureInvocationInput input = invocation(f, select(f.input.snapshot(), D, f.newProof), 100_000L);
            // when
            ClosureAttemptResult suspended = runtime.process(input);
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, suspended.kind(),
                    suspended.isComplete() ? diagnostic(suspended.processResult()) : suspended.resourceDemands().toString());
            assertEquals(1, suspended.resourceDemands().size());
            ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) suspended.resourceDemands().get(0);
            ClosureInvocationInput retry = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(demand, BORN, f.born)));
            ClosureProcessResult result = complete(runtime.process(retry));
            // then
            assertEquals(ROOT, demand.sourceDocumentId());
            assertEquals("/children/new", demand.sourcePath());
            assertEquals(id(f.born), demand.suppliedValueBlueId());
            assertSame(input.rootedBinding().context, retry.rootedBinding().context);
            assertEquals(input.executionPolicy().identity(), retry.executionPolicy().identity());
            assertEquals(input.cause().causeIdentity(), retry.cause().causeIdentity());
            assertWitness(A, f.oldProof, retry.snapshot());
            assertWitness(D, f.newProof, retry.snapshot());
            assertWitness(A, f.oldProof, result.rootedProjection().resultingSnapshot());
            assertWitness(D, f.newProof, result.rootedProjection().resultingSnapshot());
            assertTrue(result.rootedProjection().ownedDocumentIds().containsAll(Arrays.asList(ROOT, BORN)));
            assertFalse(result.rootedProjection().ownedDocumentIds().contains(A));
            assertFalse(result.rootedProjection().ownedDocumentIds().contains(D));
            assertEquals(1L, ((Number) result.rootedProjection().resultingSnapshot().managedDocument(ROOT)
                    .document().getNode("/count").getValue()).longValue());
            assertTrue(result.rootedProjection().resultingSnapshot().managedDocument(BORN).initialized());
        }
    }

    private static Fixture mixed(Runtime runtime, boolean birth, boolean live) {
        Node older = initialized(new Node().name("D").properties("count", new Node().value(2L)));
        Node source = initialized(new Node().name("A").properties("count", new Node().value(11L))
                .properties("d", new Node().blueId(id(older))).contracts(new Node().properties("embedded", process("paths", "/d"))));
        Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(A, source); bodies.put(D, older);
        List<ManagedOccurrenceBinding> rows = Arrays.asList(runtime.binding(A, "/d", D, id(older), true, null),
                runtime.binding(D, "/retired", A, id(source), false, null));
        AffectedClosureSnapshot initial = initializedSnapshot(bodies, rows, A, 11L);
        ManagedDocumentSnapshot oldD = initial.managedDocument(D);
        AffectedClosureSnapshot old = ClosureEvidenceFactory.affectedClosure(initial.graphGeneration(),
                replace(initial.managedDocuments(), changed(oldD, oldD.document(), 2L, oldD.componentGeneration())),
                initial.occurrences(), initial.components(), initial.publicRootDocumentIds());
        Node newer = initialized(new Node().name("D").properties("count", new Node().value(14L)));
        AffectedClosureSnapshot next = proof(runtime, D, newer, A,
                initialized(new Node().name("independent proof A")), "/retired", 14L);
        return fixture(runtime, old, next, A, birth, live);
    }

    private static Fixture fixture(Runtime runtime, AffectedClosureSnapshot old, AffectedClosureSnapshot next,
            DocumentId anchor, boolean birth, boolean live) {
        return fixture(runtime, old, next, anchor, birth, live, true);
    }

    private static Fixture fixture(Runtime runtime, AffectedClosureSnapshot old, AffectedClosureSnapshot next,
            DocumentId anchor, boolean birth, boolean live, boolean pending) {
        Node born = new Node().name("born child").contracts(new Node()
                .properties("lifecycle", typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)));
        Node root = new Node().name("owner").properties("count", new Node().value(0L))
                .properties("read", new Node().blueId(old.managedDocument(anchor).blueId()))
                .properties("children", new Node().properties(Collections.emptyMap()))
                .contracts(new Node().properties("source", typed(id(CHANNEL)))
                        .properties("mutate", typed(id(HANDLER)).properties("channel", new Node().value("source"))));
        Node embedded = process("paths", "/read");
        if (birth) {
            root.properties("replacement", born.clone());
            embedded.properties("collectionPaths", new Node().items(Collections.singletonList(new Node().value("/children"))));
        }
        Map<DocumentId, Node> entryBodies = new LinkedHashMap<>();
        List<ManagedOccurrenceBinding> entryRows = new ArrayList<>();
        if (live) {
            Node child = initialized(new Node().name("live child"));
            root.properties("live", new Node().blueId(id(child)));
            embedded.properties("paths", new Node().items(Arrays.asList(new Node().value("/read"), new Node().value("/live"))));
            entryBodies.put(LIVE, child);
            entryRows.add(runtime.binding(ROOT, "/live", LIVE, id(child), true, null));
        }
        root.getContracts().properties("embedded", embedded);
        entryBodies.put(ROOT, initialized(root));
        AffectedClosureSnapshot entry = initializedSnapshot(entryBodies, entryRows, ROOT, 2L);
        Node event = new Node().name("update owner");
        ClosureInvocationInput base = ClosureEvidenceFactory.processClosure(entry,
                ClosureEvidenceFactory.externalCause(event, id(event), ExternalOrderKey.of(Collections.singletonList(300L)),
                        runtime.environment.externalOrderPolicyIdentity()),
                Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(ROOT), "source", "update", 0L)),
                ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "witness-selection"), runtime.environment)
                .withRootedContext(RootedProcessingContext.derive(entry, ROOT, Collections.singletonMap(ROOT, hash('c'))), hash('d'));
        List<ManagedDocumentSnapshot> documents = new ArrayList<>(entry.managedDocuments());
        for (ManagedDocumentSnapshot doc : old.managedDocuments()) documents.add(readOnly(doc));
        List<ManagedOccurrenceBinding> rows = new ArrayList<>(entryRows);
        rows.add(runtime.binding(ROOT, "/read", anchor, old.managedDocument(anchor).blueId(), false, pending ? 0L : null));
        rows.addAll(old.occurrences());
        for (AffectedClosureSnapshot proof : Arrays.asList(entry, old, next)) for (ManagedDocumentSnapshot doc : proof.managedDocuments())
            runtime.exact.put(doc.blueId(), doc.document());
        runtime.exact.put(id(event), event);
        ClosureInvocationInput input = ClosureEvidenceFactory.rootedReadExpansion(base, 1L, documents, rows, Collections.singletonList(old));
        return new Fixture(runtime, entry, input, old, next, born);
    }

    private static AffectedClosureSnapshot proof(Runtime runtime, DocumentId source, Node body,
            DocumentId target, Node targetBody, String path, long epoch) {
        Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(source, body); bodies.put(target, targetBody);
        return initializedSnapshot(bodies, Collections.singletonList(runtime.binding(source, path, target,
                id(targetBody), false, null)), source, epoch);
    }

    private static AffectedClosureSnapshot single(DocumentId id, long epoch) {
        return initializedSnapshot(Collections.singletonMap(id, initialized(new Node().name(id.value()))), Collections.emptyList(), id, epoch);
    }

    private static AffectedClosureSnapshot initializedSnapshot(Map<DocumentId, Node> bodies,
            List<ManagedOccurrenceBinding> rows, DocumentId root, long epoch) {
        AffectedClosureSnapshot raw = snapshot(bodies, rows, root);
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ManagedDocumentSnapshot doc : raw.managedDocuments()) documents.add(new ManagedDocumentSnapshot(doc.documentId(),
                doc.blueId(), doc.document(), true, false, doc.publicRoot(), epoch, doc.componentGeneration()));
        return ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(), documents, raw.occurrences(), raw.components(), raw.publicRootDocumentIds());
    }

    private static Node initialized(Node node) {
        Node result = node.clone();
        if (result.getContracts() == null) result.contracts(new Node());
        result.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                .properties("document", new Node().blueId(id(node))));
        return result;
    }

    private static AffectedClosureSnapshot select(AffectedClosureSnapshot before, DocumentId id, AffectedClosureSnapshot proof) {
        return ClosureEvidenceFactory.rootedWitnessSelection(before, Collections.singletonMap(id, proof));
    }

    private static ClosureInvocationInput invocation(Fixture f, AffectedClosureSnapshot state, long gas) {
        return ClosureEvidenceFactory.processClosure(state, f.input.cause(), f.input.directDeliveries(),
                ClosureEvidenceFactory.executionPolicy(gas, Collections.emptyMap(), "witness-selection"), f.runtime.environment)
                .withRootedContext(RootedProcessingContext.derive(state, ROOT,
                        Collections.singletonMap(ROOT, hash('c'))), hash('d'));
    }

    private static void assertSelection(Fixture f, AffectedClosureSnapshot selected) {
        assertWitness(A, f.oldProof, selected);
        assertWitness(D, f.newProof, selected);
        assertEquals(f.input.snapshot().publicRootDocumentIds(), selected.publicRootDocumentIds());
        assertEquals(f.input.snapshot().managedDocuments().size(), selected.managedDocuments().size());
        assertEquals(f.input.snapshot().graphGeneration(), selected.graphGeneration());
        assertEquals(rows(f.input.snapshot(), ROOT), rows(selected, ROOT));
        if (f.input.snapshot().contains(LIVE)) assertDocument(f.input.snapshot().managedDocument(LIVE), selected.managedDocument(LIVE));
        assertEquals(new LinkedHashSet<>(Arrays.asList(A, D)), selected.rootedWitnesses().sources());
        ClosureEvidenceVerifier.verifySnapshot(selected);
    }

    private static void assertWitness(DocumentId id, AffectedClosureSnapshot proof, AffectedClosureSnapshot actual) {
        assertDocument(readOnly(proof.managedDocument(id)), actual.managedDocument(id));
        assertEquals(rows(proof, id), rows(actual, id));
        assertTrue(actual.rootedWitnesses().sources().contains(id));
        assertFalse(actual.managedDocument(id).publicRoot());
    }

    private static void assertDocument(ManagedDocumentSnapshot expected, ManagedDocumentSnapshot actual) {
        assertNotNull(actual);
        assertEquals(expected.blueId(), actual.blueId()); assertEquals(expected.epoch(), actual.epoch());
        assertEquals(expected.componentGeneration(), actual.componentGeneration());
        assertEquals(expected.initialized(), actual.initialized()); assertEquals(expected.terminated(), actual.terminated());
        assertEquals(expected.publicRoot(), actual.publicRoot());
        assertEquals(NodeWireForm.get(expected.document()), NodeWireForm.get(actual.document()));
    }

    private static List<String> rows(AffectedClosureSnapshot state, DocumentId source) {
        List<String> rows = new ArrayList<>();
        for (ManagedOccurrenceBinding row : state.occurrences()) if (row.sourceDocumentId().equals(source)) rows.add(
                row.occurrenceIdentity() + ":" + row.bindingIdentity() + ":" + row.active() + ":" + row.pendingHistoricalEpoch()
                        + ":" + (row.pendingRepresentationCursor() == null ? null : row.pendingRepresentationCursor().identityValue()));
        Collections.sort(rows); return rows;
    }

    private static ManagedOccurrenceBinding row(AffectedClosureSnapshot state, DocumentId source, String path) {
        return state.occurrences().stream().filter(row -> row.sourceDocumentId().equals(source) && row.sourcePath().equals(path))
                .findFirst().orElseThrow(AssertionError::new);
    }

    private static ManagedDocumentSnapshot readOnly(ManagedDocumentSnapshot doc) {
        return new ManagedDocumentSnapshot(doc.documentId(), doc.blueId(), doc.document(), doc.initialized(), doc.terminated(), false,
                doc.epoch(), doc.componentGeneration());
    }

    private static ManagedDocumentSnapshot changed(ManagedDocumentSnapshot doc, Node body, long epoch, long generation) {
        return new ManagedDocumentSnapshot(doc.documentId(), doc.blueId(), body, doc.initialized(), doc.terminated(), doc.publicRoot(), epoch, generation);
    }

    private static List<ManagedDocumentSnapshot> replace(List<ManagedDocumentSnapshot> documents, ManagedDocumentSnapshot replacement) {
        List<ManagedDocumentSnapshot> result = new ArrayList<>();
        for (ManagedDocumentSnapshot doc : documents) result.add(doc.documentId().equals(replacement.documentId()) ? replacement : doc);
        return result;
    }

    private static AffectedClosureSnapshot copy(AffectedClosureSnapshot original) {
        List<ManagedDocumentSnapshot> documents = new ArrayList<>(original.managedDocuments()); Collections.reverse(documents);
        return ClosureEvidenceFactory.affectedClosure(original.graphGeneration(), documents,
                original.occurrences(), original.components(), original.publicRootDocumentIds());
    }

    private static AffectedClosureSnapshot asserted(AffectedClosureSnapshot original, List<ManagedDocumentSnapshot> documents,
            List<ManagedOccurrenceBinding> rows, List<ComponentSnapshot> components) {
        return new AffectedClosureSnapshot(original.closureIdentity(), original.graphGeneration(), documents, rows,
                original.occurrenceBindingSetIdentity(), components, original.publicRootDocumentIds());
    }

    private static ClosureProcessResult complete(ClosureAttemptResult attempt) {
        assertTrue(attempt.isComplete(), attempt.resourceDemands().toString());
        assertTrue(attempt.processResult().commits(), diagnostic(attempt.processResult()));
        assertNotNull(attempt.processResult().rootedProjection()); return attempt.processResult();
    }

    private static List<List<Object>> trace(ClosureProcessResult result) {
        List<List<Object>> trace = new ArrayList<>();
        for (GasTraceEntry e : result.gasTrace()) trace.add(Arrays.asList(e.sequence(), e.namespace(), e.counter(), e.quantity(),
                e.weight(), e.subtotal(), e.documentId(), e.scopePath(), e.activationGeneration(), e.componentGeneration(),
                e.contractKey(), e.logicalPath(), e.workOccurrenceId(), e.reason()));
        return trace;
    }

    private static final class Fixture {
        final Runtime runtime; final AffectedClosureSnapshot entry; final ClosureInvocationInput input;
        final AffectedClosureSnapshot oldProof; final AffectedClosureSnapshot newProof; final Node born;
        Fixture(Runtime runtime, AffectedClosureSnapshot entry, ClosureInvocationInput input,
                AffectedClosureSnapshot oldProof, AffectedClosureSnapshot newProof, Node born) {
            this.runtime = runtime; this.entry = entry; this.input = input;
            this.oldProof = oldProof; this.newProof = newProof; this.born = born;
        }
    }

    private static final class Runtime implements AutoCloseable {
        final Map<String, Node> exact = new LinkedHashMap<>();
        final DocumentProcessor owner = DocumentProcessor.builder()
                .registerContractProcessor(id(CHANNEL), CHANNEL, new SelectionChannelProcessor())
                .registerContractProcessor(id(HANDLER), HANDLER, new HandlerProcessor<SelectionHandler>() {
                    public Class<SelectionHandler> contractType() { return SelectionHandler.class; }
                    public void execute(SelectionHandler handler, ProcessorExecutionContext context) {
                        long count = ((Number) context.documentAt("/count").getValue()).longValue();
                        context.applyPatch(JsonPatch.replace("/count", new Node().value(count + 1L)));
                        if (context.documentContains("/replacement")) context.applyPatch(JsonPatch.add("/children/new", context.documentAt("/replacement")));
                    }
                }).nodeProvider(id -> exact.containsKey(id) ? Collections.singletonList(exact.get(id).clone()) : Collections.emptyList()).build();
        final ClosureEnvironment environment = ClosureEvidenceFactory.environment(owner, hash('a'),
                RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY, "witness-selection-lineage", "witness-selection-binding",
                "witness-selection-provider", "witness-selection-order", "witness-selection-limits",
                blue.language.processor.GasSchedule.contracts10().portableLimits());
        ManagedOccurrenceBinding binding(DocumentId source, String path, DocumentId target, String blueId, boolean active, Long pending) {
            return ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), source,
                    ScopeAddress.embedded(path, 1L), target, blueId, active, pending);
        }
        ClosureAttemptResult process(ClosureInvocationInput input) {
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner)) { return contracts.processClosure(input); }
        }
        public void close() { owner.close(); }
    }

    public static final class SelectionHandler extends HandlerContract { }
    public static final class SelectionChannel extends ChannelContract { }
    private static final class SelectionChannelProcessor implements ChannelProcessor<SelectionChannel> {
        public Class<SelectionChannel> contractType() { return SelectionChannel.class; }
        public ExternalChannelSubscriptionFunctions<SelectionChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<SelectionChannel>() {
                public List<String> channelKeys(SelectionChannel channel) { return Collections.singletonList("update"); }
                public boolean preselects(SelectionChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                public boolean accepts(SelectionChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                public String logicalDeliveryKey(SelectionChannel channel, Node event, Node payload, ExternalChannelFunctionContext context) { return "update"; }
                public String checkpointDomainDiscriminator(SelectionChannel channel) { return "witness-selection"; }
            };
        }
    }
}
