package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** A pending immutable source's return reference is not a calculating containing edge. */
final class RootedHistoricalWitnessTest {
    private static final DocumentId P = new DocumentId("P");
    private static final DocumentId S = new DocumentId("S");

    @Test
    void historicalReactionDoesNotRewriteTheImmutableSourceOrItsReturnReference() {
        historicalReaction(false, output -> assertThrows(IllegalArgumentException.class, () -> publicCopy(output),
                "A public flat-state constructor cannot assert witness authority"));
    }

    @Test
    void historicalReactionPreservesTheImmutableSourcesRetiredReturnBinding() {
        historicalReaction(true, output -> {
            // Retired rows are valid in a flat graph, but copying them grants no witness authority.
            AffectedClosureSnapshot copy = publicCopy(output);
            assertNull(copy.rootedWitnesses());
            assertTrue(copy.graph().immutableSources().isEmpty());
        });
    }

    private void historicalReaction(boolean retiredReturn, Consumer<AffectedClosureSnapshot> verifyPublicCopy) {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            Node source0 = initialized(document("source"));
            source0.getContracts().properties("embedded", process("paths", "/peer"));
            source0.getContracts().properties("fromPeer", embedded("/peer")).properties("react", handler("fromPeer"));
            Node parent = document("parent").properties("child", new Node().blueId(id(source0)));
            parent.getContracts().properties("embedded", process("paths", "/child"))
                    .properties("fromChild", embedded("/child")).properties("react", handler("fromChild"));
            parent = initialized(parent);
            Node source1 = source0.clone().properties("count", new Node().value(1L))
                    .properties("peer", new Node().blueId(id(parent)));
            Node sourceHead = source1.clone().properties("count", new Node().value(3L));
            if (retiredReturn) sourceHead.getProperties().remove("peer");
            ManagedOccurrenceBinding pending = ManagedOccurrenceBinding.derived(fixture.environment.managedBindingPolicyIdentity(),
                    P, ScopeAddress.embedded("/child", 1L), S, id(source0), false, Long.valueOf(0L));
            ManagedOccurrenceBinding reverse = fixture.binding(S, "/peer", P, parent, !retiredReturn);
            Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(P, parent); bodies.put(S, sourceHead);
            AffectedClosureSnapshot raw = snapshot(bodies, Arrays.asList(pending, reverse), P);
            List<ManagedDocumentSnapshot> initialized = new ArrayList<>();
            for (ManagedDocumentSnapshot doc : raw.managedDocuments()) initialized.add(new ManagedDocumentSnapshot(doc.documentId(),
                    doc.blueId(), doc.document(), true, false, doc.publicRoot(), doc.documentId().equals(S) ? 3L : 1L,
                    doc.componentGeneration()));
            AffectedClosureSnapshot entry = ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(), initialized,
                    raw.occurrences(), raw.components(), raw.publicRootDocumentIds());
            String invocation = hash('e');
            Node event = token(1);
            ManagedRootEventOccurrence emitted = new ManagedRootEventOccurrence(0L, 0L, S,
                    ClosureIdentityService.INSTANCE.eventOccurrenceIdentity(invocation, 0L, id(event)),
                    ExactEventIdentityEvidence.verify(null, event, id(event), null), false);
            ManagedDocumentTransitionReceipt receipt = ManagedDocumentTransitionReceipt.identified(invocation, 0L, S, hash('f'),
                    id(source0), id(source1), Collections.singletonList(emitted), 5L);
            ManagedRevisionCause cause = ClosureEvidenceFactory.managedRevisionCause(pending.occurrenceIdentity(), 0L, 1L, source1, receipt);
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(entry, cause, Collections.<DirectLogicalDelivery>emptyList(),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.<DocumentId, Long>emptyMap(), "rooted-witness"), fixture.environment);
            RootedProcessingContext context = RootedProcessingContext.derive(entry, P, Collections.singletonMap(P, hash('c')));
            input = input.withRootedContext(context, context.retainedDeliveryBasisIdentity(cause, pending, receipt.transitionReceiptIdentity()));
            assertEquals(entry.closureIdentity(), input.snapshot().closureIdentity());
            for (Node exact : Arrays.asList(source0, source1, sourceHead, parent, event)) fixture.exact.put(id(exact), exact);
            try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                ClosureProcessResult result = contracts.processClosure(input).processResult();
                assertTrue(result.commits(), diagnostic(result));
                assertEquals(Collections.singletonList("parent:1"), fixture.reactions);
                assertEquals(Collections.singletonList(P), result.rootedProjection().ownedDocumentIds());
                ManagedDocumentSnapshot witness = result.rootedProjection().resultingSnapshot().managedDocument(S);
                assertEquals(3L, witness.epoch());
                assertEquals(id(sourceHead), witness.blueId());
                assertEquals(NodeWireForm.get(sourceHead), NodeWireForm.get(witness.document()));
                assertEquals(reverse.bindingIdentity(), result.occurrenceBindings().stream()
                        .filter(row -> row.sourceDocumentId().equals(S)).findFirst().get().bindingIdentity());
                assertTrue(result.managedTransitionReceipts().stream().noneMatch(value -> value.documentId().equals(S)));
                AffectedClosureSnapshot output = result.rootedProjection().resultingSnapshot();
                long ownerEpoch = output.managedDocument(P).epoch();
                AffectedClosureSnapshot retained = ClosureEvidenceFactory.rootedRetainedSnapshot(result,
                        Collections.singletonMap(P, ownerEpoch + 1L));
                assertEquals(ownerEpoch + 1L, retained.managedDocument(P).epoch());
                assertEquals(ownerEpoch, output.managedDocument(P).epoch(), "Original processor result stays immutable");
                assertSame(output.rootedWitnesses(), retained.rootedWitnesses());
                assertSame(output.managedDocument(S), retained.managedDocument(S));
                assertEquals(output.occurrences(), retained.occurrences());
                assertEquals(output.components(), retained.components());
                ClosureEvidenceVerifier.verifySnapshot(retained);
                assertEquals(output.closureIdentity(), ClosureEvidenceFactory.rootedRetainedSnapshot(result,
                        Collections.singletonMap(P, ownerEpoch)).closureIdentity());
                assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedRetainedSnapshot(result,
                        Collections.singletonMap(P, ownerEpoch - 1L)));
                assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedRetainedSnapshot(result,
                        Collections.singletonMap(P, ownerEpoch + 2L)));
                assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedRetainedSnapshot(result,
                        Collections.singletonMap(S, 4L)));
                assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.rootedRetainedSnapshot(result,
                        Collections.<DocumentId, Long>emptyMap()));
                verifyPublicCopy.accept(output);
                Map<DocumentId, Node> changed = new LinkedHashMap<>();
                for (ManagedDocumentSnapshot document : output.managedDocuments()) changed.put(document.documentId(), document.document());
                changed.get(S).properties("count", new Node().value(99L));
                assertThrows(IllegalArgumentException.class, () -> output.rootedWitnesses().requireUnchanged(changed, output.occurrences()));
                List<ManagedOccurrenceBinding> missing = new ArrayList<>(output.occurrences());
                missing.removeIf(row -> row.sourceDocumentId().equals(S));
                assertThrows(IllegalArgumentException.class, () -> output.rootedWitnesses().requireUnchanged(bodies, missing));
                List<ManagedOccurrenceBinding> deactivated = new ArrayList<>();
                for (ManagedOccurrenceBinding row : output.occurrences()) deactivated.add(row.sourceDocumentId().equals(S)
                        ? ManagedOccurrenceBinding.derived(row.bindingPolicyIdentity(), S, row.sourceAddress(), P,
                            row.expectedTargetBlueId(), !reverse.active(), null) : row);
                assertThrows(IllegalArgumentException.class, () -> output.rootedWitnesses().requireUnchanged(bodies, deactivated));
                // A second invocation starts with a newer owner but the same immutable
                // source witness and its older authenticated return reference.
                Node source2 = source1.clone().properties("count", new Node().value(2L));
                fixture.exact.put(id(source2), source2);
                String nextInvocation = hash('d');
                ManagedRootEventOccurrence nextEvent = new ManagedRootEventOccurrence(0L, 0L, S,
                        ClosureIdentityService.INSTANCE.eventOccurrenceIdentity(nextInvocation, 0L, id(event)),
                        ExactEventIdentityEvidence.verify(null, event, id(event), null), false);
                ManagedDocumentTransitionReceipt nextReceipt = ManagedDocumentTransitionReceipt.identified(nextInvocation, 0L, S,
                        hash('f'), id(source1), id(source2), Collections.singletonList(nextEvent), 5L);
                ManagedOccurrenceBinding nextPending = output.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(P)).findFirst().get();
                ManagedRevisionCause nextCause = ClosureEvidenceFactory.managedRevisionCause(nextPending.occurrenceIdentity(),
                        1L, 2L, source2, nextReceipt);
                RootedProcessingContext nextContext = RootedProcessingContext.derive(output, P, Collections.singletonMap(P, hash('b')));
                ClosureInvocationInput nextInput = ClosureEvidenceFactory.processClosure(output, nextCause,
                        Collections.<DirectLogicalDelivery>emptyList(), input.executionPolicy(), fixture.environment)
                        .withRootedContext(nextContext, nextContext.retainedDeliveryBasisIdentity(nextCause, nextPending,
                                nextReceipt.transitionReceiptIdentity()));
                ClosureAttemptResult nextAttempt = contracts.processClosure(nextInput);
                assertTrue(nextAttempt.isComplete(), nextAttempt.resourceDemands().toString());
                ClosureProcessResult nextResult = nextAttempt.processResult();
                assertTrue(nextResult.commits(), diagnostic(nextResult));
                assertEquals(Arrays.asList("parent:1", "parent:1"), fixture.reactions);
                assertEquals(id(sourceHead), nextResult.rootedProjection().resultingSnapshot().managedDocument(S).blueId());
                assertEquals(3L, nextResult.rootedProjection().resultingSnapshot().managedDocument(S).epoch());
                assertEquals(reverse.bindingIdentity(), nextResult.occurrenceBindings().stream()
                        .filter(row -> row.sourceDocumentId().equals(S)).findFirst().get().bindingIdentity());
                assertTrue(nextResult.managedTransitionReceipts().stream().noneMatch(value -> value.documentId().equals(S)));
                ClosureInvocationInput tight = ClosureEvidenceFactory.processClosure(input.snapshot(), cause,
                        Collections.<DirectLogicalDelivery>emptyList(), ClosureEvidenceFactory.executionPolicy(result.totalGas() - 1,
                                Collections.<DocumentId, Long>emptyMap(), "rooted-witness"), fixture.environment)
                        .withRootedContext(context, context.retainedDeliveryBasisIdentity(cause, pending, receipt.transitionReceiptIdentity()));
                ClosureProcessResult failed = contracts.processClosure(tight).processResult();
                rollback(tight, failed);
                assertEquals(blue.language.processor.ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.status());
                assertNotNull(failed.rejectedCharge());
                assertNull(failed.rootedProjection());
            }
        }
    }

    private static AffectedClosureSnapshot publicCopy(AffectedClosureSnapshot output) {
        return new AffectedClosureSnapshot(output.closureIdentity(), output.graphGeneration(), output.managedDocuments(),
                output.occurrences(), output.occurrenceBindingSetIdentity(), output.components(), output.publicRootDocumentIds());
    }

    private static Node initialized(Node source) {
        String authored = id(source);
        return source.clone().contracts(source.getContracts().clone().properties("initialized",
                typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER).properties("document", new Node().blueId(authored))));
    }
}
