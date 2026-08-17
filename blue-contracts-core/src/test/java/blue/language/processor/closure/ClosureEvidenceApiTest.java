package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused contract tests for the immutable affected-closure evidence seam. */
final class ClosureEvidenceApiTest {

    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");

    @Test
    void shouldAdmitPortableDocumentAndScopeIdentities() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentId(Normalizer.normalize(
                        "\u00e9", Normalizer.Form.NFD)));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentId("bad\u0000id"));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentId("\ud800"));

        DocumentId privateUse = new DocumentId("\ue000");
        DocumentId supplementary = new DocumentId("\ud800\udc00");
        assertTrue(privateUse.compareTo(supplementary) < 0,
                "portable order must compare Unicode scalar values, not UTF-16");

        ManagedScopeKey rootA = ManagedScopeKey.root(A);
        ManagedScopeKey rootB = ManagedScopeKey.root(B);
        assertTrue(rootA.isRoot());
        assertEquals("/", rootA.address().path());
        assertTrue(rootA.compareTo(rootB) < 0);
        assertThrows(IllegalArgumentException.class,
                () -> ScopeAddress.embedded("relative", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> ScopeAddress.embedded("/bad~2escape", 1L));
    }

    @Test
    void shouldRetainTypedDocumentsAndCyclicProofsDefensively() {
        Node source = node("source");
        ManagedDocumentSnapshot document = new ManagedDocumentSnapshot(
                A, "blue-a", source, true, false, true, 3L, 1L);
        source.name("mutated caller value");
        assertEquals("source", document.document().getName());

        Node returned = document.document();
        returned.name("mutated return value");
        assertEquals("source", document.document().getName());

        CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                Arrays.asList(node("a-proof"), node("b-proof")));
        ComponentSnapshot component = new ComponentSnapshot(
                hash('a'),
                hash('b'),
                1L,
                ComponentKind.CYCLIC,
                Arrays.asList(A, B),
                Arrays.asList("master#0", "master#1"),
                "master",
                proof,
                hash('c'));

        assertNotSame(proof, component.completeCyclicProof());
        List<Node> firstRead = component.completeCyclicProof()
                .declaredPlaceholderSet();
        firstRead.get(0).name("changed proof read");
        assertEquals("a-proof", component.completeCyclicProof()
                .declaredPlaceholderSet().get(0).getName());
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentSnapshot(
                        hash('a'), hash('b'), 1L, ComponentKind.CYCLIC,
                        Arrays.asList(A, B),
                        Arrays.asList("master#1", "master#0"),
                        "master", proof, hash('c')));
    }

    @Test
    void shouldValidateOneAuthoritativeClosureSnapshot() {
        AffectedClosureSnapshot snapshot = snapshot(false, null);

        assertEquals(Arrays.asList(A, B),
                Arrays.asList(
                        snapshot.managedDocuments().get(0).documentId(),
                        snapshot.managedDocuments().get(1).documentId()));
        assertEquals(Collections.singletonList(A),
                snapshot.publicRootDocumentIds());
        assertEquals("blue-b",
                snapshot.managedDocument(B).blueId());
        assertTrue(snapshot.contains(A));

        ManagedOccurrenceBinding historical = binding(false, 2L);
        assertFalse(historical.active());
        assertEquals(Long.valueOf(2L), historical.pendingHistoricalEpoch());
        assertThrows(IllegalArgumentException.class,
                () -> binding(true, 2L));

        List<ManagedDocumentSnapshot> reversed = Arrays.asList(
                managed(B, "blue-b", false),
                managed(A, "blue-a", true));
        assertThrows(IllegalArgumentException.class,
                () -> new AffectedClosureSnapshot(
                        hash('f'), 1L, reversed,
                        Collections.singletonList(binding(false, null)),
                        hash('e'), components(),
                        Collections.singletonList(A)));
    }

    @Test
    void shouldCloseOperationSpecificInvocationInputs() {
        AffectedClosureSnapshot snapshot = snapshot(false, null);
        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(B), "channel", "logical", 0L);
        ArrayList<DirectLogicalDelivery> deliveries =
                new ArrayList<DirectLogicalDelivery>();
        deliveries.add(delivery);
        ExecutionPolicy policy = policy(snapshot);
        ExternalEventCause external = new ExternalEventCause(
                hash('1'), node("event"), "event-blue",
                ExternalOrderKey.of(Arrays.asList("stream", 1L, "event")),
                hash('2'));

        ClosureInvocationInput input = ClosureInvocationInput.processClosure(
                hash('3'), snapshot, external, deliveries, hash('4'), policy,
                environment(hash('2')));
        deliveries.clear();

        assertEquals(ClosureInvocationInput.Operation.PROCESS_CLOSURE,
                input.operation());
        assertEquals(1, input.directDeliveries().size());
        assertThrows(UnsupportedOperationException.class,
                () -> input.directDeliveries().clear());
        assertThrows(IllegalArgumentException.class,
                () -> ClosureInvocationInput.processClosure(
                        hash('3'), snapshot,
                        new AdmissionCause(
                                hash('1'), AdmissionKind.TOP_LEVEL_ADMISSION,
                                "admit", null, null, hash('2')),
                        Collections.<DirectLogicalDelivery>emptyList(),
                        hash('4'), policy, environment(hash('2'))));

        AffectedClosureSnapshot historical = snapshot(false, 3L);
        ManagedRevisionCause revision = new ManagedRevisionCause(
                hash('5'), hash('d'), B, 3L, 4L,
                "blue-b-old", "blue-b", node("b-after"),
                hash('6'), hash('7'));
        ClosureInvocationInput managed = ClosureInvocationInput.processClosure(
                hash('8'), historical, revision,
                Collections.<DirectLogicalDelivery>emptyList(),
                hash('9'), policy(historical), environment(hash('2')));
        assertEquals(ProcessingCause.Kind.MANAGED_REVISION,
                managed.cause().kind());
        assertThrows(IllegalArgumentException.class,
                () -> ClosureInvocationInput.processClosure(
                        hash('8'), historical, revision,
                        Collections.singletonList(delivery),
                        hash('9'), policy(historical), environment(hash('2'))));
    }

    @Test
    void shouldSeparateCompletionSuspensionAndAtomicCommitEvidence() {
        AffectedClosureSnapshot snapshot = snapshot(false, null);
        ClosureCommitCompanion companion = companion(snapshot);
        Node publicEvent = node("public-event");
        ClosureProcessResult completed = new ClosureProcessResult(
                ProcessorStatus.SUCCESS,
                hash('3'), snapshot.closureIdentity(), hash('4'),
                2L, snapshot.managedDocuments(), snapshot.components(),
                snapshot.occurrences(), snapshot.occurrenceBindingSetIdentity(),
                Collections.singletonList(publicEvent), 21L, companion, null);

        publicEvent.name("caller mutation");
        assertEquals("public-event", completed.publicEvents().get(0).getName());
        completed.publicEvents().get(0).name("return mutation");
        assertEquals("public-event", completed.publicEvents().get(0).getName());
        assertTrue(completed.commits());

        ClosureAttemptResult complete = ClosureAttemptResult.complete(completed);
        assertTrue(complete.isComplete());
        assertEquals(Long.valueOf(21L), complete.totalGas());

        ClosureAttemptResult suspended = ClosureAttemptResult.needsResources(
                Arrays.asList("z-blue", "a-blue", "z-blue"));
        assertFalse(suspended.isComplete());
        assertNull(suspended.processResult());
        assertNull(suspended.totalGas());
        assertEquals(Arrays.asList("a-blue", "z-blue"),
                suspended.requiredExactBlueIds());

        assertThrows(IllegalArgumentException.class,
                () -> new ClosureProcessResult(
                        ProcessorStatus.SUCCESS,
                        hash('3'), snapshot.closureIdentity(), hash('4'),
                        2L, snapshot.managedDocuments(), snapshot.components(),
                        snapshot.occurrences(),
                        snapshot.occurrenceBindingSetIdentity(),
                        Collections.<Node>emptyList(), 0L, null, null));
    }

    private static AffectedClosureSnapshot snapshot(
            boolean active,
            Long pendingHistoricalEpoch) {
        return new AffectedClosureSnapshot(
                hash('0'),
                1L,
                Arrays.asList(
                        managed(A, "blue-a", true),
                        managed(B, "blue-b", false)),
                Collections.singletonList(
                        binding(active, pendingHistoricalEpoch)),
                hash('e'),
                components(),
                Collections.singletonList(A));
    }

    private static ManagedDocumentSnapshot managed(
            DocumentId documentId,
            String blueId,
            boolean publicRoot) {
        return new ManagedDocumentSnapshot(
                documentId, blueId, node(documentId.value()),
                true, false, publicRoot, 0L, 1L);
    }

    private static ManagedOccurrenceBinding binding(
            boolean active,
            Long pendingHistoricalEpoch) {
        return new ManagedOccurrenceBinding(
                hash('d'), hash('c'), hash('b'), A,
                ScopeAddress.embedded("/child", 1L), B,
                active || pendingHistoricalEpoch == null
                        ? "blue-b"
                        : "blue-b-old",
                active, pendingHistoricalEpoch);
    }

    private static List<ComponentSnapshot> components() {
        ComponentSnapshot first = new ComponentSnapshot(
                hash('1'), hash('2'), 1L, ComponentKind.ACYCLIC,
                Collections.singletonList(A),
                Collections.singletonList("blue-a"),
                null, null, null);
        ComponentSnapshot second = new ComponentSnapshot(
                hash('3'), hash('4'), 1L, ComponentKind.ACYCLIC,
                Collections.singletonList(B),
                Collections.singletonList("blue-b"),
                null, null, null);
        return Arrays.asList(first, second);
    }

    private static ExecutionPolicy policy(AffectedClosureSnapshot snapshot) {
        Map<DocumentId, Long> limits = new LinkedHashMap<DocumentId, Long>();
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            limits.put(document.documentId(), Long.valueOf(100L));
        }
        return new ExecutionPolicy(hash('a'), 1_000L, limits, "test-policy");
    }

    private static ClosureCommitCompanion companion(
            AffectedClosureSnapshot snapshot) {
        List<ClosureCommitCompanion.ExpectedDocumentHead> expected = Arrays.asList(
                new ClosureCommitCompanion.ExpectedDocumentHead(
                        A, "blue-a", 0L),
                new ClosureCommitCompanion.ExpectedDocumentHead(
                        B, "blue-b", 0L));
        List<ClosureCommitCompanion.ResultingDocumentHead> resulting =
                Collections.singletonList(
                        new ClosureCommitCompanion.ResultingDocumentHead(
                                A, "blue-a", "blue-a-next", 1L));
        return new ClosureCommitCompanion(
                hash('5'), hash('3'), snapshot.closureIdentity(), hash('4'),
                1L, 2L, expected, resulting,
                snapshot.occurrenceBindingSetIdentity(),
                snapshot.occurrenceBindingSetIdentity());
    }

    private static ClosureEnvironment environment(
            String externalOrderPolicyIdentity) {
        return new ClosureEnvironment(
                hash('1'), hash('2'), hash('3'), hash('4'), hash('5'),
                hash('6'), hash('7'), externalOrderPolicyIdentity,
                hash('8'), hash('9'), hash('a'));
    }

    private static Node node(String name) {
        return new Node().name(name);
    }

    private static String hash(char digit) {
        StringBuilder value = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            value.append(digit);
        }
        return value.toString();
    }
}
