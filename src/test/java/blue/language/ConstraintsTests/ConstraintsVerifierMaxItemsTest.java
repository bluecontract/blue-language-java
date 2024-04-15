package blue.language.ConstraintsTests;

import blue.language.Merger;
import blue.language.MergingProcessor;
import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.utils.BasicNodesProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.TestUtils.indent;
import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConstraintsVerifierMaxItemsTest {

    private Node node;
    private Constraints constraints;
    private BasicNodesProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        constraints = new Constraints();
        node = new Node()
                .items(Arrays.asList(new Node().value(1), new Node().value(2)))
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
    public void testMaxItemsPositive() throws Exception {
        constraints.maxItems(2);
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMaxItemsNegative() throws Exception {
        constraints.maxItems(1);
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaxItemsInheritance() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  maxItems: 3\n";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    maxItems: 3\n"+
                "constraints:\n" +
                "  maxItems: 4\n";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      maxItems: 3\n" +
                "  constraints:\n" +
                "    maxItems: 4\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0));
        assertEquals(3, node.getItems().size());

    }

    @Test
    public void testMaxItemsInheritanceStrongestConditionShouldBeUsed() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  maxItems: 3\n";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    maxItems: 3\n" +
                "constraints:\n" +
                "  maxItems: 4\n";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      maxItems: 3\n" +
                "  constraints:\n" +
                "    maxItems: 4\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3\n" +
                "  - value: 4";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0)));

    }

    @Test
    public void testMaxItemsSubInheritancePositive1() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  maxItems: 3\n";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  maxItems: 4\n";

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 4) + "\n" +
                "constraints:\n" +
                "  maxItems: 5\n";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));
        assertEquals(3, node.getItems().size());

    }

    @Test
    public void testMaxItemsSubInheritancePositive2() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  maxItems: 3\n";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  maxItems: 4\n";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    maxItems: 2\n";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));
        assertEquals(2, node.getItems().size());

    }


    @Test
    public void testMaxItemsSubInheritanceNegative() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  maxItems: 3\n";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  maxItems: 4\n";

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 4) + "\n" +
                "constraints:\n" +
                "  maxItems:\n" +
                "    value: 2\n";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0)));

    }
}

