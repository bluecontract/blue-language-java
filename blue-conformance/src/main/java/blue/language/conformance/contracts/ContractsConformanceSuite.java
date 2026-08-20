package blue.language.conformance.contracts;

import blue.language.conformance.api.BlueContractsConformanceFailure;
import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.conformance.api.BlueContractsFixtureCategory;
import blue.language.conformance.api.BlueContractsFixtureResult;
import blue.language.conformance.contracts.closure.ClosureFixtureConformance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supported entry point for the exact Blue Contracts 1.0 conformance package.
 *
 * <p>The suite owns its fixture runtimes and does not inspect or mutate a host
 * aggregate. Every manifest fixture executes, and unsupported or malformed
 * fixture data is recorded as a deterministic failure.</p>
 */
public final class ContractsConformanceSuite {

    private ContractsConformanceSuite() {
    }

    /**
     * Executes every bundled Contracts fixture.
     *
     * @return complete Contracts conformance report
     */
    public static BlueContractsConformanceReport run() {
        BlueContractsConformanceReport.validateFixturePackageIntegrity();
        BlueContractsConformanceReport.validateReleaseBindings();
        List<BlueContractsConformanceReport.FixtureInventoryEntry> inventory =
                BlueContractsConformanceReport.loadFixtureInventory();

        List<JsonNode> fixtures = new ArrayList<>(inventory.size());
        List<JsonNode> ordinaryFixtures = new ArrayList<>();
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        for (BlueContractsConformanceReport.FixtureInventoryEntry entry
                : inventory) {
            JsonNode fixture = BlueContractsConformanceReport.readFixture(
                    entry.path());
            requireInventoryMatch(entry, fixture);
            if (!"closure-fixture".equals(entry.role())) {
                harness.validate(fixture);
                ordinaryFixtures.add(fixture);
            }
            fixtures.add(fixture);
        }
        boolean completeCounterCoverage =
                new ContractsGasSchedule()
                        .hasCompleteMicrofixtureCoverage(ordinaryFixtures);
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
            fixtureIds.add(entry.id());
            categories.put(entry.id(), entry.category());
            try {
                if ("closure-fixture".equals(entry.role())) {
                    executeClosureFixture(entry, fixture);
                } else {
                    harness.execute(fixture, completeCounterCoverage);
                }
                passed.add(entry.id());
                results.add(result(
                        entry, BlueContractsFixtureResult.Status.PASS, null));
            } catch (RuntimeException | AssertionError fixtureFailure) {
                BlueContractsConformanceFailure recorded =
                        failure(entry, fixtureFailure);
                failed.add(entry.id());
                failures.add(recorded);
                results.add(result(
                        entry, BlueContractsFixtureResult.Status.FAIL,
                        recorded));
            }
        }

        return new BlueContractsConformanceReport(
                "1.0",
                BlueContractsConformanceReport.RELEASE_NAME,
                BlueContractsConformanceReport.RELEASE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .LANGUAGE_REGISTRY_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .LANGUAGE_FIXTURE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .CONTRACTS_REGISTRY_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .CONTRACTS_GAS_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .CONTRACTS_FIXTURE_PACKAGE_IDENTITY,
                fixtureIds,
                passed,
                failed,
                categories,
                failures,
                results);
    }

    private static void executeClosureFixture(
            BlueContractsConformanceReport.FixtureInventoryEntry entry,
            JsonNode fixture) {
        if ("limit-micro".equals(entry.operation())) {
            ClosureFixtureConformance.execute(entry, fixture, null);
            return;
        }
        ObjectNode executionFixture = (ObjectNode) fixture.deepCopy();
        executionFixture.remove("expected");
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(executionFixture)) {
            ClosureFixtureConformance.execute(
                    entry, fixture, runtime.processor());
        }
    }

    /**
     * Describes the packaged Contracts fixture inventory without executing it.
     *
     * @return report containing package metadata and no outcomes
     */
    public static BlueContractsConformanceReport unexecutedReport() {
        return new BlueContractsConformanceReport(
                "1.0",
                BlueContractsConformanceReport.RELEASE_NAME,
                BlueContractsConformanceReport.RELEASE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .LANGUAGE_REGISTRY_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .LANGUAGE_FIXTURE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .CONTRACTS_REGISTRY_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .CONTRACTS_GAS_PACKAGE_IDENTITY,
                BlueContractsConformanceReport.loadFixturePackageIdentity(
                        "blue-contracts-1.0-fixtures:unavailable"),
                BlueContractsConformanceReport.loadFixtureIds(),
                Collections.emptyList(),
                Collections.emptyList(),
                BlueContractsConformanceReport.loadFixtureCategories(),
                Collections.emptyList(),
                Collections.emptyList());
    }

    /**
     * Validates one parsed fixture envelope without executing it.
     *
     * @param fixture parsed fixture envelope
     */
    public static void validateFixture(JsonNode fixture) {
        new ContractsFixtureHarness().validate(fixture);
    }

    /**
     * Executes one parsed fixture envelope for focused fixture tests.
     *
     * @param fixture parsed fixture envelope
     */
    public static void runFixture(JsonNode fixture) {
        new ContractsFixtureHarness().execute(fixture, false);
    }

    private static BlueContractsFixtureResult result(
            BlueContractsConformanceReport.FixtureInventoryEntry entry,
            BlueContractsFixtureResult.Status status,
            BlueContractsConformanceFailure failure) {
        return new BlueContractsFixtureResult(
                entry.id(),
                entry.path(),
                entry.role(),
                entry.category(),
                entry.operation(),
                entry.vectors(),
                status,
                failure);
    }

    private static void requireInventoryMatch(
            BlueContractsConformanceReport.FixtureInventoryEntry entry,
            JsonNode fixture) {
        if (!entry.id().equals(fixture.path("id").asText())
                || !entry.operation().equals(
                        fixture.path("operation").asText())
                || !entry.category().equals(
                        BlueContractsFixtureCategory.fromLabel(
                                fixture.path("category").asText()))) {
            throw new IllegalStateException(
                    "Contracts fixture does not match manifest inventory: "
                            + entry.path());
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
                entry.id(),
                entry.category(),
                entry.operation(),
                failure.getClass().getName(),
                message);
    }
}
