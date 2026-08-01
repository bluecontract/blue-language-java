package blue.language.model;

import blue.language.Blue;
import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.NodeWireForm.Strategy.SIMPLE;
import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class NodeWireFormTest {

    @Test
    public void shouldSerializeBasicNodeWithStandardStrategy() throws Exception {

        // given
        Node node = new Node()
                .name("nameA")
                .description("descriptionA")
                .type(new Node().name("nameB").description("descriptionB"))
                .properties(
                        "a", new Node().value("xyz1"),
                        "b", new Node().value("xyz2").description("descriptionXyz2")
                );

        // when
        Object object = NodeWireForm.get(node);
        Map<String, Object> result = (Map<String, Object>) object;
        Map<String, Object> type = (Map<String, Object>) result.get("type");
        Map<String, Object> propertyA = (Map<String, Object>) result.get("a");
        Map<String, Object> propertyB = (Map<String, Object>) result.get("b");

        // then
        assertInstanceOf(Map.class, object);
        assertEquals("nameA", result.get("name"));
        assertEquals("descriptionA", result.get("description"));
        assertNotNull(type);
        assertEquals("nameB", type.get("name"));
        assertEquals("descriptionB", type.get("description"));
        assertNotNull(propertyA);
        assertEquals("xyz1", propertyA.get("value"));
        assertNotNull(propertyB);
        assertEquals("xyz2", propertyB.get("value"));
        assertEquals("descriptionXyz2", propertyB.get("description"));

    }


    @Test
    public void shouldSerializeBasicNodeWithSimpleStrategy() throws Exception {

        // given
        Node node = new Node()
                .name("nameA")
                .description("descriptionA")
                .type(new Node().name("nameB").description("descriptionB"))
                .properties(
                        "a", new Node().value("xyz1"),
                        "b", new Node().value("xyz2").description("descriptionXyz2")
                );

        // when
        Object object = NodeWireForm.get(node, SIMPLE);
        Map<String, Object> result = (Map<String, Object>) object;
        Map<String, Object> type = (Map<String, Object>) result.get("type");

        // then
        assertInstanceOf(Map.class, object);
        assertEquals("nameA", result.get("name"));
        assertEquals("descriptionA", result.get("description"));
        assertNotNull(type);
        assertEquals("nameB", type.get("name"));
        assertEquals("descriptionB", type.get("description"));

        assertEquals("xyz1", result.get("a"));
        assertEquals("xyz2", result.get("b"));

    }

    @Test
    public void shouldSerializeListNodeWithStandardStrategy() throws Exception {
        // given
        Node node = new Node()
                .name("nameA")
                .description("descriptionA")
                .items(
                        new Node().name("el1"),
                        new Node().value("value1"),
                        new Node().items(
                                new Node().value("x1"),
                                new Node().value("x2")
                        ),
                        new Node().items(
                                new Node().name("abc").description("abc").value("y1"),
                                new Node().value("y2")
                        )
                );

        // when
        Object object = NodeWireForm.get(node);
        Map<String, Object> result = (Map<String, Object>) object;
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        Map<String, Object> item1 = items.get(0);
        Map<String, Object> item2 = items.get(1);
        Map<String, Object> item3 = items.get(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nestedItems1 = (List<Map<String, Object>>) item3.get("items");
        Map<String, Object> item4 = items.get(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nestedItems2 = (List<Map<String, Object>>) item4.get("items");

        // then
        assertInstanceOf(Map.class, object);
        assertEquals("nameA", result.get("name"));
        assertEquals("descriptionA", result.get("description"));
        assertNotNull(items);
        assertEquals(4, items.size());
        assertEquals("el1", item1.get("name"));
        assertNull(item1.get("value"));
        assertNull(item1.get("description"));
        assertNull(item1.get("items"));
        assertEquals("value1", item2.get("value"));
        assertNull(item2.get("name"));
        assertNull(item2.get("description"));
        assertNull(item2.get("items"));
        assertNotNull(nestedItems1);
        assertEquals(2, nestedItems1.size());
        assertEquals("x1", nestedItems1.get(0).get("value"));
        assertEquals("x2", nestedItems1.get(1).get("value"));
        assertNotNull(nestedItems2);
        assertEquals(2, nestedItems2.size());
        assertEquals("abc", nestedItems2.get(0).get("name"));
        assertEquals("abc", nestedItems2.get(0).get("description"));
        assertEquals("y1", nestedItems2.get(0).get("value"));
        assertEquals("y2", nestedItems2.get(1).get("value"));
    }

    @Test
    public void shouldSerializeListNodeWithSimpleStrategy() throws Exception {
        // given
        Node node = new Node()
                .name("nameA")
                .description("descriptionA")
                .items(
                        new Node().name("el1"),
                        new Node().value("value1"),
                        new Node().items(
                                new Node().value("x1"),
                                new Node().value("x2")
                        ),
                        new Node().items(
                                new Node().name("abc").description("abc").value("y1"),
                                new Node().value("y2")
                        )
                );

        // when
        Object object = NodeWireForm.get(node, SIMPLE);
        List<Object> result = (List<Object>) object;
        List<?> thirdItemList = (List<?>) result.get(2);
        List<?> fourthItemList = (List<?>) result.get(3);

        // then
        assertInstanceOf(List.class, object);
        assertEquals(4, result.size());
        assertTrue(result.get(0) instanceof Map);
        assertEquals("el1", ((Map<?, ?>) result.get(0)).get("name"));
        assertEquals("value1", result.get(1));
        assertTrue(result.get(2) instanceof List);
        assertEquals(2, thirdItemList.size());
        assertEquals("x1", thirdItemList.get(0));
        assertEquals("x2", thirdItemList.get(1));
        assertTrue(result.get(3) instanceof List);
        assertEquals(2, fourthItemList.size());
        assertEquals("y1", fourthItemList.get(0));
        assertEquals("y2", fourthItemList.get(1));
    }

    @Test
    public void shouldSerializeSchemaConstraintsWithSimpleStrategy() throws Exception {
        // given
        Schema schema = new Schema()
                .required(true)
                .minLength(
                        new Node().name("Min smth").value(5)
                )
                .maxLength(10)
                .minimum(new BigDecimal("1.0"))
                .maximum(new BigDecimal("100.0"))
                .exclusiveMinimum(new BigDecimal("0.0"))
                .exclusiveMaximum(new BigDecimal("101.0"))
                .multipleOf(new BigDecimal("2.0"))
                .minItems(1)
                .maxItems(5)
                .uniqueItems(true)
                .minFields(1)
                .maxFields(3)
                .enumValues(Arrays.asList(new Node().value("red"), new Node().value("blue")));

        Node node = new Node()
                .name("nameA")
                .description("descriptionA")
                .schema(schema);

        // when
        Object object = NodeWireForm.get(node, SIMPLE);
        Node fromObject = JSON_MAPPER.convertValue(object, Node.class);
        Schema resultSchema = fromObject.getSchema();

        // then
        assertEquals(true, resultSchema.getRequiredValue());
        assertEquals(BigInteger.valueOf(5), resultSchema.getMinLengthExact());
        assertEquals(BigInteger.valueOf(10), resultSchema.getMaxLengthExact());
        assertEquals(0, new BigDecimal("1.0").compareTo(resultSchema.getMinimumValue()));
        assertEquals(0, new BigDecimal("100.0").compareTo(resultSchema.getMaximumValue()));
        assertEquals(0, new BigDecimal("0.0").compareTo(resultSchema.getExclusiveMinimumValue()));
        assertEquals(0, new BigDecimal("101.0").compareTo(resultSchema.getExclusiveMaximumValue()));
        assertEquals(0, new BigDecimal("2.0").compareTo(resultSchema.getMultipleOfValue()));
        assertEquals(BigInteger.ONE, resultSchema.getMinItemsExact());
        assertEquals(BigInteger.valueOf(5), resultSchema.getMaxItemsExact());
        assertEquals(true, resultSchema.getUniqueItemsValue());
        assertEquals(BigInteger.ONE, resultSchema.getMinFieldsExact());
        assertEquals(BigInteger.valueOf(3), resultSchema.getMaxFieldsExact());
        assertEquals("red", resultSchema.getEnum().get(0).getValue());
        assertEquals("blue", resultSchema.getEnum().get(1).getValue());
    }

    @Test
    public void shouldSerializeReferenceOnlyNodeAsBlueIdMap() {
        // given
        Node reference = new Node().blueId("abc");

        // when
        Object object = NodeWireForm.get(reference);

        // then
        assertEquals(Collections.singletonMap("blueId", "abc"), object);
    }

    @Test
    public void shouldSerializeListControlFields() {
        // given
        Node previousControl = new Node().previousBlueId("prevHash");
        Node positionedControl = new Node()
                .position(2)
                .value("C");
        Node listControl = new Node()
                .type(new Node().blueId(LIST_TYPE_BLUE_ID))
                .mergePolicy("append-only")
                .items(new Node().value("A"));
        Map<String, Object> previousReference = new LinkedHashMap<>();
        previousReference.put("blueId", "prevHash");

        // when
        Object previous = NodeWireForm.get(previousControl);
        Object positioned = NodeWireForm.get(positionedControl);
        Object list = NodeWireForm.get(listControl);

        // then
        assertEquals(Collections.singletonMap("$previous", previousReference), previous);
        assertEquals(new BigInteger("2"), ((Map<?, ?>) positioned).get("$pos"));
        assertEquals("C", ((Map<?, ?>) positioned).get("value"));
        assertEquals("append-only", ((Map<?, ?>) list).get("mergePolicy"));
    }

    @Test
    public void shouldSerializeBlueDirectiveRecursively() {
        // given
        Node node = new Node()
                .blue(new Node().properties("imports", new Node().properties(
                        "Person", new Node().blueId("abc"))))
                .value("hello");

        // when
        Object object = NodeWireForm.get(node);
        Map<String, Object> result = (Map<String, Object>) object;
        Map<String, Object> blue = (Map<String, Object>) result.get("blue");

        // then
        assertInstanceOf(Map.class, result.get("blue"));
        assertInstanceOf(Map.class, blue.get("imports"));
        assertEquals(Collections.singletonMap("blueId", "abc"),
                ((Map<?, ?>) blue.get("imports")).get("Person"));
    }

    @Test
    public void shouldAllowContractsAlongsideValueAndItems() {
        // given
        Node valueWithContracts = new Node()
                .value("abc")
                .properties("contracts", new Node().properties("audit", new Node().value("on")));
        Node itemsWithContracts = new Node()
                .items(new Node().value("abc"))
                .properties("contracts", new Node().properties("audit", new Node().value("on")));

        // when
        Map<String, Object> valueResult = (Map<String, Object>) NodeWireForm.get(valueWithContracts);
        Map<String, Object> itemsResult = (Map<String, Object>) NodeWireForm.get(itemsWithContracts);

        // then
        assertEquals("abc", valueResult.get("value"));
        assertTrue(valueResult.containsKey("contracts"));
        assertTrue(itemsResult.containsKey("items"));
        assertTrue(itemsResult.containsKey("contracts"));
    }

    @Test
    public void shouldEmitEnumWithoutInvalidOptionsKeyDuringCanonicalSchemaSerialization() throws Exception {
        // given
        Node node = new Blue().yamlToNode(
                "schema:\n" +
                "  enum:\n" +
                "    - red\n" +
                "    - blue");

        // when
        String json = JSON_MAPPER.writeValueAsString(NodeWireForm.get(node));

        // then
        assertTrue(json.contains("\"enum\""));
        assertFalse(json.contains("\"options\""));
        assertEquals("red", node.getSchema().getEnum().get(0).getValue());
        assertEquals("blue", node.getSchema().getEnum().get(1).getValue());
    }

    @Test
    public void shouldPreserveContractsOnPlainScalarSchemaValues() {
        // given
        Node node = new Node().schema(new Schema().enumValues(Collections.singletonList(
                new Node()
                        .value("red")
                        .contracts(new Node().properties("audit", new Node().value(true))))));

        // when
        Map<String, Object> result = (Map<String, Object>) NodeWireForm.get(node);
        Map<String, Object> schema = (Map<String, Object>) result.get("schema");
        List<Object> enumValues = (List<Object>) schema.get("enum");

        // then
        assertInstanceOf(Map.class, enumValues.get(0));
        assertTrue(((Map<String, Object>) enumValues.get(0)).containsKey("contracts"));
    }

    @Test
    public void shouldRejectInvalidProgrammaticPreviousControlSerialization() {
        // given
        Node invalid = new Node()
                .previousBlueId("prevHash")
                .value("C");

        // when
        Throwable failure = captureFailure(() ->
                NodeWireForm.get(invalid));

        // then
        assertEquals(IllegalArgumentException.class, failure.getClass());
    }

    @Test
    public void shouldRejectInvalidProgrammaticPositionControlSerialization() {
        // given
        Node invalid = new Node().position(0);

        // when
        Throwable failure = captureFailure(() ->
                NodeWireForm.get(invalid));

        // then
        assertEquals(IllegalArgumentException.class, failure.getClass());
    }

    @Test
    public void shouldRejectProgrammaticNodesWithMultiplePayloadKinds() {
        // given
        Node invalidValueAndProperties = new Node()
                .value("abc")
                .properties("child", new Node().value("def"));
        Node invalidItemsAndProperties = new Node()
                .items(new Node().value("abc"))
                .properties("child", new Node().value("def"));

        // when
        Throwable valueAndPropertiesFailure = captureFailure(() ->
                NodeWireForm.get(invalidValueAndProperties));
        Throwable itemsAndPropertiesFailure = captureFailure(() ->
                NodeWireForm.get(invalidItemsAndProperties));

        // then
        assertEquals(IllegalArgumentException.class,
                valueAndPropertiesFailure.getClass());
        assertEquals(IllegalArgumentException.class,
                itemsAndPropertiesFailure.getClass());
    }

}
