package blue.language.processor.closure;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Complete immutable input to one semantic component-finalization pass.
 *
 * <p>The latest local bodies are independent managed-document Roots. The
 * binding rows are orchestration evidence and are never injected into a
 * document step as reverse-containment context.</p>
 */
public final class ComponentFinalizationInput {

    private final ManagedDocumentGraph inputGraph;
    private final Map<DocumentId, Long> inputComponentGenerations;
    private final ManagedDocumentGraph resultingGraph;
    private final Map<DocumentId, Node> latestLocalBodies;

    /**
     * Creates a complete finalization input and derives its resulting graph.
     *
     * @param inputGraph authoritative graph before the transition
     * @param inputComponentGenerations exact generation of every input member
     * @param latestLocalBodies complete resulting local body by lineage
     * @param resultingBindings complete resulting active and inactive rows
     */
    public ComponentFinalizationInput(
            ManagedDocumentGraph inputGraph,
            Map<DocumentId, Long> inputComponentGenerations,
            Map<DocumentId, Node> latestLocalBodies,
            Collection<ManagedOccurrenceBinding> resultingBindings) {
        this.inputGraph = Objects.requireNonNull(inputGraph, "inputGraph");
        this.inputComponentGenerations = copyGenerations(
                this.inputGraph, inputComponentGenerations);
        this.latestLocalBodies = copyBodies(latestLocalBodies);
        this.resultingGraph = ManagedDocumentGraph.fromBindings(
                this.latestLocalBodies.keySet(),
                new ArrayList<ManagedOccurrenceBinding>(Objects.requireNonNull(
                        resultingBindings, "resultingBindings")));
    }

    /**
     * Returns the authoritative predecessor graph.
     *
     * @return immutable input graph
     */
    public ManagedDocumentGraph inputGraph() {
        return inputGraph;
    }

    /**
     * Returns every predecessor component generation by document lineage.
     *
     * @return immutable canonical generation map
     */
    public Map<DocumentId, Long> inputComponentGenerations() {
        return inputComponentGenerations;
    }

    /**
     * Returns the graph derived from the complete resulting binding rows.
     *
     * @return immutable resulting graph before identity rebasing
     */
    public ManagedDocumentGraph resultingGraph() {
        return resultingGraph;
    }

    /**
     * Returns defensive copies of all independently managed local bodies.
     *
     * @return immutable canonical body map containing mutable copies
     */
    public Map<DocumentId, Node> latestLocalBodies() {
        return copyBodies(latestLocalBodies);
    }

    Node localBody(DocumentId documentId) {
        Node body = latestLocalBodies.get(Objects.requireNonNull(
                documentId, "documentId"));
        if (body == null) {
            throw new IllegalArgumentException(
                    "No local body for managed document " + documentId);
        }
        return body.clone();
    }

    private static Map<DocumentId, Long> copyGenerations(
            ManagedDocumentGraph graph,
            Map<DocumentId, Long> values) {
        Map<DocumentId, Long> source = Objects.requireNonNull(
                values, "inputComponentGenerations");
        if (!source.keySet().equals(new HashSet<DocumentId>(
                graph.documentIds()))) {
            throw new IllegalArgumentException(
                    "Input generations must exactly cover the input graph");
        }
        TreeMap<DocumentId, Long> ordered = new TreeMap<DocumentId, Long>();
        for (Map.Entry<DocumentId, Long> entry : source.entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "input generation DocumentId");
            Long generation = Objects.requireNonNull(
                    entry.getValue(), "input component generation");
            ordered.put(documentId, Long.valueOf(
                    ClosureValueSupport.requireSafeInteger(
                            generation.longValue(),
                            "input component generation")));
        }
        return Collections.unmodifiableMap(
                new LinkedHashMap<DocumentId, Long>(ordered));
    }

    private static Map<DocumentId, Node> copyBodies(
            Map<DocumentId, Node> values) {
        TreeMap<DocumentId, Node> ordered = new TreeMap<DocumentId, Node>();
        for (Map.Entry<DocumentId, Node> entry
                : Objects.requireNonNull(values, "latestLocalBodies")
                        .entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "local body DocumentId");
            Node previous = ordered.put(documentId, Objects.requireNonNull(
                    entry.getValue(), "local document body").clone());
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate local body DocumentId");
            }
        }
        return Collections.unmodifiableMap(
                new LinkedHashMap<DocumentId, Node>(ordered));
    }
}
