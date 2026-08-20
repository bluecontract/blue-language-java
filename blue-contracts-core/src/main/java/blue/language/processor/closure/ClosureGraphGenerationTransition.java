package blue.language.processor.closure;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Assigns the invocation-level managed-graph generation at any boundary. */
final class ClosureGraphGenerationTransition {

    private ClosureGraphGenerationTransition() {
    }

    /**
     * Returns the input generation or its single successor according to the
     * exact active-occurrence transition law.
     *
     * <p>Every tentative boundary is compared directly with the invocation
     * input. Successive staged repartitions therefore never chain graph
     * generations, and returning to the input active set returns to the input
     * generation.</p>
     */
    static long assign(
            long inputGeneration,
            ManagedDocumentGraph inputGraph,
            ManagedDocumentGraph resultingGraph) {
        long generation = ClosureValueSupport.requireSafeInteger(
                inputGeneration, "input graph generation");
        Set<String> inputActive = activeOccurrenceIdentities(
                Objects.requireNonNull(inputGraph, "inputGraph"));
        Set<String> resultingActive = activeOccurrenceIdentities(
                Objects.requireNonNull(resultingGraph, "resultingGraph"));
        if (inputActive.equals(resultingActive)) {
            return generation;
        }
        if (generation == ClosureValueSupport.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    "graphGeneration cannot overflow the safe-integer range");
        }
        return generation + 1L;
    }

    /** Returns exact active occurrence ADD/REMOVE cardinality. */
    static long activeOccurrenceChangeCount(
            ManagedDocumentGraph before,
            ManagedDocumentGraph after) {
        Set<String> beforeActive = activeOccurrenceIdentities(
                Objects.requireNonNull(before, "before"));
        Set<String> afterActive = activeOccurrenceIdentities(
                Objects.requireNonNull(after, "after"));
        long changes = 0L;
        for (String identity : beforeActive) {
            if (!afterActive.contains(identity)) {
                changes++;
            }
        }
        for (String identity : afterActive) {
            if (!beforeActive.contains(identity)) {
                changes++;
            }
        }
        return changes;
    }

    /**
     * Returns whether the SCC membership partition changed.
     *
     * <p>Condensation order is deliberately ignored. Removing the final edge
     * between two already-distinct singleton components can reorder the
     * condensation without forming, merging, splitting, or dissolving a
     * component.</p>
     */
    static boolean componentPartitionChanged(
            ManagedDocumentGraph before,
            ManagedDocumentGraph after) {
        return !componentMemberships(
                Objects.requireNonNull(before, "before"))
                .equals(componentMemberships(
                        Objects.requireNonNull(after, "after")));
    }

    private static Set<Set<DocumentId>> componentMemberships(
            ManagedDocumentGraph graph) {
        Set<Set<DocumentId>> result =
                new HashSet<Set<DocumentId>>();
        List<List<DocumentId>> partition =
                new SccPartitioner().partition(graph);
        for (List<DocumentId> members : partition) {
            result.add(new HashSet<DocumentId>(members));
        }
        return result;
    }

    private static Set<String> activeOccurrenceIdentities(
            ManagedDocumentGraph graph) {
        LinkedHashSet<String> identities = new LinkedHashSet<String>();
        for (ManagedOccurrenceBinding binding : graph.activeBindings()) {
            identities.add(binding.occurrenceIdentity());
        }
        return identities;
    }
}
