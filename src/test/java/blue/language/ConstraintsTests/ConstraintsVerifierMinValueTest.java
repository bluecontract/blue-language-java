package blue.language.ConstraintsTests;

import blue.language.Merger;
import blue.language.MergingProcessor;
import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.utils.BasicNodesProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

public class ConstraintsVerifierMinValueTest {

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
    public void testMinValuePositive() throws Exception {
        constraints.minimum(BigDecimal.valueOf(3));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinValueNegative() throws Exception {
        constraints.minimum(BigDecimal.valueOf(4));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinValueInheritance() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minimum: 3";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    minimum: 3\n" +
                "constraints:\n" +
                "  minimum: 4";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      minimum: 3\n" +
                "  constraints:\n" +
                "    minimum: 4\n" +
                "value: 4";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0));
        assertEquals(BigInteger.valueOf(4), node.getValue());

    }

    @Test
    public void testMinValueInheritanceStrongestConditionShouldBeUsed() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minimum: 3";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    minimum: 3\n" +
                "constraints:\n" +
                "  minimum: 4";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      minimum: 3\n" +
                "  constraints:\n" +
                "    minimum: 4\n" +
                "value: 3";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0)));

    }

    @Test
    public void testMinValueSubInheritancePositive1() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minimum: 3";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  minimum: 4";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    minimum: 5";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  value: 5";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));
        assertEquals(BigInteger.valueOf(5), node.getProperties().get("a").getValue());

    }

    @Test
    public void testMinValueSubInheritancePositive2() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minimum: 3";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  minimum: 4";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    minimum: 2";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  value: 4";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));
        assertEquals(BigInteger.valueOf(4), node.getProperties().get("a").getValue());

    }


    @Test
    public void testMinValueSubInheritanceNegative() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minimum: 3";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  minimum: 4";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    minimum:\n" +
                "      value: 2";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  value: 2";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0)));

    }
}

