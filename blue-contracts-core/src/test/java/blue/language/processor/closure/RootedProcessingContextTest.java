package blue.language.processor.closure;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real finalizer topology; synthetic history hashes test only owner projection. */
final class RootedProcessingContextTest {
    private static final DocumentId A = new DocumentId("A");
    private static final DocumentId B = new DocumentId("B");
    private static final DocumentId P = new DocumentId("P");
    private static final String BLUE = "11111111111111111111111111111111";
    private static final String SHA = "sha256:" + String.join("", Collections.nCopies(64, "a"));

    @Test void twoViewsOfOneLiveCycleDeriveTheSameOwner() {
        AffectedClosureSnapshot snapshot = graph(true, false, 1L);
        Map<DocumentId, String> histories = histories(A, B);
        RootedProcessingContext fromA = RootedProcessingContext.derive(snapshot, A, histories);
        RootedProcessingContext fromB = RootedProcessingContext.derive(snapshot, B, histories);
        assertEquals(Arrays.asList(A, B), fromA.entryOwners());
        assertEquals(A, fromB.canonicalRootDocumentId());
        assertEquals(fromA.identity(), fromB.identity());
        assertEquals(fromA.ownerDescriptor(), fromB.ownerDescriptor());
        assertEquals(fromA.invocationIdentity(SHA, SHA), fromB.invocationIdentity(SHA, SHA));
        assertEquals(fromA.commitCompanionIdentity(SHA, SHA), fromB.commitCompanionIdentity(SHA, SHA));
        assertEquals(fromA.terminalKey(SHA), fromB.terminalKey(SHA));
    }

    @Test void oneWayDependencyIsNotAnEntryOwner() {
        RootedProcessingContext context = RootedProcessingContext.derive(graph(false, false, 1L), A, histories(A));
        assertEquals(Collections.singletonList(A), context.entryOwners());
        assertEquals(Collections.emptyList(), context.ownerDescriptor().get("internalEdges"));
        assertThrows(IllegalArgumentException.class,
                () -> RootedProcessingContext.derive(graph(false, false, 1L), A, histories(A, B)));
    }

    @Test void incomingParentKeepsItsOwnAnchorOutsideTheChildCycle() {
        RootedProcessingContext parent = RootedProcessingContext.derive(graph(true, true, 1L), P, histories(P));
        assertEquals(Collections.singletonList(P), parent.entryOwners());
        assertThrows(IllegalArgumentException.class,
                () -> RootedProcessingContext.derive(graph(true, true, 1L), A, histories(A, B)));
    }

    @Test void newOccurrenceGenerationIsANewOwnerContext() {
        RootedProcessingContext first = RootedProcessingContext.derive(graph(true, false, 1L), A, histories(A, B));
        RootedProcessingContext rejoined = RootedProcessingContext.derive(graph(true, false, 2L), A, histories(A, B));
        assertNotEquals(first.identity(), rejoined.identity());
    }

    @Test void missingHistoryAndCallerMutationCannotAlterAnOwner() {
        AffectedClosureSnapshot snapshot = graph(true, false, 1L);
        assertThrows(IllegalArgumentException.class, () -> RootedProcessingContext.derive(snapshot, A, histories(A)));
        Map<DocumentId, String> histories = histories(A, B);
        RootedProcessingContext context = RootedProcessingContext.derive(snapshot, A, histories);
        String identity = context.identity();
        histories.clear();
        assertEquals(identity, context.identity());
        assertThrows(UnsupportedOperationException.class, () -> context.entryOwners().clear());
        assertThrows(UnsupportedOperationException.class, () -> context.ownerDescriptor().clear());
    }

    @Test void retainedDeliveryBindsTheExactApplicationAndHistoricalPosition() {
        RootedProcessingContext context = RootedProcessingContext.derive(graph(false, false, 1L), A, histories(A));
        Node after = new Node().name("historical successor");
        String afterId = CompositionCampaignFixture.id(after);
        ManagedOccurrenceBinding target = ManagedOccurrenceBinding.derived(SHA, A,
                ScopeAddress.embedded("/child", 1L), B, BLUE, false, 0L);
        ManagedRevisionCause cause = ClosureEvidenceFactory.managedRevisionCause(target.occurrenceIdentity(), B,
                0L, 1L, BLUE, afterId, after, SHA, null);
        String delivery = context.retainedDeliveryBasisIdentity(cause, target, SHA);
        assertTrue(delivery.matches("sha256:[0-9a-f]{64}"));
        assertNotEquals(delivery, context.retainedDeliveryBasisIdentity(cause, target, CompositionCampaignFixture.hash('b')));
        ManagedOccurrenceBinding wrongPredecessor = ManagedOccurrenceBinding.derived(SHA, A,
                ScopeAddress.embedded("/child", 1L), B, afterId, false, 0L);
        assertThrows(IllegalArgumentException.class, () -> context.retainedDeliveryBasisIdentity(cause, wrongPredecessor, SHA));
        assertThrows(IllegalArgumentException.class, () -> context.retainedDeliveryBasisIdentity(cause, row(A, B, 1L), SHA));
        ManagedOccurrenceBinding wrongGeneration = ManagedOccurrenceBinding.derived(SHA, A,
                ScopeAddress.embedded("/child", 2L), B, BLUE, false, 0L);
        assertThrows(IllegalArgumentException.class, () -> context.retainedDeliveryBasisIdentity(cause, wrongGeneration, SHA));
        ManagedOccurrenceBinding wrongEpoch = ManagedOccurrenceBinding.derived(SHA, A,
                ScopeAddress.embedded("/child", 1L), B, BLUE, false, 1L);
        assertThrows(IllegalArgumentException.class, () -> context.retainedDeliveryBasisIdentity(cause, wrongEpoch, SHA));
        assertThrows(IllegalArgumentException.class, () -> context.retainedDeliveryBasisIdentity(cause, target, null));
    }

    private static Map<DocumentId, String> histories(DocumentId... ids) {
        Map<DocumentId, String> result = new LinkedHashMap<>();
        for (DocumentId id : ids) result.put(id, SHA);
        return result;
    }

    private static AffectedClosureSnapshot graph(boolean cycle, boolean parent, long generation) {
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        List<ManagedOccurrenceBinding> rows = new ArrayList<>();
        bodies.put(A, new Node().name("A").properties("child", new Node().blueId(BLUE)));
        bodies.put(B, new Node().name("B"));
        rows.add(row(A, B, generation));
        if (cycle) {
            bodies.get(B).properties("child", new Node().blueId(BLUE));
            rows.add(row(B, A, generation));
        }
        if (parent) {
            bodies.put(P, new Node().name("P").properties("child", new Node().blueId(BLUE)));
            rows.add(row(P, A, generation));
        }
        return CompositionCampaignFixture.snapshot(bodies, rows, parent ? P : A);
    }

    private static ManagedOccurrenceBinding row(DocumentId source, DocumentId target, long generation) {
        return ManagedOccurrenceBinding.derived(SHA, source, ScopeAddress.embedded("/child", generation),
                target, BLUE, true, null);
    }
}
