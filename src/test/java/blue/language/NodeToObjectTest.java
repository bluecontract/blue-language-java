package blue.language;

import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.utils.NodeToObject;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static blue.language.utils.NodeToObject.Strategy.DOMAIN_MAPPING;
import static blue.language.utils.Properties.OBJECT_CONSTRAINTS;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class NodeToObjectTest {

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

        Object object = NodeToObject.get(node);
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

        Object object = NodeToObject.get(node, DOMAIN_MAPPING);
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

        Object object = NodeToObject.get(node);
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

        Object object = NodeToObject.get(node, DOMAIN_MAPPING);
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
    public void testNodeWithConstraintsMappingStrategy() throws Exception {
        Constraints constraints = new Constraints()
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
                .uniqueItems(true);

        Node node = new Node()
                .name("nameA")
                .description("descriptionA")
                .constraints(constraints);

        Object object = NodeToObject.get(node, DOMAIN_MAPPING);
        Node fromObject = JSON_MAPPER.convertValue(object, Node.class);
        Constraints resultConstraints = fromObject.getConstraints();
        System.out.println(resultConstraints);

        System.out.println(resultConstraints.getMinLengthValue() + " = " + resultConstraints.getMinLengthValue().getClass());


//        System.out.println(object);
//        assertInstanceOf(Map.class, object);
//        Map<?, ?> result = (Map<?, ?>) object;
//
//        Object constraintsObject = result.get(OBJECT_CONSTRAINTS);
//        assertInstanceOf(Map.class, constraintsObject);
//        Map<?, ?> constraintsMap = (Map<?, ?>) constraintsObject;

//        assertEquals(true, constraintsMap.get("required"));
//        assertEquals(false, constraintsMap.get("allowMultiple"));
        assertEquals(5, resultConstraints.getMinLengthValue());
//        assertEquals(new BigInteger("10"), constraintsMap.get("maxLength"));
//        assertEquals("^[a-z]+$", constraintsMap.get("pattern"));
//        assertEquals(new BigDecimal("1.0"), constraintsMap.get("minimum"));
//        assertEquals(new BigDecimal("100.0"), constraintsMap.get("maximum"));
//        assertEquals(new BigDecimal("0.0"), constraintsMap.get("exclusiveMinimum"));
//        assertEquals(new BigDecimal("101.0"), constraintsMap.get("exclusiveMaximum"));
//        assertEquals(new BigDecimal("2.0"), constraintsMap.get("multipleOf"));
//        assertEquals(new BigInteger("1"), constraintsMap.get("minItems"));
//        assertEquals(new BigInteger("5"), constraintsMap.get("maxItems"));
//        assertEquals(true, constraintsMap.get("uniqueItems"));
    }
    
}
