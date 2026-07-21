package blue.language.utils;

import blue.language.model.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodePathAccessorTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private Node rootNode;

    @BeforeEach
    void setUp() throws Exception {
        String yaml = "name: Root\n" +
                      "type:\n" +
                      "  name: RootType\n" +
                      "  type:\n" +
                      "    name: MetaType\n" +
                      "a:\n" +
                      "  - name: A1\n" +
                      "    type:\n" +
                      "      name: TypeA\n" +
                      "  - name: A2\n" +
                      "    value: 42\n" +
                      "b:\n" +
                      "  name: B\n" +
                      "  type:\n" +
                      "    name: TypeB\n" +
                      "  c:\n" +
                      "    name: C\n" +
                      "    value: ValueC";

        rootNode = YAML_MAPPER.readValue(yaml, Node.class);
    }

    @Test
    void testRootLevelAccess() {
        assertEquals("Root", rootNode.get("/name"));
        assertTrue(rootNode.get("/type") instanceof Node);
        assertEquals("RootType", ((Node) rootNode.get("/type")).getName());
    }

    @Test
    void testNestedAccess() {
        assertEquals("B", rootNode.get("/b/name"));
        assertEquals("ValueC", rootNode.get("/b/c/value"));
    }

    @Test
    void testListAccess() {
        assertTrue(rootNode.get("/a/0") instanceof Node);
        assertEquals("A1", rootNode.get("/a/0/name"));
        assertEquals(BigInteger.valueOf(42), rootNode.get("/a/1/value"));
    }

    @Test
    void testTypeAccess() {
        assertEquals("TypeA", rootNode.get("/a/0/type/name"));
        assertEquals("MetaType", rootNode.get("/type/type/name"));
    }

    @Test
    void testBlueIdAccess() {
        assertNotNull(rootNode.get("/blueId"));
        assertNotNull(rootNode.get("/a/0/blueId"));
    }

    @Test
    void testInvalidPath() {
        assertThrows(IllegalArgumentException.class, () -> rootNode.get("/nonexistent"));
        assertThrows(IllegalArgumentException.class, () -> rootNode.get("/a/5"));
        assertThrows(IllegalArgumentException.class, () -> rootNode.get("invalid"));
    }

    @Test
    void listIndexesRemainAsciiAndUnicodeDigitsRemainPropertyNames() {
        Node node = new Node().properties("\u0660", new Node().value("property"));

        assertEquals("property", NodePathAccessor.get(node, "/\u0660"));
        assertThrows(IllegalArgumentException.class,
                () -> NodePathAccessor.get(rootNode, "/a/\u0660"));
    }

    @Test
    void testValuePrecedence() {
        Node nodeWithValue = new Node().name("Test").value("TestValue");
        Node nodeWithoutValue = new Node().name("Test");

        assertEquals("TestValue", NodePathAccessor.get(nodeWithValue, "/"));
        assertEquals("Test", NodePathAccessor.get(nodeWithValue, "/name"));

        assertTrue(NodePathAccessor.get(nodeWithoutValue, "/") instanceof Node);
        assertEquals("Test", NodePathAccessor.get(nodeWithoutValue, "/name"));
    }

    @Test
    void testJsonPointerEscaping() throws Exception {
        Node node = YAML_MAPPER.readValue(
                "\"a/b\": slash\n" +
                "\"a~b\": tilde\n" +
                "nested:\n" +
                "  \"x/y\": value", Node.class);

        assertEquals("slash", node.get("/a~1b/value"));
        assertEquals("tilde", node.get("/a~0b/value"));
        assertEquals("value", node.get("/nested/x~1y/value"));
    }

    @Test
    void nodePathAccessorReadsContracts() throws Exception {
        Node node = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  audit:\n" +
                "    enabled: true", Node.class);

        assertEquals(Boolean.TRUE, node.get("/contracts/audit/enabled/value"));
        assertSame(node.getContracts(), NodePathAccessor.getNode(node, "/contracts"));
    }

    @Test
    void nodePathEditorWritesContracts() {
        Node node = new Node();

        NodePathEditor.put(node, "/contracts/audit/enabled", new Node().value(true));

        assertNotNull(node.getContracts());
        assertEquals(Boolean.TRUE, node.get("/contracts/audit/enabled/value"));
        assertFalse(node.getProperties() != null && node.getProperties().containsKey("contracts"));
    }

    @Test
    void nodePathSelectorFindsContracts() throws Exception {
        Node node = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  audit:\n" +
                "    enabled: true\n" +
                "other:\n" +
                "  enabled: true", Node.class);

        List<String> selected = NodePathSelector.select(node,
                Arrays.asList("/contracts/*/enabled"),
                candidate -> Boolean.TRUE.equals(candidate.getValue()));

        assertEquals(Arrays.asList("/contracts/audit/enabled"), selected);
    }

    @Test
    void jsonPointerContractsRoundTrip() throws Exception {
        Node node = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  \"a/b\":\n" +
                "    \"c~d\": value", Node.class);

        assertEquals("value", node.get("/contracts/a~1b/c~0d/value"));
    }
}
