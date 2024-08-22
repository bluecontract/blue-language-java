package blue.language;

import blue.language.model.Node;
import blue.language.utils.NodeToMapListOrValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static blue.language.utils.Properties.*;
import static org.junit.jupiter.api.Assertions.*;

public class SerializationTest {

    @Test
    public void testSimpleNode() throws Exception {
        String yaml = "name: A";

        Node node = new Blue().yamlToNode(yaml);

        assertEquals("A", node.getName());
        assertNull(node.getType());

        Object result = NodeToMapListOrValue.get(node);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("A", resultMap.get("name"));
    }

    @Test
    public void testNodeWithSimpleType() throws Exception {
        String yaml =
                "name: B\n" +
                "type:\n" +
                "  name: A";

        Node node = new Blue().yamlToNode(yaml);

        assertEquals("B", node.getName());
        assertNotNull(node.getType());
        assertEquals("A", node.getType().getName());

        Object result = NodeToMapListOrValue.get(node);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("B", resultMap.get("name"));
        assertTrue(resultMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) resultMap.get("type")).get("name"));
    }

    @Test
    public void testNodeWithNestedType() throws Exception {
        String yaml =
                "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A";

        Node node = new Blue().yamlToNode(yaml);

        assertEquals("C", node.getName());
        assertNotNull(node.getType());
        assertEquals("B", node.getType().getName());
        assertNotNull(node.getType().getType());
        assertEquals("A", node.getType().getType().getName());

        Object result = NodeToMapListOrValue.get(node);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("C", resultMap.get("name"));
        assertTrue(resultMap.get("type") instanceof Map);
        Map<String, Object> typeMap = (Map<String, Object>) resultMap.get("type");
        assertEquals("B", typeMap.get("name"));
        assertTrue(typeMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) typeMap.get("type")).get("name"));
    }

    @Test
    public void testNodeWithNestedProperty() throws Exception {
        String yaml =
                "name: X\n" +
                "a:\n" +
                "  type:\n" +
                "    name: A";

        Node node = new Blue().yamlToNode(yaml);

        assertEquals("X", node.getName());
        assertNotNull(node.getProperties());
        assertTrue(node.getProperties().containsKey("a"));
        Node aNode = node.getProperties().get("a");
        assertNotNull(aNode.getType());
        assertEquals("A", aNode.getType().getName());

        Object result = NodeToMapListOrValue.get(node);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("X", resultMap.get("name"));
        assertTrue(resultMap.get("a") instanceof Map);
        Map<String, Object> aMap = (Map<String, Object>) resultMap.get("a");
        assertTrue(aMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) aMap.get("type")).get("name"));
    }

    @Test
    public void testInlineNumber() throws Exception {
        String yaml =
                "name: InlineNumber\n" +
                "value: 42";

        Node node = new Blue().yamlToNode(yaml);

        assertEquals("InlineNumber", node.getName());
        assertEquals(BigInteger.valueOf(42), node.getValue());

        Object result = NodeToMapListOrValue.get(node);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("InlineNumber", resultMap.get("name"));
        assertEquals(BigInteger.valueOf(42), resultMap.get("value"));
        assertTrue(((Map<String, Object>) resultMap.get("type")).containsKey("blueId"));
        assertEquals(INTEGER_TYPE_BLUE_ID, ((Map<String, Object>) resultMap.get("type")).get("blueId"));
    }

    @Test
    public void testTextAsInteger() throws Exception {
        String yaml =
                "name: TextAsInteger\n" +
                "type: Integer\n" +
                "value: '123'";

        Node node = new Blue().yamlToNode(yaml);

        assertEquals("TextAsInteger", node.getName());
        assertEquals(BigInteger.valueOf(123), node.getValue());
        assertNotNull(node.getType());
        assertEquals(INTEGER_TYPE_BLUE_ID, node.getType().getBlueId());

        Object result = NodeToMapListOrValue.get(node);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("TextAsInteger", resultMap.get("name"));
        assertEquals(BigInteger.valueOf(123), resultMap.get("value"));
        assertEquals(INTEGER_TYPE_BLUE_ID, ((Map<String, Object>) resultMap.get("type")).get("blueId"));
    }

    @Test
    public void testMixedTypeList() throws Exception {
        String yaml =
                "name: MixedList\n" +
                "type: List\n" +
                "items:\n" +
                "  - value: 'text'\n" +
                "  - value: 42\n" +
                "  - value: 3.14\n" +
                "  - value: true";

        Node node = new Blue().yamlToNode(yaml);

        assertEquals("MixedList", node.getName());
        assertEquals(LIST_TYPE_BLUE_ID, node.getType().getBlueId());
        assertEquals(4, node.getItems().size());
        assertEquals("text", node.getItems().get(0).getValue());
        assertEquals(BigInteger.valueOf(42), node.getItems().get(1).getValue());
        assertEquals(new BigDecimal("3.14"), node.getItems().get(2).getValue());
        assertEquals(true, node.getItems().get(3).getValue());

        Object result = NodeToMapListOrValue.get(node);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("MixedList", resultMap.get("name"));
        assertEquals(LIST_TYPE_BLUE_ID, ((Map<String, Object>) resultMap.get("type")).get("blueId"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) resultMap.get("items");
        assertEquals(4, items.size());
        assertEquals("text", items.get(0).get("value"));
        assertEquals(BigInteger.valueOf(42), items.get(1).get("value"));
        assertEquals(new BigDecimal("3.14"), items.get(2).get("value"));
        assertEquals(true, items.get(3).get("value"));
    }
}