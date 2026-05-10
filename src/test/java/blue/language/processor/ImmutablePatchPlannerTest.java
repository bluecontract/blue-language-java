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

class ImmutablePatchPlannerTest {

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
}
