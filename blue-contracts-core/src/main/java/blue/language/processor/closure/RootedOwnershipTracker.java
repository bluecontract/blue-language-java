package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Monotone ownership tracked at real, successfully verified topology boundaries. */
final class RootedOwnershipTracker {
    private final RootedInvocationBinding binding;
    private final AffectedClosureSnapshot inputSnapshot;
    private final Set<DocumentId> owners = new LinkedHashSet<>();
    private final List<AffectedClosureSnapshot> boundaries = new ArrayList<>();
    private final Set<DocumentId> changedOutsideCheckpoint = new LinkedHashSet<>();
    private final Map<DocumentId, AffectedClosureSnapshot> checkpointPredecessors = new java.util.LinkedHashMap<>();
    private final Map<DocumentId, AffectedClosureSnapshot> checkpointSuccessors = new java.util.LinkedHashMap<>();

    RootedOwnershipTracker(RootedInvocationBinding binding, AffectedClosureSnapshot inputSnapshot) {
        this.binding = binding;
        this.inputSnapshot = inputSnapshot;
        owners.addAll(binding.context.entryOwners());
    }

    // Untyped internal callers retain the strict ordinary-boundary behavior.
    void finalized(AffectedClosureSnapshot snapshot) {
        finalized(snapshot, TentativeFinalization.Boundary.Kind.WORK);
    }

    void finalized(AffectedClosureSnapshot snapshot, TentativeFinalization.Boundary.Kind kind) {
        AffectedClosureSnapshot previous = boundaries.isEmpty() ? inputSnapshot : boundaries.get(boundaries.size() - 1);
        for (DocumentId document : binding.context.entryOwners()) {
            ManagedDocumentSnapshot before = previous.managedDocument(document);
            ManagedDocumentSnapshot after = snapshot.managedDocument(document);
            if (before == null || after == null) {
                changedOutsideCheckpoint.add(document);
                checkpointPredecessors.remove(document); checkpointSuccessors.remove(document);
                continue;
            }
            if (before.blueId().equals(after.blueId())) continue;
            if (kind != TentativeFinalization.Boundary.Kind.CHECKPOINT_SETTLEMENT
                    || changedOutsideCheckpoint.contains(document) || checkpointPredecessors.containsKey(document)) {
                changedOutsideCheckpoint.add(document);
                checkpointPredecessors.remove(document); checkpointSuccessors.remove(document);
            } else {
                checkpointPredecessors.put(document, previous);
                checkpointSuccessors.put(document, snapshot);
            }
        }
        // The session supplies the verified finalizer output, not observer diagnostics.
        List<List<DocumentId>> components = new SccPartitioner().partition(snapshot.graph());
        boolean changed;
        do {
            changed = false;
            for (List<DocumentId> component : components) {
                if (!Collections.disjoint(owners, component)) changed |= owners.addAll(component);
            }
            for (Map.Entry<DocumentId, DocumentId> birth : binding.birthParents.entrySet()) {
                if (owners.contains(birth.getValue()) && snapshot.contains(birth.getKey())
                        && snapshot.managedDocument(birth.getKey()).initialized()) {
                    changed |= owners.add(birth.getKey());
                }
            }
        } while (changed);
        boundaries.add(snapshot);
    }

    Snapshot snapshot() {
        return new Snapshot(binding, inputSnapshot, owners, boundaries, checkpointPredecessors, checkpointSuccessors);
    }

    /** No public constructor permits callers to choose a writable-member list. */
    static final class Snapshot {
        final RootedInvocationBinding binding;
        final AffectedClosureSnapshot inputSnapshot;
        final List<DocumentId> owners;
        final List<AffectedClosureSnapshot> boundaries;
        final Map<DocumentId, AffectedClosureSnapshot> checkpointPredecessors;
        final Map<DocumentId, AffectedClosureSnapshot> checkpointSuccessors;

        private Snapshot(RootedInvocationBinding binding, AffectedClosureSnapshot inputSnapshot, Set<DocumentId> owners,
                List<AffectedClosureSnapshot> boundaries,
                Map<DocumentId, AffectedClosureSnapshot> checkpointPredecessors,
                Map<DocumentId, AffectedClosureSnapshot> checkpointSuccessors) {
            this.binding = binding;
            this.inputSnapshot = inputSnapshot;
            List<DocumentId> ordered = new ArrayList<>(owners);
            Collections.sort(ordered);
            this.owners = Collections.unmodifiableList(ordered);
            this.boundaries = Collections.unmodifiableList(new ArrayList<>(boundaries));
            this.checkpointPredecessors = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(checkpointPredecessors));
            this.checkpointSuccessors = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(checkpointSuccessors));
        }
    }
}
