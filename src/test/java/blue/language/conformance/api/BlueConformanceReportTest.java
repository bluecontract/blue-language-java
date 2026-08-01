package blue.language.conformance.api;

import blue.language.Blue;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueConformanceReportTest {
    @Test
    void shouldReportBlueLanguage10Version() {
        // given
        Blue blue = new Blue();

        // when
        String languageVersion = blue.languageVersion();

        // then
        assertEquals("1.0", languageVersion);
    }

    @Test
    void shouldExposeNoConformanceProfiles() {
        // given
        Method[] blueMethods = Blue.class.getMethods();
        Method[] reportMethods = BlueConformanceReport.class.getMethods();

        // when
        List<String> profileMethods = Stream.concat(
                        Arrays.stream(blueMethods),
                        Arrays.stream(reportMethods))
                .map(Method::getName)
                .filter(name -> name.toLowerCase().contains("profile"))
                .collect(Collectors.toList());

        // then
        assertTrue(profileMethods.isEmpty(), profileMethods.toString());
    }

    @Test
    void shouldLoadFixtureIdentityIntoConformanceReport() {
        // given
        // when
        BlueConformanceReport report =
                BlueConformanceSuiteRunner.unexecutedReport();
        String computedIdentity =
                BlueConformanceReport.computeFixturePackageIdentity();
        String reportedIdentity = report.getFixturePackageIdentity();
        boolean releaseGradeIdentity =
                report.isReleaseGradeFixtureIdentity();
        boolean fixtureFilesMatchIdentity =
                BlueConformanceReport.fixturePackageIdentityMatchesFixtureFiles();

        // then
        assertEquals(computedIdentity, reportedIdentity);
        assertEquals(BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY,
                reportedIdentity);
        assertEquals(
                "sha256:44465973c5c5a8c1e60712fc7970236015d9500e2e9e3fc904e364552ec74a55",
                reportedIdentity);
        assertEquals("blue-language-1.0-final-implementation-baseline",
                BlueConformanceReport.BLUE_SPEC_SOURCE);
        assertTrue(releaseGradeIdentity);
        assertTrue(fixtureFilesMatchIdentity);
    }

    @Test
    void shouldListPassedAndFailedFixtureIdsInConformanceReport() {
        // given
        List<String> fixtureIds =
                Arrays.asList("B_root_scalar", "B_root_list");
        List<String> passedFixtureIds =
                Collections.singletonList("B_root_scalar");
        List<String> failedFixtureIds =
                Collections.singletonList("B_root_list");
        Map<String, BlueFixtureCategory> fixtureCategories =
                Collections.singletonMap(
                        "B_root_scalar",
                        BlueFixtureCategory.BLUE_ID);

        // when
        BlueConformanceReport report = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "blue-language-1.0-fixtures:test",
                fixtureIds,
                passedFixtureIds,
                failedFixtureIds,
                fixtureCategories);

        // then
        assertEquals(passedFixtureIds, report.getPassedFixtureIds());
        assertEquals(failedFixtureIds, report.getFailedFixtureIds());
        assertTrue(report.getFailures().isEmpty());
    }

    @Test
    void shouldExposeDetailedFailureMetadataInConformanceReport() {
        // given
        BlueConformanceFailure failure = new BlueConformanceFailure(
                "B_bad",
                BlueFixtureCategory.BLUE_ID,
                "calculateBlueId",
                IllegalArgumentException.class.getName(),
                "bad fixture",
                BlueLanguageErrorCategory.InvalidBlueIdInput);
        // when
        BlueConformanceReport report = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50",
                Collections.singletonList("B_bad"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonMap("B_bad", BlueFixtureCategory.BLUE_ID),
                Collections.singletonList(failure));

        // then
        assertEquals(Collections.singletonList("B_bad"), report.getFailedFixtureIds());
        assertEquals("B_bad", report.getFailures().get(0).getFixtureId());
        assertEquals("calculateBlueId", report.getFailures().get(0).getOperation());
        assertEquals(IllegalArgumentException.class.getName(), report.getFailures().get(0).getExceptionClass());
        assertEquals(BlueLanguageErrorCategory.InvalidBlueIdInput, report.getFailures().get(0).getErrorCategory());
    }

    @Test
    void shouldLoadFixtureIdsAndCategoriesIntoConformanceReport() {
        // given
        // when
        BlueConformanceReport report =
                BlueConformanceSuiteRunner.unexecutedReport();

        // then
        assertTrue(report.getFixtureIds().contains("B_root_scalar"));
        assertTrue(report.getFixtureIds().contains("F_provider_wrong_blueid_rejected"));
        assertEquals(BlueFixtureCategory.BLUE_ID, report.getFixtureCategories().get("B_root_scalar"));
        assertEquals(BlueFixtureCategory.PROVIDER, report.getFixtureCategories().get("F_provider_wrong_blueid_rejected"));
    }

    @Test
    void shouldPopulatePassedAndFailedFixtureIdsWhenRunningConformanceSuite() {
        // given
        // when
        BlueConformanceReport report = BlueConformanceSuiteRunner.run();

        // then
        assertEquals(report.getFixtureIds(), report.getPassedFixtureIds(), report.getFailures().toString());
        assertTrue(report.getFailedFixtureIds().isEmpty());
        assertTrue(report.getFailures().isEmpty());
        assertTrue(report.hasRequiredFixtureCoverage());
    }

    @Test
    void shouldNotMarkFixturesPassedInStaticConformanceReport() {
        // given
        // when
        BlueConformanceReport report =
                BlueConformanceSuiteRunner.unexecutedReport();

        // then
        assertTrue(report.getPassedFixtureIds().isEmpty());
        assertTrue(report.getFailedFixtureIds().isEmpty());
        assertTrue(report.getFailures().isEmpty());
        assertTrue(report.hasRequiredFixtureCoverage());
    }

    @Test
    void shouldNotTreatFixtureCategoriesAsConformanceProfiles() {
        // given
        String blueIdLabel = "BlueId";
        String resolutionLabel = "Resolution";

        // when
        BlueFixtureCategory blueIdCategory =
                BlueFixtureCategory.fromLabel(blueIdLabel);
        BlueFixtureCategory resolutionCategory =
                BlueFixtureCategory.fromLabel(resolutionLabel);
        String reportedBlueIdLabel =
                BlueFixtureCategory.BLUE_ID.getLabel();

        // then
        assertEquals(BlueFixtureCategory.BLUE_ID, blueIdCategory);
        assertEquals(BlueFixtureCategory.RESOLUTION,
                resolutionCategory);
        assertEquals(blueIdLabel, reportedBlueIdLabel);
    }

    @Test
    void shouldRejectInvalidReleaseGradeFixtureIdentities() {
        // given
        List<String> invalidIdentities = Arrays.asList(
                "blue-language-1.0-fixtures:local-dev",
                "blue-language-1.0-fixtures:pending",
                "blue-language-1.0-fixtures:unavailable",
                "",
                null,
                "sha256:bad");
        List<Boolean> expectedInvalidResults =
                Arrays.asList(false, false, false, false, false, false);
        String sha256Identity =
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50";
        String blueIdIdentity = "blueId:B123";

        // when
        List<Boolean> invalidResults = invalidIdentities.stream()
                .map(BlueConformanceReport::isReleaseGradeFixtureIdentity)
                .collect(Collectors.toList());
        boolean sha256IdentityAccepted =
                BlueConformanceReport.isReleaseGradeFixtureIdentity(
                        sha256Identity);
        boolean blueIdIdentityAccepted =
                BlueConformanceReport.isReleaseGradeFixtureIdentity(
                        blueIdIdentity);

        // then
        assertEquals(expectedInvalidResults, invalidResults);
        assertTrue(sha256IdentityAccepted);
        assertTrue(blueIdIdentityAccepted);
    }

    @Test
    void shouldCheckAllLanguageFixturesForRequiredCoverage() {
        // given
        // when
        BlueConformanceReport report =
                BlueConformanceSuiteRunner.unexecutedReport();

        // then
        assertTrue(report.hasRequiredFixtureCoverage());
    }

    @Test
    void shouldPassRequiredCoverageOnlyWhenAllLanguageFixturesArePresent() {
        // given
        Map<String, BlueFixtureCategory> categories = new LinkedHashMap<>();
        for (String id : BlueConformanceReport.requiredFixtureIdsForBlueLanguage10()) {
            categories.put(id, BlueFixtureCategory.BLUE_ID);
        }
        // when
        BlueConformanceReport complete = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "blue-language-1.0-fixtures:B123",
                Arrays.asList(categories.keySet().toArray(new String[0])),
                Collections.emptyList(),
                Collections.emptyList(),
                categories);

        // then
        assertTrue(complete.hasRequiredFixtureCoverage());
    }

    @Test
    void shouldRejectExtraOrMissingFixturesFromExactRequiredSet() {
        // given
        Map<String, BlueFixtureCategory> categories = new LinkedHashMap<>();
        for (String id : BlueConformanceReport.requiredFixtureIdsForBlueLanguage10()) {
            categories.put(id, BlueFixtureCategory.BLUE_ID);
        }
        // when
        BlueConformanceReport exact = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50",
                Arrays.asList(categories.keySet().toArray(new String[0])),
                Collections.emptyList(),
                Collections.emptyList(),
                categories);
        List<String> withExtra = new java.util.ArrayList<>(exact.getFixtureIds());
        withExtra.add("EXTRA_fixture");
        BlueConformanceReport extra = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50",
                withExtra,
                Collections.emptyList(),
                Collections.emptyList(),
                categories);
        BlueConformanceReport missing = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50",
                Collections.singletonList(exact.getFixtureIds().get(0)),
                Collections.emptyList(),
                Collections.emptyList(),
                categories);

        // then
        assertTrue(exact.hasRequiredFixtureCoverage());
        assertTrue(exact.hasExactRequiredFixtureSet());
        assertTrue(extra.hasRequiredFixtureCoverage());
        assertFalse(extra.hasExactRequiredFixtureSet());
        assertFalse(missing.hasRequiredFixtureCoverage());
        assertFalse(missing.hasExactRequiredFixtureSet());
    }

    @Test
    void shouldAlignConformanceManifestWithRequiredFixtureSet() throws Exception {
        // given
        String fixtureResourcePath = "blue-language-1.0/fixtures";

        // when
        URL resource = getClass().getClassLoader()
                .getResource(fixtureResourcePath);
        Path fixtureRoot = Paths.get(resource.toURI());
        com.fasterxml.jackson.databind.JsonNode manifest = YAML_MAPPER.readTree(
                new String(Files.readAllBytes(fixtureRoot.resolve("manifest.yaml"))));
        Set<String> manifestIds = new LinkedHashSet<>();
        Set<Path> manifestPaths = new LinkedHashSet<>();
        List<String> manifestViolations = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode file : manifest.get("files")) {
            if (!file.hasNonNull("path")) {
                manifestViolations.add("missing path: " + file);
            }
            if (!file.hasNonNull("role")) {
                manifestViolations.add("missing role: " + file);
            }
            if (!file.hasNonNull("sha256")) {
                manifestViolations.add("missing sha256: " + file);
            }
            if (!file.hasNonNull("bytes")) {
                manifestViolations.add("missing bytes: " + file);
            }
            Path fixturePath = fixtureRoot.resolve(file.get("path").asText()).normalize();
            if (!Files.isRegularFile(fixturePath)) {
                manifestViolations.add("missing fixture file: " + fixturePath);
            }
            manifestPaths.add(fixturePath.toAbsolutePath().normalize());
            if (!"behavior-fixture".equals(file.get("role").asText())) {
                if (!"support".equals(file.get("role").asText())) {
                    manifestViolations.add(
                            "unexpected role: " + file.get("role").asText());
                }
                continue;
            }

            com.fasterxml.jackson.databind.JsonNode fixtureContent = YAML_MAPPER.readTree(
                    new String(Files.readAllBytes(fixturePath)));
            if (fixtureContent.has("profile")) {
                manifestViolations.add(
                        "fixture metadata uses profile: " + fixturePath);
            }
            if (!fixtureContent.hasNonNull("id")) {
                manifestViolations.add("fixture missing id: " + fixturePath);
            }
            if (!fixtureContent.hasNonNull("category")) {
                manifestViolations.add(
                        "fixture missing category: " + fixturePath);
            }
            if (!manifestIds.add(fixtureContent.get("id").asText())) {
                manifestViolations.add(
                        "duplicate fixture id: "
                                + fixtureContent.get("id").asText());
            }
            BlueFixtureCategory.fromLabel(fixtureContent.get("category").asText());
            if (!fixtureContent.hasNonNull("operation")) {
                manifestViolations.add(
                        "fixture missing operation: " + fixturePath);
            } else if (!BlueConformanceSuiteRunner.knownOperations()
                    .contains(fixtureContent.get("operation").asText())) {
                manifestViolations.add(
                        "unknown fixture operation in "
                                + fixturePath + ": "
                                + fixtureContent.get("operation").asText());
            }
            BlueConformanceSuiteRunner.validateFixtureMetadataForTest(fixtureContent);
        }
        List<Path> fixtureFiles;
        try (Stream<Path> paths = Files.walk(fixtureRoot)) {
            fixtureFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !"manifest.yaml".equals(path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().normalize())
                    .collect(Collectors.toList());
        }
        boolean fixtureIdentityMatches =
                BlueConformanceReport
                        .fixturePackageIdentityMatchesFixtureFiles();

        // then
        assertTrue(resource != null);
        assertEquals(BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY,
                manifest.get("packageIdentity").asText());
        assertEquals(153, manifest.get("behaviorFixtureCount").asInt());
        assertTrue(manifestViolations.isEmpty(),
                manifestViolations.toString());
        assertEquals(BlueConformanceReport.requiredFixtureIdsForBlueLanguage10(), manifestIds);
        assertTrue(fixtureIdentityMatches);
        assertEquals(manifestPaths, new LinkedHashSet<>(fixtureFiles));
    }

    @Test
    void shouldIncludeOneExactResultPerLanguageFixtureInMachineReadableReport() {
        // given
        BlueConformanceReport report = BlueConformanceSuiteRunner.run();
        // when
        Map<String, Object> encoded = report.toMachineReadableMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
                (List<Map<String, Object>>) encoded.get("results");

        // then
        assertEquals(BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY,
                encoded.get("fixturePackageIdentity"));
        assertEquals("sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e",
                encoded.get("registryPackageIdentity"));
        assertEquals(153, encoded.get("fixtureCount"));
        assertEquals(153, results.size());
        assertEquals(153, results.stream()
                .map(result -> result.get("id"))
                .collect(Collectors.toSet()).size());
        assertTrue(results.stream().allMatch(result ->
                "PASS".equals(result.get("status"))
                        || "FAIL".equals(result.get("status"))));
    }

    @Test
    void shouldNotContainTodoDescriptionsInMainResources() throws Exception {
        // given
        Path resourceRoot = Paths.get("src/main/resources");
        // when
        try (Stream<Path> paths = Files.walk(resourceRoot)) {
            List<Path> incomplete = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            String content = new String(Files.readAllBytes(path));
                            return content.contains("TODO")
                                    || content.contains("description: This transformation replaces");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
            // then
            assertEquals(Collections.emptyList(), incomplete);
        }
    }

    @Test
    void shouldResolveReadmeLinksToExistingFiles() throws Exception {
        // given
        Path readme = Paths.get("README.md");
        String content = new String(Files.readAllBytes(readme));
        Matcher matcher = Pattern.compile("\\[[^\\]]+]\\((docs/[^)]+\\.md)\\)").matcher(content);
        // when
        List<String> missingTargets = new java.util.ArrayList<>();
        while (matcher.find()) {
            Path target = readme.getParent() == null
                    ? Paths.get(matcher.group(1))
                    : readme.getParent().resolve(matcher.group(1));
            if (!Files.isRegularFile(target)) {
                missingTargets.add(matcher.group(1));
            }
        }

        // then
        assertTrue(missingTargets.isEmpty(),
                "README link targets are missing: " + missingTargets);
    }
}
