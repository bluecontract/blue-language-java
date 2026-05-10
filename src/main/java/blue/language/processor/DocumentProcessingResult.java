package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable value object representing the outcome of a single PROCESS run.
 */
public final class DocumentProcessingResult {

    private final Node document;
    private final List<Node> triggeredEvents;
    private final long totalGas;
    private final boolean capabilityFailure;
    private final String failureReason;
    private final ResolvedSnapshot snapshot;

    private DocumentProcessingResult(Node document,
            List<Node> triggeredEvents,
            long totalGas,
            boolean capabilityFailure,
            String failureReason,
            ResolvedSnapshot snapshot) {
        this.document = document;
        this.triggeredEvents = Collections.unmodifiableList(new ArrayList<>(triggeredEvents));
        this.totalGas = totalGas;
        this.capabilityFailure = capabilityFailure;
        this.failureReason = failureReason;
        this.snapshot = snapshot;
    }

    public static DocumentProcessingResult of(Node document, List<Node> triggeredEvents, long totalGas) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(triggeredEvents, "triggeredEvents");
        return new DocumentProcessingResult(document, new ArrayList<>(triggeredEvents), totalGas, false, null, null);
    }

    public static DocumentProcessingResult of(ResolvedSnapshot snapshot, List<Node> triggeredEvents, long totalGas) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(triggeredEvents, "triggeredEvents");
        return new DocumentProcessingResult(snapshot.canonicalRoot(),
                new ArrayList<>(triggeredEvents),
                totalGas,
                false,
                null,
                snapshot);
    }

    public static DocumentProcessingResult capabilityFailure(Node document, String reason) {
        Objects.requireNonNull(document, "document");
        return new DocumentProcessingResult(document, Collections.emptyList(), 0L, true, reason, null);
    }

    public DocumentProcessingResult withSnapshot(ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new DocumentProcessingResult(snapshot.canonicalRoot(),
                triggeredEvents,
                totalGas,
                capabilityFailure,
                failureReason,
                snapshot);
    }

    public Node document() {
        return document;
    }

    public List<Node> triggeredEvents() {
        return triggeredEvents;
    }

    public long totalGas() {
        return totalGas;
    }

    public boolean capabilityFailure() {
        return capabilityFailure;
    }

    public String failureReason() {
        return failureReason;
    }

    public ResolvedSnapshot snapshot() {
        return snapshot;
    }

    public String blueId() {
        return snapshot != null ? snapshot.blueId() : null;
    }

    public Node canonicalDocument() {
        return snapshot != null ? snapshot.canonicalRoot() : null;
    }

    public Node resolvedDocument() {
        return snapshot != null ? snapshot.resolvedRoot() : null;
    }
}
