package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Complete immutable result of one pure component-finalization pass.
 *
 * <p>The result makes no commit, scheduling, epoch, or gas claim. Its graph
 * contains the same active topology with active rows rebound to the exact
 * finalized target identities.</p>
 */
public final class ComponentFinalizationResult {

    private final ManagedDocumentGraph finalizedGraph;
    private final Map<DocumentId, Long> componentGenerations;
    private final List<FinalizedComponentEvidence> components;
    private final Map<DocumentId, FinalizedDocumentEvidence> documents;

    ComponentFinalizationResult(
            ManagedDocumentGraph finalizedGraph,
            Map<DocumentId, Long> componentGenerations,
            List<FinalizedComponentEvidence> components,
            Map<DocumentId, FinalizedDocumentEvidence> documents) {
        this.finalizedGraph = Objects.requireNonNull(
                finalizedGraph, "finalizedGraph");
        this.componentGenerations = immutableGenerations(
                finalizedGraph, componentGenerations);
        this.components = immutableComponents(components);
        this.documents = immutableDocuments(documents);
        validateCoverageAndOrder();
    }

    /**
     * Returns the complete active/inactive binding graph after exact rebasing.
     *
     * @return immutable finalized graph
     */
    public ManagedDocumentGraph finalizedGraph() {
        return finalizedGraph;
    }

    /**
     * Returns every resulting component generation by document lineage.
     *
     * @return immutable canonical generation map
     */
    public Map<DocumentId, Long> componentGenerations() {
        return componentGenerations;
    }

    /**
     * Returns finalized components in canonical target-before-source order.
     *
     * @return immutable component evidence sequence
     */
    public List<FinalizedComponentEvidence> components() {
        return components;
    }

    /**
     * Returns finalized documents in canonical lineage order.
     *
     * @return immutable document evidence map
     */
    public Map<DocumentId, FinalizedDocumentEvidence> documents() {
        return documents;
    }

    /**
     * Finds one exact finalized managed document.
     *
     * @param documentId stable lineage to find
     * @return finalized evidence
     * @throws IllegalArgumentException if the lineage is not in this result
     */
    public FinalizedDocumentEvidence document(DocumentId documentId) {
        FinalizedDocumentEvidence result = documents.get(
                Objects.requireNonNull(documentId, "documentId"));
        if (result == null) {
            throw new IllegalArgumentException(
                    "Document is outside the finalized graph");
        }
        return result;
    }

    private static Map<DocumentId, Long> immutableGenerations(
            ManagedDocumentGraph graph,
            Map<DocumentId, Long> values) {
        Map<DocumentId, Long> source = Objects.requireNonNull(
                values, "componentGenerations");
        if (!source.keySet().equals(new HashSet<DocumentId>(
                graph.documentIds()))) {
            throw new IllegalArgumentException(
                    "Component generations do not cover the finalized graph");
        }
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        for (DocumentId documentId : graph.documentIds()) {
            Long generation = Objects.requireNonNull(source.get(documentId),
                    "component generation");
            result.put(documentId, Long.valueOf(
                    ClosureValueSupport.requireSafeInteger(
                            generation.longValue(), "component generation")));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<FinalizedComponentEvidence> immutableComponents(
            List<FinalizedComponentEvidence> values) {
        ArrayList<FinalizedComponentEvidence> result =
                new ArrayList<FinalizedComponentEvidence>(
                        Objects.requireNonNull(values, "components"));
        for (FinalizedComponentEvidence component : result) {
            Objects.requireNonNull(component, "component");
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<DocumentId, FinalizedDocumentEvidence>
            immutableDocuments(
                    Map<DocumentId, FinalizedDocumentEvidence> values) {
        TreeMap<DocumentId, FinalizedDocumentEvidence> ordered =
                new TreeMap<DocumentId, FinalizedDocumentEvidence>();
        for (Map.Entry<DocumentId, FinalizedDocumentEvidence> entry
                : Objects.requireNonNull(values, "documents").entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "document evidence DocumentId");
            FinalizedDocumentEvidence document = Objects.requireNonNull(
                    entry.getValue(), "document evidence");
            if (!documentId.equals(document.documentId())) {
                throw new IllegalArgumentException(
                        "Document evidence key does not match its lineage");
            }
            ordered.put(documentId, document);
        }
        return Collections.unmodifiableMap(
                new LinkedHashMap<DocumentId, FinalizedDocumentEvidence>(
                        ordered));
    }

    private void validateCoverageAndOrder() {
        if (!documents.keySet().equals(new HashSet<DocumentId>(
                finalizedGraph.documentIds()))) {
            throw new IllegalArgumentException(
                    "Finalized documents do not cover the graph");
        }
        LinkedHashMap<DocumentId, Integer> owners =
                new LinkedHashMap<DocumentId, Integer>();
        for (int index = 0; index < components.size(); index++) {
            ComponentSnapshot component = components.get(index).component();
            for (DocumentId member : component.orderedMemberDocumentIds()) {
                if (owners.put(member, Integer.valueOf(index)) != null) {
                    throw new IllegalArgumentException(
                            "A document appears in more than one component");
                }
                FinalizedDocumentEvidence document = documents.get(member);
                if (document == null
                        || !component.componentIdentity().equals(
                                document.componentIdentity())
                        || !component.componentStateIdentity().equals(
                                document.componentStateIdentity())
                        || component.componentGeneration()
                        != componentGenerations.get(member).longValue()) {
                    throw new IllegalArgumentException(
                            "Component and document evidence are inconsistent");
                }
            }
        }
        if (!owners.keySet().equals(documents.keySet())) {
            throw new IllegalArgumentException(
                    "Component evidence does not cover every document");
        }
        for (Map.Entry<DocumentId, List<DocumentId>> edge
                : finalizedGraph.adjacency().entrySet()) {
            int sourceOwner = owners.get(edge.getKey()).intValue();
            for (DocumentId target : edge.getValue()) {
                int targetOwner = owners.get(target).intValue();
                if (sourceOwner != targetOwner
                        && targetOwner >= sourceOwner) {
                    throw new IllegalArgumentException(
                            "Components are not in target-before-source order");
                }
            }
        }
    }
}
