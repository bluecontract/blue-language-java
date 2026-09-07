package blue.language.conformance.api;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BlueLanguageConformanceFixtureTest {

    private static final String FIXTURE_PATH = "blue-language-1.0/fixtures";

    @TestFactory
    Stream<DynamicTest> shouldPassAllBlueLanguage10Fixtures() {
        // given
        String selector = System.getProperty(LanguageFixtureSelection.CASES_PROPERTY);

        // when
        Stream<DynamicTest> selected = LanguageFixtureSelection.dynamicTests(selector);

        // then
        return selected;
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
                        + "provider: []\n"
                        + "limits:\n"
                        + "  demandedPaths: [/left]\n"
                        + "expectedOutcome: Established\n"
                        + "expectedValue: wrong\n");
        JsonNode wrongOutcome = YAML_MAPPER.readTree(
                "id: R_mutated_outcome\n"
                        + "category: LimitedResolution\n"
                        + "operation: semanticExists\n"
                        + "source: {}\n"
                        + "provider: []\n"
                        + "path: /missing\n"
                        + "expectedOutcome: Established\n");

        // when
        Throwable identityFailure = captureFailure(
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongIdentity));
        Throwable valueFailure = captureFailure(
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongValue));
        Throwable outcomeFailure = captureFailure(
                () -> BlueConformanceSuiteRunner.runFixtureForTest(wrongOutcome));

        // then
        assertInstanceOf(AssertionError.class, identityFailure);
        assertInstanceOf(AssertionError.class, valueFailure);
        assertInstanceOf(AssertionError.class, outcomeFailure);
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
        JsonNode manifest;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(FIXTURE_PATH + "/manifest.yaml")) {
            manifest = input == null ? null : YAML_MAPPER.readTree(input);
        }
        JsonNode manifestFiles = manifest == null ? null : manifest.get("files");
        String packageIdentity = manifest != null && manifest.hasNonNull("packageIdentity")
                ? manifest.get("packageIdentity").asText()
                : null;
        Integer behaviorFixtureCount = manifest != null && manifest.hasNonNull("behaviorFixtureCount")
                ? manifest.get("behaviorFixtureCount").asInt()
                : null;
        Set<String> knownOperations = BlueConformanceSuiteRunner.knownOperations();
        Set<String> fixtureIds = new LinkedHashSet<>();
        Set<String> listedPaths = new HashSet<>();
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
                if (entryPath == null || resource == null) {
                    continue;
                }

                String fixtureResource = FIXTURE_PATH + "/" + entryPath;
                listedPaths.add(entryPath);
                try (InputStream input = getClass().getClassLoader()
                        .getResourceAsStream(fixtureResource)) {
                    if (input == null) {
                        manifestViolations.add(
                                "Missing fixture resource: " + fixtureResource);
                        continue;
                    }
                    if (!"behavior-fixture".equals(role)) {
                        if (!"support".equals(role)) {
                            manifestViolations.add("Unknown fixture role '"
                                    + role + "' for " + fixtureResource);
                        }
                        continue;
                    }

                    JsonNode fixture = YAML_MAPPER.readTree(input);
                    if (fixture.has("profile")) {
                        manifestViolations.add(
                                "Fixture metadata must use category, not profile: "
                                        + fixtureResource);
                    }
                    JsonNode idNode = fixture.get("id");
                    if (idNode == null || idNode.isNull()) {
                        manifestViolations.add(
                                "Fixture is missing required field 'id': "
                                        + fixtureResource);
                    } else if (!fixtureIds.add(idNode.asText())) {
                        manifestViolations.add(
                                "Duplicate fixture id: " + idNode.asText());
                    }

                    JsonNode categoryNode = fixture.get("category");
                    if (categoryNode == null || categoryNode.isNull()) {
                        manifestViolations.add(
                                "Fixture is missing required field 'category': "
                                        + fixtureResource);
                    } else {
                        Throwable categoryFailure = captureFailure(
                                () -> BlueFixtureCategory.fromLabel(
                                        categoryNode.asText()));
                        if (categoryFailure != null) {
                            manifestViolations.add("Unknown fixture category in "
                                    + fixtureResource + ": "
                                    + categoryFailure.getMessage());
                        }
                    }

                    JsonNode operationNode = fixture.get("operation");
                    if (operationNode == null || operationNode.isNull()) {
                        manifestViolations.add(
                                "Fixture is missing required field 'operation': "
                                        + fixtureResource);
                    } else if (!knownOperations.contains(operationNode.asText())) {
                        manifestViolations.add(
                                "Unknown fixture operation in " + fixtureResource);
                    }

                    Throwable metadataFailure = captureFailure(
                            () -> BlueConformanceSuiteRunner
                                    .validateFixtureMetadataForTest(fixture));
                    if (metadataFailure != null) {
                        manifestViolations.add("Invalid fixture metadata in "
                                + fixtureResource + ": "
                                + metadataFailure.getMessage());
                    }
                }
            }
        }
        Set<String> actualFixturePaths = resource == null
                ? Collections.emptySet()
                : fixtureYamlResources(resource);

        // then
        assertTrue(resource != null);
        assertTrue(manifestFiles != null && manifestFiles.isArray());
        assertEquals(BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY, packageIdentity);
        assertEquals(requiredFixtureCount, behaviorFixtureCount);
        assertTrue(manifestViolations.isEmpty(), String.join("\n", manifestViolations));
        assertEquals(requiredFixtureIds, fixtureIds);
        assertEquals(listedPaths, actualFixturePaths);
    }

    private Set<String> fixtureYamlResources(URL fixtureRoot) throws Exception {
        if ("file".equals(fixtureRoot.getProtocol())) {
            Path root = Paths.get(fixtureRoot.toURI());
            try (Stream<Path> paths = Files.walk(root)) {
                return paths
                        .filter(Files::isRegularFile)
                        .map(root::relativize)
                        .map(Path::toString)
                        .map(path -> path.replace('\\', '/'))
                        .filter(this::isFixtureResource)
                        .sorted()
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        }
        if ("jar".equals(fixtureRoot.getProtocol())) {
            JarURLConnection connection =
                    (JarURLConnection) fixtureRoot.openConnection();
            connection.setUseCaches(false);
            String prefix = connection.getEntryName() + "/";
            Set<String> resources = new LinkedHashSet<>();
            try (JarFile jar = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory()
                            && entry.getName().startsWith(prefix)) {
                        String relative = entry.getName()
                                .substring(prefix.length());
                        if (isFixtureResource(relative)) {
                            resources.add(relative);
                        }
                    }
                }
            }
            return resources.stream()
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        throw new IllegalArgumentException(
                "Unsupported fixture resource protocol: "
                        + fixtureRoot.getProtocol());
    }

    private boolean isFixtureResource(String path) {
        return !"manifest.yaml".equals(path)
                && !"manifest.yml".equals(path);
    }

    private String failureMessage(BlueConformanceFailure failure) {
        return "Fixture " + failure.getFixtureId()
                + " (" + failure.getCategory()
                + ", operation=" + failure.getOperation()
                + ") failed with " + failure.getExceptionClass()
                + ": " + failure.getMessage();
    }
}
