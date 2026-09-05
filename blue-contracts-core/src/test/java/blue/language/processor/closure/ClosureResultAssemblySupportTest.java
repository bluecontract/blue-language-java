package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ClosureResultAssemblySupportTest {

    @Test
    void shouldPreserveAdmissionMetadataAndDeriveExactFinalizedIdentity() {
        // given
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        Map<DocumentId, Node> bodies = new LinkedHashMap<DocumentId, Node>();
        bodies.put(b, new Node().name("retired child"));
        bodies.put(a, new Node().name("public parent"));
        Map<DocumentId, Long> generations = new LinkedHashMap<DocumentId, Long>();
        generations.put(b, 3L);
        generations.put(a, 2L);
        ComponentFinalizationResult finalized = new ComponentFinalizationKernel()
                .finalizeComponents(new ComponentFinalizationInput(
                        ManagedDocumentGraph.fromBindings(bodies.keySet(),
                                Collections.<ManagedOccurrenceBinding>emptyList()),
                        generations, bodies,
                        Collections.<ManagedOccurrenceBinding>emptyList()));
        ManagedDocumentSnapshot originalA = new ManagedDocumentSnapshot(a,
                finalized.document(a).blueId(), bodies.get(a),
                false, false, true, 7L, 2L);
        ManagedDocumentSnapshot originalB = new ManagedDocumentSnapshot(b,
                finalized.document(b).blueId(), bodies.get(b),
                true, true, false, 11L, 3L);
        AffectedClosureSnapshot before = ClosureEvidenceFactory.affectedClosure(
                5L, Arrays.asList(originalA, originalB),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Arrays.asList(ClosureEvidenceFactory.acyclicComponent(originalA),
                        ClosureEvidenceFactory.acyclicComponent(originalB)),
                Collections.singletonList(a));

        // when
        AffectedClosureSnapshot after = ClosureResultAssemblySupport
                .admissionSnapshot(before, finalized,
                        new java.util.LinkedHashSet<DocumentId>(Arrays.asList(a, b)),
                        before.occurrences(), 6L);

        // then
        assertEquals(6L, after.graphGeneration());
        assertEquals(before.publicRootDocumentIds(), after.publicRootDocumentIds());
        assertEquals(before.occurrenceBindingSetIdentity(),
                after.occurrenceBindingSetIdentity());
        assertEquals(ClosureIdentityService.INSTANCE.affectedClosureIdentity(after),
                after.closureIdentity());
        for (int i = 0; i < before.managedDocuments().size(); i++) {
            ManagedDocumentSnapshot original = before.managedDocuments().get(i);
            ManagedDocumentSnapshot result = after.managedDocuments().get(i);
            assertEquals(original.documentId(), result.documentId());
            assertEquals(true, result.initialized());
            assertEquals(original.terminated(), result.terminated());
            assertEquals(original.publicRoot(), result.publicRoot());
            assertEquals(original.epoch(), result.epoch());
            assertEquals(finalized.document(result.documentId()).componentGeneration(),
                    result.componentGeneration());
            assertEquals(finalized.document(result.documentId()).blueId(), result.blueId());
            assertEquals(NodeWireForm.get(original.document(), NodeWireForm.Strategy.SIMPLE),
                    NodeWireForm.get(result.document(), NodeWireForm.Strategy.SIMPLE));
        }
    }

    @Test
    void projectsPhysicalHostedRuntimeNamespacesToNormativeRuntime() {
        assertEquals(
                GasTraceEntry.Namespace.RUNTIME,
                ClosureResultAssemblySupport.namespace(
                        "coordination.00000000"));
        assertEquals(
                GasTraceEntry.Namespace.RUNTIME,
                ClosureResultAssemblySupport.namespace(
                        "bex.workflow.sha256:fixture.compute.00000000"));
        assertEquals(
                GasTraceEntry.Namespace.RUNTIME,
                ClosureResultAssemblySupport.namespace("runtime"));
    }

    @Test
    void preservesCoreNamespacesAndRejectsMissingPhysicalEvidence() {
        assertEquals(
                GasTraceEntry.Namespace.PROCESSOR,
                ClosureResultAssemblySupport.namespace("processor"));
        assertEquals(
                GasTraceEntry.Namespace.SEMANTIC,
                ClosureResultAssemblySupport.namespace("semantic"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClosureResultAssemblySupport.namespace(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClosureResultAssemblySupport.namespace(null));
    }
}
