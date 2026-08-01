package blue.language.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connects the exact semantic characterization to the separately approved JVM
 * API migration ledger.
 *
 * <p>The Python binary gate performs the descriptor-level diff. This verifier
 * proves that semantic verification consumed that successful gate's report,
 * the immutable pre-refactor API snapshot, and the exact checked-in ledger.
 * Every non-API semantic invariant remains verified directly by
 * {@link SemanticBaselineVerifierCli}.</p>
 */
final class ApiMigrationLedgerVerifier {

    private static final String LEDGER_SCHEMA =
            "blue-language-java-api-migration-ledger/1.0";
    private static final String BINARY_BASELINE_SCHEMA =
            "blue-language-java-api-baseline/1.0";

    private ApiMigrationLedgerVerifier() {
    }

    /** Verifies and returns deterministic API-migration evidence for a report. */
    static ObjectNode verify(
            JsonNode semanticBaseline,
            JsonNode currentApi,
            Path currentApiPath,
            Path ledgerPath,
            Path binaryBaselinePath,
            Path binaryReportPath) throws IOException {
        JsonNode ledger = SemanticBaselineSupport.readJson(ledgerPath);
        JsonNode binaryBaseline =
                SemanticBaselineSupport.readJson(binaryBaselinePath);
        verifyBaselineBinding(
                semanticBaseline,
                ledger,
                binaryBaseline,
                ledgerPath,
                binaryBaselinePath);

        ApprovalCounts approvals = approvalCounts(ledger);
        Map<String, String> report = readReport(binaryReportPath);
        verifyReport(
                report,
                currentApi,
                ledgerPath,
                binaryBaselinePath,
                approvals);

        ObjectNode evidence = SemanticBaselineSupport.JSON.createObjectNode();
        evidence.put("ledger", ledgerPath.toString());
        evidence.put(
                "ledgerSha256",
                SemanticBaselineSupport.sha256(ledgerPath));
        evidence.put(
                "binaryBaselineSha256",
                SemanticBaselineSupport.sha256(binaryBaselinePath));
        evidence.put(
                "currentInventorySha256",
                SemanticBaselineSupport.sha256(currentApiPath));
        evidence.put(
                "approvedIncompatibleChanges",
                approvals.incompatible);
        evidence.put("approvedAdditiveChanges", approvals.additive);
        evidence.put("verified", true);
        return evidence;
    }

    private static void verifyBaselineBinding(
            JsonNode semanticBaseline,
            JsonNode ledger,
            JsonNode binaryBaseline,
            Path ledgerPath,
            Path binaryBaselinePath) throws IOException {
        SemanticBaselineSupport.requireEquals(
                "API migration ledger schema",
                LEDGER_SCHEMA,
                SemanticBaselineSupport.text(ledger, "/schema"));
        SemanticBaselineSupport.requireEquals(
                "binary API baseline schema",
                BINARY_BASELINE_SCHEMA,
                SemanticBaselineSupport.text(binaryBaseline, "/schema"));
        SemanticBaselineSupport.requireEquals(
                "migration ledger binary baseline path",
                binaryBaselinePath.toAbsolutePath().normalize(),
                ledgerPath.toAbsolutePath().normalize().getParent()
                        .resolve(SemanticBaselineSupport.text(
                                ledger,
                                "/baseline/binaryApiSnapshot"))
                        .toAbsolutePath().normalize());
        SemanticBaselineSupport.requireEquals(
                "migration ledger binary baseline SHA-256",
                SemanticBaselineSupport.sha256(binaryBaselinePath),
                SemanticBaselineSupport.text(
                        ledger,
                        "/baseline/binaryApiSnapshotSha256"));
        SemanticBaselineSupport.requireEquals(
                "migration ledger semantic API inventory SHA-256",
                SemanticBaselineSupport.text(
                        semanticBaseline,
                        "/publicApi/inventorySha256"),
                SemanticBaselineSupport.text(
                        ledger,
                        "/baseline/semanticApiInventorySha256"));
        JsonNode semanticInventory = SemanticBaselineSupport.required(
                semanticBaseline,
                "/publicApi/inventory");
        JsonNode binaryClasses = SemanticBaselineSupport.required(
                binaryBaseline,
                "/classes");
        SemanticBaselineSupport.requireEquals(
                "semantic and binary baseline classes",
                SemanticBaselineSupport.required(
                        semanticInventory,
                        "/classes"),
                binaryClasses);
        SemanticBaselineSupport.requireEquals(
                "migration ledger baseline API class count",
                binaryClasses.size(),
                SemanticBaselineSupport.intValue(
                        ledger,
                        "/baseline/apiClasses"));
    }

    private static ApprovalCounts approvalCounts(JsonNode ledger) {
        JsonNode approvals = SemanticBaselineSupport.required(
                ledger,
                "/approvals");
        if (!approvals.isArray() || approvals.size() == 0) {
            throw new IllegalStateException(
                    "API migration ledger requires at least one approval");
        }
        List<String> ids = new ArrayList<>();
        int incompatible = 0;
        int additive = 0;
        for (JsonNode approval : approvals) {
            ids.add(SemanticBaselineSupport.text(approval, "/id"));
            SemanticBaselineSupport.text(approval, "/requirement");
            SemanticBaselineSupport.text(approval, "/rationale");
            JsonNode incompatibleChanges = SemanticBaselineSupport.required(
                    approval,
                    "/incompatibleChanges");
            JsonNode additiveChanges = SemanticBaselineSupport.required(
                    approval,
                    "/additiveChanges");
            if (!incompatibleChanges.isArray() || !additiveChanges.isArray()) {
                throw new IllegalStateException(
                        "Approved API changes must be arrays");
            }
            incompatible += incompatibleChanges.size();
            additive += additiveChanges.size();
        }
        List<String> sortedIds = new ArrayList<>(ids);
        Collections.sort(sortedIds);
        if (!ids.equals(sortedIds)
                || ids.size() != new java.util.HashSet<>(ids).size()) {
            throw new IllegalStateException(
                    "API migration approval ids must be sorted and unique");
        }
        return new ApprovalCounts(incompatible, additive);
    }

    private static Map<String, String> readReport(Path path)
            throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "Binary API migration report is not a regular file: "
                            + path);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(
                        line.substring(0, separator),
                        line.substring(separator + 1));
            }
        }
        return values;
    }

    private static void verifyReport(
            Map<String, String> report,
            JsonNode currentApi,
            Path ledgerPath,
            Path binaryBaselinePath,
            ApprovalCounts approvals) throws IOException {
        requireReportPath(
                report,
                "baseline",
                binaryBaselinePath);
        requireReportPath(report, "migrationLedger", ledgerPath);
        requireReportValue(
                report,
                "migrationLedgerSha256",
                SemanticBaselineSupport.sha256(ledgerPath));
        requireReportValue(report, "migrationLedgerVerified", "true");
        requireReportValue(report, "incompatibleChanges", "0");
        requireReportValue(report, "unapprovedChanges", "0");
        requireReportValue(report, "missingApprovedChanges", "0");
        requireReportValue(
                report,
                "baselineApiClasses",
                Integer.toString(SemanticBaselineSupport.required(
                        SemanticBaselineSupport.readJson(binaryBaselinePath),
                        "/classes").size()));
        requireReportValue(
                report,
                "currentApiClasses",
                Integer.toString(SemanticBaselineSupport.required(
                        currentApi,
                        "/classes").size()));
        requireReportValue(
                report,
                "actualIncompatibleChanges",
                Integer.toString(approvals.incompatible));
        requireReportValue(
                report,
                "approvedIncompatibleChanges",
                Integer.toString(approvals.incompatible));
        requireReportValue(
                report,
                "additiveChanges",
                Integer.toString(approvals.additive));
        requireReportValue(
                report,
                "approvedAdditiveChanges",
                Integer.toString(approvals.additive));
    }

    private static void requireReportPath(
            Map<String, String> report,
            String key,
            Path expected) {
        String value = requiredReportValue(report, key);
        SemanticBaselineSupport.requireEquals(
                "binary API report " + key,
                expected.toAbsolutePath().normalize(),
                Paths.get(value).toAbsolutePath().normalize());
    }

    private static void requireReportValue(
            Map<String, String> report,
            String key,
            String expected) {
        SemanticBaselineSupport.requireEquals(
                "binary API report " + key,
                expected,
                requiredReportValue(report, key));
    }

    private static String requiredReportValue(
            Map<String, String> report,
            String key) {
        String value = report.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    "Binary API report is missing " + key);
        }
        return value;
    }

    private static final class ApprovalCounts {
        private final int incompatible;
        private final int additive;

        private ApprovalCounts(int incompatible, int additive) {
            this.incompatible = incompatible;
            this.additive = additive;
        }
    }
}
