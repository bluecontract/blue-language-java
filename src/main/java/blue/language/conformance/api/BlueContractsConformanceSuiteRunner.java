package blue.language.conformance.api;

import blue.language.conformance.contracts.ContractsConformanceSuite;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Compatibility forwarding facade for the Contracts conformance suite.
 *
 * @deprecated use {@link ContractsConformanceSuite}; this type remains only
 *             as a source migration aid and owns no fixture implementation
 */
@Deprecated
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
