package blue.language.conformance.api;

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

/**
 * Result and package binding for the exact Blue Contracts 1.0 implementation
 * baseline. The report deliberately has no skipped-fixture collection: every
 * inventoried executable fixture must have a PASS or FAIL record.
 */
public final class BlueContractsConformanceReport {

    /** Classpath resources bound into the released conformance package. */
    public static final String FIXTURE_ROOT_RESOURCE = "blue-contracts-1.0/fixtures/";
    /** Authoritative Contracts fixture manifest resource. */
    public static final String FIXTURE_MANIFEST_RESOURCE = FIXTURE_ROOT_RESOURCE + "manifest.yaml";
    /** Contracts gas manifest resource. */
    public static final String GAS_MANIFEST_RESOURCE = "blue/language/processor/contracts-gas-1.0.yaml";
    /** Contracts registry manifest resource. */
    public static final String REGISTRY_MANIFEST_RESOURCE = "registry/blue-contracts-1.0/manifest.yaml";
    /** Combined release manifest resource. */
    public static final String RELEASE_MANIFEST_RESOURCE =
            "release/blue-language-1.0-contracts-1.0-bex-2.0/RELEASE-MANIFEST.yaml";
    /** Normative Contracts specification resource. */
    public static final String CONTRACTS_SPECIFICATION_RESOURCE =
            "specifications/blue-contracts-and-processor-specification-1.0.md";
    /** Normative Language specification resource. */
    public static final String LANGUAGE_SPECIFICATION_RESOURCE =
            "specifications/blue-language-specification-1.0.md";

    /** Exact release and constituent package identities. */
    public static final String RELEASE_NAME =
            "blue-language-1.0-contracts-1.0-bex-2.0-coordination-1.0-final-implementation-baseline";
    /** Exact combined release package identity. */
    public static final String RELEASE_PACKAGE_IDENTITY =
            "sha256:f6165c10ab07ddd15fb99392753de43fa3afbd79d303a3cd6e300279f09b2cfa";
    /** Exact Language registry package identity. */
    public static final String LANGUAGE_REGISTRY_PACKAGE_IDENTITY =
            "sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e";
    /** Exact Language fixture package identity. */
    public static final String LANGUAGE_FIXTURE_PACKAGE_IDENTITY =
            "sha256:44465973c5c5a8c1e60712fc7970236015d9500e2e9e3fc904e364552ec74a55";
    /** Exact Contracts registry package identity. */
    public static final String CONTRACTS_REGISTRY_PACKAGE_IDENTITY =
            RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY;
    /** Exact Contracts gas package identity. */
    public static final String CONTRACTS_GAS_PACKAGE_IDENTITY =
            "sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5";
    /** Exact Contracts fixture package identity. */
    public static final String CONTRACTS_FIXTURE_PACKAGE_IDENTITY =
            "sha256:d8231b77e196af8ff268432cf5867466151e16f2d1aec5e493c8a16c3f2e8b18";

    /** Expected digests for release-bound manifests and specifications. */
    public static final String CONTRACTS_GAS_MANIFEST_SHA256 =
            "1f4054b77fc7ef01a3e62f5b29d209e84f26e85148c91b03fe48da2c3579408f";
    /** Published SHA-256 digest of the Contracts specification. */
    public static final String CONTRACTS_SPECIFICATION_SHA256 =
            "d2efc2a5df8cd7e81b17b8c0d5f7ad73c5dbcb91344a7e5714c60605732676c1";
    /** Published SHA-256 digest of the Language specification. */
    public static final String LANGUAGE_SPECIFICATION_SHA256 =
            "41291e52f520870bd3cc0665cdb085df8f10238853531a9e99d4409b6b63c92e";

    /**
     * Fixture envelopes may use YAML anchors for literal reuse. This parser is
     * separate from Blue's YAML parser because anchors are envelope syntax, not
     * part of the Blue value model.
     */
    private static final ObjectMapper FIXTURE_YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    private final String specVersion;
    private final String releaseName;
    private final String releasePackageIdentity;
    private final String languageRegistryPackageIdentity;
    private final String languageFixturePackageIdentity;
    private final String contractsRegistryPackageIdentity;
    private final String contractsGasPackageIdentity;
    private final String fixturePackageIdentity;
    private final List<String> fixtureIds;
    private final List<String> passedFixtureIds;
    private final List<String> failedFixtureIds;
    private final Map<String, BlueContractsFixtureCategory> fixtureCategories;
    private final List<BlueContractsConformanceFailure> failures;
    private final List<BlueContractsFixtureResult> fixtureResults;

    /**
     * Creates an immutable report and normalizes null collections to empty.
     *
     * @param specVersion Contracts specification version
     * @param releaseName release name
     * @param releasePackageIdentity exact release package identity
     * @param languageRegistryPackageIdentity language registry package identity
     * @param languageFixturePackageIdentity language fixture package identity
     * @param contractsRegistryPackageIdentity Contracts registry package
     *                                         identity
     * @param contractsGasPackageIdentity Contracts gas package identity
     * @param fixturePackageIdentity Contracts fixture package identity
     * @param fixtureIds all fixture identities
     * @param passedFixtureIds fixture identities that passed
     * @param failedFixtureIds fixture identities that failed
     * @param fixtureCategories categories keyed by fixture identity
     * @param failures detailed failure records
     * @param fixtureResults complete fixture result records
     */
    public BlueContractsConformanceReport(String specVersion,
                                          String releaseName,
                                          String releasePackageIdentity,
                                          String languageRegistryPackageIdentity,
                                          String languageFixturePackageIdentity,
                                          String contractsRegistryPackageIdentity,
                                          String contractsGasPackageIdentity,
                                          String fixturePackageIdentity,
                                          List<String> fixtureIds,
                                          List<String> passedFixtureIds,
                                          List<String> failedFixtureIds,
                                          Map<String, BlueContractsFixtureCategory> fixtureCategories,
                                          List<BlueContractsConformanceFailure> failures,
                                          List<BlueContractsFixtureResult> fixtureResults) {
        this.specVersion = specVersion;
        this.releaseName = releaseName;
        this.releasePackageIdentity = releasePackageIdentity;
        this.languageRegistryPackageIdentity = languageRegistryPackageIdentity;
        this.languageFixturePackageIdentity = languageFixturePackageIdentity;
        this.contractsRegistryPackageIdentity = contractsRegistryPackageIdentity;
        this.contractsGasPackageIdentity = contractsGasPackageIdentity;
        this.fixturePackageIdentity = fixturePackageIdentity;
        this.fixtureIds = immutableCopy(fixtureIds);
        this.passedFixtureIds = immutableCopy(passedFixtureIds);
        this.failures = Collections.unmodifiableList(new ArrayList<>(
                failures != null ? failures : Collections.<BlueContractsConformanceFailure>emptyList()));
        List<String> effectiveFailed = new ArrayList<>(
                failedFixtureIds != null ? failedFixtureIds : Collections.<String>emptyList());
        if (!this.failures.isEmpty()) {
            effectiveFailed.clear();
            for (BlueContractsConformanceFailure failure : this.failures) {
                effectiveFailed.add(failure.getFixtureId());
            }
        }
        this.failedFixtureIds = Collections.unmodifiableList(effectiveFailed);
        this.fixtureCategories = Collections.unmodifiableMap(new LinkedHashMap<>(
                fixtureCategories != null
                        ? fixtureCategories
                        : Collections.<String, BlueContractsFixtureCategory>emptyMap()));
        this.fixtureResults = Collections.unmodifiableList(new ArrayList<>(
                fixtureResults != null
                        ? fixtureResults
                        : Collections.<BlueContractsFixtureResult>emptyList()));
        validateResultPartition();
    }

    /**

     * Returns the Contracts specification version.

     *

     * @return specification version

     */
    public String getSpecVersion() {
        return specVersion;
    }

    /**

     * Returns the release name.

     *

     * @return release name

     */
    public String getReleaseName() {
        return releaseName;
    }

    /**

     * Returns the release package identity.

     *

     * @return release package identity

     */
    public String getReleasePackageIdentity() {
        return releasePackageIdentity;
    }

    /**

     * Returns the language registry identity.

     *

     * @return language registry identity

     */
    public String getLanguageRegistryPackageIdentity() {
        return languageRegistryPackageIdentity;
    }

    /**

     * Returns the language fixture identity.

     *

     * @return language fixture identity

     */
    public String getLanguageFixturePackageIdentity() {
        return languageFixturePackageIdentity;
    }

    /**

     * Returns the Contracts registry identity.

     *

     * @return Contracts registry identity

     */
    public String getContractsRegistryPackageIdentity() {
        return contractsRegistryPackageIdentity;
    }

    /**

     * Returns the Contracts gas identity.

     *

     * @return Contracts gas identity

     */
    public String getContractsGasPackageIdentity() {
        return contractsGasPackageIdentity;
    }

    /**

     * Returns the Contracts fixture identity.

     *

     * @return Contracts fixture identity

     */
    public String getFixturePackageIdentity() {
        return fixturePackageIdentity;
    }

    /**

     * Returns all fixture identities.

     *

     * @return immutable fixture identity list

     */
    public List<String> getFixtureIds() {
        return fixtureIds;
    }

    /**

     * Returns passed fixture identities.

     *

     * @return immutable passed-fixture list

     */
    public List<String> getPassedFixtureIds() {
        return passedFixtureIds;
    }

    /**

     * Returns failed fixture identities.

     *

     * @return immutable failed-fixture list

     */
    public List<String> getFailedFixtureIds() {
        return failedFixtureIds;
    }

    /**

     * Returns fixture categories.

     *

     * @return immutable category map

     */
    public Map<String, BlueContractsFixtureCategory> getFixtureCategories() {
        return fixtureCategories;
    }

    /**

     * Returns detailed failures.

     *

     * @return immutable failure list

     */
    public List<BlueContractsConformanceFailure> getFailures() {
        return failures;
    }

    /**

     * Returns complete fixture results.

     *

     * @return immutable result list

     */
    public List<BlueContractsFixtureResult> getFixtureResults() {
        return fixtureResults;
    }

    /**

     * Returns the skipped-fixture count, which is always zero.

     *

     * @return zero

     */
    public int getSkippedFixtureCount() {
        return 0;
    }

    /**

     * Tests full conformance.

     *

     * @return whether every release condition passes

     */
    public boolean isConformant() {
        return failures.isEmpty()
                && passedFixtureIds.equals(fixtureIds)
                && hasExactRequiredFixtureSet()
                && isOfficialContracts10FixturePackage();
    }

    /**

     * Tests required fixture coverage.

     *

     * @return whether every required fixture is present

     */
    public boolean hasRequiredFixtureCoverage() {
        return fixtureIds.containsAll(requiredFixtureIdsForContracts10());
    }

    /**

     * Tests exact fixture-set equality.

     *

     * @return whether the fixture set is exact

     */
    public boolean hasExactRequiredFixtureSet() {
        Set<String> fixtureSet = new LinkedHashSet<>(fixtureIds);
        Set<String> requiredSet = new LinkedHashSet<>(requiredFixtureIdsForContracts10());
        return fixtureSet.equals(requiredSet) && fixtureIds.size() == requiredSet.size();
    }

    /**

     * Tests the official fixture identity.

     *

     * @return whether the fixture package is official

     */
    public boolean isOfficialContracts10FixturePackage() {
        return CONTRACTS_FIXTURE_PACKAGE_IDENTITY.equals(fixturePackageIdentity);
    }

    /**

     * Builds the release-tool report.

     *

     * @return immutable machine-readable map

     */
    public Map<String, Object> toMachineReadableMap() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put(ConformanceReportConstants.Field.SCHEMA,
                ConformanceReportConstants.Schema.CONTRACTS);

        Map<String, Object> release = new LinkedHashMap<>();
        release.put(ConformanceReportConstants.Field.NAME, releaseName);
        release.put(ConformanceReportConstants.Field.PACKAGE_IDENTITY,
                releasePackageIdentity);
        report.put(ConformanceReportConstants.Field.RELEASE, release);

        Map<String, Object> language = new LinkedHashMap<>();
        language.put(ConformanceReportConstants.Field.SPECIFICATION_VERSION,
                ConformanceReportConstants.SPECIFICATION_VERSION_1_0);
        language.put(ConformanceReportConstants.Field.SPECIFICATION_SHA256,
                LANGUAGE_SPECIFICATION_SHA256);
        language.put(
                ConformanceReportConstants.Field.REGISTRY_PACKAGE_IDENTITY,
                languageRegistryPackageIdentity);
        language.put(ConformanceReportConstants.Field.FIXTURE_PACKAGE_IDENTITY,
                languageFixturePackageIdentity);
        report.put(ConformanceReportConstants.Field.LANGUAGE, language);

        Map<String, Object> contracts = new LinkedHashMap<>();
        contracts.put(ConformanceReportConstants.Field.SPECIFICATION_VERSION,
                specVersion);
        contracts.put(ConformanceReportConstants.Field.SPECIFICATION_SHA256,
                CONTRACTS_SPECIFICATION_SHA256);
        contracts.put(
                ConformanceReportConstants.Field.REGISTRY_PACKAGE_IDENTITY,
                contractsRegistryPackageIdentity);
        contracts.put(ConformanceReportConstants.Field.GAS_PACKAGE_IDENTITY,
                contractsGasPackageIdentity);
        contracts.put(
                ConformanceReportConstants.Field.FIXTURE_PACKAGE_IDENTITY,
                fixturePackageIdentity);
        report.put(ConformanceReportConstants.Field.CONTRACTS, contracts);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put(ConformanceReportConstants.Field.TOTAL, fixtureIds.size());
        summary.put(ConformanceReportConstants.Field.PASSED,
                passedFixtureIds.size());
        summary.put(ConformanceReportConstants.Field.FAILED,
                fixtureResults.isEmpty()
                ? fixtureIds.size() - passedFixtureIds.size()
                : failedFixtureIds.size());
        summary.put(ConformanceReportConstants.Field.SKIPPED, 0);
        summary.put(ConformanceReportConstants.Field.CONFORMANT,
                isConformant());
        report.put(ConformanceReportConstants.Field.SUMMARY, summary);
        report.put(ConformanceReportConstants.Field.FIXTURES,
                machineFixtureResults());
        return Collections.unmodifiableMap(report);
    }

    /**

     * Serializes the release-tool report.

     *

     * @return JSON report

     */
    public String toMachineReadableJson() {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(toMachineReadableMap());
    }

    /**

     * Returns normative Contracts 1.0 fixture identities.

     *

     * @return immutable identity list

     */
    public static List<String> requiredFixtureIdsForContracts10() {
        return Collections.unmodifiableList(loadFixtureIds());
    }

    /**
     * Loads the declared fixture package identity.
     *
     * @param fallback value used when no identity is declared
     * @return declared identity or {@code fallback}
     */
    public static String loadFixturePackageIdentity(String fallback) {
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
    public static List<String> loadFixtureIds() {
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
    public static Map<String, BlueContractsFixtureCategory> loadFixtureCategories() {
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
    public static String computeFixturePackageIdentity() {
        return computeYamlPackageIdentity(
                FIXTURE_MANIFEST_RESOURCE,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
    }

    /**

     * Recomputes the gas package identity.

     *

     * @return gas package identity

     */
    public static String computeGasPackageIdentity() {
        return computeYamlPackageIdentity(
                GAS_MANIFEST_RESOURCE,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
    }

    /**

     * Recomputes the registry package identity.

     *

     * @return registry package identity

     */
    public static String computeRegistryPackageIdentity() {
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
    public static String computeReleasePackageIdentity() {
        return computeYamlPackageIdentity(
                RELEASE_MANIFEST_RESOURCE,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
    }

    /**

     * Verifies fixture identity and file digests.

     *

     * @return whether all evidence matches

     */
    public static boolean fixturePackageIdentityMatchesFixtureFiles() {
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
    public static void validateFixturePackageIntegrity() {
        JsonNode manifest = requireYamlResource(FIXTURE_MANIFEST_RESOURCE);
        requireText(manifest, "fixturePackage", "blue-contracts-conformance");
        requireText(
                manifest,
                RegistryManifestConstants.FIELD_SPECIFICATION_VERSION,
                ConformanceReportConstants.SPECIFICATION_VERSION_1_0);
        requireText(manifest, "schemaVersion", "blue-contracts-fixture/1.0");
        requireText(manifest, "registryPackageIdentity", CONTRACTS_REGISTRY_PACKAGE_IDENTITY);
        requireText(manifest, "gasSchedule", "blue-contracts/gas/1.0");
        requireText(manifest, "gasManifestPackageIdentity", CONTRACTS_GAS_PACKAGE_IDENTITY);
        requireText(manifest, "gasManifestSha256", CONTRACTS_GAS_MANIFEST_SHA256);
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
        requireCount(manifest, "behaviorFixtureCount", behavior);
        requireCount(manifest, "gasFixtureCount", gas);
        requireCount(manifest, "vectorCount", 90);
        if (behavior
                != ConformanceReportConstants.FixtureCount.CONTRACTS_BEHAVIOR
                || gas
                != ConformanceReportConstants.FixtureCount.CONTRACTS_GAS) {
            throw new IllegalStateException(
                    "Contracts fixture inventory must contain 82 behavior and 58 gas fixtures");
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
    public static void validateReleaseBindings() {
        JsonNode release = requireYamlResource(RELEASE_MANIFEST_RESOURCE);
        requireText(release, "release", RELEASE_NAME);
        JsonNode components = release.get("components");
        if (components == null || !components.isObject()) {
            throw new IllegalStateException("Release components object is required");
        }
        requireText(components, "languageRegistryPackage", LANGUAGE_REGISTRY_PACKAGE_IDENTITY);
        requireText(components, "languageFixturePackage", LANGUAGE_FIXTURE_PACKAGE_IDENTITY);
        requireText(components, "contractsRegistryPackage", CONTRACTS_REGISTRY_PACKAGE_IDENTITY);
        requireText(components, "contractsGasPackage", CONTRACTS_GAS_PACKAGE_IDENTITY);
        requireText(components, "contractsFixturePackage", CONTRACTS_FIXTURE_PACKAGE_IDENTITY);
        requireText(
                release,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
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
    public static JsonNode readFixture(String path) {
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
    public static List<FixtureInventoryEntry> loadFixtureInventory() {
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
        for (JsonNode file : files) {
            String role = file.path("role").asText();
            if (!"behavior-fixture".equals(role) && !"gas-fixture".equals(role)) {
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
            } else {
                gas++;
            }
        }
        if (behavior
                != ConformanceReportConstants.FixtureCount.CONTRACTS_BEHAVIOR
                || gas
                != ConformanceReportConstants.FixtureCount.CONTRACTS_GAS
                || entries.size()
                != BlueReleaseConformanceReport.CONTRACTS_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Contracts executable inventory must contain exactly "
                            + "82 behavior and 58 gas fixtures; found "
                            + behavior + " behavior and " + gas + " gas");
        }
        return Collections.unmodifiableList(entries);
    }

    private void validateResultPartition() {
        Set<String> all = new LinkedHashSet<>(fixtureIds);
        if (all.size() != fixtureIds.size()) {
            throw new IllegalArgumentException("Fixture IDs must be unique");
        }
        Set<String> passed = new LinkedHashSet<>(passedFixtureIds);
        Set<String> failed = new LinkedHashSet<>(failedFixtureIds);
        if (passed.size() != passedFixtureIds.size()
                || failed.size() != failedFixtureIds.size()) {
            throw new IllegalArgumentException(
                    "Fixture outcome IDs must be unique");
        }
        Set<String> overlap = new LinkedHashSet<>(passed);
        overlap.retainAll(failed);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("Fixtures cannot both pass and fail: " + overlap);
        }
        if (!all.containsAll(passed) || !all.containsAll(failed)) {
            throw new IllegalArgumentException("Fixture outcomes contain unknown fixture IDs");
        }
        if (!fixtureCategories.keySet().equals(all)) {
            throw new IllegalArgumentException(
                    "Every fixture must have exactly one category");
        }
        if (!fixtureResults.isEmpty()) {
            Set<String> resultIds = new LinkedHashSet<>();
            Set<String> resultPasses = new LinkedHashSet<>();
            Set<String> resultFailures = new LinkedHashSet<>();
            for (BlueContractsFixtureResult result : fixtureResults) {
                if (!resultIds.add(result.getFixtureId())) {
                    throw new IllegalArgumentException(
                            "Duplicate fixture result: " + result.getFixtureId());
                }
                if (result.getStatus()
                        == BlueContractsFixtureResult.Status.PASS) {
                    resultPasses.add(result.getFixtureId());
                } else {
                    resultFailures.add(result.getFixtureId());
                }
            }
            if (!resultIds.equals(all)) {
                throw new IllegalArgumentException(
                        "Every fixture must have exactly one machine-readable result");
            }
            Set<String> partition = new LinkedHashSet<>(passed);
            partition.addAll(failed);
            if (!partition.equals(all)
                    || !resultPasses.equals(passed)
                    || !resultFailures.equals(failed)) {
                throw new IllegalArgumentException(
                        "Machine-readable results must exactly match "
                                + "the PASS/FAIL fixture partition");
            }
            Set<String> failureIds = new LinkedHashSet<>();
            for (BlueContractsConformanceFailure failure : failures) {
                if (!failureIds.add(failure.getFixtureId())) {
                    throw new IllegalArgumentException(
                            "Duplicate fixture failure: "
                                    + failure.getFixtureId());
                }
            }
            if (!failureIds.equals(failed)) {
                throw new IllegalArgumentException(
                        "Every failed fixture must have exactly one failure");
            }
        }
    }

    private static String computeYamlPackageIdentity(String resource, String... nulledFields) {
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

    private static JsonNode loadYamlResource(String resource) {
        try (InputStream input = BlueContractsConformanceReport.class.getClassLoader()
                .getResourceAsStream(resource)) {
            return input == null ? null : FIXTURE_YAML.readTree(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read YAML resource: " + resource, ex);
        }
    }

    private static JsonNode requireYamlResource(String resource) {
        JsonNode node = loadYamlResource(resource);
        if (node == null) {
            throw new IllegalStateException("Missing required Contracts resource: " + resource);
        }
        return node;
    }

    private static byte[] readRequiredResource(String resource) {
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

    private static void assertRawResourceDigest(String resource, String expected) {
        String actual = sha256Hex(readRequiredResource(resource));
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Contracts resource digest mismatch for " + resource
                            + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static byte[] normalizeLineEndings(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] bytes) {
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

    private static void validateRelativeResourcePath(String path) {
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

    private static void requireText(JsonNode object, String field, String expected) {
        String actual = requiredText(object, field);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Contracts package field " + field + " expected " + expected + " but was " + actual);
        }
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object != null ? object.get(field) : null;
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalStateException("Required non-empty text field is missing: " + field);
        }
        return value.asText();
    }

    private static void requireCount(JsonNode manifest, String field, int expected) {
        if (!manifest.has(field) || manifest.get(field).asInt(-1) != expected) {
            throw new IllegalStateException(
                    "Contracts fixture manifest " + field + " mismatch: expected " + expected);
        }
    }

    private static List<String> immutableCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(
                values != null ? values : Collections.<String>emptyList()));
    }

    private List<Map<String, Object>> machineFixtureResults() {
        Map<String, BlueContractsFixtureResult> byId = new LinkedHashMap<>();
        for (BlueContractsFixtureResult result : fixtureResults) {
            byId.put(result.getFixtureId(), result);
        }
        List<Map<String, Object>> encoded = new ArrayList<>(fixtureIds.size());
        for (String fixtureId : fixtureIds) {
            BlueContractsFixtureResult result = byId.get(fixtureId);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put(ConformanceReportConstants.Field.ID, fixtureId);
            if (result == null) {
                BlueContractsFixtureCategory category =
                        fixtureCategories.get(fixtureId);
                value.put(ConformanceReportConstants.Field.CATEGORY,
                        category != null ? category.getLabel() : null);
                value.put(ConformanceReportConstants.Field.STATUS,
                        ConformanceReportConstants.Status.FAIL);
                value.put(ConformanceReportConstants.Field.ERROR_CATEGORY,
                        ConformanceReportConstants.ErrorCategory
                                .HARNESS_DID_NOT_RUN_FIXTURE);
                value.put(ConformanceReportConstants.Field.MESSAGE,
                        "Fixture has no execution result.");
                encoded.add(Collections.unmodifiableMap(value));
                continue;
            }
            value.put(ConformanceReportConstants.Field.PATH, result.getPath());
            value.put(ConformanceReportConstants.Field.ROLE, result.getRole());
            value.put(ConformanceReportConstants.Field.CATEGORY,
                    result.getCategory().getLabel());
            value.put(ConformanceReportConstants.Field.OPERATION,
                    result.getOperation());
            value.put(ConformanceReportConstants.Field.VECTORS,
                    result.getVectors());
            value.put(ConformanceReportConstants.Field.STATUS,
                    result.getStatus().name());
            if (result.getFailure() != null) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put(ConformanceReportConstants.Field.EXCEPTION_CLASS,
                        result.getFailure().getExceptionClass());
                failure.put(ConformanceReportConstants.Field.MESSAGE,
                        result.getFailure().getMessage());
                value.put(ConformanceReportConstants.Field.FAILURE,
                        Collections.unmodifiableMap(failure));
            }
            encoded.add(Collections.unmodifiableMap(value));
        }
        return Collections.unmodifiableList(encoded);
    }

    /** Immutable description of one executable Contracts fixture. */
    public static final class FixtureInventoryEntry {
        final String id;
        final String path;
        final String role;
        final BlueContractsFixtureCategory category;
        final String operation;
        final List<String> vectors;

        FixtureInventoryEntry(String id,
                              String path,
                              String role,
                              BlueContractsFixtureCategory category,
                              String operation,
                              List<String> vectors) {
            this.id = id;
            this.path = path;
            this.role = role;
            this.category = category;
            this.operation = operation;
            this.vectors = Collections.unmodifiableList(new ArrayList<>(vectors));
        }

        /**
         * Returns the stable fixture identity.
         *
         * @return manifest fixture identity
         */
        public String id() { return id; }

        /**
         * Returns the manifest-relative fixture resource path.
         *
         * @return fixture resource path
         */
        public String path() { return path; }

        /**
         * Returns the manifest role.
         *
         * @return fixture role
         */
        public String role() { return role; }

        /**
         * Returns the closed fixture category.
         *
         * @return fixture category
         */
        public BlueContractsFixtureCategory category() { return category; }

        /**
         * Returns the fixture operation.
         *
         * @return fixture operation name
         */
        public String operation() { return operation; }

        /**
         * Returns the immutable vector inventory.
         *
         * @return immutable ordered vector names
         */
        public List<String> vectors() { return vectors; }
    }
}
