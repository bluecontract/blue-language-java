package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.processor.closure.ManagedDocumentGraphTest.binding;
import static blue.language.processor.closure.ManagedDocumentGraphTest.identity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClosureGraphGenerationTransitionTest {

    @Test
    void shouldAnchorEverySuccessiveBoundaryToTheInvocationInput() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        ManagedDocumentGraph input = graph(
                a,
                b,
                binding(1, a, b, false),
                binding(2, b, a, false));
        ManagedDocumentGraph first = graph(
                a,
                b,
                binding(1, a, b, true),
                binding(2, b, a, false));
        ManagedDocumentGraph second = graph(
                a,
                b,
                binding(1, a, b, true),
                binding(2, b, a, true));
        ManagedDocumentGraph restored = graph(
                a,
                b,
                binding(1, a, b, false),
                binding(2, b, a, false));

        assertEquals(8L, ClosureGraphGenerationTransition.assign(
                7L, input, first));
        assertEquals(8L, ClosureGraphGenerationTransition.assign(
                7L, input, second));
        assertEquals(7L, ClosureGraphGenerationTransition.assign(
                7L, input, restored));
        assertEquals(2L, ClosureGraphGenerationTransition
                .activeOccurrenceChangeCount(input, second));
    }

    @Test
    void shouldCountParallelOccurrenceChangesWithoutInventingARepartition() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        ManagedDocumentGraph input = graph(
                a,
                b,
                binding(1, a, b, true),
                binding(2, a, b, false));
        ManagedDocumentGraph after = graph(
                a,
                b,
                binding(1, a, b, true),
                binding(2, a, b, true));

        assertEquals(input.adjacency(), after.adjacency());
        assertEquals(1L, ClosureGraphGenerationTransition
                .activeOccurrenceChangeCount(input, after));
        assertEquals(4L, ClosureGraphGenerationTransition.assign(
                3L, input, after));
    }

    @Test
    void shouldDistinguishOneCycleSplitFromASecondAdjacencyChange() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        ManagedDocumentGraph cycle = graph(
                a,
                b,
                binding(1, a, b, true),
                binding(2, b, a, true));
        ManagedDocumentGraph split = graph(
                a,
                b,
                binding(1, a, b, false),
                binding(2, b, a, true));
        ManagedDocumentGraph noEdges = graph(
                a,
                b,
                binding(1, a, b, false),
                binding(2, b, a, false));

        assertFalse(cycle.adjacency().equals(split.adjacency()));
        assertFalse(split.adjacency().equals(noEdges.adjacency()));
        assertTrue(ClosureGraphGenerationTransition
                .componentPartitionChanged(cycle, split));
        assertFalse(ClosureGraphGenerationTransition
                .componentPartitionChanged(split, noEdges));
    }

    @Test
    void shouldIgnoreBindingChurnAndRejectOnlyARealOverflowingDelta() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        ManagedOccurrenceBinding original = binding(3, a, b, true);
        ManagedOccurrenceBinding rebound = new ManagedOccurrenceBinding(
                original.occurrenceIdentity(),
                identity(999),
                original.bindingPolicyIdentity(),
                original.sourceDocumentId(),
                original.sourceAddress(),
                original.targetDocumentId(),
                "rebound-blue-id",
                true,
                null);
        ManagedDocumentGraph input = graph(a, b, original);
        ManagedDocumentGraph sameActiveOccurrence = graph(a, b, rebound);
        ManagedDocumentGraph removed = graph(
                a, b, binding(3, a, b, false));

        assertEquals(
                ClosureValueSupport.MAX_SAFE_INTEGER,
                ClosureGraphGenerationTransition.assign(
                        ClosureValueSupport.MAX_SAFE_INTEGER,
                        input,
                        sameActiveOccurrence));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClosureGraphGenerationTransition.assign(
                        ClosureValueSupport.MAX_SAFE_INTEGER,
                        input,
                        removed));
    }

    private static ManagedDocumentGraph graph(
            DocumentId a,
            DocumentId b,
            ManagedOccurrenceBinding... bindings) {
        return ManagedDocumentGraph.fromBindings(
                Arrays.asList(a, b), Arrays.asList(bindings));
    }
}
