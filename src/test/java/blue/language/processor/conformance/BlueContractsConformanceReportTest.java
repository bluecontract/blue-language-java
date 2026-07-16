package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.BlueContractsConformanceFailure;
import blue.language.BlueContractsConformanceReport;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceReportTest {

    @Test
    void contractsConformanceReportPassesCandidateReleaseGates() {
        BlueContractsConformanceReport report = new Blue().runContractsConformanceSuite();

        assertTrue(report.getFailures().isEmpty(), () -> report.getFailures().stream()
                .map(this::failureMessage)
                .collect(Collectors.joining("\n")));
        assertTrue(report.getFailedFixtureIds().isEmpty());
        assertEquals(report.getFixtureIds(), report.getPassedFixtureIds());
        assertTrue(report.hasRequiredFixtureCoverage());
        assertTrue(report.hasExactRequiredFixtureSet());
        assertEquals(BlueContractsConformanceReport.CANDIDATE_FIXTURE_PACKAGE_IDENTITY,
                report.getFixturePackageIdentity());
        assertFalse(report.isOfficialContracts10FixturePackage());
        assertTrue(BlueContractsConformanceReport.fixturePackageIdentityMatchesFixtureFiles());
    }

    @Test
    void staticContractsConformanceReportExposesCandidateMetadata() {
        BlueContractsConformanceReport report = new Blue().contractsConformanceReport();

        assertEquals(BlueContractsConformanceReport.computeFixturePackageIdentity(),
                report.getFixturePackageIdentity());
        assertTrue(report.hasRequiredFixtureCoverage());
        assertTrue(report.hasExactRequiredFixtureSet());
        assertEquals(BlueContractsConformanceReport.CANDIDATE_FIXTURE_PACKAGE_IDENTITY,
                report.getFixturePackageIdentity());
        assertEquals("feat/conformance-fixture-expansion@a0f4914",
                BlueContractsConformanceReport.CANDIDATE_BLUE_SPEC_SOURCE);
        assertFalse(report.isOfficialContracts10FixturePackage());
        assertTrue(BlueContractsConformanceReport.fixturePackageIdentityMatchesFixtureFiles());
    }

    @Test
    void officialContracts10IdentityRemainsSeparateFromCandidatePackage() {
        BlueContractsConformanceReport report = new Blue().contractsConformanceReport();

        assertEquals("sha256:2f197ca3bbdc41b75e772777cc48e51019754347e1bee26b5f3209b71d9bd9ca",
                BlueContractsConformanceReport.BLUE_CONTRACTS_1_0_FIXTURE_PACKAGE_IDENTITY);
        assertEquals(BlueContractsConformanceReport.CANDIDATE_FIXTURE_PACKAGE_IDENTITY,
                report.getFixturePackageIdentity());
        assertFalse(report.isOfficialContracts10FixturePackage());
    }

    private String failureMessage(BlueContractsConformanceFailure failure) {
        return failure.getFixtureId() + " [" + failure.getCategory().name() + "] "
                + failure.getOperation() + " -> " + failure.getExceptionClass()
                + ": " + failure.getMessage();
    }
}
