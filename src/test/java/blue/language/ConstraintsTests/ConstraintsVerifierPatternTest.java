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

public class ConstraintsVerifierPatternTest {

    private Node node;
    private Constraints constraints;
    private BasicNodesProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        constraints = new Constraints();
        node = new Node()
                .value("xyz")
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
    void testPatternPositive() throws Exception {
        constraints.pattern(Arrays.asList("^x.*", ".*y.*", ".*z$"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    void testSinglePatternPositive() throws Exception {
        constraints.pattern("^x.*");
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test void testPatternNegative() throws Exception {
        constraints.pattern(Arrays.asList("^x.*", ".*Y.*", ".*z$"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
        // nothing should be thrown
    }

    @Test
    public void testPatternInheritance() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  pattern: ^A.*\n";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    pattern: ^A.*\n" +
                "constraints:\n" +
                "  pattern: .*b.*\n";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      pattern: ^A.*\n" +
                "  constraints:\n" +
                "    pattern: .*b.*\n" +
                "value: Abcd";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0));
        assertEquals("Abcd", node.getValue());

    }

    @Test
    public void testPatternInheritanceNegative() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  pattern: ^A.*\n";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A\n" +
                "  constraints:\n" +
                "    pattern: ^A.*\n" +
                "constraints:\n" +
                "  pattern: .*B.*\n";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A\n" +
                "    constraints:\n" +
                "      pattern: ^A.*\n" +
                "  constraints:\n" +
                "    pattern: .*B.*\n" +
                "value: Abcd";

        Map<String, Node> nodes = Stream.of(a, b, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("C"))).get(0)));

    }

    @Test
    public void testPatternSubInheritancePositive() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  pattern: ^A.*";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  pattern: .*b.*\n";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    pattern: .*c$\n";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  value: Abc";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0));
        assertEquals("Abc", node.getProperties().get("a").getValue());

    }

    @Test
    public void testPatternSubInheritanceNegative() throws Exception {

        String a = "name: A\n" +
                "constraints:\n" +
                "  pattern: ^A.*";

        String b = "name: B\n" +
                "type:\n" +
                indent(a, 2) + "\n" +
                "constraints:\n" +
                "  pattern: .*b.*\n";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                indent(b, 4) + "\n" +
                "  constraints:\n" +
                "    pattern: .*c$\n";

        String y = "name: Y\n" +
                "type:\n" +
                indent(x, 2) + "\n" +
                "a:\n" +
                "  value: Ab";

        Map<String, Node> nodes = Stream.of(a, b, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        merger = new Merger(mergingProcessor, e -> null);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))).get(0)));

    }
}
