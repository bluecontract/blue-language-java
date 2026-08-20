package blue.language.processor.closure;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies the exact Contracts 1.0 component-generation transition rule. */
public final class ComponentGenerationTransition {

    private ComponentGenerationTransition() {
    }

    /**
     * Assigns one generation to every resulting graph member.
     *
     * <p>An unchanged member set and internal edge set preserves its input
     * generation. Every changed result receives one plus the maximum input
     * generation contributing any member; a result with no predecessor starts
     * at one.</p>
     *
     * @param inputGraph authoritative input graph
     * @param inputGenerations exact generation for every input member
     * @param resultingGraph resulting graph to classify
     * @return immutable resulting generation indexed by document identity
     */
    public static Map<DocumentId, Long> assign(
            ManagedDocumentGraph inputGraph,
            Map<DocumentId, Long> inputGenerations,
            ManagedDocumentGraph resultingGraph) {
        ManagedDocumentGraph before = Objects.requireNonNull(
                inputGraph, "inputGraph");
        ManagedDocumentGraph after = Objects.requireNonNull(
                resultingGraph, "resultingGraph");
        Map<DocumentId, Long> generations = validateInputGenerations(
                before, inputGenerations);
        SccPartitioner partitioner = new SccPartitioner();
        List<List<DocumentId>> inputComponents = partitioner.partition(before);
        List<List<DocumentId>> resultingComponents = partitioner.partition(after);

        Map<DocumentId, Integer> inputOwners =
                new HashMap<DocumentId, Integer>();
        long[] componentGenerations = new long[inputComponents.size()];
        for (int index = 0; index < inputComponents.size(); index++) {
            List<DocumentId> component = inputComponents.get(index);
            long generation = generations.get(component.get(0)).longValue();
            componentGenerations[index] = generation;
            for (DocumentId member : component) {
                if (generations.get(member).longValue() != generation) {
                    throw new IllegalArgumentException(
                            "Input component members have different generations");
                }
                inputOwners.put(member, Integer.valueOf(index));
            }
        }

        LinkedHashMap<DocumentId, Long> assigned =
                new LinkedHashMap<DocumentId, Long>();
        for (List<DocumentId> resultingComponent : resultingComponents) {
            long generation = unchangedGeneration(
                    before,
                    after,
                    inputComponents,
                    componentGenerations,
                    resultingComponent);
            if (generation < 0L) {
                generation = changedGeneration(
                        inputOwners,
                        componentGenerations,
                        resultingComponent);
            }
            for (DocumentId member : resultingComponent) {
                assigned.put(member, Long.valueOf(generation));
            }
        }
        return Collections.unmodifiableMap(assigned);
    }

    private static Map<DocumentId, Long> validateInputGenerations(
            ManagedDocumentGraph graph,
            Map<DocumentId, Long> inputGenerations) {
        Map<DocumentId, Long> values = Objects.requireNonNull(
                inputGenerations, "inputGenerations");
        if (!values.keySet().equals(new HashSet<DocumentId>(
                graph.documentIds()))) {
            throw new IllegalArgumentException(
                    "Input generations must exactly cover the input graph");
        }
        HashMap<DocumentId, Long> copy = new HashMap<DocumentId, Long>();
        for (Map.Entry<DocumentId, Long> entry : values.entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "input generation DocumentId");
            Long value = Objects.requireNonNull(
                    entry.getValue(), "input component generation");
            copy.put(documentId, Long.valueOf(
                    ClosureValueSupport.requireSafeInteger(
                            value.longValue(), "input component generation")));
        }
        return copy;
    }

    private static long unchangedGeneration(
            ManagedDocumentGraph before,
            ManagedDocumentGraph after,
            List<List<DocumentId>> inputComponents,
            long[] inputGenerations,
            List<DocumentId> resultingComponent) {
        for (int index = 0; index < inputComponents.size(); index++) {
            List<DocumentId> inputComponent = inputComponents.get(index);
            if (inputComponent.equals(resultingComponent)
                    && sameInternalEdges(
                            before, after, resultingComponent)) {
                return inputGenerations[index];
            }
        }
        return -1L;
    }

    private static boolean sameInternalEdges(
            ManagedDocumentGraph before,
            ManagedDocumentGraph after,
            List<DocumentId> members) {
        Set<DocumentId> memberSet = new HashSet<DocumentId>(members);
        for (DocumentId source : members) {
            Set<DocumentId> beforeTargets = internalTargets(
                    before, source, memberSet);
            Set<DocumentId> afterTargets = internalTargets(
                    after, source, memberSet);
            if (!beforeTargets.equals(afterTargets)) {
                return false;
            }
        }
        return true;
    }

    private static Set<DocumentId> internalTargets(
            ManagedDocumentGraph graph,
            DocumentId source,
            Set<DocumentId> members) {
        Set<DocumentId> targets = new HashSet<DocumentId>();
        List<DocumentId> adjacent = graph.adjacency().get(source);
        if (adjacent != null) {
            for (DocumentId target : adjacent) {
                if (members.contains(target)) {
                    targets.add(target);
                }
            }
        }
        return targets;
    }

    private static long changedGeneration(
            Map<DocumentId, Integer> inputOwners,
            long[] inputGenerations,
            List<DocumentId> resultingComponent) {
        long maximum = 0L;
        for (DocumentId member : resultingComponent) {
            Integer owner = inputOwners.get(member);
            if (owner != null) {
                maximum = Math.max(
                        maximum,
                        inputGenerations[owner.intValue()]);
            }
        }
        if (maximum == ClosureValueSupport.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    "Component generation exceeds the safe-integer range");
        }
        return maximum + 1L;
    }
}
