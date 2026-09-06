package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Processor-owned execution evidence that is intentionally separate from the
 * atomic protocol result.
 *
 * <p>The protocol result contains committed state and receipts.  This value
 * records how the implementation reached that state: accepted work, one
 * isolated document step per work occurrence, and whole-component tentative
 * finalizations.  It contains no fixture stage labels or expected-output
 * projection. Timing values are invocation-local diagnostics, saturate at
 * {@link Long#MAX_VALUE}, and never participate in protocol results,
 * identities, gas, or result selection.</p>
 */
public final class ClosureImplementationEvidence {

    private final String invocationIdentity;
    private final List<ClosureWorkOccurrence> workTrace;
    private final List<DocumentStepEvidence> documentStepTrace;
    private final List<SkippedWorkEvidence> skippedWorkTrace;
    private final List<TentativeFinalization> tentativeFinalizations;
    private final long managedDocumentStepInclusiveNanos;
    private final long managedDocumentStepNestedFinalizationProofNanos;
    private final long componentFinalizationProofNanos;
    private final long successfulResultAssemblyNanos;
    private final String nonConformanceCode;

    ClosureImplementationEvidence(
            String invocationIdentity,
            List<ClosureWorkOccurrence> workTrace,
            List<DocumentStepEvidence> documentStepTrace,
            List<TentativeFinalization> tentativeFinalizations,
            long managedDocumentStepInclusiveNanos,
            long managedDocumentStepNestedFinalizationProofNanos,
            long componentFinalizationProofNanos,
            long successfulResultAssemblyNanos,
            String nonConformanceCode) {
        this(invocationIdentity, workTrace, documentStepTrace, tentativeFinalizations,
                managedDocumentStepInclusiveNanos, managedDocumentStepNestedFinalizationProofNanos,
                componentFinalizationProofNanos, successfulResultAssemblyNanos, nonConformanceCode,
                Collections.emptyList());
    }

    ClosureImplementationEvidence(String invocationIdentity, List<ClosureWorkOccurrence> workTrace,
            List<DocumentStepEvidence> documentStepTrace, List<TentativeFinalization> tentativeFinalizations,
            long managedDocumentStepInclusiveNanos, long managedDocumentStepNestedFinalizationProofNanos,
            long componentFinalizationProofNanos, long successfulResultAssemblyNanos, String nonConformanceCode,
            List<SkippedWorkEvidence> skippedWorkTrace) {
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
        this.workTrace = immutable(workTrace, "workTrace");
        this.documentStepTrace = immutable(
                documentStepTrace, "documentStepTrace");
        this.skippedWorkTrace = immutable(skippedWorkTrace, "skippedWorkTrace");
        this.tentativeFinalizations = immutable(
                tentativeFinalizations, "tentativeFinalizations");
        this.managedDocumentStepInclusiveNanos = requireNonNegativeNanos(
                managedDocumentStepInclusiveNanos,
                "managedDocumentStepInclusiveNanos");
        this.managedDocumentStepNestedFinalizationProofNanos =
                requireNonNegativeNanos(
                        managedDocumentStepNestedFinalizationProofNanos,
                        "managedDocumentStepNestedFinalizationProofNanos");
        this.componentFinalizationProofNanos = requireNonNegativeNanos(
                componentFinalizationProofNanos,
                "componentFinalizationProofNanos");
        this.successfulResultAssemblyNanos = requireNonNegativeNanos(
                successfulResultAssemblyNanos,
                "successfulResultAssemblyNanos");
        if (this.managedDocumentStepNestedFinalizationProofNanos
                > this.managedDocumentStepInclusiveNanos) {
            throw new IllegalArgumentException(
                    "Nested finalization time cannot exceed inclusive step time");
        }
        if (this.managedDocumentStepNestedFinalizationProofNanos
                > this.componentFinalizationProofNanos) {
            throw new IllegalArgumentException(
                    "Nested finalization time cannot exceed total finalization time");
        }
        this.nonConformanceCode = nonConformanceCode == null
                ? null
                : ClosureValueSupport.requireNonEmptyText(
                        nonConformanceCode, "nonConformanceCode");
        validateOneStepPerWork();
    }

    /**
     * Returns the verified invocation identity.
     *
     * @return invocation identity
     */
    public String invocationIdentity() {
        return invocationIdentity;
    }

    /**
     * Returns accepted work in invocation-global ordinal order.
     *
     * @return immutable accepted-work trace
     */
    public List<ClosureWorkOccurrence> workTrace() {
        return workTrace;
    }

    /**
     * Returns the isolated document-step projection.
     *
     * @return immutable document-step trace
     */
    public List<DocumentStepEvidence> documentStepTrace() {
        return documentStepTrace;
    }

    /**
     * Returns exact whole-component finalization receipts.
     *
     * @return immutable tentative-finalization trace
     */
    public List<TentativeFinalization> tentativeFinalizations() {
        return tentativeFinalizations;
    }

    /**
     * Returns cumulative wall-clock time spent inside managed document-step
     * execution, including any component finalization invoked synchronously
     * by a step continuation.
     *
     * @return inclusive managed document-step time in nanoseconds
     */
    public long managedDocumentStepInclusiveNanos() {
        return managedDocumentStepInclusiveNanos;
    }

    /**
     * Returns cumulative managed document-step time after subtracting
     * component finalization/proof spans nested inside those steps.
     *
     * @return non-overlapping managed document-step time in nanoseconds
     */
    public long managedDocumentStepExclusiveNanos() {
        return managedDocumentStepInclusiveNanos
                - managedDocumentStepNestedFinalizationProofNanos;
    }

    /**
     * Returns cumulative wall-clock time spent in exact component identity
     * finalization and cyclic-proof construction.
     *
     * @return component finalization/proof time in nanoseconds
     */
    public long componentFinalizationProofNanos() {
        return componentFinalizationProofNanos;
    }

    /**
     * Returns cumulative wall-clock time spent assembling successful protocol
     * results. Failed or suspended attempts that never assemble a successful
     * result report zero.
     *
     * @return successful result-assembly time in nanoseconds
     */
    public long successfulResultAssemblyNanos() {
        return successfulResultAssemblyNanos;
    }

    /**
     * Returns a stable fail-closed capability code when the execution could
     * not yet be assembled into a conforming protocol result.
     *
     * @return capability code, or {@code null} after conforming completion
     */
    public String nonConformanceCode() {
        return nonConformanceCode;
    }

    /**
     * Reports whether every accepted work occurrence has one isolated step or
     * an exact recorded termination cutoff, and no capability gap remains.
     *
     * @return whether the implementation evidence is complete
     */
    public boolean complete() {
        return nonConformanceCode == null
                && workTrace.size() == documentStepTrace.size() + skippedWorkTrace.size();
    }

    /** Invocation-local diagnostic cutoff evidence; never source replay or publication authority. */
    public List<SkippedWorkEvidence> skippedWorkTrace() { return skippedWorkTrace; }

    public static final class SkippedWorkEvidence {
        private final long workOrdinal, requestWorkOrdinal;
        private final String requestWorkIdentity;
        SkippedWorkEvidence(ClosureWorkOccurrence work, ClosureWorkOccurrence request) {
            workOrdinal = work.ordinal(); requestWorkOrdinal = request.ordinal();
            requestWorkIdentity = request.workIdentity();
        }
        public long workOrdinal() { return workOrdinal; }
        public long requestWorkOrdinal() { return requestWorkOrdinal; }
        public String requestWorkIdentity() { return requestWorkIdentity; }
    }

    private void validateOneStepPerWork() {
        if (documentStepTrace.size() + skippedWorkTrace.size() > workTrace.size()) {
            throw new IllegalArgumentException(
                    "Document-step trace cannot exceed accepted work");
        }
        for (int index = 0; index < workTrace.size(); index++) {
            ClosureWorkOccurrence work = workTrace.get(index);
            if (work.ordinal() != index) {
                throw new IllegalArgumentException(
                        "Accepted work ordinals must be contiguous");
            }
        }
        java.util.Set<Long> covered = new java.util.HashSet<>();
        for (int index = 0; index < documentStepTrace.size(); index++) {
            DocumentStepEvidence step = documentStepTrace.get(index);
            if (!covered.add(step.workOrdinal())) throw new IllegalArgumentException("Accepted work has duplicate execution evidence");
            if (step.stepOrdinal() != index) {
                throw new IllegalArgumentException(
                        "Document-step ordinals must be contiguous");
            }
            ClosureWorkOccurrence work = workTrace.get(
                    Math.toIntExact(step.workOrdinal()));
            if (!work.targetDocumentId().equals(step.targetDocumentId())
                    || !step.targetDocumentId().equals(
                            step.executionRootDocumentId())
                    || !"/".equals(step.scopePath())
                    || !"ISOLATED_DOCUMENT".equals(
                            step.executionMode())
                    || !step.ambientContainingDocumentIds().isEmpty()) {
                throw new IllegalArgumentException(
                        "Document-step evidence disagrees with accepted work");
            }
        }
        java.util.Set<Long> executed = new java.util.HashSet<>(covered);
        for (SkippedWorkEvidence skipped : skippedWorkTrace) {
            ClosureWorkOccurrence work = workTrace.get(Math.toIntExact(skipped.workOrdinal()));
            ClosureWorkOccurrence request = workTrace.get(Math.toIntExact(skipped.requestWorkOrdinal()));
            if (!covered.add(skipped.workOrdinal()) || !executed.contains(skipped.requestWorkOrdinal())
                    || !request.targetDocumentId().equals(work.targetDocumentId())
                    || !request.workIdentity().equals(skipped.requestWorkIdentity())
                    || work.kind() == WorkKind.LIFECYCLE || work.kind() == WorkKind.INITIALIZATION)
                throw new IllegalArgumentException("Skipped work disagrees with its accepted request and disjoint execution evidence");
        }
    }

    private static <T> List<T> immutable(
            List<T> values,
            String field) {
        ArrayList<T> copy = new ArrayList<T>(
                Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return Collections.unmodifiableList(copy);
    }

    private static long requireNonNegativeNanos(
            long value,
            String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    field + " must be non-negative");
        }
        return value;
    }
}
