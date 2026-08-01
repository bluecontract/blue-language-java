package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

public class SerializationTest {

    @Test
    public void shouldSerializeSimpleNode() throws Exception {
        // given
        String yaml = "name: A";

        // when
        Node node = new Blue().yamlToNode(yaml);
        Object result = NodeWireForm.get(node);
        Map<String, Object> resultMap = (Map<String, Object>) result;

        // then
        assertEquals("A", node.getName());
        assertNull(node.getType());
        assertTrue(result instanceof Map);
        assertEquals("A", resultMap.get("name"));
    }

    @Test
    public void shouldSerializeNodeWithSimpleType() throws Exception {
        // given
        String yaml =
                "name: B\n" +
                "type:\n" +
                "  name: A";

        // when
        Node node = new Blue().yamlToNode(yaml);
        Object result = NodeWireForm.get(node);
        Map<String, Object> resultMap = (Map<String, Object>) result;

        // then
        assertEquals("B", node.getName());
        assertNotNull(node.getType());
        assertEquals("A", node.getType().getName());
        assertTrue(result instanceof Map);
        assertEquals("B", resultMap.get("name"));
        assertTrue(resultMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) resultMap.get("type")).get("name"));
    }

    @Test
    public void shouldSerializeNodeWithNestedType() throws Exception {
        // given
        String yaml =
                "name: C\n" +
                "type:\n" +
                "  name: B\n" +
                "  type:\n" +
                "    name: A";

        // when
        Node node = new Blue().yamlToNode(yaml);
        Object result = NodeWireForm.get(node);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        Map<String, Object> typeMap =
                (Map<String, Object>) resultMap.get("type");

        // then
        assertEquals("C", node.getName());
        assertNotNull(node.getType());
        assertEquals("B", node.getType().getName());
        assertNotNull(node.getType().getType());
        assertEquals("A", node.getType().getType().getName());
        assertTrue(result instanceof Map);
        assertEquals("C", resultMap.get("name"));
        assertTrue(resultMap.get("type") instanceof Map);
        assertEquals("B", typeMap.get("name"));
        assertTrue(typeMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) typeMap.get("type")).get("name"));
    }

    @Test
    public void shouldSerializeNodeWithNestedProperty() throws Exception {
        // given
        String yaml =
                "name: X\n" +
                "a:\n" +
                "  type:\n" +
                "    name: A";

        // when
        Node node = new Blue().yamlToNode(yaml);
        Node aNode = node.getProperties().get("a");
        Object result = NodeWireForm.get(node);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        Map<String, Object> aMap =
                (Map<String, Object>) resultMap.get("a");

        // then
        assertEquals("X", node.getName());
        assertNotNull(node.getProperties());
        assertTrue(node.getProperties().containsKey("a"));
        assertNotNull(aNode.getType());
        assertEquals("A", aNode.getType().getName());
        assertTrue(result instanceof Map);
        assertEquals("X", resultMap.get("name"));
        assertTrue(resultMap.get("a") instanceof Map);
        assertTrue(aMap.get("type") instanceof Map);
        assertEquals("A", ((Map<String, Object>) aMap.get("type")).get("name"));
    }

    @Test
    public void shouldSerializeInlineNumber() throws Exception {
        // given
        String yaml =
                "name: InlineNumber\n" +
                "value: 42";

        // when
        Node node = new Blue().yamlToNode(yaml);
        Object result = NodeWireForm.get(node);
        Map<String, Object> resultMap = (Map<String, Object>) result;

        // then
        assertEquals("InlineNumber", node.getName());
        assertEquals(BigInteger.valueOf(42), node.getValue());
        assertTrue(result instanceof Map);
        assertEquals("InlineNumber", resultMap.get("name"));
        assertEquals(BigInteger.valueOf(42), resultMap.get("value"));
        assertTrue(((Map<String, Object>) resultMap.get("type")).containsKey("blueId"));
        assertEquals(INTEGER_TYPE_BLUE_ID, ((Map<String, Object>) resultMap.get("type")).get("blueId"));
    }

    @Test
    public void shouldHonorExplicitIntegerTypeForQuotedNumericValue() throws Exception {
        // given
        String yaml =
                "name: TextAsInteger\n" +
                "type: Integer\n" +
                "value: '123'";

        // when
        Node node = new Blue().yamlToNode(yaml);
        Object result = NodeWireForm.get(node);
        Map<String, Object> resultMap = (Map<String, Object>) result;

        // then
        assertEquals("TextAsInteger", node.getName());
        assertEquals(BigInteger.valueOf(123), node.getValue());
        assertNotNull(node.getType());
        assertEquals(INTEGER_TYPE_BLUE_ID, node.getType().getBlueId());
        assertTrue(result instanceof Map);
        assertEquals("TextAsInteger", resultMap.get("name"));
        assertEquals(BigInteger.valueOf(123), resultMap.get("value"));
        assertEquals(INTEGER_TYPE_BLUE_ID, ((Map<String, Object>) resultMap.get("type")).get("blueId"));
    }

    @Test
    public void shouldSerializeMixedTypeList() throws Exception {
        // given
        String yaml =
                "name: MixedList\n" +
                "type: List\n" +
                "items:\n" +
                "  - value: 'text'\n" +
                "  - value: 42\n" +
                "  - value: 3.14\n" +
                "  - value: true";

        // when
        Node node = new Blue().yamlToNode(yaml);
        Object result = NodeWireForm.get(node);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) resultMap.get("items");

        // then
        assertEquals("MixedList", node.getName());
        assertEquals(LIST_TYPE_BLUE_ID, node.getType().getBlueId());
        assertEquals(4, node.getItems().size());
        assertEquals("text", node.getItems().get(0).getValue());
        assertEquals(BigInteger.valueOf(42), node.getItems().get(1).getValue());
        assertEquals(new BigDecimal("3.14"), node.getItems().get(2).getValue());
        assertEquals(true, node.getItems().get(3).getValue());
        assertTrue(result instanceof Map);
        assertEquals("MixedList", resultMap.get("name"));
        assertEquals(LIST_TYPE_BLUE_ID, ((Map<String, Object>) resultMap.get("type")).get("blueId"));
        assertEquals(4, items.size());
        assertEquals("text", items.get(0).get("value"));
        assertEquals(BigInteger.valueOf(42), items.get(1).get("value"));
        assertEquals(new BigDecimal("3.14"), items.get(2).get("value"));
        assertEquals(true, items.get(3).get("value"));
    }
}
