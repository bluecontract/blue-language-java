package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedDocumentGraphTest {

    @Test
    void shouldBuildCanonicalDeeplyImmutableActiveGraph() {
        DocumentId a = document("a");
        DocumentId b = document("b");
        List<DocumentId> inputDocuments = new ArrayList<DocumentId>(
                Arrays.asList(b, a));
        List<ManagedOccurrenceBinding> inputBindings =
                new ArrayList<ManagedOccurrenceBinding>(Arrays.asList(
                        binding(2, a, b, false),
                        binding(1, b, a, true)));

        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                inputDocuments, inputBindings);

        assertEquals(Arrays.asList(a, b), graph.documentIds());
        assertEquals(2, graph.bindings().size());
        assertEquals(1, graph.activeBindings().size());
        assertEquals(Collections.singletonList(a), graph.adjacency().get(b));
        assertEquals(Collections.emptyList(), graph.adjacency().get(a));
        assertTrue(graph.hasEdge(b, a));
        assertFalse(graph.hasEdge(a, b));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.documentIds().add(document("c")));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.adjacency().put(a, Collections.singletonList(b)));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.adjacency().get(b).add(b));

        inputDocuments.clear();
        inputBindings.clear();
        assertEquals(Arrays.asList(a, b), graph.documentIds());
        assertEquals(2, graph.bindings().size());
    }

    @Test
    void shouldVerifyInactiveBindingEndpointsToo() {
        DocumentId a = document("a");
        DocumentId missing = document("missing");

        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocumentGraph.fromBindings(
                        Collections.singletonList(a),
                        Collections.singletonList(
                                binding(1, missing, a, false))));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocumentGraph.fromBindings(
                        Collections.singletonList(a),
                        Collections.singletonList(
                                binding(2, a, missing, false))));
    }

    @Test
    void shouldRejectDuplicateDocumentAndOccurrenceCoverage() {
        DocumentId a = document("a");
        DocumentId b = document("b");
        ManagedOccurrenceBinding first = binding(1, a, b, true);
        ManagedOccurrenceBinding sameSourcePath = new ManagedOccurrenceBinding(
                identity(2),
                identity(102),
                identity(900),
                a,
                first.sourceAddress(),
                b,
                "b-blue",
                false,
                null);
        ManagedOccurrenceBinding laterGenerationAtSamePath =
                new ManagedOccurrenceBinding(
                        identity(3),
                        identity(103),
                        identity(900),
                        a,
                        ScopeAddress.embedded(
                                first.sourcePath(),
                                first.activationGeneration() + 1L),
                        b,
                        "b-blue",
                        false,
                        null);

        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocumentGraph.fromBindings(
                        Arrays.asList(a, a),
                        Collections.<ManagedOccurrenceBinding>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocumentGraph.fromBindings(
                        Arrays.asList(a, b),
                        Arrays.asList(first, sameSourcePath)));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocumentGraph.fromBindings(
                        Arrays.asList(a, b),
                        Arrays.asList(first, laterGenerationAtSamePath)));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocumentGraph.fromBindings(
                        Arrays.asList(a, b),
                        Arrays.asList(first, first)));
    }

    private static DocumentId document(String value) {
        return new DocumentId(value);
    }

    static ManagedOccurrenceBinding binding(
            int ordinal,
            DocumentId source,
            DocumentId target,
            boolean active) {
        return new ManagedOccurrenceBinding(
                identity(ordinal),
                identity(ordinal + 100),
                identity(900),
                source,
                ScopeAddress.embedded("/edge-" + ordinal, ordinal),
                target,
                target.value() + "-blue",
                active,
                null);
    }

    static String identity(int value) {
        return String.format("sha256:%064x", Integer.valueOf(value));
    }
}
