package blue.language;

import blue.language.model.limits.Limits;
import blue.language.processor.*;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.language.TestUtils.useNodeNameAsBlueIdProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ListItemsTypeCheckerTest {

    @Test
    public void testSuccess() throws Exception {
        Node a = new Node().name("A");
        Node b = new Node().name("B").type("A");
        Node c = new Node().name("C").type("B");

        Node x = new Node().name("X").properties(
                "a", new Node().type("B")
        );
        Node y = new Node().name("Y").type("X").properties(
                "a", new Node().items(
                        new Node().type("B"),
                        new Node().type("C")
                )
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new TypeAssigner(),
                        new ListItemsTypeChecker(types)
                )
        );

        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = new Node();
        merger.merge(node, nodeProvider.fetchByBlueId("Y").get(0), Limits.NO_LIMITS);

        assertEquals("B", node.getProperties().get("a").getType().getName());
    }


    @Test
    public void testFailure() throws Exception {
        Node a = new Node().name("A");
        Node b = new Node().name("B").type("A");
        Node c = new Node().name("C").type("B");

        Node x = new Node().name("X").properties(
                "a", new Node().type("B")
        );
        Node y = new Node().name("Y").type("X").properties(
                "a", new Node().items(
                        new Node().type("A"),
                        new Node().type("C")
                )
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new TypeAssigner(),
                        new ListItemsTypeChecker(types)
                )
        );

        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = new Node();

        assertThrows(IllegalArgumentException.class, () -> {
            merger.merge(node, nodeProvider.fetchByBlueId("Y").get(0), Limits.NO_LIMITS);
        });
    }

}