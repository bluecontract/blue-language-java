package blue.lang;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NodeDeserializerTest {

    @Test
    public void testBasics() throws Exception {
        String doc = "name: name\n" +
                "description: description\n" +
                "type: type\n" +
                "value: value\n" +
                "ref: ref\n" +
                "blueId: blueId\n" +
                "x: x\n" +
                "y:\n" +
                "  y1: y1\n" +
                "  y2:\n" +
                "    value: y2";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals("name", node.getName());
        assertEquals("description", node.getDescription());
        assertEquals("type", node.getType().getName());
        assertEquals("value", node.getValue());
        assertEquals("ref", node.getRef());
        assertEquals("blueId", node.getBlueId());
        assertEquals("x", node.getProperties().get("x").getValue());

        Node y = node.getProperties().get("y");
        assertEquals("y1", y.getProperties().get("y1").getValue());
        assertEquals("y2", y.getProperties().get("y2").getValue());
    }

    @Test
    public void testNumbers() throws Exception {
        String doc = "int: 132452345234524739582739458723948572934875\n" +
                "dec: 132452345234524739582739458723948572934875.132452345234524739582739458723948572934875";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals(new BigInteger("132452345234524739582739458723948572934875"), node.getProperties().get("int").getValue());
        assertEquals(new BigDecimal("132452345234524739582739458723948572934875.132452345234524739582739458723948572934875"),
                node.getProperties().get("dec").getValue());
    }

    @Test
    public void testType() throws Exception {
        String doc = "a:\n" +
                "  type: Integer\n" +
                "b:\n" +
                "  type:\n" +
                "    name: Integer\n" +
                "c:\n" +
                "  type: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                "d:\n" +
                "  type:\n" +
                "    blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals("Integer", node.getProperties().get("a").getType().getName());
        assertEquals("Integer", node.getProperties().get("b").getType().getName());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("c").getType().getBlueId());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("d").getType().getBlueId());
    }

    @Test
    public void testBlueId() throws Exception {
        String doc = "name: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                "description: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                "x: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                "y:\n" +
                "  value: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getName());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getDescription());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("x").getBlueId());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("y").getValue());
    }

    @Test
    public void testItems() throws Exception {
        String doc = "name: Abc\n" +
                "props1:\n" +
                "  items:\n" +
                "    - name: A\n" +
                "    - name: B\n" +
                "props2:\n" +
                "  - name: A\n" +
                "  - name: B";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals(2, node.getProperties().get("props1").getItems().size());
        assertEquals(2, node.getProperties().get("props2").getItems().size());
    }

    @Test
    public void testText() throws Exception {
        String doc = "abc";
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        assertEquals("abc", node.getValue());
    }

    @Test
    public void testList() throws Exception {
        String doc = "- A\n" +
                "- B";
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        assertEquals(2, node.getItems().size());
    }

    @Test
    public void testItemsAsBlueId() throws Exception {
        String doc = "name: Abc\n" +
                "items: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getItems().get(0).getBlueId());
    }

    @Test
    public void testItemsAsBlueIdThrowIfNotBlueId() throws Exception {
        String doc = "name: Abc\n" +
                "items: illegal";
        assertThrows(IllegalArgumentException.class, () -> YAML_MAPPER.readValue(doc, Node.class));
    }

}