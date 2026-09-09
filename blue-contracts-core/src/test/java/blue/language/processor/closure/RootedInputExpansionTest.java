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
