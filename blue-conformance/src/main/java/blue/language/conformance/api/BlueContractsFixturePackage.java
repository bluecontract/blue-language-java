package blue.language.conformance.api;

import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.registry.RegistryManifestConstants;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.erdtman.jcs.JsonCanonicalizer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import blue.language.conformance.api.BlueContractsConformanceReport.FixtureInventoryEntry;

import static blue.language.conformance.api.BlueContractsConformanceReport.*;

/** Loads and verifies the exact Contracts fixture and release packages. */
final class BlueContractsFixturePackage {

    private static final int MAX_VERIFIED_FIXTURE_YAML_CODE_POINTS =
            16 * 1024 * 1024;

    static final String CONTRACTS_RELEASE_IDENTITY =
            "sha256:23528ca785e4b9a00648953f2a642bac38c50cc60b099a036aac247afbdb49de";
    private static final String CONTRACTS_RELEASE_MANIFEST_RESOURCE =
            "blue-contracts-closure-1.0/release-manifest.yaml";

    /*
     * The aggregate Language release manifest predates the normative closure
     * amendment. Its own identity remains verified exactly while the current
     * Contracts report binds the superseding gas and combined-fixture
     * packages directly.
     */
    private static final String LEGACY_RELEASE_CONTRACTS_GAS_IDENTITY =
            "sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5";
    private static final String LEGACY_RELEASE_CONTRACTS_FIXTURE_IDENTITY =
            "sha256:16392301655431695df6a7cc142a7e388e426c382bf4e3c5f06ddfafb8efecdc";

    /**
     * Fixture envelopes may use YAML anchors for literal reuse. This parser is
     * separate from Blue's YAML parser because anchors are envelope syntax, not
     * part of the Blue value model.
     */
    static final ObjectMapper FIXTURE_YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    static List<String> requiredFixtureIdsForContracts10() {
        return Collections.unmodifiableList(loadFixtureIds());
    }

    /**
     * Loads the declared fixture package identity.
     *
     * @param fallback value used when no identity is declared
     * @return declared identity or {@code fallback}
     */
    static String loadFixturePackageIdentity(String fallback) {
        validateFixturePackageIntegrity();
        validateReleaseBindings();
        JsonNode manifest = requireYamlResource(FIXTURE_MANIFEST_RESOURCE);
        JsonNode identity = manifest.get(
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
        if (identity == null || !identity.isTextual() || identity.asText().trim().isEmpty()) {
            throw new IllegalStateException(
                    "Contracts fixture manifest is missing packageIdentity");
        }
        return identity.asText();
    }

    /**

     * Loads fixture identities in manifest order.

     *

     * @return fixture identity list

     */
    static List<String> loadFixtureIds() {
        List<String> ids = new ArrayList<>();
        for (FixtureInventoryEntry entry : loadFixtureInventory()) {
            ids.add(entry.id);
        }
        return ids;
    }

    /**

     * Loads fixture categories.

     *

     * @return categories keyed by fixture identity

     */
    static Map<String, BlueContractsFixtureCategory> loadFixtureCategories() {
        Map<String, BlueContractsFixtureCategory> categories = new LinkedHashMap<>();
        for (FixtureInventoryEntry entry : loadFixtureInventory()) {
            categories.put(entry.id, entry.category);
        }
        return categories;
    }

    /**

     * Recomputes the fixture package identity.

     *

     * @return fixture package identity

     */
    static String computeFixturePackageIdentity() {
        return computeYamlPackageIdentity(
                FIXTURE_MANIFEST_RESOURCE,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
    }

    /**

     * Recomputes the gas package identity.

     *

     * @return gas package identity

     */
    static String computeGasPackageIdentity() {
        return computeYamlPackageIdentity(
                GAS_MANIFEST_RESOURCE,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
    }

    /**

     * Recomputes the registry package identity.

     *

     * @return registry package identity

     */
    static String computeRegistryPackageIdentity() {
        return computeYamlPackageIdentity(
                REGISTRY_MANIFEST_RESOURCE,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
                RegistryManifestConstants.FIELD_FIXTURE_PACKAGE_IDENTITY);
    }

    /**

     * Recomputes the release package identity.

     *

     * @return release package identity

     */
    static String computeReleasePackageIdentity() {
        return computeYamlPackageIdentity(
                RELEASE_MANIFEST_RESOURCE,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
    }

    static String computeContractsReleaseIdentity() {
        return computeYamlPackageIdentity(
                CONTRACTS_RELEASE_MANIFEST_RESOURCE,
                "releaseIdentity");
    }

    /**

     * Verifies fixture identity and file digests.

     *

     * @return whether all evidence matches

     */
    static boolean fixturePackageIdentityMatchesFixtureFiles() {
        try {
            validateFixturePackageIntegrity();
            return CONTRACTS_FIXTURE_PACKAGE_IDENTITY.equals(computeFixturePackageIdentity());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Requires internally consistent fixture package evidence.
     *
     * @throws IllegalStateException when package evidence is inconsistent
     */
    static void validateFixturePackageIntegrity() {
        JsonNode manifest = requireYamlResource(FIXTURE_MANIFEST_RESOURCE);
        requireText(manifest, "fixturePackage", "blue-contracts-conformance");
        requireText(
                manifest,
                RegistryManifestConstants.FIELD_SPECIFICATION_VERSION,
                ConformanceReportConstants.SPECIFICATION_VERSION_1_0);
        requireTextArray(
                manifest,
                "schemaVersions",
                "blue-contracts-fixture/1.0",
                "blue-contracts-closure-fixture/1.0");
        requireText(manifest, "registryPackageIdentity", CONTRACTS_REGISTRY_PACKAGE_IDENTITY);
        requireText(
                manifest,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
                CONTRACTS_FIXTURE_PACKAGE_IDENTITY);

        JsonNode files = manifest.get("files");
        if (files == null || !files.isArray()) {
            throw new IllegalStateException("Contracts fixture manifest files must be a list");
        }
        Set<String> paths = new LinkedHashSet<>();
        int behavior = 0;
        int gas = 0;
        int closure = 0;
        for (JsonNode file : files) {
            String path = requiredText(
                    file, RegistryManifestConstants.FIELD_PATH);
            validateRelativeResourcePath(path);
            if (!paths.add(path)) {
                throw new IllegalStateException("Duplicate Contracts fixture file path: " + path);
            }
            String role = requiredText(file, "role");
            if ("behavior-fixture".equals(role)) {
                behavior++;
            } else if ("gas-fixture".equals(role)) {
                gas++;
            } else if ("closure-fixture".equals(role)) {
                closure++;
            } else if (!"support".equals(role)) {
                throw new IllegalStateException("Unknown Contracts fixture file role: " + role);
            }
            byte[] normalized = normalizeLineEndings(
                    readRequiredResource(FIXTURE_ROOT_RESOURCE + path));
            if (file.path("bytes").asLong(-1L) != normalized.length) {
                throw new IllegalStateException("Contracts fixture byte length mismatch: " + path);
            }
            String expectedDigest = requiredText(
                    file, RegistryManifestConstants.FIELD_SHA256);
            String actualDigest = sha256Hex(normalized);
            if (!expectedDigest.equals(actualDigest)) {
                throw new IllegalStateException("Contracts fixture digest mismatch: " + path);
            }
        }
        requireCount(manifest, "ordinaryBehaviorFixtureCount", behavior);
        requireCount(manifest, "ordinaryGasFixtureCount", gas);
        requireCount(manifest, "closureFixtureCount", closure);
        requireCount(manifest, "ordinaryFixtureCount", behavior + gas);
        requireCount(manifest, "totalExecutableFixtureCount",
                behavior + gas + closure);
        requireCount(manifest, "vectorCount", 168);
        requireCount(manifest, "ordinaryVectorCount", 114);
        requireCount(manifest, "closureVectorCount", 54);
        if (behavior
                != ConformanceReportConstants.FixtureCount
                        .CONTRACTS_ORDINARY_BEHAVIOR
                || gas
                != ConformanceReportConstants.FixtureCount
                        .CONTRACTS_ORDINARY_GAS
                || closure
                != ConformanceReportConstants.FixtureCount
                        .CONTRACTS_CLOSURE) {
            throw new IllegalStateException(
                    "Contracts fixture inventory must contain exactly 112 "
                            + "ordinary behavior, 71 ordinary gas, and 93 "
                            + "closure fixtures");
        }
        if (!CONTRACTS_FIXTURE_PACKAGE_IDENTITY.equals(computeFixturePackageIdentity())) {
            throw new IllegalStateException("Contracts fixture package identity mismatch");
        }
        loadFixtureInventory(
                manifest,
                new Function<String, JsonNode>() {
                    @Override
                    public JsonNode apply(String path) {
                        return readFixture(path);
                    }
                });
    }

    /**
     * Requires the published release bindings to match bundled resources.
     *
     * @throws IllegalStateException when a release binding is inconsistent
     */
    static void validateReleaseBindings() {
        JsonNode release = requireYamlResource(RELEASE_MANIFEST_RESOURCE);
        requireText(release, "package", RELEASE_NAME);
        JsonNode components = release.get("components");
        if (components == null || !components.isObject()) {
            throw new IllegalStateException("Release components object is required");
        }
        requireText(components, "languageRegistryPackageIdentity",
                LANGUAGE_REGISTRY_PACKAGE_IDENTITY);
        requireText(components, "languageFixturePackageIdentity",
                LANGUAGE_FIXTURE_PACKAGE_IDENTITY);
        requireText(components, "contractsRegistryPackageIdentity",
                CONTRACTS_REGISTRY_PACKAGE_IDENTITY);
        requireText(components, "contractsGasPackageIdentity",
                LEGACY_RELEASE_CONTRACTS_GAS_IDENTITY);
        requireText(components, "contractsFixturePackageIdentity",
                LEGACY_RELEASE_CONTRACTS_FIXTURE_IDENTITY);
        requireText(release, RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
                RELEASE_PACKAGE_IDENTITY);
        if (!RELEASE_PACKAGE_IDENTITY.equals(computeReleasePackageIdentity())) {
            throw new IllegalStateException("Release package identity mismatch");
        }
        if (!CONTRACTS_GAS_PACKAGE_IDENTITY.equals(computeGasPackageIdentity())) {
            throw new IllegalStateException("Contracts gas package identity mismatch");
        }
        if (!CONTRACTS_REGISTRY_PACKAGE_IDENTITY.equals(computeRegistryPackageIdentity())) {
            throw new IllegalStateException("Contracts registry package identity mismatch");
        }
        assertRawResourceDigest(GAS_MANIFEST_RESOURCE, CONTRACTS_GAS_MANIFEST_SHA256);
        assertRawResourceDigest(
                LANGUAGE_SPECIFICATION_RESOURCE,
                LANGUAGE_SPECIFICATION_SHA256);
        assertRawResourceDigest(CONTRACTS_SPECIFICATION_RESOURCE, CONTRACTS_SPECIFICATION_SHA256);

        JsonNode contractsRelease = requireYamlResource(
                CONTRACTS_RELEASE_MANIFEST_RESOURCE);
        validateContractsReleaseBindings(contractsRelease);
        if (!CONTRACTS_RELEASE_IDENTITY.equals(
                computeContractsReleaseIdentity())) {
            throw new IllegalStateException(
                    "Canonical Contracts release identity mismatch");
        }
    }

    static void validateContractsReleaseBindings(JsonNode contractsRelease) {
        requireText(contractsRelease, "manifestType",
                "blue-contracts-release");
        requireText(contractsRelease,
                RegistryManifestConstants.FIELD_SPECIFICATION_VERSION,
                ConformanceReportConstants.SPECIFICATION_VERSION_1_0);
        requireText(contractsRelease, "releaseIdentity",
                CONTRACTS_RELEASE_IDENTITY);
        requireText(requiredObject(contractsRelease, "specificationDocument"),
                RegistryManifestConstants.FIELD_SHA256,
                CONTRACTS_SPECIFICATION_SHA256);
        JsonNode languageDependency = requiredObject(
                contractsRelease, "languageDependency");
        requireText(languageDependency, "specificationSha256",
                LANGUAGE_SPECIFICATION_SHA256);
        requireText(languageDependency,
                "cyclicSetFinalizerBaselineIdentity",
                ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY);
        requireText(languageDependency,
                "cyclicSetProofVerifierBaselineIdentity",
                ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY);
        requireText(requiredObject(contractsRelease, "contractsRegistry"),
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
                CONTRACTS_REGISTRY_PACKAGE_IDENTITY);
        requireText(requiredObject(contractsRelease, "gasManifest"),
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
                CONTRACTS_GAS_PACKAGE_IDENTITY);
        requireText(requiredObject(contractsRelease, "fixturePackage"),
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
                CONTRACTS_FIXTURE_PACKAGE_IDENTITY);
    }

    static ObjectMapper fixtureYamlMapper() {
        return FIXTURE_YAML;
    }

    /**
     * Reads one path from the verified packaged fixture inventory.
     *
     * @param path manifest-relative fixture path
     * @return parsed fixture envelope
     */
    static JsonNode readFixture(String path) {
        validateRelativeResourcePath(path);
        String resource = FIXTURE_ROOT_RESOURCE + path;
        try (InputStream input = BlueContractsConformanceReport.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing required Contracts resource: " + resource);
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setCodePointLimit(
                    MAX_VERIFIED_FIXTURE_YAML_CODE_POINTS);
            Object envelope =
                    new Yaml(new SafeConstructor(options)).load(input);
            if (envelope == null) {
                throw new IllegalStateException(
                        "Empty Contracts fixture resource: " + resource);
            }
            return UncheckedObjectMapper.JSON_MAPPER.valueToTree(envelope);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to read Contracts fixture: " + resource, ex);
        }
    }

    /**
     * Loads the ordered executable inventory from the verified manifest.
     *
     * @return immutable executable inventory
     */
    static List<FixtureInventoryEntry> loadFixtureInventory() {
        JsonNode manifest = requireYamlResource(FIXTURE_MANIFEST_RESOURCE);
        return loadFixtureInventory(
                manifest,
                new Function<String, JsonNode>() {
                    @Override
                    public JsonNode apply(String path) {
                        return readFixture(path);
                    }
                });
    }

    static List<FixtureInventoryEntry> loadFixtureInventory(
            JsonNode manifest,
            Function<String, JsonNode> fixtureReader) {
        if (manifest == null || !manifest.isObject()) {
            throw new IllegalStateException(
                    "Contracts fixture manifest must be an object");
        }
        if (fixtureReader == null) {
            throw new IllegalArgumentException("fixtureReader is required");
        }
        JsonNode files = manifest.get("files");
        if (files == null || !files.isArray() || files.size() == 0) {
            throw new IllegalStateException(
                    "Contracts fixture manifest files must be a non-empty list");
        }
        List<FixtureInventoryEntry> entries = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        int behavior = 0;
        int gas = 0;
        int closure = 0;
        for (JsonNode file : files) {
            String role = file.path("role").asText();
            if (!"behavior-fixture".equals(role)
                    && !"gas-fixture".equals(role)
                    && !"closure-fixture".equals(role)) {
                continue;
            }
            String path = requiredText(
                    file, RegistryManifestConstants.FIELD_PATH);
            validateRelativeResourcePath(path);
            if (!paths.add(path)) {
                throw new IllegalStateException(
                        "Duplicate executable Contracts fixture path: " + path);
            }
            JsonNode fixture = fixtureReader.apply(path);
            if (fixture == null || !fixture.isObject()) {
                throw new IllegalStateException(
                        "Contracts fixture must be an object: " + path);
            }
            String id = requiredText(
                    fixture, ConformanceReportConstants.Field.ID);
            if (!ids.add(id)) {
                throw new IllegalStateException(
                        "Duplicate executable Contracts fixture id: " + id);
            }
            List<String> vectors = new ArrayList<>();
            JsonNode declaredVectors = fixture.get(
                    ConformanceReportConstants.Field.VECTORS);
            if (declaredVectors == null
                    || !declaredVectors.isArray()
                    || declaredVectors.size() == 0) {
                throw new IllegalStateException(
                        "Contracts fixture has no vector coverage: " + path);
            }
            for (JsonNode vector : declaredVectors) {
                if (!vector.isTextual() || vector.asText().isEmpty()) {
                    throw new IllegalStateException(
                            "Contracts fixture has malformed vector coverage: " + path);
                }
                vectors.add(vector.asText());
            }
            entries.add(new FixtureInventoryEntry(
                    id,
                    path,
                    role,
                    BlueContractsFixtureCategory.fromLabel(requiredText(
                            fixture,
                            ConformanceReportConstants.Field.CATEGORY)),
                    requiredText(
                            fixture,
                            ConformanceReportConstants.Field.OPERATION),
                    vectors));
            if ("behavior-fixture".equals(role)) {
                behavior++;
            } else if ("gas-fixture".equals(role)) {
                gas++;
            } else {
                closure++;
            }
        }
        if (behavior
                != ConformanceReportConstants.FixtureCount
                        .CONTRACTS_ORDINARY_BEHAVIOR
                || gas
                != ConformanceReportConstants.FixtureCount
                        .CONTRACTS_ORDINARY_GAS
                || closure
                != ConformanceReportConstants.FixtureCount
                        .CONTRACTS_CLOSURE
                || entries.size()
                != BlueReleaseConformanceReport.CONTRACTS_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Contracts executable inventory must contain exactly "
                            + "112 ordinary behavior, 71 ordinary gas, and 93 "
                            + "closure fixtures; found " + behavior
                            + " ordinary behavior, " + gas
                            + " ordinary gas, and " + closure + " closure");
        }
        return Collections.unmodifiableList(entries);
    }


    static String computeYamlPackageIdentity(String resource, String... nulledFields) {
        JsonNode parsed = requireYamlResource(resource);
        if (!parsed.isObject()) {
            throw new IllegalStateException("Package manifest must be an object: " + resource);
        }
        ObjectNode normalized = ((ObjectNode) parsed).deepCopy();
        for (String field : nulledFields) {
            normalized.putNull(field);
        }
        try {
            // Package identities require explicit null fields. The public
            // mapper intentionally omits null bean properties, so use a fresh
            // compact mapper for this canonical payload.
            String json = new ObjectMapper().writeValueAsString(normalized);
            byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
            return "sha256:" + sha256Hex(canonical);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to canonicalize package manifest: " + resource, ex);
        }
    }

    static JsonNode loadYamlResource(String resource) {
        try (InputStream input = BlueContractsConformanceReport.class.getClassLoader()
                .getResourceAsStream(resource)) {
            return input == null ? null : FIXTURE_YAML.readTree(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read YAML resource: " + resource, ex);
        }
    }

    static JsonNode requireYamlResource(String resource) {
        JsonNode node = loadYamlResource(resource);
        if (node == null) {
            throw new IllegalStateException("Missing required Contracts resource: " + resource);
        }
        return node;
    }

    static byte[] readRequiredResource(String resource) {
        try (InputStream input = BlueContractsConformanceReport.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing required Contracts resource: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Contracts resource: " + resource, ex);
        }
    }

    static void assertRawResourceDigest(String resource, String expected) {
        String actual = sha256Hex(readRequiredResource(resource));
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Contracts resource digest mismatch for " + resource
                            + ": expected=" + expected + ", actual=" + actual);
        }
    }

    static byte[] normalizeLineEndings(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError("SHA-256 is unavailable", ex);
        }
        byte[] value = digest.digest(bytes);
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    static void validateRelativeResourcePath(String path) {
        if (path == null
                || path.isEmpty()
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.contains("\\")
                || path.equals("..")
                || path.startsWith("../")
                || path.contains("/../")
                || path.endsWith("/..")) {
            throw new IllegalArgumentException("Unsafe Contracts fixture resource path: " + path);
        }
    }

    static void requireText(JsonNode object, String field, String expected) {
        String actual = requiredText(object, field);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Contracts package field " + field + " expected " + expected + " but was " + actual);
        }
    }

    static void requireTextArray(
            JsonNode object,
            String field,
            String... expected) {
        JsonNode actual = object == null ? null : object.get(field);
        if (actual == null || !actual.isArray()
                || actual.size() != expected.length) {
            throw new IllegalStateException(
                    "Contracts package field " + field
                            + " must contain exactly " + expected.length
                            + " values");
        }
        for (int index = 0; index < expected.length; index++) {
            if (!actual.get(index).isTextual()
                    || !expected[index].equals(actual.get(index).asText())) {
                throw new IllegalStateException(
                        "Contracts package field " + field
                                + " disagrees at index " + index);
            }
        }
    }

    static String requiredText(JsonNode object, String field) {
        JsonNode value = object != null ? object.get(field) : null;
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalStateException("Required non-empty text field is missing: " + field);
        }
        return value.asText();
    }

    static JsonNode requiredObject(JsonNode object, String field) {
        JsonNode value = object != null ? object.get(field) : null;
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(
                    "Required object field is missing: " + field);
        }
        return value;
    }

    static void requireCount(JsonNode manifest, String field, int expected) {
        if (!manifest.has(field) || manifest.get(field).asInt(-1) != expected) {
            throw new IllegalStateException(
                    "Contracts fixture manifest " + field + " mismatch: expected " + expected);
        }
    }

    static List<String> immutableCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(
                values != null ? values : Collections.<String>emptyList()));
    }

    private BlueContractsFixturePackage() {}
}
