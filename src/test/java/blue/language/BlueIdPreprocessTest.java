package blue.language;

import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.utils.BasicNodesProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.TestUtils.indent;
import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class BlueIdPreprocessTest {

    private MergingProcessor mergingProcessor;

    @BeforeEach
    public void setUp() {
        mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ConstraintsPropagator(),
                        new ConstraintsVerifier()
                )
        );
    }
    @Test
    public void testValuePreprocess() throws Exception {

        String a = "name: A\n" +
                "value: 2\n";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2);

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "value: 2";

        Node result =  process(Stream.of(a, b, x, y), "Y");
        assertNull(result.getValue());
        assertNull(result.getType().getValue());
        assertNull(result.getType().getType().getValue());
        assertEquals(BigInteger.valueOf(2), result.getType().getType().getType().getValue());
    }

    @Test
    public void testValuePreprocess2() throws Exception {

        String a = "name: A\n" +
                "value: 1\n";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2) + "\n" +
                "value: 1";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "value: 2";

        Node result =  process(Stream.of(a, b, x, y), "Y");
        assertEquals(BigInteger.valueOf(2), result.getValue());
        assertNull(result.getType().getValue());
        assertNull(result.getType().getType().getValue());
        assertEquals(BigInteger.valueOf(1), result.getType().getType().getType().getValue());
    }

    @Test
    public void testValuePreprocess3() throws Exception {

        String a = "name: A\n" +
                "value: 1\n";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2) + "\n" +
                "value: 1";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "value: 2";

        Node result =  process(Stream.of(a, b, x, y), "Y");
        assertEquals(BigInteger.valueOf(2), result.getValue());
        assertNull(result.getType().getValue());
        assertNull(result.getType().getType().getValue());
        assertEquals(BigInteger.valueOf(1), result.getType().getType().getType().getValue());
    }

    @Test
    public void testPropertyValuePreprocess() throws Exception {

        String a = "name: A\n" +
                "a: 1\n";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2) + "\n" +
                "a: 1";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a: 2";

        Node result =  process(Stream.of(a, b, x, y), "Y");
        assertEquals(BigInteger.valueOf(2), result.getProperties().get("a").getValue());
        assertThrows(NullPointerException.class, () -> result.getType().getProperties().get("a").getValue());
        assertThrows(NullPointerException.class, () -> result.getType().getType().getProperties().get("a").getValue());
        assertEquals(BigInteger.valueOf(1), result.getType().getType().getType().getProperties().get("a").getValue());
    }

    @Test
    public void testPropertyPreprocessOne() throws Exception {

        String a = "name: A\n" +
                "a: 1\n";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "a: 1";


        Node result = process(Stream.of(a, b), "B");
        assertEquals(BigInteger.valueOf(1), result.getType().getProperties().get("a").getValue());
        assertThrows(NullPointerException.class, () -> result.getProperties().get("a").getValue());
    }

    @Test
    public void testPropertyPreprocess() throws Exception {

        String a = "name: A\n" +
                "a: 1\n";

        String b = "name: B\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2);

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4);


        Node result =  process(Stream.of(a, b, x, y), "Y");
        assertEquals(BigInteger.valueOf(1), result.getType().getType()
                .getProperties().get("b")
                .getType().getProperties().get("a").getValue());
        assertThrows(NullPointerException.class, () -> result.getProperties().get("b").getValue());

    }

    @Test
    public void testListPreprocess() throws Exception {

        String a = "name: A\n" +
                "items:\n" +
                "  - 1\n" +
                "  - 2\n";

        String b = "name: B\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2);

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4) + "\n" +
                "  items:\n" +
                "    - 1\n" +
                "    - 2\n";


        Node result =  process(Stream.of(a, b, x, y), "Y");
        assertThrows(NullPointerException.class, () -> result.getProperties().get("b"));
        assertNull(result.getType().getItems());
        assertNull(result.getType().getType().getProperties().get("b").getItems());
        assertNotNull(result.getType().getType().getProperties().get("b").getType().getItems());
    }

    @Test
    public void testListPreprocess2() throws Exception {

        String a = "name: A\n" +
                "items:\n" +
                "  - name: A1\n" +
                "    value: 1\n" +
                "  - name: A2\n" +
                "    value: 2\n";

        String b = "name: B\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2);

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4) + "\n" +
                "  items:\n" +
                "    - name: A1\n" +
                "      value: 1\n" +
                "    - name: A2\n" +
                "      value: 2\n" +
                "    - name: A3\n" +
                "      value: 3";

        Node result =  process(Stream.of(a, b, x, y), "Y");
        System.out.println(YAML_MAPPER.writeValueAsString(NodeToObject.get(result)));
        assertNotNull(result.getProperties().get("b"));
        assertEquals(2, result.getProperties().get("b").getItems().stream().count());
        assertNull(result.getType().getItems());
        assertNull(result.getType().getType().getProperties().get("b").getItems());
        assertNotNull(result.getType().getType().getProperties().get("b").getType().getItems());
    }

    @Test
    public void testListPreprocess1() throws Exception {

        String a = "name: A\n" +
                "items: \n" +
                "  - 1\n" +
                "  - 2";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "items: \n" +
                "  - 1\n" +
                "  - 2";

        Node result =  process(Stream.of(a, b), "B");
        System.out.println(YAML_MAPPER.writeValueAsString(NodeToObject.get(result)));
        assertNull(result.getItems());
        assertNotNull(result.getType().getItems());
    }

    @Test
    public void testEqualBlueId() throws Exception {

        String a = "name: A\n" +
                "a: 1\n";

        String b = "name: B\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2);

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4) + "\n" +
                "c: 1";

        String result = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "c: 1\n";


        Node processed = process(Stream.of(a, b, x, y), "Y");
        Node expected = YAML_MAPPER.readValue(result, Node.class);
        assertEquals(BlueIdCalculator.calculateBlueId(processed), BlueIdCalculator.calculateBlueId(expected));
    }

    @Test
    public void testEqualBlueId2() throws Exception {

        String a = "name: A\n" +
                "a: 1\n";

        String b = "name: B\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4) + "\n" +
                "c: 1";

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2);


        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "c: 1";

        String result = "name: Y\n" +
                "type:\n" +
                indent(x, 2);

        Node processed = process(Stream.of(a, b, x, y), "Y");
        Node expected = YAML_MAPPER.readValue(result, Node.class);
        assertEquals(BlueIdCalculator.calculateBlueId(processed), BlueIdCalculator.calculateBlueId(expected));
    }

    @Test
    public void testListBlueId() throws Exception {

        String a = "name: A\n" +
                "items:\n" +
                "  - name: A1\n" +
                "    value: 1\n" +
                "  - name: A2\n" +
                "    value: 2\n" +
                "  - name: A3\n" +
                "    value: 3";

        String b = "name: B\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4);

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 2);

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "b: \n" +
                "  type:\n" +
                indent(a, 4) + "\n" +
                "  items:\n" +
                "    - name: A1\n" +
                "      value: 1\n" +
                "    - name: A2\n" +
                "      value: 2\n" +
                "    - name: A3\n" +
                "      value: 3\n" +
                "c: 1";

        String result = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "c: 1";

        Node processed = process(Stream.of(a, b, x, y), "Y");
        Node expected = YAML_MAPPER.readValue(result, Node.class);
        assertEquals(BlueIdCalculator.calculateBlueId(processed), BlueIdCalculator.calculateBlueId(expected));
    }

    Node process(Stream<String> stream, String nodeName) {
        Map<String, Node> nodes = stream.map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        Merger merger = new Merger(mergingProcessor, nodeProvider);

        BlueIdPreprocessor blueIdPreprocessor = new BlueIdPreprocessor(nodeProvider, merger);
        Node node = nodeProvider.fetchByBlueId(calculateBlueId(nodes.get(nodeName))).get(0);
        Node result = blueIdPreprocessor.process(node);
        return result;
    }
}
