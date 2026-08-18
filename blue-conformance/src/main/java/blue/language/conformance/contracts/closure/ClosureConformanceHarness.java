package blue.language.conformance.contracts.closure;

import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessor;
import blue.language.processor.closure.DocumentStepEvidence;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Initial honest closure-fixture runner. It never receives the expected tree
 * until after core execution has returned independently produced evidence.
 */
final class ClosureConformanceHarness {

    enum Status {
        INVARIANT_VERIFIED,
        IMPLEMENTATION_UNAVAILABLE,
        INPUT_REJECTED,
        NEEDS_RESOURCES,
        INCOMPLETE_EVIDENCE,
        EXECUTION_FAILED,
        UNSUPPORTED_FIXTURE
    }

    interface TraceAdapter {
        List<DocumentStepEvidence> documentSteps(
                ClosureInvocationInput input,
                ClosureAttemptResult result);
    }

    Result runCclo34(
            ClosureProcessor processor,
            TraceAdapter traceAdapter) {
        ClosureFixtureInventory.Entry entry =
                ClosureFixtureInventory.requireById(
                        ClosureFixtureInventory.C_CLO_34);
        ClosureFixtureParser.ParsedFixture parsed;
        try {
            parsed = new ClosureFixtureParser().parse(entry);
        } catch (UnsupportedOperationException failure) {
            return Result.failure(Status.UNSUPPORTED_FIXTURE, failure);
        } catch (RuntimeException failure) {
            return Result.failure(Status.INPUT_REJECTED, failure);
        }

        ClosureInvocationInput input;
        try {
            input = parsed.admit();
        } catch (RuntimeException failure) {
            return Result.failure(Status.INPUT_REJECTED, failure);
        }
        if (processor == null) {
            return Result.failure(
                    Status.IMPLEMENTATION_UNAVAILABLE,
                    new IllegalStateException(
                            "No ClosureProcessor implementation was supplied"));
        }

        ClosureAttemptResult attempt;
        try {
            attempt = Objects.requireNonNull(
                    processor.processClosure(input),
                    "ClosureProcessor result");
        } catch (RuntimeException failure) {
            return Result.failure(Status.EXECUTION_FAILED, failure);
        }
        if (!attempt.isComplete()) {
            return new Result(
                    Status.NEEDS_RESOURCES,
                    attempt,
                    Collections.<DocumentStepEvidence>emptyList(),
                    null);
        }
        if (attempt.processResult().status() != ProcessorStatus.SUCCESS) {
            return Result.failure(
                    Status.EXECUTION_FAILED,
                    new AssertionError(
                            "C-CLO-34 requires success; actual status was "
                                    + attempt.processResult().status().wireValue()),
                    attempt);
        }
        if (traceAdapter == null) {
            return Result.failure(
                    Status.INCOMPLETE_EVIDENCE,
                    new IllegalStateException(
                            "ClosureProcessResult exposes no document-step trace; "
                                    + "an implementation evidence adapter is required"),
                    attempt);
        }

        List<DocumentStepEvidence> steps;
        try {
            steps = Objects.requireNonNull(
                    traceAdapter.documentSteps(input, attempt),
                    "document step evidence");
            verifySeparateDocumentTrace(
                    ClosureFixtureInventory.readFixture(entry),
                    steps);
        } catch (RuntimeException | AssertionError failure) {
            return Result.failure(Status.EXECUTION_FAILED, failure, attempt);
        }
        return new Result(
                Status.INVARIANT_VERIFIED,
                attempt,
                steps,
                null);
    }

    static void verifySeparateDocumentTrace(
            JsonNode fixture,
            List<DocumentStepEvidence> actual) {
        JsonNode expected = ClosureFixtureInventory.requiredArray(
                ClosureFixtureInventory.requiredObject(fixture, "expected"),
                "documentStepTrace");
        if (actual.size() != expected.size()) {
            throw new AssertionError(
                    "documentStepTrace size: expected " + expected.size()
                            + " but was " + actual.size());
        }
        for (int index = 0; index < actual.size(); index++) {
            DocumentStepEvidence evidence = Objects.requireNonNull(
                    actual.get(index), "document step evidence item");
            JsonNode expectedStep = expected.get(index);
            requireEqual(
                    "stepOrdinal",
                    ClosureFixtureInventory.requiredLong(
                            expectedStep, "stepOrdinal"),
                    evidence.stepOrdinal());
            requireEqual(
                    "workOrdinal",
                    ClosureFixtureInventory.requiredLong(
                            expectedStep, "workOrdinal"),
                    evidence.workOrdinal());
            requireEqual(
                    "targetDocumentId",
                    ClosureFixtureInventory.requiredText(
                            expectedStep, "targetDocumentId"),
                    evidence.targetDocumentId().value());
            requireEqual(
                    "executionRootDocumentId",
                    ClosureFixtureInventory.requiredText(
                            expectedStep, "executionRootDocumentId"),
                    evidence.executionRootDocumentId().value());
            requireEqual(
                    "scopePath",
                    ClosureFixtureInventory.requiredText(
                            expectedStep, "scopePath"),
                    evidence.scopePath());
            requireEqual(
                    "executionMode",
                    ClosureFixtureInventory.requiredText(
                            expectedStep, "executionMode"),
                    evidence.executionMode());
            JsonNode expectedAmbient = ClosureFixtureInventory.requiredArray(
                    expectedStep, "ambientContainingDocumentIds");
            if (expectedAmbient.size()
                    != evidence.ambientContainingDocumentIds().size()
                    || !evidence.ambientContainingDocumentIds().isEmpty()) {
                throw new AssertionError(
                        "ambientContainingDocumentIds must be exactly empty");
            }
            if (!evidence.targetDocumentId().equals(
                    evidence.executionRootDocumentId())) {
                throw new AssertionError(
                        "A document step must execute at its own Root");
            }
        }
    }

    private static void requireEqual(String field, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError(
                    field + ": expected " + expected + " but was " + actual);
        }
    }

    private static void requireEqual(
            String field,
            String expected,
            String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    field + ": expected " + expected + " but was " + actual);
        }
    }

    static final class Result {
        private final Status status;
        private final ClosureAttemptResult attempt;
        private final List<DocumentStepEvidence> documentSteps;
        private final Throwable failure;

        Result(
                Status status,
                ClosureAttemptResult attempt,
                List<DocumentStepEvidence> documentSteps,
                Throwable failure) {
            this.status = status;
            this.attempt = attempt;
            this.documentSteps = documentSteps == null
                    ? Collections.<DocumentStepEvidence>emptyList()
                    : Collections.unmodifiableList(
                            new java.util.ArrayList<DocumentStepEvidence>(
                                    documentSteps));
            this.failure = failure;
        }

        static Result failure(Status status, Throwable failure) {
            return failure(status, failure, null);
        }

        static Result failure(
                Status status,
                Throwable failure,
                ClosureAttemptResult attempt) {
            return new Result(
                    status,
                    attempt,
                    Collections.<DocumentStepEvidence>emptyList(),
                    failure);
        }

        Status status() {
            return status;
        }

        ClosureAttemptResult attempt() {
            return attempt;
        }

        List<DocumentStepEvidence> documentSteps() {
            return documentSteps;
        }

        Throwable failure() {
            return failure;
        }

        boolean implementationConformanceClaimed() {
            return false;
        }
    }
}
