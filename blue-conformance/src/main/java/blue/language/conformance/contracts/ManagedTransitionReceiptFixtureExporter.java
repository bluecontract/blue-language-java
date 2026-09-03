package blue.language.conformance.contracts;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.closure.ClosureFixtureConformance;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedRootEventOccurrence;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes a candidate closure fixture package and exports runtime-owned
 * identity, gas, and managed transition-receipt evidence.
 *
 * <p>The Python package generator uses this executable boundary after its
 * ordinary deterministic refinement pass.  This class never reads a fixture's
 * {@code expected} subtree while preparing or executing an invocation.  It
 * emits a disposable JSON report; the package generator remains the sole
 * writer of normative YAML. Semantic outcomes remain authored fixture input:
 * the exporter checks attempt outcome and status before emitting any derived
 * evidence.</p>
 */
public final class ManagedTransitionReceiptFixtureExporter {

    private static final int EXPECTED_CLOSURE_FIXTURES = 98;
    private static final String REPORT_SCHEMA =
            "blue-contracts-closure-runtime-fixture-export/1.0";
    private static final String SUCCESS_MARKER =
            "CLOSURE_RUNTIME_FIXTURES_EXPORTED count=";
    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    private ManagedTransitionReceiptFixtureExporter() {
    }

    /**
     * Exports one deterministic report from an explicit candidate package.
     *
     * @param args candidate package root and nonexistent report path
     * @throws Exception when candidate execution or report publication fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Expected packageRoot and outputReport arguments");
        }
        Path packageRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        require(Files.isDirectory(packageRoot),
                "candidate package root is not a directory: " + packageRoot);
        require(!Files.exists(output),
                "output report already exists: " + output);
        Path parent = output.getParent();
        require(parent != null && Files.isDirectory(parent),
                "output report parent is not a directory: " + output);

        ObjectNode report = export(packageRoot);
        byte[] encoded = UncheckedObjectMapper.JSON_MAPPER
                .writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(report);
        byte[] terminated = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, terminated, 0, encoded.length);
        terminated[encoded.length] = (byte) '\n';
        Path temporary = Files.createTempFile(
                parent, ".managed-transition-receipts-", ".json");
        try {
            Files.write(temporary, terminated);
            try {
                Files.move(temporary, output,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, output);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        System.out.println(
                SUCCESS_MARKER
                        + report.path("executedFixtureCount").asInt());
    }

    private static ObjectNode export(Path packageRoot) {
        JsonNode manifest = readYaml(
                packageRoot.resolve("fixtures/manifest.yaml"));
        require(manifest.path("closureFixtureCount").asInt(-1)
                        == EXPECTED_CLOSURE_FIXTURES,
                "candidate does not inventory exactly 98 closure fixtures");
        JsonNode files = manifest.get("files");
        require(files != null && files.isArray(),
                "candidate fixture manifest has no files array");

        ObjectNode report = UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
        report.put(BlueLanguageConstants.OBJECT_SCHEMA,
                REPORT_SCHEMA);
        ArrayNode entries = report.putArray("entries");
        Set<String> paths = new LinkedHashSet<String>();
        int inventoried = 0;
        int executed = 0;
        int skipped = 0;
        for (JsonNode file : files) {
            if (!"closure-fixture".equals(file.path("role").asText())) {
                continue;
            }
            inventoried++;
            String relative = requiredText(file, "path");
            require(relative.startsWith("closure/")
                            && !relative.contains("..")
                            && paths.add(relative),
                    "invalid or duplicate closure fixture path: " + relative);
            JsonNode fixture = readYaml(
                    packageRoot.resolve("fixtures").resolve(relative));
            String id = requiredText(fixture, "id");
            String operation = requiredText(fixture, "operation");
            ObjectNode item = entries.addObject();
            item.put("path", relative);
            item.put("id", id);
            item.put("operation", operation);
            if ("limit-micro".equals(operation)) {
                item.put("attemptOutcome", "NotApplicable");
                skipped++;
                continue;
            }
            require("process-closure".equals(operation)
                            || "admit-closure".equals(operation),
                    "unsupported closure fixture operation: " + operation);
            execute(packageRoot, file, fixture, item);
            executed++;
        }
        require(inventoried == EXPECTED_CLOSURE_FIXTURES,
                "manifest closure fixture inventory count mismatch: "
                        + inventoried);
        report.put("inventoriedFixtureCount", inventoried);
        report.put("executedFixtureCount", executed);
        report.put("skippedLimitFixtureCount", skipped);
        return report;
    }

    private static void execute(
            Path packageRoot,
            JsonNode manifestEntry,
            JsonNode fixture,
            ObjectNode report) {
        String id = requiredText(fixture, "id");
        String path = requiredText(manifestEntry, "path");
        String operation = requiredText(fixture, "operation");
        List<String> vectors = textList(manifestEntry.get("vectors"));
        ObjectNode executionFixture = (ObjectNode) fixture.deepCopy();
        executionFixture.remove("expected");
        ClosureInvocationInput input =
                ClosureFixtureConformance.parseInvocationInput(
                        id, path, operation, vectors, executionFixture);
        ClosureAttemptResult attempt;
        Capture capture = new Capture();
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(
                             executionFixture, packageRoot);
             BlueClosureContracts contracts =
                     new BlueClosureContracts(runtime.processor(), capture)) {
            attempt = "process-closure".equals(operation)
                    ? contracts.processClosure(input)
                    : contracts.admitClosureWithLifecycleQueue(input);
        }
        require(attempt != null, id + " returned no closure attempt");
        String outcome = attempt.isComplete() ? "Complete" : "NeedsResources";
        report.put("attemptOutcome", outcome);
        JsonNode expected = fixture.get("expected");
        require(expected != null && expected.isObject(),
                id + " has no expected object for cross-check");
        require(outcome.equals(requiredText(expected, "attemptOutcome")),
                id + " regenerated attempt outcome changed");
        ObjectNode runtimeExpected = FullLifecycleFixtureJson.expected(
                attempt, capture.evidence);
        report.set("runtimeExpected", runtimeExpected);
        if (!attempt.isComplete()) {
            return;
        }
        ClosureProcessResult result = attempt.processResult();
        require(result != null, id + " complete attempt has no result");
        String status = result.status().wireValue();
        report.put("status", status);
        require(status.equals(requiredText(expected, "status")),
                id + " regenerated result status changed");
        report.set("managedTransitionReceipts",
                receipts(result.managedTransitionReceipts()));
        report.put("managedTransitionReceiptsIdentity",
                result.managedTransitionReceiptsIdentity());
        long receiptGas = 0L;
        for (ManagedDocumentTransitionReceipt receipt
                : result.managedTransitionReceipts()) {
            receiptGas = Math.addExact(receiptGas, receipt.admittedGas());
        }
        if (!result.managedTransitionReceipts().isEmpty()) {
            require(receiptGas == result.totalGas(),
                    id + " managed transition receipts do not bind total gas");
        }
        ClosureCommitCompanion companion = result.platformCommitCompanion();
        if (companion != null) {
            ObjectNode binding = report.putObject("platformCommitCompanion");
            binding.put("companionIdentity", companion.companionIdentity());
            binding.put("managedTransitionReceiptsIdentity",
                    companion.managedTransitionReceiptsIdentity());
            binding.put("bindsManagedTransitionReceipts",
                    companion.bindsManagedTransitionReceipts());
            require(companion.bindsManagedTransitionReceipts(),
                    id + " commit companion used the compatibility constructor");
            require(result.managedTransitionReceiptsIdentity().equals(
                            companion.managedTransitionReceiptsIdentity()),
                    id + " commit companion receipt aggregate mismatch");
        }
    }

    private static ArrayNode receipts(
            List<ManagedDocumentTransitionReceipt> values) {
        ArrayNode result = UncheckedObjectMapper.JSON_MAPPER.createArrayNode();
        for (ManagedDocumentTransitionReceipt value : values) {
            ObjectNode item = result.addObject();
            item.put("transitionReceiptIdentity",
                    value.transitionReceiptIdentity());
            item.put("sourceInvocationIdentity",
                    value.sourceInvocationIdentity());
            item.put("transitionOrdinal", value.transitionOrdinal());
            item.put("transitionOccurrenceIdentity",
                    value.transitionOccurrenceIdentity());
            item.put("documentId", value.documentId().value());
            item.put("originalCauseIdentity", value.originalCauseIdentity());
            item.put("beforeBlueId", value.beforeBlueId());
            item.put("afterBlueId", value.afterBlueId());
            ArrayNode events = item.putArray("emittedRootEvents");
            for (ManagedRootEventOccurrence event
                    : value.emittedRootEvents()) {
                ObjectNode encoded = events.addObject();
                encoded.put("ordinal", event.ordinal());
                encoded.put("occurrenceOrdinal", event.occurrenceOrdinal());
                encoded.put("sourceDocumentId",
                        event.sourceDocumentId().value());
                encoded.put("occurrenceIdentity", event.occurrenceIdentity());
                encoded.put("eventBlueId", event.eventBlueId());
                encoded.set("exactEvent",
                        UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                                NodeWireForm.get(
                                        event.exactEvent(),
                                        NodeWireForm.Strategy.SIMPLE)));
                encoded.put("publicAtSource", event.publicAtSource());
            }
            item.put("emittedRootEventsIdentity",
                    value.emittedRootEventsIdentity());
            item.put("admittedGas", value.admittedGas());
        }
        return result;
    }

    private static JsonNode readYaml(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            JsonNode value = YAML.readTree(input);
            require(value != null, "YAML document is empty: " + path);
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("unable to read YAML " + path,
                    exception);
        }
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        require(child != null && child.isTextual()
                        && !child.textValue().isEmpty(),
                field + " must be non-empty text");
        return child.textValue();
    }

    private static List<String> textList(JsonNode values) {
        require(values != null && values.isArray(),
                "vectors must be an array");
        ArrayList<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            require(value.isTextual() && !value.textValue().isEmpty(),
                    "vector must be non-empty text");
            result.add(value.textValue());
        }
        return Collections.unmodifiableList(result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(ClosureImplementationEvidence value) {
            require(evidence == null,
                    "one observer callback is allowed per invocation");
            evidence = value;
        }
    }
}
