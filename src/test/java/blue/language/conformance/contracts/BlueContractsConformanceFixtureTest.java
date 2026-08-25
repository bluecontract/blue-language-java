package blue.language.conformance.contracts;

import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.model.Node;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueContractsConformanceFixtureTest {

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    @Test
    void shouldPassClosedExecutionForEveryInventoriedExecutableFixture() {
        // given
        BlueContractsConformanceReport report =
                ContractsConformanceSuite.run();

        // when
        int fixtureCount = report.getFixtureIds().size();

        // then
        assertEquals(276, fixtureCount);
        assertEquals(report.getFixtureIds(),
                report.getPassedFixtureIds(),
                report.getFailures()::toString);
        assertTrue(report.getFailedFixtureIds().isEmpty());
        assertEquals(0, report.getSkippedFixtureCount());
        assertTrue(report.isConformant());
    }

    @Test
    void shouldPassClosedMetadataValidationForEveryInventoriedExecutableFixture()
            throws IOException {
        // given
        JsonNode manifest = resource("manifest.yaml");
        int expectedFixtureCount = 0;
        for (JsonNode file : manifest.path("files")) {
            String role = file.path("role").asText();
            if ("behavior-fixture".equals(role)
                    || "gas-fixture".equals(role)) {
                expectedFixtureCount++;
            }
        }

        // when
        int validatedFixtureCount = 0;
        for (JsonNode file : manifest.path("files")) {
            String role = file.path("role").asText();
            if (!"behavior-fixture".equals(role)
                    && !"gas-fixture".equals(role)) {
                continue;
            }
            JsonNode fixture = resource(file.path("path").asText());
            ContractsConformanceSuite.validateFixture(fixture);
            validatedFixtureCount++;
        }

        // then
        assertEquals(expectedFixtureCount,
                validatedFixtureCount);
    }

    @Test
    void shouldKeepUnselectedMissingExecutableBodyCollapsed()
            throws IOException {
        // given
        JsonNode fixture =
                resource("disc/c-disc-03.yaml");

        // when
        ContractsConformanceProjection projection =
                new ContractsFixtureHarness()
                        .execute(fixture, false);

        // then
        assertNotNull(projection);
    }

    @Test
    void shouldUseGenericRuntimeGuardForCyclicSetMemberMutationFixture()
            throws IOException {
        // given
        JsonNode fixture = resource("snd/c-snd-04.yaml");

        // when
        ContractsConformanceProjection projection =
                new ContractsFixtureHarness()
                        .execute(fixture, false);

        // then
        assertNotNull(projection);
    }

    @Test
    void shouldPassClosedExecutionForFinalRoutingCyclicAndFailureFixtures()
            throws IOException {
        // given
        String[] fixtures = {
                "feed/c-feed-11.yaml",
                "feed/c-feed-12.yaml",
                "feed/c-feed-13.yaml",
                "feed/c-feed-14.yaml",
                "feed/c-feed-15.yaml",
                "feed/c-feed-16.yaml",
                "feed/c-feed-17.yaml",
                "snd/c-cyc-01.yaml",
                "snd/c-cyc-02.yaml",
                "emb/c-cyc-03.yaml",
                "snd/c-cyc-04.yaml",
                "fail/c-fail-05.yaml",
                "init/c-init-06.yaml"
        };

        // when
        int executed = 0;
        for (String fixture : fixtures) {
            JsonNode input = resource(fixture);
            new ContractsFixtureHarness()
                    .execute(input, false);
            executed++;
        }

        // then
        assertEquals(fixtures.length, executed);
    }

    @Test
    void shouldNotAdmitArbitraryOrderMismatchForDeliveryHintTieOrdinal()
            throws IOException {
        // given
        ObjectNode fixture = (ObjectNode) resource(
                "feed/c-feed-14.yaml").deepCopy();
        ((ObjectNode) fixture.path("input")
                .path("feeder")
                .path("deliverySnapshot")
                .get(1)).put("order", 2);

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new ContractsFixtureHarness()
                        .execute(fixture, false));

        // then
        assertEquals(IllegalArgumentException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "Delivery hint order mismatch"));
    }

    @Test
    void shouldPreventStaleLogicalSourceFromInvalidatingFreshGroupedSource()
            throws IOException {
        // given
        ObjectNode fixture = (ObjectNode) resource(
                "feed/c-feed-17.yaml").deepCopy();
        ArrayNode assertions = (ArrayNode) fixture.path("expected")
                .path("assertions");
        assertions.removeAll();
        assertions.addObject()
                .put("actual", "result.status")
                .put("op", "present");

        // when
        ContractsConformanceProjection projection =
                new ContractsFixtureHarness()
                        .execute(fixture, false);

        // then
        assertEquals(
                "success",
                projection.project("result.status").getValue(),
                projection.values()::toString);
    }

    @Test
    void shouldExecuteInternalEventCycleBeforeLiveGasStopsIt()
            throws IOException {
        // given
        ObjectNode fixture = (ObjectNode) resource(
                "fail/c-fail-05.yaml").deepCopy();
        ArrayNode assertions = (ArrayNode) fixture.path("expected")
                .path("assertions");
        assertions.removeAll();
        assertions.addObject()
                .put("actual", "result.status")
                .put("op", "present");

        // when
        ContractsConformanceProjection projection =
                new ContractsFixtureHarness()
                        .execute(fixture, false);

        // then
        assertTrue(
                ((Number) projection.project(
                        "trace.eventOccurrencesDequeued")
                        .getValue()).longValue() > 0L,
                projection.values()::toString);
    }

    @Test
    void shouldValidateAndExecuteSelectedReferencedExecutableBody()
            throws IOException {
        // given
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
                DirectBlueIdCalculator.calculateBlueId(bodyNode);
        ObjectNode provider =
                (ObjectNode) input.path("provider");
        provider.putObject("nodes")
                .set(bodyBlueId, body);
        handler.putObject("result")
                .put("blueId", bodyBlueId);

        // when
        ContractsConformanceProjection projection =
                new ContractsFixtureHarness()
                        .execute(fixture, false);
        @SuppressWarnings("unchecked")
        List<Object> demands = (List<Object>) projection
                .project("demands.semantic")
                .getValue();

        // then
        assertEquals(
                1L,
                ((Number) projection.project(
                        "result.document.value")
                        .getValue()).longValue(),
                projection.values()::toString);
        assertTrue(demands.contains(bodyBlueId));
    }

    @Test
    void shouldFailClosedForUnknownFixtureField() throws IOException {
        // given
        ObjectNode fixture = gasFixture();
        fixture.put("undocumented", true);

        // when
        Throwable failure = captureFailure(
                () -> ContractsConformanceSuite.validateFixture(fixture));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldFailClosedForUnknownOperation() throws IOException {
        // given
        ObjectNode fixture = gasFixture();
        fixture.put("operation", "invented-operation");

        // when
        Throwable failure = captureFailure(
                () -> ContractsConformanceSuite.validateFixture(fixture));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldFailClosedForUnknownAssertionOperator() throws IOException {
        // given
        ObjectNode fixture = gasFixture();
        firstAssertion(fixture).put("op", "silently-ignore");

        // when
        Throwable failure = captureFailure(
                () -> ContractsConformanceSuite.validateFixture(fixture));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldFailClosedForUnknownProjection() throws IOException {
        // given
        ObjectNode fixture = gasFixture();
        firstAssertion(fixture).put(
                "actual", "trace.undocumentedProjection");

        // when
        Throwable failure = captureFailure(
                () -> ContractsConformanceSuite.validateFixture(fixture));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldFailClosedForUnknownRuntimeControl() throws IOException {
        // given
        ObjectNode fixture =
                (ObjectNode) resource("init/c-init-02.yaml").deepCopy();
        ((ObjectNode) fixture.path("input").path("runtime"))
                .put("hostMutation", true);

        // when
        Throwable failure = captureFailure(
                () -> ContractsConformanceSuite.validateFixture(fixture));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldEvaluateGasExpectedOutputAfterIndependentExecution()
            throws IOException {
        // given
        ObjectNode fixture = gasFixture();
        ((ObjectNode) fixture.path("expected")).put("totalGas", 999L);

        // when
        Throwable failure = captureFailure(
                () -> new ContractsFixtureHarness()
                        .execute(fixture, false));

        // then
        assertTrue(failure instanceof AssertionError);
    }

    @Test
    void shouldTreatCheckpointSubjectVariantAsStaleWithoutInitializing()
            throws IOException {
        // given
        ObjectNode fixture =
                (ObjectNode) resource("init/c-init-01.yaml").deepCopy();
        ObjectNode root = (ObjectNode) fixture.path("input").path("root");
        JsonNode channel = root.path("contracts").path("in");
        Node channelNode = UncheckedObjectMapper.JSON_MAPPER.convertValue(
                channel, Node.class);
        String contributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(channelNode);
        String domainBlueId = CheckpointDomain.derive(
                channel.path("type").path("blueId").asText(),
                Collections.singletonList(contributionBlueId),
                channel.path("checkpointDomain").asText());
        String subjectBlueId = DirectBlueIdCalculator.calculateBlueId(
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

        // when
        ContractsConformanceProjection projection =
                new ContractsFixtureHarness()
                        .execute(fixture, false);

        // then
        assertNotNull(projection);
    }

    @Test
    void shouldAcceptExactObjectAndListSubjectsForCheckpointSubjectVariant()
            throws IOException {
        // given
        ObjectNode objectSubject = YAML.createObjectNode();
        objectSubject.put("value", "E1");
        ArrayNode listSubject = YAML.createArrayNode();
        listSubject.add("E1");
        List<ObjectNode> fixtures = new ArrayList<>();
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
            fixtures.add(fixture);
        }

        // when
        List<Throwable> failures = new ArrayList<>();
        for (ObjectNode fixture : fixtures) {
            failures.add(captureFailure(
                    () -> new ContractsFixtureHarness()
                            .execute(fixture, false)));
        }

        // then
        for (int index = 0; index < failures.size(); index++) {
            assertTrue(
                    failures.get(index) == null,
                    fixtures.get(index).toString());
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
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object envelope =
                    new Yaml(new SafeConstructor(options)).load(input);
            return UncheckedObjectMapper.JSON_MAPPER.valueToTree(envelope);
        }
    }
}
