package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable host value for one completed Contracts 1.0 PROCESS invocation.
 *
 * <p>The document and emitted events are defensive snapshots. A non-success
 * status carries a stable diagnostic category; gas is the exact admitted
 * total even when the invocation stopped before applying effects.</p>
 */
public final class DocumentProcessingResult {

    private final Node document;
    private final List<Node> events;
    private final long totalGas;
    private final ProcessorStatus status;
    private final ProcessorDiagnostic diagnostic;

    private DocumentProcessingResult(Node document,
                                     List<Node> events,
                                     long totalGas,
                                     ProcessorStatus status,
                                     ProcessorDiagnostic diagnostic) {
        this.document = Objects.requireNonNull(document, "document").clone();
        Objects.requireNonNull(events, "events");
        if (totalGas < 0L) {
            throw new IllegalArgumentException("totalGas must be non-negative");
        }
        this.events = immutableNodes(events);
        this.totalGas = totalGas;
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostic = diagnostic;
        if (!status.commits() && !this.events.isEmpty()) {
            throw new IllegalArgumentException(
                    "Noncommitting PROCESS status must return an empty Root event sequence");
        }
    }

    /**
     * Creates a successful, committing PROCESS result.
     *
     * @param document committed document; stored defensively
     * @param events ordered Root emissions; stored defensively
     * @param totalGas exact admitted gas
     * @return an immutable successful result
     */
    public static DocumentProcessingResult of(Node document,
                                              List<Node> events,
                                              long totalGas) {
        return completed(document, events, totalGas, ProcessorStatus.SUCCESS,
                null);
    }

    static DocumentProcessingResult completed(Node document,
                                              List<Node> events,
                                              long totalGas,
                                              ProcessorStatus status,
                                              ProcessorDiagnostic diagnostic) {
        return new DocumentProcessingResult(document,
                events,
                totalGas,
                status,
                diagnostic);
    }

    /**
     * Creates a noncommitting capability failure with the default category.
     *
     * @param inputDocument unchanged invocation input
     * @param reason stable diagnostic explanation
     * @return an immutable noncommitting result with zero admitted gas
     */
    public static DocumentProcessingResult capabilityFailure(Node inputDocument,
                                                             String reason) {
        return capabilityFailure(inputDocument, reason,
                ProcessorErrorCategory.UnsupportedRuntimeType);
    }

    /**
     * Creates a noncommitting capability failure with an explicit category.
     *
     * @param inputDocument unchanged invocation input
     * @param reason stable diagnostic explanation
     * @param category error category, or {@code null} for the capability default
     * @return an immutable noncommitting result with zero admitted gas
     */
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

    /**
     * Creates a stable invalid-document result.
     *
     * @param inputDocument unchanged invocation input
     * @param reason validation failure explanation
     * @return an immutable noncommitting result
     */
    public static DocumentProcessingResult invalidProcessingDocument(Node inputDocument,
                                                                     String reason) {
        return nonCommitting(inputDocument,
                0L,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorDiagnostic.of(ProcessorErrorCategory.InvalidProcessingDocument, reason));
    }

    /**
     * Creates a stable invalid-event result.
     *
     * @param inputDocument unchanged invocation input
     * @param reason validation failure explanation
     * @return an immutable noncommitting result
     */
    public static DocumentProcessingResult invalidProcessingEvent(Node inputDocument,
                                                                  String reason) {
        return nonCommitting(inputDocument,
                0L,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorDiagnostic.of(ProcessorErrorCategory.InvalidProcessingEvent, reason));
    }

    /**
     * Creates a noncommitting runtime-fatal result.
     *
     * @param inputDocument unchanged invocation input
     * @param reason stable failure explanation
     * @param category error category, or {@code null} for the runtime default
     * @return an immutable runtime-fatal result
     */
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

    /**
     * Creates a noncommitting result while preserving admitted gas.
     *
     * @param inputDocument unchanged invocation input
     * @param admittedGas exact gas admitted before failure
     * @param status noncommitting terminal status
     * @param diagnostic stable diagnostic, or {@code null}
     * @return an immutable result with no emitted events
     * @throws IllegalArgumentException when {@code status} commits
     */
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
                diagnostic);
    }

    /**
     * Returns the resulting document without exposing the stored snapshot.
     *
     * @return a defensive document copy
     */
    public Node document() {
        return document.clone();
    }

    /**
     * Ordered out-of-band events emitted by Root only.
     *
     * @return an immutable list of defensive event copies
     */
    public List<Node> events() {
        return immutableNodes(events);
    }

    /**
     * Returns the exact admitted gas.
     *
     * @return non-negative gas total
     */
    public long totalGas() {
        return totalGas;
    }

    /**
     * Returns the terminal processing status.
     *
     * @return non-null status
     */
    public ProcessorStatus status() {
        return status;
    }

    /**
     * Reports whether this result may replace the caller's document.
     *
     * @return {@code true} only for a committing status
     */
    public boolean commits() {
        return status.commits();
    }

    /**
     * Returns the stable failure diagnostic, when present.
     *
     * @return diagnostic or {@code null} for a successful result
     */
    public ProcessorDiagnostic diagnostic() {
        return diagnostic;
    }

    private static List<Node> immutableNodes(List<Node> nodes) {
        List<Node> copy = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            copy.add(Objects.requireNonNull(node, "event").clone());
        }
        return Collections.unmodifiableList(copy);
    }
}
