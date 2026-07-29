package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Result of PROCESS_ATTEMPT: either one completed ProcessResult or an explicit
 * resource suspension.
 */
public final class ProcessAttemptResult {

    /** Distinguishes completed processing from resource suspension. */
    public enum Kind {
        /** Attempt produced a completed semantic result. */
        COMPLETE("complete"),
        /** Attempt suspended until exact evidence becomes available. */
        NEEDS_RESOURCES("needs-resources");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the stable value used to serialize this attempt kind.
         *
         * @return stable serialized attempt kind
         */
        public String wireValue() {
            return wireValue;
        }
    }

    private final Kind kind;
    private final DocumentProcessingResult processResult;
    private final List<String> requiredExactBlueIds;

    private ProcessAttemptResult(Kind kind,
                                 DocumentProcessingResult processResult,
                                 List<String> requiredExactBlueIds) {
        this.kind = kind;
        this.processResult = processResult;
        this.requiredExactBlueIds =
                Collections.unmodifiableList(new ArrayList<>(requiredExactBlueIds));
    }

    /**
     * Creates a completed attempt.
     *
     * @param result completed semantic result
     * @return completed attempt wrapper
     */
    public static ProcessAttemptResult complete(DocumentProcessingResult result) {
        return new ProcessAttemptResult(Kind.COMPLETE,
                Objects.requireNonNull(result, "result"),
                Collections.emptyList());
    }

    /**
     * Creates a suspended attempt with a sorted, duplicate-free demand list.
     *
     * @param exactBlueIds required exact identities
     * @return resource suspension
     * @throws IllegalArgumentException when no valid identity is supplied
     */
    public static ProcessAttemptResult needsResources(List<String> exactBlueIds) {
        Objects.requireNonNull(exactBlueIds, "exactBlueIds");
        TreeSet<String> sorted = new TreeSet<>();
        for (String blueId : exactBlueIds) {
            if (blueId == null || blueId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Required exact BlueIds must be non-empty");
            }
            sorted.add(blueId);
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException(
                    "NeedsResources must contain at least one exact BlueId");
        }
        return new ProcessAttemptResult(Kind.NEEDS_RESOURCES,
                null,
                new ArrayList<>(sorted));
    }

    /**
     * Returns whether this wrapper represents completion or suspension.
     *
     * @return immutable attempt kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Reports whether processing completed instead of requesting resources.
     *
     * @return whether this attempt contains a completed result
     */
    public boolean isComplete() {
        return kind == Kind.COMPLETE;
    }

    /**
     * Returns the semantic result produced by a completed attempt.
     *
     * @return completed result, or {@code null} for a suspension
     */
    public DocumentProcessingResult processResult() {
        return processResult;
    }

    /**
     * Returns the exact identities required to resume a suspended attempt.
     *
     * @return immutable sorted exact-resource demands
     */
    public List<String> requiredExactBlueIds() {
        return requiredExactBlueIds;
    }

    /**
     * Suspension deliberately has no portable-gas value.
     *
     * @return completed gas total, or {@code null} for a suspension
     */
    public Long portableGas() {
        return processResult != null ? processResult.totalGas() : null;
    }
}
