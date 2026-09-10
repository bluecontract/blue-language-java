package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ManagedProcessEmbeddedPath;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.SubscriptionSurfaceInvalidException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct occurrence-lifecycle proofs for dynamic Process Embedded surfaces. */
final class ProcessEmbeddedSurfaceReconcilerTest {

    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final DocumentId C = new DocumentId("c");
    private static final DocumentId D = new DocumentId("d");
    private static final String POLICY = sha256('a');
    private static final String DECLARATION = blueId("declaration");

    private final ProcessEmbeddedSurfaceReconciler reconciler =
            new ProcessEmbeddedSurfaceReconciler();

    @Test
    void shouldActivateDirectCollectionAndReservedPathsDeterministically() {
        // given
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot peer = document(B, "peer-b");
        ManagedDocumentSnapshot alpha = document(C, "child-alpha");
        ManagedDocumentSnapshot zeta = document(D, "child-zeta");
        Node resultingSource = source.document()
                .properties("peer", peer.document())
                .properties("children", new Node()
                        .properties("z", zeta.document())
                        .properties("a", alpha.document()));
        List<ManagedOccurrenceBinding> reservations = Arrays.asList(
                binding(A, "/children/z", 1L, zeta, false),
                binding(A, "/peer", 1L, peer, false),
                binding(A, "/children/a", 1L, alpha, false));
        List<ManagedProcessEmbeddedPath> projected = Arrays.asList(
                path("/peer"),
                path("/children/z"),
                path("/children/a"));

        // when
        ProcessEmbeddedSurfaceReconciler.Reconciliation result =
                reconciler.reconcileProjected(
                        A,
                        resultingSource,
                        projected,
                        reservations,
                        Arrays.asList(source, peer, alpha, zeta),
                        noFences());

        // then
        assertTrue(only(result.bindings(), A, "/peer").active());
        assertTrue(only(result.bindings(), A, "/children/a").active());
        assertTrue(only(result.bindings(), A, "/children/z").active());
        assertEquals(
                Arrays.asList(
                        "/children/a", "/children/z", "/peer"),
                transitionPaths(result.transitions()));
        for (ProcessEmbeddedSurfaceReconciler.OccurrenceTransition transition
                : result.transitions()) {
            assertEquals(
                    ProcessEmbeddedSurfaceReconciler
                            .OccurrenceTransition.Kind.ADD,
                    transition.kind());
        }
        assertEquals(3, result.activatedOccurrenceIdentities().size());
        assertTrue(result.retiredOccurrencePaths().isEmpty());
    }

    @Test
    void shouldPreserveExistingCollectionMemberAndActivateOnlyNewMember() {
        // given
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot alpha = document(B, "child-alpha");
        ManagedDocumentSnapshot beta = document(C, "child-beta");
        ManagedOccurrenceBinding existing =
                binding(A, "/children/alpha", 3L, alpha, true);
        ManagedOccurrenceBinding reservation =
                binding(A, "/children/beta", 1L, beta, false);
        Node resultingSource = source.document()
                .properties("children", new Node()
                        .properties("alpha", alpha.document())
                        .properties("beta", beta.document()));

        // when
        ProcessEmbeddedSurfaceReconciler.Reconciliation result =
                reconciler.reconcileProjected(
                        A,
                        resultingSource,
                        Arrays.asList(
                                path("/children/alpha"),
                                path("/children/beta")),
                        Arrays.asList(existing, reservation),
                        Arrays.asList(source, alpha, beta),
                        noFences());
        ManagedOccurrenceBinding retained = only(
                result.bindings(), A, "/children/alpha");
        ManagedOccurrenceBinding activated = only(
                result.bindings(), A, "/children/beta");

        // then
        assertEquals(existing.occurrenceIdentity(),
                retained.occurrenceIdentity());
        assertEquals(existing.bindingIdentity(), retained.bindingIdentity());
        assertEquals(3L, retained.activationGeneration());
        assertTrue(retained.active());
        assertTrue(activated.active());
        assertEquals(1L, activated.activationGeneration());
        assertEquals(1, result.transitions().size());
        assertEquals(
                ProcessEmbeddedSurfaceReconciler
                        .OccurrenceTransition.Kind.ADD,
                result.transitions().get(0).kind());
        assertEquals("/children/beta",
                result.transitions().get(0).sourcePath());
        assertEquals(Collections.singleton(
                        activated.occurrenceIdentity()),
                result.activatedOccurrenceIdentities());
        assertTrue(result.retiredOccurrencePaths().isEmpty());
    }

    @Test
    void shouldRetainProspectiveDirectMemberWhileCollectionIsAbsentAndActivateItWithTheSubtree() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot target = document(B, "order-one");
        ManagedOccurrenceBinding reservation =
                binding(A, "/orders/order-1", 1L, target, false);

        ProcessEmbeddedSurfaceReconciler.Reconciliation absent =
                reconciler.reconcileProjected(
                        A,
                        source.document(),
                        Collections.<ManagedProcessEmbeddedPath>emptyList(),
                        Collections.singletonList(reservation),
                        Arrays.asList(source, target),
                        noFences());

        ManagedOccurrenceBinding retained = only(
                absent.bindings(), A, "/orders/order-1");
        assertEquals(reservation.occurrenceIdentity(),
                retained.occurrenceIdentity());
        assertEquals(reservation.bindingIdentity(),
                retained.bindingIdentity());
        assertFalse(retained.active());
        assertTrue(absent.transitions().isEmpty());
        assertTrue(absent.activatedOccurrenceIdentities().isEmpty());
        assertFalse(ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B), absent.bindings()).hasEdge(A, B));

        Node withCompleteSubtree = source.document()
                .properties("orders", new Node().properties(
                        "order-1", target.document()));
        ProcessEmbeddedSurfaceReconciler.Reconciliation introduced =
                reconciler.reconcileProjected(
                        A,
                        withCompleteSubtree,
                        Collections.singletonList(
                                path("/orders/order-1")),
                        absent.bindings(),
                        Arrays.asList(source, target),
                        noFences());

        ManagedOccurrenceBinding activated = only(
                introduced.bindings(), A, "/orders/order-1");
        assertTrue(activated.active());
        assertEquals(retained.activationGeneration(),
                activated.activationGeneration());
        assertEquals(retained.occurrenceIdentity(),
                activated.occurrenceIdentity());
        assertEquals(retained.bindingIdentity(),
                activated.bindingIdentity());
        assertEquals(Collections.singleton(
                        activated.occurrenceIdentity()),
                introduced.activatedOccurrenceIdentities());
        assertEquals(1, introduced.transitions().size());
        assertEquals(
                ProcessEmbeddedSurfaceReconciler
                        .OccurrenceTransition.Kind.ADD,
                introduced.transitions().get(0).kind());
        assertTrue(ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B), introduced.bindings()).hasEdge(A, B));
    }

    @Test
    void shouldRetireRemovedDeclarationAndWholeContractToPassiveContent() {
        // given
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot peer = document(B, "peer-b");
        ManagedOccurrenceBinding active =
                binding(A, "/peer", 1L, peer, true);
        List<ProcessEmbeddedSurfaceReconciler.Reconciliation> results =
                new ArrayList<
                        ProcessEmbeddedSurfaceReconciler.Reconciliation>();

        // when
        for (Node resultingSource : Arrays.asList(
                source.document(),
                source.document().properties("peer", peer.document()))) {
            results.add(
                    reconciler.reconcileProjected(
                            A,
                            resultingSource,
                            Collections.<ManagedProcessEmbeddedPath>emptyList(),
                            Collections.singletonList(active),
                            Arrays.asList(source, peer),
                            noFences()));
        }

        // then
        for (ProcessEmbeddedSurfaceReconciler.Reconciliation result
                : results) {
            ManagedOccurrenceBinding retired = only(
                    result.bindings(), A, "/peer");
            assertFalse(retired.active());
            assertEquals(2L, retired.activationGeneration());
            assertNotEquals(active.occurrenceIdentity(),
                    retired.occurrenceIdentity());
            assertEquals(1, result.transitions().size());
            assertEquals(
                    ProcessEmbeddedSurfaceReconciler
                            .OccurrenceTransition.Kind.REMOVE,
                    result.transitions().get(0).kind());
            assertEquals(Collections.singleton(
                            new ProcessEmbeddedSurfaceReconciler
                                    .OccurrencePath(A, "/peer")),
                    result.retiredOccurrencePaths());
        }
    }

    @Test
    void shouldUseFreshGenerationAndForbidSameInvocationReactivation() {
        // given
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot peer = document(B, "peer-b");
        List<ManagedDocumentSnapshot> documents = Arrays.asList(source, peer);
        ProcessEmbeddedSurfaceReconciler.Reconciliation removed =
                reconciler.reconcileProjected(
                        A,
                        source.document(),
                        Collections.<ManagedProcessEmbeddedPath>emptyList(),
                        Collections.singletonList(
                                binding(A, "/peer", 1L, peer, true)),
                        documents,
                        noFences());
        Node readded = source.document()
                .properties("peer", peer.document());

        // when
        ClosureCapabilityGapException sameInvocation =
                captureFailure(
                () -> reconciler.reconcileProjected(
                        A,
                        readded,
                        Collections.singletonList(path("/peer")),
                        removed.bindings(),
                        documents,
                        removed.retiredOccurrencePaths()));
        ProcessEmbeddedSurfaceReconciler.Reconciliation later =
                reconciler.reconcileProjected(
                        A,
                        readded,
                        Collections.singletonList(path("/peer")),
                        removed.bindings(),
                        documents,
                        noFences());
        ManagedOccurrenceBinding active = only(
                later.bindings(), A, "/peer");

        // then
        assertNotNull(sameInvocation);
        assertEquals(
                "PROCESS_EMBEDDED_REACTIVATION_REQUIRES_LATER_INVOCATION",
                sameInvocation.code());
        assertTrue(active.active());
        assertEquals(2L, active.activationGeneration());
        assertEquals(
                ProcessEmbeddedSurfaceReconciler
                        .OccurrenceTransition.Kind.ADD,
                later.transitions().get(0).kind());
    }

    @Test
    void shouldRetargetOnceAndKeepLineageForExactStateAdvance() {
        // given
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot p1 = document(B, "target-p1");
        ManagedDocumentSnapshot p2 = document(C, "target-p2");
        ManagedOccurrenceBinding before =
                binding(A, "/peer", 4L, p1, true);

        // when
        ProcessEmbeddedSurfaceReconciler.Reconciliation retargeted =
                reconciler.reconcileProjected(
                        A,
                        source.document().properties("peer", p2.document()),
                        Collections.singletonList(path("/peer")),
                        Collections.singletonList(before),
                        Arrays.asList(source, p1, p2),
                        noFences());
        ManagedOccurrenceBinding after = only(
                retargeted.bindings(), A, "/peer");

        // then
        assertEquals(C, after.targetDocumentId());
        assertEquals(5L, after.activationGeneration());
        assertNotEquals(before.occurrenceIdentity(),
                after.occurrenceIdentity());
        assertNull(before.pendingHistoricalEpoch());
        assertNull(after.pendingHistoricalEpoch());
        assertEquals(1, retargeted.transitions().size());
        assertEquals(
                ProcessEmbeddedSurfaceReconciler
                        .OccurrenceTransition.Kind.REBIND,
                retargeted.transitions().get(0).kind());
        assertEquals(before,
                retargeted.transitions().get(0).before());
        assertEquals(after,
                retargeted.transitions().get(0).after());
        assertEquals(Collections.singleton(after.occurrenceIdentity()),
                retargeted.activatedOccurrenceIdentities());
        assertEquals(Collections.singleton(
                        new ProcessEmbeddedSurfaceReconciler
                                .OccurrencePath(A, "/peer")),
                retargeted.retiredOccurrencePaths());

        ManagedDocumentSnapshot oldState = document(B, "old-state");
        ManagedDocumentSnapshot newState = document(B, "new-state");
        ManagedOccurrenceBinding oldBinding =
                binding(A, "/same", 3L, oldState, true);
        ProcessEmbeddedSurfaceReconciler.Reconciliation stateAdvance =
                reconciler.reconcileProjected(
                        A,
                        source.document().properties(
                                "same", newState.document()),
                        Collections.singletonList(path("/same")),
                        Collections.singletonList(oldBinding),
                        Arrays.asList(source, newState),
                        noFences());
        ManagedOccurrenceBinding rebound = only(
                stateAdvance.bindings(), A, "/same");
        assertEquals(oldBinding.occurrenceIdentity(),
                rebound.occurrenceIdentity());
        assertEquals(3L, rebound.activationGeneration());
        assertNotEquals(oldBinding.bindingIdentity(),
                rebound.bindingIdentity());
        assertEquals(
                ProcessEmbeddedSurfaceReconciler
                        .OccurrenceTransition.Kind.REBIND,
                stateAdvance.transitions().get(0).kind());
    }

    @Test
    void shouldDissolveSplitAndFormCyclesFromReconciledBindings() {
        // given
        ManagedDocumentSnapshot a = document(A, "doc-a");
        ManagedDocumentSnapshot b = document(B, "doc-b");
        ManagedDocumentSnapshot c = document(C, "doc-c");
        ManagedDocumentSnapshot d = document(D, "doc-d");
        List<ManagedDocumentSnapshot> documents = Arrays.asList(a, b, c, d);

        List<ManagedOccurrenceBinding> fourCycle = Arrays.asList(
                binding(A, "/next", 1L, b, true),
                binding(B, "/next", 1L, c, true),
                binding(C, "/next", 1L, d, true),
                binding(D, "/next", 1L, a, true));

        // when
        ProcessEmbeddedSurfaceReconciler.Reconciliation split =
                reconciler.reconcileProjected(
                        B,
                        b.document().properties("next", c.document()),
                        Collections.<ManagedProcessEmbeddedPath>emptyList(),
                        fourCycle,
                        documents,
                        noFences());
        ManagedDocumentGraph splitGraph = ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B, C, D), split.bindings());

        // then
        assertFalse(splitGraph.hasEdge(B, C));
        assertEquals(4, new SccPartitioner().partition(splitGraph).size());

        List<ManagedOccurrenceBinding> oneWay = Arrays.asList(
                binding(A, "/peer", 1L, b, true),
                binding(B, "/peer", 1L, a, false));
        ProcessEmbeddedSurfaceReconciler.Reconciliation formed =
                reconciler.reconcileProjected(
                        B,
                        b.document().properties("peer", a.document()),
                        Collections.singletonList(path("/peer")),
                        oneWay,
                        Arrays.asList(a, b),
                        noFences());
        ManagedDocumentGraph cycle = ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B), formed.bindings());
        assertTrue(cycle.hasEdge(A, B));
        assertTrue(cycle.hasEdge(B, A));
        assertEquals(1, new SccPartitioner().partition(cycle).size());

        ProcessEmbeddedSurfaceReconciler.Reconciliation dissolved =
                reconciler.reconcileProjected(
                        B,
                        b.document().properties("peer", a.document()),
                        Collections.<ManagedProcessEmbeddedPath>emptyList(),
                        formed.bindings(),
                        Arrays.asList(a, b),
                        noFences());
        ManagedDocumentGraph separate = ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B), dissolved.bindings());
        assertTrue(separate.hasEdge(A, B));
        assertFalse(separate.hasEdge(B, A));
        assertEquals(2, new SccPartitioner().partition(separate).size());
    }

    @Test
    void shouldFailBeforeReplacementWhenFrozenEvidenceIsMissingOrWrong() {
        // given
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot peer = document(B, "peer-b");
        Node resultingSource = source.document()
                .properties("peer", peer.document());
        ManagedDocumentSnapshot other = document(C, "other");

        // when
        SubscriptionSurfaceInvalidException missing =
                captureFailure(
                () -> reconciler.reconcileProjected(
                        A,
                        resultingSource,
                        Collections.singletonList(path("/peer")),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        Arrays.asList(source, peer),
                        noFences()));
        InvalidExecutionEvidenceException wrong =
                captureFailure(
                () -> reconciler.reconcileProjected(
                        A,
                        resultingSource,
                        Collections.singletonList(path("/peer")),
                        Collections.singletonList(
                                binding(A, "/peer", 1L, other, false)),
                        Arrays.asList(source, peer, other),
                        noFences()));

        // then
        assertNotNull(missing);
        assertTrue(missing.getMessage().contains(
                "lacks frozen occurrence evidence"));
        assertNotNull(wrong);
        assertEquals(ProcessorErrorCategory.ManagedOccurrenceBindingMissing,
                wrong.errorCategory());
    }

    @Test
    void shouldPreservePendingHistoricalReferenceAndInlineExactValue() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot current = document(B, "current-b");
        Node historical = new Node().name("authored-b");
        String historicalBlueId =
                DirectBlueIdCalculator.calculateBlueId(historical);
        ManagedOccurrenceBinding pending = ManagedOccurrenceBinding.derived(
                POLICY,
                A,
                ScopeAddress.embedded("/peer", 1L),
                B,
                historicalBlueId,
                false,
                Long.valueOf(-1L));

        for (Node value : Arrays.asList(
                new Node().blueId(historicalBlueId),
                historical.clone())) {
            ProcessEmbeddedSurfaceReconciler.Reconciliation result =
                    reconciler.reconcileProjected(
                            A,
                            source.document().properties("peer", value),
                            Collections.singletonList(path("/peer")),
                            Collections.singletonList(pending),
                            Arrays.asList(source, current),
                            noFences());
            ManagedOccurrenceBinding retained = only(
                    result.bindings(), A, "/peer");
            assertEquals(pending.occurrenceIdentity(),
                    retained.occurrenceIdentity());
            assertEquals(pending.bindingIdentity(),
                    retained.bindingIdentity());
            assertFalse(retained.active());
            assertEquals(Long.valueOf(-1L),
                    retained.pendingHistoricalEpoch());
            assertTrue(result.transitions().isEmpty());
            assertTrue(result.activatedOccurrenceIdentities().isEmpty());
        }

        ClosureCapabilityGapException mismatch = captureFailure(
                () -> reconciler.reconcileProjected(
                        A,
                        source.document().properties(
                                "peer", new Node().name("wrong-b")),
                        Collections.singletonList(path("/peer")),
                        Collections.singletonList(pending),
                        Arrays.asList(source, current),
                        noFences()));
        assertNotNull(mismatch);
        assertEquals("NEW_OCCURRENCE_ADMISSION_REQUIRED",
                mismatch.code());
    }

    @Test
    void shouldRebindCommittedRetirementToExactCurrentStateWithoutRetargeting() {
        // given
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot prior = document(B, "prior-b");
        ManagedDocumentSnapshot current = document(B, "current-b");
        ProcessEmbeddedSurfaceReconciler.Reconciliation removed = reconciler.reconcileProjected(
                A, source.document(), Collections.<ManagedProcessEmbeddedPath>emptyList(),
                Collections.singletonList(binding(A, "/peer", 1L, prior, true)),
                Arrays.asList(source, prior), noFences());
        ManagedOccurrenceBinding reserved = only(removed.bindings(), A, "/peer");
        Node readded = source.document().properties("peer", current.document());

        // when
        ProcessEmbeddedSurfaceReconciler.Reconciliation result = reconciler.reconcileProjected(
                A, readded, Collections.singletonList(path("/peer")), removed.bindings(),
                Arrays.asList(source, current), noFences());
        ManagedOccurrenceBinding active = only(result.bindings(), A, "/peer");

        // then
        assertTrue(active.active());
        assertEquals(2L, active.activationGeneration());
        assertEquals(reserved.occurrenceIdentity(), active.occurrenceIdentity());
        assertEquals(current.blueId(), active.expectedTargetBlueId());
        assertFalse(reserved.bindingIdentity().equals(active.bindingIdentity()));
        assertEquals(1, result.transitions().size());
        assertEquals(Collections.singleton(active.occurrenceIdentity()), result.activatedOccurrenceIdentities());
        org.junit.jupiter.api.Assertions.assertThrows(ClosureCapabilityGapException.class,
                () -> reconciler.reconcileProjected(A, readded, Collections.singletonList(path("/peer")),
                        removed.bindings(), Arrays.asList(source, current), removed.retiredOccurrencePaths()));
        // A fresh prospective row cannot acquire different exact draft bytes through this rule.
        org.junit.jupiter.api.Assertions.assertThrows(InvalidExecutionEvidenceException.class,
                () -> reconciler.reconcileProjected(A, readded, Collections.singletonList(path("/peer")),
                        Collections.singletonList(binding(A, "/peer", 1L, prior, false)),
                        Arrays.asList(source, current), noFences()));
        ManagedDocumentSnapshot draft = new ManagedDocumentSnapshot(B, current.blueId(), current.document(),
                false, false, false, 0L, 0L);
        org.junit.jupiter.api.Assertions.assertThrows(InvalidExecutionEvidenceException.class,
                () -> reconciler.reconcileProjected(A, readded, Collections.singletonList(path("/peer")),
                        removed.bindings(), Arrays.asList(source, draft), noFences()));
        // The actual inserted bytes still must establish the reserved lineage's frozen target.
        ManagedDocumentSnapshot unrelated = document(C, "unrelated-c");
        for (Node wrong : Arrays.asList(prior.document(), unrelated.document(), new Node().name("forged"))) {
            org.junit.jupiter.api.Assertions.assertThrows(InvalidExecutionEvidenceException.class,
                    () -> reconciler.reconcileProjected(A, source.document().properties("peer", wrong),
                            Collections.singletonList(path("/peer")), removed.bindings(),
                            Arrays.asList(source, current, unrelated), noFences()));
        }
    }

    private static ManagedDocumentSnapshot document(
            DocumentId id,
            String name) {
        Node body = new Node().name(name);
        return new ManagedDocumentSnapshot(
                id,
                DirectBlueIdCalculator.calculateBlueId(body),
                body,
                true,
                false,
                false,
                0L,
                0L);
    }

    private static ManagedOccurrenceBinding binding(
            DocumentId source,
            String path,
            long generation,
            ManagedDocumentSnapshot target,
            boolean active) {
        return ManagedOccurrenceBinding.derived(
                POLICY,
                source,
                ScopeAddress.embedded(path, generation),
                target.documentId(),
                target.blueId(),
                active,
                null);
    }

    private static ManagedProcessEmbeddedPath path(String path) {
        return new ManagedProcessEmbeddedPath(path, DECLARATION);
    }

    private static Set<ProcessEmbeddedSurfaceReconciler.OccurrencePath>
    noFences() {
        return Collections.emptySet();
    }

    private static ManagedOccurrenceBinding only(
            List<ManagedOccurrenceBinding> bindings,
            DocumentId source,
            String path) {
        List<ManagedOccurrenceBinding> matches =
                new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : bindings) {
            if (source.equals(binding.sourceDocumentId())
                    && path.equals(binding.sourcePath())) {
                matches.add(binding);
            }
        }
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private static List<String> transitionPaths(
            List<ProcessEmbeddedSurfaceReconciler.OccurrenceTransition>
                    transitions) {
        List<String> result = new ArrayList<String>();
        for (ProcessEmbeddedSurfaceReconciler.OccurrenceTransition transition
                : transitions) {
            result.add(transition.sourcePath());
        }
        return result;
    }

    private static String blueId(String value) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value(value));
    }

    private static String sha256(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T captureFailure(
            ThrowingAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return (T) failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}
