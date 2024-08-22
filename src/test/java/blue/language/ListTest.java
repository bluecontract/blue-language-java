package blue.language;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.utils.NodeExtender;
import blue.language.utils.limits.Limits;
import blue.language.provider.BasicNodeProvider;
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
    private BasicNodeProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;
    private Preprocessor preprocessor;
    private NodeExtender extender;

    @BeforeEach
    public void setUp() {
        a = new Node().name("A");
        aId = calculateBlueId(a);
        b = new Node().name("B");
        bId = calculateBlueId(b);
        c = new Node().name("C");
        cId = calculateBlueId(c);

        List<Node> nodes = asList(a, b, c);
        nodeProvider = new BasicNodeProvider(nodes);
        mergingProcessor = new SequentialMergingProcessor(
                asList(
//                        new ListBlueIdResolver(),
                        new ValuePropagator(),
                        new TypeAssigner()
                )
        );
        merger = new Merger(mergingProcessor, nodeProvider);
        preprocessor = new Preprocessor(nodeProvider);
        extender = new NodeExtender(nodeProvider);
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

        Node x2 = new Node()
                .name("X")
                .items(
                        new Node().blueId(calculateBlueId(asList(a, b))),
                        c
                );

        Node x3 = new Node()
                .name("X")
                .items(
                        new Node().blueId(calculateBlueId(asList(a, b, c)))
                );

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

        nodeProvider.addSingleNodes(x1, x2, x3, x4);
        nodeProvider.addListAndItsItems(asList(a, b));
        nodeProvider.addListAndItsItems(asList(a, b, c));

        Node x1Extended = preprocessAndExtend(x1);
        Node x2Extended = preprocessAndExtend(x2);
        Node x3Extended = preprocessAndExtend(x3);
        Node x4Extended = preprocessAndExtend(x4);

        assertEquals(3, x1Extended.getItems().size());
        assertEquals(3, x2Extended.getItems().size());
        assertEquals(3, x3Extended.getItems().size());
        assertEquals(3, x4Extended.getItems().size());
    }

    @Test
    public void testDifferentFlavoursOfAList2() throws Exception {

        String a = "A";
        String b = "B";
        String c = "C";

        Node aNode = YAML_MAPPER.readValue(a, Node.class);
        Node bNode = YAML_MAPPER.readValue(b, Node.class);
        Node cNode = YAML_MAPPER.readValue(c, Node.class);

        nodeProvider.addSingleNodes(aNode, bNode, cNode);

        List<Node> ab = Arrays.asList(aNode, bNode);
        String abId = BlueIdCalculator.calculateBlueId(ab);
        nodeProvider.addListAndItsItems(ab);

        List<Node> abc = Arrays.asList(aNode, bNode, cNode);
        String abcId = BlueIdCalculator.calculateBlueId(abc);
        nodeProvider.addListAndItsItems(abc);

        String x1 = "name: X1\n" +
                    "items:\n" +
                    "  - A\n" +
                    "  - B\n" +
                    "  - C";

        String x2 = "name: X1\n" +
                    "items:\n" +
                    "  - blueId: " + abId + "\n" +
                    "  - C";

        String x4 = "name: X1\n" +
                    "items:\n" +
                    "  - blueId: " + abcId;

        String x5 = "name: X1\n" +
                    "items:\n" +
                    "  blueId: " + abcId;

        Node x1Extended = preprocessAndExtend(x1);
        Node x2Extended = preprocessAndExtend(x2);
        Node x4Extended = preprocessAndExtend(x4);
        Node x5Extended = preprocessAndExtend(x5);

        assertEquals(3, x1Extended.getItems().size());
        assertEquals(3, x2Extended.getItems().size());
        assertEquals(3, x4Extended.getItems().size());
        assertEquals(3, x5Extended.getItems().size());

    }

    private Node preprocessAndExtend(String doc) {
        return preprocessAndExtend(YAML_MAPPER.readValue(doc, Node.class));
    }

    private Node preprocessAndExtend(Node node) {
        Node result = preprocessor.preprocessWithDefaultBlue(node);
        extender.extend(result, Limits.NO_LIMITS);
        return result;
    }

}
