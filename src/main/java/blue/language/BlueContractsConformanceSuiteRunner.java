package blue.language;

import blue.language.processor.conformance.ContractsFixtureHarness;
import blue.language.processor.conformance.ContractsGasSchedule;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the exact, inventoried Blue Contracts 1.0 conformance package.
 */
public final class BlueContractsConformanceSuiteRunner {

    private BlueContractsConformanceSuiteRunner() {
    }

    /**
     * Executes every bundled Contracts fixture.
     *
     * @param blue runtime under test
     * @return complete Contracts conformance report
     */
    public static BlueContractsConformanceReport run(Blue blue) {
        BlueContractsConformanceReport.validateFixturePackageIntegrity();
        BlueContractsConformanceReport.validateReleaseBindings();
        List<BlueContractsConformanceReport.FixtureInventoryEntry> inventory =
                BlueContractsConformanceReport.loadFixtureInventory();

        List<JsonNode> fixtures = new ArrayList<>(inventory.size());
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        for (BlueContractsConformanceReport.FixtureInventoryEntry entry : inventory) {
            JsonNode fixture = BlueContractsConformanceReport.readFixture(entry.path);
            requireInventoryMatch(entry, fixture);
            harness.validate(fixture);
            fixtures.add(fixture);
        }
        boolean completeCounterCoverage =
                new ContractsGasSchedule().hasCompleteMicrofixtureCoverage(fixtures);
        if (!completeCounterCoverage) {
            throw new IllegalStateException(
                    "Contracts gas counter microfixture coverage is incomplete");
        }

        List<String> fixtureIds = new ArrayList<>(inventory.size());
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, BlueContractsFixtureCategory> categories =
                new LinkedHashMap<>();
        List<BlueContractsConformanceFailure> failures = new ArrayList<>();
        List<BlueContractsFixtureResult> results = new ArrayList<>();

        for (int index = 0; index < inventory.size(); index++) {
            BlueContractsConformanceReport.FixtureInventoryEntry entry =
                    inventory.get(index);
            JsonNode fixture = fixtures.get(index);
            fixtureIds.add(entry.id);
            categories.put(entry.id, entry.category);
            try {
                harness.execute(fixture, blue, completeCounterCoverage);
                passed.add(entry.id);
                results.add(new BlueContractsFixtureResult(
                        entry.id,
                        entry.path,
                        entry.role,
                        entry.category,
                        entry.operation,
                        entry.vectors,
                        BlueContractsFixtureResult.Status.PASS,
                        null));
            } catch (RuntimeException | AssertionError failure) {
                BlueContractsConformanceFailure recorded =
                        failure(entry, failure);
                failed.add(entry.id);
                failures.add(recorded);
                results.add(new BlueContractsFixtureResult(
                        entry.id,
                        entry.path,
                        entry.role,
                        entry.category,
                        entry.operation,
                        entry.vectors,
                        BlueContractsFixtureResult.Status.FAIL,
                        recorded));
            }
        }

        return new BlueContractsConformanceReport(
                "1.0",
                BlueContractsConformanceReport.RELEASE_NAME,
                BlueContractsConformanceReport.RELEASE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport.LANGUAGE_REGISTRY_PACKAGE_IDENTITY,
                BlueContractsConformanceReport.LANGUAGE_FIXTURE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport.CONTRACTS_REGISTRY_PACKAGE_IDENTITY,
                BlueContractsConformanceReport.CONTRACTS_GAS_PACKAGE_IDENTITY,
                BlueContractsConformanceReport.CONTRACTS_FIXTURE_PACKAGE_IDENTITY,
                fixtureIds,
                passed,
                failed,
                categories,
                failures,
                results);
    }

    /**
     * Validates one parsed fixture envelope for focused tests.
     *
     * @param fixture parsed fixture envelope
     */
    public static void validateFixtureMetadataForTest(JsonNode fixture) {
        new ContractsFixtureHarness().validate(fixture);
    }

    /**
     * Executes one parsed fixture envelope for focused tests.
     *
     * @param fixture parsed fixture envelope
     */
    public static void runFixtureSpecForTest(JsonNode fixture) {
        new ContractsFixtureHarness().execute(fixture, new Blue(), false);
    }

    private static void requireInventoryMatch(
            BlueContractsConformanceReport.FixtureInventoryEntry entry,
            JsonNode fixture) {
        if (!entry.id.equals(fixture.path("id").asText())
                || !entry.operation.equals(fixture.path("operation").asText())
                || !entry.category.equals(BlueContractsFixtureCategory.fromLabel(
                fixture.path("category").asText()))) {
            throw new IllegalStateException(
                    "Contracts fixture does not match manifest inventory: "
                            + entry.path);
        }
    }

    private static BlueContractsConformanceFailure failure(
            BlueContractsConformanceReport.FixtureInventoryEntry entry,
            Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = failure.toString();
        }
        return new BlueContractsConformanceFailure(
                entry.id,
                entry.category,
                entry.operation,
                failure.getClass().getName(),
                message);
    }
}
