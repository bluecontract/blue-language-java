package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmutablePatchPlannerTest {

    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void plansPatchMetadataAndNewRootWithoutMutatingOriginalRoot() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a:\n" +
                "  b: 1\n", Node.class));

        ImmutablePatchPlanner.PatchPlan plan = new ImmutablePatchPlanner(root)
                .plan("/a", JsonPatch.replace("/a/b", new Node().value(2)));

        assertEquals(BigInteger.ONE, root.at("/a/b").getValue());
        assertEquals(BigInteger.valueOf(2), plan.root().at("/a/b").getValue());
        assertEquals(BigInteger.ONE, plan.beforeNode().getValue());
        assertEquals(BigInteger.valueOf(2), plan.afterNode().getValue());
        assertEquals("/a/b", plan.path());
        assertEquals("/a", plan.originScope());
        assertEquals(Arrays.asList("/a", "/"), plan.cascadeScopes());
    }

    @Test
    void plansArrayAppendMetadataWithNullBeforeAndAppendedAfter() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "values:\n" +
                "  items:\n" +
                "    - a\n", Node.class));

        ImmutablePatchPlanner.PatchPlan plan = new ImmutablePatchPlanner(root)
                .plan("/", JsonPatch.add("/values/-", new Node().value("b")));

        assertNull(plan.beforeNode());
        assertEquals("b", plan.afterNode().getValue());
        assertEquals("a", root.at("/values/0").getValue());
        assertEquals("b", plan.root().at("/values/1").getValue());
    }

    @Test
    void plannerReadsAndReportsJsonPointerEscapedPaths() throws Exception {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "\"scope/one\":\n" +
                "  \"field~two\": old\n", Node.class));

        ImmutablePatchPlanner.PatchPlan plan = new ImmutablePatchPlanner(root)
                .plan("/scope~1one", JsonPatch.replace("/scope~1one/field~0two", new Node().value("new")));

        assertEquals("old", plan.beforeNode().getValue());
        assertEquals("new", plan.afterNode().getValue());
        assertEquals("new", plan.root().at("/scope~1one/field~0two").getValue());
        assertEquals("/scope~1one/field~0two", plan.path());
        assertEquals(Arrays.asList("/scope~1one", "/"), plan.cascadeScopes());
    }

    @Test
    void rejectsEveryMutationOperationStrictlyBelowPureCyclicSetMemberReference() {
        FrozenNode root = cyclicMemberRoot();
        ImmutablePatchPlanner planner = new ImmutablePatchPlanner(root);
        JsonPatch[] patches = {
                JsonPatch.add("/cyclic/member", new Node().value(1)),
                JsonPatch.replace("/cyclic/member", new Node().value(1)),
                JsonPatch.remove("/cyclic/member")
        };

        for (JsonPatch patch : patches) {
            ProcessorFailureException failure = assertThrows(
                    ProcessorFailureException.class,
                    () -> planner.plan("/", patch));

            assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                    failure.errorCategory());
            assertTrue(root.at("/cyclic").isReferenceOnly());
            assertEquals(CYCLIC_MEMBER_BLUE_ID,
                    root.at("/cyclic").getReferenceBlueId());
        }
    }

    @Test
    void wholeCyclicSetMemberReferenceCanBeReplacedBeforeWritingBelowIt() {
        FrozenNode root = cyclicMemberRoot();
        ImmutablePatchPlanner.PatchPlan replacement =
                new ImmutablePatchPlanner(root).plan(
                        "/",
                        JsonPatch.replace("/cyclic",
                                new Node().properties(
                                        "member",
                                        new Node().value("whole replacement"))));

        ImmutablePatchPlanner.PatchPlan descendant =
                new ImmutablePatchPlanner(replacement.root()).plan(
                        "/",
                        JsonPatch.add("/cyclic/next", new Node().value("allowed")));

        assertEquals("whole replacement",
                descendant.root().at("/cyclic/member").getValue());
        assertEquals("allowed",
                descendant.root().at("/cyclic/next").getValue());
        assertTrue(root.at("/cyclic").isReferenceOnly());
    }

    @Test
    void processEmbeddedCannotTreatCyclicMemberEndpointAsScope() {
        ImmutablePatchPlanner planner =
                new ImmutablePatchPlanner(cyclicMemberRoot());

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> planner.validateProcessEmbeddedTraversalPath(
                        "/cyclic"));

        assertEquals(
                ProcessorErrorCategory.CyclicSetMutationUnsupported,
                failure.errorCategory());
        assertTrue(failure.getMessage().contains(
                "Process Embedded traversal into cyclic-set member"));
    }

    @Test
    void introducingPureCyclicSetMemberReferenceBlocksOnlyLaterDescendantMutation() {
        FrozenNode initial = FrozenNode.fromNode(new Node());
        ImmutablePatchPlanner.PatchPlan introduced =
                new ImmutablePatchPlanner(initial).plan(
                        "/",
                        JsonPatch.add("/cyclic",
                                new Node().blueId(CYCLIC_MEMBER_BLUE_ID)));

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> new ImmutablePatchPlanner(introduced.root()).plan(
                        "/",
                        JsonPatch.add("/cyclic/member", new Node().value(1))));

        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                failure.errorCategory());
    }

    @Test
    void resolvedNodeWithCyclicProvenanceAndPayloadIsNotPureReferenceBoundary() {
        FrozenNode root = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "cyclic",
                        new Node()
                                .blueId(CYCLIC_MEMBER_BLUE_ID)
                                .properties("member", new Node().value("before"))));

        ImmutablePatchPlanner.PatchPlan plan =
                new ImmutablePatchPlanner(root).plan(
                        "/",
                        JsonPatch.replace(
                                "/cyclic/member",
                                new Node().value("after")));

        assertEquals("after", plan.root().at("/cyclic/member").getValue());
    }

    @Test
    void rejectsTraversalBelowCyclicMemberInEveryIntrinsicNodeChild() {
        for (String field : Arrays.asList(
                "type",
                "itemType",
                "keyType",
                "valueType",
                "blue",
                "contracts")) {
            FrozenNode root = FrozenNode.fromResolvedNode(
                    nodeWithIntrinsicCyclicReference(field));

            ProcessorFailureException failure = assertThrows(
                    ProcessorFailureException.class,
                    () -> new ImmutablePatchPlanner(root).plan(
                            "/",
                            JsonPatch.add(
                                    "/" + field + "/member",
                                    new Node().value(1))),
                    field);

            assertEquals(
                    ProcessorErrorCategory.CyclicSetMutationUnsupported,
                    failure.errorCategory(),
                    field);
        }
    }

    @Test
    void intrinsicTraversalTakesPrecedenceOverListItemTraversal() {
        for (String field : Arrays.asList(
                "type",
                "itemType",
                "keyType",
                "valueType",
                "blue",
                "contracts")) {
            FrozenNode root = FrozenNode.fromResolvedNode(
                    new Node().properties(
                            "list",
                            nodeWithIntrinsicCyclicReference(field)
                                    .items(new Node().value("retained item"))));

            ProcessorFailureException failure = assertThrows(
                    ProcessorFailureException.class,
                    () -> new ImmutablePatchPlanner(root).plan(
                            "/",
                            JsonPatch.add(
                                    "/list/" + field + "/member",
                                    new Node().value(1))),
                    field);

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
