package blue.language.conformance.contracts.closure;

import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact public-facade execution gate for the complete executable closure corpus. */
final class FullClosureCorpusConformanceTest {

    @Test
    void executesEverySupportedFixtureBeforeReadingExpectedEvidence(
            TestReporter reporter) {
        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();
        Counts counts = new Counts();
        for (ClosureFixtureInventory.Entry entry : source.entries()) {
            if ("limit-micro".equals(entry.operation())) {
                counts.unsupported++;
                reporter.publishEntry(entry.id(),
                        "UNSUPPORTED: portable limit microfixture; no closure invocation");
                continue;
            }

            // This copy has no expected subtree.  Parser, runtime, and public
            // facade therefore finish without any expected value in reach.
            JsonNode executionFixture = source.executionFixture(entry);
            ClosureFixtureParser.ParsedFixture parsed =
                    new ClosureFixtureParser().parse(entry, executionFixture);
            ClosureInvocationInput input = parsed.admit();
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (ClosureFixtureRuntime runtime =
                         ClosureFixtureRuntime.fromFixture(executionFixture);
                 BlueClosureContracts contracts = new BlueClosureContracts(
                         runtime.processor(), capture)) {
                if ("process-closure".equals(entry.operation())) {
                    attempt = contracts.processClosure(input);
                } else if ("admit-closure".equals(entry.operation())) {
                    attempt = contracts.admitClosureWithLifecycleQueue(input);
                } else {
                    throw new AssertionError(
                            "Unclassified closure operation: "
                                    + entry.operation());
                }
            }

            // Read the expected tree and any external trace only after the
            // independently produced attempt and observer evidence are final.
            JsonNode expected = source.expectedAfterExecution(entry);
            if (!attempt.isComplete()) {
                counts.needsResources++;
                assertNeedsResources(entry.id(), expected, attempt, capture);
                reporter.publishEntry(entry.id(), "NEEDS_RESOURCES");
                continue;
            }

            counts.executed++;
            assertNotNull(attempt.processResult(), entry.id());
            if (attempt.processResult().status() == ProcessorStatus.SUCCESS) {
                counts.success++;
            } else {
                counts.nonSuccess++;
            }
            try {
                Cclo34FullResultConformanceTest.assertCompleteResult(
                        expected, attempt, capture.evidence);
            } catch (AssertionError failure) {
                throw new AssertionError(entry.id() + ": "
                        + failure.getMessage(), failure);
            }
            reporter.publishEntry(entry.id(),
                    "EXECUTED: "
                            + attempt.processResult().status().wireValue());
        }

        reporter.publishEntry("summary", counts.toString());
        assertEquals(93, source.entries().size());
        assertEquals(66, counts.executed);
        assertEquals(50, counts.success);
        assertEquals(16, counts.nonSuccess);
        assertEquals(9, counts.needsResources);
        assertEquals(18, counts.unsupported);
    }

    private static void assertNeedsResources(
            String id,
            JsonNode expected,
            ClosureAttemptResult attempt,
            Capture capture) {
        assertEquals("NeedsResources",
                ClosureFixtureInventory.requiredText(
                        expected, "attemptOutcome"), id);
        List<String> expectedIds = new ArrayList<String>();
        for (JsonNode value : ClosureFixtureInventory.requiredArray(
                expected, "requiredBlueIds")) {
            expectedIds.add(value.asText());
        }
        assertEquals(expectedIds,
                new ArrayList<String>(attempt.requiredExactBlueIds()), id);
        assertFalse(attempt.isComplete(), id);
        assertNull(attempt.processResult(), id);
        assertNull(attempt.totalGas(), id);
        assertNull(capture.evidence, id);
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            assertNull(evidence, "one observer callback per invocation");
            evidence = value;
        }
    }

    private static final class Counts {
        private int executed;
        private int success;
        private int nonSuccess;
        private int needsResources;
        private int unsupported;

        @Override
        public String toString() {
            return "EXECUTED=" + executed
                    + " (success=" + success
                    + ", non-success=" + nonSuccess + ")"
                    + ", NEEDS_RESOURCES=" + needsResources
                    + ", UNSUPPORTED=" + unsupported;
        }
    }
}
