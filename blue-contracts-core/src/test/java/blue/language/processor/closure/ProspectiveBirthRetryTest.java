package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.*;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class ProspectiveBirthRetryTest {
    @Test
    void shouldDiscoverAndInitializeNestedBirthsWithoutPublishingSuspendedPrefixes() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            Node leaf = document("reading").properties("seed", new Node().value(0));
            Node child = parent("sample", "reading", leaf);
            Node root = parent("study", "sample", child);
            DocumentId rootId = new DocumentId("a");
            ClosureInvocationInput original = fixture.admission(snapshot(Collections.singletonMap(rootId, root),
                    Collections.emptyList(), rootId), 100_000L);
            // when
            ClosureAttemptResult first = fixture.admit(original);
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, first.kind());
            assertNull(first.totalGas());
            assertNull(first.processResult());
            ManagedOccurrenceEvidenceDemand childDemand = demand(first);
            assertEquals(id(child), childDemand.suppliedValueBlueId());
            ClosureInvocationInput withChild = ClosureEvidenceFactory.withProspectiveBirths(original,
                    Collections.singletonList(new ManagedDocumentBirth(childDemand, new DocumentId("b"), child)));
            fixture.initialized.clear(); fixture.reactions.clear();
            ClosureAttemptResult second = fixture.admit(withChild);
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, second.kind());
            ManagedOccurrenceEvidenceDemand leafDemand = demand(second);
            ClosureInvocationInput complete = ClosureEvidenceFactory.withProspectiveBirths(withChild,
                    Collections.singletonList(new ManagedDocumentBirth(leafDemand, new DocumentId("c"), leaf)));
            fixture.initialized.clear(); fixture.reactions.clear();
            ClosureProcessResult result = fixture.admit(complete).processResult();
            // then
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("study", "sample", "reading"), fixture.initialized);
            assertEquals(Arrays.asList("study:0", "sample:0"), fixture.reactions);
            assertEquals(3, result.managedTransitionReceipts().size());
            assertEquals(original.cause().causeIdentity(), complete.cause().causeIdentity());
            assertEquals(original.executionPolicy().identity(), complete.executionPolicy().identity());
            assertEquals(original.snapshot().managedDocument(rootId).blueId(),
                    complete.snapshot().managedDocument(rootId).blueId());
            assertNotEquals(original.invocationIdentity(), complete.invocationIdentity());
            assertNull(original.snapshot().managedDocument(new DocumentId("b")));
            assertEquals(3, result.resultingDocuments().size());
            assertTrue(result.totalGas() > 0L);
        }
    }

    @Test
    void shouldKeepEqualContentBirthsDistinctAndRejectConflictingEvidence() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            Node child = document("same").properties("seed", new Node().value(0));
            Node root = parent("parent", "x/y", child);
            root.getNode("/install").properties("x~y", child.clone());
            DocumentId rootId = new DocumentId("root");
            ClosureInvocationInput input = fixture.admission(snapshot(Collections.singletonMap(rootId, root),
                    Collections.emptyList(), rootId), 100_000L);
            ClosureAttemptResult missing = fixture.admit(input);
            assertEquals(2, missing.resourceDemands().size());
            ManagedOccurrenceEvidenceDemand d0 = (ManagedOccurrenceEvidenceDemand) missing.resourceDemands().get(0);
            ManagedOccurrenceEvidenceDemand d1 = (ManagedOccurrenceEvidenceDemand) missing.resourceDemands().get(1);
            ManagedDocumentBirth first = new ManagedDocumentBirth(d0, new DocumentId("child-a"), child);
            ManagedDocumentBirth second = new ManagedDocumentBirth(d1, new DocumentId("child-b"), child);
            // when
            ClosureInvocationInput expanded = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Arrays.asList(second, first));
            fixture.initialized.clear(); fixture.reactions.clear();
            ClosureProcessResult result = fixture.admit(expanded).processResult();
            // then
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(2L, fixture.initialized.stream().filter("same"::equals).count());
            assertEquals(2, fixture.reactions.size());
            assertNotEquals(result.occurrenceBindings().get(0).occurrenceIdentity(),
                    result.occurrenceBindings().get(1).occurrenceIdentity());
            assertEquals(input.directDeliverySnapshotIdentity(), expanded.directDeliverySnapshotIdentity());
            assertThrows(IllegalArgumentException.class, () -> new ManagedDocumentBirth(d0,
                    new DocumentId("bad"), document("different")));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.withProspectiveBirths(input,
                    Arrays.asList(first, first)));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.withProspectiveBirths(input,
                    Arrays.asList(first, new ManagedDocumentBirth(d1, first.documentId(), child))));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(d0, rootId, child))));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.withProspectiveBirths(expanded,
                    Collections.singletonList(first)));
            Node copy = first.document(); copy.properties("mutated", new Node().value(true));
            assertEquals(id(child), id(first.document()));
        }
    }

    @Test
    void shouldChargeTheReplayedPrefixAndRollbackAFailedChildAdmission() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            Node child = document("child").properties("seed", new Node().value(0));
            Node root = parent("parent", "child", child);
            DocumentId rootId = new DocumentId("root");
            ClosureInvocationInput input = fixture.admission(snapshot(Collections.singletonMap(rootId, root),
                    Collections.emptyList(), rootId), 100_000L);
            ManagedOccurrenceEvidenceDemand demand = demand(fixture.admit(input));
            ClosureInvocationInput expanded = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(demand, new DocumentId("child"), child)));
            fixture.failAt = 1;
            fixture.initialized.clear(); fixture.reactions.clear();
            // when
            ClosureProcessResult failed = fixture.admit(expanded).processResult();
            // then
            rollback(expanded, failed);
            assertTrue(failed.totalGas() > 0L);
            assertEquals(Arrays.asList("parent", "child"), fixture.initialized);
            assertEquals(NodeWireForm.get(root), NodeWireForm.get(input.snapshot().managedDocument(rootId).document()));
        }
    }

    @Test
    void shouldRejectBirthContentClaimingPriorLifecycleOrCheckpointState() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            DocumentId rootId = new DocumentId("root");
            ClosureInvocationInput input = fixture.admission(snapshot(
                    Collections.singletonMap(rootId, document("parent")),
                    Collections.emptyList(), rootId), 100_000L);
            for (String key : Arrays.asList("initialized", "terminated", "checkpoint")) {
                Node child = document("unproved");
                child.getContracts().properties(key, new Node().value("unproved"));
                ManagedOccurrenceEvidenceDemand demand = ManagedOccurrenceEvidenceDemand.derived(
                        input.cause().causeIdentity(), input.snapshot().closureIdentity(),
                        input.snapshot().graphGeneration(), rootId, "/children/new",
                        id(process("collectionPaths", "/children")), id(child), 0L);
                // when / then: matching content identity does not prove prior processing.
                assertThrows(IllegalArgumentException.class, () -> new ManagedDocumentBirth(
                        demand, new DocumentId("new"), child));
            }
        }
    }

    private static ManagedOccurrenceEvidenceDemand demand(ClosureAttemptResult attempt) {
        assertEquals(1, attempt.resourceDemands().size());
        return (ManagedOccurrenceEvidenceDemand) attempt.resourceDemands().get(0);
    }
    private static Node parent(String label, String key, Node child) {
        Node parent = document(label).properties("children", new Node().properties(Collections.emptyMap()))
                .properties("install", new Node().properties(key, child));
        parent.getContracts().properties("embedded", process("collectionPaths", "/children"))
                .properties("fromChildren", embedded(null)).properties("react", handler("fromChildren"));
        return parent;
    }
}
