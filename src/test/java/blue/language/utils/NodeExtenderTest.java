package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.TestUtils;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;
import blue.language.utils.limits.PathLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
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
    public void testExtendNonExistentPath() {
        Node node = nodes.get("Y").clone();
        Limits limits = new PathLimits.Builder()
                .addPath("/nonexistent")
                .build();
        nodeExtender.extend(node, limits);

        assertEquals("Y", node.get("/name"));
        assertThrows(IllegalArgumentException.class, () -> node.get("/nonexistent"));
    }

}