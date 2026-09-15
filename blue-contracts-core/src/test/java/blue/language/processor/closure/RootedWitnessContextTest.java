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

/** Exact historical witnesses may select different versions of the same lineage. */
final class RootedWitnessContextTest {
    private static final DocumentId ROOT = new DocumentId("B");
    private static final DocumentId A = new DocumentId("A");
    private static final DocumentId D = new DocumentId("D");
    private static final DocumentId PEER = new DocumentId("S");
    private static final DocumentId BORN = new DocumentId("new-child");
    private static final Node CHANNEL = new Node().name("Witness context test channel");
    private static final Node HANDLER = new Node().name("Witness context test handler");

    @Test
    void independentImmutableTargetDoesNotReplaceTheSourcesAuthenticatedOlderReference() {
        try (Runtime runtime = new Runtime()) {
            Expansion values = mixed(runtime, false);
            ClosureInvocationInput expanded = values.prepare(values.proofs);
            assertWitnessesUnchanged(values, expanded.snapshot());
            assertFalse(expanded.snapshot().graph().hasEdge(A, D));
            assertTrue(row(expanded.snapshot(), A, "/d").active());
            assertNotEquals(row(expanded.snapshot(), A, "/d").expectedTargetBlueId(),
                    expanded.snapshot().managedDocument(D).blueId());

            // Proof-list order and Java snapshot identity are not authority operands.
            List<AffectedClosureSnapshot> reversed = new ArrayList<>(values.proofs);
            Collections.reverse(reversed);
            List<AffectedClosureSnapshot> equivalent = new ArrayList<>();
            for (AffectedClosureSnapshot proof : reversed) equivalent.add(copyProof(proof));
            for (List<AffectedClosureSnapshot> proofs : Arrays.asList(reversed, equivalent)) {
                ClosureInvocationInput reordered = values.prepare(proofs);
                assertEquals(expanded.invocationIdentity(), reordered.invocationIdentity());
                assertEquals(expanded.snapshot().closureIdentity(), reordered.snapshot().closureIdentity());
                assertEquals(expanded.snapshot().occurrenceBindingSetIdentity(),
                        reordered.snapshot().occurrenceBindingSetIdentity());
                assertWitnessesUnchanged(values, reordered.snapshot());
            }
        }
    }

    @Test
    void anOlderTargetInsideAnotherProofCannotAuthenticateTheSelectedNewerTarget() {
        try (Runtime runtime = new Runtime()) {
            Expansion values = mixed(runtime, false);
            assertThrows(IllegalArgumentException.class,
                    () -> values.prepare(Collections.singletonList(values.proofs.get(0))));
            List<ManagedDocumentSnapshot> forged = new ArrayList<>(values.documents);
            for (int index = 0; index < forged.size(); index++) {
                ManagedDocumentSnapshot selected = forged.get(index);
                if (selected.documentId().equals(D)) {
                    forged.set(index, new ManagedDocumentSnapshot(D, selected.blueId(),
                            selected.document().properties("count", new Node().value(99L)),
                            selected.initialized(), selected.terminated(), selected.publicRoot(),
                            selected.epoch(), selected.componentGeneration()));
                }
            }
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(
                    values.original, 1L, forged, values.rows, values.proofs));

            List<ManagedOccurrenceBinding> forgedRows = new ArrayList<>();
            List<ManagedOccurrenceBinding> retiredRows = new ArrayList<>();
            for (ManagedOccurrenceBinding binding : values.rows) {
                boolean historicalSource = binding.sourceDocumentId().equals(A);
                forgedRows.add(historicalSource
                        ? runtime.binding(A, binding.sourcePath(), D,
                                values.proofs.get(1).managedDocument(D).blueId(), true, null)
                        : binding);
                retiredRows.add(historicalSource
                        ? runtime.binding(A, binding.sourcePath(), D, binding.expectedTargetBlueId(), false, null)
                        : binding);
            }
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(
                    values.original, 1L, values.documents, forgedRows, values.proofs));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(
                    values.original, 1L, values.documents, retiredRows, values.proofs));
            List<ManagedOccurrenceBinding> missingRows = new ArrayList<>(values.rows);
            missingRows.removeIf(binding -> binding.sourceDocumentId().equals(A));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(
                    values.original, 1L, values.documents, missingRows, values.proofs));
        }
    }

    @Test
    void equalTargetBlueIdDoesNotRequireMatchingHistoricalComponentGeneration() {
        try (Runtime runtime = new Runtime()) {
            AffectedClosureSnapshot sourceProof = mixed(runtime, false).proofs.get(0);
            ManagedDocumentSnapshot older = sourceProof.managedDocument(D);
            ManagedDocumentSnapshot independentlySelected = new ManagedDocumentSnapshot(D, older.blueId(),
                    older.document(), older.initialized(), older.terminated(), true,
                    older.epoch(), older.componentGeneration() + 1L);
            AffectedClosureSnapshot targetProof = ClosureEvidenceFactory.affectedClosure(1L,
                    Collections.singletonList(independentlySelected), Collections.emptyList(),
                    Collections.singletonList(ClosureEvidenceFactory.acyclicComponent(independentlySelected)),
                    Collections.singletonList(D));
            Expansion values = expansion(runtime, Arrays.asList(sourceProof, targetProof),
                    Arrays.asList(sourceProof.managedDocument(A), independentlySelected), false);
            ClosureInvocationInput expanded = values.prepare(values.proofs);
            assertWitnessesUnchanged(values, expanded.snapshot());
            assertEquals(older.blueId(), expanded.snapshot().managedDocument(D).blueId());
            assertNotEquals(older.componentGeneration(), expanded.snapshot().managedDocument(D).componentGeneration());
            assertTrue(expanded.snapshot().graph().hasEdge(A, D));
            assertTrue(expanded.snapshot().graph().calculating(row(expanded.snapshot(), A, "/d")));
        }
    }

    @Test
    void aSameContextImmutableCycleRetainsItsCompleteCyclicComponent() {
        assertCyclicWitness(false);
    }

    @Test
    void aSameContextImmutableSelfCycleRetainsItsCompleteCyclicComponent() {
        assertCyclicWitness(true);
    }

    @Test
    void processFinalizationAndRetainedResultPreserveIndependentWitnessContexts() {
        try (Runtime runtime = new Runtime()) {
            Expansion values = mixed(runtime, false);
            ClosureInvocationInput expanded = values.prepare(values.proofs);
            ClosureProcessResult result = complete(runtime.process(expanded));
            assertEquals(Collections.singletonList(ROOT), result.rootedProjection().ownedDocumentIds());
            assertEquals(1L, ((Number) result.rootedProjection().resultingSnapshot()
                    .managedDocument(ROOT).document().getNode("/count").getValue()).longValue());
            assertEquals(expanded.snapshot().graphGeneration(), result.graphGeneration());
            assertWitnessesUnchanged(values, result.rootedProjection().resultingSnapshot());
            assertTrue(result.managedTransitionReceipts().stream().noneMatch(receipt ->
                    receipt.documentId().equals(A) || receipt.documentId().equals(D)));
            long rootEpoch = result.rootedProjection().resultingSnapshot().managedDocument(ROOT).epoch();
            AffectedClosureSnapshot retained = ClosureEvidenceFactory.rootedRetainedSnapshot(
                    result, Collections.singletonMap(ROOT, rootEpoch));
            assertWitnessesUnchanged(values, retained);

            ClosureInvocationInput tightOriginal = ClosureEvidenceFactory.processClosure(
                    values.original.snapshot(), values.original.cause(), values.original.directDeliveries(),
                    ClosureEvidenceFactory.executionPolicy(result.totalGas() - 1L,
                            Collections.emptyMap(), "witness-context"), runtime.environment)
                    .withRootedContext(values.original.rootedBinding().context, hash('d'));
            ClosureInvocationInput tight = ClosureEvidenceFactory.rootedReadExpansion(tightOriginal,
                    1L, values.documents, values.rows, values.proofs);
            ClosureAttemptResult failedAttempt = runtime.process(tight);
            assertTrue(failedAttempt.isComplete(), failedAttempt.resourceDemands().toString());
            ClosureProcessResult failed = failedAttempt.processResult();
            rollback(tight, failed);
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.status());
            assertNotNull(failed.rejectedCharge());
            assertNull(failed.rootedProjection());
        }
    }

    @Test
    void aLiveJoinReleasesWitnessRolesAndRestoresOrdinaryRebinding() {
        try (Runtime runtime = new Runtime()) {
            Expansion values = mixed(runtime, false);
            ClosureInvocationInput expanded = values.prepare(values.proofs);
            List<ManagedOccurrenceBinding> joined = new ArrayList<>();
            for (ManagedOccurrenceBinding binding : expanded.snapshot().occurrences()) {
                joined.add(binding.sourceDocumentId().equals(ROOT) && binding.targetDocumentId().equals(A)
                        ? runtime.binding(ROOT, binding.sourcePath(), A, binding.expectedTargetBlueId(), true, null)
                        : binding);
            }
            joined.add(runtime.binding(A, "/return", ROOT,
                    expanded.snapshot().managedDocument(ROOT).blueId(), true, null));
            RootedWitnessFrame frame = new RootedWitnessFrame(expanded);
            RootedWitnessFrame.State released = frame.at(joined, expanded.snapshot().graph().documentIds());
            assertTrue(released.sources().isEmpty(),
                    "The owner SCC and its active forward descendants must not remain frozen witnesses");

            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            Map<DocumentId, Long> generations = new LinkedHashMap<>();
            for (ManagedDocumentSnapshot document : expanded.snapshot().managedDocuments()) {
                bodies.put(document.documentId(), document.document());
                generations.put(document.documentId(), document.componentGeneration());
            }
            bodies.get(A).properties("return", new Node().blueId(expanded.snapshot().managedDocument(ROOT).blueId()));
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                    new ComponentFinalizationInput(expanded.snapshot().graph(), generations, bodies, joined, released));
            assertTrue(finalized.finalizedGraph().hasEdge(A, D));
            assertEquals(expanded.snapshot().managedDocument(D).blueId(),
                    finalized.document(A).document().getNode("/d").getBlueId());
            assertNotEquals(expanded.snapshot().managedDocument(A).blueId(), finalized.document(A).blueId());
            assertTrue(new SccPartitioner().partition(finalized.finalizedGraph()).stream()
                    .anyMatch(component -> component.containsAll(Arrays.asList(ROOT, A))));
            RootedWitnessFrame.State split = frame.at(expanded.snapshot().occurrences(), bodies.keySet());
            assertTrue(split.sources().isEmpty(), "A later split cannot refreeze already calculating work");
        }
    }

    @Test
    void prospectiveBirthRetryKeepsWitnessContextsThroughReplayAndPublication() {
        try (Runtime runtime = new Runtime()) {
            Expansion values = mixed(runtime, true);
            ClosureInvocationInput expanded = values.prepare(values.proofs);
            ClosureAttemptResult suspended = runtime.process(expanded);
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, suspended.kind(),
                    suspended.isComplete() ? diagnostic(suspended.processResult()) : suspended.resourceDemands().toString());
            assertNull(suspended.processResult());
            assertEquals(1, suspended.resourceDemands().size());
            assertTrue(suspended.resourceDemands().get(0) instanceof ManagedOccurrenceEvidenceDemand);
            ManagedOccurrenceEvidenceDemand demand =
                    (ManagedOccurrenceEvidenceDemand) suspended.resourceDemands().get(0);
            assertEquals(ROOT, demand.sourceDocumentId());
            assertEquals("/children/new", demand.sourcePath());
            assertEquals(id(values.born), demand.suppliedValueBlueId());

            ClosureInvocationInput retry = ClosureEvidenceFactory.withProspectiveBirths(expanded,
                    Collections.singletonList(new ManagedDocumentBirth(demand, BORN, values.born)));
            assertSame(expanded.rootedBinding().context, retry.rootedBinding().context);
            assertEquals(expanded.cause().causeIdentity(), retry.cause().causeIdentity());
            assertEquals(expanded.executionPolicy().identity(), retry.executionPolicy().identity());
            assertEquals(expanded.snapshot().managedDocument(ROOT).blueId(),
                    retry.snapshot().managedDocument(ROOT).blueId());
            assertWitnessesUnchanged(values, retry.snapshot());
            assertNull(expanded.snapshot().managedDocument(BORN));
            assertFalse(retry.snapshot().managedDocument(BORN).initialized());

            ClosureProcessResult result = complete(runtime.process(retry));
            assertWitnessesUnchanged(values, result.rootedProjection().resultingSnapshot());
            assertTrue(result.rootedProjection().ownedDocumentIds().containsAll(Arrays.asList(ROOT, BORN)));
            assertFalse(result.rootedProjection().ownedDocumentIds().contains(A));
            assertFalse(result.rootedProjection().ownedDocumentIds().contains(D));
            assertTrue(result.rootedProjection().resultingSnapshot().managedDocument(BORN).initialized());
            assertEquals(1L, ((Number) result.rootedProjection().resultingSnapshot()
                    .managedDocument(ROOT).document().getNode("/count").getValue()).longValue(),
                    "The suspended prefix is replayed, not published twice");
            assertTrue(result.managedTransitionReceipts().stream().noneMatch(receipt ->
                    receipt.documentId().equals(A) || receipt.documentId().equals(D)));
        }
    }

    private static void assertCyclicWitness(boolean selfCycle) {
        try (Runtime runtime = new Runtime()) {
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            Node first = initialized(new Node().name("immutable A")
                    .properties("peer", new Node().value("before finalization")));
            bodies.put(A, first);
            if (!selfCycle) bodies.put(PEER, initialized(new Node().name("immutable peer")
                    .properties("peer", new Node().value("before finalization"))));
            List<ManagedOccurrenceBinding> rows = new ArrayList<>();
            DocumentId target = selfCycle ? A : PEER;
            rows.add(runtime.binding(A, "/peer", target, id(bodies.get(target)), true, null));
            if (!selfCycle) rows.add(runtime.binding(PEER, "/peer", A, id(first), true, null));
            AffectedClosureSnapshot proof = initializedSnapshot(bodies, rows, A,
                    selfCycle ? Collections.singletonMap(A, 11L) : epochs(A, 11L, PEER, 11L));
            Expansion values = expansion(runtime, Collections.singletonList(proof),
                    proof.managedDocuments(), false);
            ClosureInvocationInput expanded = values.prepare(values.proofs);
            assertWitnessesUnchanged(values, expanded.snapshot());
            ComponentSnapshot original = proof.components().get(0);
            ComponentSnapshot actual = expanded.snapshot().components().stream()
                    .filter(component -> component.orderedMemberDocumentIds().contains(A))
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals(ComponentKind.CYCLIC, actual.kind());
            assertEquals(original.orderedMemberDocumentIds(), actual.orderedMemberDocumentIds());
            assertEquals(original.orderedMemberBlueIds(), actual.orderedMemberBlueIds());
            assertEquals(original.componentStateIdentity(), actual.componentStateIdentity());
            assertEquals(original.componentGeneration(), actual.componentGeneration());
            assertTrue(expanded.snapshot().graph().hasEdge(A, target));
            if (!selfCycle) assertTrue(expanded.snapshot().graph().hasEdge(PEER, A));
            assertNotNull(actual.completeCyclicProof());
        }
    }

    private static Expansion mixed(Runtime runtime, boolean birth) {
        Node older = initialized(new Node().name("D").properties("count", new Node().value(2L)));
        Node newer = initialized(new Node().name("D").properties("count", new Node().value(14L)));
        Node source = initialized(new Node().name("A").properties("count", new Node().value(11L))
                .properties("d", new Node().blueId(id(older)))
                .contracts(new Node().properties("embedded", process("paths", "/d"))));
        Map<DocumentId, Node> oldBodies = new LinkedHashMap<>();
        oldBodies.put(A, source); oldBodies.put(D, older);
        AffectedClosureSnapshot first = initializedSnapshot(oldBodies,
                Collections.singletonList(runtime.binding(A, "/d", D, id(older), true, null)),
                A, epochs(A, 11L, D, 2L));
        AffectedClosureSnapshot second = initializedSnapshot(Collections.singletonMap(D, newer),
                Collections.emptyList(), D, Collections.singletonMap(D, 14L));
        return expansion(runtime, Arrays.asList(first, second),
                Arrays.asList(first.managedDocument(A), second.managedDocument(D)), birth);
    }

    private static Expansion expansion(Runtime runtime, List<AffectedClosureSnapshot> proofs,
            List<ManagedDocumentSnapshot> selected, boolean birth) {
        Node root = new Node().name("owner").properties("count", new Node().value(0L))
                .properties("children", new Node().properties(Collections.emptyMap()))
                .contracts(new Node().properties("source", typed(id(CHANNEL)))
                        .properties("mutate", typed(id(HANDLER)).properties("channel", new Node().value("source"))));
        Node born = new Node().name("born child").contracts(new Node()
                .properties("lifecycle", typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)));
        Node embedded = new Node().type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED));
        List<Node> readPaths = new ArrayList<>();
        if (birth) {
            root.properties("replacement", born.clone());
            embedded.properties("collectionPaths", new Node().items(Collections.singletonList(new Node().value("/children"))));
        }
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
        List<ManagedDocumentSnapshot> witnesses = new ArrayList<>();
        for (ManagedDocumentSnapshot document : selected) {
            String path = "/read" + document.documentId().value();
            root.properties(path.substring(1), new Node().blueId(document.blueId()));
            readPaths.add(new Node().value(path));
            bindings.add(runtime.binding(ROOT, path, document.documentId(), document.blueId(), false, 0L));
            witnesses.add(readOnly(document));
            AffectedClosureSnapshot sourceProof = proofs.stream()
                    .filter(proof -> proof.managedDocument(document.documentId()) != null
                            && proof.managedDocument(document.documentId()).blueId().equals(document.blueId()))
                    .findFirst().orElseThrow(AssertionError::new);
            for (ManagedOccurrenceBinding row : sourceProof.occurrences()) {
                if (row.sourceDocumentId().equals(document.documentId())) bindings.add(row);
            }
        }
        embedded.properties("paths", new Node().items(readPaths));
        root.getContracts().properties("embedded", embedded);
        root = initialized(root);
        AffectedClosureSnapshot entry = initializedSnapshot(Collections.singletonMap(ROOT, root),
                Collections.emptyList(), ROOT, Collections.singletonMap(ROOT, 2L));
        Node event = new Node().name("update owner");
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, id(event),
                ExternalOrderKey.of(Collections.singletonList(300L)), runtime.environment.externalOrderPolicyIdentity());
        ClosureInvocationInput original = ClosureEvidenceFactory.processClosure(entry, cause,
                Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(ROOT), "source", "update", 0L)),
                ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "witness-context"), runtime.environment)
                .withRootedContext(RootedProcessingContext.derive(entry, ROOT,
                        Collections.singletonMap(ROOT, hash('c'))), hash('d'));
        List<ManagedDocumentSnapshot> documents = new ArrayList<>(entry.managedDocuments());
        documents.addAll(witnesses);
        for (AffectedClosureSnapshot proof : proofs) {
            for (ManagedDocumentSnapshot document : proof.managedDocuments()) runtime.exact.put(document.blueId(), document.document());
        }
        runtime.exact.put(id(root), root);
        runtime.exact.put(id(event), event);
        return new Expansion(original, documents, bindings, proofs, witnesses, born);
    }

    private static void assertWitnessesUnchanged(Expansion values, AffectedClosureSnapshot snapshot) {
        assertNotNull(snapshot.rootedWitnesses());
        LinkedHashSet<DocumentId> expectedSources = new LinkedHashSet<>();
        for (ManagedDocumentSnapshot expected : values.witnesses) {
            expectedSources.add(expected.documentId());
            ManagedDocumentSnapshot actual = snapshot.managedDocument(expected.documentId());
            assertNotNull(actual);
            assertEquals(expected.blueId(), actual.blueId());
            assertEquals(expected.epoch(), actual.epoch());
            assertEquals(expected.componentGeneration(), actual.componentGeneration());
            assertEquals(expected.initialized(), actual.initialized());
            assertEquals(expected.terminated(), actual.terminated());
            assertFalse(actual.publicRoot());
            assertEquals(NodeWireForm.get(expected.document()), NodeWireForm.get(actual.document()));
            assertEquals(values.rows.stream().filter(binding -> binding.sourceDocumentId().equals(expected.documentId())).count(),
                    snapshot.occurrences().stream().filter(binding -> binding.sourceDocumentId().equals(expected.documentId())).count());
            for (ManagedOccurrenceBinding binding : values.rows) {
                if (binding.sourceDocumentId().equals(expected.documentId())) {
                    ManagedOccurrenceBinding actualRow = row(snapshot, expected.documentId(), binding.sourcePath());
                    assertEquals(binding.occurrenceIdentity(), actualRow.occurrenceIdentity());
                    assertEquals(binding.bindingIdentity(), actualRow.bindingIdentity());
                    assertEquals(binding.active(), actualRow.active());
                    assertEquals(binding.pendingHistoricalEpoch(), actualRow.pendingHistoricalEpoch());
                    assertEquals(binding.pendingRepresentationCursor(), actualRow.pendingRepresentationCursor());
                }
            }
        }
        assertEquals(expectedSources, snapshot.rootedWitnesses().sources());
        ClosureEvidenceVerifier.verifySnapshot(snapshot);
    }

    private static ManagedOccurrenceBinding row(AffectedClosureSnapshot snapshot, DocumentId source, String path) {
        return snapshot.occurrences().stream().filter(row -> row.sourceDocumentId().equals(source)
                && row.sourcePath().equals(path)).findFirst().orElseThrow(AssertionError::new);
    }

    private static ClosureProcessResult complete(ClosureAttemptResult attempt) {
        assertTrue(attempt.isComplete(), attempt.resourceDemands().toString());
        ClosureProcessResult result = attempt.processResult();
        assertTrue(result.commits(), diagnostic(result));
        assertNotNull(result.rootedProjection());
        return result;
    }

    private static Node initialized(Node node) {
        Node result = node.clone();
        if (result.getContracts() == null) result.contracts(new Node());
        result.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                .properties("document", new Node().blueId(id(node))));
        return result;
    }

    private static AffectedClosureSnapshot initializedSnapshot(Map<DocumentId, Node> bodies,
            List<ManagedOccurrenceBinding> rows, DocumentId root, Map<DocumentId, Long> epochs) {
        AffectedClosureSnapshot raw = snapshot(bodies, rows, root);
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ManagedDocumentSnapshot document : raw.managedDocuments()) documents.add(new ManagedDocumentSnapshot(
                document.documentId(), document.blueId(), document.document(), true, false, document.publicRoot(),
                epochs.get(document.documentId()), document.componentGeneration()));
        return ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(), documents, raw.occurrences(),
                raw.components(), raw.publicRootDocumentIds());
    }

    private static ManagedDocumentSnapshot readOnly(ManagedDocumentSnapshot document) {
        return new ManagedDocumentSnapshot(document.documentId(), document.blueId(), document.document(),
                document.initialized(), document.terminated(), false, document.epoch(), document.componentGeneration());
    }

    private static Map<DocumentId, Long> epochs(DocumentId first, long firstEpoch, DocumentId second, long secondEpoch) {
        Map<DocumentId, Long> epochs = new LinkedHashMap<>();
        epochs.put(first, firstEpoch);
        epochs.put(second, secondEpoch);
        return epochs;
    }

    private static AffectedClosureSnapshot copyProof(AffectedClosureSnapshot proof) {
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ManagedDocumentSnapshot document : proof.managedDocuments()) documents.add(new ManagedDocumentSnapshot(
                document.documentId(), document.blueId(), document.document(), document.initialized(), document.terminated(),
                document.publicRoot(), document.epoch(), document.componentGeneration()));
        return new AffectedClosureSnapshot(proof.closureIdentity(), proof.graphGeneration(), documents,
                proof.occurrences(), proof.occurrenceBindingSetIdentity(), proof.components(), proof.publicRootDocumentIds());
    }

    private static final class Expansion {
        final ClosureInvocationInput original;
        final List<ManagedDocumentSnapshot> documents;
        final List<ManagedOccurrenceBinding> rows;
        final List<AffectedClosureSnapshot> proofs;
        final List<ManagedDocumentSnapshot> witnesses;
        final Node born;

        Expansion(ClosureInvocationInput original, List<ManagedDocumentSnapshot> documents,
                List<ManagedOccurrenceBinding> rows, List<AffectedClosureSnapshot> proofs,
                List<ManagedDocumentSnapshot> witnesses, Node born) {
            this.original = original; this.documents = documents; this.rows = rows;
            this.proofs = proofs; this.witnesses = witnesses; this.born = born;
        }

        ClosureInvocationInput prepare(List<AffectedClosureSnapshot> sourceProofs) {
            return ClosureEvidenceFactory.rootedReadExpansion(original, 1L, documents, rows, sourceProofs);
        }
    }

    private static final class Runtime implements AutoCloseable {
        final Map<String, Node> exact = new LinkedHashMap<>();
        final DocumentProcessor owner = DocumentProcessor.builder()
                .registerContractProcessor(id(CHANNEL), CHANNEL, new WitnessChannelProcessor())
                .registerContractProcessor(id(HANDLER), HANDLER, new HandlerProcessor<WitnessHandler>() {
                    public Class<WitnessHandler> contractType() { return WitnessHandler.class; }
                    public void execute(WitnessHandler handler, ProcessorExecutionContext context) {
                        long count = ((Number) context.documentAt("/count").getValue()).longValue();
                        context.applyPatch(JsonPatch.replace("/count", new Node().value(count + 1L)));
                        if (context.documentContains("/replacement"))
                            context.applyPatch(JsonPatch.add("/children/new", context.documentAt("/replacement")));
                    }
                }).nodeProvider(id -> exact.containsKey(id)
                        ? Collections.singletonList(exact.get(id).clone()) : Collections.emptyList()).build();
        final ClosureEnvironment environment = ClosureEvidenceFactory.environment(owner, hash('a'),
                RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY, "witness-context-lineage", "witness-context-binding",
                "witness-context-provider", "witness-context-order", "witness-context-limits",
                blue.language.processor.GasSchedule.contracts10().portableLimits());

        ManagedOccurrenceBinding binding(DocumentId source, String path, DocumentId target,
                String blueId, boolean active, Long pending) {
            return ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), source,
                    ScopeAddress.embedded(path, 1L), target, blueId, active, pending);
        }

        ClosureAttemptResult process(ClosureInvocationInput input) {
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
                return contracts.processClosure(input);
            }
        }

        public void close() { owner.close(); }
    }

    public static final class WitnessHandler extends HandlerContract { }
    public static final class WitnessChannel extends ChannelContract { }
    private static final class WitnessChannelProcessor implements ChannelProcessor<WitnessChannel> {
        public Class<WitnessChannel> contractType() { return WitnessChannel.class; }
        public ExternalChannelSubscriptionFunctions<WitnessChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<WitnessChannel>() {
                public List<String> channelKeys(WitnessChannel channel) { return Collections.singletonList("update"); }
                public boolean preselects(WitnessChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                public boolean accepts(WitnessChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                public String logicalDeliveryKey(WitnessChannel channel, Node event, Node payload,
                        ExternalChannelFunctionContext context) { return "update"; }
                public String checkpointDomainDiscriminator(WitnessChannel channel) { return "witness-context"; }
            };
        }
    }
}
