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
    private final ProcessorStatus status;
    private final ProcessorErrorCategory errorCategory;
    private final ResolvedSnapshot snapshot;

    private DocumentProcessingResult(Node document,
            List<Node> triggeredEvents,
            long totalGas,
            boolean capabilityFailure,
            String failureReason,
            ProcessorStatus status,
            ProcessorErrorCategory errorCategory,
            ResolvedSnapshot snapshot) {
        this.document = document;
        this.triggeredEvents = Collections.unmodifiableList(new ArrayList<>(triggeredEvents));
        this.totalGas = totalGas;
        this.capabilityFailure = capabilityFailure;
        this.failureReason = failureReason;
        this.status = status != null
                ? status
                : (capabilityFailure ? ProcessorStatus.CAPABILITY_FAILURE : ProcessorStatus.SUCCESS);
        this.errorCategory = errorCategory;
        this.snapshot = snapshot;
    }

    public static DocumentProcessingResult of(Node document, List<Node> triggeredEvents, long totalGas) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(triggeredEvents, "triggeredEvents");
        return new DocumentProcessingResult(document,
                new ArrayList<>(triggeredEvents),
                totalGas,
                false,
                null,
                ProcessorStatus.SUCCESS,
                null,
                null);
    }

    public static DocumentProcessingResult of(ResolvedSnapshot snapshot, List<Node> triggeredEvents, long totalGas) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(triggeredEvents, "triggeredEvents");
        return new DocumentProcessingResult(snapshot.canonicalRoot(),
                new ArrayList<>(triggeredEvents),
                totalGas,
                false,
                null,
                ProcessorStatus.SUCCESS,
                null,
                snapshot);
    }

    public static DocumentProcessingResult of(ResolvedSnapshot snapshot,
                                              List<Node> triggeredEvents,
                                              long totalGas,
                                              ProcessorStatus status,
                                              ProcessorErrorCategory errorCategory,
                                              String failureReason) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(triggeredEvents, "triggeredEvents");
        return new DocumentProcessingResult(snapshot.canonicalRoot(),
                new ArrayList<>(triggeredEvents),
                totalGas,
                status == ProcessorStatus.CAPABILITY_FAILURE
                        || status == ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                failureReason,
                status,
                errorCategory,
                snapshot);
    }

    public static DocumentProcessingResult of(Node document,
                                              List<Node> triggeredEvents,
                                              long totalGas,
                                              ProcessorStatus status,
                                              ProcessorErrorCategory errorCategory,
                                              String failureReason) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(triggeredEvents, "triggeredEvents");
        return new DocumentProcessingResult(document,
                new ArrayList<>(triggeredEvents),
                totalGas,
                status == ProcessorStatus.CAPABILITY_FAILURE
                        || status == ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                failureReason,
                status,
                errorCategory,
                null);
    }

    public static DocumentProcessingResult capabilityFailure(Node document, String reason) {
        return capabilityFailure(document, reason, ProcessorErrorCategory.UnsupportedContract);
    }

    public static DocumentProcessingResult capabilityFailure(Node document,
                                                            String reason,
                                                            ProcessorErrorCategory errorCategory) {
        Objects.requireNonNull(document, "document");
        return new DocumentProcessingResult(document,
                Collections.emptyList(),
                0L,
                true,
                reason,
                ProcessorStatus.CAPABILITY_FAILURE,
                errorCategory,
                null);
    }

    public static DocumentProcessingResult invalidProcessingDocument(Node document, String reason) {
        Objects.requireNonNull(document, "document");
        return new DocumentProcessingResult(document,
                Collections.emptyList(),
                0L,
                true,
                reason,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorErrorCategory.InvalidProcessingDocument,
                null);
    }

    public static DocumentProcessingResult runtimeFatal(Node document,
                                                       String reason,
                                                       ProcessorErrorCategory errorCategory) {
        Objects.requireNonNull(document, "document");
        return new DocumentProcessingResult(document,
                Collections.emptyList(),
                0L,
                false,
                reason,
                ProcessorStatus.RUNTIME_FATAL,
                errorCategory,
                null);
    }

    public DocumentProcessingResult withSnapshot(ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new DocumentProcessingResult(snapshot.canonicalRoot(),
                triggeredEvents,
                totalGas,
                capabilityFailure,
                failureReason,
                status,
                errorCategory,
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

    public ProcessorStatus status() {
        return status;
    }

    public ProcessorErrorCategory errorCategory() {
        return errorCategory;
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
