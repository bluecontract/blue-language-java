package blue.language.conformance.api;

import blue.language.codec.jackson.UncheckedObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic machine-readable report for the exact Language 1.0 and
 * Contracts 1.0 fixture packages bound by the final implementation baseline.
 */
public final class BlueReleaseConformanceReport {

    /** Versioned machine-readable report schema identifier. */
    public static final String SCHEMA =
            ConformanceReportConstants.Schema.RELEASE;

    /** Exact fixture cardinalities bound by the final release package. */
    public static final int LANGUAGE_FIXTURE_COUNT = 175;
    /** Exact Contracts fixture cardinality. */
    public static final int CONTRACTS_FIXTURE_COUNT = 276;
    /** Exact combined fixture cardinality. */
    public static final int TOTAL_FIXTURE_COUNT =
            LANGUAGE_FIXTURE_COUNT + CONTRACTS_FIXTURE_COUNT;

    private final BlueConformanceReport language;
    private final BlueContractsConformanceReport contracts;

    /**
     * Creates a combined report and verifies release bindings.
     *
     * @param language Language conformance report
     * @param contracts Contracts conformance report
     * @throws IllegalArgumentException when either report has incorrect
     *                                  release bindings
     */
    public BlueReleaseConformanceReport(BlueConformanceReport language,
                                        BlueContractsConformanceReport contracts) {
        this.language = Objects.requireNonNull(language, "language");
        this.contracts = Objects.requireNonNull(
                contracts, ConformanceReportConstants.Field.CONTRACTS);
        validateBindings();
    }

    /** Returns the Language report.
     * @return Language conformance report */
    public BlueConformanceReport getLanguageReport() {
        return language;
    }

    /** Returns the Contracts report.
     * @return Contracts conformance report */
    public BlueContractsConformanceReport getContractsReport() {
        return contracts;
    }

    /** Tests combined release conformance.
     * @return whether both exact suites passed */
    public boolean isConformant() {
        return language.getFailures().isEmpty()
                && language.getFailedFixtureIds().isEmpty()
                && language.getPassedFixtureIds().equals(
                        language.getFixtureIds())
                && language.hasExactRequiredFixtureSet()
                && contracts.isConformant();
    }

    /** Builds a deterministic release report.
     * @return immutable machine-readable map */
    public Map<String, Object> toMachineReadableMap() {
        List<Map<String, Object>> fixtures = combinedFixtureResults();
        int passed = 0;
        for (Map<String, Object> fixture : fixtures) {
            if (ConformanceReportConstants.Status.PASS.equals(
                    fixture.get(ConformanceReportConstants.Field.STATUS))) {
                passed++;
            }
        }

        Map<String, Object> release = new LinkedHashMap<>();
        release.put(ConformanceReportConstants.Field.NAME,
                contracts.getReleaseName());
        release.put(ConformanceReportConstants.Field.PACKAGE_IDENTITY,
                contracts.getReleasePackageIdentity());
        release.put(
                ConformanceReportConstants.Field.CONTRACTS_RELEASE_IDENTITY,
                BlueContractsFixturePackage.CONTRACTS_RELEASE_IDENTITY);

        Map<String, Object> packages = new LinkedHashMap<>();
        packages.put(ConformanceReportConstants.Field.LANGUAGE_REGISTRY,
                contracts.getLanguageRegistryPackageIdentity());
        packages.put(ConformanceReportConstants.Field.LANGUAGE_FIXTURES,
                contracts.getLanguageFixturePackageIdentity());
        packages.put(ConformanceReportConstants.Field.CONTRACTS_REGISTRY,
                contracts.getContractsRegistryPackageIdentity());
        packages.put(ConformanceReportConstants.Field.CONTRACTS_GAS,
                contracts.getContractsGasPackageIdentity());
        packages.put(ConformanceReportConstants.Field.CONTRACTS_FIXTURES,
                contracts.getFixturePackageIdentity());
        packages.put(ConformanceReportConstants.Field.CONTRACTS_RELEASE,
                BlueContractsFixturePackage.CONTRACTS_RELEASE_IDENTITY);

        Map<String, Object> specifications = new LinkedHashMap<>();
        specifications.put(ConformanceReportConstants.Field.LANGUAGE_SHA256,
                BlueContractsConformanceReport
                        .LANGUAGE_SPECIFICATION_SHA256);
        specifications.put(ConformanceReportConstants.Field.CONTRACTS_SHA256,
                BlueContractsConformanceReport
                        .CONTRACTS_SPECIFICATION_SHA256);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put(ConformanceReportConstants.Field.TOTAL, fixtures.size());
        summary.put(ConformanceReportConstants.Field.PASSED, passed);
        summary.put(ConformanceReportConstants.Field.FAILED,
                fixtures.size() - passed);
        summary.put(ConformanceReportConstants.Field.SKIPPED, 0);
        summary.put(ConformanceReportConstants.Field.CONFORMANT,
                isConformant());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put(ConformanceReportConstants.Field.SCHEMA, SCHEMA);
        report.put(ConformanceReportConstants.Field.RELEASE,
                Collections.unmodifiableMap(release));
        report.put(ConformanceReportConstants.Field.PACKAGES,
                Collections.unmodifiableMap(packages));
        report.put(ConformanceReportConstants.Field.SPECIFICATIONS,
                Collections.unmodifiableMap(specifications));
        report.put(ConformanceReportConstants.Field.SUMMARY,
                Collections.unmodifiableMap(summary));
        report.put(ConformanceReportConstants.Field.FIXTURES, fixtures);
        return Collections.unmodifiableMap(report);
    }

    /** Serializes the release report.
     * @return JSON report */
    public String toMachineReadableJson() {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(
                toMachineReadableMap());
    }

    private void validateBindings() {
        if (!ConformanceReportConstants.SPECIFICATION_VERSION_1_0.equals(
                language.getSpecVersion())
                || !BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY.equals(
                language.getFixturePackageIdentity())
                || !BlueContractsConformanceReport
                .LANGUAGE_REGISTRY_PACKAGE_IDENTITY.equals(
                        language.getCoreRegistryPackageIdentity())
                || !language.hasExactRequiredFixtureSet()
                || language.getFixtureIds().size()
                != LANGUAGE_FIXTURE_COUNT) {
            throw new IllegalArgumentException(
                    "Language report is not bound to the exact "
                            + "Blue Language 1.0 release package");
        }
        if (!ConformanceReportConstants.SPECIFICATION_VERSION_1_0.equals(
                contracts.getSpecVersion())
                || !BlueContractsConformanceReport.RELEASE_NAME.equals(
                contracts.getReleaseName())
                || !BlueContractsConformanceReport
                .RELEASE_PACKAGE_IDENTITY.equals(
                        contracts.getReleasePackageIdentity())
                || !BlueContractsConformanceReport
                .LANGUAGE_REGISTRY_PACKAGE_IDENTITY.equals(
                        contracts.getLanguageRegistryPackageIdentity())
                || !BlueContractsConformanceReport
                .LANGUAGE_FIXTURE_PACKAGE_IDENTITY.equals(
                        contracts.getLanguageFixturePackageIdentity())
                || !BlueContractsConformanceReport
                .CONTRACTS_REGISTRY_PACKAGE_IDENTITY.equals(
                        contracts.getContractsRegistryPackageIdentity())
                || !BlueContractsConformanceReport
                .CONTRACTS_GAS_PACKAGE_IDENTITY.equals(
                        contracts.getContractsGasPackageIdentity())
                || !BlueContractsConformanceReport
                .CONTRACTS_FIXTURE_PACKAGE_IDENTITY.equals(
                        contracts.getFixturePackageIdentity())
                || !contracts.hasExactRequiredFixtureSet()
                || contracts.getFixtureIds().size()
                != CONTRACTS_FIXTURE_COUNT) {
            throw new IllegalArgumentException(
                    "Contracts report is not bound to the exact "
                            + "Blue Contracts 1.0 release package");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> combinedFixtureResults() {
        List<Map<String, Object>> combined =
                new ArrayList<>(TOTAL_FIXTURE_COUNT);
        appendResults(
                combined,
                ConformanceReportConstants.Suite.LANGUAGE,
                (List<Map<String, Object>>) language
                        .toMachineReadableMap().get(
                                ConformanceReportConstants.Field.RESULTS));
        appendResults(
                combined,
                ConformanceReportConstants.Suite.CONTRACTS,
                (List<Map<String, Object>>) contracts
                        .toMachineReadableMap().get(
                                ConformanceReportConstants.Field.FIXTURES));
        if (combined.size() != TOTAL_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Combined release report must contain exactly "
                            + TOTAL_FIXTURE_COUNT + " fixture results");
        }
        Set<String> resultKeys = new LinkedHashSet<>();
        for (Map<String, Object> fixture : combined) {
            Object key = fixture.get(
                    ConformanceReportConstants.Field.RESULT_KEY);
            Object status = fixture.get(
                    ConformanceReportConstants.Field.STATUS);
            if (!(key instanceof String) || !resultKeys.add((String) key)) {
                throw new IllegalStateException(
                        "Combined fixture result keys must be unique");
            }
            if (!ConformanceReportConstants.Status.PASS.equals(status)
                    && !ConformanceReportConstants.Status.FAIL.equals(status)) {
                throw new IllegalStateException(
                        "Combined fixture results support only PASS or FAIL");
            }
        }
        return Collections.unmodifiableList(combined);
    }

    private static void appendResults(
            List<Map<String, Object>> target,
            String suite,
            List<Map<String, Object>> source) {
        if (source == null) {
            throw new IllegalStateException(
                    "Missing machine-readable results for " + suite);
        }
        for (Map<String, Object> raw : source) {
            Object id = raw.get(ConformanceReportConstants.Field.ID);
            if (!(id instanceof String) || ((String) id).isEmpty()) {
                throw new IllegalStateException(
                        "Machine-readable fixture result is missing id");
            }
            Map<String, Object> fixture = new LinkedHashMap<>();
            fixture.put(ConformanceReportConstants.Field.RESULT_KEY,
                    suite + ":" + id);
            fixture.put(ConformanceReportConstants.Field.SUITE, suite);
            fixture.putAll(raw);
            target.add(Collections.unmodifiableMap(fixture));
        }
    }
}
