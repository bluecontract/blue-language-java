package blue.language.utils;

import blue.language.model.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

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
                      "value: RootValue\n" +
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
        assertEquals("RootValue", rootNode.get("/value"));
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
    void testValuePrecedence() {
        Node nodeWithValue = new Node().name("Test").value("TestValue");
        Node nodeWithoutValue = new Node().name("Test");

        assertEquals("TestValue", NodePathAccessor.get(nodeWithValue, "/"));
        assertEquals("Test", NodePathAccessor.get(nodeWithValue, "/name"));

        assertTrue(NodePathAccessor.get(nodeWithoutValue, "/") instanceof Node);
        assertEquals("Test", NodePathAccessor.get(nodeWithoutValue, "/name"));
    }
}