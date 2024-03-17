package blue.lang;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.lang.TestUtils.numbersMustIncreasePayloadMerger;
import static blue.lang.TestUtils.useNodeNameAsBlueIdProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BasicTest {

    @Test
    public void testSimpleNodesMerge() throws Exception {

        Node a = new Node().name("A").value(1);
        Node b = new Node().name("B").type("A").value(2);
        Node c = new Node().name("C").type("B").value(3);

        Node x = new Node().name("X").properties(
                "a", new Node().type("B")
        );
        Node y = new Node().name("Y").type("X").properties(
                "a", new Node().type("C")
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        NodeProcessor nodeProcessor = numbersMustIncreasePayloadMerger();
        Merger merger = new Merger(nodeProvider, nodeProcessor);

        Node node = new Node();
        merger.merge(node, nodeProvider.fetchByBlueId("Y"));

        assertEquals(node.getProperties().size(), 1);
        assertEquals(node.getProperties().get("a").getValue(), 3);

    }

}