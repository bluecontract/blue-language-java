package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.ManagedProcessEmbeddedPath;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only, all-source typed demand discovery for managed occurrences. */
final class ManagedOccurrenceDemandDiscoveryTest {

    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final DocumentId C = new DocumentId("c");
    private static final String POLICY = hash('a');
    private static final String CAUSE = hash('c');
    private static final String CLOSURE = hash('d');
    private static final String DECLARATION = blueId(
            new Node().name("effective Process Embedded declaration"));

    private final ProcessEmbeddedSurfaceReconciler reconciler =
            new ProcessEmbeddedSurfaceReconciler();

    @Test
    void missingPureExactReferenceProducesExactNodeDemandAtItsSourcePath() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        String missingBlueId = blueId(new Node().name("provider target"));

        List<ClosureResourceDemand> demands = reconciler.resourceDemands(
                A,
                source.document().properties(
                        "peer", new Node().blueId(missingBlueId)),
                Collections.singletonList(path("/peer")),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.singletonList(source),
                context(false));

        assertEquals(1, demands.size());
        assertTrue(demands.get(0) instanceof ExactNodeDemand);
        ExactNodeDemand demand = (ExactNodeDemand) demands.get(0);
        assertEquals(missingBlueId, demand.blueId());
        assertEquals(A, demand.sourceDocumentId());
        assertEquals("/peer", demand.logicalPath());
    }

    @Test
    void knownExactContentWithoutFrozenRowProducesOccurrenceDemand() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot target = document(B, "target-b");

        List<ClosureResourceDemand> demands = reconciler.resourceDemands(
                A,
                source.document().properties("peer", target.document()),
                Collections.singletonList(path("/peer")),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Arrays.asList(source, target),
                context(false));

        assertEquals(1, demands.size());
        assertTrue(demands.get(0)
                instanceof ManagedOccurrenceEvidenceDemand);
        ManagedOccurrenceEvidenceDemand demand =
                (ManagedOccurrenceEvidenceDemand) demands.get(0);
        assertEquals(CAUSE, demand.logicalCauseIdentity());
        assertEquals(CLOSURE, demand.inputClosureIdentity());
        assertEquals(7L, demand.inputGraphGeneration());
        assertEquals(A, demand.sourceDocumentId());
        assertEquals("/peer", demand.sourcePath());
        assertEquals(DECLARATION,
                demand.processEmbeddedDeclarationIdentity());
        assertEquals(target.blueId(), demand.suppliedValueBlueId());
        assertTrue(demand.suppliedExactValue().isPresent());
        assertEquals(
                NodeWireForm.get(target.document()),
                NodeWireForm.get(
                        demand.suppliedExactValue().orElseThrow(
                                () -> new AssertionError(
                                        "missing inline resource"))));
    }

    @Test
    void activeRetargetToANonmemberUsesOccurrenceEvidenceDemand() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot prior = document(B, "prior-b");
        ManagedOccurrenceBinding active = binding(
                A, "/peer", 3L, prior, true);
        Node successor = new Node().name("exact nonmember successor");

        List<ClosureResourceDemand> demands = reconciler.resourceDemands(
                A,
                source.document().properties("peer", successor),
                Collections.singletonList(path("/peer")),
                Collections.singletonList(active),
                Arrays.asList(source, prior),
                context(false));

        assertEquals(1, demands.size());
        assertTrue(demands.get(0)
                instanceof ManagedOccurrenceEvidenceDemand);
        assertEquals(blueId(successor),
                demands.get(0).suppliedValueBlueId());
    }

    @Test
    void providerEstablishedExactReferenceSkipsExactDemand() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        String availableBlueId = blueId(
                new Node().name("provider-established target"));

        List<ClosureResourceDemand> demands = reconciler.resourceDemands(
                A,
                source.document().properties(
                        "peer", new Node().blueId(availableBlueId)),
                Collections.singletonList(path("/peer")),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.singletonList(source),
                context(true));

        assertEquals(1, demands.size());
        assertTrue(demands.get(0)
                instanceof ManagedOccurrenceEvidenceDemand);
        assertFalse(demands.get(0) instanceof ExactNodeDemand);
        assertFalse(((ManagedOccurrenceEvidenceDemand) demands.get(0))
                .suppliedExactValue().isPresent(),
                "a pure reference is not retained as inline exact content");
    }

    @Test
    void sameSuppliedValueDemandsExactContentBeforeOccurrenceEvidence() {
        ManagedDocumentSnapshot source = document(A, "causal source");
        ManagedDocumentSnapshot target = document(B, "causal target");
        Node resulting = source.document().properties(
                "peer", new Node().blueId(target.blueId()));
        List<ManagedProcessEmbeddedPath> surface =
                Collections.singletonList(path("/peer"));
        List<ManagedDocumentSnapshot> sourceOnly =
                Collections.singletonList(source);

        List<ClosureResourceDemand> exactFirst =
                reconciler.resourceDemands(
                        A,
                        resulting,
                        surface,
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        sourceOnly,
                        context(false));
        List<ClosureResourceDemand> exactReplay =
                reconciler.resourceDemands(
                        A,
                        resulting,
                        surface,
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        sourceOnly,
                        context(false));
        List<ClosureResourceDemand> occurrenceSecond =
                reconciler.resourceDemands(
                        A,
                        resulting,
                        surface,
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        sourceOnly,
                        context(true));
        List<ClosureResourceDemand> occurrenceReplay =
                reconciler.resourceDemands(
                        A,
                        resulting,
                        surface,
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        sourceOnly,
                        context(true));

        assertEquals(1, exactFirst.size());
        assertTrue(exactFirst.get(0) instanceof ExactNodeDemand);
        assertEquals(target.blueId(),
                ((ExactNodeDemand) exactFirst.get(0)).blueId());
        assertEquals(exactFirst, exactReplay);
        assertEquals(demandIdentities(exactFirst),
                demandIdentities(exactReplay));

        assertEquals(1, occurrenceSecond.size());
        assertTrue(occurrenceSecond.get(0)
                instanceof ManagedOccurrenceEvidenceDemand);
        assertEquals(occurrenceSecond, occurrenceReplay);
        assertEquals(demandIdentities(occurrenceSecond),
                demandIdentities(occurrenceReplay));
        assertEquals(A, exactFirst.get(0).sourceDocumentId());
        assertEquals(A, occurrenceSecond.get(0).sourceDocumentId());
        assertEquals("/peer", exactFirst.get(0).sourcePath());
        assertEquals("/peer", occurrenceSecond.get(0).sourcePath());
        assertEquals(target.blueId(),
                exactFirst.get(0).suppliedValueBlueId());
        assertEquals(target.blueId(),
                occurrenceSecond.get(0).suppliedValueBlueId());

        ManagedOccurrenceBinding evidence = binding(
                A, "/peer", 1L, target, false);
        assertTrue(reconciler.resourceDemands(
                A,
                resulting,
                surface,
                Collections.singletonList(evidence),
                Arrays.asList(source, target),
                context(true)).isEmpty(),
                "prospective occurrence evidence completes acquisition");
    }

    @Test
    void allProjectedRootsAreAggregatedCanonicallyAndRepeatExactly() {
        ManagedDocumentSnapshot a = document(A, "source-a");
        ManagedDocumentSnapshot b = document(B, "source-b");
        ManagedDocumentSnapshot c = document(C, "known-c");
        String missingBlueId = blueId(new Node().name("unknown target"));
        Map<DocumentId, Node> roots =
                new LinkedHashMap<DocumentId, Node>();
        roots.put(B, b.document().properties(
                "peer", new Node().blueId(c.blueId())));
        roots.put(A, a.document()
                .properties("zUnknown", new Node().blueId(missingBlueId))
                .properties("aKnown", c.document()));
        Map<DocumentId, List<ManagedProcessEmbeddedPath>> surfaces =
                new LinkedHashMap<DocumentId,
                        List<ManagedProcessEmbeddedPath>>();
        surfaces.put(B, Collections.singletonList(path("/peer")));
        surfaces.put(A, Arrays.asList(
                path("/zUnknown"), path("/aKnown")));
        List<ManagedDocumentSnapshot> documents = Arrays.asList(a, b, c);

        List<ClosureResourceDemand> first = reconciler.resourceDemands(
                roots,
                surfaces,
                Collections.<ManagedOccurrenceBinding>emptyList(),
                documents,
                context(false));
        List<ClosureResourceDemand> replay = reconciler.resourceDemands(
                roots,
                surfaces,
                Collections.<ManagedOccurrenceBinding>emptyList(),
                documents,
                context(false));

        assertEquals(3, first.size());
        assertEquals(Arrays.asList(
                        "a:/aKnown",
                        "a:/zUnknown",
                        "b:/peer"),
                demandLocations(first));
        assertTrue(first.get(0)
                instanceof ManagedOccurrenceEvidenceDemand);
        assertTrue(first.get(1) instanceof ExactNodeDemand);
        assertTrue(first.get(2)
                instanceof ManagedOccurrenceEvidenceDemand);
        assertEquals(first, replay);
        assertEquals(demandIdentities(first), demandIdentities(replay));
    }

    @Test
    void explicitProspectiveEvidenceClearsDemandAndMatchesClosedActivation() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot target = document(B, "target-b");
        Node resulting = source.document().properties(
                "peer", new Node().blueId(target.blueId()));
        List<ManagedDocumentSnapshot> documents = Arrays.asList(
                source, target);
        List<ManagedProcessEmbeddedPath> surface =
                Collections.singletonList(path("/peer"));

        assertEquals(1, reconciler.resourceDemands(
                A,
                resulting,
                surface,
                Collections.<ManagedOccurrenceBinding>emptyList(),
                documents,
                context(false)).size());

        ManagedOccurrenceBinding evidence = binding(
                A, "/peer", 1L, target, false);
        assertTrue(reconciler.resourceDemands(
                A,
                resulting,
                surface,
                Collections.singletonList(evidence),
                documents,
                context(false)).isEmpty());
        ProcessEmbeddedSurfaceReconciler.Reconciliation committed =
                reconciler.reconcileProjected(
                        A,
                        resulting,
                        surface,
                        Collections.singletonList(evidence),
                        documents,
                        Collections.<ProcessEmbeddedSurfaceReconciler
                                .OccurrencePath>emptySet(),
                        context(false));

        assertEquals(1, committed.bindings().size());
        assertTrue(committed.bindings().get(0).active());
        assertEquals(target.documentId(),
                committed.bindings().get(0).targetDocumentId());
    }

    @Test
    void priorFinalizedReferencePreservesOnlyItsProvenActiveLineage() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot target = document(B, "target-head");
        String priorTargetBlueId = blueId(
                new Node().name("prior finalized target state"));
        Node resulting = source.document().properties(
                "peer", new Node().blueId(priorTargetBlueId));
        ManagedOccurrenceBinding active = binding(
                A, "/peer", 3L, target, true);
        List<ManagedProcessEmbeddedPath> surface =
                Collections.singletonList(path("/peer"));
        List<ManagedDocumentSnapshot> documents = Arrays.asList(
                source, target);
        ProcessEmbeddedSurfaceReconciler.DemandContext sameLineage =
                contextWithPriorFinalizedReference(
                        B, priorTargetBlueId, false);

        assertTrue(reconciler.resourceDemands(
                A,
                resulting,
                surface,
                Collections.singletonList(active),
                documents,
                sameLineage).isEmpty());
        ProcessEmbeddedSurfaceReconciler.Reconciliation reconciled =
                reconciler.reconcileProjected(
                        A,
                        resulting,
                        surface,
                        Collections.singletonList(active),
                        documents,
                        Collections.<ProcessEmbeddedSurfaceReconciler
                                .OccurrencePath>emptySet(),
                        sameLineage);
        assertEquals(1, reconciled.bindings().size());
        ManagedOccurrenceBinding retained = reconciled.bindings().get(0);
        assertEquals(active.occurrenceIdentity(),
                retained.occurrenceIdentity());
        assertEquals(active.bindingIdentity(), retained.bindingIdentity());
        assertEquals(active.targetDocumentId(), retained.targetDocumentId());
        assertEquals(active.activationGeneration(),
                retained.activationGeneration());
        assertTrue(reconciled.transitions().isEmpty());

        List<ClosureResourceDemand> wrongLineage =
                reconciler.resourceDemands(
                        A,
                        resulting,
                        surface,
                        Collections.singletonList(active),
                        documents,
                        contextWithPriorFinalizedReference(
                                C, priorTargetBlueId, false));
        assertEquals(1, wrongLineage.size());
        assertTrue(wrongLineage.get(0) instanceof ExactNodeDemand);

        List<ClosureResourceDemand> providerOnly =
                reconciler.resourceDemands(
                        A,
                        resulting,
                        surface,
                        Collections.singletonList(active),
                        documents,
                        context(true));
        assertEquals(1, providerOnly.size());
        assertTrue(providerOnly.get(0)
                instanceof ManagedOccurrenceEvidenceDemand);
    }

    @Test
    void finalDiscoveryPhysicallyVerifiesAnExactHistoricalReference() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot currentTarget = document(B, "target-head");
        String historicalBlueId = blueId(
                new Node().name("historical target revision"));
        ManagedOccurrenceBinding historical =
                ManagedOccurrenceBinding.derived(
                        POLICY,
                        A,
                        ScopeAddress.embedded("/peer", 3L),
                        B,
                        historicalBlueId,
                        false,
                        Long.valueOf(5L));
        Node resulting = source.document().properties(
                "peer", new Node().blueId(historicalBlueId));
        List<ManagedDocumentSnapshot> documents = Arrays.asList(
                source, currentTarget);
        List<ManagedProcessEmbeddedPath> surface =
                Collections.singletonList(path("/peer"));
        ArrayList<String> requested = new ArrayList<String>();

        assertTrue(reconciler.resourceDemands(
                A,
                resulting,
                surface,
                Collections.singletonList(historical),
                documents,
                context(false, false, requested)).isEmpty());
        assertTrue(requested.isEmpty(),
                "Pre-admission discovery must trust frozen historical evidence");

        List<ClosureResourceDemand> missing = reconciler.resourceDemands(
                A,
                resulting,
                surface,
                Collections.singletonList(historical),
                documents,
                context(false, true, requested));

        assertEquals(Collections.singletonList(historicalBlueId), requested);
        assertEquals(1, missing.size());
        assertTrue(missing.get(0) instanceof ExactNodeDemand);
        assertEquals(ExactNodeDemand.PROVIDER_SOURCE_DOCUMENT_ID,
                missing.get(0).sourceDocumentId());
        assertEquals(ExactNodeDemand.PROVIDER_LOGICAL_PATH,
                missing.get(0).sourcePath());
        assertEquals(historicalBlueId,
                ((ExactNodeDemand) missing.get(0)).blueId());

        requested.clear();
        assertTrue(reconciler.resourceDemands(
                A,
                resulting,
                surface,
                Collections.singletonList(historical),
                documents,
                context(true, true, requested)).isEmpty());
        assertEquals(Collections.singletonList(historicalBlueId), requested);
    }

    @Test
    void typedPreflightLeavesDocumentsBindingsFencesAndGasUnchanged() {
        ManagedDocumentSnapshot source = document(A, "source-a");
        ManagedDocumentSnapshot target = document(B, "target-b");
        Node resulting = source.document().properties(
                "peer", target.document());
        ArrayList<ManagedOccurrenceBinding> bindings =
                new ArrayList<ManagedOccurrenceBinding>();
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>(
                        Arrays.asList(source, target));
        Set<ProcessEmbeddedSurfaceReconciler.OccurrencePath> fences =
                new LinkedHashSet<ProcessEmbeddedSurfaceReconciler
                        .OccurrencePath>();
        Object beforeResulting = NodeWireForm.get(resulting);
        Object beforeSource = NodeWireForm.get(source.document());
        long portableGas = 41L;

        ClosureResourceDemandException suspended = assertThrows(
                ClosureResourceDemandException.class,
                () -> reconciler.reconcileProjected(
                        A,
                        resulting,
                        Collections.singletonList(path("/peer")),
                        bindings,
                        documents,
                        fences,
                        context(false)));

        assertEquals(1, suspended.demands().size());
        assertTrue(suspended.demands().get(0)
                instanceof ManagedOccurrenceEvidenceDemand);
        assertTrue(bindings.isEmpty());
        assertEquals(Arrays.asList(source, target), documents);
        assertTrue(fences.isEmpty());
        assertEquals(beforeResulting, NodeWireForm.get(resulting));
        assertEquals(beforeSource, NodeWireForm.get(source.document()));
        assertEquals(41L, portableGas,
                "Demand discovery has no portable-gas mutation capability");
        assertFalse(suspended.demands().isEmpty());
    }

    private static ProcessEmbeddedSurfaceReconciler.DemandContext context(
            final boolean exactAvailable) {
        return context(exactAvailable, false, null);
    }

    private static ProcessEmbeddedSurfaceReconciler.DemandContext context(
            final boolean exactAvailable,
            boolean verifyHistoricalExactReferences,
            final List<String> requestedBlueIds) {
        return new ProcessEmbeddedSurfaceReconciler.DemandContext(
                CAUSE,
                CLOSURE,
                7L,
                new ProcessEmbeddedSurfaceReconciler
                        .ExactReferenceAvailability() {
                    @Override
                    public boolean isAvailable(String blueId) {
                        if (requestedBlueIds != null) {
                            requestedBlueIds.add(blueId);
                        }
                        return exactAvailable;
                    }
                },
                verifyHistoricalExactReferences);
    }

    private static ProcessEmbeddedSurfaceReconciler.DemandContext
    contextWithPriorFinalizedReference(
            final DocumentId documentId,
            final String exactBlueId,
            boolean verifyHistoricalExactReferences) {
        return new ProcessEmbeddedSurfaceReconciler.DemandContext(
                CAUSE,
                CLOSURE,
                7L,
                new ProcessEmbeddedSurfaceReconciler
                        .ExactReferenceAvailability() {
                    @Override
                    public boolean isAvailable(String blueId) {
                        return false;
                    }
                },
                new ProcessEmbeddedSurfaceReconciler
                        .PriorFinalizedReferenceAvailability() {
                    @Override
                    public boolean isAvailable(
                            DocumentId candidate, String blueId) {
                        return documentId.equals(candidate)
                                && exactBlueId.equals(blueId);
                    }
                },
                verifyHistoricalExactReferences);
    }

    private static ManagedDocumentSnapshot document(
            DocumentId id,
            String name) {
        Node body = new Node().name(name);
        return new ManagedDocumentSnapshot(
                id,
                blueId(body),
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

    private static List<String> demandLocations(
            List<ClosureResourceDemand> demands) {
        ArrayList<String> result = new ArrayList<String>();
        for (ClosureResourceDemand demand : demands) {
            result.add(demand.sourceDocumentId().value()
                    + ":" + demand.sourcePath());
        }
        return result;
    }

    private static List<String> demandIdentities(
            List<ClosureResourceDemand> demands) {
        ArrayList<String> result = new ArrayList<String>();
        for (ClosureResourceDemand demand : demands) {
            result.add(demand.demandIdentity());
        }
        return result;
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static String hash(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
