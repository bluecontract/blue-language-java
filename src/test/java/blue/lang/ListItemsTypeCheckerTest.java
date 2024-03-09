package blue.lang;

import blue.lang.graph.*;
import blue.lang.graph.processor.ListItemsTypeChecker;
import blue.lang.graph.processor.SequentialNodeProcessor;
import blue.lang.graph.processor.TypeAssigner;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ListItemsTypeCheckerTest {

    @Test
    public void testSuccess() throws Exception {
        BasicNode a = new BasicNode().name("A");
        BasicNode b = new BasicNode().name("B").type("A");
        BasicNode c = new BasicNode().name("C").type("B");

        BasicNode x = new BasicNode().name("X").properties(
                "a", new BasicNode().type("B")
        );
        BasicNode y = new BasicNode().name("Y").type("X").properties(
                "a", new BasicNode().items(
                        new BasicNode().type("B"),
                        new BasicNode().type("C")
                )
        );

        List<BasicNode> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Arrays.asList(
                        new TypeAssigner(types),
                        new ListItemsTypeChecker(types)
                )
        );
        BasicNodeManager manager = new BasicNodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        BasicNode node = new BasicNode();
        merger.merge(node, manager.getNode("Y"));

        assertEquals(node.getProperties().get("a").getType(), "B");
    }


    @Test
    public void testFailure() throws Exception {
        BasicNode a = new BasicNode().name("A");
        BasicNode b = new BasicNode().name("B").type("A");
        BasicNode c = new BasicNode().name("C").type("B");

        BasicNode x = new BasicNode().name("X").properties(
                "a", new BasicNode().type("B")
        );
        BasicNode y = new BasicNode().name("Y").type("X").properties(
                "a", new BasicNode().items(
                        new BasicNode().type("A"),
                        new BasicNode().type("C")
                )
        );

        List<BasicNode> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Arrays.asList(
                        new TypeAssigner(types),
                        new ListItemsTypeChecker(types)
                )
        );
        BasicNodeManager manager = new BasicNodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        BasicNode node = new BasicNode();

        assertThrows(IllegalArgumentException.class, () -> {
            merger.merge(node, manager.getNode("Y"));
        });
    }

}