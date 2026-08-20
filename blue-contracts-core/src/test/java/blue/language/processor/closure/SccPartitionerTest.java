package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.language.processor.closure.ManagedDocumentGraphTest.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SccPartitionerTest {

    @Test
    void shouldPartitionSelfCycle() {
        DocumentId a = new DocumentId("a");
        ManagedDocumentGraph graph = graph(
                Collections.singletonList(a),
                binding(1, a, a, true));

        assertEquals(
                Collections.singletonList(Collections.singletonList(a)),
                new SccPartitioner().partition(graph));
        assertTrue(graph.hasSelfEdge(a));
    }

    @Test
    void shouldReturnMultipleComponentsTargetBeforeSource() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        DocumentId c = new DocumentId("c");
        DocumentId d = new DocumentId("d");
        DocumentId e = new DocumentId("e");
        DocumentId f = new DocumentId("f");
        ManagedDocumentGraph graph = graph(
                Arrays.asList(f, e, d, c, b, a),
                binding(1, a, b, true),
                binding(2, b, a, true),
                binding(3, d, e, true),
                binding(4, e, d, true),
                binding(5, c, a, true),
                binding(6, c, d, true),
                binding(7, f, d, true));

        assertEquals(
                Arrays.asList(
                        Arrays.asList(a, b),
                        Arrays.asList(d, e),
                        Collections.singletonList(c),
                        Collections.singletonList(f)),
                new SccPartitioner().partition(graph));
    }

    @Test
    void shouldUseUnicodeScalarOrderForIncomparableTie() {
        DocumentId supplementary = new DocumentId("\uD800\uDC00");
        DocumentId privateUse = new DocumentId("\uE000");
        ManagedDocumentGraph graph = graph(
                Arrays.asList(supplementary, privateUse));

        assertEquals(
                Arrays.asList(
                        Collections.singletonList(privateUse),
                        Collections.singletonList(supplementary)),
                new SccPartitioner().partition(graph));
    }

    @Test
    void shouldIgnoreInactiveEdges() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        ManagedDocumentGraph graph = graph(
                Arrays.asList(a, b),
                binding(1, a, b, false));

        assertEquals(
                Arrays.asList(
                        Collections.singletonList(a),
                        Collections.singletonList(b)),
                new SccPartitioner().partition(graph));
    }

    @Test
    void shouldBeInvariantToNodeAndBindingInputOrder() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        DocumentId c = new DocumentId("c");
        List<DocumentId> documents = Arrays.asList(a, b, c);
        List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                binding(1, a, b, true),
                binding(2, b, a, true),
                binding(3, c, a, true));
        List<DocumentId> reversedDocuments =
                new ArrayList<DocumentId>(documents);
        List<ManagedOccurrenceBinding> reversedBindings =
                new ArrayList<ManagedOccurrenceBinding>(bindings);
        Collections.reverse(reversedDocuments);
        Collections.reverse(reversedBindings);

        List<List<DocumentId>> expected = new SccPartitioner().partition(
                ManagedDocumentGraph.fromBindings(documents, bindings));
        List<List<DocumentId>> actual = new SccPartitioner().partition(
                ManagedDocumentGraph.fromBindings(
                        reversedDocuments, reversedBindings));

        assertEquals(expected, actual);
    }

    private static ManagedDocumentGraph graph(
            List<DocumentId> documents,
            ManagedOccurrenceBinding... bindings) {
        return ManagedDocumentGraph.fromBindings(
                documents, Arrays.asList(bindings));
    }
}
