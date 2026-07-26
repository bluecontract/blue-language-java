package blue.language.conformance;

import blue.language.Blue;
import blue.language.BlueConformanceFailure;
import blue.language.BlueConformanceReport;
import blue.language.BlueConformanceSuiteRunner;
import blue.language.BlueFixtureCategory;
import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BlueLanguageConformanceFixtureTest {

    private static final String FIXTURE_PATH = "blue-language-1.0/fixtures";

    @TestFactory
    Stream<DynamicTest> blueLanguage10Fixtures() {
        BlueConformanceReport report = new Blue().runConformanceSuite();
        Map<String, BlueConformanceFailure> failuresById = report.getFailures().stream()
                .collect(Collectors.toMap(BlueConformanceFailure::getFixtureId, Function.identity()));

        return report.getFixtureIds().stream()
                .map(id -> DynamicTest.dynamicTest(id, () -> {
                    BlueConformanceFailure failure = failuresById.get(id);
                    if (failure != null) {
                        fail(failureMessage(failure));
                    }
                    assertTrue(report.getPassedFixtureIds().contains(id), "Fixture did not run: " + id);
                }));
    }

    @Test
    void fixtureWithoutExpectedOutputFailsMetadataValidation() {
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_missing_expected\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "input: 1\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void fixtureOperationCalculateBlueIdAllowingCyclicPlaceholdersIsRejected() {
        JsonNode spec = YAML_MAPPER.readTree(
                "id: C_placeholder_helper\n" +
                "category: Circular\n" +
                "operation: calculateBlueIdAllowingCyclicPlaceholders\n" +
                "input:\n" +
                "  blueId: this#0\n" +
                "expectedNodeBlueId: placeholder\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void fixtureTopLevelProfileFieldFails() {
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_profile_metadata\n" +
                "profile: BlueId\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "input: 1\n" +
                "expectedNodeBlueId: placeholder\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void fixtureInputMayContainOrdinaryProfileField() {
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_profile_data\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "input:\n" +
                "  profile: user\n" +
                "expectedNodeBlueId: placeholder\n");

        assertDoesNotThrow(() -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void fixtureExpectedOutputMayContainOrdinaryProfileField() {
        JsonNode spec = YAML_MAPPER.readTree(
                "id: R_profile_expected\n" +
                "category: Resolution\n" +
                "operation: preprocess\n" +
                "source:\n" +
                "  profile: user\n" +
                "expectedPreprocessed:\n" +
                "  profile: user\n");

        assertDoesNotThrow(() -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void fixtureProviderNodeMayContainOrdinaryProfileField() {
        JsonNode spec = YAML_MAPPER.readTree(
                "id: F_profile_provider\n" +
                "category: Provider\n" +
                "operation: calculateBlueId\n" +
                "provider:\n" +
                "  - requestedBlueId: placeholder\n" +
                "    node:\n" +
                "      profile: user\n" +
                "input: 1\n" +
                "expectedNodeBlueId: placeholder\n");

        assertDoesNotThrow(() -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void fixtureExpectedErrorCategoryIsValidated() {
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_error_category\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "expectError: true\n" +
                "expectedErrorCategory: InvalidBlueIdInput\n" +
                "input:\n" +
                "  type: Integer\n" +
                "  value: 1\n");

        assertDoesNotThrow(() -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void fixtureExpectedErrorCategoryRejectsUnknownCategory() {
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_error_category\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "expectError: true\n" +
                "expectedErrorCategory: NotACategory\n" +
                "input:\n" +
                "  type: Integer\n" +
                "  value: 1\n");

        assertThrows(IllegalArgumentException.class,
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));
    }

    @Test
    void mutatedExpectedIdentityValueAndOutcomeFailClosed() {
        JsonNode wrongIdentity = YAML_MAPPER.readTree(
                "id: B_mutated_identity\n"
                        + "category: BlueId\n"
                        + "operation: calculateBlueId\n"
                        + "input: value\n"
                        + "expectedNodeBlueId: \""
                        + "11111111111111111111111111111111111111111111\"\n");
        JsonNode wrongValue = YAML_MAPPER.readTree(
                "id: F_mutated_value\n"
                        + "category: LimitedExpansion\n"
                        + "operation: expandLimited\n"
                        + "source:\n"
                        + "  left: wanted\n"
                        + "limits:\n"
                        + "  demandedPaths: [/left]\n"
                        + "expectedOutcome: Established\n"
                        + "expectedValue: wrong\n");
        JsonNode wrongOutcome = YAML_MAPPER.readTree(
                "id: R_mutated_outcome\n"
                        + "category: LimitedResolution\n"
                        + "operation: semanticExists\n"
                        + "source: {}\n"
                        + "path: /missing\n"
                        + "expectedOutcome: Established\n");

        assertThrows(AssertionError.class,
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongIdentity));
        assertThrows(AssertionError.class,
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongValue));
        assertThrows(AssertionError.class,
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongOutcome));
    }

    @Test
    void languageErrorClassifierRecognizesRepresentativeCategories() {
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(new IllegalArgumentException("not a valid BlueId")));
        assertEquals(BlueLanguageErrorCategory.SchemaViolation,
                BlueLanguageErrorClassifier.classify(new IllegalArgumentException("schema keyword minLength applies to wrong kind")));
        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(new IllegalArgumentException("Provider returned content for abc but computed BlueId xyz")));
        assertEquals(BlueLanguageErrorCategory.ListControlViolation,
                BlueLanguageErrorClassifier.classify(new IllegalArgumentException("$pos list overlay is invalid")));
    }

    @Test
    void missingProviderMessagesClassifyAsProviderUnavailable() {
        String[] messages = {
                "No content found for blueId: missing",
                "No content found for $previous blueId: missing",
                "No content found for required blueId missing at path /subject."
        };

        for (String message : messages) {
            assertEquals(BlueLanguageErrorCategory.ProviderUnavailable,
                    BlueLanguageErrorClassifier.classify(new IllegalArgumentException(message)),
                    message);
        }
    }

    @Test
    void conformanceManifestIsAuthoritative() throws Exception {
        URL resource = getClass().getClassLoader().getResource(FIXTURE_PATH);
        assertTrue(resource != null);
        Path fixtureRoot = Paths.get(resource.toURI());
        JsonNode manifest = YAML_MAPPER.readTree(new String(Files.readAllBytes(fixtureRoot.resolve("manifest.yaml"))));
        JsonNode manifestFiles = manifest.get("files");
        assertTrue(manifestFiles != null && manifestFiles.isArray());
        assertEquals(BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY,
                requireNonNull(manifest, "packageIdentity").asText());
        assertEquals(125, requireNonNull(manifest, "behaviorFixtureCount").asInt());

        Set<String> fixtureIds = new LinkedHashSet<>();
        Set<Path> listedPaths = new HashSet<>();
        for (JsonNode entry : manifestFiles) {
            assertTrue(entry.hasNonNull("path"));
            assertTrue(entry.hasNonNull("role"));
            assertTrue(entry.hasNonNull("sha256"));
            assertTrue(entry.hasNonNull("bytes"));
            Path fixturePath = fixtureRoot.resolve(entry.get("path").asText()).normalize();
            assertTrue(Files.isRegularFile(fixturePath), "Missing fixture file: " + fixturePath);
            listedPaths.add(fixturePath.toAbsolutePath().normalize());
            if (!"behavior-fixture".equals(entry.get("role").asText())) {
                assertEquals("support", entry.get("role").asText());
                continue;
            }

            JsonNode fixture = YAML_MAPPER.readTree(new String(Files.readAllBytes(fixturePath)));
            assertFalse(fixture.has("profile"), "Fixture metadata must use category, not profile: " + fixturePath);
            String id = requireNonNull(fixture, "id").asText();
            assertTrue(fixtureIds.add(id), "Duplicate fixture id: " + id);
            BlueFixtureCategory.fromLabel(requireNonNull(fixture, "category").asText());
            assertTrue(BlueConformanceSuiteRunner.knownOperations().contains(requireNonNull(fixture, "operation").asText()),
                    "Unknown fixture operation in " + fixturePath);
            BlueConformanceSuiteRunner.validateFixtureMetadataForTest(fixture);
        }

        assertEquals(BlueConformanceReport.requiredFixtureIdsForBlueLanguage10(), fixtureIds);
        assertEquals(listedPaths, fixtureYamlFiles(fixtureRoot));
    }

    private Set<Path> fixtureYamlFiles(Path fixtureRoot) throws Exception {
        try (Stream<Path> paths = Files.walk(fixtureRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !"manifest.yaml".equals(name)
                                && !"manifest.yml".equals(name);
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> path.toAbsolutePath().normalize())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private JsonNode requireNonNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Fixture is missing required field: " + field);
        }
        return value;
    }

    private String failureMessage(BlueConformanceFailure failure) {
        return "Fixture " + failure.getFixtureId()
                + " (" + failure.getCategory()
                + ", operation=" + failure.getOperation()
                + ") failed with " + failure.getExceptionClass()
                + ": " + failure.getMessage();
    }
}
