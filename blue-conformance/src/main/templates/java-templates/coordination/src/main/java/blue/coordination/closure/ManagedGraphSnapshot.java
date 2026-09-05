package blue.coordination.closure;

import blue.contracts.closure.CanonicalOrders;
import blue.contracts.closure.DocumentId;
import blue.contracts.closure.ManagedOccurrenceBinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact immutable graph generation used to construct one Contracts closure frame. */
public final class ManagedGraphSnapshot {
    private final long graphGeneration;
    private final Map<DocumentId, ManagedDocumentVertex> vertices;
    private final List<ManagedOccurrenceBinding> occurrences;
    private final ComponentIndex componentIndex;
    private final String identity;

    public ManagedGraphSnapshot(
            long graphGeneration,
            Map<DocumentId, ManagedDocumentVertex> vertices,
            List<ManagedOccurrenceBinding> occurrences,
            ComponentIndex componentIndex,
            String identity) {
        this.graphGeneration = CanonicalOrders.requireSafeInteger(
                graphGeneration, "graphGeneration");
        LinkedHashMap<DocumentId, ManagedDocumentVertex> vertexCopy =
                new LinkedHashMap<DocumentId, ManagedDocumentVertex>();
        for (Map.Entry<DocumentId, ManagedDocumentVertex> entry
                : Objects.requireNonNull(vertices, "vertices").entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "vertex documentId");
            ManagedDocumentVertex vertex = Objects.requireNonNull(
                    entry.getValue(), "vertex");
            if (!documentId.equals(vertex.documentId())) {
                throw new IllegalArgumentException("vertex key mismatch");
            }
            vertexCopy.put(documentId, vertex);
        }
        this.vertices = Collections.unmodifiableMap(vertexCopy);
        ArrayList<ManagedOccurrenceBinding> occurrenceCopy =
                new ArrayList<ManagedOccurrenceBinding>(Objects.requireNonNull(
                        occurrences, "occurrences"));
        for (ManagedOccurrenceBinding occurrence : occurrenceCopy) {
            Objects.requireNonNull(occurrence, "occurrence");
            if (!this.vertices.containsKey(occurrence.sourceDocumentId())
                    || !this.vertices.containsKey(occurrence.targetDocumentId())) {
                throw new IllegalArgumentException(
                        "occurrence endpoint outside graph vertices");
            }
        }
        this.occurrences = Collections.unmodifiableList(occurrenceCopy);
        this.componentIndex = Objects.requireNonNull(componentIndex, "componentIndex");
        if (!this.vertices.keySet().equals(this.componentIndex.documentIds())) {
            throw new IllegalArgumentException(
                    "vertices must equal complete component membership");
        }
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    public long graphGeneration() {
        return graphGeneration;
    }

    public Map<DocumentId, ManagedDocumentVertex> vertices() {
        return vertices;
    }

    public List<ManagedOccurrenceBinding> occurrences() {
        return occurrences;
    }

    public ComponentIndex componentIndex() {
        return componentIndex;
    }

    public String identity() {
        return identity;
    }
}
