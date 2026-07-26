package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.BlueContractsConformanceFailure;
import blue.language.BlueContractsConformanceReport;
import blue.language.BlueReleaseConformanceReport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceReportTest {

    private static final List<String> PUBLISHED_FIXTURE_FAILURES =
            Arrays.asList(
                    "c-disc-04",
                    "c-disc-05",
                    "c-e2e-02",
                    "c-emb-02",
                    "c-emb-07",
                    "c-evt-01",
                    "c-evt-03",
                    "c-life-03",
                    "c-prot-02",
                    "c-rep-04",
                    "c-snd-04",
                    "c-upd-01",
                    "c-upd-02",
                    "c-upd-03");

    @Test
    void exactReleaseReportRecordsEveryPassAndPublishedFixtureFailure()
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
        assertEquals(
                PUBLISHED_FIXTURE_FAILURES,
                contracts.getFailedFixtureIds(),
                () -> contracts.getFailures().stream()
                        .map(this::failureMessage)
                        .collect(Collectors.joining("\n")));
        assertEquals(113,
                contracts.getPassedFixtureIds().size());
        assertEquals(14, contracts.getFailures().size());
        assertTrue(contracts.getFailures().stream()
                .allMatch(failure ->
                        failure.getMessage() != null
                                && !failure.getMessage()
                                .trim().isEmpty()));
        assertEquals(0, contracts.getSkippedFixtureCount());
        assertTrue(!contracts.isConformant());
        assertTrue(!release.isConformant());

        Map<String, Object> encoded =
                release.toMachineReadableMap();
        assertEquals(BlueContractsConformanceReport
                        .RELEASE_PACKAGE_IDENTITY,
                nested(encoded, "release", "packageIdentity"));
        assertEquals(BlueContractsConformanceReport
                        .CONTRACTS_FIXTURE_PACKAGE_IDENTITY,
                nested(encoded, "packages", "contractsFixtures"));
        assertEquals(252, nested(encoded, "summary", "total"));
        assertEquals(238, nested(encoded, "summary", "passed"));
        assertEquals(14, nested(encoded, "summary", "failed"));
        assertEquals(0, nested(encoded, "summary", "skipped"));
        assertEquals(false,
                nested(encoded, "summary", "conformant"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures =
                (List<Map<String, Object>>) encoded.get("fixtures");
        Set<Object> keys = fixtures.stream()
                .map(fixture -> fixture.get("resultKey"))
                .collect(Collectors.toCollection(HashSet::new));
        assertEquals(252, fixtures.size());
        assertEquals(252, keys.size());
        assertEquals(238, fixtures.stream()
                .filter(fixture ->
                        "PASS".equals(fixture.get("status")))
                .count());
        List<String> encodedFailures = fixtures.stream()
                .filter(fixture ->
                        "contracts".equals(fixture.get("suite"))
                                && "FAIL".equals(
                                fixture.get("status")))
                .map(fixture -> (String) fixture.get("id"))
                .collect(Collectors.toList());
        assertEquals(PUBLISHED_FIXTURE_FAILURES,
                encodedFailures);
        assertTrue(fixtures.stream()
                .filter(fixture ->
                        "FAIL".equals(fixture.get("status")))
                .allMatch(fixture ->
                        fixture.get("failure") instanceof Map));

        JsonNode json = JSON_MAPPER.readTree(
                release.toMachineReadableJson());
        assertEquals(252, json.path("fixtures").size());
        assertEquals(238,
                json.path("summary").path("passed").asInt());
        assertEquals(14,
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

    private String failureMessage(BlueContractsConformanceFailure failure) {
        return failure.getFixtureId() + " ["
                + failure.getCategory().name() + "] "
                + failure.getOperation() + " -> "
                + failure.getExceptionClass() + ": "
                + failure.getMessage();
    }
}
