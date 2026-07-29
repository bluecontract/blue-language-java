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
import java.util.ArrayList;
import java.util.Arrays;
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

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BlueLanguageConformanceFixtureTest {

    private static final String FIXTURE_PATH = "blue-language-1.0/fixtures";

    @TestFactory
    Stream<DynamicTest> shouldPassAllBlueLanguage10Fixtures() {
        // given
        Blue blue = new Blue();

        // when
        BlueConformanceReport report = blue.runConformanceSuite();
        Map<String, BlueConformanceFailure> failuresById = report.getFailures().stream()
                .collect(Collectors.toMap(BlueConformanceFailure::getFixtureId, Function.identity()));

        // then
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
    void shouldRejectFixtureWithoutExpectedOutputDuringMetadataValidation() {
        // given
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_missing_expected\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "input: 1\n");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectPlaceholderAwareBlueIdCalculationAsFixtureOperation() {
        // given
        JsonNode spec = YAML_MAPPER.readTree(
                "id: C_placeholder_helper\n" +
                "category: Circular\n" +
                "operation: calculateBlueIdAllowingCyclicPlaceholders\n" +
                "input:\n" +
                "  blueId: this#0\n" +
                "expectedNodeBlueId: placeholder\n");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectTopLevelFixtureProfileField() {
        // given
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_profile_metadata\n" +
                "profile: BlueId\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "input: 1\n" +
                "expectedNodeBlueId: placeholder\n");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldAllowOrdinaryProfileFieldInFixtureInput() {
        // given
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_profile_data\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "input:\n" +
                "  profile: user\n" +
                "expectedNodeBlueId: placeholder\n");

        // when
        Throwable failure = captureFailure(
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));

        // then
        assertNull(failure);
    }

    @Test
    void shouldAllowOrdinaryProfileFieldInExpectedOutput() {
        // given
        JsonNode spec = YAML_MAPPER.readTree(
                "id: R_profile_expected\n" +
                "category: Resolution\n" +
                "operation: preprocess\n" +
                "source:\n" +
                "  profile: user\n" +
                "expectedPreprocessed:\n" +
                "  profile: user\n");

        // when
        Throwable failure = captureFailure(
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));

        // then
        assertNull(failure);
    }

    @Test
    void shouldAllowOrdinaryProfileFieldInProviderNode() {
        // given
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

        // when
        Throwable failure = captureFailure(
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));

        // then
        assertNull(failure);
    }

    @Test
    void shouldAcceptKnownExpectedErrorCategory() {
        // given
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_error_category\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "expectError: true\n" +
                "expectedErrorCategory: InvalidBlueIdInput\n" +
                "input:\n" +
                "  type: Integer\n" +
                "  value: 1\n");

        // when
        Throwable failure = captureFailure(
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));

        // then
        assertNull(failure);
    }

    @Test
    void shouldRejectUnknownExpectedErrorCategory() {
        // given
        JsonNode spec = YAML_MAPPER.readTree(
                "id: B_error_category\n" +
                "category: BlueId\n" +
                "operation: calculateBlueId\n" +
                "expectError: true\n" +
                "expectedErrorCategory: NotACategory\n" +
                "input:\n" +
                "  type: Integer\n" +
                "  value: 1\n");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(spec));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldFailClosedWhenExpectedIdentityValueOrOutcomeIsMutated() {
        // given
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

        // when
        AssertionError identityFailure = captureFailure(
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongIdentity));
        AssertionError valueFailure = captureFailure(
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongValue));
        AssertionError outcomeFailure = captureFailure(
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongOutcome));

        // then
        assertTrue(identityFailure instanceof AssertionError);
        assertTrue(valueFailure instanceof AssertionError);
        assertTrue(outcomeFailure instanceof AssertionError);
    }

    @Test
    void shouldClassifyRepresentativeLanguageErrors() {
        // given
        IllegalArgumentException invalidBlueId = new IllegalArgumentException("not a valid BlueId");
        IllegalArgumentException schemaViolation =
                new IllegalArgumentException("schema keyword minLength applies to wrong kind");
        IllegalArgumentException providerMismatch =
                new IllegalArgumentException("Provider returned content for abc but computed BlueId xyz");
        IllegalArgumentException listControlViolation =
                new IllegalArgumentException("$pos list overlay is invalid");

        // when
        BlueLanguageErrorCategory invalidBlueIdCategory = BlueLanguageErrorClassifier.classify(invalidBlueId);
        BlueLanguageErrorCategory schemaViolationCategory = BlueLanguageErrorClassifier.classify(schemaViolation);
        BlueLanguageErrorCategory providerMismatchCategory = BlueLanguageErrorClassifier.classify(providerMismatch);
        BlueLanguageErrorCategory listControlViolationCategory = BlueLanguageErrorClassifier.classify(listControlViolation);

        // then
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId, invalidBlueIdCategory);
        assertEquals(BlueLanguageErrorCategory.SchemaViolation, schemaViolationCategory);
        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch, providerMismatchCategory);
        assertEquals(BlueLanguageErrorCategory.ListControlViolation, listControlViolationCategory);
    }

    @Test
    void shouldClassifyMissingProviderMessagesAsProviderUnavailable() {
        // given
        String[] messages = {
                "No content found for blueId: missing",
                "No content found for $previous blueId: missing",
                "No content found for required blueId missing at path /subject."
        };

        // when
        List<BlueLanguageErrorCategory> categories = Arrays.stream(messages)
                .map(message -> BlueLanguageErrorClassifier.classify(new IllegalArgumentException(message)))
                .collect(Collectors.toList());

        // then
        assertEquals(
                Collections.nCopies(messages.length, BlueLanguageErrorCategory.ProviderUnavailable),
                categories);
    }

    @Test
    void shouldTreatConformanceManifestAsAuthoritative() throws Exception {
        // given
        Set<String> requiredFixtureIds = BlueConformanceReport.requiredFixtureIdsForBlueLanguage10();
        int requiredFixtureCount = requiredFixtureIds.size();

        // when
        URL resource = getClass().getClassLoader().getResource(FIXTURE_PATH);
        Path fixtureRoot = resource == null ? null : Paths.get(resource.toURI());
        JsonNode manifest = fixtureRoot == null
                ? null
                : YAML_MAPPER.readTree(new String(Files.readAllBytes(fixtureRoot.resolve("manifest.yaml"))));
        JsonNode manifestFiles = manifest == null ? null : manifest.get("files");
        String packageIdentity = manifest != null && manifest.hasNonNull("packageIdentity")
                ? manifest.get("packageIdentity").asText()
                : null;
        Integer behaviorFixtureCount = manifest != null && manifest.hasNonNull("behaviorFixtureCount")
                ? manifest.get("behaviorFixtureCount").asInt()
                : null;
        Set<String> knownOperations = BlueConformanceSuiteRunner.knownOperations();
        Set<String> fixtureIds = new LinkedHashSet<>();
        Set<Path> listedPaths = new HashSet<>();
        List<String> manifestViolations = new ArrayList<>();
        if (manifestFiles != null && manifestFiles.isArray()) {
            for (JsonNode entry : manifestFiles) {
                String entryPath = entry.hasNonNull("path") ? entry.get("path").asText() : null;
                String role = entry.hasNonNull("role") ? entry.get("role").asText() : null;
                if (entryPath == null) {
                    manifestViolations.add("Manifest entry is missing path: " + entry);
                }
                if (role == null) {
                    manifestViolations.add("Manifest entry is missing role: " + entry);
                }
                if (!entry.hasNonNull("sha256")) {
                    manifestViolations.add("Manifest entry is missing sha256: " + entry);
                }
                if (!entry.hasNonNull("bytes")) {
                    manifestViolations.add("Manifest entry is missing bytes: " + entry);
                }
                if (entryPath == null || fixtureRoot == null) {
                    continue;
                }

                Path fixturePath = fixtureRoot.resolve(entryPath).normalize();
                if (!Files.isRegularFile(fixturePath)) {
                    manifestViolations.add("Missing fixture file: " + fixturePath);
                    continue;
                }
                listedPaths.add(fixturePath.toAbsolutePath().normalize());
                if (!"behavior-fixture".equals(role)) {
                    if (!"support".equals(role)) {
                        manifestViolations.add("Unknown fixture role '" + role + "' for " + fixturePath);
                    }
                    continue;
                }

                JsonNode fixture = YAML_MAPPER.readTree(new String(Files.readAllBytes(fixturePath)));
                if (fixture.has("profile")) {
                    manifestViolations.add("Fixture metadata must use category, not profile: " + fixturePath);
                }
                JsonNode idNode = fixture.get("id");
                if (idNode == null || idNode.isNull()) {
                    manifestViolations.add("Fixture is missing required field 'id': " + fixturePath);
                } else if (!fixtureIds.add(idNode.asText())) {
                    manifestViolations.add("Duplicate fixture id: " + idNode.asText());
                }

                JsonNode categoryNode = fixture.get("category");
                if (categoryNode == null || categoryNode.isNull()) {
                    manifestViolations.add("Fixture is missing required field 'category': " + fixturePath);
                } else {
                    Throwable categoryFailure = captureFailure(
                            () -> BlueFixtureCategory.fromLabel(categoryNode.asText()));
                    if (categoryFailure != null) {
                        manifestViolations.add("Unknown fixture category in " + fixturePath
                                + ": " + categoryFailure.getMessage());
                    }
                }

                JsonNode operationNode = fixture.get("operation");
                if (operationNode == null || operationNode.isNull()) {
                    manifestViolations.add("Fixture is missing required field 'operation': " + fixturePath);
                } else if (!knownOperations.contains(operationNode.asText())) {
                    manifestViolations.add("Unknown fixture operation in " + fixturePath);
                }

                Throwable metadataFailure = captureFailure(
                        () -> BlueConformanceSuiteRunner.validateFixtureMetadataForTest(fixture));
                if (metadataFailure != null) {
                    manifestViolations.add("Invalid fixture metadata in " + fixturePath
                            + ": " + metadataFailure.getMessage());
                }
            }
        }
        Set<Path> actualFixturePaths = fixtureRoot == null
                ? Collections.emptySet()
                : fixtureYamlFiles(fixtureRoot);

        // then
        assertTrue(resource != null);
        assertTrue(manifestFiles != null && manifestFiles.isArray());
        assertEquals(BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY, packageIdentity);
        assertEquals(requiredFixtureCount, behaviorFixtureCount);
        assertTrue(manifestViolations.isEmpty(), String.join("\n", manifestViolations));
        assertEquals(requiredFixtureIds, fixtureIds);
        assertEquals(listedPaths, actualFixturePaths);
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

    private String failureMessage(BlueConformanceFailure failure) {
        return "Fixture " + failure.getFixtureId()
                + " (" + failure.getCategory()
                + ", operation=" + failure.getOperation()
                + ") failed with " + failure.getExceptionClass()
                + ": " + failure.getMessage();
    }
}
