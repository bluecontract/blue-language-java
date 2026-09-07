package blue.language.conformance.contracts;

import blue.language.codec.jackson.UncheckedObjectMapper;
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

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractsFixtureHarnessControlTest {

    @Test
    void shouldReplaceChildBeforeMarkerWriteInCorrectedLifecycleFixture()
            throws IOException {
        // given
        ObjectNode fixture = copy("life/c-life-03.yaml");
        ArrayNode assertions = (ArrayNode) fixture.path("expected")
                .path("assertions");
        assertions.removeAll();
        ObjectNode status = assertions.addObject();
        status.put("actual", "result.status");
        status.put("op", "equals");
        status.put("expected", "success");

        // when
        ContractsConformanceProjection projection = execute(fixture);

        // then
        assertTrue(
                projection.project(
                        "result.document.child.replacement").isPresent(),
                projection.values()::toString);
        assertTrue(
                ContractsAssertionEvaluator.deepEquals(
                        projection.project(
                                "result.document.child.replacement")
                                .getValue(),
                        true));
    }

    @Test
    void shouldPassPublishedAssertionsForCorrectedAssignedFixtures()
            throws IOException {
        // given
        // when
        for (String fixture : Arrays.asList(
                "disc/c-disc-04.yaml",
                "e2e/c-e2e-02.yaml",
                "evt/c-evt-01.yaml",
                "life/c-life-03.yaml",
                "prot/c-prot-02.yaml")) {
            execute(resource(fixture));
        }
    // then
    }

    @Test
    void shouldUseDeclaredEmbeddedScopesForPublishedNestedScopeControls()
            throws IOException {
        // given
        // when
        for (String fixture : Arrays.asList(
                "evt/c-evt-03.yaml",
                "life/c-life-03.yaml",
                "upd/c-upd-03.yaml")) {
            execute(resource(fixture));
        }
    // then
    }

    @Test
    void shouldDeriveCanonicalDeliverySnapshotFromCollectionMembers()
            throws IOException {
        // given
        ObjectNode fixture = copy("emb/c-emb-08.yaml");
        fixture.put("operation", "platform");
        ArrayNode assertions = (ArrayNode) fixture.path("expected")
                .path("assertions");
        assertions.removeAll();
        ObjectNode assertion = assertions.addObject();
        assertion.put("actual", "feeder.canonicalSnapshot");
        assertion.put("op", "equals");
        ArrayNode expected = assertion.putArray("expected");
        expected.addObject()
                .put("scopePath", "/lessons/lesson-a")
                .put("channelKey", "in");
        expected.addObject()
                .put("scopePath", "/lessons/lesson-b")
                .put("channelKey", "in");

        // when
        ContractsConformanceProjection projection = execute(fixture);

        // then
        assertTrue(
                projection.project("feeder.canonicalSnapshot").isPresent());
    }

    @Test
    void shouldProcessCollectionMembersInCanonicalDeliveryOrder()
            throws IOException {
        // given
        ObjectNode fixture = copy("emb/c-emb-08.yaml");

        // when
        ContractsConformanceProjection projection = execute(fixture);

        // then
        assertEquals(
                "success",
                projection.project("result.status").getValue(),
                projection.values()::toString);
        assertEquals(
                Arrays.asList(
                        "/lessons/lesson-a:in",
                        "/lessons/lesson-b:in"),
                projection.project("trace.externalDeliveryOrder")
                        .getValue(),
                projection.values()::toString);
    }

    @Test
    void shouldProcessReferencedCollectionMembersAndChannels()
            throws IOException {
        // given
        List<String> fixtures = Arrays.asList(
                "emb/c-emb-13.yaml",
                "emb/c-emb-15.yaml");

        // when
        for (String fixture : fixtures) {
            ContractsConformanceProjection projection =
                    execute(resource(fixture));

            // then
            assertEquals(
                    "success",
                    projection.project("result.status").getValue(),
                    projection.values()::toString);
        }
    }

    @Test
    void shouldTargetOnlyTheSelectedCollectionMember()
            throws IOException {
        // given
        ObjectNode fixture = copy("feed/c-feed-18.yaml");

        // when
        ContractsConformanceProjection projection = execute(fixture);

        // then
        assertEquals(
                "success",
                projection.project("result.status").getValue(),
                projection.values()::toString);
        assertEquals(
                Arrays.asList("/lessons/lesson-a:in"),
                projection.project("trace.externalDeliveryOrder")
                        .getValue(),
                projection.values()::toString);
    }

    @Test
    void shouldRejectSameInvocationCollectionMemberReactivation()
            throws IOException {
        // given
        ObjectNode fixture = copy("emb/c-emb-11.yaml");

        // when
        ContractsConformanceProjection projection = execute(fixture);

        // then
        assertEquals(
                "invalid-processing-document",
                projection.project("result.status")
                        .getValue(),
                projection.values()::toString);
        assertEquals(
                false,
                projection.project("commit.rootCommitted")
                        .getValue(),
                projection.values()::toString);
    }

    @Test
    void shouldAllowInstallingRootForwardAllWithoutReceivingDescendant()
            throws IOException {
        // given
        ContractsConformanceProjection projection =
                execute(resource("evt/c-evt-04.yaml"));

        // when
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) projection
                .project("result.events").getValue();
        // then
        assertEquals(2, events.size());
        assertEquals(events.get(0), events.get(1));
    }

    @Test
    void shouldKeepSelectedChildEmissionsNonPublicWithoutRootForward()
            throws IOException {
        // given
        ContractsConformanceProjection projection =
                execute(resource("evt/c-evt-03.yaml"));
        // when
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) projection
                .project("result.events").getValue();
        // then
        assertTrue(events.isEmpty());
        assertEquals(
                1L,
                ((Number) projection.project(
                        "trace.eventOccurrencesDequeued")
                        .getValue()).longValue());
    }

    @Test
    void shouldEvaluateBothImplicationsForChannelLawCases()
            throws IOException {
        // given
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

        // when
        ContractsConformanceProjection projection =
                execute(fixture);

        // then
        assertTrue(projection != null);
    }

    @Test
    void shouldTreatRawIndexOmissionAsFeederNonconformance()
            throws IOException {
        // given
        ObjectNode fixture = copy("feed/c-feed-04.yaml");
        ArrayNode candidates = (ArrayNode) fixture.path("input")
                .path("feeder").path("rawIndexCandidates");
        candidates.remove(1);
        ObjectNode assertion = firstAssertion(fixture);
        assertion.put("actual", "platform.status");
        assertion.put("op", "equals");
        assertion.put("expected", "feeder-nonconformance");

        // when
        ContractsConformanceProjection projection =
                execute(fixture);

        // then
        assertTrue(projection != null);
    }

    @Test
    void shouldApplyOnlyMutableBusinessStateForAcceptanceVariants()
            throws IOException {
        // given
        JsonNode fixture =
                resource("feed/c-feed-03.yaml");

        // when
        ContractsConformanceProjection projection =
                execute(fixture);
        boolean firstAccepted =
                (Boolean) projection.variants().get("state-0")
                        .project("feeder.acceptanceResult")
                        .getValue();
        boolean secondAccepted =
                (Boolean) projection.variants().get("state-1")
                        .project("feeder.acceptanceResult")
                        .getValue();

        // then
        assertTrue(firstAccepted);
        assertTrue(secondAccepted);
    }

    @Test
    void shouldRejectAcceptanceVariantThatMutatesContracts()
            throws IOException {
        // given
        ObjectNode invalid = copy("feed/c-feed-03.yaml");
        ObjectNode firstState = (ObjectNode) invalid.path("input")
                .path("feeder").path("acceptanceStateVariants").get(0);
        firstState.putObject("contracts");

        // when
        IllegalArgumentException exception = captureFailure(
                () -> execute(invalid));

        // then
        assertEquals(IllegalArgumentException.class,
                exception.getClass());
        assertTrue(exception.getMessage().contains(
                "mutable business state"));
    }

    @Test
    void shouldRequireExactRetainedSnapshotPerEventInEventQueue()
            throws IOException {
        // given
        JsonNode fixture =
                resource("feed/c-feed-08.yaml");

        // when
        ContractsConformanceProjection projection =
                execute(fixture);
        Object callOrder =
                projection.project("feeder.callOrder")
                        .getValue();

        // then
        assertEquals(
                Arrays.asList("E1:/child", "E1:/", "E2:/"),
                callOrder);
    }

    @Test
    void shouldRejectEventQueueWithoutExactRetainedSnapshot()
            throws IOException {
        // given
        ObjectNode missing = copy("feed/c-feed-08.yaml");
        ((ObjectNode) missing.path("input").path("feeder")
                .path("targetsByEvent")).remove("E2");

        // when
        IllegalArgumentException exception = captureFailure(
                () -> execute(missing));

        // then
        assertEquals(IllegalArgumentException.class,
                exception.getClass());
        assertTrue(exception.getMessage().contains(
                "no retained snapshot for E2"));
    }

    @Test
    void shouldTraverseProcessAndPatchPipelineForListOperationVariants()
            throws IOException {
        // given
        ContractsConformanceProjection projection =
                execute(resource("rep/c-rep-07.yaml"));
        ContractsConformanceProjection append =
                projection.variants().get("append");
        // when
        ContractsConformanceProjection replace =
                projection.variants().get("replace-head");

        // then
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
    void shouldUseCanonicalRootContentForPureReferenceVariant()
            throws IOException {
        // given
        ContractsConformanceProjection projection =
                execute(resource("rep/c-rep-01.yaml"));

        ContractsConformanceProjection inline =
                projection.variants().get("inline");
        // when
        ContractsConformanceProjection reference =
                projection.variants().get("reference");
        // then
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
    void shouldUseExactValidatorProducedDeltaForSubscriptionProjection()
            throws IOException {
        // given
        JsonNode fixture =
                resource("idx/c-idx-02.yaml");

        // when
        ContractsConformanceProjection projection =
                execute(fixture);
        Object mode = projection.project(
                "commit.subscriptionDelta.mode")
                .getValue();
        @SuppressWarnings("unchecked")
        List<Object> startAfter =
                (List<Object>) projection.project(
                        "commit.newIntervals.0.startAfterExternalOrderKey")
                .getValue();

        // then
        assertEquals("incremental", mode);
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

    private static ObjectNode firstAssertion(ObjectNode fixture) {
        return (ObjectNode) fixture.path("expected")
                .path("assertions").get(0);
    }

    private static ObjectNode copy(String path) throws IOException {
        return (ObjectNode) resource(path).deepCopy();
    }

    private static ContractsConformanceProjection execute(
            JsonNode fixture) {
        return new ContractsFixtureHarness()
                .execute(fixture, false);
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
