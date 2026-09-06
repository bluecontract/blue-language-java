package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.LinkedHashSet;

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
    private final List<ManagedReadPin> readPins;
    private final Map<DocumentId, ComponentSnapshot> componentsByMember;
    private final Map<DocumentId, List<ManagedOccurrenceBinding>> outgoingByMember;

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
        this(closureIdentity, graphGeneration, managedDocuments, occurrences,
                occurrenceBindingSetIdentity, components, publicRootDocumentIds,
                Collections.<ManagedReadPin>emptyList());
    }

    /** Adds verified historical dependency bodies without adding mutable lineage cells. */
    public AffectedClosureSnapshot(String closureIdentity, long graphGeneration,
            List<ManagedDocumentSnapshot> managedDocuments, List<ManagedOccurrenceBinding> occurrences,
            String occurrenceBindingSetIdentity, List<ComponentSnapshot> components,
            List<DocumentId> publicRootDocumentIds, List<ManagedReadPin> readPins) {
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
        ArrayList<ManagedReadPin> pins = new ArrayList<ManagedReadPin>(Objects.requireNonNull(readPins, "readPins"));
        Collections.sort(pins);
        for (int index = 0; index < pins.size(); index++) {
            ManagedReadPin pin = Objects.requireNonNull(pins.get(index), "readPin");
            if (!documentsById.containsKey(pin.documentId())
                    || index > 0 && pins.get(index - 1).compareTo(pin) == 0) {
                throw new IllegalArgumentException("Read pins must uniquely name a member's exact dependency state");
            }
        }
        this.readPins = Collections.unmodifiableList(pins);
        validateOccurrenceEndpoints();
        validateComponentPartition();
        validatePublicRoots();
        Map<DocumentId, ComponentSnapshot> componentIndex = new LinkedHashMap<DocumentId, ComponentSnapshot>();
        for (ComponentSnapshot component : this.components)
            for (DocumentId member : component.orderedMemberDocumentIds()) componentIndex.put(member, component);
        this.componentsByMember = Collections.unmodifiableMap(componentIndex);
        Map<DocumentId, List<ManagedOccurrenceBinding>> outgoing = new LinkedHashMap<DocumentId, List<ManagedOccurrenceBinding>>();
        for (DocumentId member : documentsById.keySet()) outgoing.put(member, new ArrayList<ManagedOccurrenceBinding>());
        for (ManagedOccurrenceBinding row : this.occurrences) outgoing.get(row.sourceDocumentId()).add(row);
        Map<DocumentId, List<ManagedOccurrenceBinding>> stable = new LinkedHashMap<DocumentId, List<ManagedOccurrenceBinding>>();
        for (Map.Entry<DocumentId, List<ManagedOccurrenceBinding>> entry : outgoing.entrySet())
            stable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        this.outgoingByMember = Collections.unmodifiableMap(stable);
    }

    public ComponentSnapshot component(DocumentId member) { return componentsByMember.get(member); }
    public List<ManagedOccurrenceBinding> occurrencesFrom(DocumentId member) {
        List<ManagedOccurrenceBinding> found = outgoingByMember.get(member);
        return found == null ? Collections.<ManagedOccurrenceBinding>emptyList() : found;
    }

    /** Exact reusable authority is physical evidence, not another semantic identity field. */
    public List<ReusableComponentAuthority> reusableComponents() {
        Set<ReusableComponentAuthority> result = new LinkedHashSet<ReusableComponentAuthority>();
        for (ManagedDocumentSnapshot document : managedDocuments)
            if (document.reusableAuthority().isPresent()) result.add(document.reusableAuthority().get());
        return Collections.unmodifiableList(new ArrayList<ReusableComponentAuthority>(result));
    }

    public AffectedClosureSnapshot retainResidentBodies(Set<DocumentId> resident) {
        return retainResidentBodies(resident, Collections.<RootChannelMetadata>emptyList());
    }

    /**
     * Verifies this exact owning state before discarding body and cyclic-proof
     * payloads. The resulting immutable authority has no hidden body cache.
     * Metadata was captured by the owning configured channel projector.
     */
    public AffectedClosureSnapshot retainResidentBodies(Set<DocumentId> resident,
            Collection<RootChannelMetadata> metadata) {
        Objects.requireNonNull(resident, "resident");
        if (!documentsById.keySet().containsAll(resident))
            throw new IllegalArgumentException("Resident body selection contains a foreign lineage");
        Map<DocumentId, RootChannelMetadata> roots = new TreeMap<DocumentId, RootChannelMetadata>();
        for (RootChannelMetadata value : Objects.requireNonNull(metadata, "metadata")) {
            value.verifyState(Objects.requireNonNull(managedDocument(value.documentId()), "metadata member"));
            if (roots.put(value.documentId(), value) != null) throw new IllegalArgumentException("Duplicate root metadata");
        }
        ComponentFinalizationResult verified = ClosureEvidenceVerifier.verifyAndFinalizeSnapshot(this);
        Map<DocumentId, ReusableComponentAuthority> authorities = new LinkedHashMap<DocumentId, ReusableComponentAuthority>();
        List<ComponentSnapshot> headers = new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence exact : verified.components()) {
            DocumentId first = exact.component().orderedMemberDocumentIds().get(0);
            ReusableComponentAuthority authority = managedDocument(first).reusableAuthority().orElse(null);
            if (authority != null) {
                authority.verifyUnchanged(this);
                authority = authority.withMetadata(roots);
            } else {
                authority = ReusableComponentAuthority.captureVerified(this, exact, roots);
            }
            headers.add(authority.component());
            for (DocumentId member : authority.component().orderedMemberDocumentIds()) authorities.put(member, authority);
        }
        List<ManagedDocumentSnapshot> documents = new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot original : managedDocuments)
            documents.add(authorities.get(original.documentId()).retainingBody(original, resident.contains(original.documentId())));
        return ClosureEvidenceFactory.affectedClosure(graphGeneration, documents, occurrences, headers, publicRootDocumentIds, readPins);
    }

    /** Additive exact evidence does not change any semantic snapshot or invocation identity. */
    public AffectedClosureSnapshot withResidentBody(ManagedReadPin exact) {
        ManagedDocumentSnapshot old = Objects.requireNonNull(managedDocument(exact.documentId()), "body member");
        ManagedDocumentSnapshot hydrated = old.withResidentBody(exact);
        List<ManagedDocumentSnapshot> documents = new ArrayList<ManagedDocumentSnapshot>(managedDocuments);
        documents.set(documents.indexOf(old), hydrated);
        return ClosureEvidenceFactory.affectedClosure(graphGeneration, documents, occurrences, components, publicRootDocumentIds, readPins);
    }

    /**
     * Applies the established host-generation/public-root normalization while
     * preserving residency. Cyclic normalized proof identity was computed at
     * verified capture, never guessed from another proof digest.
     */
    public AffectedClosureSnapshot canonicalSemanticView() {
        List<ManagedDocumentSnapshot> documents = new ArrayList<ManagedDocumentSnapshot>();
        List<ComponentSnapshot> normalized = new ArrayList<ComponentSnapshot>();
        for (ComponentSnapshot component : components) {
            ReusableComponentAuthority authority = managedDocument(component.orderedMemberDocumentIds().get(0))
                    .reusableAuthority().orElse(null);
            if (authority != null) {
                authority.verifyUnchanged(this);
                ReusableComponentAuthority canonical = authority.canonicalSemanticAuthority();
                normalized.add(canonical.component());
                for (DocumentId member : component.orderedMemberDocumentIds()) {
                    ManagedDocumentSnapshot previous = managedDocument(member);
                    documents.add(canonical.retainingCanonicalBody(previous));
                }
            } else {
                for (DocumentId member : component.orderedMemberDocumentIds()) {
                    ManagedDocumentSnapshot previous = managedDocument(member);
                    documents.add(new ManagedDocumentSnapshot(member, previous.blueId(), previous.document(), previous.initialized(),
                            previous.terminated(), true, previous.epoch(), 0L));
                }
                if (component.kind() == ComponentKind.ACYCLIC)
                    normalized.add(ClosureEvidenceFactory.acyclicComponent(documents.get(documents.size() - 1)));
                else normalized.add(ClosureEvidenceFactory.cyclicComponent(0L, component.orderedMemberDocumentIds(),
                        component.orderedMemberBlueIds(), component.masterBlueId(), component.completeCyclicProof()));
            }
        }
        List<DocumentId> publicRoots = new ArrayList<DocumentId>(documentsById.keySet());
        return ClosureEvidenceFactory.affectedClosure(0L, documents, occurrences, normalized, publicRoots, readPins);
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

    public List<ManagedReadPin> readPins() { return readPins; }

    public ManagedReadPin readPin(DocumentId documentId, String blueId) {
        for (ManagedReadPin pin : readPins) {
            if (pin.documentId().equals(documentId) && pin.blueId().equals(blueId)) return pin;
        }
        return null;
    }

    Map<String, ManagedReadPin> pinnedOccurrences() {
        Map<String, ManagedReadPin> result = new LinkedHashMap<String, ManagedReadPin>();
        for (ManagedOccurrenceBinding binding : occurrences) {
            if (binding.active() && !managedDocument(binding.targetDocumentId()).blueId().equals(binding.expectedTargetBlueId())) {
                result.put(binding.occurrenceIdentity(), Objects.requireNonNull(
                        readPin(binding.targetDocumentId(), binding.expectedTargetBlueId()), "exact read pin"));
            }
        }
        return Collections.unmodifiableMap(result);
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
        for (ManagedOccurrenceBinding occurrence : occurrences) {
            ManagedDocumentSnapshot source = documentsById.get(
                    occurrence.sourceDocumentId());
            ManagedDocumentSnapshot target = documentsById.get(
                    occurrence.targetDocumentId());
            if (source == null || target == null) {
                throw new IllegalArgumentException(
                        "Occurrence endpoint is outside the affected closure");
            }
            if (occurrence.active()
                    && !target.blueId().equals(
                            occurrence.expectedTargetBlueId())
                    && readPin(occurrence.targetDocumentId(), occurrence.expectedTargetBlueId()) == null) {
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

        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                documentsById.keySet(), occurrences);
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
