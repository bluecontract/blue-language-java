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
    /** Authoritative final Language/Contracts package manifest resource. */
    public static final String RELEASE_MANIFEST_RESOURCE =
            "release/blue-language-contracts-embedded-modules-collection-paths-1.0/"
                    + "PACKAGE-MANIFEST.yaml";
    /** Normative Contracts specification resource. */
    public static final String CONTRACTS_SPECIFICATION_RESOURCE =
            "specifications/blue-contracts-and-processor-specification-1.0.md";
    /** Normative Language specification resource. */
    public static final String LANGUAGE_SPECIFICATION_RESOURCE =
            "specifications/blue-language-specification-1.0.md";

    /** Exact release and constituent package identities. */
    public static final String RELEASE_NAME =
            "blue-language-contracts-embedded-modules-collection-paths";
    /** Canonical identity declared by the exact supplied package manifest. */
    public static final String RELEASE_PACKAGE_IDENTITY =
            "sha256:0268c0adc8badf0d1ab5cdef4a323117b82253a3695f9125af750437a23014b6";
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
            "sha256:16392301655431695df6a7cc142a7e388e426c382bf4e3c5f06ddfafb8efecdc";

    /** Expected digests for release-bound manifests and specifications. */
    public static final String CONTRACTS_GAS_MANIFEST_SHA256 =
            "1f4054b77fc7ef01a3e62f5b29d209e84f26e85148c91b03fe48da2c3579408f";
    /** Published SHA-256 digest of the Contracts specification. */
    public static final String CONTRACTS_SPECIFICATION_SHA256 =
            "6406153791ed99cf97163726b8d2a272e3f0ca1078dc6c9f69b81855d81e5c81";
    /** Published SHA-256 digest of the Language specification. */
    public static final String LANGUAGE_SPECIFICATION_SHA256 =
            "a234b0b42190a7982809781b5efdaa2e5f1ab4b7f8d870fbd1ffe7020cc7e869";


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
    /** Returns the exact ordered Contracts 1.0 fixture identities. */
    public static List<String> requiredFixtureIdsForContracts10() {
        return BlueContractsFixturePackage.requiredFixtureIdsForContracts10();
    }

    /**
     * Loads the bound fixture package identity.
     *
     * @param fallback value used when the package manifest is unavailable
     * @return bound package identity, or the supplied fallback
     */
    public static String loadFixturePackageIdentity(String fallback) {
        return BlueContractsFixturePackage.loadFixturePackageIdentity(fallback);
    }

    /** Returns the exact ordered fixture identity inventory. */
    public static List<String> loadFixtureIds() {
        return BlueContractsFixturePackage.loadFixtureIds();
    }

    /** Returns fixture categories keyed by exact fixture identity. */
    public static Map<String, BlueContractsFixtureCategory> loadFixtureCategories() {
        return BlueContractsFixturePackage.loadFixtureCategories();
    }

    /** Computes the canonical Contracts fixture package identity. */
    public static String computeFixturePackageIdentity() {
        return BlueContractsFixturePackage.computeFixturePackageIdentity();
    }

    /** Computes the canonical Contracts gas package identity. */
    public static String computeGasPackageIdentity() {
        return BlueContractsFixturePackage.computeGasPackageIdentity();
    }

    /** Computes the canonical Contracts registry package identity. */
    public static String computeRegistryPackageIdentity() {
        return BlueContractsFixturePackage.computeRegistryPackageIdentity();
    }

    /** Computes the canonical final release package identity. */
    public static String computeReleasePackageIdentity() {
        return BlueContractsFixturePackage.computeReleasePackageIdentity();
    }

    /** Reports whether the fixture manifest identity matches its exact files. */
    public static boolean fixturePackageIdentityMatchesFixtureFiles() {
        return BlueContractsFixturePackage.fixturePackageIdentityMatchesFixtureFiles();
    }

    /**
     * Verifies fixture paths, bytes, digests, counts, and package identity.
     *
     * @throws IllegalStateException when any package binding is inconsistent
     */
    public static void validateFixturePackageIntegrity() {
        BlueContractsFixturePackage.validateFixturePackageIntegrity();
    }

    /**
     * Verifies final release, registry, gas, and specification bindings.
     *
     * @throws IllegalStateException when any release binding is inconsistent
     */
    public static void validateReleaseBindings() {
        BlueContractsFixturePackage.validateReleaseBindings();
    }

    /** Returns the strict fixture-envelope YAML mapper. */
    static ObjectMapper fixtureYamlMapper() {
        return BlueContractsFixturePackage.fixtureYamlMapper();
    }

    /**
     * Reads one path from the verified packaged fixture inventory.
     *
     * @param path manifest-relative fixture path
     * @return parsed fixture envelope
     */
    public static JsonNode readFixture(String path) {
        return BlueContractsFixturePackage.readFixture(path);
    }

    /** Loads the ordered executable fixture inventory. */
    public static List<FixtureInventoryEntry> loadFixtureInventory() {
        return BlueContractsFixturePackage.loadFixtureInventory();
    }

    static List<FixtureInventoryEntry> loadFixtureInventory(
            JsonNode manifest,
            Function<String, JsonNode> fixtureReader) {
        return BlueContractsFixturePackage.loadFixtureInventory(
                manifest, fixtureReader);
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
