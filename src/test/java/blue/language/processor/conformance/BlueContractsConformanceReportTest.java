package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.BlueContractsConformanceReport;
import blue.language.BlueReleaseConformanceReport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceReportTest {

    @Test
    void exactReleaseReportRequiresEveryFixtureToPass()
            throws Exception {
        BlueReleaseConformanceReport release =
                new Blue().runReleaseConformanceSuites();
        BlueContractsConformanceReport contracts =
                release.getContractsReport();

        assertEquals(125,
                release.getLanguageReport()
                        .getPassedFixtureIds().size());
        assertTrue(release.getLanguageReport()
                .getFailures().isEmpty());
        assertEquals(
                release.getLanguageReport().getFixtureIds(),
                release.getLanguageReport()
                        .getPassedFixtureIds());

        assertEquals(127, contracts.getFixtureIds().size());
        assertEquals(69, contracts.getFixtureResults().stream()
                .filter(result ->
                        "behavior-fixture".equals(result.getRole()))
                .count());
        assertEquals(58, contracts.getFixtureResults().stream()
                .filter(result -> "gas-fixture".equals(result.getRole()))
                .count());
        assertEquals(contracts.getFixtureIds(),
                contracts.getPassedFixtureIds(),
                () -> contracts.getFailures().toString());
        assertEquals(127, contracts.getPassedFixtureIds().size());
        assertTrue(contracts.getFailedFixtureIds().isEmpty());
        assertTrue(contracts.getFailures().isEmpty());
        assertEquals(0, contracts.getSkippedFixtureCount());
        assertTrue(contracts.isConformant());
        assertTrue(release.isConformant());

        Map<String, Object> encoded =
                release.toMachineReadableMap();
        assertEquals(BlueContractsConformanceReport
                        .RELEASE_PACKAGE_IDENTITY,
                nested(encoded, "release", "packageIdentity"));
        assertEquals(BlueContractsConformanceReport
                        .CONTRACTS_FIXTURE_PACKAGE_IDENTITY,
                nested(encoded, "packages", "contractsFixtures"));
        assertEquals(252, nested(encoded, "summary", "total"));
        assertEquals(252, nested(encoded, "summary", "passed"));
        assertEquals(0, nested(encoded, "summary", "failed"));
        assertEquals(0, nested(encoded, "summary", "skipped"));
        assertEquals(true,
                nested(encoded, "summary", "conformant"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures =
                (List<Map<String, Object>>) encoded.get("fixtures");
        Set<Object> keys = fixtures.stream()
                .map(fixture -> fixture.get("resultKey"))
                .collect(Collectors.toCollection(HashSet::new));
        assertEquals(252, fixtures.size());
        assertEquals(252, keys.size());
        assertEquals(252, fixtures.stream()
                .filter(fixture ->
                        "PASS".equals(fixture.get("status")))
                .count());
        assertTrue(fixtures.stream()
                .noneMatch(fixture ->
                        "FAIL".equals(fixture.get("status"))));

        JsonNode json = JSON_MAPPER.readTree(
                release.toMachineReadableJson());
        assertEquals(252, json.path("fixtures").size());
        assertEquals(252,
                json.path("summary").path("passed").asInt());
        assertEquals(0,
                json.path("summary").path("failed").asInt());
    }

    @Test
    void staticReportExposesExactBindingsAndNeverClaimsUnrunPasses() {
        BlueContractsConformanceReport report =
                new Blue().contractsConformanceReport();

        assertEquals(BlueContractsConformanceReport
                        .CONTRACTS_FIXTURE_PACKAGE_IDENTITY,
                report.getFixturePackageIdentity());
        assertEquals(BlueContractsConformanceReport
                        .CONTRACTS_FIXTURE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .computeFixturePackageIdentity());
        assertEquals(BlueContractsConformanceReport
                        .CONTRACTS_GAS_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .computeGasPackageIdentity());
        assertEquals(BlueContractsConformanceReport
                        .CONTRACTS_REGISTRY_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .computeRegistryPackageIdentity());
        assertEquals(BlueContractsConformanceReport
                        .RELEASE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport
                        .computeReleasePackageIdentity());
        assertTrue(BlueContractsConformanceReport
                .fixturePackageIdentityMatchesFixtureFiles());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures =
                (List<Map<String, Object>>) report
                        .toMachineReadableMap().get("fixtures");
        assertEquals(127, fixtures.size());
        assertTrue(fixtures.stream().allMatch(
                result -> "FAIL".equals(result.get("status"))
                        && "HarnessDidNotRunFixture".equals(
                        result.get("errorCategory"))));
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> map,
                                 String object,
                                 String field) {
        return ((Map<String, Object>) map.get(object)).get(field);
    }

}
