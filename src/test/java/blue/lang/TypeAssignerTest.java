package blue.lang;

import blue.lang.graph.*;
import blue.lang.graph.processor.SequentialNodeProcessor;
import blue.lang.graph.processor.TypeAssigner;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TypeAssignerTest {

    @Test
    public void testPropertySubtype() throws Exception {
        BasicNode a = new BasicNode().name("A");
        BasicNode b = new BasicNode().name("B").type("A");
        BasicNode c = new BasicNode().name("C").type("B");

        BasicNode x = new BasicNode().name("X").properties(
                "a", new BasicNode().type("B")
        );
        BasicNode y = new BasicNode().name("Y").type("X").properties(
                "a", new BasicNode().type("C")
        );

        List<BasicNode> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Collections.singletonList(
                        new TypeAssigner(types)
                )
        );
        BasicNodeManager manager = new BasicNodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        BasicNode node = new BasicNode();
        merger.merge(node, manager.getNode("Y"));

        assertEquals(node.getProperties().get("a").getType(), "C");
    }


    @Test
    public void testEmptyTypeIsInherited() throws Exception {
        BasicNode a = new BasicNode().name("A");
        BasicNode b = new BasicNode().name("B").type("A");
        BasicNode c = new BasicNode().name("C").type("B");

        BasicNode x = new BasicNode().name("X").properties(
                "a", new BasicNode().type("B")
        );
        BasicNode y = new BasicNode().name("Y").type("X").properties(
                "a", new BasicNode()
        );

        List<BasicNode> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Collections.singletonList(
                        new TypeAssigner(types)
                )
        );
        BasicNodeManager manager = new BasicNodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        BasicNode node = new BasicNode();
        merger.merge(node, manager.getNode("Y"));

        assertEquals(node.getProperties().get("a").getType(), "B");
    }

}