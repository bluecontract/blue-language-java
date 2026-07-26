package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.ResolvedSnapshot;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable host value for one completed Contracts 1.0 PROCESS invocation.
 */
public final class DocumentProcessingResult {

    private final Node document;
    private final List<Node> events;
    private final long totalGas;
    private final ProcessorStatus status;
    private final ProcessorDiagnostic diagnostic;
    /**
     * Legacy host companion.  It is deliberately excluded from the serialized
     * ProcessResult, whose public semantic projection has exactly five fields.
     */
    @JsonIgnore
    private final ResolvedSnapshot snapshot;

    private DocumentProcessingResult(Node document,
                                     List<Node> events,
                                     long totalGas,
                                     ProcessorStatus status,
                                     ProcessorDiagnostic diagnostic,
                                     ResolvedSnapshot snapshot) {
        this.document = Objects.requireNonNull(document, "document").clone();
        Objects.requireNonNull(events, "events");
        if (totalGas < 0L) {
            throw new IllegalArgumentException("totalGas must be non-negative");
        }
        this.events = immutableNodes(events);
        this.totalGas = totalGas;
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostic = diagnostic;
        this.snapshot = snapshot;
        if (!status.commits() && !this.events.isEmpty()) {
            throw new IllegalArgumentException(
                    "Noncommitting PROCESS status must return an empty Root event sequence");
        }
    }

    public static DocumentProcessingResult of(Node document,
                                              List<Node> events,
                                              long totalGas) {
        return completed(document, events, totalGas, ProcessorStatus.SUCCESS,
                null, null);
    }

    public static DocumentProcessingResult of(ResolvedSnapshot snapshot,
                                              List<Node> events,
                                              long totalGas) {
        Objects.requireNonNull(snapshot, "snapshot");
        return completed(snapshot.canonicalRoot(), events, totalGas,
                ProcessorStatus.SUCCESS, null, snapshot);
    }

    public static DocumentProcessingResult of(ResolvedSnapshot snapshot,
                                              List<Node> events,
                                              long totalGas,
                                              ProcessorStatus status,
                                              ProcessorErrorCategory errorCategory,
                                              String failureReason) {
        Objects.requireNonNull(snapshot, "snapshot");
        return completed(snapshot.canonicalRoot(), events, totalGas, status,
                diagnostic(errorCategory, failureReason), snapshot);
    }

    public static DocumentProcessingResult of(Node document,
                                              List<Node> events,
                                              long totalGas,
                                              ProcessorStatus status,
                                              ProcessorErrorCategory errorCategory,
                                              String failureReason) {
        return completed(document, events, totalGas, status,
                diagnostic(errorCategory, failureReason), null);
    }

    static DocumentProcessingResult ofSelected(Node document,
                                               ResolvedSnapshot snapshot,
                                               List<Node> events,
                                               long totalGas,
                                               ProcessorStatus status,
                                               ProcessorErrorCategory errorCategory,
                                               String failureReason) {
        return completed(document, events, totalGas, status,
                diagnostic(errorCategory, failureReason),
                Objects.requireNonNull(snapshot, "snapshot"));
    }

    static DocumentProcessingResult completed(Node document,
                                              List<Node> events,
                                              long totalGas,
                                              ProcessorStatus status,
                                              ProcessorDiagnostic diagnostic,
                                              ResolvedSnapshot snapshot) {
        return new DocumentProcessingResult(document,
                events,
                totalGas,
                status,
                diagnostic,
                snapshot);
    }

    public static DocumentProcessingResult capabilityFailure(Node inputDocument,
                                                             String reason) {
        return capabilityFailure(inputDocument, reason,
                ProcessorErrorCategory.UnsupportedRuntimeType);
    }

    public static DocumentProcessingResult capabilityFailure(Node inputDocument,
                                                             String reason,
                                                             ProcessorErrorCategory category) {
        return nonCommitting(inputDocument,
                0L,
                ProcessorStatus.CAPABILITY_FAILURE,
                ProcessorDiagnostic.of(category != null
                        ? category
                        : ProcessorErrorCategory.UnsupportedRuntimeType, reason));
    }

    public static DocumentProcessingResult invalidProcessingDocument(Node inputDocument,
                                                                     String reason) {
        return nonCommitting(inputDocument,
                0L,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorDiagnostic.of(ProcessorErrorCategory.InvalidProcessingDocument, reason));
    }

    public static DocumentProcessingResult invalidProcessingEvent(Node inputDocument,
                                                                  String reason) {
        return nonCommitting(inputDocument,
                0L,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorDiagnostic.of(ProcessorErrorCategory.InvalidProcessingEvent, reason));
    }

    public static DocumentProcessingResult runtimeFatal(Node inputDocument,
                                                        String reason,
                                                        ProcessorErrorCategory category) {
        return nonCommitting(inputDocument,
                0L,
                ProcessorStatus.RUNTIME_FATAL,
                ProcessorDiagnostic.of(category != null
                        ? category
                        : ProcessorErrorCategory.RuntimeExecutionFailure, reason));
    }

    public static DocumentProcessingResult nonCommitting(Node inputDocument,
                                                         long admittedGas,
                                                         ProcessorStatus status,
                                                         ProcessorDiagnostic diagnostic) {
        Objects.requireNonNull(status, "status");
        if (status.commits()) {
            throw new IllegalArgumentException("Use a committing result factory for success");
        }
        return completed(inputDocument,
                Collections.emptyList(),
                admittedGas,
                status,
                diagnostic,
                null);
    }

    public DocumentProcessingResult withSnapshot(ResolvedSnapshot snapshot) {
        return completed(document,
                events,
                totalGas,
                status,
                diagnostic,
                Objects.requireNonNull(snapshot, "snapshot"));
    }

    public Node document() {
        return document.clone();
    }

    /**
     * Ordered out-of-band events emitted by Root only.
     */
    public List<Node> events() {
        return immutableNodes(events);
    }

    /**
     * Compatibility alias for the preview API.
     */
    public List<Node> triggeredEvents() {
        return events();
    }

    public long totalGas() {
        return totalGas;
    }

    public ProcessorStatus status() {
        return status;
    }

    public boolean commits() {
        return status.commits();
    }

    public ProcessorDiagnostic diagnostic() {
        return diagnostic;
    }

    /**
     * Compatibility flag retained for existing hosts.
     */
    public boolean capabilityFailure() {
        return status == ProcessorStatus.CAPABILITY_FAILURE
                || status == ProcessorStatus.INVALID_PROCESSING_DOCUMENT;
    }

    public String failureReason() {
        return diagnostic != null ? diagnostic.message() : null;
    }

    public ProcessorErrorCategory errorCategory() {
        return diagnostic != null ? diagnostic.category() : null;
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

    private static ProcessorDiagnostic diagnostic(ProcessorErrorCategory category,
                                                  String reason) {
        return category != null
                ? ProcessorDiagnostic.of(category, reason)
                : null;
    }

    private static List<Node> immutableNodes(List<Node> nodes) {
        List<Node> copy = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            copy.add(Objects.requireNonNull(node, "event").clone());
        }
        return Collections.unmodifiableList(copy);
    }
}
