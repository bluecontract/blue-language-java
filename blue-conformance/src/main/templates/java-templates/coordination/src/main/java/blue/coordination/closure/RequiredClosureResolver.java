package blue.coordination.closure;

import blue.contracts.closure.DocumentId;
import blue.contracts.closure.ManagedOccurrenceBinding;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves direct targets, all members of their SCCs, and all required
 * containing documents. It deliberately does not schedule a whole global weak
 * component.
 */
public final class RequiredClosureResolver {
    public List<DocumentId> resolve(ManagedGraphSnapshot graph, Set<DocumentId> directTargets) {
        Map<DocumentId, List<DocumentId>> parents = reverseParents(graph.occurrences());
        LinkedHashSet<DocumentId> selected = new LinkedHashSet<DocumentId>();
        ArrayDeque<DocumentId> queue = new ArrayDeque<DocumentId>();
        List<DocumentId> seeds = new ArrayList<DocumentId>(directTargets);
        Collections.sort(seeds);
        queue.addAll(seeds);

        while (!queue.isEmpty()) {
            DocumentId current = queue.removeFirst();
            ComponentId componentId = graph.componentIndex().componentOf(current);
            ProcessingComponent component = graph.componentIndex().component(componentId);
            for (DocumentId member : component.orderedMembers()) {
                if (selected.add(member)) {
                    queue.addLast(member);
                }
            }
            for (DocumentId parent : parents.getOrDefault(current, Collections.<DocumentId>emptyList())) {
                if (selected.add(parent)) {
                    queue.addLast(parent);
                }
            }
        }

        List<DocumentId> result = new ArrayList<DocumentId>(selected);
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private Map<DocumentId, List<DocumentId>> reverseParents(List<ManagedOccurrenceBinding> occurrences) {
        Map<DocumentId, List<DocumentId>> result = new LinkedHashMap<DocumentId, List<DocumentId>>();
        for (ManagedOccurrenceBinding occurrence : occurrences) {
            if (!occurrence.active()) {
                continue;
            }
            result.computeIfAbsent(
                    occurrence.targetDocumentId(),
                    ignored -> new ArrayList<DocumentId>())
                    .add(occurrence.sourceDocumentId());
        }
        for (List<DocumentId> values : result.values()) {
            Collections.sort(values);
        }
        return result;
    }
}
