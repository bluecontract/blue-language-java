package blue.language.utils;

import blue.language.NodeProvider;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NodeExtenderTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private Map<String, Node> nodes;
    private NodeProvider nodeProvider;
    private NodeExtender nodeExtender;

    @BeforeEach
    public void setup() throws Exception {
        BasicNodeProvider exactProvider = new BasicNodeProvider();
        nodes = new LinkedHashMap<>();

        Node a = YAML_MAPPER.readValue(
                "name: A\n" +
                "x: 1\n" +
                "y:\n" +
                "  z: 1", Node.class);
        exactProvider.addSingleNodes(a);
        nodes.put("A", a);

        Node b = YAML_MAPPER.readValue(
                "name: B\n" +
                "type:\n" +
                "  blueId: " + exactProvider.getBlueIdByName("A") + "\n" +
                "x: 2", Node.class);
        exactProvider.addSingleNodes(b);
        nodes.put("B", b);

        Node c = YAML_MAPPER.readValue(
                "name: C\n" +
                "type:\n" +
                "  blueId: " + exactProvider.getBlueIdByName("B") + "\n" +
                "x: 3", Node.class);
        exactProvider.addSingleNodes(c);
        nodes.put("C", c);

        Node x = YAML_MAPPER.readValue(
                "name: X\n" +
                "a:\n" +
                "  type:\n" +
                "    blueId: " + exactProvider.getBlueIdByName("A") + "\n" +
                "b:\n" +
                "  type:\n" +
                "    blueId: " + exactProvider.getBlueIdByName("B") + "\n" +
                "c:\n" +
                "  type:\n" +
                "    blueId: " + exactProvider.getBlueIdByName("C") + "\n" +
                "d:\n" +
                "  - blueId: " + exactProvider.getBlueIdByName("C") + "\n" +
                "  - blueId: " + exactProvider.getBlueIdByName("A"), Node.class);
        exactProvider.addSingleNodes(x);
        nodes.put("X", x);

        Node y = YAML_MAPPER.readValue(
                "name: Y\n" +
                "forA:\n" +
                "  blueId: " + exactProvider.getBlueIdByName("A") + "\n" +
                "forX:\n" +
                "  blueId: " + exactProvider.getBlueIdByName("X"), Node.class);
        exactProvider.addSingleNodes(y);
        nodes.put("Y", y);

        nodeProvider = exactProvider;
        nodeExtender = new NodeExtender(nodeProvider);
    }

    @Test
    public void shouldExtendSingleProperty() {
        // given
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/forA")
                .build();
        // when
        nodeExtender.extend(node, limits);

        // then
        assertEquals("A", node.get("/forA/name"));
        assertEquals(BigInteger.valueOf(1), node.get("/forA/x"));
        assertEquals(BigInteger.valueOf(1), node.get("/forA/y/z"));
        assertThrows(IllegalArgumentException.class, () -> node.get("/forX/a"));
    }

    @Test
    public void shouldExtendNestedProperty() {
        // given
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/forX/a")
                .build();
        // when
        nodeExtender.extend(node, limits);

        // then
        assertEquals("X", node.get("/forX/name"));
        assertEquals("A", node.get("/forX/a/type/name"));
        assertEquals(BigInteger.valueOf(1), node.get("/forX/a/type/x"));
    }

    @Test
    public void shouldExtendListItem() {
        // given
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/forX/d/0")
                .build();
        // when
        nodeExtender.extend(node, limits);

        // then
        assertEquals("X", node.get("/forX/name"));
        assertEquals("C", node.get("/forX/d/0/name"));
        assertEquals("B", node.get("/forX/d/0/type/name"));
        assertEquals(BigInteger.valueOf(2), node.get("/forX/d/0/type/x"));
    }

    @Test
    public void shouldExtendWithMultiplePaths() {
        // given
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/forA")
                .addPath("/forX/b")
                .build();
        // when
        nodeExtender.extend(node, limits);

        // then
        assertEquals("A", node.get("/forA/name"));
        assertEquals(BigInteger.valueOf(1), node.get("/forA/x"));
        assertEquals("X", node.get("/forX/name"));
        assertThrows(IllegalArgumentException.class, () -> node.get("/forX/a/prop"));
    }

    @Test
    public void shouldExtendList() throws Exception {

        // given
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
        // when
        nodeExtender.extend(node, limits);

        // then
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
    public void shouldExtendListDirectly() throws Exception {
        // given
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
        // when
        nodeExtender.extend(nodeABC, limits);

        // then
        assertEquals(3, nodeABC.getItems().size());

        assertEquals("A", nodeABC.get("/0/name"));
        assertEquals(1, nodeABC.getAsInteger("/0/value"));

        assertEquals("B", nodeABC.get("/1/name"));
        assertEquals(2, nodeABC.getAsInteger("/1/value"));

        assertEquals("C", nodeABC.get("/2/name"));
        assertEquals(3, nodeABC.getAsInteger("/2/value"));
    }

}
