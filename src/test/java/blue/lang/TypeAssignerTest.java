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
        Node a = new Node().name("A");
        Node b = new Node().name("B").type("A");
        Node c = new Node().name("C").type("B");

        Node x = new Node().name("X").properties(
                "a", new Node().type("B")
        );
        Node y = new Node().name("Y").type("X").properties(
                "a", new Node().type("C")
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Collections.singletonList(
                        new TypeAssigner(types)
                )
        );
        NodeManager manager = new NodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        Node node = new Node();
        merger.merge(node, manager.getNode("Y"));

        assertEquals(node.getProperties().get("a").getType(), "C");
    }


    @Test
    public void testEmptyTypeIsInherited() throws Exception {
        Node a = new Node().name("A");
        Node b = new Node().name("B").type("A");
        Node c = new Node().name("C").type("B");

        Node x = new Node().name("X").properties(
                "a", new Node().type("B")
        );
        Node y = new Node().name("Y").type("X").properties(
                "a", new Node()
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Collections.singletonList(
                        new TypeAssigner(types)
                )
        );
        NodeManager manager = new NodeManager(nodes, nodeProcessor);

        Merger merger = new Merger(manager);
        Node node = new Node();
        merger.merge(node, manager.getNode("Y"));

        assertEquals(node.getProperties().get("a").getType(), "B");
    }

}