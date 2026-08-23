package blue.language.conformance.contracts;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.closure.Cclo34FullResultConformanceTest;
import blue.language.conformance.contracts.closure.ClosureFixtureConformance;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transactional and exact-evidence tests for the FL source compiler. */
final class FullLifecycleFixtureExporterTest {

    private static final ObjectMapper STRICT_YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    private static final Set<String> EXPECTED_NAMES = new TreeSet<String>(
            Arrays.asList(
                    "fl-adm-01-root-patch-event.yaml",
                    "fl-adm-02-duplicate-equal-events.yaml",
                    "fl-adm-03-non-public-containing-route.yaml",
                    "fl-adm-04-document-update-continuation.yaml",
                    "fl-adm-05-graceful-termination.yaml",
                    "fl-adm-06-order-representation-reference.yaml",
                    "fl-adm-06-order-representation-reversed.yaml",
                    "fl-adm-06-order-representation-inline.yaml",
                    "fl-adm-07-finite-cyclic-route.yaml",
                    "fl-adm-08-infinite-cycle-gas-retry-first.yaml",
                    "fl-adm-08-infinite-cycle-gas-retry-retry.yaml",
                    "fl-adm-09-late-member-rollback.yaml",
                    "fl-adm-10-unknown-occurrence.yaml"));

    @Test
    void shouldExportDeterministicCompleteFixturesAndReplayExactEvidence(
            @TempDir Path temporary) throws IOException {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));

        List<Path> firstFiles = FullLifecycleFixtureExporter.export(
                sourceRoot(), packageRoot(), first);
        List<Path> secondFiles = FullLifecycleFixtureExporter.export(
                sourceRoot(), packageRoot(), second);

        assertEquals(13, firstFiles.size());
        assertEquals(EXPECTED_NAMES, names(first));
        assertEquals(EXPECTED_NAMES, names(second));
        for (String name : EXPECTED_NAMES) {
            assertArrayEquals(
                    Files.readAllBytes(first.resolve(name)),
                    Files.readAllBytes(second.resolve(name)),
                    name);
        }

        for (String name : EXPECTED_NAMES) {
            Path fixturePath = first.resolve(name);
            JsonNode fixture = STRICT_YAML.readTree(fixturePath.toFile());
            if (name.equals(
                    "fl-adm-03-non-public-containing-route.yaml")) {
                assertFlAdm03InitializationSnapshots(fixture);
            }
            JsonNode cause = fixture.path("input").path("cause");
            String admissionLabel = cause.path("label").textValue();
            AdmissionCause expectedCause =
                    ClosureEvidenceFactory.admissionCause(
                            AdmissionKind.TOP_LEVEL_ADMISSION,
                            admissionLabel,
                            null,
                            null,
                            admissionLabel);
            assertEquals(expectedCause.policyIdentity(),
                    cause.path("policyIdentity").textValue(), name);
            assertEquals(expectedCause.causeIdentity(),
                    cause.path("causeIdentity").textValue(), name);
            JsonNode sharedLimitSource = fixture.get("sharedLimitSource");
            assertNotNull(sharedLimitSource, name);
            assertTrue(sharedLimitSource.isObject(), name);
            if (name.startsWith("fl-adm-08-")) {
                assertEquals(2, sharedLimitSource.size(), name);
                assertEquals("FIXTURE_OVERRIDE",
                        sharedLimitSource.path("kind").textValue(), name);
                assertEquals(20000L,
                        sharedLimitSource.path("sharedLimit").longValue(),
                        name);
            } else {
                assertEquals(1, sharedLimitSource.size(), name);
                assertEquals("RELEASE_DEFAULT",
                        sharedLimitSource.path("kind").textValue(), name);
            }
            JsonNode provider = fixture.get("provider");
            assertNotNull(provider, name);
            assertTrue(provider.isObject(), name);
            assertEquals(3, provider.size(), name);
            assertTrue(provider.path("nodes").isObject(), name);
            assertEquals(0, provider.path("nodes").size(), name);
            assertTrue(provider.path("expectedRequiredBlueIds").isArray(),
                    name);
            assertEquals(0,
                    provider.path("expectedRequiredBlueIds").size(), name);
            assertTrue(provider.path("expectedLoads").isArray(), name);
            assertEquals(0, provider.path("expectedLoads").size(), name);
            JsonNode locality = fixture.get("locality");
            assertNotNull(locality, name);
            assertTrue(locality.isObject(), name);
            assertEquals(2, locality.size(), name);
            assertEquals(0,
                    locality.path("unrelatedDocumentCount").intValue(), name);
            assertEquals(0,
                    locality.path("expectedUnrelatedDocumentsOpened")
                            .intValue(), name);
            JsonNode limit = fixture.get("limit");
            assertNotNull(limit, name);
            assertTrue(limit.isObject(), name);
            assertEquals(1, limit.size(), name);
            assertTrue(limit.has("probe"), name);
            assertTrue(limit.get("probe").isNull(), name);
            String fixtureId = name.substring(
                    0, name.length() - ".yaml".length());
            ClosureInvocationInput input =
                    ClosureFixtureConformance.parseAdmissionInput(
                            fixtureId,
                            "closure/" + name,
                            Collections.singletonList(
                                    fixture.path("vectors").path(0)
                                            .textValue()),
                            fixture);
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (ClosureFixtureRuntime runtime =
                         ClosureFixtureRuntime.fromFixture(
                                 fixture, packageRoot());
                 BlueClosureContracts contracts = new BlueClosureContracts(
                         runtime.processor(), capture)) {
                attempt = contracts.admitClosureWithLifecycleQueue(input);
            }
            assertNotNull(capture.evidence, name);
            assertTrue(capture.evidence.complete(), name);
            JsonNode semanticExpected = fixture.get("expected").deepCopy();
            normalizeIntegralWidths(semanticExpected);
            Cclo34FullResultConformanceTest.assertCompleteResult(
                    semanticExpected, attempt, capture.evidence);
        }
    }

    private static void normalizeIntegralWidths(JsonNode value) {
        if (value.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields =
                    value.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if (child.isIntegralNumber()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) value)
                            .put(field.getKey(), child.bigIntegerValue());
                } else {
                    normalizeIntegralWidths(child);
                }
            }
        } else if (value.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode array =
                    (com.fasterxml.jackson.databind.node.ArrayNode) value;
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isIntegralNumber()) {
                    array.set(index, com.fasterxml.jackson.databind.node
                            .BigIntegerNode.valueOf(child.bigIntegerValue()));
                } else {
                    normalizeIntegralWidths(child);
                }
            }
        }
    }

    private static void assertFlAdm03InitializationSnapshots(
            JsonNode fixture) {
        ObjectNode aResult = resultingDocument(fixture, "fl-adm-03-a");
        ObjectNode bResult = resultingDocument(fixture, "fl-adm-03-b");
        ObjectNode aDocument = (ObjectNode) aResult.path("document")
                .deepCopy();
        ObjectNode aContracts = (ObjectNode) aDocument.path("contracts");
        String aMarkerBlueId = aContracts.path("initialized")
                .path("document").path("blueId").textValue();
        aContracts.remove("initialized");
        String aBatchEntryBlueId = DirectBlueIdCalculator.calculateBlueId(
                UncheckedObjectMapper.JSON_MAPPER.convertValue(
                        aDocument, Node.class));
        String aInvocationBlueId = fixture.path("input").path("documents")
                .path("fl-adm-03-a").path("blueId").textValue();
        assertEquals(aBatchEntryBlueId, aMarkerBlueId,
                "FL-ADM-03 A must freeze its later component-batch entry");
        assertNotEquals(aInvocationBlueId, aMarkerBlueId,
                "FL-ADM-03 A must not freeze the earlier invocation input");

        String bInvocationBlueId = fixture.path("input").path("documents")
                .path("fl-adm-03-b").path("blueId").textValue();
        String bMarkerBlueId = bResult.path("document").path("contracts")
                .path("initialized").path("document").path("blueId")
                .textValue();
        assertEquals(bInvocationBlueId, bMarkerBlueId,
                "FL-ADM-03 B must freeze its invocation-input batch entry");
    }

    private static ObjectNode resultingDocument(
            JsonNode fixture, String documentId) {
        for (JsonNode result : fixture.path("expected")
                .path("resultingDocuments")) {
            if (documentId.equals(result.path("documentId").textValue())) {
                return (ObjectNode) result;
            }
        }
        throw new AssertionError("Missing resulting document " + documentId);
    }

    @Test
    void shouldLeaveOutputEmptyWhenACompleteSourceInventoryIsMalformed(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Files.write(
                stagedSources.resolve(
                        "fl-adm-05-graceful-termination.yaml"),
                "schema: malformed\n".getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);
        assertTrue(failure.getMessage().contains(
                "fl-adm-05-graceful-termination.yaml"));
    }

    @Test
    void shouldRejectUnknownSourceAndExpectFields(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-01-root-patch-event.yaml");
        com.fasterxml.jackson.databind.node.ObjectNode source =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        STRICT_YAML.readTree(sourcePath.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                source.get("expect")).put("checkpointWritez", 0);
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains("checkpointWritez"));
    }

    @Test
    void shouldRejectDuplicateYamlKeys(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-01-root-patch-event.yaml");
        String yaml = new String(
                Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        Files.write(sourcePath,
                yaml.replace(
                        "scenario: FL-ADM-01\n",
                        "scenario: FL-ADM-01\nscenario: FL-ADM-01\n")
                        .getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failureMessages(failure).contains("Duplicate field"),
                failureMessages(failure));
    }

    @Test
    void shouldRejectUnknownSourceField(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-01-root-patch-event.yaml");
        ObjectNode source = source(sourcePath);
        source.put("expext", true);
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains("expext"));
    }

    @Test
    void shouldRequireTheExactSourceFamilyIdentity(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-01-root-patch-event.yaml");
        com.fasterxml.jackson.databind.node.ObjectNode source =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        STRICT_YAML.readTree(sourcePath.toFile());
        source.put("scenario", "FL-ADM-10");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains("scenario"));
    }

    @Test
    void shouldRejectDocumentMacroThatDisagreesWithOccurrenceTarget(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-03-non-public-containing-route.yaml");
        com.fasterxml.jackson.databind.node.ObjectNode source =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        STRICT_YAML.readTree(sourcePath.toFile());
        com.fasterxml.jackson.databind.node.ObjectNode documents =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        source.get("documents");
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        documents.get("fl-adm-03-a").get("document");
        root.putObject("child").put(
                "$documentBlueId", "fl-adm-03-a");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "document macro target disagrees with occurrence"),
                failure.getMessage());
    }

    @Test
    void shouldRejectThisIndexThatDisagreesWithOccurrenceTarget(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-07-finite-cyclic-route.yaml");
        com.fasterxml.jackson.databind.node.ObjectNode source =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        STRICT_YAML.readTree(sourcePath.toFile());
        com.fasterxml.jackson.databind.node.ObjectNode documents =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        source.get("documents");
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        documents.get("fl-adm-07-a").get("document");
        root.putObject("b").put("blueId", "this#0");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "disagrees with occurrence target"),
                failure.getMessage());
    }

    @Test
    void shouldRejectUnresolvedOccurrenceDocumentReference(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-03-non-public-containing-route.yaml");
        ObjectNode source = source(sourcePath);
        ((ObjectNode) source.path("occurrences").path(0))
                .put("targetDocumentId", "missing-document");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "occurrence references unknown document"),
                failure.getMessage());
    }

    @Test
    void shouldRejectUnresolvedEventReference(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-01-root-patch-event.yaml");
        ObjectNode source = source(sourcePath);
        ObjectNode handler = (ObjectNode) source.path("runtime")
                .path("initializationHandlers")
                .path("fl-adm-01-root/initialize");
        ((ObjectNode) handler.path("events").path(0))
                .put("$eventValue", "missing-event");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains("unknown event"),
                failure.getMessage());
    }

    @Test
    void shouldRejectUnresolvedHandlerReference(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-01-root-patch-event.yaml");
        ObjectNode source = source(sourcePath);
        ObjectNode handlers = (ObjectNode) source.path("runtime")
                .path("handlers");
        JsonNode result = handlers.remove("fl-adm-01-root/react");
        handlers.set("fl-adm-01-root/missingContract", result);
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "runtime key does not select an authored contract"),
                failure.getMessage());
    }

    @Test
    void shouldRejectUnresolvedCaseReference(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-08-infinite-cycle-gas-retry.yaml");
        ObjectNode source = source(sourcePath);
        ((ObjectNode) source.path("cases").path(1))
                .put("repeatOf", "missing-case");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "repeatOf must name an earlier case"),
                failure.getMessage());
    }

    @Test
    void shouldRejectIncompleteInputOrder(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-07-finite-cyclic-route.yaml");
        ObjectNode source = source(sourcePath);
        ((ArrayNode) source.get("inputOrder")).remove(1);
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "inputOrder must be a complete document permutation"),
                failure.getMessage());
    }

    @Test
    void shouldRejectMalformedThisPlaceholder(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-07-finite-cyclic-route.yaml");
        ObjectNode source = source(sourcePath);
        ObjectNode root = (ObjectNode) source.path("documents")
                .path("fl-adm-07-a").path("document");
        ((ObjectNode) root.get("b")).put("blueId", "this#not-an-index");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "invalid cyclic placeholder"),
                failure.getMessage());
    }

    @Test
    void shouldRejectProcessorOwnedStatePatch(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-01-root-patch-event.yaml");
        ObjectNode source = source(sourcePath);
        ObjectNode patch = (ObjectNode) source.path("runtime")
                .path("initializationHandlers")
                .path("fl-adm-01-root/initialize")
                .path("patches").path(0);
        patch.put("path", "/contracts/initialized");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "runtime patch targets processor-owned state"),
                failure.getMessage());
    }

    @Test
    void shouldRejectFailureMixedWithSuccessfulOutput(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-09-late-member-rollback.yaml");
        ObjectNode source = source(sourcePath);
        ObjectNode result = (ObjectNode) source.path("runtime")
                .path("initializationHandlers")
                .path("fl-adm-09-b/failLater");
        result.putArray("patches").addObject()
                .put("op", "replace")
                .put("path", "/name")
                .put("val", "not-published");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "fail is exclusive with successful output"),
                failure.getMessage());
    }

    @Test
    void shouldRejectRepeatedCaseInputDisagreement(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-08-infinite-cycle-gas-retry.yaml");
        ObjectNode source = source(sourcePath);
        ObjectNode retry = (ObjectNode) source.path("cases").path(1);
        retry.putArray("inputOrder")
                .add("fl-adm-08-b")
                .add("fl-adm-08-a");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "repeatOf cannot add inputOrder or representation"),
                failure.getMessage());
    }

    @Test
    void shouldRejectUnresolvedMacro(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-01-root-patch-event.yaml");
        ObjectNode source = source(sourcePath);
        ObjectNode document = (ObjectNode) source.path("documents")
                .path("fl-adm-01-root").path("document");
        document.putObject("unresolved").put("$notAMacro", "value");
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains("contains unresolved macro"),
                failure.getMessage());
    }

    @Test
    void shouldNeverPublishPartialOutputAfterLateExpectationFailure(
            @TempDir Path temporary) throws IOException {
        Path stagedSources = stageSources(temporary);
        Path sourcePath = stagedSources.resolve(
                "fl-adm-10-unknown-occurrence.yaml");
        ObjectNode source = source(sourcePath);
        ((ObjectNode) source.get("expect")).put("checkpointWrites", 1);
        STRICT_YAML.writeValue(sourcePath.toFile(), source);

        IllegalArgumentException failure = assertRejectedWithoutOutput(
                temporary, stagedSources);

        assertTrue(failure.getMessage().contains(
                        "fl-adm-10-unknown-occurrence"),
                failure.getMessage());
    }

    private static Path stageSources(Path temporary) throws IOException {
        Path stagedSources = Files.createDirectory(
                temporary.resolve("sources"));
        try (Stream<Path> files = Files.list(sourceRoot())) {
            for (Path source : files
                    .filter(path -> path.getFileName().toString()
                            .matches("fl-adm-[0-9]{2}.*\\.yaml"))
                    .collect(Collectors.toList())) {
                Files.copy(source, stagedSources.resolve(
                        source.getFileName().toString()));
            }
        }
        return stagedSources;
    }

    private static ObjectNode source(Path path) throws IOException {
        return (ObjectNode) STRICT_YAML.readTree(path.toFile());
    }

    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                if (messages.length() > 0) {
                    messages.append(" | ");
                }
                messages.append(current.getMessage());
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static IllegalArgumentException assertRejectedWithoutOutput(
            Path temporary,
            Path stagedSources) throws IOException {
        Path output = Files.createDirectory(temporary.resolve("output"));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> FullLifecycleFixtureExporter.export(
                        stagedSources, packageRoot(), output));
        try (Stream<Path> files = Files.list(output)) {
            assertEquals(0L, files.count());
        }
        return failure;
    }

    private static Set<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Path sourceRoot() {
        return modulePath("src/main/fixture-sources/full-lifecycle");
    }

    private static Path packageRoot() {
        return modulePath(
                "src/main/resources/blue-contracts-closure-1.0");
    }

    private static Path modulePath(String relative) {
        Path working = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        Path direct = working.resolve(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromRoot = working.resolve("blue-conformance")
                .resolve(relative);
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new AssertionError("Cannot locate blue-conformance/" + relative);
    }

    private static final class Capture
            implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            evidence = value;
        }
    }
}
