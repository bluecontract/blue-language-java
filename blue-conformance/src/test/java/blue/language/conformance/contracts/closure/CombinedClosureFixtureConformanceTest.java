package blue.language.conformance.contracts.closure;

import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.conformance.contracts.ContractsConformanceSuite;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CombinedClosureFixtureConformanceTest {

    @Test
    void shouldExposeTheExactCombinedExecutableInventory() {
        List<BlueContractsConformanceReport.FixtureInventoryEntry> entries =
                BlueContractsConformanceReport.loadFixtureInventory();

        assertEquals(276, entries.size());
        assertEquals(112L, count(entries, "behavior-fixture"));
        assertEquals(71L, count(entries, "gas-fixture"));
        assertEquals(93L, count(entries, "closure-fixture"));
    }

    @Test
    void shouldExecuteLimitFixtureThroughTheReportBridge() {
        execute("c-clo-16-managed-documents-at-bound");
    }

    @Test
    void shouldLoadAliasExpandedCclo04WithinTheVerifiedParserBound() {
        BlueContractsConformanceReport.FixtureInventoryEntry entry =
                entry("c-clo-04-default-policy-loop");

        assertEquals(entry.id(), BlueContractsConformanceReport
                .readFixture(entry.path()).path("id").asText());
    }

    @Test
    void shouldPreserveOrdinaryBehaviorAndNewGasMicroExecution() {
        runOrdinary("c-snd-04");
        runOrdinary("gas-processor-closureExpanded");
    }

    @Test
    void shouldIndependentlyMeasureEveryLimitGenerator() {
        int executed = 0;
        for (BlueContractsConformanceReport.FixtureInventoryEntry entry
                : BlueContractsConformanceReport.loadFixtureInventory()) {
            if (!"closure-fixture".equals(entry.role())
                    || !"limit-micro".equals(entry.operation())) {
                continue;
            }
            ClosureFixtureConformance.execute(
                    entry,
                    BlueContractsConformanceReport.readFixture(entry.path()),
                    null);
            executed++;
        }
        assertEquals(18, executed);
    }

    @Test
    void shouldRejectATrustedObservedValueThatTheGeneratorDoesNotMeasure() {
        BlueContractsConformanceReport.FixtureInventoryEntry entry =
                entry("c-clo-16-managed-documents-at-bound");
        ObjectNode fixture = (ObjectNode) BlueContractsConformanceReport
                .readFixture(entry.path()).deepCopy();
        ((ObjectNode) fixture.path("limit")).put("observed", 0L);

        assertThrows(AssertionError.class,
                () -> ClosureFixtureConformance.execute(
                        entry, fixture, null));
    }

    @Test
    void shouldAllowDiagnosticReasonWordingDifferencesButRequirePresence() {
        ObjectNode expected = JsonNodeFactory.instance.objectNode();
        expected.put("reason", "canonical reference wording");

        ClosureFixtureConformance.verifyDiagnosticReasonPresence(
                "gasTrace[0]", expected, "implementation wording");
        assertThrows(AssertionError.class,
                () -> ClosureFixtureConformance
                        .verifyDiagnosticReasonPresence(
                                "gasTrace[0]", expected, null));

        expected.remove("reason");
        ClosureFixtureConformance.verifyDiagnosticReasonPresence(
                "gasTrace[0]", expected, null);
        assertThrows(AssertionError.class,
                () -> ClosureFixtureConformance
                        .verifyDiagnosticReasonPresence(
                                "gasTrace[0]", expected,
                                "unexpected diagnostic"));
    }

    @Test
    void shouldAllowAbsentObserverEvidenceOnlyForThreeEmptyTraces() {
        ObjectNode expected = JsonNodeFactory.instance.objectNode();
        expected.putArray("workTrace");
        expected.putArray("documentStepTrace");
        expected.putArray("tentativeFinalizations");

        ClosureFixtureConformance.verifyImplementationEvidence(
                "admission-failure", expected, "unused", null);

        String[] evidenceFields = {
                "workTrace",
                "documentStepTrace",
                "tentativeFinalizations"
        };
        for (String field : evidenceFields) {
            expected.withArray(field).addObject();
            assertThrows(AssertionError.class,
                    () -> ClosureFixtureConformance
                            .verifyImplementationEvidence(
                                    "execution", expected, "unused", null));
            expected.withArray(field).removeAll();
        }
    }

    private static void execute(String id) {
        BlueContractsConformanceReport.FixtureInventoryEntry entry = entry(id);
        ObjectNode fixture = (ObjectNode) BlueContractsConformanceReport
                .readFixture(entry.path()).deepCopy();
        if ("limit-micro".equals(entry.operation())) {
            ClosureFixtureConformance.execute(entry, fixture, null);
            return;
        }
        ObjectNode executionFixture = fixture.deepCopy();
        executionFixture.remove("expected");
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(executionFixture)) {
            ClosureFixtureConformance.execute(
                    entry, fixture, runtime.processor());
        }
    }

    private static void runOrdinary(String id) {
        BlueContractsConformanceReport.FixtureInventoryEntry entry = entry(id);
        assertFalse("closure-fixture".equals(entry.role()));
        ContractsConformanceSuite.runFixture(
                BlueContractsConformanceReport.readFixture(entry.path()));
    }

    private static BlueContractsConformanceReport.FixtureInventoryEntry entry(
            String id) {
        for (BlueContractsConformanceReport.FixtureInventoryEntry entry
                : BlueContractsConformanceReport.loadFixtureInventory()) {
            if (id.equals(entry.id())) {
                return entry;
            }
        }
        throw new AssertionError("Missing fixture " + id);
    }

    private static long count(
            List<BlueContractsConformanceReport.FixtureInventoryEntry> entries,
            String role) {
        long result = 0L;
        for (BlueContractsConformanceReport.FixtureInventoryEntry entry
                : entries) {
            if (role.equals(entry.role())) {
                result++;
            }
        }
        return result;
    }
}
