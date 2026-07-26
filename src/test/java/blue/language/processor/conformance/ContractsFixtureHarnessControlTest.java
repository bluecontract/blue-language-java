package blue.language.processor.conformance;

import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractsFixtureHarnessControlTest {

    @Test
    void publishedUnexecutableControlsArePackageContradictions()
            throws IOException {
        assertContradiction(
                "evt/c-evt-03.yaml",
                "c-evt-03",
                "runtime.childEmissions",
                "no non-root occurrence");
        assertContradiction(
                "life/c-life-03.yaml",
                "c-life-03",
                "runtime.cascadeMutation.replaceScopeDuringLifecycle",
                "no exact non-root replacement scope");
        assertContradiction(
                "upd/c-upd-03.yaml",
                "c-upd-03",
                "runtime.cascadeMutation.sourceCutOffDuringUpdate",
                "only possible Document Update source is Root");
    }

    @Test
    void rootForwardAllMayBeInstalledWithoutReceivingADescendant()
            throws IOException {
        ContractsConformanceProjection projection =
                execute(resource("evt/c-evt-04.yaml"));

        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) projection
                .project("result.events").getValue();
        assertEquals(2, events.size());
        assertEquals(events.get(0), events.get(1));
    }

    @Test
    void selectedChildEmissionsRemainNonPublicWithoutRootForward()
            throws IOException {
        ObjectNode fixture = executableChildEmissionFixture();

        ContractsConformanceProjection projection =
                execute(fixture);
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) projection
                .project("result.events").getValue();
        assertTrue(events.isEmpty());
        assertEquals(
                1L,
                ((Number) projection.project(
                        "trace.eventOccurrencesDequeued")
                        .getValue()).longValue());
    }

    @Test
    void channelLawCasesEvaluateBothImplications()
            throws IOException {
        ObjectNode fixture = copy("feed/c-feed-02.yaml");
        ArrayNode laws = (ArrayNode) fixture.path("input")
                .path("feeder").path("channelLawCases");
        ObjectNode violation = laws.addObject();
        violation.put("accepts", false);
        violation.put("preselects", true);
        violation.put("keyIntersection", false);
        ObjectNode assertion = firstAssertion(fixture);
        assertion.put("op", "equals");
        ArrayNode expected = assertion.putArray("expected");
        expected.add(true);
        expected.add(true);
        expected.add(false);

        execute(fixture);
    }

    @Test
    void rawIndexOmissionIsFeederNonconformance()
            throws IOException {
        ObjectNode fixture = copy("feed/c-feed-04.yaml");
        ArrayNode candidates = (ArrayNode) fixture.path("input")
                .path("feeder").path("rawIndexCandidates");
        candidates.remove(1);
        ObjectNode assertion = firstAssertion(fixture);
        assertion.put("actual", "platform.status");
        assertion.put("op", "equals");
        assertion.put("expected", "feeder-nonconformance");

        execute(fixture);
    }

    @Test
    void acceptanceVariantsApplyOnlyMutableBusinessState()
            throws IOException {
        ContractsConformanceProjection projection =
                execute(resource("feed/c-feed-03.yaml"));

        assertTrue((Boolean) projection.variants().get("state-0")
                .project("feeder.acceptanceResult").getValue());
        assertTrue((Boolean) projection.variants().get("state-1")
                .project("feeder.acceptanceResult").getValue());

        ObjectNode invalid = copy("feed/c-feed-03.yaml");
        ObjectNode firstState = (ObjectNode) invalid.path("input")
                .path("feeder").path("acceptanceStateVariants").get(0);
        firstState.putObject("contracts");
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> execute(invalid));
        assertTrue(exception.getMessage().contains(
                "mutable business state"));
    }

    @Test
    void eventQueueRequiresAnExactRetainedSnapshotPerEvent()
            throws IOException {
        ContractsConformanceProjection projection =
                execute(resource("feed/c-feed-08.yaml"));
        assertEquals(
                Arrays.asList("E1:/child", "E1:/", "E2:/"),
                projection.project("feeder.callOrder").getValue());

        ObjectNode missing = copy("feed/c-feed-08.yaml");
        ((ObjectNode) missing.path("input").path("feeder")
                .path("targetsByEvent")).remove("E2");
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> execute(missing));
        assertTrue(exception.getMessage().contains(
                "no retained snapshot for E2"));
    }

    @Test
    void listOperationVariantsTraverseProcessAndPatchPipeline()
            throws IOException {
        ContractsConformanceProjection projection =
                execute(resource("rep/c-rep-07.yaml"));
        ContractsConformanceProjection append =
                projection.variants().get("append");
        ContractsConformanceProjection replace =
                projection.variants().get("replace-head");

        assertEquals(
                "success",
                append.project("result.status").getValue());
        assertEquals(
                "success",
                replace.project("result.status").getValue());
        assertEquals(
                1L,
                ((Number) append.project(
                        "trace.semantic.listFoldStepRecomputed")
                        .getValue()).longValue());
        assertEquals(
                1000L,
                ((Number) replace.project(
                        "trace.semantic.listFoldStepRecomputed")
                        .getValue()).longValue());
        assertTrue(
                ((Number) append.project(
                        "trace.processor.processInvocation")
                        .getValue()).longValue() > 0L);
        assertTrue(
                ((Number) replace.project(
                        "trace.processor.processInvocation")
                        .getValue()).longValue() > 0L);
    }

    @Test
    void pureReferenceVariantUsesTheCanonicalRootContent()
            throws IOException {
        ContractsConformanceProjection projection =
                execute(resource("rep/c-rep-01.yaml"));

        ContractsConformanceProjection inline =
                projection.variants().get("inline");
        ContractsConformanceProjection reference =
                projection.variants().get("reference");
        assertEquals(
                inline.project("result").getValue(),
                reference.project("result").getValue());
        assertEquals(
                inline.project("trace.gas").getValue(),
                reference.project("trace.gas").getValue());
        assertEquals(
                inline.project("demands.semantic").getValue(),
                reference.project("demands.semantic").getValue());
        assertEquals(
                inline.project(
                        "trace.contractSnapshots./h.sourceContributionNodeBlueIds")
                        .getValue(),
                reference.project(
                        "trace.contractSnapshots./h.sourceContributionNodeBlueIds")
                        .getValue());
    }

    @Test
    void subscriptionProjectionUsesTheExactValidatorProducedDelta()
            throws IOException {
        ContractsConformanceProjection projection =
                execute(resource("idx/c-idx-02.yaml"));

        assertEquals(
                "incremental",
                projection.project(
                        "commit.subscriptionDelta.mode")
                        .getValue());
        assertEquals(
                "new",
                projection.project(
                        "commit.newIntervals.0.channelKey")
                        .getValue());
        assertEquals(
                8L,
                ((Number) projection.project(
                        "commit.newIntervals.0.activationRootRevision")
                        .getValue()).longValue());
        @SuppressWarnings("unchecked")
        List<Object> startAfter =
                (List<Object>) projection.project(
                        "commit.newIntervals.0.startAfterExternalOrderKey")
                        .getValue();
        assertEquals(3, startAfter.size());
        assertEquals(
                1000L,
                ((Number) startAfter.get(0)).longValue());
        assertEquals("timeline", startAfter.get(1));
        assertEquals(
                1L,
                ((Number) startAfter.get(2)).longValue());
        assertEquals(
                0,
                ((List<?>) projection.project(
                        "commit.retiredIntervals")
                        .getValue()).size());
    }

    private static void assertContradiction(
            String resource,
            String fixtureId,
            String control,
            String reason) throws IOException {
        FixturePackageContradictionException exception =
                assertThrows(
                        FixturePackageContradictionException.class,
                        () -> execute(resource(resource)));
        assertEquals(fixtureId, exception.fixtureId());
        assertEquals(control, exception.control());
        assertTrue(exception.getMessage().contains(reason));
    }

    private static ObjectNode firstAssertion(ObjectNode fixture) {
        return (ObjectNode) fixture.path("expected")
                .path("assertions").get(0);
    }

    private static ObjectNode executableChildEmissionFixture()
            throws IOException {
        ObjectNode fixture = copy("evt/c-evt-03.yaml");
        ObjectNode root =
                (ObjectNode) fixture.path("input").path("root");
        ObjectNode rootContracts =
                (ObjectNode) root.path("contracts");
        ObjectNode childContracts = rootContracts.deepCopy();

        JsonNode scalar = root.remove("value");
        root.set("rootValue", scalar);
        ((ObjectNode) rootContracts.path("h")
                .path("result")).remove("patches");
        ObjectNode embedded =
                rootContracts.putObject("embedded");
        embedded.putObject("type").put(
                "blueId", RuntimeBlueIds.PROCESS_EMBEDDED);
        embedded.putArray("paths").add("/child");

        ObjectNode child = root.putObject("child");
        child.put("counter", 0);
        ((ObjectNode) childContracts.path("h")
                .path("result").path("patches").get(0))
                .put("path", "/child/counter");
        child.set("contracts", childContracts);

        ArrayNode hints = (ArrayNode) fixture.path("input")
                .path("feeder").path("deliverySnapshot");
        ObjectNode childHint =
                ((ObjectNode) hints.get(0)).deepCopy();
        childHint.put("scopePath", "/child");
        hints.insert(0, childHint);
        return fixture;
    }

    private static ObjectNode copy(String path) throws IOException {
        return (ObjectNode) resource(path).deepCopy();
    }

    private static ContractsConformanceProjection execute(
            JsonNode fixture) {
        return new ContractsFixtureHarness()
                .execute(fixture, null, false);
    }

    private static JsonNode resource(String path)
            throws IOException {
        String resource =
                "blue-contracts-1.0/fixtures/" + path;
        try (InputStream input =
                     ContractsFixtureHarnessControlTest.class
                             .getClassLoader()
                             .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing test resource " + resource);
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object fixture =
                    new Yaml(new SafeConstructor(options)).load(input);
            return UncheckedObjectMapper.JSON_MAPPER
                    .valueToTree(fixture);
        }
    }
}
