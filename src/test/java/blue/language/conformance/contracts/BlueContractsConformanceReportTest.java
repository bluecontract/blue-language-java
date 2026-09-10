package blue.language.conformance.contracts;

import blue.language.conformance.api.BlueConformanceSuiteRunner;
import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.conformance.api.BlueReleaseConformanceReport;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.testing.RepositoryLayout;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceReportTest {

    private static final int CONTRACTS_BEHAVIOR_FIXTURE_COUNT = 126;
    private static final int CONTRACTS_GAS_FIXTURE_COUNT = 71;
    private static final int CONTRACTS_CLOSURE_FIXTURE_COUNT = 98;

    private static final Pattern SPECIFICATION_REGISTRY_IDENTITY = Pattern.compile(
            "(?s)The canonical core-registry package identity bound by this "
                    + "fixture package is:\\s*```text\\s*"
                    + "(sha256:[0-9a-f]{64})\\s*```");
    private static final Pattern RELEASE_LANGUAGE_REGISTRY_IDENTITY =
            Pattern.compile(
                    "(?m)^\\s*languageRegistryPackageIdentity:\\s*"
                            + "(sha256:[0-9a-f]{64})\\s*$");
    private static final Pattern MACHINE_LANGUAGE_REGISTRY_IDENTITY =
            Pattern.compile(
                    "\"languageRegistry\"\\s*:\\s*\""
                            + "(sha256:[0-9a-f]{64})\"");
    private static final Pattern CONSTANT_LANGUAGE_REGISTRY_IDENTITY =
            Pattern.compile(
                    "LANGUAGE_REGISTRY_PACKAGE_IDENTITY\\s*=\\s*"
                            + "\"(sha256:[0-9a-f]{64})\"");
    private static final Pattern README_LANGUAGE_REGISTRY_IDENTITY =
            Pattern.compile(
                    "(?s)The registry package identity is\\s*"
                            + "`(sha256:[0-9a-f]{64})`");
    @Test
    void shouldReportEveryLanguageFixturePassingInExactRelease() {
        // given
        int expectedLanguageFixtures = 185;

        // when
        BlueReleaseConformanceReport release =
                exactReleaseReport();

        // then
        assertEquals(
                expectedLanguageFixtures,
                release.getLanguageReport()
                        .getPassedFixtureIds().size());
        assertTrue(release.getLanguageReport()
                .getFailures().isEmpty());
        assertEquals(
                release.getLanguageReport().getFixtureIds(),
                release.getLanguageReport()
                        .getPassedFixtureIds());
    }

    @Test
    void shouldReportEveryContractsFixturePassingWithExactRoles() {
        // given
        int expectedContractsFixtures =
                BlueReleaseConformanceReport.CONTRACTS_FIXTURE_COUNT;
        long expectedBehaviorFixtures =
                CONTRACTS_BEHAVIOR_FIXTURE_COUNT;
        long expectedGasFixtures =
                CONTRACTS_GAS_FIXTURE_COUNT;
        long expectedClosureFixtures =
                CONTRACTS_CLOSURE_FIXTURE_COUNT;

        // when
        BlueContractsConformanceReport contracts =
                exactReleaseReport().getContractsReport();

        // then
        assertEquals(expectedContractsFixtures,
                contracts.getFixtureIds().size());
        assertEquals(expectedBehaviorFixtures,
                contracts.getFixtureResults().stream()
                .filter(result ->
                        "behavior-fixture".equals(result.getRole()))
                .count());
        assertEquals(expectedGasFixtures,
                contracts.getFixtureResults().stream()
                .filter(result -> "gas-fixture".equals(result.getRole()))
                .count());
        assertEquals(expectedClosureFixtures,
                contracts.getFixtureResults().stream()
                .filter(result -> "closure-fixture".equals(result.getRole()))
                .count());
        assertEquals(contracts.getFixtureIds(),
                contracts.getPassedFixtureIds(),
                () -> contracts.getFailures().toString());
        assertEquals(expectedContractsFixtures,
                contracts.getPassedFixtureIds().size());
        assertTrue(contracts.getFailedFixtureIds().isEmpty());
        assertTrue(contracts.getFailures().isEmpty());
        assertEquals(0, contracts.getSkippedFixtureCount());
        assertTrue(contracts.isConformant());
    }

    @Test
    void shouldExposeExactPackageAndSpecificationBindingsInReleaseReport() {
        // given
        String expectedLanguageRegistry =
                "sha256:5c7a48fd3437182a2b6c43255c96e58c81e9872b4a3c150906b831812925a321";
        String expectedLanguageFixtures =
                "sha256:f323e169fc2e18d918686cf5cb8843dd6b8ce7e81fd9484e2921ce554b004695";
        String expectedContractsRegistry =
                RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY;
        String expectedContractsGas =
                "sha256:03219c42eb3696ef8727fe8ae226c8a5eb4a6126859ba744f571d892c409626a";
        String expectedContractsFixtures =
                "sha256:ff6ed64b9e41e9dd6436fd81893fbccf3a4895051d26ad5e1a9c2820c4e444b3";

        // when
        BlueReleaseConformanceReport release = exactReleaseReport();
        Map<String, Object> encoded =
                release.toMachineReadableMap();

        // then
        assertTrue(release.isConformant());
        assertEquals(BlueContractsConformanceReport
                        .RELEASE_PACKAGE_IDENTITY,
                nested(encoded, "release", "packageIdentity"));
        assertEquals(
                "sha256:7379f9a6dff5a58faa5f9c329f3d22d494efde0f6bb3f0258eeedf9dff7c5ab8",
                nested(encoded, "release", "contractsReleaseIdentity"));
        assertEquals(BlueContractsConformanceReport
                        .CONTRACTS_FIXTURE_PACKAGE_IDENTITY,
                nested(encoded, "packages", "contractsFixtures"));
        assertEquals(
                expectedLanguageRegistry,
                nested(encoded, "packages", "languageRegistry"));
        assertEquals(
                expectedLanguageFixtures,
                nested(encoded, "packages", "languageFixtures"));
        assertEquals(
                expectedContractsRegistry,
                nested(encoded, "packages", "contractsRegistry"));
        assertEquals(
                expectedContractsGas,
                nested(encoded, "packages", "contractsGas"));
        assertEquals(
                expectedContractsFixtures,
                nested(encoded, "packages", "contractsFixtures"));
        assertEquals(
                "sha256:7379f9a6dff5a58faa5f9c329f3d22d494efde0f6bb3f0258eeedf9dff7c5ab8",
                nested(encoded, "packages", "contractsRelease"));
        assertEquals(
                BlueContractsConformanceReport
                        .LANGUAGE_SPECIFICATION_SHA256,
                nested(encoded, "specifications", "languageSha256"));
        assertEquals(
                BlueContractsConformanceReport
                        .CONTRACTS_SPECIFICATION_SHA256,
                nested(encoded, "specifications", "contractsSha256"));
    }

    @Test
    void shouldExposeCompletePassingRowsInMachineReadableReleaseReport() {
        // given
        int expectedReleaseFixtures =
                BlueReleaseConformanceReport.TOTAL_FIXTURE_COUNT;

        // when
        Map<String, Object> encoded =
                exactReleaseReport().toMachineReadableMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures =
                (List<Map<String, Object>>) encoded.get("fixtures");
        Set<Object> keys = fixtures.stream()
                .map(fixture -> fixture.get("resultKey"))
                .collect(Collectors.toCollection(HashSet::new));

        // then
        assertEquals(expectedReleaseFixtures,
                nested(encoded, "summary", "total"));
        assertEquals(expectedReleaseFixtures,
                nested(encoded, "summary", "passed"));
        assertEquals(0, nested(encoded, "summary", "failed"));
        assertEquals(0, nested(encoded, "summary", "skipped"));
        assertEquals(true,
                nested(encoded, "summary", "conformant"));
        assertEquals(expectedReleaseFixtures, fixtures.size());
        assertEquals(expectedReleaseFixtures, keys.size());
        assertEquals(expectedReleaseFixtures, fixtures.stream()
                .filter(fixture ->
                        "PASS".equals(fixture.get("status")))
                .count());
        assertTrue(fixtures.stream()
                .noneMatch(fixture ->
                        "FAIL".equals(fixture.get("status"))));
    }

    @Test
    void shouldSerializeCompleteReleaseSummaryToJson()
            throws Exception {
        // given
        int expectedReleaseFixtures =
                BlueReleaseConformanceReport.TOTAL_FIXTURE_COUNT;

        // when
        JsonNode json = JSON_MAPPER.readTree(
                exactReleaseReport().toMachineReadableJson());

        // then
        assertEquals(expectedReleaseFixtures,
                json.path("fixtures").size());
        assertEquals(expectedReleaseFixtures,
                json.path("summary").path("passed").asInt());
        assertEquals(0,
                json.path("summary").path("failed").asInt());
    }

    @Test
    void shouldVerifyStaticReportExposesExactBindingsAndNeverClaimsUnrunPasses() {
        // given
        String expectedReleaseName =
                "blue-language-contracts-embedded-modules-collection-paths";

        // when
        BlueContractsConformanceReport report =
                ContractsConformanceSuite.unexecutedReport();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures =
                (List<Map<String, Object>>) report
                        .toMachineReadableMap().get("fixtures");

        // then
        assertEquals(expectedReleaseName, report.getReleaseName());
        assertEquals(
                "sha256:28b08b722e924c4d0da5b5352f37ad17fad5c425ce8281260689109162a68aea",
                report.getReleasePackageIdentity());
        assertEquals(
                "sha256:ff6ed64b9e41e9dd6436fd81893fbccf3a4895051d26ad5e1a9c2820c4e444b3",
                report.getFixturePackageIdentity());
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
        assertEquals(
                "77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144",
                nested(report.toMachineReadableMap(),
                        "language", "specificationSha256"));
        assertEquals(
                "e91381c970859a6bafecdd99e46f5115ba033bf0534be84bbd5582531e9e347f",
                nested(report.toMachineReadableMap(),
                        "contracts", "specificationSha256"));

        assertEquals(295, fixtures.size());
        assertTrue(fixtures.stream().allMatch(
                result -> "FAIL".equals(result.get("status"))
                        && "HarnessDidNotRunFixture".equals(
                        result.get("errorCategory"))));
    }

    @Test
    void shouldVerifyLanguageSpecificationCopiesBindAuthoritativeRegistryIdentity()
            throws Exception {
        // given
        String runtimeSpecification = readUtf8Resource(
                BlueContractsConformanceReport.LANGUAGE_SPECIFICATION_RESOURCE);
        String conformanceSpecification =
                readUtf8Resource("language/1.0/spec.md");
        String registryManifest =
                readUtf8Resource("registry/blue-language-1.0/manifest.yaml");
        String fixtureManifest = readUtf8Resource(
                "blue-language-1.0/fixtures/manifest.yaml");
        String releaseManifest = readUtf8Resource(
                BlueContractsConformanceReport.RELEASE_MANIFEST_RESOURCE);

        // when
        BlueContractsConformanceReport.validateReleaseBindings();
        String manifestIdentity =
                requiredYamlIdentity(
                        "packageIdentity",
                        registryManifest);

        // then
        assertEquals(runtimeSpecification, conformanceSpecification,
                "Runtime and conformance specification copies must be exact");
        assertEquals(
                BlueContractsConformanceReport
                        .LANGUAGE_REGISTRY_PACKAGE_IDENTITY,
                manifestIdentity);
        assertEquals(manifestIdentity, requiredMatch(
                SPECIFICATION_REGISTRY_IDENTITY, runtimeSpecification));
        assertEquals(manifestIdentity, requiredYamlIdentity(
                "registryPackageIdentity", fixtureManifest));
        assertEquals(
                BlueContractsConformanceReport
                        .LANGUAGE_FIXTURE_PACKAGE_IDENTITY,
                requiredYamlIdentity("packageIdentity", fixtureManifest));
        assertEquals(manifestIdentity, requiredYamlIdentity(
                "languageRegistryPackageIdentity", releaseManifest));
        assertEquals(
                BlueContractsConformanceReport
                        .LANGUAGE_FIXTURE_PACKAGE_IDENTITY,
                requiredYamlIdentity(
                        "languageFixturePackageIdentity", releaseManifest));
        assertEquals(
                BlueContractsConformanceReport.RELEASE_PACKAGE_IDENTITY,
                requiredYamlIdentity("packageIdentity", releaseManifest));
    }

    @Test
    void shouldVerifyEveryBundledLanguageRegistryBindingUsesTheAuthoritativeIdentity()
            throws Exception {
        // given
        Path repository = RepositoryLayout.repositoryRoot();
        List<Path> roots = new ArrayList<>();
        roots.add(repository.resolve("README.md"));
        roots.add(repository.resolve("CHANGELOG.md"));
        roots.add(repository.resolve("docs"));
        roots.add(repository.resolve("src/test/resources"));
        roots.addAll(RepositoryLayout.productionResourceRoots());
        roots.addAll(RepositoryLayout.productionJavaRoots());
        List<String> bindings = new ArrayList<>();

        // when
        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths
                        .filter(Files::isRegularFile)
                        .filter(BlueContractsConformanceReportTest
                                ::isIdentityTextFile)
                        .collect(Collectors.toList())) {
                    String relative =
                            repository.relativize(path)
                                    .toString()
                                    .replace('\\', '/');
                    if (isHistoricalIdentityRecord(relative)) {
                        continue;
                    }
                    String content = new String(
                            Files.readAllBytes(path),
                            StandardCharsets.UTF_8);
                    collectBindings(
                            bindings,
                            relative,
                            content,
                            SPECIFICATION_REGISTRY_IDENTITY);
                    collectBindings(
                            bindings,
                            relative,
                            content,
                            RELEASE_LANGUAGE_REGISTRY_IDENTITY);
                    collectBindings(
                            bindings,
                            relative,
                            content,
                            MACHINE_LANGUAGE_REGISTRY_IDENTITY);
                    collectBindings(
                            bindings,
                            relative,
                            content,
                            README_LANGUAGE_REGISTRY_IDENTITY);
                    if (relative.endsWith(
                            "BlueContractsConformanceReport.java")) {
                        collectBindings(
                                bindings,
                                relative,
                                content,
                                CONSTANT_LANGUAGE_REGISTRY_IDENTITY);
                    }
                    if (relative.endsWith(
                            "registry/blue-language-1.0/manifest.yaml")) {
                        bindings.add(
                                relative + "="
                                        + requiredYamlIdentity(
                                        "packageIdentity",
                                        content));
                    }
                    if (relative.endsWith(
                            "blue-language-1.0/fixtures/manifest.yaml")) {
                        bindings.add(
                                relative + "="
                                        + requiredYamlIdentity(
                                        "registryPackageIdentity",
                                        content));
                    }
                }
            }
        }
        String authoritative =
                BlueContractsConformanceReport
                        .LANGUAGE_REGISTRY_PACKAGE_IDENTITY;

        // then
        assertTrue(
                bindings.size() >= 6,
                () -> "Too few Language registry bindings were discovered: "
                        + bindings);
        assertTrue(
                bindings.stream().allMatch(
                        binding -> binding.endsWith(
                                "=" + authoritative)),
                () -> "Conflicting Language registry bindings: "
                        + bindings);
    }

    private static BlueReleaseConformanceReport exactReleaseReport() {
        return ExactReleaseReportHolder.REPORT;
    }

    private static final class ExactReleaseReportHolder {
        private static final BlueReleaseConformanceReport REPORT =
                new BlueReleaseConformanceReport(
                        BlueConformanceSuiteRunner.run(),
                        ContractsConformanceSuite.run());
    }

    private static boolean isIdentityTextFile(Path path) {
        String name = path.getFileName()
                .toString();
        return name.endsWith(".md")
                || name.endsWith(".yaml")
                || name.endsWith(".yml")
                || name.endsWith(".json")
                || name.endsWith(".java")
                || name.endsWith(".txt");
    }

    private static boolean isHistoricalIdentityRecord(String relative) {
        // This migration record deliberately preserves the identities of the
        // release it describes. It is evidence for an old-to-new transition,
        // not an active registry binding.
        return "docs/language-1.0-contracts-kernel-1.0-migration.md"
                .equals(relative);
    }

    private static void collectBindings(
            List<String> bindings,
            String source,
            String content,
            Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            bindings.add(source + "=" + matcher.group(1));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> map,
                                 String object,
                                 String field) {
        return ((Map<String, Object>) map.get(object)).get(field);
    }

    private static String readUtf8Resource(String resource)
            throws IOException {
        try (InputStream input =
                     BlueContractsConformanceReportTest.class
                             .getClassLoader()
                             .getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("Missing test resource: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String requiredMatch(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            throw new AssertionError(
                    "Required registry identity binding is missing");
        }
        return matcher.group(1);
    }

    private static String requiredYamlIdentity(String field, String yaml) {
        Pattern pattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(field)
                        + ":\\s+(sha256:[0-9a-f]{64})\\s*$");
        return requiredMatch(pattern, yaml);
    }

}
