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

    public enum Kind {
        COMPLETE("complete"),
        NEEDS_RESOURCES("needs-resources");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

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

    public static ProcessAttemptResult complete(DocumentProcessingResult result) {
        return new ProcessAttemptResult(Kind.COMPLETE,
                Objects.requireNonNull(result, "result"),
                Collections.emptyList());
    }

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

    public Kind kind() {
        return kind;
    }

    public boolean isComplete() {
        return kind == Kind.COMPLETE;
    }

    public DocumentProcessingResult processResult() {
        return processResult;
    }

    public List<String> requiredExactBlueIds() {
        return requiredExactBlueIds;
    }

    /**
     * Suspension deliberately has no portable-gas value.
     */
    public Long portableGas() {
        return processResult != null ? processResult.totalGas() : null;
    }
}
