package blue.lang;

import blue.lang.graph.BasicNode;
import blue.lang.graph.BasicNodeManager;
import blue.lang.graph.Merger;
import blue.lang.graph.NodeProcessor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BasicTest {

    @Test
    public void testSimpleNodesMerge() throws Exception {

        BasicNode a = new BasicNode().name("A").value(1);
        BasicNode b = new BasicNode().name("B").type("A").value(2);
        BasicNode c = new BasicNode().name("C").type("B").value(3);

        BasicNode x = new BasicNode().name("X").properties(
                "a", new BasicNode().type("B")
        );
        BasicNode y = new BasicNode().name("Y").type("X").properties(
                "a", new BasicNode().type("C")
        );

        List<BasicNode> nodes = Arrays.asList(a, b, c, x, y);
        BasicNodeManager manager = new BasicNodeManager(nodes, numbersMustIncreasePayloadMerger());
        Merger merger = new Merger(manager);
        BasicNode node = new BasicNode();
        merger.merge(node, manager.getNode("Y"));

        assertEquals(node.getProperties().size(), 1);
        assertEquals(node.getProperties().get("a").getValue(), 3);

    }

    private static NodeProcessor numbersMustIncreasePayloadMerger() {
        return (target, source, nodeManager) -> {
            Integer targetValue = (Integer) target.getValue();
            Integer sourceValue = (Integer) source.getValue();
            if (sourceValue == null)
                return;
            if (targetValue != null && targetValue > sourceValue)
                throw new IllegalArgumentException("targetValue > sourceValue, " + targetValue + ", " + sourceValue);
            target.value(sourceValue);
        };
    }

}