package blue.language.conformance.cli;

import blue.language.conformance.api.BlueContractsConformanceFailure;
import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.conformance.api.BlueConformanceFailure;
import blue.language.conformance.api.BlueConformanceReport;
import blue.language.conformance.api.BlueConformanceSuiteRunner;
import blue.language.conformance.api.BlueReleaseConformanceReport;
import blue.language.conformance.contracts.ContractsConformanceSuite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict release entry point for the exact Language 1.0 and Contracts 1.0
 * conformance packages.
 *
 * <p>The command executes every fixture, writes both machine-readable and
 * human-readable reports, and exits unsuccessfully if a fixture fails, is
 * missing, or is not bound to the published package identities.</p>
 */
public final class ReleaseConformanceCli {

    private static final String DEFAULT_JSON_REPORT =
            "build/reports/conformance/release-conformance.json";
    private static final String DEFAULT_TEXT_REPORT =
            "build/reports/conformance/release-conformance.txt";

    private ReleaseConformanceCli() {
    }

    /**
     * Runs both release conformance suites and writes their JSON and text reports.
     *
     * @param args optional JSON-report and text-report output paths
     * @throws IOException if either report cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length > 2) {
            throw new IllegalArgumentException(
                    "Usage: ReleaseConformanceCli [json-report] [text-report]");
        }
        Path jsonReport = Paths.get(
                args.length >= 1 ? args[0] : DEFAULT_JSON_REPORT);
        Path textReport = Paths.get(
                args.length >= 2 ? args[1] : DEFAULT_TEXT_REPORT);

        BlueReleaseConformanceReport report =
                new BlueReleaseConformanceReport(
                        BlueConformanceSuiteRunner.run(),
                        ContractsConformanceSuite.run());
        write(jsonReport, report.toMachineReadableJson() + "\n");
        write(textReport, humanReport(report));

        if (!report.isConformant()) {
            throw new IllegalStateException(
                    "Release conformance failed; see " + textReport);
        }
    }

    static String humanReport(BlueReleaseConformanceReport report) {
        BlueConformanceReport language = report.getLanguageReport();
        BlueContractsConformanceReport contracts =
                report.getContractsReport();
        int languageTotal = language.getFixtureIds().size();
        int languagePassed = language.getPassedFixtureIds().size();
        int contractsTotal = contracts.getFixtureIds().size();
        int contractsPassed = contracts.getPassedFixtureIds().size();

        StringBuilder text = new StringBuilder();
        text.append("Blue Language Java release conformance\n");
        text.append("release=").append(contracts.getReleaseName()).append('\n');
        text.append("releasePackage=")
                .append(contracts.getReleasePackageIdentity()).append('\n');
        text.append("languageRegistry=")
                .append(contracts.getLanguageRegistryPackageIdentity())
                .append('\n');
        text.append("languageFixtures=")
                .append(contracts.getLanguageFixturePackageIdentity())
                .append('\n');
        text.append("contractsRegistry=")
                .append(contracts.getContractsRegistryPackageIdentity())
                .append('\n');
        text.append("contractsGas=")
                .append(contracts.getContractsGasPackageIdentity())
                .append('\n');
        text.append("contractsFixtures=")
                .append(contracts.getFixturePackageIdentity()).append('\n');
        text.append("language=")
                .append(languagePassed).append('/').append(languageTotal)
                .append(" passed, ")
                .append(languageTotal - languagePassed)
                .append(" failed, 0 skipped\n");
        text.append("contracts=")
                .append(contractsPassed).append('/').append(contractsTotal)
                .append(" passed, ")
                .append(contractsTotal - contractsPassed)
                .append(" failed, ")
                .append(contracts.getSkippedFixtureCount())
                .append(" skipped\n");
        text.append("conformant=").append(report.isConformant()).append('\n');

        List<String> failures = new ArrayList<>();
        for (BlueConformanceFailure failure : language.getFailures()) {
            failures.add("language:" + failure.getFixtureId()
                    + " [" + failure.getCategory() + "] "
                    + failure.getMessage());
        }
        for (BlueContractsConformanceFailure failure
                : contracts.getFailures()) {
            failures.add("contracts:" + failure.getFixtureId()
                    + " [" + failure.getCategory() + "] "
                    + failure.getMessage());
        }
        if (!failures.isEmpty()) {
            text.append("failures:\n");
            for (String failure : failures) {
                text.append("- ").append(failure).append('\n');
            }
        }
        return text.toString();
    }

    private static void write(Path path, String content) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
