package blue.language.processor.closure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic SCC partitioner with canonical target-before-source output. */
public final class SccPartitioner {

    /** Creates a stateless deterministic partitioner. */
    public SccPartitioner() {
    }

    /**
     * Partitions every graph member exactly once. Component members are in
     * {@link DocumentId} order. Target components precede their sources, and
     * incomparable components are selected by minimum member DocumentId.
     *
     * @param graph verified managed-document graph
     * @return canonical deeply immutable SCC member lists
     */
    public List<List<DocumentId>> partition(ManagedDocumentGraph graph) {
        State state = new State(Objects.requireNonNull(graph, "graph"));
        for (DocumentId documentId : graph.documentIds()) {
            if (!state.index.containsKey(documentId)) {
                state.visit(documentId);
            }
        }
        return orderTargetBeforeSource(graph, state.components);
    }

    private static List<List<DocumentId>> orderTargetBeforeSource(
            ManagedDocumentGraph graph,
            List<List<DocumentId>> discovered) {
        Map<DocumentId, Integer> owners = new HashMap<DocumentId, Integer>();
        ArrayList<Set<Integer>> outgoing = new ArrayList<Set<Integer>>();
        for (int index = 0; index < discovered.size(); index++) {
            List<DocumentId> component = discovered.get(index);
            Collections.sort(component);
            outgoing.add(new HashSet<Integer>());
            for (DocumentId member : component) {
                owners.put(member, Integer.valueOf(index));
            }
        }
        for (DocumentId source : graph.documentIds()) {
            int sourceOwner = owners.get(source).intValue();
            for (DocumentId target : graph.adjacency().get(source)) {
                int targetOwner = owners.get(target).intValue();
                if (sourceOwner != targetOwner) {
                    outgoing.get(sourceOwner).add(Integer.valueOf(targetOwner));
                }
            }
        }

        Set<Integer> remaining = new HashSet<Integer>();
        for (int index = 0; index < discovered.size(); index++) {
            remaining.add(Integer.valueOf(index));
        }
        ArrayList<List<DocumentId>> result =
                new ArrayList<List<DocumentId>>();
        while (!remaining.isEmpty()) {
            Integer selected = null;
            for (Integer candidate : remaining) {
                if (hasRemainingTarget(
                        outgoing.get(candidate.intValue()), remaining)) {
                    continue;
                }
                if (selected == null
                        || minimum(discovered, candidate).compareTo(
                                minimum(discovered, selected)) < 0) {
                    selected = candidate;
                }
            }
            if (selected == null) {
                throw new IllegalStateException(
                        "SCC condensation graph contains a cycle");
            }
            result.add(Collections.unmodifiableList(
                    new ArrayList<DocumentId>(
                            discovered.get(selected.intValue()))));
            remaining.remove(selected);
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean hasRemainingTarget(
            Set<Integer> targets,
            Set<Integer> remaining) {
        for (Integer target : targets) {
            if (remaining.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private static DocumentId minimum(
            List<List<DocumentId>> components,
            Integer component) {
        return components.get(component.intValue()).get(0);
    }

    private static final class State {

        private final ManagedDocumentGraph graph;
        private final Map<DocumentId, Integer> index =
                new HashMap<DocumentId, Integer>();
        private final Map<DocumentId, Integer> lowLink =
                new HashMap<DocumentId, Integer>();
        private final Deque<DocumentId> stack = new ArrayDeque<DocumentId>();
        private final Set<DocumentId> onStack = new HashSet<DocumentId>();
        private final List<List<DocumentId>> components =
                new ArrayList<List<DocumentId>>();
        private int nextIndex;

        private State(ManagedDocumentGraph graph) {
            this.graph = graph;
        }

        private void visit(DocumentId documentId) {
            int assignedIndex = nextIndex++;
            index.put(documentId, Integer.valueOf(assignedIndex));
            lowLink.put(documentId, Integer.valueOf(assignedIndex));
            stack.push(documentId);
            onStack.add(documentId);
            for (DocumentId target : graph.adjacency().get(documentId)) {
                if (!index.containsKey(target)) {
                    visit(target);
                    lowLink.put(documentId, Integer.valueOf(Math.min(
                            lowLink.get(documentId).intValue(),
                            lowLink.get(target).intValue())));
                } else if (onStack.contains(target)) {
                    lowLink.put(documentId, Integer.valueOf(Math.min(
                            lowLink.get(documentId).intValue(),
                            index.get(target).intValue())));
                }
            }
            if (lowLink.get(documentId).equals(index.get(documentId))) {
                ArrayList<DocumentId> component =
                        new ArrayList<DocumentId>();
                DocumentId member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(documentId));
                components.add(component);
            }
        }
    }
}
