package blue.language;

import blue.language.model.Node;
import blue.language.model.limits.Limits;
import blue.language.processor.*;
import blue.language.utils.BasicNodesProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ListTest {

    private Node a, b, c, x, y;
    private String aId, bId, cId, xId, yId;
    private BasicNodesProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        a = new Node().name("A");
        aId = calculateBlueId(a);
        b = new Node().name("B");
        bId = calculateBlueId(b);
        c = new Node().name("C");
        cId = calculateBlueId(c);

        List<Node> nodes = asList(a, b, c);
        nodeProvider = new BasicNodesProvider(nodes);
        mergingProcessor = new SequentialMergingProcessor(
                asList(
                        new BlueIdResolver(),
                        new ListBlueIdResolver(),
                        new ValuePropagator(),
                        new TypeAssigner()
                )
        );
        merger = new Merger(mergingProcessor, nodeProvider);
    }


    @Test
    public void testSubtypeHasMoreItemsThanParentType() throws Exception {
        x = new Node()
                .name("X")
                .items(
                        a,
                        b
                );
        xId = calculateBlueId(x);
        y = new Node()
                .name("Y")
                .type(new Node().blueId(xId))
                .items(
                        a,
                        b,
                        c
                );
        yId = calculateBlueId(y);

        nodeProvider.addSingleNodes(x, y);
        Node node = merger.resolve(nodeProvider.fetchByBlueId(yId).get(0), Limits.NO_LIMITS);

        assertEquals(3, node.getItems().size());
    }

    @Test
    public void testSubtypeHasLessItemsThanParentType() throws Exception {
        x = new Node()
                .name("X")
                .items(
                        a,
                        b,
                        c
                );
        xId = calculateBlueId(x);
        y = new Node()
                .name("Y")
                .type(new Node().blueId(xId))
                .items(
                        a,
                        b
                );
        yId = calculateBlueId(y);

        nodeProvider.addSingleNodes(x, y);
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(yId).get(0), Limits.NO_LIMITS));
    }

    @Test
    public void testSubtypeHasSameNumberOfItemsAsParentType() throws Exception {
        x = new Node()
                .name("X")
                .items(
                        a,
                        b
                );
        xId = calculateBlueId(x);
        y = new Node()
                .name("Y")
                .type(new Node().blueId(xId))
                .items(
                        a,
                        b
                );
        yId = calculateBlueId(y);

        nodeProvider.addSingleNodes(x, y);
        Node node = merger.resolve(nodeProvider.fetchByBlueId(yId).get(0), Limits.NO_LIMITS);

        assertEquals(2, node.getItems().size());
    }

    @Test
    public void testDifferentFlavoursOfAList() throws Exception {

        Node x1 = new Node()
                .name("X")
                .items(
                        a,
                        b,
                        c
                );
        String x1Id = calculateBlueId(x1);

        Node x2 = new Node()
                .name("X")
                .items(
                        new Node().blueId(calculateBlueId(asList(a, b))),
                        c
                );
        String x2Id = calculateBlueId(x2);

        Node x3 = new Node()
                .name("X")
                .items(
                        new Node().blueId(calculateBlueId(asList(a, b, c)))
                );
        String x3Id = calculateBlueId(x3);

        Node x4 = new Node()
                .name("X")
                .items(
                        new Node()
                                .blueId(calculateBlueId(
                                        asList(
                                                new Node().blueId(calculateBlueId(asList(a, b))),
                                                c
                                        )
                                ))
                );
        String x4Id = calculateBlueId(x4);

        nodeProvider.addSingleNodes(x1, x2, x3, x4);
        nodeProvider.addNodesList(asList(a, b));
        nodeProvider.addNodesList(asList(a, b, c));

        Node x1Node = nodeProvider.fetchByBlueId(x1Id).get(0);
        Node x2Node = nodeProvider.fetchByBlueId(x2Id).get(0);
        Node x3Node = nodeProvider.fetchByBlueId(x3Id).get(0);
        Node x4Node = nodeProvider.fetchByBlueId(x4Id).get(0);

        Node x1Resolved = merger.resolve(x1Node, Limits.NO_LIMITS);
        Node x2Resolved = merger.resolve(x2Node, Limits.NO_LIMITS);
        Node x3Resolved = merger.resolve(x3Node, Limits.NO_LIMITS);
        Node x4Resolved = merger.resolve(x4Node, Limits.NO_LIMITS);

        assertEquals(3, x1Resolved.getItems().size());
        assertEquals(3, x2Resolved.getItems().size());
        assertEquals(3, x3Resolved.getItems().size());
        assertEquals(3, x4Resolved.getItems().size());
    }

    @Test
    public void testDifferentFlavoursOfAList2() throws Exception {

        String a = "A";
        String b = "B";
        String c = "C";

        Node aNode = YAML_MAPPER.readValue(a, Node.class);
        Node bNode = YAML_MAPPER.readValue(b, Node.class);
        Node cNode = YAML_MAPPER.readValue(c, Node.class);

        BasicNodesProvider nodeProvider = new BasicNodesProvider(aNode, bNode, cNode);
        merger = new Merger(mergingProcessor, nodeProvider);

        List<Node> ab = Arrays.asList(aNode, bNode);
        String abId = BlueIdCalculator.calculateBlueId(ab);
        nodeProvider.addNodesList(ab);

        List<Node> abc = Arrays.asList(aNode, bNode, cNode);
        String abcId = BlueIdCalculator.calculateBlueId(abc);
        nodeProvider.addNodesList(abc);

        String x1 = "name: X1\n" +
                "items:\n" +
                "  - A\n" +
                "  - B\n" +
                "  - C";

        String x2 = "name: X1\n" +
                "items:\n" +
                "  - blueId: " + abId + "\n" +
                "  - C";

        String x3 = "name: X1\n" +
                "items:\n" +
                "  - " + abId + "\n" +
                "  - C";

        String x4 = "name: X1\n" +
                "items:\n" +
                "  - " + abcId;

        String x5 = "name: X1\n" +
                "items: " + abcId;

        String x6 = "blueId: " + abcId;

        Node x1Resolved = merger.resolve(YAML_MAPPER.readValue(x1, Node.class));
        Node x2Resolved = merger.resolve(YAML_MAPPER.readValue(x2, Node.class));
        Node x3Resolved = merger.resolve(YAML_MAPPER.readValue(x3, Node.class));
        Node x4Resolved = merger.resolve(YAML_MAPPER.readValue(x4, Node.class));
        Node x5Resolved = merger.resolve(YAML_MAPPER.readValue(x5, Node.class));
        Node x6Resolved = merger.resolve(YAML_MAPPER.readValue(x6, Node.class));

        assertEquals(3, x1Resolved.getItems().size());
        assertEquals(3, x2Resolved.getItems().size());
        assertEquals(3, x3Resolved.getItems().size());
        assertEquals(3, x4Resolved.getItems().size());
        assertEquals(3, x5Resolved.getItems().size());
        assertEquals(3, x6Resolved.getItems().size());

    }

}
