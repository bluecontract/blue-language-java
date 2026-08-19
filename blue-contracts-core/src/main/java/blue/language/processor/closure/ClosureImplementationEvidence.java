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
 * projection.</p>
 */
public final class ClosureImplementationEvidence {

    private final String invocationIdentity;
    private final List<ClosureWorkOccurrence> workTrace;
    private final List<DocumentStepEvidence> documentStepTrace;
    private final List<TentativeFinalization> tentativeFinalizations;
    private final String nonConformanceCode;

    ClosureImplementationEvidence(
            String invocationIdentity,
            List<ClosureWorkOccurrence> workTrace,
            List<DocumentStepEvidence> documentStepTrace,
            List<TentativeFinalization> tentativeFinalizations,
            String nonConformanceCode) {
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
        this.workTrace = immutable(workTrace, "workTrace");
        this.documentStepTrace = immutable(
                documentStepTrace, "documentStepTrace");
        this.tentativeFinalizations = immutable(
                tentativeFinalizations, "tentativeFinalizations");
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
     * Returns a stable fail-closed capability code when the execution could
     * not yet be assembled into a conforming protocol result.
     *
     * @return capability code, or {@code null} after conforming completion
     */
    public String nonConformanceCode() {
        return nonConformanceCode;
    }

    /**
     * Reports whether every accepted work occurrence has one completed
     * isolated step and no capability gap remains.
     *
     * @return whether the implementation evidence is complete
     */
    public boolean complete() {
        return nonConformanceCode == null
                && workTrace.size() == documentStepTrace.size();
    }

    private void validateOneStepPerWork() {
        if (documentStepTrace.size() > workTrace.size()) {
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
        for (int index = 0; index < documentStepTrace.size(); index++) {
            DocumentStepEvidence step = documentStepTrace.get(index);
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
}
