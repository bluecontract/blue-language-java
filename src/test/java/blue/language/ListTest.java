package blue.language;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.processor.FailureCapture;
import blue.language.utils.NodeExpander;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ListTest {

    private Node a, b, c, x, y;
    private String aId, bId, cId, xId, yId;
    private BasicNodeProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;
    private Preprocessor preprocessor;
    private NodeExpander expander;

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
        expander = new NodeExpander(nodeProvider);
    }


    @Test
    public void shouldAllowSubtypeWithMoreItemsThanParentType() throws Exception {
        // given
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
        // when
        Node node = merger.resolve(nodeProvider.fetchByBlueId(yId).get(0), Limits.NO_LIMITS);

        // then
        assertEquals(3, node.getItems().size());
    }

    @Test
    public void shouldRejectSubtypeWithFewerItemsThanParentType() throws Exception {
        // given
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

        // when
        nodeProvider.addSingleNodes(x, y);
        // then
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(yId).get(0), Limits.NO_LIMITS));
    }

    @Test
    public void shouldResolveSubtypeWithSameItemCountAsParentType() throws Exception {
        // given
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
        // when
        Node node = merger.resolve(nodeProvider.fetchByBlueId(yId).get(0), Limits.NO_LIMITS);

        // then
        assertEquals(2, node.getItems().size());
    }

    @Test
    public void shouldResolveInlineAndReferencedListRepresentationsToSameItems() throws Exception {

        // given
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

        nodeProvider.addSingleNodes(x1, x2, x3);
        nodeProvider.addListAndItsItems(asList(a, b));
        nodeProvider.addListAndItsItems(asList(a, b, c));

        // when
        Node x1Expanded = preprocessAndExpand(x1);
        Node x2Expanded = preprocessAndExpand(x2);
        Node x3Expanded = preprocessAndExpand(x3);

        // then
        assertEquals(3, x1Expanded.getItems().size());
        assertEquals(3, x2Expanded.getItems().size());
        assertEquals(3, x3Expanded.getItems().size());
    }

    @Test
    public void shouldResolveYamlInlineAndReferencedListRepresentations() throws Exception {

        // given
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

        String x1 = "name: X1\n" +
                    "items:\n" +
                    "  - A\n" +
                    "  - B\n" +
                    "  - C";

        String x2 = "name: X1\n" +
                    "items:\n" +
                    "  - blueId: " + abId + "\n" +
                    "  - C";

        // when
        Node x1Expanded = preprocessAndExpand(x1);
        Node x2Expanded = preprocessAndExpand(x2);

        // then
        assertEquals(3, x1Expanded.getItems().size());
        assertEquals(3, x2Expanded.getItems().size());
    }

    @Test
    public void shouldRejectBlueIdObjectAsListItemsPayload() {
        // given
        Node aNode = YAML_MAPPER.readValue("A", Node.class);
        Node bNode = YAML_MAPPER.readValue("B", Node.class);
        Node cNode = YAML_MAPPER.readValue("C", Node.class);
        List<Node> abc = Arrays.asList(aNode, bNode, cNode);
        String abcId = BlueIdCalculator.calculateBlueId(abc);
        nodeProvider.addSingleNodes(aNode, bNode, cNode);
        nodeProvider.addListAndItsItems(abc);
        String invalid = "name: X1\n"
                + "items:\n"
                + "  blueId: " + abcId;

        // when
        Throwable failure =
                FailureCapture.captureFailure(
                        () -> preprocessAndExpand(invalid));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    private Node preprocessAndExpand(String doc) {
        return preprocessAndExpand(YAML_MAPPER.readValue(doc, Node.class));
    }

    private Node preprocessAndExpand(Node node) {
        Node result = preprocessor.preprocess(node);
        expander.expand(result, Limits.NO_LIMITS);
        return result;
    }

}
