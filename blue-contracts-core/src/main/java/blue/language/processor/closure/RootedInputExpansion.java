package blue.language.processor.closure;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;

/** Proof that resource augmentation preserves the admitted rooted operation. */
final class RootedInputExpansion {
    private RootedInputExpansion() { }

    static ClosureInvocationInput prepare(ClosureInvocationInput original, long generation,
            List<ManagedDocumentSnapshot> documents, List<ManagedOccurrenceBinding> bindings,
            List<AffectedClosureSnapshot> proofs) {
        Objects.requireNonNull(original, "original");
        if (original.rootedBinding() == null) throw new IllegalArgumentException("Read evidence requires rooted authority");
        List<ManagedDocumentSnapshot> selected = new ArrayList<>(documents);
        selected.sort(Comparator.comparing(ManagedDocumentSnapshot::documentId));
        List<ManagedOccurrenceBinding> rows = new ArrayList<>(bindings);
        Collections.sort(rows);
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        Map<DocumentId, Long> generations = new LinkedHashMap<>();
        for (ManagedDocumentSnapshot document : selected) {
            if (bodies.put(document.documentId(), document.document()) != null)
                throw new IllegalArgumentException("Duplicate primary document view");
            generations.put(document.documentId(), document.componentGeneration());
        }
        RootedWitnessFrame.State witnesses = RootedWitnessFrame.readExpansion(original, selected, rows, proofs);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(bodies.keySet(), rows, witnesses.sources());
        ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                graph, generations, bodies, rows, witnesses));
        for (ManagedDocumentSnapshot document : selected) {
            FinalizedDocumentEvidence exact = finalized.documents().get(document.documentId());
            if (!exact.blueId().equals(document.blueId()) || exact.componentGeneration() != document.componentGeneration())
                throw new IllegalArgumentException("Read evidence changed a selected exact primary view");
        }
        List<ComponentSnapshot> components = new ArrayList<ComponentSnapshot>();
        finalized.components().forEach(component -> components.add(component.component()));
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        String bindingIdentity = identities.occurrenceBindingSetIdentity(rows);
        List<DocumentId> roots = original.snapshot().publicRootDocumentIds();
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                "sha256:0000000000000000000000000000000000000000000000000000000000000000", generation, selected, rows,
                bindingIdentity, components, roots, witnesses);
        AffectedClosureSnapshot snapshot = new AffectedClosureSnapshot(identities.affectedClosureIdentity(provisional), generation,
                selected, rows, bindingIdentity, components, roots, witnesses);
        return ClosureEvidenceFactory.processClosure(snapshot, original.cause(), original.directDeliveries(),
                original.executionPolicy(), original.environment()).withRootedReadExpansionOf(original);
    }

    static void verify(ClosureInvocationInput original, ClosureInvocationInput expanded) {
        if (original.rootedBinding() == null || expanded.rootedBinding() != null
                || original.operation() != ClosureInvocationInput.Operation.PROCESS_CLOSURE
                || expanded.operation() != original.operation()) {
            throw new IllegalArgumentException("Read expansion requires an unchanged rooted processing operation");
        }
        ClosureEvidenceVerifier.verifySnapshot(original.snapshot());
        ClosureEvidenceVerifier.verifySnapshot(expanded.snapshot());
        // Reconstructing on the original snapshot checks every immutable environment,
        // delivery, cause and policy operand through the unchanged base identity rule.
        String frozen = ClosureEvidenceFactory.processClosure(original.snapshot(), expanded.cause(),
                expanded.directDeliveries(), expanded.executionPolicy(), expanded.environment()).invocationIdentity();
        if (!frozen.equals(original.invocationIdentity())) {
            throw new IllegalArgumentException("Read expansion changed the cause, budget, deliveries or runtime");
        }
        if (!original.snapshot().publicRootDocumentIds().equals(expanded.snapshot().publicRootDocumentIds())) {
            throw new IllegalArgumentException("Read evidence cannot add public owners");
        }
        for (ManagedDocumentSnapshot before : original.snapshot().managedDocuments()) {
            ManagedDocumentSnapshot after = expanded.snapshot().managedDocument(before.documentId());
            if (after == null || !before.blueId().equals(after.blueId())
                    || before.componentGeneration() != after.componentGeneration()
                    || before.epoch() != after.epoch() || before.initialized() != after.initialized()
                    || before.terminated() != after.terminated() || before.publicRoot() != after.publicRoot()) {
                throw new IllegalArgumentException("Read expansion changed an original exact document");
            }
        }
        Map<String, ManagedOccurrenceBinding> originalRows = rows(original.snapshot());
        Map<String, ManagedOccurrenceBinding> expandedRows = rows(expanded.snapshot());
        for (Map.Entry<String, ManagedOccurrenceBinding> entry : originalRows.entrySet()) {
            ManagedOccurrenceBinding after = expandedRows.get(entry.getKey());
            if (after == null || !entry.getValue().bindingIdentity().equals(after.bindingIdentity())) {
                throw new IllegalArgumentException("Read expansion changed an original occurrence");
            }
        }
        for (Map.Entry<String, ManagedOccurrenceBinding> entry : expandedRows.entrySet()) {
            ManagedOccurrenceBinding row = entry.getValue();
            if (!originalRows.containsKey(entry.getKey()) && row.active()
                    && original.snapshot().contains(row.sourceDocumentId())) {
                throw new IllegalArgumentException("Read evidence cannot activate an edge from an original document");
            }
        }
    }

    private static Map<String, ManagedOccurrenceBinding> rows(AffectedClosureSnapshot snapshot) {
        Map<String, ManagedOccurrenceBinding> rows = new LinkedHashMap<>();
        for (ManagedOccurrenceBinding row : snapshot.occurrences()) rows.put(row.occurrenceIdentity(), row);
        return rows;
    }
}
