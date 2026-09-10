package blue.language.processor.closure;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.*;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production queue/finalizer tests; history hashes here isolate processor ownership. */
final class RootedProcessorOwnershipTest {
    private static final DocumentId P = new DocumentId("P");
    private static final DocumentId S = new DocumentId("S");

    @Test void processorSeparatesCalculatedChildFromOwnedParentAndPreservesTheFullMeter() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = pair(fixture, false);
            ClosureInvocationInput base = fixture.admission(graph, 100_000L);
            RootedProcessingContext context = RootedProcessingContext.derive(graph, P, Collections.singletonMap(P, hash('c')));
            ClosureProcessResult plain = fixture.admit(base).processResult();
            fixture.reactions.clear(); fixture.initialized.clear();
            ClosureProcessResult rooted = fixture.admit(base.withRootedContext(context, hash('d'))).processResult();
            assertTrue(rooted.commits(), diagnostic(rooted));
            RootedPublicationProjection scope = rooted.rootedProjection();
            assertNotNull(scope);
            assertEquals(Collections.singletonList(P), scope.ownedDocumentIds());
            assertEquals(Collections.singletonList(S), scope.embeddedViewDocuments().stream().map(ResultingDocument::documentId).collect(java.util.stream.Collectors.toList()));
            assertEquals(1L, ((Number) scope.ownedDocuments().get(0).document().getNode("/count").getValue()).longValue());
            assertEquals(plain.gasTraceIdentity(), rooted.gasTraceIdentity());
            assertEquals(plain.totalGas(), rooted.totalGas());
            assertEquals(plain.commitCompanion().companionIdentity(), rooted.commitCompanion().companionIdentity());
            assertEquals(context.invocationIdentity(base.invocationIdentity(), hash('d')), scope.invocationIdentity());
            assertEquals(context.commitCompanionIdentity(rooted.commitCompanion().companionIdentity(),
                    scope.invocationIdentity()), scope.companionIdentity());
            assertNull(plain.rootedProjection());
            assertThrows(UnsupportedOperationException.class, () -> scope.ownedDocumentIds().clear());
        }
    }

    @Test void aTransientJoinRemainsOwnedAfterSplitAndDoesNotRotateTheEntryContext() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot entry = pair(fixture, false);
            RootedProcessingContext context = RootedProcessingContext.derive(entry, P, Collections.singletonMap(P, hash('c')));
            ClosureInvocationInput input = fixture.admission(entry, 100_000L).withRootedContext(context, hash('d'));
            RootedOwnershipTracker tracker = new RootedOwnershipTracker(input.rootedBinding(), input.snapshot());
            tracker.finalized(entry);
            assertEquals(Collections.singletonList(P), tracker.snapshot().owners);
            tracker.finalized(pair(fixture, true));
            assertEquals(Arrays.asList(P, S), tracker.snapshot().owners);
            tracker.finalized(entry);
            assertEquals(Arrays.asList(P, S), tracker.snapshot().owners);
            assertSame(context, tracker.snapshot().binding.context);
        }
    }

    @Test void rootedFailureHasNoPublicationAuthority() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            fixture.loop = true;
            AffectedClosureSnapshot graph = CompositionReactionCycleTest.ring(fixture, "rooted-loop", 3, 2);
            Map<DocumentId, String> histories = new LinkedHashMap<>();
            graph.managedDocuments().forEach(d -> histories.put(d.documentId(), hash('c')));
            ClosureInvocationInput input = fixture.admission(graph, 15_000L).withRootedContext(
                    RootedProcessingContext.derive(graph, new DocumentId("d0"), histories), hash('d'));
            ClosureProcessResult failed = fixture.admit(input).processResult();
            rollback(input, failed);
            assertNull(failed.rootedProjection());
            assertNotNull(failed.rejectedCharge());
        }
    }

    @Test void contextCannotBeReplacedOrCopiedOntoDifferentEntryTopology() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot entry = pair(fixture, false);
            RootedProcessingContext context = RootedProcessingContext.derive(entry, P, Collections.singletonMap(P, hash('c')));
            ClosureInvocationInput base = fixture.admission(entry, 100_000L);
            assertThrows(IllegalArgumentException.class, () -> base.withRootedContext(context, hash('d'))
                    .withRootedContext(context, hash('e')));
            assertThrows(IllegalArgumentException.class, () -> fixture.admission(pair(fixture, true), 100_000L)
                    .withRootedContext(context, hash('d')));
        }
    }

    private static AffectedClosureSnapshot pair(CompositionCampaignFixture fixture, boolean cycle) {
        Node source = document("source").properties("seed", new Node().value(0L));
        Node parent = document("parent").properties("child", new Node().blueId(id(source)));
        parent.getContracts().properties("embedded", process("paths", "/child"))
                .properties("fromChild", embedded("/child")).properties("react", handler("fromChild"));
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
        bindings.add(fixture.binding(P, "/child", S, source, true));
        if (cycle) {
            source.properties("peer", new Node().blueId(id(parent)));
            source.getContracts().properties("embedded", process("paths", "/peer"));
            bindings.add(fixture.binding(S, "/peer", P, parent, true));
        }
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(P, parent); bodies.put(S, source);
        return snapshot(bodies, bindings, P);
    }
}
