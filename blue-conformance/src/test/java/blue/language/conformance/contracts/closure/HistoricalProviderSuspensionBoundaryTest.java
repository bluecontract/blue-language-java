package blue.language.conformance.contracts.closure;

import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.conformance.contracts.ClosureFixtureRuntimeAssertions;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact C22 suspension and C23 availability-retry boundary evidence. */
final class HistoricalProviderSuspensionBoundaryTest {

    private static final String HISTORICAL_A5 =
            "2kpAUcknjsY6eoij8s6u3vKHKNzFBWyweaekTpMkK7E8";

    @Test
    void unavailableExactHistoricalNodeSuspendsWithoutCompletedEvidence() {
        FixtureExecution fixture = fixture(
                "c-clo-22-a10-attach-a5-needs-resources");
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(fixture.envelope);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor(), capture)) {
            attempt = contracts.processClosure(fixture.input);
        }

        assertFalse(attempt.isComplete());
        assertEquals(Collections.singletonList(HISTORICAL_A5),
                attempt.requiredExactBlueIds());
        assertNull(attempt.processResult());
        assertNull(attempt.totalGas());
        assertNull(capture.evidence);
    }

    @Test
    void availableExactHistoricalNodeIsPhysicallyDemandedAndCompletes() {
        FixtureExecution fixture = fixture(
                "c-clo-23-00-attach-a5-retry");
        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(fixture.envelope);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor())) {
            attempt = contracts.processClosure(fixture.input);
            ClosureFixtureRuntimeAssertions.verifyExactProviderLoads(
                    runtime, Collections.singletonList(HISTORICAL_A5));
        }

        assertTrue(attempt.isComplete());
        assertEquals(ProcessorStatus.SUCCESS,
                attempt.processResult().status());
    }

    @Test
    void authenticatedHistoricalRevisionValidatesItsExactSuccessor() {
        FixtureExecution fixture = fixture("c-clo-23-01-a5-to-a6");
        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(fixture.envelope);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor())) {
            attempt = contracts.processClosure(fixture.input);
        }

        assertTrue(attempt.isComplete());
        assertEquals(ProcessorStatus.SUCCESS,
                attempt.processResult().status());
    }

    @Test
    void historicalRevisionRejectsSuccessorBlueIdTampering() {
        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();
        ClosureFixtureInventory.Entry entry = requireEntry(
                source, "c-clo-23-01-a5-to-a6");
        ObjectNode envelope = (ObjectNode) source.executionFixture(entry);
        ((ObjectNode) envelope.path("input").path("cause"))
                .put("afterBlueId", HISTORICAL_A5);
        ClosureInvocationInput input = new ClosureFixtureParser()
                .parse(entry, envelope).admit();

        IllegalArgumentException failure;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(envelope);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor())) {
            failure = assertThrows(IllegalArgumentException.class,
                    () -> contracts.processClosure(input));
        }
        assertTrue(failure.getMessage().contains("afterBlueId"));
    }

    private static FixtureExecution fixture(String id) {
        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();
        ClosureFixtureInventory.Entry entry = requireEntry(source, id);
        JsonNode envelope = source.executionFixture(entry);
        ClosureInvocationInput input = new ClosureFixtureParser()
                .parse(entry, envelope).admit();
        return new FixtureExecution(envelope, input);
    }

    private static ClosureFixtureInventory.Entry requireEntry(
            ClosureFixtureCorpusSource source,
            String id) {
        for (ClosureFixtureInventory.Entry entry : source.entries()) {
            if (id.equals(entry.id())) {
                return entry;
            }
        }
        throw new AssertionError("Closure fixture missing: " + id);
    }

    private static final class FixtureExecution {
        private final JsonNode envelope;
        private final ClosureInvocationInput input;

        private FixtureExecution(
                JsonNode envelope,
                ClosureInvocationInput input) {
            this.envelope = envelope;
            this.input = input;
        }
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(ClosureImplementationEvidence value) {
            assertNull(evidence, "one observer callback per invocation");
            evidence = value;
        }
    }
}
