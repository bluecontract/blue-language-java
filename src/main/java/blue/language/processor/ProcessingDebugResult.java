package blue.language.processor;

import blue.language.merge.ResolvedSnapshot;

import java.util.Objects;

/**
 * Explicit conformance/debug wrapper around the five-field semantic result.
 *
 * <p>This wrapper is not a {@code ProcessResult} and is never committed or
 * serialized as part of Root/outbox semantics.</p>
 */
public final class ProcessingDebugResult {

    private final DocumentProcessingResult processResult;
    private final ProcessingConformanceTrace trace;
    private final PlatformCommitCompanion platformCommitCompanion;
    private final ResolvedSnapshot resultingSnapshot;

    /**
     * Creates a debug result without platform or snapshot metadata.
     *
     * @param processResult semantic PROCESS result
     * @param trace immutable conformance trace
     */
    public ProcessingDebugResult(DocumentProcessingResult processResult,
                                 ProcessingConformanceTrace trace) {
        this(processResult, trace, null, null);
    }

    ProcessingDebugResult(
            DocumentProcessingResult processResult,
            ProcessingConformanceTrace trace,
            PlatformCommitCompanion platformCommitCompanion) {
        this(processResult, trace, platformCommitCompanion, null);
    }

    ProcessingDebugResult(
            DocumentProcessingResult processResult,
            ProcessingConformanceTrace trace,
            PlatformCommitCompanion platformCommitCompanion,
            ResolvedSnapshot resultingSnapshot) {
        this.processResult = Objects.requireNonNull(processResult, "processResult");
        this.trace = Objects.requireNonNull(trace, "trace");
        this.platformCommitCompanion = platformCommitCompanion;
        this.resultingSnapshot = resultingSnapshot;
    }

    /**
     * Returns the semantic result produced by the PROCESS operation.
     *
     * @return immutable semantic PROCESS result
     */
    public DocumentProcessingResult processResult() {
        return processResult;
    }

    /**
     * Returns the non-semantic trace captured for conformance and debugging.
     *
     * @return immutable non-semantic conformance trace
     */
    public ProcessingConformanceTrace trace() {
        return trace;
    }

    /**
     * Returns the non-semantic platform hand-off when execution was bound to
     * verified revision evidence. It is absent for initialization and for
     * attempts rejected before evidence admission.
     *
     * @return platform companion, or {@code null}
     */
    public PlatformCommitCompanion platformCommitCompanion() {
        return platformCommitCompanion;
    }

    /**
     * Returns the out-of-band immutable processing snapshot, when execution
     * used the snapshot-native runtime. It is not a ProcessResult field.
     *
     * @return resulting snapshot, or {@code null}
     */
    public ResolvedSnapshot resultingSnapshot() {
        return resultingSnapshot;
    }
}
