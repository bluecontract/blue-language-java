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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlueContractsConformanceReport {

    public static final String FIXTURE_MANIFEST_RESOURCE = "blue-contracts-1.0/fixtures/manifest.yaml";
    public static final String BLUE_CONTRACTS_1_0_FIXTURE_PACKAGE_IDENTITY =
            "sha256:22713df4d50a38b91762aea1e1a360019c1d16c2584ca1bac022305edb4c66d1";

    private final String specVersion;
    private final String fixturePackageIdentity;
    private final List<String> fixtureIds;
    private final List<String> passedFixtureIds;
    private final List<String> failedFixtureIds;
    private final Map<String, BlueContractsFixtureCategory> fixtureCategories;
    private final List<BlueContractsConformanceFailure> failures;

    public BlueContractsConformanceReport(String specVersion,
                                          String fixturePackageIdentity,
                                          List<String> fixtureIds,
                                          List<String> passedFixtureIds,
                                          List<String> failedFixtureIds,
                                          Map<String, BlueContractsFixtureCategory> fixtureCategories,
                                          List<BlueContractsConformanceFailure> failures) {
        this.specVersion = specVersion;
        this.fixturePackageIdentity = fixturePackageIdentity;
        this.fixtureIds = Collections.unmodifiableList(new ArrayList<>(fixtureIds));
        this.passedFixtureIds = Collections.unmodifiableList(new ArrayList<>(passedFixtureIds));
        List<String> effectiveFailed = new ArrayList<>(failedFixtureIds);
        if (failures != null && !failures.isEmpty()) {
            effectiveFailed.clear();
            for (BlueContractsConformanceFailure failure : failures) {
                effectiveFailed.add(failure.getFixtureId());
            }
        }
        this.failedFixtureIds = Collections.unmodifiableList(effectiveFailed);
        this.fixtureCategories = Collections.unmodifiableMap(new LinkedHashMap<>(fixtureCategories));
        this.failures = Collections.unmodifiableList(new ArrayList<>(
                failures != null ? failures : Collections.emptyList()));
    }

    public String getSpecVersion() {
        return specVersion;
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

    public Map<String, BlueContractsFixtureCategory> getFixtureCategories() {
        return fixtureCategories;
    }

    public List<BlueContractsConformanceFailure> getFailures() {
        return failures;
    }

    public boolean hasRequiredFixtureCoverage() {
        return fixtureIds.containsAll(requiredFixtureIdsForContracts10());
    }

    public boolean hasExactRequiredFixtureSet() {
        Set<String> fixtureSet = new LinkedHashSet<>(fixtureIds);
        Set<String> requiredSet = new LinkedHashSet<>(requiredFixtureIdsForContracts10());
        return fixtureSet.equals(requiredSet) && fixtureIds.size() == requiredSet.size();
    }

    public boolean isOfficialContracts10FixturePackage() {
        return BLUE_CONTRACTS_1_0_FIXTURE_PACKAGE_IDENTITY.equals(fixturePackageIdentity);
    }

    public static List<String> requiredFixtureIdsForContracts10() {
        return Collections.unmodifiableList(loadFixtureIds());
    }

    public static String loadFixturePackageIdentity(String fallback) {
        Map<?, ?> manifest = loadFixtureManifest();
        Object identity = manifest != null ? manifest.get("fixturePackageIdentity") : null;
        return identity == null || identity.toString().trim().isEmpty() ? fallback : identity.toString();
    }

    public static List<String> loadFixtureIds() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null || !(manifest.get("fixtures") instanceof List)) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (Object fixture : (List<?>) manifest.get("fixtures")) {
            if (fixture instanceof Map && ((Map<?, ?>) fixture).get("id") != null) {
                ids.add(((Map<?, ?>) fixture).get("id").toString());
            }
        }
        return ids;
    }

    public static Map<String, BlueContractsFixtureCategory> loadFixtureCategories() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null || !(manifest.get("fixtures") instanceof List)) {
            return Collections.emptyMap();
        }
        Map<String, BlueContractsFixtureCategory> categories = new LinkedHashMap<>();
        for (Object fixture : (List<?>) manifest.get("fixtures")) {
            if (fixture instanceof Map) {
                Map<?, ?> fixtureMap = (Map<?, ?>) fixture;
                Object id = fixtureMap.get("id");
                Object category = fixtureMap.get("category");
                if (id != null && category != null) {
                    categories.put(id.toString(), BlueContractsFixtureCategory.fromLabel(category.toString()));
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
            if (manifest == null || !(manifest.get("fixtures") instanceof List)) {
                throw new IllegalStateException("Blue Contracts fixture manifest has no fixture list");
            }
            for (Object fixture : (List<?>) manifest.get("fixtures")) {
                if (!(fixture instanceof Map)) {
                    throw new IllegalStateException("Blue Contracts fixture entry must be a map");
                }
                Object path = ((Map<?, ?>) fixture).get("path");
                if (path == null || path.toString().trim().isEmpty()) {
                    throw new IllegalStateException("Blue Contracts fixture entry is missing path");
                }
                String fixturePath = path.toString();
                digest.update(("\n--- " + fixturePath + "\n").getBytes(StandardCharsets.UTF_8));
                digest.update(normalizeLineEndings(readFixtureResource("blue-contracts-1.0/fixtures/" + fixturePath)));
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

    @SuppressWarnings("unchecked")
    private static Map<?, ?> loadFixtureManifest() {
        try (InputStream inputStream = BlueContractsConformanceReport.class.getClassLoader()
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
        try (InputStream inputStream = BlueContractsConformanceReport.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing Blue Contracts fixture resource: " + resource);
            }
            return readAll(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read Blue Contracts fixture resource: " + resource, e);
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
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }
}
