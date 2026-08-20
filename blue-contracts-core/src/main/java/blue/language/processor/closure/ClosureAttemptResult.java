package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Complete closure result or exact resource suspension; never both. */
public final class ClosureAttemptResult {

    /** Closed completion-or-suspension discriminator. */
    public enum Kind {
        /** Attempt produced one completed result. */
        COMPLETE("complete"),
        /** Attempt suspended for exact named resources. */
        NEEDS_RESOURCES("needs-resources");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the documented value.
         *
         * @return stable attempt-kind wire value
         */
        public String wireValue() {
            return wireValue;
        }
    }

    private final Kind kind;
    private final ClosureProcessResult processResult;
    private final List<String> requiredExactBlueIds;

    private ClosureAttemptResult(
            Kind kind,
            ClosureProcessResult processResult,
            List<String> requiredExactBlueIds) {
        this.kind = kind;
        this.processResult = processResult;
        this.requiredExactBlueIds = Collections.unmodifiableList(
                new ArrayList<String>(requiredExactBlueIds));
    }

    /**
     * Wraps one completed closure result.
     *
     * @param result completed result
     * @return complete attempt
     */
    public static ClosureAttemptResult complete(ClosureProcessResult result) {
        return new ClosureAttemptResult(
                Kind.COMPLETE,
                Objects.requireNonNull(result, "result"),
                Collections.<String>emptyList());
    }

    /**
     * Creates a sorted duplicate-free exact-resource suspension.
     *
     * @param requiredExactBlueIds exact BlueIds required to retry
     * @return suspended attempt
     */
    public static ClosureAttemptResult needsResources(
            List<String> requiredExactBlueIds) {
        Objects.requireNonNull(requiredExactBlueIds, "requiredExactBlueIds");
        TreeSet<String> sorted = new TreeSet<String>(
                ClosureValueSupport::comparePortableText);
        for (String blueId : requiredExactBlueIds) {
            sorted.add(ClosureValueSupport.requireBlueId(
                    blueId, "required exact BlueId"));
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException(
                    "NeedsResources must name at least one exact BlueId");
        }
        return new ClosureAttemptResult(
                Kind.NEEDS_RESOURCES,
                null,
                new ArrayList<String>(sorted));
    }

    /**
     * Returns the documented value.
     *
     * @return completion-or-suspension discriminator
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the documented value.
     *
     * @return whether this attempt contains a completed result
     */
    public boolean isComplete() {
        return kind == Kind.COMPLETE;
    }

    /**
     * Returns the documented value.
     *
     * @return completed result, or {@code null} for suspension
     */
    public ClosureProcessResult processResult() {
        return processResult;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical exact-resource demand list
     */
    public List<String> requiredExactBlueIds() {
        return requiredExactBlueIds;
    }

    /**
     * Returns gas only for a completed result.
     *
     * @return exact total gas, or {@code null} for suspension
     */
    public Long totalGas() {
        return processResult == null
                ? null
                : Long.valueOf(processResult.totalGas());
    }
}
