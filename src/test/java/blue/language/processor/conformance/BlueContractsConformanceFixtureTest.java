package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.BlueContractsConformanceReport;
import blue.language.BlueContractsConformanceSuiteRunner;
import blue.language.model.Node;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceFixtureTest {

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    @Test
    void everyInventoriedExecutableFixturePassesClosedExecution() {
        BlueContractsConformanceReport report =
                new Blue().runContractsConformanceSuite();

        assertEquals(127, report.getFixtureIds().size());
        assertEquals(report.getFixtureIds(),
                report.getPassedFixtureIds(),
                report.getFailures()::toString);
        assertTrue(report.getFailedFixtureIds().isEmpty());
        assertEquals(0, report.getSkippedFixtureCount());
        assertTrue(report.isConformant());
    }

    @Test
    void everyInventoriedExecutableFixturePassesClosedMetadataValidation()
            throws IOException {
        JsonNode manifest = resource("manifest.yaml");
        for (JsonNode file : manifest.path("files")) {
            String role = file.path("role").asText();
            if (!"behavior-fixture".equals(role)
                    && !"gas-fixture".equals(role)) {
                continue;
            }
            JsonNode fixture = resource(file.path("path").asText());
            assertDoesNotThrow(() ->
                    BlueContractsConformanceSuiteRunner
                            .validateFixtureMetadataForTest(fixture),
                    file.path("path").asText());
        }
    }

    @Test
    void unselectedMissingExecutableBodyRemainsCollapsed()
            throws IOException {
        JsonNode fixture =
                resource("disc/c-disc-03.yaml");

        assertDoesNotThrow(
                () -> new ContractsFixtureHarness()
                        .execute(fixture, null, false));
    }

    @Test
    void cyclicSetMemberMutationFixtureUsesGenericRuntimeGuard()
            throws IOException {
        JsonNode fixture = resource("snd/c-snd-04.yaml");

        assertDoesNotThrow(
                () -> new ContractsFixtureHarness()
                        .execute(fixture, null, false));
    }

    @Test
    void selectedReferencedExecutableBodyIsVerifiedAndExecuted()
            throws IOException {
        ObjectNode fixture = (ObjectNode) resource(
                "disc/c-disc-03.yaml").deepCopy();
        ObjectNode input =
                (ObjectNode) fixture.path("input");
        ObjectNode root =
                (ObjectNode) input.path("root");
        ObjectNode handler =
                (ObjectNode) root.path("contracts")
                        .path("h");
        JsonNode body =
                handler.path("result").deepCopy();
        Node bodyNode =
                UncheckedObjectMapper.JSON_MAPPER.convertValue(
                        body, Node.class);
        String bodyBlueId =
                BlueIdCalculator.calculateBlueId(bodyNode);
        ObjectNode provider =
                (ObjectNode) input.path("provider");
        provider.putObject("nodes")
                .set(bodyBlueId, body);
        handler.putObject("result")
                .put("blueId", bodyBlueId);

        ContractsConformanceProjection projection =
                new ContractsFixtureHarness()
                        .execute(fixture, null, false);

        assertEquals(
                1L,
                ((Number) projection.project(
                        "result.document.value")
                        .getValue()).longValue(),
                projection.values()::toString);
        @SuppressWarnings("unchecked")
        List<Object> demands = (List<Object>) projection
                .project("demands.semantic")
                .getValue();
        assertTrue(demands.contains(bodyBlueId));
    }

    @Test
    void unknownFixtureFieldFailsClosed() throws IOException {
        ObjectNode fixture = gasFixture();
        fixture.put("undocumented", true);

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner
                        .validateFixtureMetadataForTest(fixture));
    }

    @Test
    void unknownOperationFailsClosed() throws IOException {
        ObjectNode fixture = gasFixture();
        fixture.put("operation", "invented-operation");

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner
                        .validateFixtureMetadataForTest(fixture));
    }

    @Test
    void unknownAssertionOperatorFailsClosed() throws IOException {
        ObjectNode fixture = gasFixture();
        firstAssertion(fixture).put("op", "silently-ignore");

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner
                        .validateFixtureMetadataForTest(fixture));
    }

    @Test
    void unknownProjectionFailsClosed() throws IOException {
        ObjectNode fixture = gasFixture();
        firstAssertion(fixture).put(
                "actual", "trace.undocumentedProjection");

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner
                        .validateFixtureMetadataForTest(fixture));
    }

    @Test
    void unknownRuntimeControlFailsClosed() throws IOException {
        ObjectNode fixture =
                (ObjectNode) resource("init/c-init-02.yaml").deepCopy();
        ((ObjectNode) fixture.path("input").path("runtime"))
                .put("hostMutation", true);

        assertThrows(IllegalArgumentException.class,
                () -> BlueContractsConformanceSuiteRunner
                        .validateFixtureMetadataForTest(fixture));
    }

    @Test
    void gasExpectedOutputIsEvaluatedAfterIndependentExecution()
            throws IOException {
        ObjectNode fixture = gasFixture();
        ((ObjectNode) fixture.path("expected")).put("totalGas", 999L);

        assertThrows(AssertionError.class,
                () -> new ContractsFixtureHarness()
                        .execute(fixture, null, false));
    }

    @Test
    void checkpointSubjectVariantIsStaleAndDoesNotInitialize()
            throws IOException {
        ObjectNode fixture =
                (ObjectNode) resource("init/c-init-01.yaml").deepCopy();
        ObjectNode root = (ObjectNode) fixture.path("input").path("root");
        JsonNode channel = root.path("contracts").path("in");
        Node channelNode = UncheckedObjectMapper.JSON_MAPPER.convertValue(
                channel, Node.class);
        String contributionBlueId =
                BlueIdCalculator.calculateBlueId(channelNode);
        String domainBlueId = CheckpointDomain.derive(
                channel.path("type").path("blueId").asText(),
                Collections.singletonList(contributionBlueId),
                channel.path("checkpointDomain").asText());
        String subjectBlueId = BlueIdCalculator.calculateBlueId(
                new Node().value("E1"));
        ObjectNode checkpoint = ((ObjectNode) root.path("contracts"))
                .putObject("checkpoint");
        checkpoint.putObject("type")
                .put("blueId", RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT);
        ObjectNode stored = checkpoint.putObject("entries")
                .putObject("in");
        stored.putObject("domain").put("blueId", domainBlueId);
        stored.putObject("subject").put("blueId", subjectBlueId);

        ArrayNode assertions = (ArrayNode) fixture.path("expected")
                .path("assertions");
        ObjectNode status = assertions.addObject();
        status.put("actual", "result.status");
        status.put("op", "equals");
        status.put("expected", "stale");
        status.put("variant", "stale");

        new ContractsFixtureHarness().execute(fixture, null, false);
    }

    @Test
    void checkpointSubjectVariantAcceptsExactObjectAndListSubjects()
            throws IOException {
        ObjectNode objectSubject = YAML.createObjectNode();
        objectSubject.put("value", "E1");
        ArrayNode listSubject = YAML.createArrayNode();
        listSubject.add("E1");

        for (JsonNode subject :
                new JsonNode[]{objectSubject, listSubject}) {
            ObjectNode fixture =
                    (ObjectNode) resource(
                            "init/c-init-01.yaml").deepCopy();
            ObjectNode stale = (ObjectNode) fixture.path("input")
                    .path("variants").get(1);
            stale.set("checkpointSubject", subject);
            ObjectNode status = ((ArrayNode) fixture.path("expected")
                    .path("assertions")).addObject();
            status.put("actual", "result.status");
            status.put("op", "equals");
            status.put("expected", "stale");
            status.put("variant", "stale");

            assertDoesNotThrow(
                    () -> new ContractsFixtureHarness()
                            .execute(fixture, null, false),
                    subject.toString());
        }
    }

    private static ObjectNode gasFixture() throws IOException {
        return (ObjectNode) YAML.readTree(
                "schema: blue-contracts-fixture/1.0\n"
                        + "id: local-gas-01\n"
                        + "vectors: [C-GAS-99]\n"
                        + "category: gas\n"
                        + "operation: gas-micro\n"
                        + "input:\n"
                        + "  namespace: processor\n"
                        + "  counter: processInvocation\n"
                        + "  quantity: 1\n"
                        + "  weightManifest: blue-contracts/gas/1.0\n"
                        + "expected:\n"
                        + "  totalGas: 50\n"
                        + "  assertions:\n"
                        + "  - actual: manifest.counterCoverage.complete\n"
                        + "    op: equals\n"
                        + "    expected: false\n");
    }

    private static ObjectNode firstAssertion(ObjectNode fixture) {
        return (ObjectNode) fixture.path("expected")
                .path("assertions").get(0);
    }

    private static JsonNode resource(String path) throws IOException {
        String resource = "blue-contracts-1.0/fixtures/" + path;
        try (InputStream input =
                     BlueContractsConformanceFixtureTest.class
                             .getClassLoader()
                             .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing test resource " + resource);
            }
            return YAML.readTree(input);
        }
    }
}
