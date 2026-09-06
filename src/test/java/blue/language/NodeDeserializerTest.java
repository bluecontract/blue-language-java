package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIdReferenceValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class NodeDeserializerTest {

    @Test
    public void shouldDeserializeBasicNodeFields() throws Exception {
        // given
        String doc = "name: name\n" +
                     "description: description\n" +
                     "type: type\n" +
                     "x: x\n" +
                     "y:\n" +
                     "  y1: y1\n" +
                     "  y2:\n" +
                     "    value: y2";
        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        Node y = node.getProperties().get("y");
        Node y1 = y.getProperties().get("y1");
        Node y2 = y.getProperties().get("y2");

        // then
        assertEquals("name", node.getName());
        assertEquals("description", node.getDescription());
        assertEquals("type", node.getType().getValue());
        assertEquals("x", node.getProperties().get("x").getValue());
        assertEquals("y1", y1.getValue());
        assertTrue(y1.isInlineValue());
        assertEquals("y2", y2.getValue());
        assertFalse(y2.isInlineValue());

    }

    @Test
    public void shouldDeserializeValuePayloadWithMetadata() throws Exception {
        // given
        String doc = "name: name\n" +
                     "description: description\n" +
                     "type: Text\n" +
                     "value: value";

        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        // then
        assertEquals("name", node.getName());
        assertEquals("description", node.getDescription());
        assertEquals("Text", node.getType().getValue());
        assertEquals("value", node.getValue());
    }

    @Test
    public void shouldDeserializeReferenceOnlyBlueId() throws Exception {
        // given
        String document = "blueId: abc";

        // when
        Node node = YAML_MAPPER.readValue(document, Node.class);

        // then
        assertTrue(node.isReferenceOnly());
        assertEquals("abc", node.getBlueId());
    }

    @Test
    public void shouldRejectBlueIdWithSiblingFields() {
        // given
        String document = "blueId: abc\n" +
                "name: Invalid";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldEnforcePayloadKindExclusivity() {
        // given
        String valueWithProperty = "value: abc\n" +
                "child: value";
        String itemsWithProperty = "items:\n" +
                "  - abc\n" +
                "child: value";
        String valueWithItems = "value: abc\n" +
                "items:\n" +
                "  - def";

        // when
        Throwable valueWithPropertyFailure = captureFailure(
                () -> YAML_MAPPER.readValue(valueWithProperty, Node.class));
        Throwable itemsWithPropertyFailure = captureFailure(
                () -> YAML_MAPPER.readValue(itemsWithProperty, Node.class));
        Throwable valueWithItemsFailure = captureFailure(
                () -> YAML_MAPPER.readValue(valueWithItems, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, valueWithPropertyFailure);
        assertInstanceOf(RuntimeException.class, itemsWithPropertyFailure);
        assertInstanceOf(RuntimeException.class, valueWithItemsFailure);
    }

    @Test
    public void shouldDeserializeContractsAsReservedContentForValuePayload() throws Exception {
        // given
        String document = "value: abc\n"
                + "contracts:\n"
                + "  audit:\n"
                + "    value: enabled";

        // when
        Node node = YAML_MAPPER.readValue(document, Node.class);

        // then
        assertEquals("abc", node.getValue());
        assertNotNull(node.getContracts());
        assertFalse(node.getProperties() != null
                && node.getProperties().containsKey("contracts"));
        assertEquals("enabled", node.getAsText("/contracts/audit/value"));
    }

    @Test
    public void shouldDeserializeContractsAsReservedContentForItemsPayload() throws Exception {
        // given
        String document = "items:\n"
                + "  - abc\n"
                + "contracts:\n"
                + "  audit:\n"
                + "    value: enabled";

        // when
        Node node = YAML_MAPPER.readValue(document, Node.class);

        // then
        assertEquals(1, node.getItems().size());
        assertEquals("enabled", node.getAsText("/contracts/audit/value"));
    }

    @Test
    public void shouldRejectNonObjectContractsPayload() {
        // given
        String document = "contracts: false";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldIncludeContractsInCanonicalIdentity() throws Exception {
        // given
        Node withoutContracts = YAML_MAPPER.readValue("value: abc", Node.class);
        Node withContracts = YAML_MAPPER.readValue(
                "value: abc\n"
                        + "contracts:\n"
                        + "  audit:\n"
                        + "    value: enabled",
                Node.class);

        // when
        String baseId = DirectBlueIdCalculator.calculateBlueId(withoutContracts);
        String contractsId = DirectBlueIdCalculator.calculateBlueId(withContracts);

        // then
        assertNotEquals(baseId, contractsId);
    }

    @Test
    public void shouldDeserializeListControlMetadata() throws Exception {
        // given
        String doc = "type: List\n" +
                     "mergePolicy: append-only\n" +
                     "items:\n" +
                     "  - $previous:\n" +
                     "      blueId: prevHash\n" +
                     "  - $pos: 2\n" +
                     "    value: C\n" +
                     "  - $empty: true";

        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        // then
        assertEquals("append-only", node.getMergePolicy());
        assertEquals("prevHash", node.getItems().get(0).getPreviousBlueId());
        assertEquals((Integer) 2, node.getItems().get(1).getPosition());
        assertEquals("C", node.getItems().get(1).getValue());
        assertEquals(true, node.getItems().get(2).getProperties().get("$empty").getValue());
    }

    @Test
    public void shouldRejectPreviousControlWithSiblings() {
        // given
        String document = "$previous:\n" +
                "  blueId: prevHash\n" +
                "value: C";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectInvalidListControlMetadata() {
        // given: controls are recognized only at the top of a list element (§11.3).
        String[] invalidDocuments = {
                "mergePolicy: replace-all",
                "items: [{ $previous: prevHash }]",
                "items: [{ $previous: {blueId: prevHash, extra: value} }]",
                "items: [{ $pos: -1, value: C }]",
                "items: [{ $pos: 1.5, value: C }]",
                "items: [{ $pos: \"1\", value: C }]",
                "items: [{ $pos: 2147483648, value: C }]",
                "items: [{ $pos: 0 }]",
                "items: [{ $previous: {blueId: 123} }]"
        };

        // when
        Throwable[] failures = new Throwable[invalidDocuments.length];
        for (int index = 0; index < invalidDocuments.length; index++) {
            String document = invalidDocuments[index];
            failures[index] = captureFailure(
                    () -> YAML_MAPPER.readValue(document, Node.class));
        }

        // then
        for (Throwable failure : failures) {
            assertInstanceOf(RuntimeException.class, failure);
        }
    }

    @Test
    public void shouldRejectInternalPropertiesField() {
        // given
        String document = "properties:\n" +
                "  x: y";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldDeserializeSupportedNumericForms() throws Exception {
        // given
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
        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        // then
        assertEquals(new BigInteger("9007199254740991"), node.getProperties().get("int1").getValue());
        assertEquals("132452345234524739582739458723948572934875", node.getProperties().get("int2").getValue());
        assertEquals(new BigInteger("132452345234524739582739458723948572934875"), node.getProperties().get("int3").getValue());
        assertEquals(new BigDecimal("132452345234524739582739458723948572934875.132452345234524739582739458723948572934875"), node.getProperties().get("dec1").getValue());
        assertEquals(new BigDecimal("1.3245234523452473E+41"), node.getProperties().get("dec2").getValue());
    }

    @Test
    public void shouldRejectUnquotedLargeInteger() {
        // given
        String document = "x: 132452345234524739582739458723948572934875";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldCanonicalizeTypedDoubleNumericFormsToBinary64() throws Exception {
        // given
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

        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        // then
        assertEquals(new BigDecimal("1.0"), node.getProperties().get("fromInteger").getValue());
        assertEquals(new BigDecimal("1.0"), node.getProperties().get("fromDecimal").getValue());
        assertEquals(new BigDecimal("1.0"), node.getProperties().get("fromString").getValue());
    }

    @Test
    public void shouldRejectNonFiniteStringsForTypedDouble() throws Exception {
        // given
        String doc = "x:\n" +
                     "  type:\n" +
                     "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                     "  value: NaN";

        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        Throwable failure = captureFailure(
                () -> node.getProperties().get("x").getValue());

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldParseExplicitBooleanTextValuesStrictly() {
        // given
        String trueDocument = "type:\n" +
                "  blueId: " + BOOLEAN_TYPE_BLUE_ID + "\n" +
                "value: \"true\"";
        String falseDocument = "type:\n" +
                "  blueId: " + BOOLEAN_TYPE_BLUE_ID + "\n" +
                "value: \"false\"";
        String invalidDocument = "type:\n" +
                "  blueId: " + BOOLEAN_TYPE_BLUE_ID + "\n" +
                "value: \"anything\"";

        // when
        Node trueNode = YAML_MAPPER.readValue(trueDocument, Node.class);
        Node falseNode = YAML_MAPPER.readValue(falseDocument, Node.class);
        Node invalid = YAML_MAPPER.readValue(invalidDocument, Node.class);
        Object trueValue = trueNode.getValue();
        Object falseValue = falseNode.getValue();
        Throwable invalidValueFailure = captureFailure(invalid::getValue);

        // then
        assertEquals(true, trueValue);
        assertEquals(false, falseValue);
        assertInstanceOf(IllegalArgumentException.class, invalidValueFailure);
    }

    @Test
    public void shouldDeserializeTypeMetadata() throws Exception {
        // given
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
        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        // then
        assertEquals("Integer", node.getProperties().get("a").getType().getName());
        assertEquals("Integer", node.getProperties().get("b").getType().getName());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("c").getType().getBlueId());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("d").getType().getBlueId());
    }

    @Test
    public void shouldDeserializeBlueIdMetadata() throws Exception {
        // given
        String doc = "name: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "description: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "x: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "y:\n" +
                     "  value: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";
        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        // then
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getName());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getDescription());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("x").getValue());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("y").getValue());
    }

    @Test
    public void shouldDeserializeItemPayloads() throws Exception {
        // given
        String doc = "name: Abc\n" +
                     "props1:\n" +
                     "  items:\n" +
                     "    - name: A\n" +
                     "    - name: B\n" +
                     "props2:\n" +
                     "  - name: A\n" +
                     "  - name: B";
        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        // then
        assertEquals(2, node.getProperties().get("props1").getItems().size());
        assertEquals(2, node.getProperties().get("props2").getItems().size());
    }

    @Test
    public void shouldDeserializeTextPayloads() throws Exception {
        // given
        String doc = "abc";
        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        // then
        assertEquals("abc", node.getValue());
    }

    @Test
    public void shouldDeserializeListPayloads() throws Exception {
        // given
        String doc = "- A\n" +
                     "- B";
        // when
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        // then
        assertEquals(2, node.getItems().size());
    }

    @Test
    public void shouldDeserializeSchemaMetadata() throws Exception {
        // given
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

        // when
        Schema schema = node.getSchema();
        // then
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
    public void shouldRejectSchemaPattern() {
        // given
        String doc = "name: name\n" +
                     "schema:\n" +
                     "  pattern: \"^[a-z]+$\"";

        // when
        Throwable exception = captureFailure(
                () -> YAML_MAPPER.readValue(doc, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, exception);
        assertTrue(exception.getMessage().contains("schema.pattern"));
    }

    @Test
    public void shouldRejectInvalidSchemaOptionsKey() {
        // given
        String doc = "name: name\n" +
                     "schema:\n" +
                     "  options:\n" +
                     "    - value: red\n" +
                     "    - value: blue";

        // when
        Throwable exception = captureFailure(
                () -> YAML_MAPPER.readValue(doc, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, exception);
        assertTrue(exception.getMessage().contains("schema.options"));
    }

    @Test
    public void shouldRejectInvalidConstraintsKey() {
        // given
        String doc = "name: name\n" +
                     "constraints:\n" +
                     "  minLength: 5";

        // when
        Throwable exception = captureFailure(
                () -> YAML_MAPPER.readValue(doc, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, exception);
        assertTrue(exception.getMessage().contains("\"constraints\" is not part of the Blue Language 1.0"));
    }

    @Test
    public void shouldRejectSchemaAllowMultiple() {
        // given
        String doc = "name: name\n" +
                     "schema:\n" +
                     "  allowMultiple: true";

        // when
        Throwable exception = captureFailure(
                () -> YAML_MAPPER.readValue(doc, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, exception);
        assertTrue(exception.getMessage().contains("schema.allowMultiple"));
    }

    @Test
    public void shouldRejectSchemaAndConstraintsConflict() {
        // given
        String document = "schema:\n" +
                "  minLength: 5\n" +
                "constraints:\n" +
                "  maxLength: 10";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectRootNull() {
        // given
        String document = "null";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldAcceptRootScalarListObjectAndReference() {
        // given
        String scalarDocument = "abc";
        String listDocument = "[]";
        String objectDocument = "{}";
        String referenceDocument = "blueId: abc";

        // when
        Node scalar = YAML_MAPPER.readValue(scalarDocument, Node.class);
        Node list = YAML_MAPPER.readValue(listDocument, Node.class);
        Node object = YAML_MAPPER.readValue(objectDocument, Node.class);
        Node reference = YAML_MAPPER.readValue(referenceDocument, Node.class);

        // then
        assertEquals("abc", scalar.getValue());
        assertNotNull(list.getItems());
        assertNotNull(object);
        assertTrue(reference.isReferenceOnly());
    }

    @Test
    public void shouldRejectWrongReservedFieldTypes() {
        // given
        String[] invalidDocuments = {
                "name: true",
                "description: 123",
                "blueId: 123",
                "mergePolicy: true",
                "schema: []"
        };

        // when
        Throwable[] failures = new Throwable[invalidDocuments.length];
        for (int index = 0; index < invalidDocuments.length; index++) {
            String document = invalidDocuments[index];
            failures[index] = captureFailure(
                    () -> YAML_MAPPER.readValue(document, Node.class));
        }

        // then
        for (Throwable failure : failures) {
            assertInstanceOf(RuntimeException.class, failure);
        }
    }

    @Test
    public void shouldRejectObjectValuedItems() {
        // given
        String document = "items:\n" +
                "  blueId: abc";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectNestedBlueAndRootBlueList() {
        // given
        String nestedBlue = "child:\n" +
                "  blue: x";
        String rootBlueList = "blue:\n" +
                "  - x";

        // when
        Throwable nestedBlueFailure = captureFailure(
                () -> YAML_MAPPER.readValue(nestedBlue, Node.class));
        Throwable rootBlueListFailure = captureFailure(
                () -> YAML_MAPPER.readValue(rootBlueList, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, nestedBlueFailure);
        assertInstanceOf(RuntimeException.class, rootBlueListFailure);
    }

    @Test
    public void shouldEnforceStrictSchemaKeywordValueShapes() {
        // given
        String typedMinimumDocument = "schema:\n" +
                "  minimum:\n" +
                "    type:\n" +
                "      blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                "    value: \"9007199254740992\"";
        String safeLargeCountDocument = "schema:\n  minItems: 9007199254740991";
        String enumDocument = "schema:\n" +
                "  enum:\n" +
                "    - type:\n" +
                "        blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                "      value: \"9007199254740992\"";
        String[] invalidDocuments = {
                "schema:\n  required: \"true\"",
                "schema:\n  required:\n    value: true",
                "schema:\n  uniqueItems:\n    value: true",
                "schema:\n  minItems: \"1\"",
                "schema:\n  minItems: 9007199254740992",
                "schema:\n  minItems:\n    type: Integer\n    value: \"5\"",
                "schema:\n  minLength:\n    type: Integer\n    value: \"5\"",
                "schema:\n  enum: red",
                "schema:\n  enum:\n    - null",
                "schema:\n  enum:\n    - {}",
                "schema:\n  enum:\n    - $empty: true",
                "schema:\n  enum:\n    - value: 1\n      contracts: {}",
                "schema:\n  enum:\n    - name: one\n      value: 1",
                "schema:\n  enum:\n    - value: 1\n      schema:\n        minimum: 0",
                "schema:\n  minimum: \"9007199254740992\"",
                "schema:\n  minimum: 9007199254740992",
                "schema:\n  minimum:\n    type: Integer\n    value: \"1\"\n    contracts: {}",
                "schema:\n  minimum:\n    type: Integer\n    value: \"1\"\n    name: one"
        };

        // when
        Node node = YAML_MAPPER.readValue(typedMinimumDocument, Node.class);
        Node safeLargeCount = YAML_MAPPER.readValue(safeLargeCountDocument, Node.class);
        Node enumNode = YAML_MAPPER.readValue(enumDocument, Node.class);
        Throwable[] failures = new Throwable[invalidDocuments.length];
        for (int index = 0; index < invalidDocuments.length; index++) {
            String document = invalidDocuments[index];
            failures[index] = captureFailure(
                    () -> YAML_MAPPER.readValue(document, Node.class));
        }

        // then
        for (Throwable failure : failures) {
            assertInstanceOf(RuntimeException.class, failure);
        }

        assertEquals(new BigInteger("9007199254740992"), node.getSchema().getMinimum().getValue());
        assertEquals(new BigInteger("9007199254740991"), safeLargeCount.getSchema().getMinItems().getValue());
        assertEquals(new BigInteger("9007199254740992"), enumNode.getSchema().getEnum().get(0).getValue());
    }

    @Test
    public void shouldValidateEnumReferenceSyntaxAtTheExactReferenceBoundary() {
        // given
        String[] ids = {"abc", "this#0"};
        // when
        for (String id : ids) {
            Node parsed = YAML_MAPPER.readValue("schema:\n  enum:\n    - blueId: " + id, Node.class);
            // then
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BlueIdReferenceValidator.validate(parsed));
            assertTrue(failure.getMessage().contains("/schema/enum/0/blueId"));
        }
    }

    @Test
    public void shouldEnforceCanonicalAsciiGrammarForExplicitIntegerStrings() throws Exception {
        // given
        String negativeZeroDocument = "schema:\n" +
                "  minimum:\n" +
                "    type: Integer\n" +
                "    value: \"-0\"";
        String leadingZeroDocument =
                "schema:\n  minimum:\n    type: Integer\n    value: \"01\"";
        String explicitPlusDocument =
                "schema:\n  minimum:\n    type: Integer\n    value: \"+1\"";
        String nonAsciiDigitDocument =
                "schema:\n  minimum:\n    type: Integer\n    value: \"\u0661\"";

        // when
        Node negativeZero = YAML_MAPPER.readValue(negativeZeroDocument, Node.class);
        Node preprocessedNegativeZero = new Blue().preprocess(negativeZero);
        Throwable negativeZeroFailure = captureFailure(
                () -> preprocessedNegativeZero.getSchema().getMinimum().getValue());
        Throwable leadingZeroFailure = captureFailure(
                () -> YAML_MAPPER.readValue(leadingZeroDocument, Node.class));
        Throwable explicitPlusFailure = captureFailure(
                () -> YAML_MAPPER.readValue(explicitPlusDocument, Node.class));
        Throwable nonAsciiDigitFailure = captureFailure(
                () -> YAML_MAPPER.readValue(nonAsciiDigitDocument, Node.class));

        // then
        assertEquals("-0", negativeZero.getSchema().getMinimum().getRawValue());
        assertInstanceOf(IllegalArgumentException.class, negativeZeroFailure);
        assertInstanceOf(RuntimeException.class, leadingZeroFailure);
        assertInstanceOf(RuntimeException.class, explicitPlusFailure);
        assertInstanceOf(RuntimeException.class, nonAsciiDigitFailure);
    }

    @Test
    public void shouldRejectContractsOnExplicitScalarForSchemaEnum() {
        // given
        String document = "schema:\n  enum:\n    - value: 1\n      contracts: {}";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectNameAndDescriptionOnExplicitScalarForSchemaEnum() {
        // given
        String nameDocument = "schema:\n  enum:\n    - name: one\n      value: 1";
        String descriptionDocument =
                "schema:\n  enum:\n    - description: one\n      value: 1";

        // when
        Throwable nameFailure = captureFailure(
                () -> YAML_MAPPER.readValue(nameDocument, Node.class));
        Throwable descriptionFailure = captureFailure(
                () -> YAML_MAPPER.readValue(descriptionDocument, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, nameFailure);
        assertInstanceOf(RuntimeException.class, descriptionFailure);
    }

    @Test
    public void shouldRejectSchemaOnExplicitScalarForSchemaEnum() {
        // given
        String document =
                "schema:\n  enum:\n    - value: 1\n      schema:\n        minimum: 0";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectContractsOnExplicitNumericNodeForSchemaMinimum() {
        // given
        String document =
                "schema:\n  minimum:\n    type: Integer\n    value: \"1\"\n    contracts: {}";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectExplicitNodeForSchemaMinItems() {
        // given
        String document =
                "schema:\n  minItems:\n    type: Integer\n    value: \"5\"";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectExplicitNodeForSchemaMinLength() {
        // given
        String document =
                "schema:\n  minLength:\n    type: Integer\n    value: \"5\"";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldAcceptAndPreprocessTypedLargeIntegerAliasForSchemaMinimum() {
        // given
        String document = "schema:\n" +
                "  minimum:\n" +
                "    type: Integer\n" +
                "    value: \"9007199254740992\"";

        // when
        Node parsed = YAML_MAPPER.readValue(document, Node.class);
        Node preprocessed = new Blue().preprocess(parsed);

        // then
        assertEquals("Integer", parsed.getSchema().getMinimum().getType().getValue());
        assertEquals(INTEGER_TYPE_BLUE_ID, preprocessed.getSchema().getMinimum().getType().getBlueId());
        assertEquals(new BigInteger("9007199254740992"), preprocessed.getSchema().getMinimum().getValue());
    }

    @Test
    public void shouldExposeExactSafeLargeIntegerValuesForSchemaCountKeywords() {
        // given
        String document = "schema:\n" +
                "  minItems: 2147483648\n" +
                "  maxItems: 9007199254740991\n" +
                "  minLength: 2147483648\n" +
                "  maxLength: 9007199254740991\n" +
                "  minFields: 2147483648\n" +
                "  maxFields: 9007199254740991";

        // when
        Node parsed = YAML_MAPPER.readValue(document, Node.class);

        // then
        assertEquals(new BigInteger("2147483648"), parsed.getSchema().getMinItemsExact());
        assertEquals(new BigInteger("9007199254740991"), parsed.getSchema().getMaxItemsExact());
        assertEquals(new BigInteger("2147483648"), parsed.getSchema().getMinLengthExact());
        assertEquals(new BigInteger("9007199254740991"), parsed.getSchema().getMaxLengthExact());
        assertEquals(new BigInteger("2147483648"), parsed.getSchema().getMinFieldsExact());
        assertEquals(new BigInteger("9007199254740991"), parsed.getSchema().getMaxFieldsExact());
        assertTrue(parsed.getSchema().toString().contains("9007199254740991"));
    }

    @Test
    public void shouldLetSchemaVerifierHandleLargeSafeCountDeterministically() {
        // given
        String document = "items: []\n" +
                "schema:\n" +
                "  minItems: 2147483648";
        Node parsed = YAML_MAPPER.readValue(document, Node.class);

        // when
        Throwable error = captureFailure(
                () -> new Blue().resolve(parsed));

        // then
        assertInstanceOf(IllegalArgumentException.class, error);
        assertTrue(error.getMessage().contains("minimum required items"));
    }

    @Test
    public void shouldPreserveReservedNullFieldsUntilMandatoryPreprocessing() {
        // given
        String[] reservedFields = {
                "name",
                "description",
                "mergePolicy",
                "value",
                "items",
                "type",
                "itemType",
                "keyType",
                "valueType",
                "schema",
                "contracts",
                "blueId"
        };
        String[] sourceDocuments = {
                "name: null",
                "description: null",
                "mergePolicy: null",
                "value: null",
                "items: null",
                "type: null",
                "itemType: null",
                "keyType: null",
                "valueType: null",
                "schema: null",
                "contracts: null",
                "blueId: null"
        };

        // when
        for (int index = 0; index < sourceDocuments.length; index++) {
            Node raw = YAML_MAPPER.readValue(
                    sourceDocuments[index], Node.class);

            // then
            Node rawReservedValue = "contracts".equals(
                    reservedFields[index])
                    ? raw.getContracts()
                    : raw.getProperties().get(reservedFields[index]);
            assertTrue(Nodes.isSourceNullLiteral(rawReservedValue),
                    reservedFields[index]);
            Node preprocessed = new Blue().preprocess(raw);
            assertTrue(Nodes.isExactEmptyObject(preprocessed));
        }
    }

    @Test
    public void shouldDistinguishSourceNullEmptyObjectAndEmptyList() {
        // given
        Node raw = YAML_MAPPER.readValue(
                "x: null\n"
                        + "emptyObject: {}\n"
                        + "emptyList: []",
                Node.class);
        Node rawList = YAML_MAPPER.readValue(
                "items: [null, {}, []]",
                Node.class);

        // when
        Node preprocessed = new Blue().preprocess(raw);
        Node preprocessedList = new Blue().preprocess(rawList);

        // then
        assertTrue(Nodes.isSourceNullLiteral(
                raw.getProperties().get("x")));
        assertTrue(Nodes.isExactEmptyObject(
                raw.getProperties().get("emptyObject")));
        assertNotNull(raw.getProperties().get("emptyList").getItems());
        assertTrue(Nodes.isSourceNullLiteral(rawList.getItems().get(0)));
        assertTrue(Nodes.isExactEmptyObject(rawList.getItems().get(1)));
        assertNotNull(rawList.getItems().get(2).getItems());

        assertFalse(preprocessed.getProperties().containsKey("x"));
        assertTrue(Nodes.isExactEmptyObject(
                preprocessed.getProperties().get("emptyObject")));
        assertNotNull(preprocessed.getProperties().get("emptyList").getItems());
        assertTrue(Nodes.isEmptyPlaceholder(preprocessedList.getItems().get(0)));
        assertTrue(Nodes.isExactEmptyObject(preprocessedList.getItems().get(1)));
        assertNotNull(preprocessedList.getItems().get(2).getItems());
    }

    @Test
    public void shouldPreserveBlueSemanticsWhenParsingNullAndEmptyString() {
        // given
        String objectNullDocument = "x: null";
        String listNullDocument = "items:\n  - null";
        String emptyStringDocument = "value: \"\"";
        String rootNullDocument = "null";

        // when
        Node objectNull = YAML_MAPPER.readValue(objectNullDocument, Node.class);
        Node listNull = YAML_MAPPER.readValue(listNullDocument, Node.class);
        Node empty = YAML_MAPPER.readValue(emptyStringDocument, Node.class);
        Throwable rootNullFailure = captureFailure(
                () -> YAML_MAPPER.readValue(rootNullDocument, Node.class));

        // then
        assertTrue(objectNull.getProperties().containsKey("x"));
        assertNull(objectNull.getProperties().get("x").getValue());
        assertEquals(1, listNull.getItems().size());
        assertNull(listNull.getItems().get(0).getValue());

        assertInstanceOf(RuntimeException.class, rootNullFailure);
        assertEquals("", empty.getValue());
    }

    @Test
    public void shouldRejectDuplicateKeys() {
        // given
        String duplicateJsonKeys = "{\"x\":1,\"x\":2}";
        String duplicateYamlKeys = "x: 1\nx: 2";

        // when
        Throwable jsonFailure = captureFailure(
                () -> JSON_MAPPER.readValue(duplicateJsonKeys, Node.class));
        Throwable yamlFailure = captureFailure(
                () -> YAML_MAPPER.readValue(duplicateYamlKeys, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, jsonFailure);
        assertInstanceOf(RuntimeException.class, yamlFailure);
    }

    @Test
    public void shouldRejectYamlCustomTags() {
        // given
        String document = "value: !custom tagged";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectYamlAnchors() {
        // given
        String document = "x: &shared abc\ny: *shared";

        // when
        Throwable failure = captureFailure(
                () -> YAML_MAPPER.readValue(document, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRejectYamlOnlyTags() {
        // given
        String binaryDocument = "value: !!binary SGVsbG8=";
        String setDocument = "value: !!set\n  ? a\n  ? b";
        String orderedMapDocument = "value: !!omap\n  - a: 1";

        // when
        Throwable binaryFailure = captureFailure(
                () -> YAML_MAPPER.readValue(binaryDocument, Node.class));
        Throwable setFailure = captureFailure(
                () -> YAML_MAPPER.readValue(setDocument, Node.class));
        Throwable orderedMapFailure = captureFailure(
                () -> YAML_MAPPER.readValue(orderedMapDocument, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, binaryFailure);
        assertInstanceOf(RuntimeException.class, setFailure);
        assertInstanceOf(RuntimeException.class, orderedMapFailure);
    }

    @Test
    public void shouldKeepYamlTimestampAndEmptyStringInJsonDataModel() {
        // given
        String timestampDocument = "value: 2026-05-24";
        String emptyStringDocument = "value: \"\"";

        // when
        Node timestamp = YAML_MAPPER.readValue(timestampDocument, Node.class);
        Node empty = YAML_MAPPER.readValue(emptyStringDocument, Node.class);

        // then
        assertEquals("2026-05-24", timestamp.getValue());
        assertEquals("", empty.getValue());
    }

}
