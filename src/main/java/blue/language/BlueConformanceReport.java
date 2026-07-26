package blue.language;

import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.UncheckedObjectMapper;

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

public final class BlueConformanceReport {

    public static final String FIXTURE_MANIFEST_RESOURCE = "blue-language-1.0/fixtures/manifest.yaml";
    public static final String FIXTURE_PACKAGE_IDENTITY =
            "sha256:277418303ae10aade4029a398f880a8d0f2b321d4943492ac811287c21eb3dbb";
    public static final String BLUE_SPEC_SOURCE =
            "blue-language-1.0-final-implementation-baseline";
    /** @deprecated use {@link #FIXTURE_PACKAGE_IDENTITY}. */
    @Deprecated
    public static final String CANDIDATE_FIXTURE_PACKAGE_IDENTITY = FIXTURE_PACKAGE_IDENTITY;
    /** @deprecated use {@link #BLUE_SPEC_SOURCE}. */
    @Deprecated
    public static final String CANDIDATE_BLUE_SPEC_SOURCE = BLUE_SPEC_SOURCE;
    private static final Set<String> REQUIRED_FIXTURE_IDS = requiredFixtureIds();

    private final String specVersion;
    private final Map<String, String> coreRegistryBlueIds;
    private final String fixturePackageIdentity;
    private final List<String> fixtureIds;
    private final List<String> passedFixtureIds;
    private final List<String> failedFixtureIds;
    private final List<BlueConformanceFailure> failures;
    private final Map<String, BlueFixtureCategory> fixtureCategories;

    public BlueConformanceReport(String specVersion,
                                 Map<String, String> coreRegistryBlueIds,
                                 String fixturePackageIdentity,
                                 List<String> passedFixtureIds) {
        this(specVersion, coreRegistryBlueIds, fixturePackageIdentity, Collections.emptyList(), passedFixtureIds, Collections.emptyList(), Collections.emptyMap());
    }

    public BlueConformanceReport(String specVersion,
                                 Map<String, String> coreRegistryBlueIds,
                                 String fixturePackageIdentity,
                                 List<String> fixtureIds,
                                 List<String> passedFixtureIds,
                                 List<String> failedFixtureIds,
                                 Map<String, BlueFixtureCategory> fixtureCategories) {
        this(specVersion, coreRegistryBlueIds, fixturePackageIdentity, fixtureIds, passedFixtureIds, failedFixtureIds, fixtureCategories, Collections.emptyList());
    }

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

    public String getSpecVersion() {
        return specVersion;
    }

    public Map<String, String> getCoreRegistryBlueIds() {
        return coreRegistryBlueIds;
    }

    public String getFixturePackageIdentity() {
        return fixturePackageIdentity;
    }

    public List<String> getFixtureIds() {
        return fixtureIds;
    }

    public List<String> getPassedFixtureIds() {
        return passedFixtureIds;
    }

    public List<String> getFailedFixtureIds() {
        return failedFixtureIds;
    }

    public List<BlueConformanceFailure> getFailures() {
        return failures;
    }

    public Map<String, BlueFixtureCategory> getFixtureCategories() {
        return fixtureCategories;
    }

    public String getCoreRegistryPackageIdentity() {
        return BlueCoreTypeRegistry.INSTANCE.packageIdentity();
    }

    /**
     * Complete one-result-per-fixture report for CI and release tooling.
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
            result.put("id", id);
            BlueFixtureCategory category = fixtureCategories.get(id);
            result.put("category", category == null ? null : category.getLabel());
            result.put("operation", operations.get(id));
            BlueConformanceFailure failure = failuresById.get(id);
            if (failure != null) {
                result.put("status", "FAIL");
                result.put("errorCategory", failure.getErrorCategory() == null
                        ? null : failure.getErrorCategory().name());
                result.put("exceptionClass", failure.getExceptionClass());
                result.put("message", failure.getMessage());
            } else if (passed.contains(id)) {
                result.put("status", "PASS");
            } else {
                result.put("status", "FAIL");
                result.put("errorCategory", "HarnessDidNotRunFixture");
                result.put("message", "Fixture has no execution result.");
            }
            results.add(result);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("specificationVersion", specVersion);
        report.put("registryPackageIdentity", getCoreRegistryPackageIdentity());
        report.put("fixturePackageIdentity", fixturePackageIdentity);
        report.put("coreRegistryBlueIds", coreRegistryBlueIds);
        report.put("fixtureCount", fixtureIds.size());
        report.put("passedCount", passedFixtureIds.size());
        report.put("failedCount", fixtureIds.size() - passedFixtureIds.size());
        report.put("results", results);
        return Collections.unmodifiableMap(report);
    }

    public String toMachineReadableJson() {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(toMachineReadableMap());
    }

    public boolean isReleaseGradeFixtureIdentity() {
        return FIXTURE_PACKAGE_IDENTITY.equals(fixturePackageIdentity)
                && isReleaseGradeFixtureIdentity(fixturePackageIdentity);
    }

    public boolean hasRequiredFixtureCoverage() {
        return new HashSet<>(fixtureIds).containsAll(REQUIRED_FIXTURE_IDS);
    }

    public boolean hasExactRequiredFixtureSet() {
        return new LinkedHashSet<>(fixtureIds).equals(REQUIRED_FIXTURE_IDS);
    }

    public static Set<String> requiredFixtureIdsForBlueLanguage10() {
        return Collections.unmodifiableSet(REQUIRED_FIXTURE_IDS);
    }

    public static String loadFixturePackageIdentity(String fallback) {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return fallback;
        }
        Object identity = manifest.get("packageIdentity");
        return identity == null || identity.toString().trim().isEmpty()
                ? fallback
                : identity.toString();
    }

    public static List<String> loadFixtureIds() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (Map<?, ?> file : behaviorFixtureFiles(manifest)) {
            Map<?, ?> fixture = loadFixture(file);
            Object id = fixture.get("id");
            if (id == null || id.toString().trim().isEmpty()) {
                throw new IllegalStateException("Blue Language fixture is missing id: " + file.get("path"));
            }
            ids.add(id.toString());
        }
        return ids;
    }

    public static Map<String, BlueFixtureCategory> loadFixtureCategories() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return Collections.emptyMap();
        }
        Map<String, BlueFixtureCategory> categories = new LinkedHashMap<>();
        for (Map<?, ?> file : behaviorFixtureFiles(manifest)) {
            Map<?, ?> fixture = loadFixture(file);
            Object id = fixture.get("id");
            Object category = fixture.get("category");
            if (id == null || category == null) {
                throw new IllegalStateException(
                        "Blue Language fixture is missing id/category: " + file.get("path"));
            }
            categories.put(id.toString(), BlueFixtureCategory.fromLabel(category.toString()));
        }
        return categories;
    }

    public static Map<String, String> loadFixtureOperations() {
        Map<?, ?> manifest = loadFixtureManifest();
        Map<String, String> operations = new LinkedHashMap<>();
        for (Map<?, ?> file : behaviorFixtureFiles(manifest)) {
            Map<?, ?> fixture = loadFixture(file);
            Object id = fixture.get("id");
            Object operation = fixture.get("operation");
            if (id == null || operation == null) {
                throw new IllegalStateException(
                        "Blue Language fixture is missing id/operation: " + file.get("path"));
            }
            operations.put(id.toString(), operation.toString());
        }
        return Collections.unmodifiableMap(operations);
    }

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
            normalized.put("packageIdentity", null);
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

    public static boolean fixturePackageIdentityMatchesFixtureFiles() {
        String identity = loadFixturePackageIdentity(null);
        return identity != null
                && identity.equals(computeFixturePackageIdentity())
                && manifestFileDigestsMatch();
    }

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
        Object path = file.get("path");
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
                Object path = entry.get("path");
                Object expectedBytes = entry.get("bytes");
                Object expectedDigest = entry.get("sha256");
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
        if (ids.size() != 125 || new LinkedHashSet<>(ids).size() != 125) {
            throw new IllegalStateException(
                    "Blue Language 1.0 requires exactly 125 unique behavior fixtures; found "
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
