package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Non-semantic compare-and-swap companion for one atomic closure publication.
 *
 * <p>Per-document heads and epochs remain explicit host evidence. The value is
 * paired with, but is not a field of, an individual document result.</p>
 */
public final class ClosureCommitCompanion {

    private final String companionIdentity;
    private final String invocationIdentity;
    private final String inputClosureIdentity;
    private final String outputClosureIdentity;
    private final long expectedInputGraphGeneration;
    private final long outputGraphGeneration;
    private final List<ExpectedDocumentHead> expectedDocumentHeads;
    private final List<ResultingDocumentHead> resultingDocumentHeads;
    private final String inputOccurrenceBindingSetIdentity;
    private final String outputOccurrenceBindingSetIdentity;

    /**
     * Creates one atomic closure compare-and-swap companion skeleton.
     *
     * @param companionIdentity exact companion identity
     * @param invocationIdentity exact invocation identity
     * @param inputClosureIdentity expected input closure identity
     * @param outputClosureIdentity resulting closure identity
     * @param expectedInputGraphGeneration expected graph generation
     * @param outputGraphGeneration resulting graph generation
     * @param expectedDocumentHeads canonical expected durable heads
     * @param resultingDocumentHeads canonical changed durable heads
     * @param inputOccurrenceBindingSetIdentity expected row-set identity
     * @param outputOccurrenceBindingSetIdentity resulting row-set identity
     */
    public ClosureCommitCompanion(
            String companionIdentity,
            String invocationIdentity,
            String inputClosureIdentity,
            String outputClosureIdentity,
            long expectedInputGraphGeneration,
            long outputGraphGeneration,
            List<ExpectedDocumentHead> expectedDocumentHeads,
            List<ResultingDocumentHead> resultingDocumentHeads,
            String inputOccurrenceBindingSetIdentity,
            String outputOccurrenceBindingSetIdentity) {
        this.companionIdentity = ClosureValueSupport.requireSha256Identity(
                companionIdentity, "companionIdentity");
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
        this.inputClosureIdentity = ClosureValueSupport.requireSha256Identity(
                inputClosureIdentity, "inputClosureIdentity");
        this.outputClosureIdentity = ClosureValueSupport.requireSha256Identity(
                outputClosureIdentity, "outputClosureIdentity");
        this.expectedInputGraphGeneration =
                ClosureValueSupport.requireSafeInteger(
                        expectedInputGraphGeneration,
                        "expectedInputGraphGeneration");
        this.outputGraphGeneration = ClosureValueSupport.requireSafeInteger(
                outputGraphGeneration, "outputGraphGeneration");
        this.expectedDocumentHeads = immutableCanonicalExpectedHeads(
                expectedDocumentHeads);
        this.resultingDocumentHeads = immutableCanonicalResultingHeads(
                resultingDocumentHeads);
        this.inputOccurrenceBindingSetIdentity =
                ClosureValueSupport.requireSha256Identity(
                        inputOccurrenceBindingSetIdentity,
                        "inputOccurrenceBindingSetIdentity");
        this.outputOccurrenceBindingSetIdentity =
                ClosureValueSupport.requireSha256Identity(
                        outputOccurrenceBindingSetIdentity,
                        "outputOccurrenceBindingSetIdentity");
        validateResultingHeadSubset();
    }

    /**
     * Returns the documented value.
     *
     * @return exact companion identity
     */
    public String companionIdentity() {
        return companionIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return exact invocation identity
     */
    public String invocationIdentity() {
        return invocationIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return expected input closure identity
     */
    public String inputClosureIdentity() {
        return inputClosureIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return resulting closure identity
     */
    public String outputClosureIdentity() {
        return outputClosureIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return expected graph generation
     */
    public long expectedInputGraphGeneration() {
        return expectedInputGraphGeneration;
    }

    /**
     * Returns the documented value.
     *
     * @return resulting graph generation
     */
    public long outputGraphGeneration() {
        return outputGraphGeneration;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical expected document heads
     */
    public List<ExpectedDocumentHead> expectedDocumentHeads() {
        return expectedDocumentHeads;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical resulting document heads
     */
    public List<ResultingDocumentHead> resultingDocumentHeads() {
        return resultingDocumentHeads;
    }

    /**
     * Returns the documented value.
     *
     * @return expected complete occurrence-row-set identity
     */
    public String inputOccurrenceBindingSetIdentity() {
        return inputOccurrenceBindingSetIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return resulting complete occurrence-row-set identity
     */
    public String outputOccurrenceBindingSetIdentity() {
        return outputOccurrenceBindingSetIdentity;
    }

    private void validateResultingHeadSubset() {
        Set<DocumentId> expected = new HashSet<DocumentId>();
        for (ExpectedDocumentHead head : expectedDocumentHeads) {
            expected.add(head.documentId());
        }
        for (ResultingDocumentHead head : resultingDocumentHeads) {
            if (!expected.contains(head.documentId())) {
                throw new IllegalArgumentException(
                        "Resulting document head has no compare-and-swap expectation");
            }
        }
    }

    private static List<ExpectedDocumentHead> immutableCanonicalExpectedHeads(
            List<ExpectedDocumentHead> values) {
        ArrayList<ExpectedDocumentHead> copy = copyNonNull(
                values, "expectedDocumentHeads");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "A closure commit must compare at least one document head");
        }
        requireCanonicalExpected(copy);
        return Collections.unmodifiableList(copy);
    }

    private static List<ResultingDocumentHead> immutableCanonicalResultingHeads(
            List<ResultingDocumentHead> values) {
        ArrayList<ResultingDocumentHead> copy = copyNonNull(
                values, "resultingDocumentHeads");
        requireCanonicalResulting(copy);
        return Collections.unmodifiableList(copy);
    }

    private static void requireCanonicalExpected(
            List<ExpectedDocumentHead> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).documentId().compareTo(
                    values.get(index).documentId()) >= 0) {
                throw new IllegalArgumentException(
                        "Expected document heads are not canonical");
            }
        }
    }

    private static void requireCanonicalResulting(
            List<ResultingDocumentHead> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).documentId().compareTo(
                    values.get(index).documentId()) >= 0) {
                throw new IllegalArgumentException(
                        "Resulting document heads are not canonical");
            }
        }
    }

    private static <T> ArrayList<T> copyNonNull(
            List<T> values,
            String field) {
        ArrayList<T> copy = new ArrayList<T>(
                Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return copy;
    }

    /** Expected durable state for one affected managed document. */
    public static final class ExpectedDocumentHead {
        private final DocumentId documentId;
        private final String blueId;
        private final long epoch;

        /**
         * Creates one expected durable document head.
         *
         * @param documentId stable managed lineage
         * @param blueId expected exact BlueId
         * @param epoch expected durable epoch
         */
        public ExpectedDocumentHead(
                DocumentId documentId,
                String blueId,
                long epoch) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.blueId = ClosureValueSupport.requireBlueId(blueId, "blueId");
            this.epoch = ClosureValueSupport.requireSafeInteger(epoch, "epoch");
        }

        /**
         * Returns the documented value.
         *
         * @return stable managed lineage
         */
        public DocumentId documentId() {
            return documentId;
        }

        /**
         * Returns the documented value.
         *
         * @return expected exact BlueId
         */
        public String blueId() {
            return blueId;
        }

        /**
         * Returns the documented value.
         *
         * @return expected durable epoch
         */
        public long epoch() {
            return epoch;
        }
    }

    /** Durable state installed for one changed managed document. */
    public static final class ResultingDocumentHead {
        private final DocumentId documentId;
        private final String beforeBlueId;
        private final String afterBlueId;
        private final long resultingEpoch;

        /**
         * Creates one resulting durable document head.
         *
         * @param documentId stable managed lineage
         * @param beforeBlueId expected predecessor BlueId
         * @param afterBlueId resulting BlueId
         * @param resultingEpoch resulting durable epoch
         */
        public ResultingDocumentHead(
                DocumentId documentId,
                String beforeBlueId,
                String afterBlueId,
                long resultingEpoch) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
            this.beforeBlueId = ClosureValueSupport.requireBlueId(
                    beforeBlueId, "beforeBlueId");
            this.afterBlueId = ClosureValueSupport.requireBlueId(
                    afterBlueId, "afterBlueId");
            this.resultingEpoch = ClosureValueSupport.requireSafeInteger(
                    resultingEpoch, "resultingEpoch");
        }

        /**
         * Returns the documented value.
         *
         * @return stable managed lineage
         */
        public DocumentId documentId() {
            return documentId;
        }

        /**
         * Returns the documented value.
         *
         * @return expected predecessor BlueId
         */
        public String beforeBlueId() {
            return beforeBlueId;
        }

        /**
         * Returns the documented value.
         *
         * @return resulting BlueId
         */
        public String afterBlueId() {
            return afterBlueId;
        }

        /**
         * Returns the documented value.
         *
         * @return resulting durable epoch
         */
        public long resultingEpoch() {
            return resultingEpoch;
        }
    }
}
