package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure preparation of the closed-evidence replay specified by Contracts §9.4. */
final class ProspectiveBirthRetry {
    private ProspectiveBirthRetry() { }

    static ClosureInvocationInput prepare(ClosureInvocationInput input,
                                         List<ManagedDocumentBirth> births) {
        Objects.requireNonNull(input, "input");
        if (input.admissionCandidate() != null) {
            throw new IllegalArgumentException("Invalid admission candidates cannot resolve births");
        }
        if (Objects.requireNonNull(births, "births").isEmpty()) {
            throw new IllegalArgumentException("A birth retry requires exact birth evidence");
        }
        AffectedClosureSnapshot before = input.snapshot();
        Map<DocumentId, ManagedDocumentSnapshot> documents = new TreeMap<>();
        for (ManagedDocumentSnapshot document : before.managedDocuments()) {
            documents.put(document.documentId(), document);
        }
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>(before.occurrences());
        Set<String> demands = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (ManagedOccurrenceBinding binding : bindings) {
            paths.add(pathKey(binding.sourceDocumentId(), binding.sourcePath()));
        }
        List<ComponentSnapshot> components = new ArrayList<>(before.components());
        List<ManagedDocumentBirth> ordered = new ArrayList<>(births);
        ordered.sort(Comparator.comparing(ManagedDocumentBirth::documentId));
        for (ManagedDocumentBirth birth : ordered) {
            ManagedOccurrenceEvidenceDemand demand = birth.demand();
            if (!demand.logicalCauseIdentity().equals(input.cause().causeIdentity())
                    || !demand.inputClosureIdentity().equals(before.closureIdentity())
                    || demand.inputGraphGeneration() != before.graphGeneration()
                    || before.managedDocument(demand.sourceDocumentId()) == null
                    || !demands.add(demand.demandIdentity())) {
                throw new IllegalArgumentException("Birth demand does not belong to this exact input");
            }
            if (documents.containsKey(birth.documentId())) {
                throw new IllegalArgumentException("Birth lineage is already represented in the closure");
            }
            if (!paths.add(pathKey(demand.sourceDocumentId(), demand.sourcePath()))) {
                throw new IllegalArgumentException("Birth occurrence already has lineage evidence");
            }
            ManagedDocumentSnapshot document = new ManagedDocumentSnapshot(birth.documentId(),
                    demand.suppliedValueBlueId(), birth.document(), false, false, false, 0L, 1L);
            documents.put(birth.documentId(), document);
            components.add(ClosureEvidenceFactory.acyclicComponent(document));
            bindings.add(ManagedOccurrenceBinding.derived(
                    input.environment().managedBindingPolicyIdentity(), demand.sourceDocumentId(),
                    ScopeAddress.embedded(demand.sourcePath(), 1L), birth.documentId(),
                    demand.suppliedValueBlueId(), false, null));
        }
        // The added edges are inactive: all predecessor components and exact
        // document heads remain untouched. Canonical component ordering is
        // computed over the expanded inventory without finalizing live heads.
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(documents.keySet(), bindings);
        Map<DocumentId, ComponentSnapshot> byMember = new HashMap<>();
        for (ComponentSnapshot component : components) {
            for (DocumentId id : component.orderedMemberDocumentIds()) byMember.put(id, component);
        }
        List<ComponentSnapshot> sorted = new ArrayList<>();
        for (List<DocumentId> members : new SccPartitioner().partition(graph)) {
            sorted.add(byMember.get(members.get(0)));
        }
        AffectedClosureSnapshot expanded = ClosureEvidenceFactory.affectedClosure(
                before.graphGeneration(), new ArrayList<>(documents.values()), bindings,
                sorted, before.publicRootDocumentIds());
        if (input.operation() == ClosureInvocationInput.Operation.ADMIT_CLOSURE) {
            return ClosureEvidenceFactory.admitClosure(expanded, (AdmissionCause) input.cause(),
                    null, input.executionPolicy(), input.environment());
        }
        return ClosureEvidenceFactory.processClosure(expanded, input.cause(), input.directDeliveries(),
                input.executionPolicy(), input.environment());
    }

    private static String pathKey(DocumentId source, String path) {
        return source.value().length() + ":" + source.value() + path;
    }
}
