package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.*;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** F2 handoff: authenticated source epoch, new lineage, observation, publication. */
final class RetainedEpochBirthCompositionTest {
    @Test
    void shouldApplyRetainedEpochAndInitializeOnlyTheNewLineage() {
        // given: the source has actually executed once and owns its Contracts receipt.
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            DocumentId sourceId = new DocumentId("source");
            DocumentId consumerId = new DocumentId("consumer");
            Node sourceBefore = document("source").properties("seed", new Node().value(0));
            ClosureProcessResult sourceResult = fixture.admit(fixture.admission(snapshot(
                    Collections.singletonMap(sourceId, sourceBefore), Collections.emptyList(), sourceId),
                    100_000L)).processResult();
            assertTrue(sourceResult.commits(), diagnostic(sourceResult));
            Node sourceAfter = sourceResult.resultingDocuments().get(0).document();
            ManagedDocumentTransitionReceipt retained = sourceResult.managedTransitionReceipts().get(0);
            fixture.exact.put(id(sourceBefore), sourceBefore);
            fixture.exact.put(id(sourceAfter), sourceAfter);
            fixture.exact.put(id(token(0)), token(0));
            Node born = document("new reading").properties("seed", new Node().value(0));
            Node consumer = document("consumer")
                    .properties("source", new Node().blueId(id(sourceBefore)))
                    .properties("children", new Node().properties(Collections.emptyMap()))
                    .properties("reactionInstall", new Node().properties("reading", born));
            consumer.getContracts().properties("embedded", process("paths", "/source")
                            .properties("collectionPaths", new Node().items(Collections.singletonList(
                                    new Node().value("/children")))))
                    .properties("fromDescendant", embedded(null))
                    .properties("react", handler("fromDescendant"));
            ManagedOccurrenceBinding historical = ManagedOccurrenceBinding.derived(
                    fixture.environment.managedBindingPolicyIdentity(), consumerId,
                    ScopeAddress.embedded("/source", 1L), sourceId, id(sourceBefore), false, -1L);
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            bodies.put(consumerId, consumer); bodies.put(sourceId, sourceAfter);
            AffectedClosureSnapshot initial = snapshot(bodies, Collections.singletonList(historical), consumerId);
            List<ManagedDocumentSnapshot> members = new ArrayList<>();
            for (ManagedDocumentSnapshot value : initial.managedDocuments()) {
                members.add(new ManagedDocumentSnapshot(value.documentId(), value.blueId(), value.document(),
                        value.documentId().equals(sourceId), false, value.publicRoot(), 0L,
                        value.componentGeneration()));
            }
            initial = ClosureEvidenceFactory.affectedClosure(initial.graphGeneration(), members,
                    initial.occurrences(), initial.components(), initial.publicRootDocumentIds());
            ClosureProcessResult admitted = fixture.admit(fixture.admission(initial, 100_000L)).processResult();
            assertTrue(admitted.commits(), diagnostic(admitted));
            AffectedClosureSnapshot before = resultingSnapshot(admitted);
            ManagedRevisionCause cause = ClosureEvidenceFactory.managedRevisionCause(
                    historical.occurrenceIdentity(), -1L, 0L, sourceAfter, retained);
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(before, cause,
                    Collections.emptyList(), ClosureEvidenceFactory.executionPolicy(100_000L,
                            Collections.emptyMap(), "r2-retained-gas"), fixture.environment);
            fixture.initialized.clear(); fixture.reactions.clear();
            // when: discover birth, reserve its lineage, replay the same retained application.
            ClosureAttemptResult missing;
            try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                missing = contracts.processClosure(input);
            }
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, missing.kind());
            assertNull(missing.processResult());
            ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) missing.resourceDemands().get(0);
            ClosureInvocationInput retry = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(demand, new DocumentId("reading"), born)));
            fixture.initialized.clear(); fixture.reactions.clear();
            ClosureProcessResult applied;
            try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                applied = contracts.processClosure(retry).processResult();
            }
            // then: retained source work and public emission are not replayed.
            assertNotNull(applied);
            assertTrue(applied.commits(), diagnostic(applied));
            assertEquals(Collections.singletonList("new reading"), fixture.initialized);
            assertEquals(Arrays.asList("consumer:0", "consumer:0"), fixture.reactions);
            ResultingDocument source = applied.resultingDocuments().stream()
                    .filter(value -> value.documentId().equals(sourceId)).findFirst().get();
            assertEquals(NodeWireForm.get(sourceAfter), NodeWireForm.get(source.document()));
            assertEquals(0L, source.epoch());
            assertTrue(applied.publicEvents().isEmpty());
            assertEquals(2, applied.managedTransitionReceipts().size());
            assertTrue(applied.managedTransitionReceipts().stream()
                    .noneMatch(value -> value.documentId().equals(sourceId)));
            assertNotNull(applied.commitCompanion());
            assertEquals(retained.transitionReceiptIdentity(), ((ManagedRevisionCause) retry.cause())
                    .sourceTransitionReceipt().get().transitionReceiptIdentity());
        }
    }

    private static AffectedClosureSnapshot resultingSnapshot(ClosureProcessResult result) {
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ResultingDocument value : result.resultingDocuments()) {
            documents.add(new ManagedDocumentSnapshot(value.documentId(), value.afterBlueId(), value.document(),
                    value.initialized(), value.terminated(), value.publicRoot(), value.epoch(),
                    value.componentGeneration()));
        }
        return ClosureEvidenceFactory.affectedClosure(result.graphGeneration(), documents,
                result.occurrenceBindings(), result.resultingComponents(), Collections.singletonList(new DocumentId("consumer")));
    }
}
