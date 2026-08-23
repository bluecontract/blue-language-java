package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.processor.GasSchedule;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** Per-fixture exact public-facade gate for the complete closure corpus. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class DynamicClosureCorpusConformanceTest {

    private static final int FIXTURE_COUNT = 80;
    private static final int LIMIT_MICRO_COUNT = 18;
    private static final String REPORT_PATH_PROPERTY =
            "blue.contracts.closureDiscrepancyReport";
    private static final Path DEFAULT_REPORT_PATH = Paths.get(
            "build", "reports", "conformance",
            "closure-discrepancy-report.json");

    private final Map<String, DiscrepancyRow> rows =
            new ConcurrentHashMap<String, DiscrepancyRow>();
    private ClosureFixtureCorpusSource source;

    @TestFactory
    List<DynamicTest> executesEveryFixtureAsAnIndependentCase() {
        source = ClosureFixtureCorpusSource.open();
        rows.clear();
        deletePriorReport();
        List<DynamicTest> tests = new ArrayList<DynamicTest>();
        int unsupported = 0;
        for (ClosureFixtureInventory.Entry entry : source.entries()) {
            boolean limitMicro = "limit-micro".equals(entry.operation());
            if (limitMicro) {
                unsupported++;
            }
            String disposition = limitMicro
                    ? "UNSUPPORTED limit-micro"
                    : entry.operation();
            tests.add(dynamicTest(
                    entry.id() + " [" + disposition + "]",
                    () -> record(executeFixture(source, entry))));
        }

        assertEquals(FIXTURE_COUNT, tests.size(), "closure fixture cases");
        assertEquals(LIMIT_MICRO_COUNT, unsupported,
                "unsupported limit-micro cases");
        return tests;
    }

    @AfterAll
    void shouldWriteOneManifestOrderedDiscrepancyRowPerFixture()
            throws IOException {
        assertNotNull(source, "closure fixture source");
        assertEquals(FIXTURE_COUNT, source.entries().size(),
                "closure fixture inventory");
        assertEquals(FIXTURE_COUNT, rows.size(),
                "completed discrepancy rows");

        ArrayNode output = JsonNodeFactory.instance.arrayNode();
        int limitMicros = 0;
        for (ClosureFixtureInventory.Entry entry : source.entries()) {
            DiscrepancyRow row = rows.get(entry.id());
            assertNotNull(row, entry.id() + " discrepancy row");
            assertEquals(entry.id(), row.fixtureId, "fixtureId");
            assertEquals(entry.operation(), row.operation, "operation");
            assertEquals("PASS", row.outcome, entry.id() + " outcome");
            assertEquals(row.expectedStatus, row.implementationStatus,
                    entry.id() + " status discrepancy");
            if ("limit-micro".equals(entry.operation())) {
                limitMicros++;
                assertNull(row.gas, entry.id() + " gas");
                assertEquals(0, row.workCount, entry.id() + " workCount");
                assertNull(row.resultIdentity,
                        entry.id() + " resultIdentity");
            } else if ("needs-resources".equals(
                    row.implementationStatus)) {
                assertNull(row.gas, entry.id() + " gas");
                assertEquals(0, row.workCount, entry.id() + " workCount");
                assertNull(row.resultIdentity,
                        entry.id() + " resultIdentity");
            } else {
                assertNotNull(row.gas, entry.id() + " gas");
                assertNotNull(row.resultIdentity,
                        entry.id() + " resultIdentity");
            }
            ObjectNode encoded = row.toJson();
            assertEquals(8, encoded.size(), entry.id() + " report fields");
            output.add(encoded);
        }
        assertEquals(FIXTURE_COUNT, output.size(), "discrepancy report rows");
        assertEquals(LIMIT_MICRO_COUNT, limitMicros,
                "limit-micro discrepancy rows");

        Path report = reportPath();
        Files.createDirectories(report.toAbsolutePath().getParent());
        UncheckedObjectMapper.JSON_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(report.toFile(), output);
    }

    private static DiscrepancyRow executeFixture(
            ClosureFixtureCorpusSource source,
            ClosureFixtureInventory.Entry entry) {
        if ("limit-micro".equals(entry.operation())) {
            // The frozen fixture has no public-facade invocation. Measure its
            // independent generator without pretending that closure work ran.
            return executeLimitMicro(source, entry);
        }

        // The execution copy contains no expected subtree. Expected evidence
        // is not read until the independently produced attempt is complete.
        JsonNode executionFixture = source.executionFixture(entry);
        ClosureInvocationInput input = new ClosureFixtureParser()
                .parse(entry, executionFixture)
                .admit();
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

        JsonNode expected = source.expectedAfterExecution(entry);
        if (!attempt.isComplete()) {
            String expectedStatus = assertNeedsResources(
                    entry.id(), expected, attempt, capture);
            String implementationStatus = attempt.kind().wireValue();
            assertEquals(expectedStatus, implementationStatus,
                    entry.id() + " attempt outcome");
            return new DiscrepancyRow(
                    entry.id(), entry.operation(), "PASS",
                    implementationStatus, expectedStatus,
                    null, 0, null);
        }

        assertNotNull(attempt.processResult(), entry.id());
        try {
            Cclo34FullResultConformanceTest.assertCompleteResult(
                    expected, attempt, capture.evidence);
        } catch (AssertionError failure) {
            throw new AssertionError(
                    entry.id() + ": " + failure.getMessage(), failure);
        }
        ClosureProcessResult result = attempt.processResult();
        return new DiscrepancyRow(
                entry.id(), entry.operation(), "PASS",
                result.status().wireValue(),
                ClosureFixtureInventory.requiredText(expected, "status"),
                Long.valueOf(result.totalGas()),
                capture.evidence == null
                        ? 0 : capture.evidence.workTrace().size(),
                result.outputClosureIdentity());
    }

    private static DiscrepancyRow executeLimitMicro(
            ClosureFixtureCorpusSource source,
            ClosureFixtureInventory.Entry entry) {
        JsonNode executionFixture = source.executionFixture(entry);
        JsonNode limit = ClosureFixtureInventory.requiredObject(
                executionFixture, "limit");
        String limitName = ClosureFixtureInventory.requiredText(
                limit, "limit");
        long configured = ClosureFixtureInventory.requiredLong(
                limit, "configured");
        long measured = ClosureLimitMicroEvaluator.measure(
                limitName,
                ClosureFixtureInventory.requiredObject(limit, "generator"));

        JsonNode expected = source.expectedAfterExecution(entry);
        String implementationStatus = measured <= configured
                ? "ACCEPT" : "REJECT";
        String expectedStatus = ClosureFixtureInventory.requiredText(
                expected, "limitDecision");
        assertEquals(GasSchedule.contracts10().portableLimit(limitName),
                configured, entry.id() + " configured limit");
        assertEquals(ClosureFixtureInventory.requiredLong(limit, "observed"),
                measured, entry.id() + " measured limit");
        assertEquals(expectedStatus, implementationStatus,
                entry.id() + " limit decision");
        assertFalse(ClosureFixtureInventory.requiredBoolean(
                        expected, "rejectedStepAdmitted"),
                entry.id() + " rejected step admission");
        assertEquals("NOT_EXECUTED_BY_MICROFIXTURE",
                ClosureFixtureInventory.requiredText(
                        expected, "subsequentOutcome"),
                entry.id() + " subsequent outcome");
        return new DiscrepancyRow(
                entry.id(), entry.operation(), "PASS",
                implementationStatus, expectedStatus, null, 0, null);
    }

    private static String assertNeedsResources(
            String id,
            JsonNode expected,
            ClosureAttemptResult attempt,
            Capture capture) {
        String expectedOutcome = ClosureFixtureInventory.requiredText(
                expected, "attemptOutcome");
        assertEquals("NeedsResources", expectedOutcome, id);
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
        return "needs-resources";
    }

    private void record(DiscrepancyRow row) {
        assertNull(rows.putIfAbsent(row.fixtureId, row),
                row.fixtureId + " duplicate discrepancy row");
    }

    private void deletePriorReport() {
        try {
            Files.deleteIfExists(reportPath());
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to clear prior closure discrepancy report",
                    failure);
        }
    }

    private static Path reportPath() {
        String configured = System.getProperty(REPORT_PATH_PROPERTY);
        return configured == null || configured.isEmpty()
                ? DEFAULT_REPORT_PATH
                : Paths.get(configured);
    }

    private static final class DiscrepancyRow {
        private final String fixtureId;
        private final String operation;
        private final String outcome;
        private final String implementationStatus;
        private final String expectedStatus;
        private final Long gas;
        private final int workCount;
        private final String resultIdentity;

        private DiscrepancyRow(
                String fixtureId,
                String operation,
                String outcome,
                String implementationStatus,
                String expectedStatus,
                Long gas,
                int workCount,
                String resultIdentity) {
            this.fixtureId = fixtureId;
            this.operation = operation;
            this.outcome = outcome;
            this.implementationStatus = implementationStatus;
            this.expectedStatus = expectedStatus;
            this.gas = gas;
            this.workCount = workCount;
            this.resultIdentity = resultIdentity;
        }

        private ObjectNode toJson() {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.put("fixtureId", fixtureId);
            result.put("operation", operation);
            result.put("outcome", outcome);
            result.put("implementationStatus", implementationStatus);
            result.put("expectedStatus", expectedStatus);
            if (gas == null) {
                result.putNull("gas");
            } else {
                result.put("gas", gas.longValue());
            }
            result.put("workCount", workCount);
            if (resultIdentity == null) {
                result.putNull("resultIdentity");
            } else {
                result.put("resultIdentity", resultIdentity);
            }
            return result;
        }
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
}
