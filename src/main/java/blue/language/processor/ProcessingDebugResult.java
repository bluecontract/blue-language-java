package blue.language.processor;

import blue.language.snapshot.ResolvedSnapshot;

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

    public DocumentProcessingResult processResult() {
        return processResult;
    }

    public ProcessingConformanceTrace trace() {
        return trace;
    }

    /**
     * Returns the non-semantic platform hand-off when execution was bound to
     * verified revision evidence. It is absent for initialization and for
     * attempts rejected before evidence admission.
     */
    public PlatformCommitCompanion platformCommitCompanion() {
        return platformCommitCompanion;
    }

    /**
     * Returns the out-of-band immutable processing snapshot, when execution
     * used the snapshot-native runtime. It is not a ProcessResult field.
     */
    public ResolvedSnapshot resultingSnapshot() {
        return resultingSnapshot;
    }
}
