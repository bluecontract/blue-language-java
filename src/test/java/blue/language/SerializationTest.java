package blue.language;

import blue.language.model.Node;
import blue.language.utils.NodeToObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class SerializationTest {
    
    @Test
    public void testSimpleNode() throws Exception {
        String yaml = "name: A";

        Node node = YAML_MAPPER.readValue(yaml, Node.class);

        assertEquals("A", node.getName());
        assertNull(node.getType());

        Object officialResult = NodeToObject.get(node, NodeToObject.Strategy.OFFICIAL);
        assertTrue(officialResult instanceof Map);
        Map<String, Object> officialMap = (Map<String, Object>) officialResult;
        assertEquals("A", officialMap.get("name"));

        Object simpleResult = NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
        assertTrue(simpleResult instanceof Map);
        Map<String, Object> simpleMap = (Map<String, Object>) simpleResult;
        assertEquals("A", simpleMap.get("name"));
    }

    @Test
    public void testNodeWithSimpleType() throws Exception {
        String yaml =
                "name: B\n" +
                "type:\n" +
                "  name: A";

        Node node = YAML_MAPPER.readValue(yaml, Node.class);

        assertEquals("B", node.getName());
        assertNotNull(node.getType());
        assertEquals("A", node.getType().getName());

        Object officialResult = NodeToObject.get(node, NodeToObject.Strategy.OFFICIAL);
        assertTrue(officialResult instanceof Map);
        Map<String, Object> officialMap = (Map<String, Object>) officialResult;
        assertEquals("B", officialMap.get("name"));
        assertTrue(officialMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) officialMap.get("type")).get("name"));

        Object simpleResult = NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
        assertTrue(simpleResult instanceof Map);
        Map<String, Object> simpleMap = (Map<String, Object>) simpleResult;
        assertEquals("B", simpleMap.get("name"));
        assertTrue(simpleMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) simpleMap.get("type")).get("name"));
    }

    @Test
    public void testNodeWithNestedType() throws Exception {
        String yaml =
                "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A";

        Node node = YAML_MAPPER.readValue(yaml, Node.class);

        assertEquals("C", node.getName());
        assertNotNull(node.getType());
        assertEquals("B", node.getType().getName());
        assertNotNull(node.getType().getType());
        assertEquals("A", node.getType().getType().getName());

        Object officialResult = NodeToObject.get(node, NodeToObject.Strategy.OFFICIAL);
        assertTrue(officialResult instanceof Map);
        Map<String, Object> officialMap = (Map<String, Object>) officialResult;
        assertEquals("C", officialMap.get("name"));
        assertTrue(officialMap.get("type") instanceof Map);
        Map<String, Object> officialTypeMap = (Map<String, Object>) officialMap.get("type");
        assertEquals("B", officialTypeMap.get("name"));
        assertTrue(officialTypeMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) officialTypeMap.get("type")).get("name"));

        Object simpleResult = NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
        assertTrue(simpleResult instanceof Map);
        Map<String, Object> simpleMap = (Map<String, Object>) simpleResult;
        assertEquals("C", simpleMap.get("name"));
        assertTrue(simpleMap.get("type") instanceof Map);
        Map<String, Object> simpleTypeMap = (Map<String, Object>) simpleMap.get("type");
        assertEquals("B", simpleTypeMap.get("name"));
        assertTrue(simpleTypeMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) simpleTypeMap.get("type")).get("name"));
    }

    @Test
    public void testNodeWithNestedProperty() throws Exception {
        String yaml =
                "name: X\n" +
                "a:\n" +
                "  type:\n" +
                "    name: A";

        Node node = YAML_MAPPER.readValue(yaml, Node.class);

        assertEquals("X", node.getName());
        assertNotNull(node.getProperties());
        assertTrue(node.getProperties().containsKey("a"));
        Node aNode = node.getProperties().get("a");
        assertNotNull(aNode.getType());
        assertEquals("A", aNode.getType().getName());

        Object officialResult = NodeToObject.get(node, NodeToObject.Strategy.OFFICIAL);
        assertTrue(officialResult instanceof Map);
        Map<String, Object> officialMap = (Map<String, Object>) officialResult;
        assertEquals("X", officialMap.get("name"));
        assertTrue(officialMap.get("a") instanceof Map);
        Map<String, Object> officialAMap = (Map<String, Object>) officialMap.get("a");
        assertTrue(officialAMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) officialAMap.get("type")).get("name"));

        Object simpleResult = NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
        assertInstanceOf(Map.class, simpleResult);
        Map<String, Object> simpleMap = (Map<String, Object>) simpleResult;
        assertEquals("X", simpleMap.get("name"));
        assertTrue(simpleMap.get("a") instanceof Map);
        Map<String, Object> simpleAMap = (Map<String, Object>) simpleMap.get("a");
        assertTrue(simpleAMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) simpleAMap.get("type")).get("name"));
    }

    @Test
    public void testInlineNumber() throws Exception {
        String yaml =
                "name: InlineNumber\n" +
                "value: 42";

        Node node = YAML_MAPPER.readValue(yaml, Node.class);

        assertEquals("InlineNumber", node.getName());
        assertEquals(BigInteger.valueOf(42), node.getValue());

        Object officialResult = NodeToObject.get(node, NodeToObject.Strategy.OFFICIAL);
        assertTrue(officialResult instanceof Map);
        Map<String, Object> officialMap = (Map<String, Object>) officialResult;
        assertEquals("InlineNumber", officialMap.get("name"));
        assertEquals(BigInteger.valueOf(42), officialMap.get("value"));
        assertTrue(((Map<String, Object>) officialMap.get("type")).containsKey("blueId"));
        assertEquals(INTEGER_TYPE_BLUE_ID, ((Map<String, Object>) officialMap.get("type")).get("blueId"));

        Object simpleResult = NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
        assertTrue(simpleResult instanceof BigInteger);
        assertEquals(BigInteger.valueOf(42), simpleResult);
    }

    @Test
    public void testTextAsInteger() throws Exception {
        String yaml =
                "name: TextAsInteger\n" +
                "type: Integer\n" +
                "value: '123'";

        Node node = YAML_MAPPER.readValue(yaml, Node.class);

        assertEquals("TextAsInteger", node.getName());
        assertEquals(BigInteger.valueOf(123), node.getValue());
        assertNotNull(node.getType());
        assertEquals(INTEGER_TYPE_BLUE_ID, node.getType().getBlueId());

        Object officialResult = NodeToObject.get(node, NodeToObject.Strategy.OFFICIAL);
        assertTrue(officialResult instanceof Map);
        Map<String, Object> officialMap = (Map<String, Object>) officialResult;
        assertEquals("TextAsInteger", officialMap.get("name"));
        assertEquals(BigInteger.valueOf(123), officialMap.get("value"));
        assertEquals(INTEGER_TYPE_BLUE_ID, ((Map<String, Object>) officialMap.get("type")).get("blueId"));

        Object simpleResult = NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
        assertTrue(simpleResult instanceof BigInteger);
        assertEquals(BigInteger.valueOf(123), simpleResult);
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

        Node node = YAML_MAPPER.readValue(yaml, Node.class);

        assertEquals("MixedList", node.getName());
        assertEquals(LIST_TYPE_BLUE_ID, node.getType().getBlueId());
        assertEquals(4, node.getItems().size());
        assertEquals("text", node.getItems().get(0).getValue());
        assertEquals(BigInteger.valueOf(42), node.getItems().get(1).getValue());
        assertEquals(new BigDecimal("3.14"), node.getItems().get(2).getValue());
        assertEquals(true, node.getItems().get(3).getValue());

        Object officialResult = NodeToObject.get(node, NodeToObject.Strategy.OFFICIAL);
        assertTrue(officialResult instanceof Map);
        Map<String, Object> officialMap = (Map<String, Object>) officialResult;
        assertEquals("MixedList", officialMap.get("name"));
        assertEquals(LIST_TYPE_BLUE_ID, ((Map<String, Object>) officialMap.get("type")).get("blueId"));
        List<Map<String, Object>> officialItems = (List<Map<String, Object>>) officialMap.get("items");
        assertEquals(4, officialItems.size());
        assertEquals("text", officialItems.get(0).get("value"));
        assertEquals(BigInteger.valueOf(42), officialItems.get(1).get("value"));
        assertEquals(new BigDecimal("3.14"), officialItems.get(2).get("value"));
        assertEquals(true, officialItems.get(3).get("value"));

        Object simpleResult = NodeToObject.get(node, NodeToObject.Strategy.SIMPLE);
        assertTrue(simpleResult instanceof List);
        List<Object> simpleList = (List<Object>) simpleResult;
        assertEquals(4, simpleList.size());
        assertEquals("text", simpleList.get(0));
        assertEquals(BigInteger.valueOf(42), simpleList.get(1));
        assertEquals(new BigDecimal("3.14"), simpleList.get(2));
        assertEquals(true, simpleList.get(3));
    }

}
