package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.processor.ListItemsTypeChecker;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.limits.Limits;
import blue.language.provider.Types;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ListItemsTypeCheckerTest {

    @Test
    public void shouldAcceptCompatibleListItemTypes() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Node a = new Node().name("A");
        nodeProvider.addSingleNodes(a);
        Node b = new Node().name("B").type(
                new Node().blueId(nodeProvider.getBlueIdByName("A")));
        nodeProvider.addSingleNodes(b);
        Node c = new Node().name("C").type(
                new Node().blueId(nodeProvider.getBlueIdByName("B")));
        nodeProvider.addSingleNodes(c);

        Node x = new Node().name("X").properties(
                "a", new Node().type(new Node().blueId(nodeProvider.getBlueIdByName("B")))
        );
        nodeProvider.addSingleNodes(x);
        Node y = new Node().name("Y")
                .type(new Node().blueId(nodeProvider.getBlueIdByName("X"))).properties(
                "a", new Node().items(
                        new Node().type(new Node().blueId(nodeProvider.getBlueIdByName("B"))),
                        new Node().type(new Node().blueId(nodeProvider.getBlueIdByName("B")))
                )
        );
        nodeProvider.addSingleNodes(y);

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListItemsTypeChecker(types)
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = new Node();
        // when
        merger.merge(node, nodeProvider.fetchByBlueId(
                nodeProvider.getBlueIdByName("Y")).get(0), Limits.NO_LIMITS);

        // then
        assertEquals("B", node.getProperties().get("a").getType().getName());
    }


    @Test
    public void shouldRejectIncompatibleListItemTypes() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Node a = new Node().name("A");
        nodeProvider.addSingleNodes(a);
        Node b = new Node().name("B").type(
                new Node().blueId(nodeProvider.getBlueIdByName("A")));
        nodeProvider.addSingleNodes(b);
        Node c = new Node().name("C").type(
                new Node().blueId(nodeProvider.getBlueIdByName("B")));
        nodeProvider.addSingleNodes(c);

        Node x = new Node().name("X").properties(
                "a", new Node().type(new Node().blueId(nodeProvider.getBlueIdByName("B")))
        );
        nodeProvider.addSingleNodes(x);
        Node y = new Node().name("Y")
                .type(new Node().blueId(nodeProvider.getBlueIdByName("X"))).properties(
                "a", new Node().items(
                        new Node().type(new Node().blueId(nodeProvider.getBlueIdByName("A"))),
                        new Node().type(new Node().blueId(nodeProvider.getBlueIdByName("C")))
                )
        );
        nodeProvider.addSingleNodes(y);

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        Types types = new Types(nodes);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListItemsTypeChecker(types)
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        // when
        Node node = new Node();

        // then
        assertThrows(IllegalArgumentException.class, () -> {
            merger.merge(node, nodeProvider.fetchByBlueId(
                    nodeProvider.getBlueIdByName("Y")).get(0), Limits.NO_LIMITS);
        });
    }

}
