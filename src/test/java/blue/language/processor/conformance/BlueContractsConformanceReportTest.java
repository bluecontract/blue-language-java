package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.BlueContractsConformanceFailure;
import blue.language.BlueContractsConformanceReport;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceReportTest {

    @Test
    void contractsConformanceReportPassesReleaseGates() {
        BlueContractsConformanceReport report = new Blue().runContractsConformanceSuite();

        assertTrue(report.getFailures().isEmpty(), () -> report.getFailures().stream()
                .map(this::failureMessage)
                .collect(Collectors.joining("\n")));
        assertTrue(report.getFailedFixtureIds().isEmpty());
        assertEquals(report.getFixtureIds(), report.getPassedFixtureIds());
        assertTrue(report.hasRequiredFixtureCoverage());
        assertTrue(report.hasExactRequiredFixtureSet());
        assertTrue(report.isOfficialContracts10FixturePackage());
        assertTrue(BlueContractsConformanceReport.fixturePackageIdentityMatchesFixtureFiles());
    }

    @Test
    void staticContractsConformanceReportExposesReleaseMetadata() {
        BlueContractsConformanceReport report = new Blue().contractsConformanceReport();

        assertEquals(BlueContractsConformanceReport.computeFixturePackageIdentity(),
                report.getFixturePackageIdentity());
        assertTrue(report.hasRequiredFixtureCoverage());
        assertTrue(report.hasExactRequiredFixtureSet());
        assertTrue(report.isOfficialContracts10FixturePackage());
        assertTrue(BlueContractsConformanceReport.fixturePackageIdentityMatchesFixtureFiles());
    }

    @Test
    void contractsFixturePackageIdentityMatchesOfficialContracts10Release() {
        BlueContractsConformanceReport report = new Blue().contractsConformanceReport();

        assertEquals(BlueContractsConformanceReport.BLUE_CONTRACTS_1_0_FIXTURE_PACKAGE_IDENTITY,
                report.getFixturePackageIdentity());
        assertTrue(report.isOfficialContracts10FixturePackage());
    }

    private String failureMessage(BlueContractsConformanceFailure failure) {
        return failure.getFixtureId() + " [" + failure.getCategory().name() + "] "
                + failure.getOperation() + " -> " + failure.getExceptionClass()
                + ": " + failure.getMessage();
    }
}
