package blue.language.conformance.contracts;

import blue.language.conformance.contracts.closure.ClosureFixtureConformance;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Explicit, disposable review evidence, not an exporter or an acceptance oracle.
 * Runs only through its dedicated JavaExec task. No fixture is rewritten and no
 * old outcome or status may change. Each selected fixture is executed once by
 * the existing owning runtime and serialized by its existing full-result view.
 */
final class ReviewedClosureFixtureCandidates {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Same bounded ceiling as ClosureFixtureInventory's verified-fixture parser.
    private static final int MAX_VERIFIED_FIXTURE_YAML_CODE_POINTS = 16 * 1024 * 1024;
    private static final ObjectMapper YAML = fixtureYamlMapper();

    // These are review candidates, not approved deltas. In particular C-CLO-27
    // must be reviewed only after the independent runtime controls are green.
    private static final Set<String> ALLOWED = Collections.unmodifiableSet(
            new TreeSet<String>(Arrays.asList(
                    "c-clo-02-acyclic-materialized-parity",
                    "c-clo-02-acyclic-pure-reference-parity",
                    "c-clo-02-dynamic-finite-cycle",
                    "c-clo-03-exact-tentative-identity-visibility",
                    "c-clo-04-default-policy-loop",
                    "c-clo-04-same-event-gas-loop",
                    "c-clo-05-direct-both-members",
                    "c-clo-06-duplicate-event-occurrences",
                    "c-clo-08-cycle-during-initialization",
                    "c-clo-09-merge-two-cycles",
                    "c-clo-10-split-to-singletons",
                    "c-clo-11-split-into-two-cycles",
                    "c-clo-12-frozen-edge-removal",
                    "c-clo-13-frozen-edge-addition",
                    "c-clo-19-multiple-public-roots-canonical",
                    "c-clo-19-public-event-boundary",
                    "c-clo-19-single-public-root",
                    "c-clo-20-late-outer-failure",
                    "c-clo-23-00-attach-a5-retry",
                    "c-clo-23-01-a5-to-a6",
                    "c-clo-23-02-a6-to-a7",
                    "c-clo-23-03-a7-to-a8",
                    "c-clo-23-04-a8-to-a9",
                    "c-clo-23-05-a9-to-a10",
                    "c-clo-24-occurrence-continuity",
                    "c-clo-25-policy-identity-binding",
                    "c-clo-26-embedded-local-cap",
                    "c-clo-27-multiple-scc-one-closure",
                    "c-clo-28-containing-spine-identity-gas",
                    "c-clo-28-mixed-result-shape",
                    "c-clo-30-language-cyclic-oracles",
                    "c-clo-33-checkpoint-domain-retirement",
                    "c-clo-34-separate-document-steps",
                    "c-clo-35-00-remove-and-create-successor",
                    "c-clo-35-01-readd-committed-successor",
                    "fl-adm-03-non-public-containing-route",
                    "fl-adm-06-order-representation-inline",
                    "fl-adm-06-order-representation-reference",
                    "fl-adm-06-order-representation-reversed",
                    "fl-adm-07-finite-cyclic-route",
                    "fl-adm-08-infinite-cycle-gas-retry-first",
                    "fl-adm-08-infinite-cycle-gas-retry-retry",
                    "fl-adm-09-late-member-rollback")));

    private ReviewedClosureFixtureCandidates() { }

    private static ObjectMapper fixtureYamlMapper() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(MAX_VERIFIED_FIXTURE_YAML_CODE_POINTS);
        return new ObjectMapper(YAMLFactory.builder().loaderOptions(options)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3,
                "Expected packageRoot, emptyTemporaryOutputRoot, commaSeparatedFixtureIds");
        Path packageRoot = Paths.get(args[0]).toRealPath();
        Path output = validateOutputDirectory(packageRoot, Paths.get(args[1]));
        List<String> ids = selectedIds(args[2]);
        Map<String, JsonNode> inventory = inventory(packageRoot, ids);
        // Nothing is published until all selected attempts and statuses match.
        // Retain results, not live runtime instances, between fixture executions.
        Map<String, ObjectNode> reports = new LinkedHashMap<String, ObjectNode>();
        for (String id : ids) {
            reports.put(id, collect(packageRoot, id, inventory.get(id)));
        }
        validateOutputDirectory(packageRoot, output);
        ObjectNode index = JSON.createObjectNode();
        index.put("kind", "review-candidates-not-approved-goldens");
        ArrayNode entries = index.putArray("entries");
        for (Map.Entry<String, ObjectNode> report : reports.entrySet()) {
            String file = report.getKey() + ".json";
            writeNew(output.resolve(file), report.getValue());
            ObjectNode entry = entries.addObject();
            entry.put("id", report.getKey());
            entry.put("report", file);
            entry.put("deltaCount", report.getValue().path("deltas").size());
        }
        // The index is the completion marker; a failed publication has none.
        writeNew(output.resolve("review-index.json"), index);
        System.out.println("REVIEW_CANDIDATES count=" + reports.size()
                + " output=" + output + " (not approved; fixtures unchanged)");
    }

    static List<String> selectedIds(String commaSeparated) {
        require(commaSeparated != null && !commaSeparated.trim().isEmpty(),
                "An explicit nonempty fixture ID list is required");
        Set<String> selected = new TreeSet<String>();
        for (String part : commaSeparated.split(",", -1)) {
            String id = part.trim();
            require(ALLOWED.contains(id), "Fixture is not in the review allowlist: " + id);
            require(selected.add(id), "Duplicate review fixture: " + id);
        }
        return Collections.unmodifiableList(new ArrayList<String>(selected));
    }

    static Path validateOutputDirectory(Path packageRoot, Path requested) throws IOException {
        Path output = requested.toRealPath();
        Path temporaryRoot = Paths.get(System.getProperty("java.io.tmpdir")).toRealPath();
        require(Files.isDirectory(output), "Output must be an existing empty directory");
        require(output.startsWith(temporaryRoot) && !output.equals(temporaryRoot),
                "Output must be a disposable child of the JVM temporary directory: " + temporaryRoot);
        require(!output.startsWith(packageRoot.toRealPath()),
                "Output must not be inside the fixture package");
        // Also reject a temporary checkout's source tree, even if outside the
        // supplied package. Resolve symlinks before making either decision.
        for (Path part : output) {
            require(!"src".equals(part.toString()), "Output must not be in a source tree");
        }
        try (DirectoryStream<Path> contents = Files.newDirectoryStream(output)) {
            require(!contents.iterator().hasNext(), "Output directory is not empty: " + output);
        }
        return output;
    }

    private static Map<String, JsonNode> inventory(Path packageRoot, List<String> ids)
            throws IOException {
        JsonNode manifest = YAML.readTree(Files.readAllBytes(packageRoot.resolve("fixtures/manifest.yaml")));
        require(manifest.path("files").isArray(), "Fixture manifest has no files array");
        Map<String, JsonNode> result = new LinkedHashMap<String, JsonNode>();
        for (JsonNode entry : manifest.path("files")) {
            if (!"closure-fixture".equals(entry.path("role").asText())) {
                continue;
            }
            for (String id : ids) {
                if (("closure/" + id + ".yaml").equals(entry.path("path").asText())) {
                    require(result.put(id, entry) == null, "Duplicate manifest entry: " + id);
                }
            }
        }
        require(result.size() == ids.size(), "Selected fixture missing from package manifest");
        return result;
    }

    private static ObjectNode collect(Path packageRoot, String id, JsonNode entry) throws Exception {
        String relative = text(entry, "path");
        Path fixturePath = packageRoot.resolve("fixtures").resolve(relative).toRealPath();
        require(fixturePath.startsWith(packageRoot), "Fixture escapes the selected package");
        byte[] sourceBytes = Files.readAllBytes(fixturePath);
        JsonNode fixture = YAML.readTree(sourceBytes);
        require(fixture.isObject() && id.equals(text(fixture, "id")), "Fixture ID mismatch: " + id);
        String operation = text(fixture, "operation");
        require("process-closure".equals(operation) || "admit-closure".equals(operation),
                "Unsupported diagnostic operation: " + operation);
        require(fixture.path("expected").isObject(), id + " has no original expected object");
        ObjectNode before = (ObjectNode) fixture.path("expected").deepCopy();
        require("Complete".equals(text(before, "attemptOutcome")),
                id + " is not an allowlisted complete-attempt baseline");
        text(before, "status");
        JsonNode originalTraceReference = attachTrace(packageRoot, id, before);
        List<String> vectors = new ArrayList<String>();
        require(entry.path("vectors").isArray(), id + " has no manifest vectors");
        for (JsonNode vector : entry.path("vectors")) {
            require(vector.isTextual(), "Invalid vector for " + id);
            vectors.add(vector.textValue());
        }
        // The owning parser validates the original envelope and removes its
        // expected subtree itself. Runtime preparation likewise receives no
        // expectations, preventing the candidate from becoming its own oracle.
        ClosureInvocationInput input = ClosureFixtureConformance.parseInvocationInput(
                id, relative, operation, vectors, fixture);
        ObjectNode executionFixture = (ObjectNode) fixture.deepCopy();
        executionFixture.remove("expected");
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime = ClosureFixtureRuntime.fromFixture(executionFixture, packageRoot);
             BlueClosureContracts contracts = new BlueClosureContracts(runtime.processor(), capture)) {
            attempt = "process-closure".equals(operation)
                    ? contracts.processClosure(input) : contracts.admitClosureWithLifecycleQueue(input);
        }
        ObjectNode after = FullLifecycleFixtureJson.expected(attempt, capture.evidence);
        requireSameOutcome(id, before, after);
        ObjectNode report = JSON.createObjectNode();
        report.put("kind", "review-candidate-not-approved-golden");
        report.put("id", id);
        report.put("path", relative);
        report.put("sourceSha256", hex(MessageDigest.getInstance("SHA-256").digest(sourceBytes)));
        if (originalTraceReference != null) {
            report.set("originalGasTraceFile", originalTraceReference);
        }
        report.set("expectedBefore", before);
        report.set("actualAfter", after);
        report.set("deltas", differences(before, after));
        return report;
    }

    private static JsonNode attachTrace(Path packageRoot, String id, ObjectNode before) throws IOException {
        JsonNode reference = before.get("gasTraceFile");
        if (reference == null || reference.isNull()) {
            return null;
        }
        require(reference.isTextual() && !reference.textValue().contains(".."), "Unsafe gas trace path");
        Path tracePath = packageRoot.resolve("fixtures/closure").resolve(reference.textValue()).toRealPath();
        require(tracePath.startsWith(packageRoot), "Trace escapes the selected package");
        JsonNode trace = YAML.readTree(Files.readAllBytes(tracePath));
        require(id.equals(text(trace, "fixture")) && trace.path("entries").isArray(), "Wrong gas trace fixture");
        before.set("gasTrace", trace.path("entries").deepCopy());
        before.remove("gasTraceFile");
        return reference;
    }

    static void requireSameOutcome(String id, JsonNode before, JsonNode after) {
        require(text(before, "attemptOutcome").equals(text(after, "attemptOutcome")),
                id + " changed attempt outcome; no review candidates published: " + after.path("attemptOutcome"));
        require(text(before, "status").equals(text(after, "status")),
                id + " changed status; no review candidates published: " + after.path("status"));
    }

    static ArrayNode differences(JsonNode before, JsonNode after) {
        ArrayNode result = JSON.createArrayNode();
        difference("", before, after, result);
        return result;
    }

    private static void difference(String path, JsonNode before, JsonNode after, ArrayNode result) {
        if (before != null && after != null && (before.equals(after)
                || before.isNumber() && after.isNumber()
                && before.decimalValue().compareTo(after.decimalValue()) == 0)) {
            return;
        }
        if (before != null && after != null && before.isObject() && after.isObject()) {
            Set<String> fields = new TreeSet<String>();
            before.fieldNames().forEachRemaining(fields::add);
            after.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                difference(path + "/" + field.replace("~", "~0").replace("/", "~1"),
                        before.get(field), after.get(field), result);
            }
        } else if (before != null && after != null && before.isArray() && after.isArray()) {
            for (int index = 0; index < Math.max(before.size(), after.size()); index++) {
                difference(path + "/" + index, before.get(index), after.get(index), result);
            }
        } else {
            ObjectNode delta = result.addObject();
            delta.put("path", path);
            delta.put("beforePresent", before != null);
            delta.put("afterPresent", after != null);
            if (before != null) { delta.set("before", before); }
            if (after != null) { delta.set("after", after); }
        }
    }

    private static void writeNew(Path output, JsonNode value) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && !value.textValue().isEmpty(),
                "Missing nonempty text field: " + field);
        return value.textValue();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(ClosureImplementationEvidence value) {
            require(evidence == null, "More than one implementation evidence result");
            evidence = value;
        }
    }
}
