package blue.language.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiMigrationLedgerVerifierTest {

    private static final Path SEMANTIC_BASELINE = Paths.get(
            "api/semantic-baseline-1.0.json");
    private static final Path BINARY_BASELINE = Paths.get(
            "api/blue-language-java-1.0.json");
    private static final Path MIGRATION_LEDGER = Paths.get(
            "api/modernization-api-migration-ledger-1.0.json");

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldAcceptEvidenceBoundToTheExactCheckedInLedger() throws Exception {
        // given
        Fixture fixture = fixture("0", "0");

        // when
        ObjectNode evidence = ApiMigrationLedgerVerifier.verify(
                fixture.semanticBaseline,
                fixture.currentApi,
                fixture.currentApiPath,
                fixture.migrationLedgerPath,
                fixture.binaryBaselinePath,
                fixture.binaryReportPath);

        // then
        assertTrue(evidence.path("verified").asBoolean());
        assertEquals(
                SemanticBaselineSupport.sha256(fixture.migrationLedgerPath),
                evidence.path("ledgerSha256").asText());
        assertEquals(
                "api/modernization-api-migration-ledger-1.0.json",
                evidence.path("ledger").asText());
    }

    @Test
    void shouldAcceptRepositoryRelativeEvidenceFromARelocatedWorkspace()
            throws Exception {
        // given
        Path relocatedApi = temporaryDirectory
                .resolve("relocated-repository")
                .resolve("api");
        Files.createDirectories(relocatedApi);
        Path relocatedBaseline = Files.copy(
                BINARY_BASELINE,
                relocatedApi.resolve(BINARY_BASELINE.getFileName()));
        Path relocatedLedger = Files.copy(
                MIGRATION_LEDGER,
                relocatedApi.resolve(MIGRATION_LEDGER.getFileName()));
        Fixture fixture = fixture(
                "0",
                "0",
                relocatedBaseline,
                relocatedLedger,
                "api/blue-language-java-1.0.json",
                "api/modernization-api-migration-ledger-1.0.json");

        // when
        ObjectNode evidence = ApiMigrationLedgerVerifier.verify(
                fixture.semanticBaseline,
                fixture.currentApi,
                fixture.currentApiPath,
                fixture.migrationLedgerPath,
                fixture.binaryBaselinePath,
                fixture.binaryReportPath);

        // then
        assertTrue(evidence.path("verified").asBoolean());
        assertEquals(
                SemanticBaselineSupport.sha256(relocatedBaseline),
                evidence.path("binaryBaselineSha256").asText());
        assertEquals(
                "api/modernization-api-migration-ledger-1.0.json",
                evidence.path("ledger").asText());
    }

    @Test
    void shouldRejectReportWithAnUnapprovedOrMissingChange() throws Exception {
        // given
        Fixture fixture = fixture("1", "0");

        // when
        Executable verification = () -> ApiMigrationLedgerVerifier.verify(
                        fixture.semanticBaseline,
                        fixture.currentApi,
                        fixture.currentApiPath,
                        fixture.migrationLedgerPath,
                        fixture.binaryBaselinePath,
                        fixture.binaryReportPath);

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                verification);
        assertTrue(failure.getMessage().contains("unapprovedChanges"));
    }

    @Test
    void shouldRejectRepositoryRelativeEvidenceWithTraversal() throws Exception {
        // given
        Fixture fixture = fixture(
                "0",
                "0",
                BINARY_BASELINE,
                MIGRATION_LEDGER,
                "../api/blue-language-java-1.0.json",
                "api/modernization-api-migration-ledger-1.0.json");

        // when
        Executable verification = () -> ApiMigrationLedgerVerifier.verify(
                fixture.semanticBaseline,
                fixture.currentApi,
                fixture.currentApiPath,
                fixture.migrationLedgerPath,
                fixture.binaryBaselinePath,
                fixture.binaryReportPath);

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                verification);
        assertTrue(failure.getMessage().contains("must not traverse"));
    }

    @Test
    void shouldRejectRepositoryRelativeEvidenceForAnotherFile() throws Exception {
        // given
        Fixture fixture = fixture(
                "0",
                "0",
                BINARY_BASELINE,
                MIGRATION_LEDGER,
                "fixtures/blue-language-java-1.0.json",
                "api/modernization-api-migration-ledger-1.0.json");

        // when
        Executable verification = () -> ApiMigrationLedgerVerifier.verify(
                fixture.semanticBaseline,
                fixture.currentApi,
                fixture.currentApiPath,
                fixture.migrationLedgerPath,
                fixture.binaryBaselinePath,
                fixture.binaryReportPath);

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                verification);
        assertTrue(failure.getMessage().contains("binary API report baseline"));
    }

    private Fixture fixture(
            String unapprovedChanges,
            String missingApprovedChanges) throws Exception {
        return fixture(
                unapprovedChanges,
                missingApprovedChanges,
                BINARY_BASELINE,
                MIGRATION_LEDGER,
                BINARY_BASELINE.toAbsolutePath().toString(),
                MIGRATION_LEDGER.toAbsolutePath().toString());
    }

    private Fixture fixture(
            String unapprovedChanges,
            String missingApprovedChanges,
            Path binaryBaselinePath,
            Path migrationLedgerPath,
            String reportedBaseline,
            String reportedLedger) throws Exception {
        JsonNode semanticBaseline =
                SemanticBaselineSupport.readJson(SEMANTIC_BASELINE);
        JsonNode currentApi = SemanticBaselineSupport.required(
                semanticBaseline,
                "/publicApi/inventory").deepCopy();
        Path currentApiPath = temporaryDirectory.resolve("current-api.json");
        SemanticBaselineSupport.writeJson(currentApiPath, currentApi);

        JsonNode ledger = SemanticBaselineSupport.readJson(
                migrationLedgerPath);
        int approvedIncompatible = approvedCount(
                ledger,
                "incompatibleChanges");
        int approvedAdditive = approvedCount(ledger, "additiveChanges");
        int currentClasses = SemanticBaselineSupport.required(
                currentApi,
                "/classes").size();
        int baselineClasses = SemanticBaselineSupport.required(
                SemanticBaselineSupport.readJson(binaryBaselinePath),
                "/classes").size();

        List<String> report = new ArrayList<>();
        report.add("baseline=" + reportedBaseline);
        report.add("current=fixture.jar");
        report.add("baselineApiClasses=" + baselineClasses);
        report.add("currentApiClasses=" + currentClasses);
        report.add("currentClassMajorVersions=52");
        report.add("incompatibleChanges=0");
        report.add("additiveChanges=" + approvedAdditive);
        report.add("migrationLedger=" + reportedLedger);
        report.add("migrationLedgerSha256="
                + SemanticBaselineSupport.sha256(migrationLedgerPath));
        report.add("migrationLedgerVerified=true");
        report.add("actualIncompatibleChanges=" + approvedIncompatible);
        report.add("approvedIncompatibleChanges=" + approvedIncompatible);
        report.add("approvedAdditiveChanges=" + approvedAdditive);
        report.add("unapprovedChanges=" + unapprovedChanges);
        report.add("missingApprovedChanges=" + missingApprovedChanges);
        Path binaryReportPath = temporaryDirectory.resolve("binary-api.txt");
        Files.write(binaryReportPath, report, StandardCharsets.UTF_8);
        return new Fixture(
                semanticBaseline,
                currentApi,
                currentApiPath,
                migrationLedgerPath,
                binaryBaselinePath,
                binaryReportPath);
    }

    private int approvedCount(JsonNode ledger, String field) {
        int count = 0;
        for (JsonNode approval : SemanticBaselineSupport.required(
                ledger,
                "/approvals")) {
            count += SemanticBaselineSupport.required(
                    approval,
                    "/" + field).size();
        }
        return count;
    }

    private static final class Fixture {
        private final JsonNode semanticBaseline;
        private final JsonNode currentApi;
        private final Path currentApiPath;
        private final Path migrationLedgerPath;
        private final Path binaryBaselinePath;
        private final Path binaryReportPath;

        private Fixture(
                JsonNode semanticBaseline,
                JsonNode currentApi,
                Path currentApiPath,
                Path migrationLedgerPath,
                Path binaryBaselinePath,
                Path binaryReportPath) {
            this.semanticBaseline = semanticBaseline;
            this.currentApi = currentApi;
            this.currentApiPath = currentApiPath;
            this.migrationLedgerPath = migrationLedgerPath;
            this.binaryBaselinePath = binaryBaselinePath;
            this.binaryReportPath = binaryReportPath;
        }
    }
}
