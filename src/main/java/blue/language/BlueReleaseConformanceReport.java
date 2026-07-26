package blue.language;

import blue.language.utils.UncheckedObjectMapper;

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

    public static final String SCHEMA =
            "blue-language-java-release-conformance-report/1.0";
    public static final int LANGUAGE_FIXTURE_COUNT = 125;
    public static final int CONTRACTS_FIXTURE_COUNT = 127;
    public static final int TOTAL_FIXTURE_COUNT =
            LANGUAGE_FIXTURE_COUNT + CONTRACTS_FIXTURE_COUNT;

    private final BlueConformanceReport language;
    private final BlueContractsConformanceReport contracts;

    public BlueReleaseConformanceReport(BlueConformanceReport language,
                                        BlueContractsConformanceReport contracts) {
        this.language = Objects.requireNonNull(language, "language");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        validateBindings();
    }

    public BlueConformanceReport getLanguageReport() {
        return language;
    }

    public BlueContractsConformanceReport getContractsReport() {
        return contracts;
    }

    public boolean isConformant() {
        return language.getFailures().isEmpty()
                && language.getFailedFixtureIds().isEmpty()
                && language.getPassedFixtureIds().equals(
                        language.getFixtureIds())
                && language.hasExactRequiredFixtureSet()
                && contracts.isConformant();
    }

    public Map<String, Object> toMachineReadableMap() {
        List<Map<String, Object>> fixtures = combinedFixtureResults();
        int passed = 0;
        for (Map<String, Object> fixture : fixtures) {
            if ("PASS".equals(fixture.get("status"))) {
                passed++;
            }
        }

        Map<String, Object> release = new LinkedHashMap<>();
        release.put("name", contracts.getReleaseName());
        release.put("packageIdentity",
                contracts.getReleasePackageIdentity());

        Map<String, Object> packages = new LinkedHashMap<>();
        packages.put("languageRegistry",
                contracts.getLanguageRegistryPackageIdentity());
        packages.put("languageFixtures",
                contracts.getLanguageFixturePackageIdentity());
        packages.put("contractsRegistry",
                contracts.getContractsRegistryPackageIdentity());
        packages.put("contractsGas",
                contracts.getContractsGasPackageIdentity());
        packages.put("contractsFixtures",
                contracts.getFixturePackageIdentity());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", fixtures.size());
        summary.put("passed", passed);
        summary.put("failed", fixtures.size() - passed);
        summary.put("skipped", 0);
        summary.put("conformant", isConformant());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", SCHEMA);
        report.put("release", Collections.unmodifiableMap(release));
        report.put("packages", Collections.unmodifiableMap(packages));
        report.put("summary", Collections.unmodifiableMap(summary));
        report.put("fixtures", fixtures);
        return Collections.unmodifiableMap(report);
    }

    public String toMachineReadableJson() {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(
                toMachineReadableMap());
    }

    private void validateBindings() {
        if (!"1.0".equals(language.getSpecVersion())
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
        if (!"1.0".equals(contracts.getSpecVersion())
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
                "language",
                (List<Map<String, Object>>) language
                        .toMachineReadableMap().get("results"));
        appendResults(
                combined,
                "contracts",
                (List<Map<String, Object>>) contracts
                        .toMachineReadableMap().get("fixtures"));
        if (combined.size() != TOTAL_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Combined release report must contain exactly "
                            + TOTAL_FIXTURE_COUNT + " fixture results");
        }
        Set<String> resultKeys = new LinkedHashSet<>();
        for (Map<String, Object> fixture : combined) {
            Object key = fixture.get("resultKey");
            Object status = fixture.get("status");
            if (!(key instanceof String) || !resultKeys.add((String) key)) {
                throw new IllegalStateException(
                        "Combined fixture result keys must be unique");
            }
            if (!"PASS".equals(status) && !"FAIL".equals(status)) {
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
            Object id = raw.get("id");
            if (!(id instanceof String) || ((String) id).isEmpty()) {
                throw new IllegalStateException(
                        "Machine-readable fixture result is missing id");
            }
            Map<String, Object> fixture = new LinkedHashMap<>();
            fixture.put("resultKey", suite + ":" + id);
            fixture.put("suite", suite);
            fixture.putAll(raw);
            target.add(Collections.unmodifiableMap(fixture));
        }
    }
}
