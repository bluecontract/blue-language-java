package blue.language.ConstraintsTests;

import blue.language.Merger;
import blue.language.MergingProcessor;
import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.utils.BasicNodesProvider;
import blue.language.utils.BlueIdCalculator;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConstraintsVerifierOptionsTest {

    private Node node;
    private Constraints constraints;
    private BasicNodesProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        constraints = new Constraints();
        node = new Node()
                .value(3)
                .constraints(constraints);
        mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ConstraintsPropagator(),
                        new ConstraintsVerifier()
                )
        );
        merger = new Merger(mergingProcessor, e -> null);
    }

    @Test
    public void testOptionsPositive() throws Exception {
        constraints.options(Stream.of(1, 2, 3).map(e -> new Node().value(e)).collect(Collectors.toList()));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testOptionsNegative() throws Exception {
        constraints.options(Stream.of(1, 2, 4).map(e -> new Node().value(e)).collect(Collectors.toList()));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testOptionsInheritance() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  options: \n" +
                "    - value: 2\n" +
                "    - value: 3\n";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    options:\n" +
                "      - value: 2\n" +
                "      - value: 3\n" +
                "constraints:\n" +
                "  options: \n" +
                "    - value: 3\n";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      options:\n" +
                "        - value: 2\n" +
                "        - value: 3\n" +
                "  constraints:\n" +
                "    options:\n" +
                "      - value: 3\n" +
                "value: 3";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0));
        assertEquals(BigInteger.valueOf(3), node.getValue());

    }

    @Test
    public void testOptionsSubInheritancePositive1() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  options: [1, 2, 3]";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  options: [2, 3, 4]";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    options: [3, 4, 5]";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  value: 3";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));
        assertEquals(BigInteger.valueOf(3), node.getProperties().get("a").getValue());

    }

    @Test
    public void testOptionsSubInheritancePositive2() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  options: \n" +
                "    - name: aName\n" +
                "      aProp: a\n" +
                "    - name: bName\n" +
                "      bProp: b\n" +
                "    - name: cName\n" +
                "      cProp: c";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  options: \n" +
                "    - name: aName\n" +
                "      aProp: a\n" +
                "    - name: bName\n" +
                "      bProp: b";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    options: \n" +
                "      - name: aName\n" +
                "        aProp: a\n";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  name: aName\n" +
                "  aProp: a";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));

        assertEquals("a", node.getProperties().get("a").getProperties().get("aProp").getValue());

    }

    @Test
    public void testOptionsSubInheritanceNegative2() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  options: \n" +
                "    - name: aName\n" +
                "      aProp: a\n" +
                "    - name: bName\n" +
                "      bProp: b\n" +
                "    - name: cName\n" +
                "      cProp: c";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  options: \n" +
                "    - name: aName\n" +
                "      aProp: a\n" +
                "    - name: bName\n" +
                "      bProp: b";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    options: \n" +
                "      - name: aName\n" +
                "        aProp: a\n";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  name: cName\n" +
                "  cProp: c";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0)));

    }

    @Test
    public void testOptionsSubInheritancePositive3() throws Exception {

        // BlueId: 2R2j1t8fX4EzDuEUgGGDSLf8j3xH7QkXM1JZNxDguhp1
        String a = "name: A\n" +
                "aProp: a\n";

        // BlueId: F3D3mkgTtQTdfdDW8uc4qmyrKHR93jya8mZc5xf86ea2
        String b = "name: B\n" +
                "bProp: b";


        // BlueId: 8MRoLUcSXHs87KLn48kDWxdzSEsARSEKUW9QqFi2czni
        String x = "name: X\n" +
                "xProp: x\n";

        String y = "name: Y\n" +
                "prop:\n" +
                "  constraints:\n" +
                "    options:\n" +
                "      - blueId: 8MRoLUcSXHs87KLn48kDWxdzSEsARSEKUW9QqFi2czni \n" +
                "  name: X\n" +
                "  xProp: x";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, nodeProvider);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));

        assertEquals("x", node.getProperties().get("prop").getProperties().get("xProp").getValue());

    }

    @Test
    public void testOptionsSubInheritanceNegative3() throws Exception {

        // BlueId: 2R2j1t8fX4EzDuEUgGGDSLf8j3xH7QkXM1JZNxDguhp1
        String a = "name: A\n" +
                "aProp: a\n";

        // BlueId: F3D3mkgTtQTdfdDW8uc4qmyrKHR93jya8mZc5xf86ea2
        String b = "name: B\n" +
                "bProp: b";


        // BlueId: 8MRoLUcSXHs87KLn48kDWxdzSEsARSEKUW9QqFi2czni
        String x = "name: X\n" +
                "xProp: x\n";

        String y = "name: Y\n" +
                "prop:\n" +
                "  constraints:\n" +
                "    options:\n" +
                "      - blueId: 2R2j1t8fX4EzDuEUgGGDSLf8j3xH7QkXM1JZNxDguhp1 \n" +
                "  name: X\n" +
                "  xProp: x";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, nodeProvider);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0)));
    }

    @Test
    public void testOptionsSubInheritanceNegative() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  options: [1, 2, 3]";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  options: [2, 3, 4]";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    options:\n" +
                "      - 2\n" +
                "      - 4\n" +
                "      - 5";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  value: 3";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0)));

    }
}
