package blue.language.processor;

import blue.language.model.Node;

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

    private DocumentProcessingResult(Node document,
                                     List<Node> triggeredEvents,
                                     long totalGas,
                                     boolean capabilityFailure,
                                     String failureReason) {
        this.document = document;
        this.triggeredEvents = Collections.unmodifiableList(triggeredEvents);
        this.totalGas = totalGas;
        this.capabilityFailure = capabilityFailure;
        this.failureReason = failureReason;
    }

    public static DocumentProcessingResult of(Node document, List<Node> triggeredEvents, long totalGas) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(triggeredEvents, "triggeredEvents");
        return new DocumentProcessingResult(document, List.copyOf(triggeredEvents), totalGas, false, null);
    }

    public static DocumentProcessingResult capabilityFailure(Node document, String reason) {
        Objects.requireNonNull(document, "document");
        return new DocumentProcessingResult(document, List.of(), 0L, true, reason);
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
}
