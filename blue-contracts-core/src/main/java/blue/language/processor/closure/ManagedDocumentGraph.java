package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Canonical concrete managed-document graph derived from complete occurrence
 * binding evidence.
 *
 * <p>Every binding row is verified, including inactive reservations. Only
 * active rows contribute directed edges. Parallel active occurrences collapse
 * to one graph edge without losing the complete binding evidence.</p>
 */
public final class ManagedDocumentGraph {

    private final List<DocumentId> documentIds;
    private final List<ManagedOccurrenceBinding> bindings;
    private final List<ManagedOccurrenceBinding> activeBindings;
    private final Map<DocumentId, List<DocumentId>> adjacency;

    private ManagedDocumentGraph(
            List<DocumentId> documentIds,
            List<ManagedOccurrenceBinding> bindings,
            List<ManagedOccurrenceBinding> activeBindings,
            Map<DocumentId, List<DocumentId>> adjacency) {
        this.documentIds = documentIds;
        this.bindings = bindings;
        this.activeBindings = activeBindings;
        this.adjacency = adjacency;
    }

    /**
     * Builds a canonical graph from the complete managed-document membership
     * and complete active/inactive occurrence rows.
     *
     * @param documentIds complete graph membership in any input order
     * @param bindings complete active and inactive rows in any input order
     * @return verified canonical graph
     */
    public static ManagedDocumentGraph fromBindings(
            Collection<DocumentId> documentIds,
            Collection<ManagedOccurrenceBinding> bindings) {
        ArrayList<DocumentId> canonicalDocuments = copyDocuments(documentIds);
        Set<DocumentId> membership = new HashSet<DocumentId>(
                canonicalDocuments);
        ArrayList<ManagedOccurrenceBinding> canonicalBindings = copyBindings(
                bindings, membership);
        ArrayList<ManagedOccurrenceBinding> active =
                new ArrayList<ManagedOccurrenceBinding>();
        LinkedHashMap<DocumentId, TreeSet<DocumentId>> mutableAdjacency =
                new LinkedHashMap<DocumentId, TreeSet<DocumentId>>();
        for (DocumentId documentId : canonicalDocuments) {
            mutableAdjacency.put(documentId, new TreeSet<DocumentId>());
        }
        for (ManagedOccurrenceBinding binding : canonicalBindings) {
            if (binding.active()) {
                active.add(binding);
                mutableAdjacency.get(binding.sourceDocumentId()).add(
                        binding.targetDocumentId());
            }
        }
        LinkedHashMap<DocumentId, List<DocumentId>> frozenAdjacency =
                new LinkedHashMap<DocumentId, List<DocumentId>>();
        for (DocumentId documentId : canonicalDocuments) {
            frozenAdjacency.put(documentId, Collections.unmodifiableList(
                    new ArrayList<DocumentId>(
                            mutableAdjacency.get(documentId))));
        }
        return new ManagedDocumentGraph(
                Collections.unmodifiableList(canonicalDocuments),
                Collections.unmodifiableList(canonicalBindings),
                Collections.unmodifiableList(active),
                Collections.unmodifiableMap(frozenAdjacency));
    }

    /**
     * Returns all graph members in canonical order.
     *
     * @return immutable document identities
     */
    public List<DocumentId> documentIds() {
        return documentIds;
    }

    /**
     * Returns every verified row, including inactive reservations.
     *
     * @return immutable canonical binding rows
     */
    public List<ManagedOccurrenceBinding> bindings() {
        return bindings;
    }

    /**
     * Returns the canonically ordered rows that contributed graph edges.
     *
     * @return immutable active binding rows
     */
    public List<ManagedOccurrenceBinding> activeBindings() {
        return activeBindings;
    }

    /**
     * Returns a canonical, deeply immutable adjacency map.
     *
     * @return every member and its unique active targets
     */
    public Map<DocumentId, List<DocumentId>> adjacency() {
        return adjacency;
    }

    /**
     * Tests graph membership.
     *
     * @param documentId identity to test
     * @return whether the identity is a graph member
     */
    public boolean contains(DocumentId documentId) {
        return adjacency.containsKey(Objects.requireNonNull(
                documentId, "documentId"));
    }

    /**
     * Tests whether at least one active occurrence contributes an edge.
     *
     * @param source source document
     * @param target target document
     * @return whether the directed edge exists
     */
    public boolean hasEdge(DocumentId source, DocumentId target) {
        List<DocumentId> targets = adjacency.get(Objects.requireNonNull(
                source, "source"));
        if (targets == null) {
            return false;
        }
        return Collections.binarySearch(
                targets, Objects.requireNonNull(target, "target")) >= 0;
    }

    /**
     * Tests whether a member has an active edge to itself.
     *
     * @param documentId graph member
     * @return whether a self-edge exists
     */
    public boolean hasSelfEdge(DocumentId documentId) {
        return hasEdge(documentId, documentId);
    }

    private static ArrayList<DocumentId> copyDocuments(
            Collection<DocumentId> values) {
        ArrayList<DocumentId> copy = new ArrayList<DocumentId>(
                Objects.requireNonNull(values, "documentIds"));
        Set<DocumentId> unique = new HashSet<DocumentId>();
        for (DocumentId documentId : copy) {
            if (!unique.add(Objects.requireNonNull(
                    documentId, "documentId"))) {
                throw new IllegalArgumentException(
                        "Duplicate managed DocumentId");
            }
        }
        Collections.sort(copy);
        return copy;
    }

    private static ArrayList<ManagedOccurrenceBinding> copyBindings(
            Collection<ManagedOccurrenceBinding> values,
            Set<DocumentId> membership) {
        ArrayList<ManagedOccurrenceBinding> copy =
                new ArrayList<ManagedOccurrenceBinding>(
                        Objects.requireNonNull(values, "bindings"));
        Set<String> occurrenceIdentities = new HashSet<String>();
        Set<String> bindingIdentities = new HashSet<String>();
        Map<DocumentId, Set<String>> sourcePaths =
                new HashMap<DocumentId, Set<String>>();
        for (ManagedOccurrenceBinding binding : copy) {
            Objects.requireNonNull(binding, "binding");
            if (!membership.contains(binding.sourceDocumentId())) {
                throw new IllegalArgumentException(
                        "Occurrence source is outside graph membership");
            }
            if (!membership.contains(binding.targetDocumentId())) {
                throw new IllegalArgumentException(
                        "Occurrence target is outside graph membership");
            }
            if (!occurrenceIdentities.add(binding.occurrenceIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate occurrence identity");
            }
            if (!bindingIdentities.add(binding.bindingIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate binding identity");
            }
            Set<String> paths = sourcePaths.get(binding.sourceDocumentId());
            if (paths == null) {
                paths = new HashSet<String>();
                sourcePaths.put(binding.sourceDocumentId(), paths);
            }
            if (!paths.add(binding.sourcePath())) {
                throw new IllegalArgumentException(
                        "More than one occurrence row for a source path");
            }
        }
        Collections.sort(copy);
        return copy;
    }
}
