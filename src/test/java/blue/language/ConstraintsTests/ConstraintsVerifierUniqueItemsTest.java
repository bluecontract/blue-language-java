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

public class ConstraintsVerifierUniqueItemsTest {

    private Node node;
    private Constraints constraints;
    private BasicNodesProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        constraints = new Constraints().allowMultiple(true);
        node = new Node()
                .items(Arrays.asList(new Node().value(1), new Node().value(1)))
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
    public void testUniqueItemsPositive() throws Exception {
        constraints.uniqueItems(false);
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testUniqueItemsNegative() throws Exception {
        constraints.uniqueItems(true);
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testUniqueItemsInheritance() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  uniqueItems: true\n" +
                "  allowMultiple: true";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    uniqueItems: true\n" +
                "    allowMultiple: true\n" +
                "constraints:\n" +
                "  allowMultiple: true";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      uniqueItems: true\n" +
                "      allowMultiple: true\n" +
                "  constraints:\n" +
                "    allowMultiple: true\n" +
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
    public void testUniqueItemsInheritanceStrongestConditionShouldBeUsed() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  uniqueItems: true\n" +
                "  allowMultiple: true";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    uniqueItems: true\n" +
                "    allowMultiple: true\n" +
                "constraints:\n" +
                "  uniqueItems: false\n" +
                "  allowMultiple: true";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      uniqueItems: true\n" +
                "      allowMultiple: true\n" +
                "  constraints:\n" +
                "    uniqueItems: false\n" +
                "    allowMultiple: true\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 1\n" +
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
    public void testUniqueItemsSubInheritancePositive1() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  uniqueItems: true\n" +
                "  allowMultiple: true";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  uniqueItems: false\n" +
                "  allowMultiple: true";

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 4) + "\n" +
                "constraints:\n" +
                "  uniqueItems: false\n" +
                "  allowMultiple: true";

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
    public void testUniqueItemsSubInheritancePositive2() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  uniqueItems: true\n" +
                "  allowMultiple: true";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  uniqueItems: true\n" +
                "  allowMultiple: true";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    uniqueItems: false\n" +
                "    allowMultiple: true";

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
    public void testUniqueItemsSubInheritanceNegative() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  uniqueItems: true\n" +
                "  allowMultiple: true";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  uniqueItems: false\n" +
                "  allowMultiple: true";

        String x = "name: X\n" +
                "type:\n" +
                indent(b, 4) + "\n" +
                "constraints:\n" +
                "  uniqueItems:\n" +
                "    value: false\n" +
                "  allowMultiple: true";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2\n" +
                "  - value: 2";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0)));

    }
}
