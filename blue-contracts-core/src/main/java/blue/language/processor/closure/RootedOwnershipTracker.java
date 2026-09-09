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

    RootedOwnershipTracker(RootedInvocationBinding binding, AffectedClosureSnapshot inputSnapshot) {
        this.binding = binding;
        this.inputSnapshot = inputSnapshot;
        owners.addAll(binding.context.entryOwners());
    }

    void finalized(AffectedClosureSnapshot snapshot) {
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
        return new Snapshot(binding, inputSnapshot, owners, boundaries);
    }

    /** No public constructor permits callers to choose a writable-member list. */
    static final class Snapshot {
        final RootedInvocationBinding binding;
        final AffectedClosureSnapshot inputSnapshot;
        final List<DocumentId> owners;
        final List<AffectedClosureSnapshot> boundaries;

        private Snapshot(RootedInvocationBinding binding, AffectedClosureSnapshot inputSnapshot, Set<DocumentId> owners,
                List<AffectedClosureSnapshot> boundaries) {
            this.binding = binding;
            this.inputSnapshot = inputSnapshot;
            List<DocumentId> ordered = new ArrayList<>(owners);
            Collections.sort(ordered);
            this.owners = Collections.unmodifiableList(ordered);
            this.boundaries = Collections.unmodifiableList(new ArrayList<>(boundaries));
        }
    }
}
