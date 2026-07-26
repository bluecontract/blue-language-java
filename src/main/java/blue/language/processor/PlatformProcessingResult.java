package blue.language.processor;

import java.util.Objects;

/**
 * Atomic host hand-off for a completed PROCESS invocation.
 *
 * <p>The semantic five-field result and its revision-bound platform companion
 * are deliberately separate values delivered together. A host must use both
 * in one transaction; neither this wrapper nor the companion is a public
 * semantic effect log.</p>
 */
public final class PlatformProcessingResult {

    private final DocumentProcessingResult processResult;
    private final PlatformCommitCompanion commitCompanion;

    PlatformProcessingResult(
            DocumentProcessingResult processResult,
            PlatformCommitCompanion commitCompanion) {
        this.processResult = Objects.requireNonNull(
                processResult, "processResult");
        this.commitCompanion = Objects.requireNonNull(
                commitCompanion, "commitCompanion");
    }

    public DocumentProcessingResult processResult() {
        return processResult;
    }

    public PlatformCommitCompanion commitCompanion() {
        return commitCompanion;
    }
}
