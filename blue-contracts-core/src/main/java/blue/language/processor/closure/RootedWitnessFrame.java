package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Separates immutable pending-history proof sources from live calculating views. */
final class RootedWitnessFrame {
    private final Set<DocumentId> owners = new LinkedHashSet<>();
    private final Map<DocumentId, AffectedClosureSnapshot> originals = new LinkedHashMap<>();

    RootedWitnessFrame(ClosureInvocationInput input) {
        this(input.snapshot(), input.rootedBinding());
    }

    private RootedWitnessFrame(AffectedClosureSnapshot snapshot, RootedInvocationBinding binding) {
        if (binding == null) throw new IllegalArgumentException("Witness roles require a rooted invocation");
        ClosureEvidenceVerifier.verifySnapshot(snapshot);
        owners.addAll(binding.context.entryOwners());
        Set<DocumentId> live = reachable(owners, snapshot.occurrences());
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            if (document.initialized() && !live.contains(document.documentId())
                    && !binding.birthParents.containsKey(document.documentId())) {
                State prior = snapshot.rootedWitnesses();
                AffectedClosureSnapshot proof = prior == null ? null : prior.originals.get(document.documentId());
                originals.put(document.documentId(), proof == null ? snapshot : proof);
            }
        }
    }

    static AffectedClosureSnapshot bind(AffectedClosureSnapshot snapshot, RootedInvocationBinding binding) {
        RootedWitnessFrame frame = new RootedWitnessFrame(snapshot, binding);
        List<DocumentId> ids = new ArrayList<>();
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) ids.add(document.documentId());
        State roles = frame.at(snapshot.occurrences(), ids);
        if (roles.sources().isEmpty()) return snapshot;
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(ids, snapshot.occurrences(), roles.sources());
        List<ComponentSnapshot> components = new ArrayList<>();
        for (List<DocumentId> members : new SccPartitioner().partition(graph)) {
            ComponentSnapshot match = null;
            for (ComponentSnapshot component : snapshot.components()) {
                if (component.orderedMemberDocumentIds().equals(members)) match = component;
            }
            if (match == null) throw new IllegalArgumentException("Witness role projection split a verified component");
            components.add(match);
        }
        AffectedClosureSnapshot result = new AffectedClosureSnapshot(snapshot.closureIdentity(), snapshot.graphGeneration(),
                snapshot.managedDocuments(), snapshot.occurrences(), snapshot.occurrenceBindingSetIdentity(),
                components, snapshot.publicRootDocumentIds(), roles);
        if (!snapshot.closureIdentity().equals(ClosureIdentityService.INSTANCE.affectedClosureIdentity(result))) {
            throw new IllegalArgumentException("Witness roles changed the closed input identity");
        }
        return result;
    }

    State at(Collection<ManagedOccurrenceBinding> bindings, Collection<DocumentId> documents) {
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(documents, bindings);
        boolean expanded;
        do {
            expanded = false;
            for (List<DocumentId> component : new SccPartitioner().partition(graph)) {
                if (!Collections.disjoint(owners, component)) expanded |= owners.addAll(component);
            }
        } while (expanded);
        Set<DocumentId> live = reachable(owners, bindings);
        // Once a witness joins this calculation, a later split cannot freeze its tentative work.
        originals.keySet().removeAll(live);
        return new State(originals);
    }

    private static Set<DocumentId> reachable(Collection<DocumentId> roots, Collection<ManagedOccurrenceBinding> rows) {
        Set<DocumentId> reached = new LinkedHashSet<>(roots);
        ArrayDeque<DocumentId> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            DocumentId source = queue.removeFirst();
            for (ManagedOccurrenceBinding row : rows) {
                if (row.active() && row.sourceDocumentId().equals(source) && reached.add(row.targetDocumentId())) {
                    queue.addLast(row.targetDocumentId());
                }
            }
        }
        return reached;
    }

    /** Carries actual previously verified snapshots, never a caller-selected mutable allowlist. */
    static final class State {
        private final Map<DocumentId, AffectedClosureSnapshot> originals;
        private State(Map<DocumentId, AffectedClosureSnapshot> originals) {
            this.originals = Collections.unmodifiableMap(new LinkedHashMap<>(originals));
        }

        Set<DocumentId> sources() { return originals.keySet(); }

        void requireUnchanged(Map<DocumentId, Node> bodies, Collection<ManagedOccurrenceBinding> bindings) {
            for (Map.Entry<DocumentId, AffectedClosureSnapshot> entry : originals.entrySet()) {
                DocumentId id = entry.getKey();
                ManagedDocumentSnapshot original = entry.getValue().managedDocument(id);
                Node actual = bodies.get(id);
                if (actual == null || !NodeWireForm.get(original.document()).equals(NodeWireForm.get(actual))) {
                    throw new IllegalArgumentException("Immutable rooted witness body changed: " + id);
                }
                if (!rows(entry.getValue().occurrences(), id).equals(rows(bindings, id))) {
                    throw new IllegalArgumentException("Immutable rooted witness binding inventory changed: " + id);
                }
            }
        }

        void requireUnchanged(AffectedClosureSnapshot snapshot) {
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) bodies.put(document.documentId(), document.document());
            requireUnchanged(bodies, snapshot.occurrences());
            for (Map.Entry<DocumentId, AffectedClosureSnapshot> entry : originals.entrySet()) {
                ManagedDocumentSnapshot before = entry.getValue().managedDocument(entry.getKey());
                ManagedDocumentSnapshot after = snapshot.managedDocument(entry.getKey());
                if (!before.blueId().equals(after.blueId()) || before.epoch() != after.epoch()
                        || before.initialized() != after.initialized() || before.terminated() != after.terminated()
                        || before.publicRoot() != after.publicRoot() || before.componentGeneration() != after.componentGeneration()) {
                    throw new IllegalArgumentException("Immutable rooted witness position changed: " + entry.getKey());
                }
            }
        }

        private static List<String> rows(Collection<ManagedOccurrenceBinding> rows, DocumentId source) {
            List<String> result = new ArrayList<>();
            for (ManagedOccurrenceBinding row : rows) if (row.sourceDocumentId().equals(source)) {
                result.add(row.bindingIdentity() + ":" + row.active() + ":" + row.pendingHistoricalEpoch()
                        + ":" + (row.pendingRepresentationCursor() == null ? null : row.pendingRepresentationCursor().identityValue()));
            }
            Collections.sort(result);
            return result;
        }
    }
}
