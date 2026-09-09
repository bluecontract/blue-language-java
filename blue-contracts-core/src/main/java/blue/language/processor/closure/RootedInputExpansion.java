package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;

/** Proof that resource augmentation preserves the admitted rooted operation. */
final class RootedInputExpansion {
    private RootedInputExpansion() { }

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
