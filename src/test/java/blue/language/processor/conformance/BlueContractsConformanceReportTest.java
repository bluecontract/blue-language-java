package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.BlueContractsConformanceReport;
import blue.language.BlueReleaseConformanceReport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceReportTest {

    private static final Pattern SPECIFICATION_REGISTRY_IDENTITY = Pattern.compile(
            "(?s)The canonical core-registry package identity bound by this "
                    + "fixture package is:\\s*```text\\s*"
                    + "(sha256:[0-9a-f]{64})\\s*```");
    private static final Pattern RELEASE_LANGUAGE_REGISTRY_IDENTITY =
            Pattern.compile(
                    "(?m)^\\s*languageRegistryPackage:\\s*"
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
        int expectedLanguageFixtures = 128;

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
        int expectedContractsFixtures = 140;
        long expectedBehaviorFixtures = 82L;
        long expectedGasFixtures = 58L;

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
                "sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e";
        String expectedLanguageFixtures =
                "sha256:267145c335c26e5a27121c31986ff53cc630a2ce1755aad97c376ef234560dd5";
        String expectedContractsRegistry =
                "sha256:67ce3101449c5bca9e6093b081da239d5d699fdc02182a058d3ad795c6c6120b";
        String expectedContractsGas =
                "sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5";
        String expectedContractsFixtures =
                "sha256:de65cf1ba53e5408f804513691434102b41cb33a95cbf8412ae890d8e28ad982";

        // when
        BlueReleaseConformanceReport release = exactReleaseReport();
        Map<String, Object> encoded =
                release.toMachineReadableMap();

        // then
        assertTrue(release.isConformant());
        assertEquals(BlueContractsConformanceReport
                        .RELEASE_PACKAGE_IDENTITY,
                nested(encoded, "release", "packageIdentity"));
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
        int expectedReleaseFixtures = 268;

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
        int expectedReleaseFixtures = 268;

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
                "blue-language-1.0-contracts-1.0-bex-2.0-coordination-1.0-final-implementation-baseline";

        // when
        BlueContractsConformanceReport report =
                new Blue().contractsConformanceReport();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures =
                (List<Map<String, Object>>) report
                        .toMachineReadableMap().get("fixtures");

        // then
        assertEquals(expectedReleaseName, report.getReleaseName());
        assertEquals(
                "sha256:de13521d2abf23fd3e3084aa6d754591c9b2f97b91176142287bc3d7456350d3",
                report.getReleasePackageIdentity());
        assertEquals(
                "sha256:de65cf1ba53e5408f804513691434102b41cb33a95cbf8412ae890d8e28ad982",
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
                "ac1ac47e10c91be82ebe45e2406f33ad5073cc3f3684bc1651704117b5008852",
                nested(report.toMachineReadableMap(),
                        "language", "specificationSha256"));
        assertEquals(
                "f99c17c700a1771b0cf308dfe7590b4001377a886a9b9eddb0d4a941647b8f83",
                nested(report.toMachineReadableMap(),
                        "contracts", "specificationSha256"));

        assertEquals(140, fixtures.size());
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
                "languageRegistryPackage", releaseManifest));
        assertEquals(
                BlueContractsConformanceReport
                        .LANGUAGE_FIXTURE_PACKAGE_IDENTITY,
                requiredYamlIdentity(
                        "languageFixturePackage", releaseManifest));
        assertEquals(
                BlueContractsConformanceReport.RELEASE_PACKAGE_IDENTITY,
                requiredYamlIdentity("packageIdentity", releaseManifest));
    }

    @Test
    void shouldVerifyEveryBundledLanguageRegistryBindingUsesTheAuthoritativeIdentity()
            throws Exception {
        // given
        Path repository = Paths.get("")
                .toAbsolutePath()
                .normalize();
        List<Path> roots = java.util.Arrays.asList(
                repository.resolve("README.md"),
                repository.resolve("CHANGELOG.md"),
                repository.resolve("docs"),
                repository.resolve("src/main/resources"),
                repository.resolve("src/test/resources"),
                repository.resolve(
                        "src/main/java/blue/language"));
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
                new Blue().runReleaseConformanceSuites();
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
