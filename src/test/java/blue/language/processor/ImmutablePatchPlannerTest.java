package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmutablePatchPlannerTest {

    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void shouldPlanPatchMetadataAndNewRootWithoutMutatingOriginalRoot() {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a:\n" +
                "  b: 1\n", Node.class));

        // when
        ImmutablePatchPlanner.PatchPlan plan = new ImmutablePatchPlanner(root)
                .plan("/a", JsonPatch.replace("/a/b", new Node().value(2)));

        // then
        assertEquals(BigInteger.ONE, root.at("/a/b").getValue());
        assertEquals(BigInteger.valueOf(2), plan.root().at("/a/b").getValue());
        assertEquals(BigInteger.ONE, plan.beforeNode().getValue());
        assertEquals(BigInteger.valueOf(2), plan.afterNode().getValue());
        assertEquals("/a/b", plan.path());
        assertEquals("/a", plan.originScope());
        assertEquals(Arrays.asList("/a", "/"), plan.cascadeScopes());
    }

    @Test
    void shouldPlanArrayAppendMetadataWithNullBeforeAndAppendedAfter() {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "values:\n" +
                "  items:\n" +
                "    - a\n", Node.class));

        // when
        ImmutablePatchPlanner.PatchPlan plan = new ImmutablePatchPlanner(root)
                .plan("/", JsonPatch.add("/values/-", new Node().value("b")));

        // then
        assertNull(plan.beforeNode());
        assertEquals("b", plan.afterNode().getValue());
        assertEquals("a", root.at("/values/0").getValue());
        assertEquals("b", plan.root().at("/values/1").getValue());
    }

    @Test
    void shouldVerifyPlannerReadsAndReportsJsonPointerEscapedPaths() throws Exception {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "\"scope/one\":\n" +
                "  \"field~two\": old\n", Node.class));

        // when
        ImmutablePatchPlanner.PatchPlan plan = new ImmutablePatchPlanner(root)
                .plan("/scope~1one", JsonPatch.replace("/scope~1one/field~0two", new Node().value("new")));

        // then
        assertEquals("old", plan.beforeNode().getValue());
        assertEquals("new", plan.afterNode().getValue());
        assertEquals("new", plan.root().at("/scope~1one/field~0two").getValue());
        assertEquals("/scope~1one/field~0two", plan.path());
        assertEquals(Arrays.asList("/scope~1one", "/"), plan.cascadeScopes());
    }

    @Test
    void shouldRejectEveryMutationOperationStrictlyBelowPureCyclicSetMemberReference() {
        // given
        FrozenNode root = cyclicMemberRoot();
        ImmutablePatchPlanner planner = new ImmutablePatchPlanner(root);
        JsonPatch[] patches = {
                JsonPatch.add("/cyclic/member", new Node().value(1)),
                JsonPatch.replace("/cyclic/member", new Node().value(1)),
                JsonPatch.remove("/cyclic/member")
        };

        // when
        List<Throwable> failures =
                new ArrayList<>(patches.length);
        for (JsonPatch patch : patches) {
            failures.add(captureFailure(
                    () -> planner.plan("/", patch)));
        }

        // then
        for (Throwable failure : failures) {
            assertTrue(failure instanceof ProcessorFailureException);
            assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                    ((ProcessorFailureException) failure)
                            .errorCategory());
        }
        assertTrue(root.at("/cyclic").isReferenceOnly());
        assertEquals(CYCLIC_MEMBER_BLUE_ID,
                root.at("/cyclic").getReferenceBlueId());
    }

    @Test
    void shouldVerifyWholeCyclicSetMemberReferenceCanBeReplacedBeforeWritingBelowIt() {
        // given
        FrozenNode root = cyclicMemberRoot();
        ImmutablePatchPlanner.PatchPlan replacement =
                new ImmutablePatchPlanner(root).plan(
                        "/",
                        JsonPatch.replace("/cyclic",
                                new Node().properties(
                                        "member",
                                        new Node().value("whole replacement"))));

        // when
        ImmutablePatchPlanner.PatchPlan descendant =
                new ImmutablePatchPlanner(replacement.root()).plan(
                        "/",
                        JsonPatch.add("/cyclic/next", new Node().value("allowed")));

        // then
        assertEquals("whole replacement",
                descendant.root().at("/cyclic/member").getValue());
        assertEquals("allowed",
                descendant.root().at("/cyclic/next").getValue());
        assertTrue(root.at("/cyclic").isReferenceOnly());
    }

    @Test
    void shouldVerifyProcessEmbeddedCannotTreatCyclicMemberEndpointAsScope() {
        // given
        ImmutablePatchPlanner planner =
                new ImmutablePatchPlanner(cyclicMemberRoot());

        // when
        ProcessorFailureException failure = captureFailure(
                () -> planner.validateProcessEmbeddedTraversalPath(
                        "/cyclic"));

        // then
        assertEquals(ProcessorFailureException.class,
                failure.getClass());
        assertEquals(
                ProcessorErrorCategory
                        .CyclicSetEmbeddedBoundaryUnsupported,
                failure.errorCategory());
        assertTrue(failure.getMessage().contains(
                "Process Embedded traversal into cyclic-set member"));
    }

    @Test
    void shouldVerifyIntroducingPureCyclicSetMemberReferenceBlocksOnlyLaterDescendantMutation() {
        // given
        FrozenNode initial = FrozenNode.fromNode(new Node());
        ImmutablePatchPlanner.PatchPlan introduced =
                new ImmutablePatchPlanner(initial).plan(
                        "/",
                        JsonPatch.add("/cyclic",
                                new Node().blueId(CYCLIC_MEMBER_BLUE_ID)));

        // when
        ProcessorFailureException failure = captureFailure(
                () -> new ImmutablePatchPlanner(introduced.root()).plan(
                        "/",
                        JsonPatch.add("/cyclic/member", new Node().value(1))));

        // then
        assertEquals(ProcessorFailureException.class,
                failure.getClass());
        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                failure.errorCategory());
    }

    @Test
    void shouldVerifyResolvedNodeWithCyclicProvenanceAndPayloadIsNotPureReferenceBoundary() {
        // given
        FrozenNode root = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "cyclic",
                        new Node()
                                .blueId(CYCLIC_MEMBER_BLUE_ID)
                                .properties("member", new Node().value("before"))));

        // when
        ImmutablePatchPlanner.PatchPlan plan =
                new ImmutablePatchPlanner(root).plan(
                        "/",
                        JsonPatch.replace(
                                "/cyclic/member",
                                new Node().value("after")));

        // then
        assertEquals("after", plan.root().at("/cyclic/member").getValue());
    }

    @Test
    void shouldRejectTraversalBelowCyclicMemberInEveryIntrinsicNodeChild() {
        // given
        List<String> fields = Arrays.asList(
                "type",
                "itemType",
                "keyType",
                "valueType",
                "blue",
                "contracts");
        List<FrozenNode> roots =
                new ArrayList<>(fields.size());
        for (String field : fields) {
            roots.add(FrozenNode.fromResolvedNode(
                    nodeWithIntrinsicCyclicReference(field)));
        }

        // when
        List<ProcessorFailureException> failures =
                new ArrayList<>(fields.size());
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            FrozenNode root = roots.get(index);
            failures.add(captureFailure(
                    () -> new ImmutablePatchPlanner(root).plan(
                            "/",
                            JsonPatch.add(
                                    "/" + field + "/member",
                                    new Node().value(1)))));
        }

        // then
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            ProcessorFailureException failure =
                    failures.get(index);
            assertEquals(ProcessorFailureException.class,
                    failure.getClass(), field);
            assertEquals(
                    ProcessorErrorCategory.CyclicSetMutationUnsupported,
                    failure.errorCategory(),
                    field);
        }
    }

    @Test
    void shouldVerifyIntrinsicTraversalTakesPrecedenceOverListItemTraversal() {
        // given
        List<String> fields = Arrays.asList(
                "type",
                "itemType",
                "keyType",
                "valueType",
                "blue",
                "contracts");
        List<FrozenNode> roots =
                new ArrayList<>(fields.size());
        for (String field : fields) {
            roots.add(FrozenNode.fromResolvedNode(
                    new Node().properties(
                            "list",
                            nodeWithIntrinsicCyclicReference(field)
                                    .items(new Node().value(
                                            "retained item")))));
        }

        // when
        List<ProcessorFailureException> failures =
                new ArrayList<>(fields.size());
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            FrozenNode root = roots.get(index);
            failures.add(captureFailure(
                    () -> new ImmutablePatchPlanner(root).plan(
                            "/",
                            JsonPatch.add(
                                    "/list/" + field + "/member",
                                    new Node().value(1)))));
        }

        // then
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            ProcessorFailureException failure =
                    failures.get(index);
            assertEquals(ProcessorFailureException.class,
                    failure.getClass(), field);
            assertEquals(
                    ProcessorErrorCategory.CyclicSetMutationUnsupported,
                    failure.errorCategory(),
                    field);
        }
    }

    private FrozenNode cyclicMemberRoot() {
        return FrozenNode.fromNode(
                new Node().properties(
                        "cyclic",
                        new Node().blueId(CYCLIC_MEMBER_BLUE_ID)));
    }

    private Node nodeWithIntrinsicCyclicReference(String field) {
        Node root = new Node();
        Node reference = new Node().blueId(CYCLIC_MEMBER_BLUE_ID);
        if ("type".equals(field)) {
            return root.type(reference);
        }
        if ("itemType".equals(field)) {
            return root.itemType(reference);
        }
        if ("keyType".equals(field)) {
            return root.keyType(reference);
        }
        if ("valueType".equals(field)) {
            return root.valueType(reference);
        }
        if ("blue".equals(field)) {
            return root.blue(reference);
        }
        if ("contracts".equals(field)) {
            return root.contracts(reference);
        }
        throw new IllegalArgumentException("Unsupported intrinsic field: " + field);
    }
}
