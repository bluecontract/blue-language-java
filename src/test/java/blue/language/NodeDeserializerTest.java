package blue.language;

import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.utils.Properties;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
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
    public void contractsAreReservedIdentityContent() throws Exception {
        Node valueWithContracts = YAML_MAPPER.readValue(
                "value: abc\n" +
                "contracts:\n" +
                "  audit:\n" +
                "    value: enabled", Node.class);
        assertEquals("abc", valueWithContracts.getValue());
        assertNotNull(valueWithContracts.getContracts());
        assertFalse(valueWithContracts.getProperties() != null
                && valueWithContracts.getProperties().containsKey("contracts"));
        assertEquals("enabled", valueWithContracts.getAsText("/contracts/audit/value"));

        Node itemsWithContracts = YAML_MAPPER.readValue(
                "items:\n" +
                "  - abc\n" +
                "contracts:\n" +
                "  audit:\n" +
                "    value: enabled", Node.class);
        assertEquals(1, itemsWithContracts.getItems().size());
        assertEquals("enabled", itemsWithContracts.getAsText("/contracts/audit/value"));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("contracts: false", Node.class));

        String baseId = BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue("value: abc", Node.class));
        String contractsId = BlueIdCalculator.calculateBlueId(valueWithContracts);
        assertNotEquals(baseId, contractsId);
    }

    @Test
    public void testListControlMetadata() throws Exception {
        String doc = "type: List\n" +
                     "mergePolicy: append-only\n" +
                     "items:\n" +
                     "  - $previous:\n" +
                     "      blueId: prevHash\n" +
                     "  - $pos: 2\n" +
                     "    value: C\n" +
                     "  - $empty: true";

        Node node = YAML_MAPPER.readValue(doc, Node.class);

        assertEquals("append-only", node.getMergePolicy());
        assertEquals("prevHash", node.getItems().get(0).getPreviousBlueId());
        assertEquals((Integer) 2, node.getItems().get(1).getPosition());
        assertEquals("C", node.getItems().get(1).getValue());
        assertEquals(true, node.getItems().get(2).getProperties().get("$empty").getValue());
    }

    @Test
    public void testPreviousControlWithSiblingsIsRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$previous:\n" +
                "  blueId: prevHash\n" +
                "value: C", Node.class));
    }

    @Test
    public void testInvalidListControlMetadataIsRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "mergePolicy: replace-all", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$previous: prevHash", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$previous:\n" +
                "  blueId: prevHash\n" +
                "  extra: value", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$pos: -1\n" +
                "value: C", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$pos: 1.5\n" +
                "value: C", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$pos: \"1\"\n" +
                "value: C", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$pos: 2147483648\n" +
                "value: C", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$pos: 0", Node.class));

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "$previous:\n" +
                "  blueId: 123", Node.class));
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
                     "int2: \"132452345234524739582739458723948572934875\"\n" +
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
        assertEquals("132452345234524739582739458723948572934875", node.getProperties().get("int2").getValue());
        assertEquals(new BigInteger("132452345234524739582739458723948572934875"), node.getProperties().get("int3").getValue());
        assertEquals(new BigDecimal("132452345234524739582739458723948572934875.132452345234524739582739458723948572934875"), node.getProperties().get("dec1").getValue());
        assertEquals(new BigDecimal("1.3245234523452473E+41"), node.getProperties().get("dec2").getValue());
    }

    @Test
    public void testUnquotedLargeIntegerIsRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "x: 132452345234524739582739458723948572934875", Node.class));
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
    public void explicitBooleanTextValuesAreParsedStrictly() {
        Node trueNode = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + BOOLEAN_TYPE_BLUE_ID + "\n" +
                "value: \"true\"", Node.class);
        assertEquals(true, trueNode.getValue());

        Node falseNode = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + BOOLEAN_TYPE_BLUE_ID + "\n" +
                "value: \"false\"", Node.class);
        assertEquals(false, falseNode.getValue());

        Node invalid = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + BOOLEAN_TYPE_BLUE_ID + "\n" +
                "value: \"anything\"", Node.class);
        assertThrows(IllegalArgumentException.class, invalid::getValue);
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
                     "  minLength: 5\n" +
                     "  maxLength: 10\n" +
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
                     "    - red\n" +
                     "    - value: blue";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        Schema schema = node.getSchema();
        assertTrue(schema.getRequiredValue());

        assertEquals(BigInteger.valueOf(5), schema.getMinLengthExact());
        assertEquals(BigInteger.valueOf(10), schema.getMaxLengthExact());

        assertEquals(new BigDecimal("1.01"), schema.getMinimumValue());
        assertEquals(new BigDecimal("100.01"), schema.getMaximumValue());
        assertEquals(new BigDecimal("0.01"), schema.getExclusiveMinimumValue());
        assertEquals(new BigDecimal("101.01"), schema.getExclusiveMaximumValue());
        assertEquals(new BigDecimal("2.01"), schema.getMultipleOfValue());
        assertEquals(BigInteger.ONE, schema.getMinItemsExact());
        assertEquals(BigInteger.valueOf(5), schema.getMaxItemsExact());
        assertEquals(true, schema.getUniqueItemsValue());
        assertEquals(BigInteger.ONE, schema.getMinFieldsExact());
        assertEquals(BigInteger.valueOf(3), schema.getMaxFieldsExact());
        assertEquals(2, schema.getEnum().size());
        assertEquals("red", schema.getEnum().get(0).getValue());
        assertEquals("blue", schema.getEnum().get(1).getValue());
    }

    @Test
    public void testSchemaPatternIsRejected() {
        String doc = "name: name\n" +
                     "schema:\n" +
                     "  pattern: \"^[a-z]+$\"";

        RuntimeException exception = assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(doc, Node.class));
        assertTrue(exception.getMessage().contains("schema.pattern"));
    }

    @Test
    public void testInvalidSchemaOptionsKeyIsRejected() {
        String doc = "name: name\n" +
                     "schema:\n" +
                     "  options:\n" +
                     "    - value: red\n" +
                     "    - value: blue";

        RuntimeException exception = assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(doc, Node.class));
        assertTrue(exception.getMessage().contains("schema.options"));
    }

    @Test
    public void testInvalidConstraintsKeyIsRejected() {
        String doc = "name: name\n" +
                     "constraints:\n" +
                     "  minLength: 5";

        RuntimeException exception = assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(doc, Node.class));
        assertTrue(exception.getMessage().contains("\"constraints\" is not part of the Blue Language 1.0"));
    }

    @Test
    public void testSchemaAllowMultipleIsRejected() {
        String doc = "name: name\n" +
                     "schema:\n" +
                     "  allowMultiple: true";

        RuntimeException exception = assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(doc, Node.class));
        assertTrue(exception.getMessage().contains("schema.allowMultiple"));
    }

    @Test
    public void testSchemaAndConstraintsConflictIsRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "schema:\n" +
                "  minLength: 5\n" +
                "constraints:\n" +
                "  maxLength: 10", Node.class));
    }

    @Test
    public void rootNullIsRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("null", Node.class));
    }

    @Test
    public void rootScalarListObjectAndReferenceAreAccepted() {
        assertEquals("abc", YAML_MAPPER.readValue("abc", Node.class).getValue());
        assertNotNull(YAML_MAPPER.readValue("[]", Node.class).getItems());
        assertNotNull(YAML_MAPPER.readValue("{}", Node.class));
        assertTrue(YAML_MAPPER.readValue("blueId: abc", Node.class).isReferenceOnly());
    }

    @Test
    public void rejectsWrongReservedFieldTypes() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("name: true", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("description: 123", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("blueId: 123", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("mergePolicy: true", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema: []", Node.class));
    }

    @Test
    public void rejectsObjectValuedItems() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "items:\n" +
                "  blueId: abc", Node.class));
    }

    @Test
    public void nestedBlueAndRootBlueListAreRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "child:\n" +
                "  blue: x", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(
                "blue:\n" +
                "  - x", Node.class));
    }

    @Test
    public void schemaKeywordValueShapesAreStrict() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  required: \"true\"", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  required:\n    value: true", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  uniqueItems:\n    value: true", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  minItems: \"1\"", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  minItems: 9007199254740992", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  minItems:\n    type: Integer\n    value: \"5\"", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  minLength:\n    type: Integer\n    value: \"5\"", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum: red", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - null", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - {}", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - $empty: true", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - blueId: abc", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - blueId: this#0", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - value: 1\n      contracts: {}", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - name: one\n      value: 1", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - value: 1\n      schema:\n        minimum: 0", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  minimum: \"9007199254740992\"", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  minimum: 9007199254740992", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  minimum:\n    type: Integer\n    value: \"1\"\n    contracts: {}", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema:\n  minimum:\n    type: Integer\n    value: \"1\"\n    name: one", Node.class));

        Node node = YAML_MAPPER.readValue(
                "schema:\n" +
                "  minimum:\n" +
                "    type:\n" +
                "      blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                "    value: \"9007199254740992\"", Node.class);
        assertEquals(new BigInteger("9007199254740992"), node.getSchema().getMinimum().getValue());

        Node safeLargeCount = YAML_MAPPER.readValue("schema:\n  minItems: 9007199254740991", Node.class);
        assertEquals(new BigInteger("9007199254740991"), safeLargeCount.getSchema().getMinItems().getValue());

        Node enumNode = YAML_MAPPER.readValue(
                "schema:\n" +
                "  enum:\n" +
                "    - type:\n" +
                "        blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                "      value: \"9007199254740992\"", Node.class);
        assertEquals(new BigInteger("9007199254740992"), enumNode.getSchema().getEnum().get(0).getValue());
    }

    @Test
    public void schemaEnumRejectsContractsOnExplicitScalar() {
        assertThrows(RuntimeException.class,
                () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - value: 1\n      contracts: {}", Node.class));
    }

    @Test
    public void schemaEnumRejectsNameDescriptionOnExplicitScalar() {
        assertThrows(RuntimeException.class,
                () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - name: one\n      value: 1", Node.class));
        assertThrows(RuntimeException.class,
                () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - description: one\n      value: 1", Node.class));
    }

    @Test
    public void schemaEnumRejectsSchemaOnExplicitScalar() {
        assertThrows(RuntimeException.class,
                () -> YAML_MAPPER.readValue("schema:\n  enum:\n    - value: 1\n      schema:\n        minimum: 0", Node.class));
    }

    @Test
    public void schemaMinimumRejectsContractsOnExplicitNumericNode() {
        assertThrows(RuntimeException.class,
                () -> YAML_MAPPER.readValue("schema:\n  minimum:\n    type: Integer\n    value: \"1\"\n    contracts: {}", Node.class));
    }

    @Test
    public void schemaMinItemsExplicitNodeRejected() {
        assertThrows(RuntimeException.class,
                () -> YAML_MAPPER.readValue("schema:\n  minItems:\n    type: Integer\n    value: \"5\"", Node.class));
    }

    @Test
    public void schemaMinLengthExplicitNodeRejected() {
        assertThrows(RuntimeException.class,
                () -> YAML_MAPPER.readValue("schema:\n  minLength:\n    type: Integer\n    value: \"5\"", Node.class));
    }

    @Test
    public void schemaMinimumTypedLargeIntegerAliasIsAcceptedAndPreprocessed() {
        Node parsed = YAML_MAPPER.readValue(
                "schema:\n" +
                "  minimum:\n" +
                "    type: Integer\n" +
                "    value: \"9007199254740992\"", Node.class);

        assertEquals("Integer", parsed.getSchema().getMinimum().getType().getValue());

        Node preprocessed = new Blue().preprocess(parsed);
        assertEquals(INTEGER_TYPE_BLUE_ID, preprocessed.getSchema().getMinimum().getType().getBlueId());
        assertEquals(new BigInteger("9007199254740992"), preprocessed.getSchema().getMinimum().getValue());
    }

    @Test
    public void schemaCountKeywordsExposeExactSafeLargeIntegerValues() {
        Node parsed = YAML_MAPPER.readValue(
                "schema:\n" +
                "  minItems: 2147483648\n" +
                "  maxItems: 9007199254740991\n" +
                "  minLength: 2147483648\n" +
                "  maxLength: 9007199254740991\n" +
                "  minFields: 2147483648\n" +
                "  maxFields: 9007199254740991", Node.class);

        assertEquals(new BigInteger("2147483648"), parsed.getSchema().getMinItemsExact());
        assertEquals(new BigInteger("9007199254740991"), parsed.getSchema().getMaxItemsExact());
        assertEquals(new BigInteger("2147483648"), parsed.getSchema().getMinLengthExact());
        assertEquals(new BigInteger("9007199254740991"), parsed.getSchema().getMaxLengthExact());
        assertEquals(new BigInteger("2147483648"), parsed.getSchema().getMinFieldsExact());
        assertEquals(new BigInteger("9007199254740991"), parsed.getSchema().getMaxFieldsExact());
        assertTrue(parsed.getSchema().toString().contains("9007199254740991"));
    }

    @Test
    public void schemaVerifierHandlesLargeButSafeCountDeterministically() {
        Node parsed = YAML_MAPPER.readValue(
                "items: []\n" +
                "schema:\n" +
                "  minItems: 2147483648", Node.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Blue().resolve(parsed));
        assertTrue(error.getMessage().contains("minimum required items"));
    }

    @Test
    public void reservedNullFieldsAreRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("name: null", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("description: null", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("mergePolicy: null", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("value: null", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("items: null", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("type: null", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("schema: null", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("contracts: null", Node.class));
    }

    @Test
    public void nullAndEmptyStringParsingPreservesBlueSemantics() {
        Node objectNull = YAML_MAPPER.readValue("x: null", Node.class);
        assertTrue(objectNull.getProperties().containsKey("x"));
        assertNull(objectNull.getProperties().get("x").getValue());

        Node listNull = YAML_MAPPER.readValue("items:\n  - null", Node.class);
        assertEquals(1, listNull.getItems().size());
        assertNull(listNull.getItems().get(0).getValue());

        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("null", Node.class));
        assertEquals("", YAML_MAPPER.readValue("value: \"\"", Node.class).getValue());
    }

    @Test
    public void duplicateKeysAreRejected() {
        assertThrows(RuntimeException.class, () -> JSON_MAPPER.readValue("{\"x\":1,\"x\":2}", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("x: 1\nx: 2", Node.class));
    }

    @Test
    public void yamlCustomTagsAreRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("value: !custom tagged", Node.class));
    }

    @Test
    public void yamlAnchorsAreRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("x: &shared abc\ny: *shared", Node.class));
    }

    @Test
    public void yamlOnlyTagsAreRejected() {
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("value: !!binary SGVsbG8=", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("value: !!set\n  ? a\n  ? b", Node.class));
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue("value: !!omap\n  - a: 1", Node.class));
    }

    @Test
    public void yamlTimestampAndEmptyStringStayInJsonDataModel() {
        Node timestamp = YAML_MAPPER.readValue("value: 2026-05-24", Node.class);
        assertEquals("2026-05-24", timestamp.getValue());

        Node empty = YAML_MAPPER.readValue("value: \"\"", Node.class);
        assertEquals("", empty.getValue());
    }

}
