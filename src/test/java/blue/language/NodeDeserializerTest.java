package blue.language;

import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class NodeDeserializerTest {

    @Test
    public void testBasics() throws Exception {
        String doc = "name: name\n" +
                     "description: description\n" +
                     "type: type\n" +
                     "value: value\n" +
                     "blueId: blueId\n" +
                     "x: x\n" +
                     "y:\n" +
                     "  y1: y1\n" +
                     "  y2:\n" +
                     "    value: y2";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals("name", node.getName());
        assertEquals("description", node.getDescription());
        assertEquals("type", node.getType().getValue());
        assertEquals("value", node.getValue());
        assertEquals("blueId", node.getBlueId());
        assertEquals("x", node.getProperties().get("x").getValue());

        Node y = node.getProperties().get("y");
        Node y1 = y.getProperties().get("y1");
        assertEquals("y1", y1.getValue());
        assertTrue(y1.isInlineValue());

        Node y2 = y.getProperties().get("y2");
        assertEquals("y2", y2.getValue());
        assertFalse(y2.isInlineValue());

    }

    @Test
    public void testNumbers() throws Exception {
        String doc = "int1: 9007199254740991\n" +
                     "int2: 132452345234524739582739458723948572934875\n" +
                     "int3:\n" +
                     "  type:\n" +
                     "    blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                     "  value: \"132452345234524739582739458723948572934875\"\n" +
                     "dec1: 132452345234524739582739458723948572934875.132452345234524739582739458723948572934875\n" +
                     "dec2:\n" +
                     "  type:\n" +
                     "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                     "  value: \"132452345234524739582739458723948572934875.132452345234524739582739458723948572934875\"\n";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals(new BigInteger("9007199254740991"), node.getProperties().get("int1").getValue());
        assertEquals(new BigInteger("9007199254740991"), node.getProperties().get("int2").getValue());
        assertEquals(new BigInteger("132452345234524739582739458723948572934875"), node.getProperties().get("int3").getValue());
        assertEquals(new BigDecimal("1.3245234523452473E+41"), node.getProperties().get("dec1").getValue());
        assertEquals(new BigDecimal("1.3245234523452473E+41"), node.getProperties().get("dec2").getValue());
    }

    @Test
    public void testType() throws Exception {
        String doc = "a:\n" +
                     "  type:\n" +
                     "    name: Integer\n" +
                     "b:\n" +
                     "  type:\n" +
                     "    name: Integer\n" +
                     "c:\n" +
                     "  type:\n" +
                     "    blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
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
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("x").getValue());
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
    public void testConstraints() throws Exception {
        String doc = "name: name\n" +
                     "constraints:\n" +
                     "  required: true\n" +
                     "  allowMultiple: false\n" +
                     "  minLength: 5\n" +
                     "  maxLength: 10\n" +
                     "  pattern: \"^[a-z]+$\"\n" +
                     "  minimum: 1.01\n" +
                     "  maximum: 100.01\n" +
                     "  exclusiveMinimum: 0.01\n" +
                     "  exclusiveMaximum: 101.01\n" +
                     "  multipleOf: 2.01\n" +
                     "  minItems: 1\n" +
                     "  maxItems: 5\n" +
                     "  uniqueItems: true\n" +
                     "  options:\n" +
                     "    - blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "    - name: name2\n" +
                     "      description: description2";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        Constraints constraints = node.getConstraints();
        assertTrue(constraints.getRequiredValue());

        assertEquals(false, constraints.getAllowMultipleValue());
        assertEquals((Integer) 5, constraints.getMinLengthValue());
        assertEquals((Integer) 10, constraints.getMaxLengthValue());

        // patters is a list of strings
        assertEquals("^[a-z]+$", constraints.getPatternValue().get(0));
        assertEquals(new BigDecimal("1.01"), constraints.getMinimumValue());
        assertEquals(new BigDecimal("100.01"), constraints.getMaximumValue());
        assertEquals(new BigDecimal("0.01"), constraints.getExclusiveMinimumValue());
        assertEquals(new BigDecimal("101.01"), constraints.getExclusiveMaximumValue());
        assertEquals(new BigDecimal("2.01"), constraints.getMultipleOfValue());
        assertEquals((Integer) 1, constraints.getMinItemsValue());
        assertEquals((Integer) 5, constraints.getMaxItemsValue());
        assertEquals(true, constraints.getUniqueItemsValue());
        assertEquals(2, constraints.getOptions().size());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", constraints.getOptions().get(0).getBlueId());
        assertEquals("name2", constraints.getOptions().get(1).getName());
        assertEquals("description2", constraints.getOptions().get(1).getDescription());
    }

}