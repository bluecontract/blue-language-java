package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;
import java.util.*;
import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Augmentation is a read-evidence operation, never a new owner or business operation. */
final class RootedInputExpansionTest {
    private static final DocumentId A = new DocumentId("A");
    private static final DocumentId B = new DocumentId("B");

    @Test void exactAdditionalReadEvidenceKeepsTheOriginalContextAndMeter() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput original = input(fixture, single(0), 100_000L, 1L);
            RootedProcessingContext context = RootedProcessingContext.derive(original.snapshot(), A, Collections.singletonMap(A, hash('a')));
            ClosureInvocationInput rooted = original.withRootedContext(context, hash('b'));
            ClosureInvocationInput expanded = input(fixture, expanded(0, false), 100_000L, 1L).withRootedReadExpansionOf(rooted);
            assertSame(rooted.rootedBinding(), expanded.rootedBinding());
            assertSame(context, expanded.rootedBinding().context);
            assertEquals(original.invocationIdentity(), expanded.rootedBinding().entryInvocationIdentity);
            assertEquals(Collections.singletonList(A), context.entryOwners());
            assertEquals(original.executionPolicy().identity(), expanded.executionPolicy().identity());
            assertThrows(IllegalArgumentException.class, () -> expanded.withRootedReadExpansionOf(rooted));
        }
    }

    @Test void cannotChangeCauseOrBudgetWhileRetainingRootedAuthority() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput original = rooted(fixture);
            assertThrows(IllegalArgumentException.class, () -> input(fixture, expanded(0, false), 100_001L, 1L)
                    .withRootedReadExpansionOf(original));
            assertThrows(IllegalArgumentException.class, () -> input(fixture, expanded(0, false), 100_000L, 2L)
                    .withRootedReadExpansionOf(original));
        }
    }

    @Test void cannotRewriteAnOriginalDocumentOrActivateItsEdge() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput original = rooted(fixture);
            assertThrows(IllegalArgumentException.class, () -> input(fixture, expanded(9, false), 100_000L, 1L)
                    .withRootedReadExpansionOf(original));
            assertThrows(IllegalArgumentException.class, () -> input(fixture, expanded(0, true), 100_000L, 1L)
                    .withRootedReadExpansionOf(original));
        }
    }

    @Test void cannotMintAnOwnerOrBorrowAnUnrootedOperation() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureInvocationInput original = rooted(fixture);
            AffectedClosureSnapshot graph = expanded(0, false);
            List<ManagedDocumentSnapshot> allPublic = new ArrayList<>();
            graph.managedDocuments().forEach(d -> allPublic.add(new ManagedDocumentSnapshot(d.documentId(), d.blueId(),
                    d.document(), d.initialized(), d.terminated(), true, d.epoch(), d.componentGeneration())));
            AffectedClosureSnapshot forged = ClosureEvidenceFactory.affectedClosure(graph.graphGeneration(), allPublic, graph.occurrences(),
                    graph.components(), Arrays.asList(A, B));
            assertThrows(IllegalArgumentException.class, () -> input(fixture, forged, 100_000L, 1L).withRootedReadExpansionOf(original));
            assertThrows(IllegalArgumentException.class, () -> input(fixture, graph, 100_000L, 1L)
                    .withRootedReadExpansionOf(input(fixture, single(0), 100_000L, 1L)));
        }
    }

    @Test void cannotActivateAnAddedEdgeEvenWhenEveryOriginalByteIsUnchanged() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            Node b = new Node().name("B");
            Node a = new Node().name("A").properties("child", new Node().blueId(id(b)));
            AffectedClosureSnapshot entry = snapshot(Collections.singletonMap(A, a), Collections.emptyList(), A);
            ClosureInvocationInput base = input(fixture, entry, 100_000L, 1L);
            ClosureInvocationInput original = base.withRootedContext(RootedProcessingContext.derive(entry, A,
                    Collections.singletonMap(A, hash('a'))), hash('b'));
            Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(A, a); bodies.put(B, b);
            ManagedOccurrenceBinding edge = ManagedOccurrenceBinding.derived(hash('a'), A,
                    ScopeAddress.embedded("/child", 1L), B, id(b), true, null);
            ClosureInvocationInput expanded = input(fixture, snapshot(bodies, Collections.singletonList(edge), A), 100_000L, 1L);
            assertEquals(entry.managedDocument(A).blueId(), expanded.snapshot().managedDocument(A).blueId());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> expanded.withRootedReadExpansionOf(original));
            assertEquals("Read evidence cannot activate an edge from an original document", failure.getMessage());
        }
    }

    private static ClosureInvocationInput rooted(CompositionCampaignFixture fixture) {
        ClosureInvocationInput original = input(fixture, single(0), 100_000L, 1L);
        return original.withRootedContext(RootedProcessingContext.derive(original.snapshot(), A,
                Collections.singletonMap(A, hash('a'))), hash('b'));
    }

    @Test void completeHistoricalProofRetainsAnOlderViewOfTheCalculatingLineage() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            HistoricalAlias values = historicalAlias(fixture);
            ClosureInvocationInput expanded = ClosureEvidenceFactory.rootedReadExpansion(values.original, 1L,
                    values.documents, values.rows, Collections.singletonList(values.proof));
            assertEquals(values.original.snapshot().managedDocument(B).blueId(), expanded.snapshot().managedDocument(B).blueId());
            assertEquals(values.proof.managedDocument(A).blueId(), expanded.snapshot().managedDocument(A).blueId());
            assertNotEquals(values.proof.managedDocument(B).blueId(), expanded.snapshot().managedDocument(B).blueId());
            assertEquals(values.proof.occurrences().get(0).expectedTargetBlueId(), expanded.snapshot().occurrences().stream()
                    .filter(row -> row.sourceDocumentId().equals(A)).findFirst().orElseThrow(AssertionError::new).expectedTargetBlueId());
            assertEquals(Collections.singleton(A), expanded.snapshot().rootedWitnesses().sources());
            assertSame(values.original.rootedBinding(), expanded.rootedBinding());
            assertEquals(Collections.singletonList(B), expanded.rootedBinding().context.entryOwners());
            assertDoesNotThrow(() -> ClosureEvidenceVerifier.verifySnapshot(expanded.snapshot()));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.affectedClosure(1L,
                    values.documents, values.rows, expanded.snapshot().components(), Collections.singletonList(B)));
        }
    }

    @Test void historicalReadExpansionRejectsMissingForgedAndChangedEvidence() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            HistoricalAlias values = historicalAlias(fixture);
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(values.original, 1L,
                    values.documents, values.rows, Collections.emptyList()));
            ManagedDocumentSnapshot source = values.documents.get(1);
            ManagedDocumentSnapshot mutation = new ManagedDocumentSnapshot(A, source.blueId(),
                    source.document().properties("injected", new Node().value(1)), true, false, false, source.epoch(), source.componentGeneration());
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(values.original, 1L,
                    Arrays.asList(values.documents.get(0), mutation), values.rows, Collections.singletonList(values.proof)));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(values.original, 1L,
                    Arrays.asList(values.documents.get(0), source, source), values.rows, Collections.singletonList(values.proof)));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(values.original, 1L,
                    values.documents, Collections.singletonList(values.rows.get(1)), Collections.singletonList(values.proof)));
            ManagedDocumentSnapshot proofB = values.proof.managedDocument(B);
            ManagedDocumentSnapshot forgedB = new ManagedDocumentSnapshot(B, proofB.blueId(), new Node().name("forged"),
                    proofB.initialized(), proofB.terminated(), proofB.publicRoot(), proofB.epoch(), proofB.componentGeneration());
            AffectedClosureSnapshot forged = ClosureEvidenceFactory.affectedClosure(values.proof.graphGeneration(),
                    Arrays.asList(values.proof.managedDocument(A), forgedB), values.proof.occurrences(),
                    values.proof.components(), values.proof.publicRootDocumentIds());
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(values.original, 1L,
                    values.documents, values.rows, Collections.singletonList(forged)));
            ManagedDocumentSnapshot publicSource = new ManagedDocumentSnapshot(A, source.blueId(), source.document(),
                    source.initialized(), source.terminated(), true, source.epoch(), source.componentGeneration());
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedReadExpansion(values.original, 1L,
                    Arrays.asList(values.documents.get(0), publicSource), values.rows, Collections.singletonList(values.proof)));
        }
    }

    private static HistoricalAlias historicalAlias(CompositionCampaignFixture fixture) {
        Node b0 = initializedAlias(new Node().name("B").properties("counter", new Node().value(0)));
        Node b1 = b0.clone().properties("counter", new Node().value(1));
        Node a = initializedAlias(new Node().name("A").properties("peer", new Node().blueId(id(b0))));
        ManagedOccurrenceBinding historical = ManagedOccurrenceBinding.derived(hash('a'), A, ScopeAddress.embedded("/peer", 1L),
                B, id(b0), true, null);
        Map<DocumentId, Node> sourceBodies = new LinkedHashMap<>(); sourceBodies.put(A, a); sourceBodies.put(B, b0);
        AffectedClosureSnapshot proof = initializedSnapshot(sourceBodies, Collections.singletonList(historical), A);
        AffectedClosureSnapshot current = initializedSnapshot(Collections.singletonMap(B, b1), Collections.emptyList(), B);
        ClosureInvocationInput base = input(fixture, current, 100_000L, 1L);
        ClosureInvocationInput original = base.withRootedContext(RootedProcessingContext.derive(current, B,
                Collections.singletonMap(B, hash('a'))), hash('b'));
        ManagedDocumentSnapshot source = proof.managedDocument(A);
        ManagedDocumentSnapshot readSource = new ManagedDocumentSnapshot(A, source.blueId(), source.document(),
                source.initialized(), source.terminated(), false, source.epoch(), source.componentGeneration());
        ManagedOccurrenceBinding pending = ManagedOccurrenceBinding.derived(hash('a'), B, ScopeAddress.embedded("/child", 1L),
                A, source.blueId(), false, 0L);
        return new HistoricalAlias(original, proof, Arrays.asList(current.managedDocument(B), readSource), Arrays.asList(historical, pending));
    }

    private static Node initializedAlias(Node node) {
        return node.clone().contracts(new Node().properties("initialized", new Node()
                .type(new Node().blueId(blue.language.processor.registry.RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties("document", new Node().blueId(id(node)))));
    }

    private static AffectedClosureSnapshot initializedSnapshot(Map<DocumentId, Node> bodies,
            List<ManagedOccurrenceBinding> rows, DocumentId root) {
        AffectedClosureSnapshot raw = snapshot(bodies, rows, root);
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ManagedDocumentSnapshot doc : raw.managedDocuments()) documents.add(new ManagedDocumentSnapshot(doc.documentId(),
                doc.blueId(), doc.document(), true, false, doc.publicRoot(), doc.epoch(), doc.componentGeneration()));
        return ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(), documents, raw.occurrences(), raw.components(), raw.publicRootDocumentIds());
    }

    private static final class HistoricalAlias {
        final ClosureInvocationInput original;
        final AffectedClosureSnapshot proof;
        final List<ManagedDocumentSnapshot> documents;
        final List<ManagedOccurrenceBinding> rows;
        HistoricalAlias(ClosureInvocationInput original, AffectedClosureSnapshot proof,
                List<ManagedDocumentSnapshot> documents, List<ManagedOccurrenceBinding> rows) {
            this.original = original; this.proof = proof; this.documents = documents; this.rows = rows;
        }
    }
    private static ClosureInvocationInput input(CompositionCampaignFixture fixture, AffectedClosureSnapshot graph,
                                                long gas, long order) {
        Node event = new Node().properties("input", new Node().value(order));
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, id(event), ExternalOrderKey.of(Collections.singletonList(order)),
                fixture.environment.externalOrderPolicyIdentity());
        return ClosureEvidenceFactory.processClosure(graph, cause, Collections.emptyList(),
                ClosureEvidenceFactory.executionPolicy(gas, Collections.emptyMap(), "test"), fixture.environment);
    }
    private static AffectedClosureSnapshot single(int value) {
        return snapshot(Collections.singletonMap(A, new Node().name("A").properties("counter", new Node().value(value))),
                Collections.emptyList(), A);
    }
    private static AffectedClosureSnapshot expanded(int value, boolean active) {
        Node b = new Node().name("B");
        Node a = new Node().name("A").properties("counter", new Node().value(value));
        if (active) a.properties("child", new Node().blueId(id(b)));
        Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(A, a); bodies.put(B, b);
        ManagedOccurrenceBinding row = ManagedOccurrenceBinding.derived(hash('a'), A, ScopeAddress.embedded("/child", 1L),
                B, id(b), active, null);
        return snapshot(bodies, Collections.singletonList(row), A);
    }
}
