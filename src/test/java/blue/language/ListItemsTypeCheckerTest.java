package blue.language;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.processor.ListItemsTypeChecker;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;
import blue.language.utils.Types;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.language.TestUtils.useNodeNameAsBlueIdProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ListItemsTypeCheckerTest {

    @Test
    public void testSuccess() throws Exception {
        Node a = new Node().name("A").blueId("A");
        Node b = new Node().name("B").blueId("B").type(a);
        Node c = new Node().name("C").blueId("C").type(b);

        Node x = new Node().name("X").blueId("X").properties(
                "a", new Node().type(b)
        );
        Node y = new Node().name("Y").blueId("Y").type(x).properties(
                "a", new Node().items(
                        new Node().type(b),
                        new Node().type(b)
                )
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
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
        Node b = new Node().name("B").type(a);
        Node c = new Node().name("C").type(b);

        Node x = new Node().name("X").properties(
                "a", new Node().type(b)
        );
        Node y = new Node().name("Y").type(x).properties(
                "a", new Node().items(
                        new Node().type(a),
                        new Node().type(c)
                )
        );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
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