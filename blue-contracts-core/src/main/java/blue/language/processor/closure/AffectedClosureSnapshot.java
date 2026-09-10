package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete immutable authoritative state for one connected affected closure.
 * Cause, delivery and execution-policy evidence deliberately live elsewhere.
 */
public final class AffectedClosureSnapshot {

    private final String closureIdentity;
    private final long graphGeneration;
    private final List<ManagedDocumentSnapshot> managedDocuments;
    private final List<ManagedOccurrenceBinding> occurrences;
    private final String occurrenceBindingSetIdentity;
    private final List<ComponentSnapshot> components;
    private final List<DocumentId> publicRootDocumentIds;
    private final Map<DocumentId, ManagedDocumentSnapshot> documentsById;
    private final RootedWitnessFrame.State rootedWitnesses;

    /**
     * Creates one closed authoritative affected-closure state.
     *
     * @param closureIdentity asserted state-only closure identity
     * @param graphGeneration frozen managed-graph generation
     * @param managedDocuments canonical current document records
     * @param occurrences canonical authoritative binding rows
     * @param occurrenceBindingSetIdentity asserted complete row-set identity
     * @param components canonical initial component partition
     * @param publicRootDocumentIds canonical public Root declarations
     */
    public AffectedClosureSnapshot(
            String closureIdentity,
            long graphGeneration,
            List<ManagedDocumentSnapshot> managedDocuments,
            List<ManagedOccurrenceBinding> occurrences,
            String occurrenceBindingSetIdentity,
            List<ComponentSnapshot> components,
            List<DocumentId> publicRootDocumentIds) {
        this(closureIdentity, graphGeneration, managedDocuments, occurrences, occurrenceBindingSetIdentity,
                components, publicRootDocumentIds, null);
    }

    AffectedClosureSnapshot(String closureIdentity, long graphGeneration,
            List<ManagedDocumentSnapshot> managedDocuments, List<ManagedOccurrenceBinding> occurrences,
            String occurrenceBindingSetIdentity, List<ComponentSnapshot> components,
            List<DocumentId> publicRootDocumentIds, RootedWitnessFrame.State rootedWitnesses) {
        this.rootedWitnesses = rootedWitnesses;
        this.closureIdentity = ClosureValueSupport.requireSha256Identity(
                closureIdentity, "closureIdentity");
        this.graphGeneration = ClosureValueSupport.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.managedDocuments = immutableCanonicalDocuments(managedDocuments);
        this.documentsById = indexDocuments(this.managedDocuments);
        this.occurrences = immutableCanonicalOccurrences(occurrences);
        this.occurrenceBindingSetIdentity =
                ClosureValueSupport.requireSha256Identity(
                        occurrenceBindingSetIdentity,
                        "occurrenceBindingSetIdentity");
        this.components = immutableComponents(components);
        this.publicRootDocumentIds = immutableCanonicalDocumentIds(
                publicRootDocumentIds, "publicRootDocumentIds");
        if (rootedWitnesses != null) rootedWitnesses.requireUnchanged(this);
        validateOccurrenceEndpoints();
        validateComponentPartition();
        validatePublicRoots();
    }

    RootedWitnessFrame.State rootedWitnesses() { return rootedWitnesses; }

    ManagedDocumentGraph graph() {
        return ManagedDocumentGraph.fromBindings(documentsById.keySet(), occurrences,
                rootedWitnesses == null ? Collections.<DocumentId>emptySet() : rootedWitnesses.sources());
    }

    /**
     * Returns the documented value.
     *
     * @return asserted state-only closure identity
     */
    public String closureIdentity() {
        return closureIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return frozen graph generation
     */
    public long graphGeneration() {
        return graphGeneration;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical managed-document records
     */
    public List<ManagedDocumentSnapshot> managedDocuments() {
        return managedDocuments;
    }

    /**
     * Looks up one managed-document record.
     *
     * @param documentId stable lineage to find
     * @return matching record, or {@code null}
     */
    public ManagedDocumentSnapshot managedDocument(DocumentId documentId) {
        return documentsById.get(Objects.requireNonNull(documentId, "documentId"));
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical occurrence rows
     */
    public List<ManagedOccurrenceBinding> occurrences() {
        return occurrences;
    }

    /**
     * Returns the documented value.
     *
     * @return asserted complete occurrence-row-set identity
     */
    public String occurrenceBindingSetIdentity() {
        return occurrenceBindingSetIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical component partition
     */
    public List<ComponentSnapshot> components() {
        return components;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical public Root identities
     */
    public List<DocumentId> publicRootDocumentIds() {
        return publicRootDocumentIds;
    }

    /**
     * Tests closure membership by stable lineage.
     *
     * @param documentId stable lineage to test
     * @return whether that lineage is present
     */
    public boolean contains(DocumentId documentId) {
        return documentsById.containsKey(
                Objects.requireNonNull(documentId, "documentId"));
    }

    private void validateOccurrenceEndpoints() {
        ManagedDocumentGraph graph = graph();
        for (ManagedOccurrenceBinding occurrence : occurrences) {
            ManagedDocumentSnapshot source = documentsById.get(
                    occurrence.sourceDocumentId());
            ManagedDocumentSnapshot target = documentsById.get(
                    occurrence.targetDocumentId());
            if (source == null || target == null) {
                throw new IllegalArgumentException(
                        "Occurrence endpoint is outside the affected closure");
            }
            if (graph.calculating(occurrence)
                    && !target.blueId().equals(
                            occurrence.expectedTargetBlueId())) {
                throw new IllegalArgumentException(
                        "Active occurrence target BlueId disagrees with current state");
            }
        }
    }

    private void validateComponentPartition() {
        Set<DocumentId> covered = new HashSet<DocumentId>();
        for (ComponentSnapshot component : components) {
            List<DocumentId> memberIds = component.orderedMemberDocumentIds();
            List<String> memberBlueIds = component.orderedMemberBlueIds();
            for (int index = 0; index < memberIds.size(); index++) {
                DocumentId memberId = memberIds.get(index);
                ManagedDocumentSnapshot document = documentsById.get(memberId);
                if (document == null) {
                    throw new IllegalArgumentException(
                            "Component member is outside the affected closure");
                }
                if (!covered.add(memberId)) {
                    throw new IllegalArgumentException(
                            "Managed document appears in more than one component");
                }
                if (!document.blueId().equals(memberBlueIds.get(index))) {
                    throw new IllegalArgumentException(
                            "Component member BlueId disagrees with document state");
                }
                if (document.componentGeneration()
                        != component.componentGeneration()) {
                    throw new IllegalArgumentException(
                            "Component generation disagrees with document state");
                }
            }
        }
        if (!covered.equals(documentsById.keySet())) {
            throw new IllegalArgumentException(
                    "Components do not partition all managed documents");
        }

        ManagedDocumentGraph graph = graph();
        List<List<DocumentId>> expected = new SccPartitioner().partition(graph);
        if (expected.size() != components.size()) {
            throw new IllegalArgumentException(
                    "Components do not match the active managed graph");
        }
        for (int index = 0; index < expected.size(); index++) {
            List<DocumentId> expectedMembers = expected.get(index);
            ComponentSnapshot actual = components.get(index);
            if (!expectedMembers.equals(actual.orderedMemberDocumentIds())) {
                throw new IllegalArgumentException(
                        "Components are not in canonical target-before-source order");
            }
            boolean expectedCyclic = expectedMembers.size() > 1
                    || graph.hasSelfEdge(expectedMembers.get(0));
            if (expectedCyclic != (actual.kind() == ComponentKind.CYCLIC)) {
                throw new IllegalArgumentException(
                        "Component kind disagrees with the active managed graph");
            }
        }
    }

    private void validatePublicRoots() {
        List<DocumentId> declared = new ArrayList<DocumentId>();
        for (ManagedDocumentSnapshot document : managedDocuments) {
            if (document.publicRoot()) {
                declared.add(document.documentId());
            }
        }
        if (!declared.equals(publicRootDocumentIds)) {
            throw new IllegalArgumentException(
                    "Public Root list disagrees with managed-document flags");
        }
    }

    private static List<ManagedDocumentSnapshot> immutableCanonicalDocuments(
            List<ManagedDocumentSnapshot> values) {
        ArrayList<ManagedDocumentSnapshot> copy = copyNonNull(
                values, "managedDocuments");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "An affected closure must contain a managed document");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "Managed documents are not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<DocumentId, ManagedDocumentSnapshot> indexDocuments(
            List<ManagedDocumentSnapshot> documents) {
        LinkedHashMap<DocumentId, ManagedDocumentSnapshot> indexed =
                new LinkedHashMap<DocumentId, ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot document : documents) {
            indexed.put(document.documentId(), document);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static List<ManagedOccurrenceBinding> immutableCanonicalOccurrences(
            List<ManagedOccurrenceBinding> values) {
        ArrayList<ManagedOccurrenceBinding> copy = copyNonNull(
                values, "occurrences");
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "Occurrence bindings are not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<ComponentSnapshot> immutableComponents(
            List<ComponentSnapshot> values) {
        ArrayList<ComponentSnapshot> copy = copyNonNull(values, "components");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "An affected closure must contain a component");
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<DocumentId> immutableCanonicalDocumentIds(
            List<DocumentId> values,
            String field) {
        ArrayList<DocumentId> copy = copyNonNull(values, field);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        field + " is not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static <T> ArrayList<T> copyNonNull(
            List<T> values,
            String field) {
        ArrayList<T> copy = new ArrayList<T>(
                Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return copy;
    }
}
