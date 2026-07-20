package blue.language;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
    private static final Set<String> KNOWN_FIXTURE_OPERATIONS = new HashSet<>(Arrays.asList(
            "parseSource",
            "parseBlueIdInput",
            "calculateBlueId",
            "calculateCircularSetBlueIds",
            "preprocess",
            "resolve",
            "scenario",
            "canonicalize",
            "assertMinimizedOverlayRoundTrip",
            "calculateContentBlueId",
            "calculateSemanticBlueId",
            "expand",
            "collapse",
            "assertSameNodeBlueId",
            "assertViewPath",
            "registryNodeHashesToPublishedBlueId",
            "changingRegistryDescriptionChangesBlueId",
            "lintPublishableDocumentation"
    ));

    @Test
    void languageVersionIsBlueLanguage10() {
        assertEquals("1.0", new Blue().languageVersion());
    }

    @Test
    void conformanceReportHasNoProfiles() {
        for (Method method : Blue.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("profile"));
        }
        for (Method method : BlueConformanceReport.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("profile"));
        }
    }

    @Test
    void conformanceReportLoadsFixtureIdentity() {
        Blue blue = new Blue();
        BlueConformanceReport report = blue.conformanceReport();

        assertEquals(BlueConformanceReport.computeFixturePackageIdentity(), report.getFixturePackageIdentity());
        assertEquals(BlueConformanceReport.CANDIDATE_FIXTURE_PACKAGE_IDENTITY,
                report.getFixturePackageIdentity());
        assertEquals("feat/conformance-fixture-expansion@07814f5",
                BlueConformanceReport.CANDIDATE_BLUE_SPEC_SOURCE);
        assertTrue(report.isReleaseGradeFixtureIdentity());
        assertTrue(BlueConformanceReport.fixturePackageIdentityMatchesFixtureFiles());
    }

    @Test
    void conformanceReportListsPassedAndFailedFixtureIds() {
        BlueConformanceReport report = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "blue-language-1.0-fixtures:test",
                Arrays.asList("B_root_scalar", "B_root_list"),
                Collections.singletonList("B_root_scalar"),
                Collections.singletonList("B_root_list"),
                Collections.singletonMap("B_root_scalar", BlueFixtureCategory.BLUE_ID));

        assertEquals(Collections.singletonList("B_root_scalar"), report.getPassedFixtureIds());
        assertEquals(Collections.singletonList("B_root_list"), report.getFailedFixtureIds());
        assertTrue(report.getFailures().isEmpty());
    }

    @Test
    void conformanceReportExposesDetailedFailureMetadata() {
        BlueConformanceFailure failure = new BlueConformanceFailure(
                "B_bad",
                BlueFixtureCategory.BLUE_ID,
                "calculateBlueId",
                IllegalArgumentException.class.getName(),
                "bad fixture",
                BlueLanguageErrorCategory.InvalidBlueIdInput);
        BlueConformanceReport report = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50",
                Collections.singletonList("B_bad"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonMap("B_bad", BlueFixtureCategory.BLUE_ID),
                Collections.singletonList(failure));

        assertEquals(Collections.singletonList("B_bad"), report.getFailedFixtureIds());
        assertEquals("B_bad", report.getFailures().get(0).getFixtureId());
        assertEquals("calculateBlueId", report.getFailures().get(0).getOperation());
        assertEquals(IllegalArgumentException.class.getName(), report.getFailures().get(0).getExceptionClass());
        assertEquals(BlueLanguageErrorCategory.InvalidBlueIdInput, report.getFailures().get(0).getErrorCategory());
    }

    @Test
    void conformanceReportLoadsFixtureIdsAndCategories() {
        BlueConformanceReport report = new Blue().conformanceReport();

        assertTrue(report.getFixtureIds().contains("B_root_scalar"));
        assertTrue(report.getFixtureIds().contains("F_provider_wrong_blueid_rejected"));
        assertEquals(BlueFixtureCategory.BLUE_ID, report.getFixtureCategories().get("B_root_scalar"));
        assertEquals(BlueFixtureCategory.PROVIDER, report.getFixtureCategories().get("F_provider_wrong_blueid_rejected"));
    }

    @Test
    void runConformanceSuitePopulatesPassedAndFailedFixtureIds() {
        BlueConformanceReport report = new Blue().runConformanceSuite();

        assertEquals(report.getFixtureIds(), report.getPassedFixtureIds(), report.getFailures().toString());
        assertTrue(report.getFailedFixtureIds().isEmpty());
        assertTrue(report.getFailures().isEmpty());
        assertTrue(report.hasRequiredFixtureCoverage());
    }

    @Test
    void staticConformanceReportDoesNotPretendFixturesPassed() {
        BlueConformanceReport report = new Blue().conformanceReport();

        assertTrue(report.getPassedFixtureIds().isEmpty());
        assertTrue(report.getFailedFixtureIds().isEmpty());
        assertTrue(report.getFailures().isEmpty());
        assertTrue(report.hasRequiredFixtureCoverage());
    }

    @Test
    void fixtureCategoriesAreNotConformanceProfiles() {
        assertEquals(BlueFixtureCategory.BLUE_ID, BlueFixtureCategory.fromLabel("BlueId"));
        assertEquals(BlueFixtureCategory.RESOLUTION, BlueFixtureCategory.fromLabel("Resolution"));
        assertEquals("BlueId", BlueFixtureCategory.BLUE_ID.getLabel());
    }

    @Test
    void releaseGradeFixtureIdentityRejectsLocalDevPendingUnavailableAndBlank() {
        assertFalse(BlueConformanceReport.isReleaseGradeFixtureIdentity("blue-language-1.0-fixtures:local-dev"));
        assertFalse(BlueConformanceReport.isReleaseGradeFixtureIdentity("blue-language-1.0-fixtures:pending"));
        assertFalse(BlueConformanceReport.isReleaseGradeFixtureIdentity("blue-language-1.0-fixtures:unavailable"));
        assertFalse(BlueConformanceReport.isReleaseGradeFixtureIdentity(""));
        assertFalse(BlueConformanceReport.isReleaseGradeFixtureIdentity(null));
        assertFalse(BlueConformanceReport.isReleaseGradeFixtureIdentity("sha256:bad"));
        assertTrue(BlueConformanceReport.isReleaseGradeFixtureIdentity(
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50"));
        assertTrue(BlueConformanceReport.isReleaseGradeFixtureIdentity("blueId:B123"));
    }

    @Test
    void requiredFixtureCoverageChecksAllLanguageFixtures() {
        BlueConformanceReport report = new Blue().conformanceReport();

        assertTrue(report.hasRequiredFixtureCoverage());
    }

    @Test
    void requiredFixtureCoveragePassesOnlyWhenAllLanguageFixturesArePresent() {
        Map<String, BlueFixtureCategory> categories = new LinkedHashMap<>();
        for (String id : BlueConformanceReport.requiredFixtureIdsForBlueLanguage10()) {
            categories.put(id, BlueFixtureCategory.BLUE_ID);
        }
        BlueConformanceReport complete = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "blue-language-1.0-fixtures:B123",
                Arrays.asList(categories.keySet().toArray(new String[0])),
                Collections.emptyList(),
                Collections.emptyList(),
                categories);

        assertTrue(complete.hasRequiredFixtureCoverage());
    }

    @Test
    void exactRequiredFixtureSetRejectsExtraOrMissing() {
        Map<String, BlueFixtureCategory> categories = new LinkedHashMap<>();
        for (String id : BlueConformanceReport.requiredFixtureIdsForBlueLanguage10()) {
            categories.put(id, BlueFixtureCategory.BLUE_ID);
        }
        BlueConformanceReport exact = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50",
                Arrays.asList(categories.keySet().toArray(new String[0])),
                Collections.emptyList(),
                Collections.emptyList(),
                categories);

        assertTrue(exact.hasRequiredFixtureCoverage());
        assertTrue(exact.hasExactRequiredFixtureSet());

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
        assertTrue(extra.hasRequiredFixtureCoverage());
        assertFalse(extra.hasExactRequiredFixtureSet());

        BlueConformanceReport missing = new BlueConformanceReport(
                "1.0",
                Collections.emptyMap(),
                "sha256:e579c14256b470ef5c987c282c760dff8865d68ecd55bce0dc1bbdb5cdb19a50",
                Collections.singletonList(exact.getFixtureIds().get(0)),
                Collections.emptyList(),
                Collections.emptyList(),
                categories);
        assertFalse(missing.hasRequiredFixtureCoverage());
        assertFalse(missing.hasExactRequiredFixtureSet());
    }

    @Test
    void conformanceManifestAndRequiredFixtureSetAreAligned() throws Exception {
        URL resource = getClass().getClassLoader().getResource("blue-language-1.0/fixtures");
        assertTrue(resource != null);
        Path fixtureRoot = Paths.get(resource.toURI());
        com.fasterxml.jackson.databind.JsonNode manifest = YAML_MAPPER.readTree(
                new String(Files.readAllBytes(fixtureRoot.resolve("manifest.yaml"))));
        Set<String> manifestIds = new LinkedHashSet<>();
        Set<Path> manifestPaths = new LinkedHashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode fixture : manifest.get("fixtures")) {
            assertFalse(fixture.has("profile"));
            assertTrue(fixture.hasNonNull("id"));
            assertTrue(fixture.hasNonNull("category"));
            assertTrue(fixture.hasNonNull("path"));
            BlueFixtureCategory.fromLabel(fixture.get("category").asText());
            assertTrue(manifestIds.add(fixture.get("id").asText()), "Duplicate fixture id: " + fixture.get("id").asText());
            Path fixturePath = fixtureRoot.resolve(fixture.get("path").asText()).normalize();
            assertTrue(Files.isRegularFile(fixturePath), "Missing fixture file: " + fixturePath);
            manifestPaths.add(fixturePath.toAbsolutePath().normalize());

            com.fasterxml.jackson.databind.JsonNode fixtureContent = YAML_MAPPER.readTree(
                    new String(Files.readAllBytes(fixturePath)));
            assertFalse(fixtureContent.has("profile"), "Fixture metadata must use category, not profile: " + fixturePath);
            assertTrue(fixtureContent.hasNonNull("id"), "Fixture missing id: " + fixturePath);
            assertTrue(fixtureContent.hasNonNull("category"), "Fixture missing category: " + fixturePath);
            assertEquals(fixture.get("id").asText(), fixtureContent.get("id").asText(), "Fixture id mismatch: " + fixturePath);
            assertEquals(
                    BlueFixtureCategory.fromLabel(fixture.get("category").asText()),
                    BlueFixtureCategory.fromLabel(fixtureContent.get("category").asText()),
                    "Fixture category mismatch: " + fixturePath);
            assertTrue(fixtureContent.hasNonNull("operation"), "Fixture missing operation: " + fixturePath);
            assertTrue(KNOWN_FIXTURE_OPERATIONS.contains(fixtureContent.get("operation").asText()),
                    "Unknown fixture operation in " + fixturePath + ": " + fixtureContent.get("operation").asText());
        }

        assertEquals(BlueConformanceReport.requiredFixtureIdsForBlueLanguage10(), manifestIds);

        List<Path> fixtureFiles;
        try (Stream<Path> paths = Files.walk(fixtureRoot)) {
            fixtureFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .filter(path -> !"manifest.yaml".equals(path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().normalize())
                    .collect(Collectors.toList());
        }
        assertEquals(new HashSet<>(manifestPaths), new HashSet<>(fixtureFiles));
    }

    @Test
    void mainResourcesDoNotContainTodoDescriptions() throws Exception {
        Path resourceRoot = Paths.get("src/main/resources");
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
            assertEquals(Collections.emptyList(), incomplete);
        }
    }

    @Test
    void readmeLinksPointToExistingFiles() throws Exception {
        Path readme = Paths.get("README.md");
        String content = new String(Files.readAllBytes(readme));
        Matcher matcher = Pattern.compile("\\[[^\\]]+]\\((docs/[^)]+\\.md)\\)").matcher(content);
        while (matcher.find()) {
            Path target = readme.getParent() == null
                    ? Paths.get(matcher.group(1))
                    : readme.getParent().resolve(matcher.group(1));
            assertTrue(Files.isRegularFile(target), "README link target is missing: " + matcher.group(1));
        }
    }
}
