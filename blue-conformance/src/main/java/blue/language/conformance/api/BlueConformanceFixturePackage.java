package blue.language.conformance.api;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.NodeDeserializer;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.DirectNodeManifest;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePath;
import blue.language.registry.NodeProviderWrapper;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/** Loads and verifies the exact packaged Language fixture inventory. */
abstract class BlueConformanceFixturePackage extends BlueConformanceFixtureSupport {

    static List<FixtureEntry> fixtureEntries() {
        JsonNode manifest = readYamlResource(MANIFEST_RESOURCE);
        assertEquals(BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY,
                manifest.path("packageIdentity").asText());
        assertEquals(EXPECTED_BEHAVIOR_FIXTURE_COUNT,
                manifest.path("behaviorFixtureCount").asInt());
        assertEquals(EXPECTED_BEHAVIOR_FIXTURE_COUNT,
                BlueConformanceReport.requiredFixtureIdsForBlueLanguage10().size());
        JsonNode files = requireArray(manifest, "files");
        List<FixtureEntry> result = new ArrayList<>();
        String previousPath = null;
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode file : files) {
            String path = requireText(file, FixtureField.PATH);
            validateRelativePath(path);
            if (previousPath != null && previousPath.compareTo(path) >= 0) {
                throw new IllegalStateException(
                        "Fixture manifest files must be sorted by path.");
            }
            previousPath = path;
            byte[] bytes = readResourceBytes(FIXTURE_ROOT + path);
            assertEquals(file.path("bytes").asLong(),
                    (long) normalizeLineEndings(bytes).length);
            assertEquals(requireText(file, "sha256"),
                    sha256Hex(normalizeLineEndings(bytes)));
            String role = requireText(file, "role");
            if ("support".equals(role)) continue;
            if (!"behavior-fixture".equals(role)) {
                throw new IllegalStateException(
                        "Unknown Language fixture file role: " + role);
            }
            JsonNode fixture = UncheckedObjectMapper.YAML_MAPPER.readTree(
                    new String(bytes, StandardCharsets.UTF_8));
            validateFixtureMetadata(fixture);
            String id = requireText(fixture, FixtureField.ID);
            if (!ids.add(id)) {
                throw new IllegalStateException(
                        "Duplicate Language fixture id: " + id);
            }
            result.add(new FixtureEntry(id,
                    BlueFixtureCategory.fromLabel(
                            requireText(fixture, FixtureField.CATEGORY)), path));
        }
        assertEquals(EXPECTED_BEHAVIOR_FIXTURE_COUNT, result.size());
        return Collections.unmodifiableList(result);
    }

    static void validateFixtureMetadata(JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw new IllegalArgumentException(
                    "Language fixture must be an object.");
        }
        spec.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIXTURE_FIELDS.contains(field)) {
                throw new IllegalArgumentException(
                        "Unknown Language fixture field: " + field);
            }
        });
        requireText(spec, FixtureField.ID);
        BlueFixtureCategory.fromLabel(requireText(spec, FixtureField.CATEGORY));
        String operation = requireText(spec, FixtureField.OPERATION);
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException(
                    "Unsupported fixture operation: " + operation);
        }
        if (spec.has("profile")) {
            throw new IllegalArgumentException(
                    "Language fixtures use category, not profile.");
        }
        if (spec.has(FixtureField.EXPECTED_ERROR_CATEGORY)) {
            BlueLanguageErrorCategory.valueOf(
                    requireText(spec, FixtureField.EXPECTED_ERROR_CATEGORY));
        }
        boolean hasAssertion = spec.path(FixtureField.EXPECT_ERROR).asBoolean(false);
        java.util.Iterator<String> fields = spec.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            hasAssertion |= field.startsWith(FixtureField.EXPECTED)
                    || field.startsWith("also")
                    || FixtureField.ASSERTIONS.equals(field)
                    || FixtureField.VARIANTS.equals(field)
                    || FixtureField.REQUIRED_HEADINGS.equals(field)
                    || FixtureField.FORBIDDEN_JOINED_TERMS.equals(field)
                    || FixtureField.EXPECT_BLUE_ID_CHANGED.equals(field);
        }
        if (!hasAssertion) {
            throw new IllegalArgumentException(
                    "Fixture has no expected result assertion: "
                            + requireText(spec, FixtureField.ID));
        }
    }

    static BlueConformanceFailure failure(
            FixtureEntry fixture, Throwable throwable) {
        String operation = null;
        try {
            operation = requireText(
                    readYamlResource(FIXTURE_ROOT + fixture.path), FixtureField.OPERATION);
        } catch (RuntimeException ignored) {
            // Keep manifest-level failure details.
        }
        return new BlueConformanceFailure(
                fixture.id, fixture.category, operation,
                throwable.getClass().getName(), throwable.getMessage(),
                BlueLanguageErrorClassifier.classify(throwable));
    }

    static void requireRegistryKind(JsonNode spec) {
        assertEquals("Blue Language core type registry",
                requireText(spec, FixtureField.REGISTRY_KIND));
    }

}
