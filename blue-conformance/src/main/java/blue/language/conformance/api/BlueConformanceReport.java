package blue.language.conformance.api;

import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.registry.RegistryManifestConstants;
import blue.language.codec.jackson.UncheckedObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable metadata and execution results for the closed Blue Language 1.0
 * conformance package.
 *
 * <p>Collection arguments are defensively copied. Machine-readable output
 * always contains one result for every manifest fixture; a fixture with no
 * recorded execution is represented as a failure rather than a skip.</p>
 */
public final class BlueConformanceReport {

    /** Classpath location of the authoritative fixture manifest. */
    public static final String FIXTURE_MANIFEST_RESOURCE = "blue-language-1.0/fixtures/manifest.yaml";
    /** Expected identity of the complete final fixture package. */
    public static final String FIXTURE_PACKAGE_IDENTITY =
            "sha256:83a0d7ec99d711577d6c08962b922aedd342d858a9bf12560ade790692d08cae";
    /** Human-readable identifier of the specification source bound to the package. */
    public static final String BLUE_SPEC_SOURCE =
            "blue-language-1.0-final-implementation-baseline";
    private static final Set<String> REQUIRED_FIXTURE_IDS = requiredFixtureIds();

    private final String specVersion;
    private final Map<String, String> coreRegistryBlueIds;
    private final String fixturePackageIdentity;
    private final List<String> fixtureIds;
    private final List<String> passedFixtureIds;
    private final List<String> failedFixtureIds;
    private final List<BlueConformanceFailure> failures;
    private final Map<String, BlueFixtureCategory> fixtureCategories;

    /**
     * Creates a legacy report containing only passed fixture identities.
     *
     * @param specVersion specification version
     * @param coreRegistryBlueIds core registry identities by type name
     * @param fixturePackageIdentity exact fixture package identity
     * @param passedFixtureIds fixtures that passed
     */
    public BlueConformanceReport(String specVersion,
                                 Map<String, String> coreRegistryBlueIds,
                                 String fixturePackageIdentity,
                                 List<String> passedFixtureIds) {
        this(specVersion, coreRegistryBlueIds, fixturePackageIdentity, Collections.emptyList(), passedFixtureIds, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Creates a report without detailed failure records.
     *
     * @param specVersion specification version
     * @param coreRegistryBlueIds core registry identities by type name
     * @param fixturePackageIdentity exact fixture package identity
     * @param fixtureIds all manifest fixture identities
     * @param passedFixtureIds fixtures that passed
     * @param failedFixtureIds fixtures that failed
     * @param fixtureCategories categories keyed by fixture identity
     */
    public BlueConformanceReport(String specVersion,
                                 Map<String, String> coreRegistryBlueIds,
                                 String fixturePackageIdentity,
                                 List<String> fixtureIds,
                                 List<String> passedFixtureIds,
                                 List<String> failedFixtureIds,
                                 Map<String, BlueFixtureCategory> fixtureCategories) {
        this(specVersion, coreRegistryBlueIds, fixturePackageIdentity, fixtureIds, passedFixtureIds, failedFixtureIds, fixtureCategories, Collections.emptyList());
    }

    /**
     * Creates a complete conformance report.
     *
     * @param specVersion specification version
     * @param coreRegistryBlueIds core registry identities by type name
     * @param fixturePackageIdentity exact fixture package identity
     * @param fixtureIds all manifest fixture identities
     * @param passedFixtureIds fixtures that passed
     * @param failedFixtureIds fixtures that failed when detailed records are
     *                         absent
     * @param fixtureCategories categories keyed by fixture identity
     * @param failures detailed failure records
     */
    public BlueConformanceReport(String specVersion,
                                 Map<String, String> coreRegistryBlueIds,
                                 String fixturePackageIdentity,
                                 List<String> fixtureIds,
                                 List<String> passedFixtureIds,
                                 List<String> failedFixtureIds,
                                 Map<String, BlueFixtureCategory> fixtureCategories,
                                 List<BlueConformanceFailure> failures) {
        this.specVersion = specVersion;
        this.coreRegistryBlueIds = Collections.unmodifiableMap(new LinkedHashMap<>(coreRegistryBlueIds));
        this.fixturePackageIdentity = fixturePackageIdentity;
        this.fixtureIds = Collections.unmodifiableList(new ArrayList<>(fixtureIds));
        this.passedFixtureIds = Collections.unmodifiableList(new ArrayList<>(passedFixtureIds));
        List<String> effectiveFailedFixtureIds = new ArrayList<>(failedFixtureIds);
        if (!failures.isEmpty()) {
            effectiveFailedFixtureIds.clear();
            for (BlueConformanceFailure failure : failures) {
                effectiveFailedFixtureIds.add(failure.getFixtureId());
            }
        }
        this.failedFixtureIds = Collections.unmodifiableList(effectiveFailedFixtureIds);
        this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
        this.fixtureCategories = Collections.unmodifiableMap(new LinkedHashMap<>(fixtureCategories));
    }

    /**
     * Returns the specification version.
     *
     * @return specification version
     */
    public String getSpecVersion() {
        return specVersion;
    }

    /**
     * Returns core registry identities by type name.
     *
     * @return immutable registry identity map
     */
    public Map<String, String> getCoreRegistryBlueIds() {
        return coreRegistryBlueIds;
    }

    /**
     * Returns the exact fixture package identity.
     *
     * @return fixture package identity
     */
    public String getFixturePackageIdentity() {
        return fixturePackageIdentity;
    }

    /**
     * Returns all manifest fixture identities.
     *
     * @return immutable fixture identity list
     */
    public List<String> getFixtureIds() {
        return fixtureIds;
    }

    /**
     * Returns fixture identities that passed.
     *
     * @return immutable passed-fixture list
     */
    public List<String> getPassedFixtureIds() {
        return passedFixtureIds;
    }

    /**
     * Returns fixture identities that failed.
     *
     * @return immutable failed-fixture list
     */
    public List<String> getFailedFixtureIds() {
        return failedFixtureIds;
    }

    /**
     * Returns detailed failure records.
     *
     * @return immutable failure list
     */
    public List<BlueConformanceFailure> getFailures() {
        return failures;
    }

    /**
     * Returns fixture categories keyed by identity.
     *
     * @return immutable fixture-category map
     */
    public Map<String, BlueFixtureCategory> getFixtureCategories() {
        return fixtureCategories;
    }

    /**
     * Returns the active canonical registry package identity.
     *
     * @return core registry package identity
     */
    public String getCoreRegistryPackageIdentity() {
        return BlueCoreTypeRegistry.INSTANCE.packageIdentity();
    }

    /**
     * Complete one-result-per-fixture report for CI and release tooling.
     *
     * @return immutable machine-readable report map
     */
    public Map<String, Object> toMachineReadableMap() {
        Map<String, BlueConformanceFailure> failuresById = new LinkedHashMap<>();
        for (BlueConformanceFailure failure : failures) {
            failuresById.put(failure.getFixtureId(), failure);
        }
        Set<String> passed = new HashSet<>(passedFixtureIds);
        Map<String, String> operations = loadFixtureOperations();
        List<Map<String, Object>> results = new ArrayList<>(fixtureIds.size());
        for (String id : fixtureIds) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(ConformanceReportConstants.Field.ID, id);
            BlueFixtureCategory category = fixtureCategories.get(id);
            result.put(ConformanceReportConstants.Field.CATEGORY,
                    category == null ? null : category.getLabel());
            result.put(ConformanceReportConstants.Field.OPERATION,
                    operations.get(id));
            BlueConformanceFailure failure = failuresById.get(id);
            if (failure != null) {
                result.put(ConformanceReportConstants.Field.STATUS,
                        ConformanceReportConstants.Status.FAIL);
                result.put(ConformanceReportConstants.Field.ERROR_CATEGORY,
                        failure.getErrorCategory() == null
                                ? null
                                : failure.getErrorCategory().name());
                result.put(ConformanceReportConstants.Field.EXCEPTION_CLASS,
                        failure.getExceptionClass());
                result.put(ConformanceReportConstants.Field.MESSAGE,
                        failure.getMessage());
            } else if (passed.contains(id)) {
                result.put(ConformanceReportConstants.Field.STATUS,
                        ConformanceReportConstants.Status.PASS);
            } else {
                result.put(ConformanceReportConstants.Field.STATUS,
                        ConformanceReportConstants.Status.FAIL);
                result.put(ConformanceReportConstants.Field.ERROR_CATEGORY,
                        ConformanceReportConstants.ErrorCategory
                                .HARNESS_DID_NOT_RUN_FIXTURE);
                result.put(ConformanceReportConstants.Field.MESSAGE,
                        "Fixture has no execution result.");
            }
            results.add(result);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put(ConformanceReportConstants.Field.SPECIFICATION_VERSION,
                specVersion);
        report.put(ConformanceReportConstants.Field.REGISTRY_PACKAGE_IDENTITY,
                getCoreRegistryPackageIdentity());
        report.put(ConformanceReportConstants.Field.FIXTURE_PACKAGE_IDENTITY,
                fixturePackageIdentity);
        report.put(ConformanceReportConstants.Field.CORE_REGISTRY_BLUE_IDS,
                coreRegistryBlueIds);
        report.put(ConformanceReportConstants.Field.FIXTURE_COUNT,
                fixtureIds.size());
        report.put(ConformanceReportConstants.Field.PASSED_COUNT,
                passedFixtureIds.size());
        report.put(ConformanceReportConstants.Field.FAILED_COUNT,
                fixtureIds.size() - passedFixtureIds.size());
        report.put(ConformanceReportConstants.Field.RESULTS, results);
        return Collections.unmodifiableMap(report);
    }

    /**
     * Serializes the machine-readable report.
     *
     * @return JSON report
     */
    public String toMachineReadableJson() {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(toMachineReadableMap());
    }

    /**
     * Tests whether this report uses the final fixture package identity.
     *
     * @return whether the fixture identity is release-grade and exact
     */
    public boolean isReleaseGradeFixtureIdentity() {
        return FIXTURE_PACKAGE_IDENTITY.equals(fixturePackageIdentity)
                && isReleaseGradeFixtureIdentity(fixturePackageIdentity);
    }

    /**
     * Tests whether every required fixture appears in this report.
     *
     * @return whether required fixture coverage is present
     */
    public boolean hasRequiredFixtureCoverage() {
        return new HashSet<>(fixtureIds).containsAll(REQUIRED_FIXTURE_IDS);
    }

    /**
     * Tests whether this report contains exactly the required fixture set.
     *
     * @return whether the fixture set is exact
     */
    public boolean hasExactRequiredFixtureSet() {
        return new LinkedHashSet<>(fixtureIds).equals(REQUIRED_FIXTURE_IDS);
    }

    /**
     * Returns the normative Blue Language 1.0 fixture identities.
     *
     * @return immutable required fixture set
     */
    public static Set<String> requiredFixtureIdsForBlueLanguage10() {
        return Collections.unmodifiableSet(REQUIRED_FIXTURE_IDS);
    }

    /**
     * Loads the fixture package identity from the manifest.
     *
     * @param fallback value returned when the manifest declares no identity
     * @return declared package identity or {@code fallback}
     */
    public static String loadFixturePackageIdentity(String fallback) {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return fallback;
        }
        Object identity = manifest.get(
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
        return identity == null || identity.toString().trim().isEmpty()
                ? fallback
                : identity.toString();
    }

    /**
     * Loads behavior-fixture identities in manifest order.
     *
     * @return fixture identity list
     * @throws IllegalStateException when manifest evidence is malformed
     */
    public static List<String> loadFixtureIds() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (Map<?, ?> file : behaviorFixtureFiles(manifest)) {
            Map<?, ?> fixture = loadFixture(file);
            Object id = fixture.get(ConformanceReportConstants.Field.ID);
            if (id == null || id.toString().trim().isEmpty()) {
                throw new IllegalStateException(
                        "Blue Language fixture is missing id: "
                                + file.get(
                                        RegistryManifestConstants.FIELD_PATH));
            }
            ids.add(id.toString());
        }
        return ids;
    }

    /**
     * Loads fixture categories keyed by identity.
     *
     * @return fixture-category map
     * @throws IllegalStateException when manifest evidence is malformed
     */
    public static Map<String, BlueFixtureCategory> loadFixtureCategories() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return Collections.emptyMap();
        }
        Map<String, BlueFixtureCategory> categories = new LinkedHashMap<>();
        for (Map<?, ?> file : behaviorFixtureFiles(manifest)) {
            Map<?, ?> fixture = loadFixture(file);
            Object id = fixture.get(ConformanceReportConstants.Field.ID);
            Object category = fixture.get(
                    ConformanceReportConstants.Field.CATEGORY);
            if (id == null || category == null) {
                throw new IllegalStateException(
                        "Blue Language fixture is missing id/category: "
                                + file.get(
                                        RegistryManifestConstants.FIELD_PATH));
            }
            categories.put(id.toString(), BlueFixtureCategory.fromLabel(category.toString()));
        }
        return categories;
    }

    /**
     * Loads fixture operations keyed by identity.
     *
     * @return immutable fixture-operation map
     * @throws IllegalStateException when manifest evidence is malformed
     */
    public static Map<String, String> loadFixtureOperations() {
        Map<?, ?> manifest = loadFixtureManifest();
        Map<String, String> operations = new LinkedHashMap<>();
        for (Map<?, ?> file : behaviorFixtureFiles(manifest)) {
            Map<?, ?> fixture = loadFixture(file);
            Object id = fixture.get(ConformanceReportConstants.Field.ID);
            Object operation = fixture.get(
                    ConformanceReportConstants.Field.OPERATION);
            if (id == null || operation == null) {
                throw new IllegalStateException(
                        "Blue Language fixture is missing id/operation: "
                                + file.get(
                                        RegistryManifestConstants.FIELD_PATH));
            }
            operations.put(id.toString(), operation.toString());
        }
        return Collections.unmodifiableMap(operations);
    }

    /**
     * Recomputes the canonical fixture manifest identity.
     *
     * @return SHA-256 fixture package identity
     * @throws IllegalStateException when the manifest cannot be read or hashed
     */
    public static String computeFixturePackageIdentity() {
        try {
            Map<?, ?> loaded = loadFixtureManifest();
            if (loaded == null) {
                throw new IllegalStateException("Blue Language fixture manifest not found");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : loaded.entrySet()) {
                normalized.put(entry.getKey().toString(), entry.getValue());
            }
            normalized.put(
                    RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
                    null);
            Object canonical = canonicalizeJsonValue(normalized);
            // The shared mapper is intentionally pretty-printing and omits
            // nulls for public Blue serialization. Package identity requires
            // compact canonical JSON and an explicit packageIdentity:null.
            byte[] canonicalJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + toHex(digest.digest(canonicalJson));
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Unable to calculate Blue Language fixture package identity", e);
        }
    }

    /**
     * Verifies the manifest identity and every declared file digest.
     *
     * @return whether all fixture package evidence matches
     */
    public static boolean fixturePackageIdentityMatchesFixtureFiles() {
        String identity = loadFixturePackageIdentity(null);
        return identity != null
                && identity.equals(computeFixturePackageIdentity())
                && manifestFileDigestsMatch();
    }

    /**
     * Tests whether an identity has a release-grade format.
     *
     * @param identity identity to inspect
     * @return whether the identity is non-placeholder and well formed
     */
    public static boolean isReleaseGradeFixtureIdentity(String identity) {
        if (identity == null || identity.trim().isEmpty()) {
            return false;
        }
        String trimmed = identity.trim();
        if (trimmed.contains("local-dev")
                || trimmed.contains("pending")
                || trimmed.contains("unavailable")) {
            return false;
        }
        if (trimmed.startsWith("sha256:")) {
            return trimmed.substring("sha256:".length()).matches("[0-9a-f]{64}");
        }
        return trimmed.startsWith("blueId:") && trimmed.length() > "blueId:".length();
    }

    private static Map<?, ?> loadFixtureManifest() {
        try (InputStream inputStream = BlueConformanceReport.class.getClassLoader()
                .getResourceAsStream(FIXTURE_MANIFEST_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "Missing Blue Language 1.0 fixture manifest: " + FIXTURE_MANIFEST_RESOURCE);
            }
            return UncheckedObjectMapper.YAML_MAPPER.readValue(inputStream, Map.class);
        } catch (IOException invalidManifest) {
            throw new IllegalStateException(
                    "Unable to load Blue Language 1.0 fixture manifest", invalidManifest);
        }
    }

    private static List<Map<?, ?>> behaviorFixtureFiles(Map<?, ?> manifest) {
        Object files = manifest.get("files");
        if (!(files instanceof List)) {
            throw new IllegalStateException("Blue Language fixture manifest has no files list");
        }
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object file : (List<?>) files) {
            if (!(file instanceof Map)) {
                throw new IllegalStateException("Blue Language fixture manifest contains a non-map file entry");
            }
            Map<?, ?> entry = (Map<?, ?>) file;
            if ("behavior-fixture".equals(String.valueOf(entry.get("role")))) {
                result.add(entry);
            }
        }
        return result;
    }

    private static Map<?, ?> loadFixture(Map<?, ?> file) {
        Object path = file.get(RegistryManifestConstants.FIELD_PATH);
        if (path == null || path.toString().trim().isEmpty()) {
            throw new IllegalStateException("Blue Language fixture manifest entry is missing path");
        }
        try {
            return UncheckedObjectMapper.YAML_MAPPER.readValue(
                    readFixtureResource("blue-language-1.0/fixtures/" + path), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read Blue Language fixture " + path, e);
        }
    }

    private static boolean manifestFileDigestsMatch() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return false;
        }
        Object files = manifest.get("files");
        if (!(files instanceof List)) {
            return false;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (Object file : (List<?>) files) {
                if (!(file instanceof Map)) {
                    return false;
                }
                Map<?, ?> entry = (Map<?, ?>) file;
                Object path = entry.get(
                        RegistryManifestConstants.FIELD_PATH);
                Object expectedBytes = entry.get("bytes");
                Object expectedDigest = entry.get(
                        RegistryManifestConstants.FIELD_SHA256);
                if (path == null || expectedBytes == null || expectedDigest == null) {
                    return false;
                }
                byte[] bytes = normalizeLineEndings(readFixtureResource(
                        "blue-language-1.0/fixtures/" + path));
                if (((Number) expectedBytes).longValue() != bytes.length) {
                    return false;
                }
                if (!expectedDigest.toString().equals(toHex(sha256.digest(bytes)))) {
                    return false;
                }
            }
            return true;
        } catch (NoSuchAlgorithmException | RuntimeException invalidManifest) {
            return false;
        }
    }

    private static Object canonicalizeJsonValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                sorted.put(entry.getKey().toString(), canonicalizeJsonValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> values = new ArrayList<>();
            for (Object element : (List<?>) value) {
                values.add(canonicalizeJsonValue(element));
            }
            return values;
        }
        return value;
    }

    private static byte[] readFixtureResource(String resource) {
        try (InputStream inputStream = BlueConformanceReport.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing Blue Language fixture resource: " + resource);
            }
            return readAll(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read Blue Language fixture resource: " + resource, e);
        }
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static byte[] normalizeLineEndings(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static Set<String> requiredFixtureIds() {
        List<String> ids = loadFixtureIds();
        if (ids.size() != BlueReleaseConformanceReport.LANGUAGE_FIXTURE_COUNT
                || new LinkedHashSet<>(ids).size()
                != BlueReleaseConformanceReport.LANGUAGE_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Blue Language 1.0 requires exactly "
                            + BlueReleaseConformanceReport.LANGUAGE_FIXTURE_COUNT
                            + " unique behavior fixtures; found "
                            + ids.size());
        }
        String calculatedIdentity = computeFixturePackageIdentity();
        boolean fileDigestsMatch = manifestFileDigestsMatch();
        if (!FIXTURE_PACKAGE_IDENTITY.equals(calculatedIdentity)
                || !fileDigestsMatch) {
            throw new IllegalStateException(
                    "Blue Language 1.0 fixture package does not match the release"
                            + " (expectedIdentity=" + FIXTURE_PACKAGE_IDENTITY
                            + ", calculatedIdentity=" + calculatedIdentity
                            + ", fileDigestsMatch=" + fileDigestsMatch + ").");
        }
        return new LinkedHashSet<>(ids);
    }
}
