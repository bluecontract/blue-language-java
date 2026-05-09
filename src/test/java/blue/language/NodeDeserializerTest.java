package blue.language;

import blue.language.model.Schema;
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
                     "x: x\n" +
                     "y:\n" +
                     "  y1: y1\n" +
                     "  y2:\n" +
                     "    value: y2";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals("name", node.getName());
        assertEquals("description", node.getDescription());
        assertEquals("type", node.getType().getValue());
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
    public void testValuePayloadWithMetadata() throws Exception {
        String doc = "name: name\n" +
                     "description: description\n" +
                     "type: Text\n" +
                     "value: value";

        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals("name", node.getName());
        assertEquals("description", node.getDescription());
        assertEquals("Text", node.getType().getValue());
        assertEquals("value", node.getValue());
    }

    @Test
    public void testReferenceOnlyBlueId() throws Exception {
        Node node = YAML_MAPPER.readValue("blueId: abc", Node.class);

        assertTrue(node.isReferenceOnly());
        assertEquals("abc", node.getBlueId());
    }

    @Test
    public void testBlueIdWithSiblingFieldsIsRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "blueId: abc\n" +
                "name: Invalid", Node.class));
    }

    @Test
    public void testPayloadKindExclusivity() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "value: abc\n" +
                "child: value", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "items:\n" +
                "  - abc\n" +
                "child: value", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "value: abc\n" +
                "items:\n" +
                "  - def", Node.class));
    }

    @Test
    public void testInternalPropertiesFieldIsRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "properties:\n" +
                "  x: y", Node.class));
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
        assertEquals(new BigInteger("132452345234524739582739458723948572934875"), node.getProperties().get("int2").getValue());
        assertEquals(new BigInteger("132452345234524739582739458723948572934875"), node.getProperties().get("int3").getValue());
        assertEquals(new BigDecimal("132452345234524739582739458723948572934875.132452345234524739582739458723948572934875"), node.getProperties().get("dec1").getValue());
        assertEquals(new BigDecimal("1.3245234523452473E+41"), node.getProperties().get("dec2").getValue());
    }

    @Test
    public void testTypedDoubleCanonicalizesNumericFormsToBinary64() throws Exception {
        String doc = "fromInteger:\n" +
                     "  type:\n" +
                     "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                     "  value: 1\n" +
                     "fromDecimal:\n" +
                     "  type:\n" +
                     "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                     "  value: 1.0\n" +
                     "fromString:\n" +
                     "  type:\n" +
                     "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                     "  value: \"1\"";

        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals(new BigDecimal("1.0"), node.getProperties().get("fromInteger").getValue());
        assertEquals(new BigDecimal("1.0"), node.getProperties().get("fromDecimal").getValue());
        assertEquals(new BigDecimal("1.0"), node.getProperties().get("fromString").getValue());
    }

    @Test
    public void testTypedDoubleRejectsNonFiniteStrings() throws Exception {
        String doc = "x:\n" +
                     "  type:\n" +
                     "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                     "  value: NaN";

        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertThrows(IllegalArgumentException.class, () -> node.getProperties().get("x").getValue());
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
    public void testSchema() throws Exception {
        String doc = "name: name\n" +
                     "schema:\n" +
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
                     "  minFields: 1\n" +
                     "  maxFields: 3\n" +
                     "  enum:\n" +
                     "    - blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "    - name: name2\n" +
                     "      description: description2";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        Schema schema = node.getSchema();
        assertTrue(schema.getRequiredValue());

        assertEquals(false, schema.getAllowMultipleValue());
        assertEquals((Integer) 5, schema.getMinLengthValue());
        assertEquals((Integer) 10, schema.getMaxLengthValue());

        // patters is a list of strings
        assertEquals("^[a-z]+$", schema.getPatternValue().get(0));
        assertEquals(new BigDecimal("1.01"), schema.getMinimumValue());
        assertEquals(new BigDecimal("100.01"), schema.getMaximumValue());
        assertEquals(new BigDecimal("0.01"), schema.getExclusiveMinimumValue());
        assertEquals(new BigDecimal("101.01"), schema.getExclusiveMaximumValue());
        assertEquals(new BigDecimal("2.01"), schema.getMultipleOfValue());
        assertEquals((Integer) 1, schema.getMinItemsValue());
        assertEquals((Integer) 5, schema.getMaxItemsValue());
        assertEquals(true, schema.getUniqueItemsValue());
        assertEquals((Integer) 1, schema.getMinFieldsValue());
        assertEquals((Integer) 3, schema.getMaxFieldsValue());
        assertEquals(2, schema.getEnum().size());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", schema.getEnum().get(0).getBlueId());
        assertEquals("name2", schema.getEnum().get(1).getName());
        assertEquals("description2", schema.getEnum().get(1).getDescription());
    }

    @Test
    public void testLegacySchemaOptionsMigratesToEnum() throws Exception {
        String doc = "name: name\n" +
                     "schema:\n" +
                     "  options:\n" +
                     "    - value: red\n" +
                     "    - value: blue";

        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertNotNull(node.getSchema().getEnum());
        assertEquals(2, node.getSchema().getEnum().size());
        assertEquals("red", node.getSchema().getEnum().get(0).getValue());
        assertSame(node.getSchema().getEnum(), node.getSchema().getOptions());
    }

    @Test
    public void testLegacyConstraintsMigratesToSchema() throws Exception {
        String doc = "name: name\n" +
                     "constraints:\n" +
                     "  minLength: 5";

        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertNotNull(node.getSchema());
        assertEquals((Integer) 5, node.getSchema().getMinLengthValue());
        assertNull(node.getProperties());
    }

    @Test
    public void testSchemaAndConstraintsConflictIsRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "schema:\n" +
                "  minLength: 5\n" +
                "constraints:\n" +
                "  maxLength: 10", Node.class));
    }

}
