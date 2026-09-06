package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.time.Duration;

import static blue.language.processor.closure.ManagedDocumentGraphTest.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

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

    @Test
    void shouldMatchIndependentReachabilityAndOriginalMinimumReadyRule() {
        Random random = new Random(0x534343L);
        for (int run = 0; run < 300; run++) {
            List<DocumentId> members = new ArrayList<DocumentId>();
            int count = 1 + random.nextInt(22);
            for (int i = 0; i < count; i++) members.add(new DocumentId(i % 3 == 0 ? "\uD800\uDC00-" + i : "member-" + i));
            List<ManagedOccurrenceBinding> rows = new ArrayList<ManagedOccurrenceBinding>();
            int ordinal = 1;
            for (DocumentId source : members) for (DocumentId target : members) {
                if (random.nextInt(9) == 0) rows.add(binding(ordinal++, source, target, random.nextInt(5) != 0));
                if (random.nextInt(90) == 0) rows.add(binding(ordinal++, source, target, true)); // distinct alias, same graph edge
            }
            ManagedDocumentGraph original = ManagedDocumentGraph.fromBindings(members, rows);
            List<List<DocumentId>> expected = referencePartition(original);
            assertEquals(expected, new SccPartitioner().partition(original), "seeded graph " + run);
            Collections.shuffle(members, random); Collections.shuffle(rows, random);
            assertEquals(expected, new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(members, rows)),
                    "input-order-independent graph " + run);
        }
    }

    @Test
    void shouldOrderTenThousandIndependentHeadersWithoutQuadraticReadyScans() {
        List<DocumentId> members = new ArrayList<DocumentId>();
        for (int i = 0; i < 10_000; i++) members.add(new DocumentId("member-" + i));
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(members, Collections.emptyList());
        List<DocumentId> expected = new ArrayList<DocumentId>(members); Collections.sort(expected);
        List<List<DocumentId>> actual = assertTimeout(Duration.ofSeconds(5), () -> new SccPartitioner().partition(graph));
        assertEquals(10_000, actual.size());
        for (int i = 0; i < expected.size(); i++) assertEquals(Collections.singletonList(expected.get(i)), actual.get(i));
    }

    @Test
    void shouldReleaseOneParentAfterTenThousandTargetsInCanonicalOrder() {
        DocumentId parent = new DocumentId("0-parent");
        List<DocumentId> members = new ArrayList<DocumentId>(); members.add(parent);
        List<ManagedOccurrenceBinding> rows = new ArrayList<ManagedOccurrenceBinding>();
        List<DocumentId> expected = new ArrayList<DocumentId>();
        for (int i = 0; i < 10_000; i++) {
            DocumentId target = new DocumentId("target-" + i); members.add(target); expected.add(target);
            rows.add(binding(i + 1, parent, target, true));
        }
        Collections.sort(expected); expected.add(parent);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(members, rows);
        List<List<DocumentId>> actual = assertTimeout(Duration.ofSeconds(5), () -> new SccPartitioner().partition(graph));
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertEquals(Collections.singletonList(expected.get(i)), actual.get(i));
    }

    /** Small independent oracle: transitive closure finds SCCs, then the original ready-set scan orders them. */
    private static List<List<DocumentId>> referencePartition(ManagedDocumentGraph graph) {
        List<DocumentId> members = graph.documentIds(); int count = members.size();
        Map<DocumentId, Integer> indexes = new HashMap<DocumentId, Integer>();
        for (int i = 0; i < count; i++) indexes.put(members.get(i), i);
        boolean[][] reachable = new boolean[count][count];
        for (int i = 0; i < count; i++) {
            reachable[i][i] = true;
            for (DocumentId target : graph.adjacency().get(members.get(i))) reachable[i][indexes.get(target)] = true;
        }
        for (int k = 0; k < count; k++) for (int i = 0; i < count; i++) for (int j = 0; j < count; j++)
            reachable[i][j] |= reachable[i][k] && reachable[k][j];
        List<List<DocumentId>> groups = new ArrayList<List<DocumentId>>();
        Map<DocumentId, Integer> owner = new HashMap<DocumentId, Integer>();
        for (int i = 0; i < count; i++) if (!owner.containsKey(members.get(i))) {
            List<DocumentId> component = new ArrayList<DocumentId>(); int id = groups.size();
            for (int j = 0; j < count; j++) if (reachable[i][j] && reachable[j][i]) {
                component.add(members.get(j)); owner.put(members.get(j), id);
            }
            groups.add(component);
        }
        Set<Integer> remaining = new HashSet<Integer>();
        for (int i = 0; i < groups.size(); i++) remaining.add(i);
        List<List<DocumentId>> result = new ArrayList<List<DocumentId>>();
        while (!remaining.isEmpty()) {
            Integer selected = null;
            for (Integer candidate : remaining) {
                boolean ready = true;
                for (DocumentId member : groups.get(candidate)) for (DocumentId target : graph.adjacency().get(member))
                    if (!candidate.equals(owner.get(target)) && remaining.contains(owner.get(target))) ready = false;
                if (ready && (selected == null || groups.get(candidate).get(0).compareTo(groups.get(selected).get(0)) < 0)) selected = candidate;
            }
            if (selected == null) throw new AssertionError("Invalid reference condensation graph");
            result.add(groups.get(selected)); remaining.remove(selected);
        }
        return result;
    }

    private static ManagedDocumentGraph graph(
            List<DocumentId> documents,
            ManagedOccurrenceBinding... bindings) {
        return ManagedDocumentGraph.fromBindings(
                documents, Arrays.asList(bindings));
    }
}
