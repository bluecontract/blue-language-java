package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real owning Language finalization, not transport-only header fixtures. */
final class ReusableComponentAuthorityTest {
    private static final DocumentId A = new DocumentId("header-a");
    private static final DocumentId B = new DocumentId("header-b");
    private static final DocumentId C = new DocumentId("header-c");
    private static final String POLICY = "sha256:" + String.join("", Collections.nCopies(64, "a"));

    @Test void exactChainSurvivesBodyEvictionWithoutChangingItsIdentityOrOwningMetadata() {
        AffectedClosureSnapshot full = chain();
        AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.singleton(A));
        assertEquals(full.closureIdentity(), sparse.closureIdentity());
        assertEquals(full.occurrenceBindingSetIdentity(), sparse.occurrenceBindingSetIdentity());
        assertEquals(full.components().size(), sparse.components().size());
        assertTrue(sparse.managedDocument(A).hasResidentBody());
        assertFalse(sparse.managedDocument(B).hasResidentBody());
        assertFalse(sparse.managedDocument(C).residentDocument().isPresent());
        assertEquals(3, sparse.reusableComponents().size());
        for (ReusableComponentAuthority authority : sparse.reusableComponents()) {
            authority.verifyUnchanged(sparse);
            for (ManagedDocumentSnapshot header : authority.memberHeaders()) assertFalse(header.hasResidentBody());
        }
        ClosureEvidenceVerifier.verifySnapshot(sparse);
        assertEquals(full.closureIdentity(), sparse.retainResidentBodies(Collections.emptySet()).closureIdentity());
        Node copy = sparse.managedDocument(A).document(); copy.name("caller mutation");
        assertEquals(full.managedDocument(A).document().getName(), sparse.managedDocument(A).document().getName());
    }

    @Test void absentBodyIsNamedAndExactHydrationDoesNotChangeSemanticIdentity() {
        AffectedClosureSnapshot full = chain();
        AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.singleton(A));
        ExecutionEvidenceUnavailableException need = assertThrows(ExecutionEvidenceUnavailableException.class,
                () -> sparse.managedDocument(B).document());
        assertEquals(Collections.singletonList(full.managedDocument(B).blueId()), need.requiredExactBlueIds());
        ManagedReadPin exact = ManagedReadPin.fromExactEvidence(B, full.managedDocument(B).blueId(),
                full.managedDocument(B).document(), null);
        AffectedClosureSnapshot hydrated = sparse.withResidentBody(exact);
        assertTrue(hydrated.managedDocument(B).hasResidentBody());
        assertFalse(hydrated.managedDocument(C).hasResidentBody());
        assertEquals(full.closureIdentity(), hydrated.closureIdentity());
        ClosureEvidenceVerifier.verifySnapshot(hydrated);
        Node newer = new Node().name("different exact head");
        ManagedReadPin wrong = ManagedReadPin.fromExactEvidence(B, DirectBlueIdCalculator.calculateBlueId(newer), newer, null);
        assertThrows(IllegalArgumentException.class, () -> sparse.withResidentBody(wrong));
        ManagedReadPin wrongLineage = ManagedReadPin.fromExactEvidence(C, exact.blueId(), exact.document(), null);
        assertThrows(IllegalArgumentException.class, () -> sparse.managedDocument(B).withResidentBody(wrongLineage));
    }

    @Test void unchangedCertificatesCannotAuthenticateAlteredMarkersBodyOrOutgoingRows() {
        AffectedClosureSnapshot full = chain();
        ManagedDocumentSnapshot b = full.managedDocument(B);
        List<ManagedDocumentSnapshot> forged = new ArrayList<>(full.managedDocuments());
        forged.set(forged.indexOf(b), new ManagedDocumentSnapshot(B, b.blueId(), b.document(), true,
                b.terminated(), b.publicRoot(), b.epoch(), b.componentGeneration()));
        AffectedClosureSnapshot wrongMarkers = replace(full, forged, full.occurrences(), full.components());
        assertThrows(IllegalArgumentException.class, () -> wrongMarkers.retainResidentBodies(Collections.singleton(A)));
        forged.set(forged.indexOf(forged.stream().filter(d -> d.documentId().equals(B)).findFirst().get()),
                new ManagedDocumentSnapshot(B, b.blueId(), new Node().name("forged content"), false, false, true, 0L, 1L));
        AffectedClosureSnapshot wrongBody = replace(full, forged, full.occurrences(), full.components());
        assertThrows(IllegalArgumentException.class, () -> wrongBody.retainResidentBodies(Collections.singleton(A)));

        AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.singleton(A));
        List<ManagedOccurrenceBinding> altered = new ArrayList<>();
        for (ManagedOccurrenceBinding row : sparse.occurrences()) altered.add(row.sourceDocumentId().equals(B)
                ? ManagedOccurrenceBinding.derived(row.bindingPolicyIdentity(), row.sourceDocumentId(),
                    ScopeAddress.embedded(row.sourcePath(), row.activationGeneration() + 1L),
                    row.targetDocumentId(), row.expectedTargetBlueId(), row.active(), null) : row);
        AffectedClosureSnapshot wrongTopology = replace(sparse, sparse.managedDocuments(), altered, sparse.components());
        assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceVerifier.verifySnapshot(wrongTopology));
    }

    @Test void sparseFinalizationChangesOwnedRootWithoutAcquiringUntouchedTransitiveBodies() {
        AffectedClosureSnapshot full = chain();
        AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.singleton(A));
        Node changed = sparse.managedDocument(A).document().name("changed parent");
        ComponentFinalizationResult hot = finalize(full, bodyMap(A, changed, B, full.managedDocument(B).document(),
                C, full.managedDocument(C).document()), full.occurrences(), false);
        ComponentFinalizationResult cold = finalize(sparse, bodyMap(A, changed), sparse.occurrences(), true);
        for (DocumentId id : Arrays.asList(A, B, C)) assertEquals(hot.document(id).blueId(), cold.document(id).blueId());
        assertFalse(cold.document(B).hasResidentBody());
        assertFalse(cold.document(C).hasResidentBody());
        assertEquals(hot.finalizedGraph().bindings().size(), cold.finalizedGraph().bindings().size());
        assertTrue(cold.document(B).reusableAuthority().isPresent());
    }

    @Test void changedDependencyRequiresAffectedAbsentSourceBodyInsteadOfLatestOrSyntheticContent() {
        AffectedClosureSnapshot full = chain();
        AffectedClosureSnapshot sparse = full.retainResidentBodies(new java.util.HashSet<>(Arrays.asList(A, C)));
        Map<DocumentId, Node> bodies = bodyMap(A, sparse.managedDocument(A).document(),
                C, sparse.managedDocument(C).document().name("changed child"));
        ExecutionEvidenceUnavailableException need = assertThrows(ExecutionEvidenceUnavailableException.class,
                () -> finalize(sparse, bodies, sparse.occurrences(), true));
        assertEquals(Collections.singletonList(full.managedDocument(B).blueId()), need.requiredExactBlueIds());
    }

    @Test void cyclicProofPayloadCanBeAbsentAndCanonicalNormalizationStillHasTheExactHotIdentity() {
        AffectedClosureSnapshot full = cycle("cycle");
        AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.singleton(A));
        ComponentSnapshot header = sparse.component(A);
        assertFalse(header.hasResidentCyclicProof());
        assertThrows(ExecutionEvidenceUnavailableException.class, header::completeCyclicProof);
        assertEquals(full.closureIdentity(), sparse.closureIdentity());
        assertEquals(full.component(A).cyclicProofIdentity(), header.cyclicProofIdentity());
        ClosureEvidenceVerifier.verifySnapshot(sparse);
        AffectedClosureSnapshot normalized = sparse.canonicalSemanticView();
        assertEquals(full.canonicalSemanticView().closureIdentity(), normalized.closureIdentity());
        assertTrue(normalized.managedDocument(A).hasResidentBody());
        assertFalse(normalized.managedDocument(B).hasResidentBody());
        assertFalse(normalized.component(A).hasResidentCyclicProof());
        ClosureEvidenceVerifier.verifySnapshot(normalized);
        ComponentFinalizationResult unchanged = finalize(sparse, bodyMap(A, sparse.managedDocument(A).document()), sparse.occurrences(), true);
        assertFalse(unchanged.document(B).hasResidentBody());
        Node mutated = sparse.managedDocument(A).document().name("mutated cyclic member");
        ExecutionEvidenceUnavailableException need = assertThrows(ExecutionEvidenceUnavailableException.class,
                () -> finalize(sparse, bodyMap(A, mutated), sparse.occurrences(), true));
        assertEquals(Collections.singletonList(full.managedDocument(B).blueId()), need.requiredExactBlueIds());
    }

    @Test void foreignCyclicProofCannotBeTurnedIntoAReusableHeader() {
        AffectedClosureSnapshot full = cycle("original");
        ComponentSnapshot c = full.component(A), other = cycle("other").component(A);
        ComponentSnapshot forged = new ComponentSnapshot(c.componentIdentity(), c.componentStateIdentity(), c.componentGeneration(),
                c.kind(), c.orderedMemberDocumentIds(), c.orderedMemberBlueIds(), c.masterBlueId(),
                other.completeCyclicProof(), c.cyclicProofIdentity());
        AffectedClosureSnapshot asserted = new AffectedClosureSnapshot(full.closureIdentity(), full.graphGeneration(),
                full.managedDocuments(), full.occurrences(), full.occurrenceBindingSetIdentity(), Collections.singletonList(forged), full.publicRootDocumentIds());
        assertThrows(IllegalArgumentException.class, () -> asserted.retainResidentBodies(Collections.emptySet()));
    }

    @Test void promotionIntoANewSccDemandsAllMissingActualOwnedMembers() {
        String authoredA = DirectBlueIdCalculator.calculateBlueId(new Node().name("authored-a"));
        String authoredB = DirectBlueIdCalculator.calculateBlueId(new Node().name("authored-b"));
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(A, new Node().name("a").properties("child", new Node().blueId(authoredB)));
        bodies.put(B, new Node().name("b").properties("parent", new Node().blueId(authoredA)));
        List<ManagedOccurrenceBinding> rows = Arrays.asList(binding(A, "/child", B, authoredB, false),
                binding(B, "/parent", A, authoredA, false));
        AffectedClosureSnapshot full = snapshot(bodies, rows), sparse = full.retainResidentBodies(Collections.singleton(A));
        List<ManagedOccurrenceBinding> joined = new ArrayList<>();
        for (ManagedOccurrenceBinding row : sparse.occurrences()) joined.add(ManagedOccurrenceBinding.derived(
                row.bindingPolicyIdentity(), row.sourceDocumentId(), row.sourceAddress(), row.targetDocumentId(),
                sparse.managedDocument(row.targetDocumentId()).blueId(), true, null));
        ExecutionEvidenceUnavailableException need = assertThrows(ExecutionEvidenceUnavailableException.class,
                () -> finalize(sparse, bodyMap(A, sparse.managedDocument(A).document()), joined, true));
        assertEquals(Collections.singletonList(full.managedDocument(B).blueId()), need.requiredExactBlueIds());
    }

    static AffectedClosureSnapshot chain() {
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(A, new Node().name("a").properties("child", new Node().blueId("old-b")));
        bodies.put(B, new Node().name("b").properties("child", new Node().blueId("old-c")));
        bodies.put(C, new Node().name("c"));
        return snapshot(bodies, Arrays.asList(binding(A, "/child", B, "old-b", true), binding(B, "/child", C, "old-c", true)));
    }
    static AffectedClosureSnapshot cycle(String label) {
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(A, new Node().name(label + "-a").properties("child", new Node().blueId("old-b")));
        bodies.put(B, new Node().name(label + "-b").properties("parent", new Node().blueId("old-a")));
        return snapshot(bodies, Arrays.asList(binding(A, "/child", B, "old-b", true), binding(B, "/parent", A, "old-a", true)));
    }
    private static ManagedOccurrenceBinding binding(DocumentId source, String path, DocumentId target, String blueId, boolean active) {
        return ManagedOccurrenceBinding.derived(POLICY, source, ScopeAddress.embedded(path, 1L), target, blueId, active, null);
    }
    static AffectedClosureSnapshot snapshot(Map<DocumentId, Node> bodies, List<ManagedOccurrenceBinding> rows) {
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(bodies.keySet(), rows);
        Map<DocumentId, Long> generations = new TreeMap<>(); bodies.keySet().forEach(id -> generations.put(id, 1L));
        ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                new ComponentFinalizationInput(graph, generations, bodies, rows));
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (FinalizedDocumentEvidence exact : finalized.documents().values()) documents.add(new ManagedDocumentSnapshot(
                exact.documentId(), exact.blueId(), exact.document(), false, false, true, 0L, exact.componentGeneration()));
        List<ComponentSnapshot> components = new ArrayList<>();
        for (FinalizedComponentEvidence component : finalized.components()) components.add(component.component());
        return ClosureEvidenceFactory.affectedClosure(1L, documents, finalized.finalizedGraph().bindings(), components,
                new ArrayList<>(bodies.keySet()));
    }
    private static AffectedClosureSnapshot replace(AffectedClosureSnapshot base, List<ManagedDocumentSnapshot> documents,
            List<ManagedOccurrenceBinding> rows, List<ComponentSnapshot> components) {
        return ClosureEvidenceFactory.affectedClosure(base.graphGeneration(), documents, rows, components, base.publicRootDocumentIds());
    }
    private static ComponentFinalizationResult finalize(AffectedClosureSnapshot snapshot, Map<DocumentId, Node> bodies,
            List<ManagedOccurrenceBinding> rows, boolean reuse) {
        Map<DocumentId, Long> generations = new TreeMap<>();
        snapshot.managedDocuments().forEach(d -> generations.put(d.documentId(), d.componentGeneration()));
        return new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                ManagedDocumentGraph.fromBindings(generations.keySet(), snapshot.occurrences()), generations, bodies, rows,
                Collections.emptyMap(), reuse ? snapshot.reusableComponents() : Collections.emptyList()));
    }

    private static Map<DocumentId, Node> bodyMap(Object... pairs) {
        Map<DocumentId, Node> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((DocumentId) pairs[i], (Node) pairs[i + 1]);
        return result;
    }
}
