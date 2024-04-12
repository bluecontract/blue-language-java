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

public class ConstraintsVerifierMinItemsTest {

    private Node node;
    private Constraints constraints;
    private BasicNodesProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        constraints = new Constraints().allowMultiple(true);
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
    public void testMinItemsPositive() throws Exception {
        constraints.minItems(1);
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinItemsNegative() throws Exception {
        constraints.minItems(3);
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinItemsInheritance() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minItems: 3\n" +
                "  allowMultiple: true\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3\n" +
                "  - value: 4";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    minItems: 3\n" +
                "    allowMultiple: true\n" +
                "  items:\n" +
                "    - value: 1\n" +
                "    - value: 2\n" +
                "    - value: 3\n" +
                "    - value: 4\n" +
                "constraints:\n" +
                "  minItems: 4\n" +
                "  allowMultiple: true";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      minItems: 3\n" +
                "      allowMultiple: true\n" +
                "    items:\n" +
                "      - value: 1\n" +
                "      - value: 2\n" +
                "      - value: 3\n" +
                "      - value: 4\n" +
                "  constraints:\n" +
                "    minItems: 4\n" +
                "    allowMultiple: true\n";


        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0));
        assertEquals(4, node.getItems().size());

    }

    @Test
    public void testMinItemsInheritanceStrongestConditionShouldBeUsed() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minItems: 3\n" +
                "  allowMultiple: true\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    minItems: 3\n" +
                "    allowMultiple: true\n" +
                "  items:\n" +
                "    - value: 1\n" +
                "    - value: 2\n" +
                "    - value: 3\n" +
                "constraints:\n" +
                "  minItems: 4\n" +
                "  allowMultiple: true";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      minItems: 3\n" +
                "      allowMultiple: true\n" +
                "  items:\n" +
                "    - value: 1\n" +
                "    - value: 2\n" +
                "    - value: 3\n" +
                "  constraints:\n" +
                "    minItems: 4\n" +
                "    allowMultiple: true\n";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0)));

    }

    @Test
    public void testMinItemsSubInheritancePositive1() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minItems: 3\n" +
                "  allowMultiple: true\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  minItems: 4\n" +
                "  allowMultiple: true\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3\n" +
                "  - value: 4";

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 4) + "\n" +
                "constraints:\n" +
                "  minItems: 5\n" +
                "  allowMultiple: true\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3\n" +
                "  - value: 4\n" +
                "  - value: 5";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));
        assertEquals(5, node.getItems().size());

    }

    @Test
    public void testMinItemsSubInheritancePositive2() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minItems: 3\n" +
                "  allowMultiple: true\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3\n" +
                "  - value: 4";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  minItems: 4\n" +
                "  allowMultiple: true";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    minItems: 2\n" +
                "    allowMultiple: true";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));
        assertEquals(4, node.getItems().size());

    }


    @Test
    public void testMinItemsSubInheritanceNegative() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  minItems: 3\n" +
                "  allowMultiple: truen\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 3";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  minItems: 4\n" +
                "  allowMultiple: true";

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 4) + "\n" +
                "constraints:\n" +
                "  minItems:\n" +
                "    value: 2\n" +
                "  allowMultiple: true";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0)));

    }
}

