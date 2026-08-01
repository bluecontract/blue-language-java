package blue.language.conformance.runner;

import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.conformance.contracts.ContractsConformanceSuite;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Public runner entry point for the Contracts conformance suite.
 *
 * <p>The runner owns no fixture implementation. It keeps orchestration above
 * the report-only API package and delegates execution to the closed Contracts
 * fixture suite.</p>
 */
public final class BlueContractsConformanceSuiteRunner {

    private BlueContractsConformanceSuiteRunner() {
    }

    /** Executes every bundled Contracts fixture. */
    public static BlueContractsConformanceReport run() {
        return ContractsConformanceSuite.run();
    }

    /** Describes the fixture inventory without executing it. */
    public static BlueContractsConformanceReport unexecutedReport() {
        return ContractsConformanceSuite.unexecutedReport();
    }

    /** Validates one parsed fixture envelope for focused tests. */
    public static void validateFixtureMetadataForTest(JsonNode fixture) {
        ContractsConformanceSuite.validateFixture(fixture);
    }

    /** Executes one parsed fixture envelope for focused tests. */
    public static void runFixtureSpecForTest(JsonNode fixture) {
        ContractsConformanceSuite.runFixture(fixture);
    }
}
