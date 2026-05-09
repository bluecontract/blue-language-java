package blue.language;

import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.utils.NodeToMapListOrValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static blue.language.utils.NodeToMapListOrValue.Strategy.SIMPLE;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class NodeToMapListOrValueTest {

    @Test
    public void testBasicStandardStrategy() throws Exception {

        Node node = new Node()
                .name("nameA")
                .description("descriptionA")
                .type(new Node().name("nameB").description("descriptionB"))
                .properties(
                        "a", new Node().value("xyz1"),
                        "b", new Node().value("xyz2").description("descriptionXyz2")
                );

        Object object = NodeToMapListOrValue.get(node);
        assertInstanceOf(Map.class, object);
        Map<String, Object> result = (Map<String, Object>) object;

        assertEquals("nameA", result.get("name"));
        assertEquals("descriptionA", result.get("description"));

        Map<String, Object> type = (Map<String, Object>) result.get("type");
        assertNotNull(type);
        assertEquals("nameB", type.get("name"));
        assertEquals("descriptionB", type.get("description"));

        Map<String, Object> propertyA = (Map<String, Object>) result.get("a");
        assertNotNull(propertyA);
        assertEquals("xyz1", propertyA.get("value"));

        Map<String, Object> propertyB = (Map<String, Object>) result.get("b");
        assertNotNull(propertyB);
        assertEquals("xyz2", propertyB.get("value"));
        assertEquals("descriptionXyz2", propertyB.get("description"));

    }


    @Test
    public void testBasicDomainMappingStrategy() throws Exception {

        Node node = new Node()
                .name("nameA")
                .description("descriptionA")
                .type(new Node().name("nameB").description("descriptionB"))
                .properties(
                        "a", new Node().value("xyz1"),
                        "b", new Node().value("xyz2").description("descriptionXyz2")
                );

        Object object = NodeToMapListOrValue.get(node, SIMPLE);
        assertInstanceOf(Map.class, object);
        Map<String, Object> result = (Map<String, Object>) object;

        assertEquals("nameA", result.get("name"));
        assertEquals("descriptionA", result.get("description"));

        Map<String, Object> type = (Map<String, Object>) result.get("type");
        assertNotNull(type);
        assertEquals("nameB", type.get("name"));
        assertEquals("descriptionB", type.get("description"));

        assertEquals("xyz1", result.get("a"));
        assertEquals("xyz2", result.get("b"));

    }

    @Test
    public void testListStandardStrategy() throws Exception {
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

        Object object = NodeToMapListOrValue.get(node);
        assertInstanceOf(Map.class, object);
        Map<String, Object> result = (Map<String, Object>) object;

        assertEquals("nameA", result.get("name"));
        assertEquals("descriptionA", result.get("description"));

        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertNotNull(items);
        assertEquals(4, items.size());

        Map<String, Object> item1 = items.get(0);
        assertEquals("el1", item1.get("name"));
        assertNull(item1.get("value"));
        assertNull(item1.get("description"));
        assertNull(item1.get("items"));

        Map<String, Object> item2 = items.get(1);
        assertEquals("value1", item2.get("value"));
        assertNull(item2.get("name"));
        assertNull(item2.get("description"));
        assertNull(item2.get("items"));

        Map<String, Object> item3 = items.get(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nestedItems1 = (List<Map<String, Object>>) item3.get("items");
        assertNotNull(nestedItems1);
        assertEquals(2, nestedItems1.size());
        assertEquals("x1", nestedItems1.get(0).get("value"));
        assertEquals("x2", nestedItems1.get(1).get("value"));

        Map<String, Object> item4 = items.get(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nestedItems2 = (List<Map<String, Object>>) item4.get("items");
        assertNotNull(nestedItems2);
        assertEquals(2, nestedItems2.size());
        assertEquals("abc", nestedItems2.get(0).get("name"));
        assertEquals("abc", nestedItems2.get(0).get("description"));
        assertEquals("y1", nestedItems2.get(0).get("value"));
        assertEquals("y2", nestedItems2.get(1).get("value"));
    }

    @Test
    public void testListDomainMappingStrategy() throws Exception {
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

        Object object = NodeToMapListOrValue.get(node, SIMPLE);
        assertInstanceOf(List.class, object);
        List<Object> result = (List<Object>) object;

        assertEquals(4, result.size());

        assertTrue(result.get(0) instanceof Map);
        assertEquals("el1", ((Map<?, ?>) result.get(0)).get("name"));

        assertEquals("value1", result.get(1));

        assertTrue(result.get(2) instanceof List);
        List<?> thirdItemList = (List<?>) result.get(2);
        assertEquals(2, thirdItemList.size());
        assertEquals("x1", thirdItemList.get(0));
        assertEquals("x2", thirdItemList.get(1));

        assertTrue(result.get(3) instanceof List);
        List<?> fourthItemList = (List<?>) result.get(3);
        assertEquals(2, fourthItemList.size());
        assertEquals("y1", fourthItemList.get(0));
        assertEquals("y2", fourthItemList.get(1));
    }

    @Test
    public void testNodeWithSchemaMappingStrategy() throws Exception {
        Schema schema = new Schema()
                .required(true)
                .allowMultiple(false)
                .minLength(
                        new Node().name("Min smth").value(5)
                )
                .maxLength(10)
                .pattern("^[a-z]+$")
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

        Object object = NodeToMapListOrValue.get(node, SIMPLE);
        Node fromObject = JSON_MAPPER.convertValue(object, Node.class);
        Schema resultSchema = fromObject.getSchema();

        assertEquals(true, resultSchema.getRequiredValue());
        assertEquals(false, resultSchema.getAllowMultipleValue());
        assertEquals(5, resultSchema.getMinLengthValue());
        assertEquals(10, resultSchema.getMaxLengthValue());
        assertEquals("^[a-z]+$", resultSchema.getPatternValue().get(0));
        assertEquals(0, new BigDecimal("1.0").compareTo(resultSchema.getMinimumValue()));
        assertEquals(0, new BigDecimal("100.0").compareTo(resultSchema.getMaximumValue()));
        assertEquals(0, new BigDecimal("0.0").compareTo(resultSchema.getExclusiveMinimumValue()));
        assertEquals(0, new BigDecimal("101.0").compareTo(resultSchema.getExclusiveMaximumValue()));
        assertEquals(0, new BigDecimal("2.0").compareTo(resultSchema.getMultipleOfValue()));
        assertEquals(1, resultSchema.getMinItemsValue());
        assertEquals(5, resultSchema.getMaxItemsValue());
        assertEquals(true, resultSchema.getUniqueItemsValue());
        assertEquals(1, resultSchema.getMinFieldsValue());
        assertEquals(3, resultSchema.getMaxFieldsValue());
        assertEquals("red", resultSchema.getEnum().get(0).getValue());
        assertEquals("blue", resultSchema.getEnum().get(1).getValue());
    }

    @Test
    public void testReferenceOnlyBlueIdSerialization() {
        Object object = NodeToMapListOrValue.get(new Node().blueId("abc"));

        assertEquals(Map.of("blueId", "abc"), object);
    }

    @Test
    public void testListControlSerialization() {
        Object previous = NodeToMapListOrValue.get(new Node().previousBlueId("prevHash"));
        assertEquals(Map.of("$previous", Map.of("blueId", "prevHash")), previous);

        Object positioned = NodeToMapListOrValue.get(new Node()
                .position(2)
                .value("C"));
        assertEquals(new BigInteger("2"), ((Map<?, ?>) positioned).get("$pos"));
        assertEquals("C", ((Map<?, ?>) positioned).get("value"));

        Object list = NodeToMapListOrValue.get(new Node()
                .type(new Node().blueId("6aehfNAxHLC1PHHoDr3tYtFH3RWNbiWdFancJ1bypXEY"))
                .mergePolicy("append-only")
                .items(new Node().value("A")));
        assertEquals("append-only", ((Map<?, ?>) list).get("mergePolicy"));
    }

    @Test
    public void testInvalidProgrammaticPreviousControlSerializationIsRejected() {
        Node invalid = new Node()
                .previousBlueId("prevHash")
                .value("C");

        assertThrows(IllegalArgumentException.class, () -> NodeToMapListOrValue.get(invalid));
    }

    @Test
    public void testInvalidProgrammaticPositionControlSerializationIsRejected() {
        Node invalid = new Node().position(0);

        assertThrows(IllegalArgumentException.class, () -> NodeToMapListOrValue.get(invalid));
    }

    @Test
    public void testProgrammaticPayloadKindExclusivity() {
        Node invalidValueAndProperties = new Node()
                .value("abc")
                .properties("child", new Node().value("def"));

        Node invalidItemsAndProperties = new Node()
                .items(new Node().value("abc"))
                .properties("child", new Node().value("def"));

        assertThrows(IllegalArgumentException.class, () -> NodeToMapListOrValue.get(invalidValueAndProperties));
        assertThrows(IllegalArgumentException.class, () -> NodeToMapListOrValue.get(invalidItemsAndProperties));
    }

}
