package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.TestUtils;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.limits.Limits;
import blue.language.utils.limits.PathLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NodeExtenderTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private Map<String, Node> nodes;
    private NodeProvider nodeProvider;
    private NodeExtender nodeExtender;

    @BeforeEach
    public void setup() throws Exception {
        String a = "name: A\n" +
                   "x: 1\n" +
                   "y:\n" +
                   "  z: 1";

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: blueId-A\n" +
                   "x: 2";

        String c = "name: C\n" +
                   "type:\n" +
                   "  blueId: blueId-B\n" +
                   "x: 3";

        String x = "name: X\n" +
                   "a:\n" +
                   "  type:\n" +
                   "    blueId: blueId-A\n" +
                   "b:\n" +
                   "  type:\n" +
                   "    blueId: blueId-B\n" +
                   "c:\n" +
                   "  type:\n" +
                   "    blueId: blueId-C\n" +
                   "d:\n" +
                   "  - blueId: blueId-C\n" +
                   "  - blueId: blueId-A";

        String y = "name: Y\n" +
                   "forA:\n" +
                   "  blueId: blueId-A\n" +
                   "forX:\n" +
                   "  blueId: blueId-X";

        nodes = Stream.of(a, b, c, x, y)
                .map(doc -> {
                    try {
                        return YAML_MAPPER.readValue(doc, Node.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toMap(Node::getName, node -> node));

        nodeProvider = TestUtils.fakeNameBasedNodeProvider(nodes.values());
        nodeExtender = new NodeExtender(nodeProvider);
    }

    @Test
    public void testExtendSingleProperty() {
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/forA")
                .build();
        nodeExtender.extend(node, limits);

        assertEquals("A", node.get("/forA/name"));
        assertEquals(BigInteger.valueOf(1), node.get("/forA/x"));
        assertEquals(BigInteger.valueOf(1), node.get("/forA/y/z"));
        assertThrows(IllegalArgumentException.class, () -> node.get("/forX/a"));
    }

    @Test
    public void testExtendNestedProperty() {
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/forX/a")
                .build();
        nodeExtender.extend(node, limits);

        assertEquals("X", node.get("/forX/name"));
        assertEquals("A", node.get("/forX/a/type/name"));
        assertEquals(BigInteger.valueOf(1), node.get("/forX/a/type/x"));
    }

    @Test
    public void testExtendListItem() {
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/forX/d/0")
                .build();
        nodeExtender.extend(node, limits);

        assertEquals("X", node.get("/forX/name"));
        assertEquals("C", node.get("/forX/d/0/name"));
        assertEquals("B", node.get("/forX/d/0/type/name"));
        assertEquals(BigInteger.valueOf(2), node.get("/forX/d/0/type/x"));
    }

    @Test
    public void testExtendWithMultiplePaths() {
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/forA")
                .addPath("/forX/b")
                .build();
        nodeExtender.extend(node, limits);

        assertEquals("A", node.get("/forA/name"));
        assertEquals(BigInteger.valueOf(1), node.get("/forA/x"));
        assertEquals("X", node.get("/forX/name"));
        assertThrows(IllegalArgumentException.class, () -> node.get("/forX/a/prop"));
    }

    @Test
    public void testExtendList() throws Exception {

        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\nvalue: 1";
        String b = "name: B\nvalue: 2";
        String c = "name: C\nvalue: 3";

        Node nodeA = YAML_MAPPER.readValue(a, Node.class);
        Node nodeB = YAML_MAPPER.readValue(b, Node.class);
        Node nodeC = YAML_MAPPER.readValue(c, Node.class);

        nodeProvider.addSingleNodes(nodeA, nodeB, nodeC);

        String listBlueId = BlueIdCalculator.calculateBlueId(Arrays.asList(nodeA, nodeB));
        nodeProvider.addListAndItsItems(Arrays.asList(nodeA, nodeB));

        String listNode = "name: ListNode\n" +
                          "items:\n" +
                          "  - blueId: " + listBlueId + "\n" +
                          "  - blueId: " + nodeProvider.getBlueIdByName("C");

        Node node = YAML_MAPPER.readValue(listNode, Node.class);
        nodeProvider.addSingleNodes(node);

        NodeExtender nodeExtender = new NodeExtender(nodeProvider);

        Limits limits = new PathLimits.Builder()
                .addPath("/*")
                .build();
        nodeExtender.extend(node, limits);

        assertEquals("ListNode", node.getName());
        assertEquals(3, node.getItems().size());

        assertEquals("A", node.get("/0/name"));
        assertEquals(1, node.getAsInteger("/0/value"));

        assertEquals("B", node.get("/1/name"));
        assertEquals(2, node.getAsInteger("/1/value"));

        assertEquals("C", node.get("/2/name"));
        assertEquals(3, node.getAsInteger("/2/value"));
    }

    @Test
    public void testExtendListDirectly() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\nvalue: 1";
        String b = "name: B\nvalue: 2";
        String c = "name: C\nvalue: 3";

        Node nodeA = YAML_MAPPER.readValue(a, Node.class);
        Node nodeB = YAML_MAPPER.readValue(b, Node.class);
        Node nodeC = YAML_MAPPER.readValue(c, Node.class);

        nodeProvider.addSingleNodes(nodeA, nodeB, nodeC);

        String listABBlueId = BlueIdCalculator.calculateBlueId(Arrays.asList(nodeA, nodeB));
        nodeProvider.addList(Arrays.asList(nodeA, nodeB));

        String ab = "blueId: " + listABBlueId;
        Node nodeAB = YAML_MAPPER.readValue(ab, Node.class);
        nodeProvider.addList(Arrays.asList(nodeAB, nodeC));

        String listABCBlueId = BlueIdCalculator.calculateBlueId(Arrays.asList(nodeAB, nodeC));
        String abc = "blueId: " + listABCBlueId;
        Node nodeABC = YAML_MAPPER.readValue(abc, Node.class);

        NodeExtender nodeExtender = new NodeExtender(nodeProvider);

        Limits limits = new PathLimits.Builder()
                .addPath("/*")
                .build();
        nodeExtender.extend(nodeABC, limits);

        assertEquals(3, nodeABC.getItems().size());

        assertEquals("A", nodeABC.get("/0/name"));
        assertEquals(1, nodeABC.getAsInteger("/0/value"));

        assertEquals("B", nodeABC.get("/1/name"));
        assertEquals(2, nodeABC.getAsInteger("/1/value"));

        assertEquals("C", nodeABC.get("/2/name"));
        assertEquals(3, nodeABC.getAsInteger("/2/value"));
    }

}