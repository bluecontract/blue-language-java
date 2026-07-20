package blue.language;

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

public final class BlueConformanceReport {

    public static final String FIXTURE_MANIFEST_RESOURCE = "blue-language-1.0/fixtures/manifest.yaml";
    public static final String CANDIDATE_FIXTURE_PACKAGE_IDENTITY =
            "sha256:274f62aa1e9a1b189f0dd9c832900160edf7e1fd837adb0da5aa717dc9e3c42d";
    public static final String CANDIDATE_BLUE_SPEC_SOURCE =
            "feat/conformance-fixture-expansion@07814f5";
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

    public boolean isReleaseGradeFixtureIdentity() {
        return CANDIDATE_FIXTURE_PACKAGE_IDENTITY.equals(fixturePackageIdentity)
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
        Object identity = manifest.get("fixturePackageIdentity");
        return identity == null || identity.toString().trim().isEmpty()
                ? fallback
                : identity.toString();
    }

    public static List<String> loadFixtureIds() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return Collections.emptyList();
        }
        Object fixtures = manifest.get("fixtures");
        if (!(fixtures instanceof List)) {
            return Collections.emptyList();
        }
        List<?> fixtureList = (List<?>) fixtures;
        List<String> ids = new ArrayList<>();
        for (Object fixture : fixtureList) {
            if (fixture instanceof Map) {
                Object id = ((Map<?, ?>) fixture).get("id");
                if (id != null) {
                    ids.add(id.toString());
                }
            }
        }
        return ids;
    }

    public static Map<String, BlueFixtureCategory> loadFixtureCategories() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return Collections.emptyMap();
        }
        Object fixtures = manifest.get("fixtures");
        if (!(fixtures instanceof List)) {
            return Collections.emptyMap();
        }
        Map<String, BlueFixtureCategory> categories = new LinkedHashMap<>();
        for (Object fixture : (List<?>) fixtures) {
            if (fixture instanceof Map) {
                Map<?, ?> fixtureMap = (Map<?, ?>) fixture;
                Object id = fixtureMap.get("id");
                Object category = fixtureMap.get("category");
                if (id != null && category != null) {
                    categories.put(id.toString(), BlueFixtureCategory.fromLabel(category.toString()));
                }
            }
        }
        return categories;
    }

    public static String computeFixturePackageIdentity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("manifest.yaml\n".getBytes(StandardCharsets.UTF_8));
            digest.update(normalizeManifestForIdentity(readFixtureResource(FIXTURE_MANIFEST_RESOURCE)));
            Map<?, ?> manifest = loadFixtureManifest();
            if (manifest == null) {
                throw new IllegalStateException("Blue Language fixture manifest not found");
            }
            Object fixtures = manifest.get("fixtures");
            if (!(fixtures instanceof List)) {
                throw new IllegalStateException("Blue Language fixture manifest has no fixture list");
            }
            for (Object fixture : (List<?>) fixtures) {
                if (!(fixture instanceof Map)) {
                    throw new IllegalStateException("Blue Language fixture manifest contains a non-map fixture entry");
                }
                Object path = ((Map<?, ?>) fixture).get("path");
                if (path == null || path.toString().trim().isEmpty()) {
                    throw new IllegalStateException("Blue Language fixture manifest entry is missing path");
                }
                String fixturePath = path.toString();
                digest.update(("\n--- " + fixturePath + "\n").getBytes(StandardCharsets.UTF_8));
                digest.update(normalizeLineEndings(readFixtureResource("blue-language-1.0/fixtures/" + fixturePath)));
            }
            return "sha256:" + toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    public static boolean fixturePackageIdentityMatchesFixtureFiles() {
        String identity = loadFixturePackageIdentity(null);
        return identity != null && identity.equals(computeFixturePackageIdentity());
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
                return null;
            }
            return UncheckedObjectMapper.YAML_MAPPER.readValue(inputStream, Map.class);
        } catch (Exception ignored) {
            return null;
        }
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

    private static byte[] normalizeManifestForIdentity(byte[] bytes) {
        String normalized = new String(normalizeLineEndings(bytes), StandardCharsets.UTF_8)
                .replaceFirst("(?m)^fixturePackageIdentity:.*$", "fixturePackageIdentity: \"\"");
        return normalized.getBytes(StandardCharsets.UTF_8);
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
        return new LinkedHashSet<>(loadFixtureIds());
    }
}
