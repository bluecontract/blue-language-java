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
                MIGRATION_LEDGER,
                BINARY_BASELINE,
                fixture.binaryReportPath);

        // then
        assertTrue(evidence.path("verified").asBoolean());
        assertEquals(
                SemanticBaselineSupport.sha256(MIGRATION_LEDGER),
                evidence.path("ledgerSha256").asText());
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
                        MIGRATION_LEDGER,
                        BINARY_BASELINE,
                        fixture.binaryReportPath);

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                verification);
        assertTrue(failure.getMessage().contains("unapprovedChanges"));
    }

    private Fixture fixture(
            String unapprovedChanges,
            String missingApprovedChanges) throws Exception {
        JsonNode semanticBaseline =
                SemanticBaselineSupport.readJson(SEMANTIC_BASELINE);
        JsonNode currentApi = SemanticBaselineSupport.required(
                semanticBaseline,
                "/publicApi/inventory").deepCopy();
        Path currentApiPath = temporaryDirectory.resolve("current-api.json");
        SemanticBaselineSupport.writeJson(currentApiPath, currentApi);

        JsonNode ledger = SemanticBaselineSupport.readJson(MIGRATION_LEDGER);
        int approvedIncompatible = approvedCount(
                ledger,
                "incompatibleChanges");
        int approvedAdditive = approvedCount(ledger, "additiveChanges");
        int currentClasses = SemanticBaselineSupport.required(
                currentApi,
                "/classes").size();
        int baselineClasses = SemanticBaselineSupport.required(
                SemanticBaselineSupport.readJson(BINARY_BASELINE),
                "/classes").size();

        List<String> report = new ArrayList<>();
        report.add("baseline=" + BINARY_BASELINE.toAbsolutePath());
        report.add("current=fixture.jar");
        report.add("baselineApiClasses=" + baselineClasses);
        report.add("currentApiClasses=" + currentClasses);
        report.add("currentClassMajorVersions=52");
        report.add("incompatibleChanges=0");
        report.add("additiveChanges=" + approvedAdditive);
        report.add("migrationLedger=" + MIGRATION_LEDGER.toAbsolutePath());
        report.add("migrationLedgerSha256="
                + SemanticBaselineSupport.sha256(MIGRATION_LEDGER));
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
        private final Path binaryReportPath;

        private Fixture(
                JsonNode semanticBaseline,
                JsonNode currentApi,
                Path currentApiPath,
                Path binaryReportPath) {
            this.semanticBaseline = semanticBaseline;
            this.currentApi = currentApi;
            this.currentApiPath = currentApiPath;
            this.binaryReportPath = binaryReportPath;
        }
    }
}
