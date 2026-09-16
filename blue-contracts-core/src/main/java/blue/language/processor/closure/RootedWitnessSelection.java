package blue.language.processor.closure;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Fresh state construction, never a mutation of an admitted rooted operation. */
final class RootedWitnessSelection {
    private static final String UNBOUND_IDENTITY =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";

    private RootedWitnessSelection() { }

    static AffectedClosureSnapshot prepare(AffectedClosureSnapshot original,
            Map<DocumentId, AffectedClosureSnapshot> selectedWitnessProofs) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(selectedWitnessProofs, "selectedWitnessProofs");
        verifyClosedSnapshot(original);
        if (selectedWitnessProofs.isEmpty()) return original;
        RootedWitnessFrame.State previous = original.rootedWitnesses();
        if (previous == null) {
            throw new IllegalArgumentException("Witness selection requires authenticated immutable witness roles");
        }
        Map<DocumentId, AffectedClosureSnapshot> proofs = new TreeMap<>();
        for (Map.Entry<DocumentId, AffectedClosureSnapshot> entry : selectedWitnessProofs.entrySet()) {
            DocumentId id = Objects.requireNonNull(entry.getKey(), "selected witness lineage");
            if (!previous.sources().contains(id)) {
                throw new IllegalArgumentException("Witness selection cannot replace an owner, live or absent document: " + id);
            }
            requireUnanchored(original, previous.sources(), id);
            AffectedClosureSnapshot proof = Objects.requireNonNull(entry.getValue(), "selected witness proof");
            verifyClosedSnapshot(proof);
            ManagedDocumentSnapshot selected = proof.managedDocument(id);
            if (selected == null || !selected.initialized()) {
                throw new IllegalArgumentException("Selected proof lacks an initialized witness primary: " + id);
            }
            requireSameInventory(original, proof, id);
            proofs.put(id, proof);
        }

        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        Map<DocumentId, Long> generations = new LinkedHashMap<>();
        for (ManagedDocumentSnapshot before : original.managedDocuments()) {
            AffectedClosureSnapshot proof = proofs.get(before.documentId());
            ManagedDocumentSnapshot selected = proof == null ? before : proof.managedDocument(before.documentId());
            ManagedDocumentSnapshot document = proof == null ? before : new ManagedDocumentSnapshot(
                    selected.documentId(), selected.blueId(), selected.document(), selected.initialized(),
                    selected.terminated(), false, selected.epoch(), selected.componentGeneration());
            documents.add(document);
            bodies.put(document.documentId(), document.document());
            generations.put(document.documentId(), document.componentGeneration());
        }
        List<ManagedOccurrenceBinding> rows = new ArrayList<>();
        for (ManagedOccurrenceBinding row : original.occurrences()) {
            if (!proofs.containsKey(row.sourceDocumentId())) rows.add(row);
        }
        for (Map.Entry<DocumentId, AffectedClosureSnapshot> entry : proofs.entrySet()) {
            rows.addAll(ownRows(entry.getValue(), entry.getKey()).values());
        }
        Collections.sort(rows);
        RootedWitnessFrame.State witnesses = previous.withSelectedProofs(proofs);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(bodies.keySet(), rows, witnesses);
        ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                new ComponentFinalizationInput(graph, generations, bodies, rows, witnesses));
        // Finalization authenticates the selected closed graph, not a permission to
        // repair an incompatible selection by rewriting any primary or its position.
        for (ManagedDocumentSnapshot document : documents) {
            FinalizedDocumentEvidence exact = finalized.documents().get(document.documentId());
            if (!document.blueId().equals(exact.blueId())
                    || document.componentGeneration() != exact.componentGeneration()) {
                throw new IllegalArgumentException("Witness selection changed a selected exact primary view");
            }
        }
        List<ComponentSnapshot> components = new ArrayList<>();
        finalized.components().forEach(component -> components.add(component.component()));
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        String bindingIdentity = identities.occurrenceBindingSetIdentity(rows);
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(UNBOUND_IDENTITY,
                original.graphGeneration(), documents, rows, bindingIdentity, components,
                original.publicRootDocumentIds(), witnesses);
        AffectedClosureSnapshot result = new AffectedClosureSnapshot(identities.affectedClosureIdentity(provisional),
                original.graphGeneration(), documents, rows, bindingIdentity, components,
                original.publicRootDocumentIds(), witnesses);
        verifyClosedSnapshot(result);
        return result;
    }

    private static void verifyClosedSnapshot(AffectedClosureSnapshot snapshot) {
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        if (!snapshot.occurrenceBindingSetIdentity().equals(identities.occurrenceBindingSetIdentity(snapshot.occurrences()))
                || !snapshot.closureIdentity().equals(identities.affectedClosureIdentity(snapshot))) {
            throw new IllegalArgumentException("Witness selection requires an exactly identity-bound snapshot");
        }
        ClosureEvidenceVerifier.verifySnapshot(snapshot);
    }

    private static void requireUnanchored(AffectedClosureSnapshot original, Set<DocumentId> witnesses,
            DocumentId selected) {
        for (ManagedOccurrenceBinding row : original.occurrences()) {
            if (!row.active() && row.pendingHistoricalEpoch() != null
                    && !witnesses.contains(row.sourceDocumentId()) && row.targetDocumentId().equals(selected)) {
                throw new IllegalArgumentException("Witness selection cannot replace a calculating source's pending anchor: " + selected);
            }
        }
    }

    private static void requireSameInventory(AffectedClosureSnapshot original, AffectedClosureSnapshot proof,
            DocumentId selected) {
        Map<String, ManagedOccurrenceBinding> before = ownRows(original, selected);
        Map<String, ManagedOccurrenceBinding> after = ownRows(proof, selected);
        if (!before.keySet().equals(after.keySet())) {
            throw new IllegalArgumentException("Witness selection does not support changed occurrence inventory: " + selected);
        }
        for (ManagedOccurrenceBinding row : after.values()) {
            if (!original.contains(row.targetDocumentId())) {
                throw new IllegalArgumentException("Witness selection does not support new primary document inventory");
            }
        }
    }

    private static Map<String, ManagedOccurrenceBinding> ownRows(AffectedClosureSnapshot snapshot, DocumentId source) {
        Map<String, ManagedOccurrenceBinding> result = new TreeMap<>();
        for (ManagedOccurrenceBinding row : snapshot.occurrences()) {
            if (source.equals(row.sourceDocumentId())) result.put(row.occurrenceIdentity(), row);
        }
        return result;
    }
}
