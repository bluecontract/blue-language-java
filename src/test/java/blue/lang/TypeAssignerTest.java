package blue.lang;

import blue.lang.model.limits.Limits;
import blue.lang.processor.*;
import blue.lang.utils.BasicNodesProvider;
import blue.lang.utils.DirectoryBasedNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.lang.utils.BlueIdCalculator.calculateBlueId;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class TypeAssignerTest {

    @Test
    public void testPropertySubtype() throws Exception {
        Node a = new Node().name("A");
        Node b = new Node().name("B").type(new Node().blueId(calculateBlueId(a)));
        Node c = new Node().name("C").type(new Node().blueId(calculateBlueId(b)));

        Node x = new Node()
                .name("X")
                .properties(
                        "a", new Node().type(new Node().blueId(calculateBlueId(b)))
                );
        Node y = new Node()
                .name("Y")
                .type(new Node().blueId(calculateBlueId(x)))
                .properties(
                        "a", new Node().type(new Node().blueId(calculateBlueId(c)))
                );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new NamePropagator(),
                        new TypeAssigner(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );

        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(y)), Limits.NO_LIMITS);

        assertEquals("C", node.getProperties().get("a").getType().getName());
    }

    @Test
    public void testEmptyTypeIsInherited() throws Exception {
        Node a = new Node().name("A");
        Node b = new Node().name("B").type(new Node().blueId(calculateBlueId(a)));
        Node c = new Node().name("C").type(new Node().blueId(calculateBlueId(b)));

        Node x = new Node()
                .name("X")
                .properties(
                        "a", new Node().type(new Node().blueId(calculateBlueId(b)))
                );
        Node y = new Node()
                .name("Y")
                .type(new Node().blueId(calculateBlueId(x)))
                .properties(
                        "a", new Node()
                );

        List<Node> nodes = Arrays.asList(a, b, c, x, y);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new NamePropagator(),
                        new TypeAssigner(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );

        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(y)), Limits.NO_LIMITS);

        assertEquals("B", node.getProperties().get("a").getType().getName());
    }


    @Test
    public void testPropertySubtypeOnYamlDocsWithNoBlueIds() throws Exception {

        String a = "name: A";

        String b = "name: B\n" +
                "type:\n" +
                "  name: A";

        String c = "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A";

        String x = "name: X\n" +
                "a:\n" +
                "  type:\n" +
                "    name: A";

        String y = "name: Y\n" +
                "type:\n" +
                "  name: X\n" +
                "  a:\n" +
                "    type:\n" +
                "      name: A\n" +
                "a:\n" +
                "  type:\n" +
                "    name: B\n" +
                "    type:\n" +
                "      name: A";

        Map<String, Node> nodes = Stream.of(a, b, c, x, y)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new NamePropagator(),
                        new TypeAssigner(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node node = merger.resolve(nodeProvider.fetchByBlueId(calculateBlueId(nodes.get("Y"))), Limits.NO_LIMITS);

        assertEquals("B", node.getProperties().get("a").getType().getName());
    }

    @Test
    public void testDifferentSubtypeVariations2() throws Exception {
        
        DirectoryBasedNodeProvider dirNodeProvider = TestUtils.samplesDirectoryNodeProvider();

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new NamePropagator(),
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );

        Merger merger = new Merger(mergingProcessor, dirNodeProvider);
        Node source = dirNodeProvider.getNodes().stream().filter(e -> "My Voucher".equals(e.getName())).findAny().get();

        Node node = merger.resolve(source, Limits.NO_LIMITS);

        assertEquals("+1234567890", node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("phone").getValue());

    }

    @Test
    public void testPathLimits() throws Exception {

        DirectoryBasedNodeProvider dirNodeProvider = TestUtils.samplesDirectoryNodeProvider();

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new NamePropagator(),
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );

        Merger merger = new Merger(mergingProcessor, dirNodeProvider);
        Node source = dirNodeProvider.getNodes().stream().filter(e -> "My Voucher".equals(e.getName())).findAny().get();

        Node node = merger.resolve(source, Limits.path("details/customerSupport/phone"));

        assertEquals("+1234567890", node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("phone").getValue());

        assertThrows(NullPointerException.class, () -> {
            node.getProperties().get("details")
                    .getProperties().get("customerSupport")
                    .getProperties().get("email").getValue();
        });

    }

    @Test
    public void testDepthLimit2() throws Exception {

        DirectoryBasedNodeProvider dirNodeProvider = TestUtils.samplesDirectoryNodeProvider();

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new NamePropagator(),
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );

        Merger merger = new Merger(mergingProcessor, dirNodeProvider);
        Node source = dirNodeProvider.getNodes().stream().filter(e -> "My Voucher".equals(e.getName())).findAny().get();

        Node node = merger.resolve(source, Limits.depth(2));

        assertEquals("HvB9broPqR3gU5jkKCUnqoYNzEbZ4WhUj88D3DsEkJ4n", node.getProperties().get("details")
                .getProperties().get("customerSupport").getBlueId());

        assertThrows(NullPointerException.class, () -> {
            node.getProperties().get("details")
                    .getProperties().get("customerSupport")
                    .getProperties().get("email").getValue();
        });

        assertThrows(NullPointerException.class, () -> {
            node.getProperties().get("details")
                    .getProperties().get("customerSupport")
                    .getProperties().get("phone").getValue();
        });

    }

}