package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static blue.language.processor.closure.ManagedDocumentGraphTest.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ComponentGenerationTransitionTest {

    @Test
    void shouldPreserveGenerationForEqualMembersAndInternalEdges() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        DocumentId c = new DocumentId("c");
        ManagedDocumentGraph before = graph(
                Arrays.asList(a, b, c),
                binding(1, a, b, true),
                binding(2, b, a, true));
        ManagedDocumentGraph after = graph(
                Arrays.asList(a, b, c),
                binding(3, a, b, true),
                binding(4, b, a, true),
                binding(5, c, a, true));

        Map<DocumentId, Long> assigned =
                ComponentGenerationTransition.assign(
                        before,
                        generations(a, 4L, b, 4L, c, 2L),
                        after);

        assertEquals(Long.valueOf(4L), assigned.get(a));
        assertEquals(Long.valueOf(4L), assigned.get(b));
        assertEquals(Long.valueOf(2L), assigned.get(c));
    }

    @Test
    void shouldAdvanceFromMaximumContributorOnMerge() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        DocumentId c = new DocumentId("c");
        ManagedDocumentGraph before = graph(
                Arrays.asList(a, b, c),
                binding(1, a, b, true),
                binding(2, b, a, true));
        ManagedDocumentGraph after = graph(
                Arrays.asList(a, b, c),
                binding(3, a, b, true),
                binding(4, b, a, true),
                binding(5, b, c, true),
                binding(6, c, a, true));

        Map<DocumentId, Long> assigned =
                ComponentGenerationTransition.assign(
                        before,
                        generations(a, 2L, b, 2L, c, 5L),
                        after);

        assertEquals(Long.valueOf(6L), assigned.get(a));
        assertEquals(Long.valueOf(6L), assigned.get(b));
        assertEquals(Long.valueOf(6L), assigned.get(c));
    }

    @Test
    void shouldAdvanceEveryResultOnSplit() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        DocumentId c = new DocumentId("c");
        ManagedDocumentGraph before = graph(
                Arrays.asList(a, b, c),
                binding(1, a, b, true),
                binding(2, b, a, true),
                binding(3, b, c, true),
                binding(4, c, a, true));
        ManagedDocumentGraph after = graph(
                Arrays.asList(a, b, c),
                binding(5, a, b, true),
                binding(6, b, a, true));

        Map<DocumentId, Long> assigned =
                ComponentGenerationTransition.assign(
                        before,
                        generations(a, 7L, b, 7L, c, 7L),
                        after);

        assertEquals(Long.valueOf(8L), assigned.get(a));
        assertEquals(Long.valueOf(8L), assigned.get(b));
        assertEquals(Long.valueOf(8L), assigned.get(c));
    }

    @Test
    void shouldAdvanceWhenInternalEdgeSetChangesWithoutMembershipChange() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        ManagedDocumentGraph before = graph(
                Arrays.asList(a, b),
                binding(1, a, b, true),
                binding(2, b, a, true));
        ManagedDocumentGraph after = graph(
                Arrays.asList(a, b),
                binding(3, a, b, true),
                binding(4, b, a, true),
                binding(5, a, a, true));

        Map<DocumentId, Long> assigned =
                ComponentGenerationTransition.assign(
                        before,
                        generations(a, 2L, b, 2L),
                        after);

        assertEquals(Long.valueOf(3L), assigned.get(a));
        assertEquals(Long.valueOf(3L), assigned.get(b));
    }

    @Test
    void shouldStartNewComponentAtOneAndRejectOverflow() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        ManagedDocumentGraph before = graph(
                Collections.singletonList(a));
        ManagedDocumentGraph after = graph(
                Arrays.asList(a, b));

        Map<DocumentId, Long> assigned =
                ComponentGenerationTransition.assign(
                        before,
                        generations(a, 3L),
                        after);
        assertEquals(Long.valueOf(3L), assigned.get(a));
        assertEquals(Long.valueOf(1L), assigned.get(b));

        ManagedDocumentGraph changed = graph(
                Collections.singletonList(a),
                binding(1, a, a, true));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentGenerationTransition.assign(
                        before,
                        generations(
                                a,
                                ClosureValueSupport.MAX_SAFE_INTEGER),
                        changed));
    }

    private static ManagedDocumentGraph graph(
            java.util.List<DocumentId> documents,
            ManagedOccurrenceBinding... bindings) {
        return ManagedDocumentGraph.fromBindings(
                documents, Arrays.asList(bindings));
    }

    private static Map<DocumentId, Long> generations(Object... pairs) {
        Map<DocumentId, Long> result = new HashMap<DocumentId, Long>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(
                    (DocumentId) pairs[index],
                    Long.valueOf(((Long) pairs[index + 1]).longValue()));
        }
        return result;
    }
}
