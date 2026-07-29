package blue.language.utils;

import blue.language.model.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.*;

class NodePathAccessorTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private Node rootNode;

    @BeforeEach
    void setUp() throws Exception {
        String yaml = "name: Root\n" +
                      "type:\n" +
                      "  name: RootType\n" +
                      "  type:\n" +
                      "    name: MetaType\n" +
                      "a:\n" +
                      "  - name: A1\n" +
                      "    type:\n" +
                      "      name: TypeA\n" +
                      "  - name: A2\n" +
                      "    value: 42\n" +
                      "b:\n" +
                      "  name: B\n" +
                      "  type:\n" +
                      "    name: TypeB\n" +
                      "  c:\n" +
                      "    name: C\n" +
                      "    value: ValueC";

        rootNode = YAML_MAPPER.readValue(yaml, Node.class);
    }

    @Test
    void shouldAccessRootLevelProperty() {
        // given
        String namePath = "/name";
        String typePath = "/type";

        // when
        Object name = rootNode.get(namePath);
        Object type = rootNode.get(typePath);

        // then
        assertEquals("Root", name);
        assertInstanceOf(Node.class, type);
        assertEquals("RootType", ((Node) type).getName());
    }

    @Test
    void shouldAccessNestedProperty() {
        // given
        String nestedNamePath = "/b/name";
        String nestedValuePath = "/b/c/value";

        // when
        Object nestedName = rootNode.get(nestedNamePath);
        Object nestedValue = rootNode.get(nestedValuePath);

        // then
        assertEquals("B", nestedName);
        assertEquals("ValueC", nestedValue);
    }

    @Test
    void shouldAccessListItem() {
        // given
        String firstItemPath = "/a/0";
        String firstItemNamePath = "/a/0/name";
        String secondItemValuePath = "/a/1/value";

        // when
        Object firstItem = rootNode.get(firstItemPath);
        Object firstItemName = rootNode.get(firstItemNamePath);
        Object secondItemValue = rootNode.get(secondItemValuePath);

        // then
        assertInstanceOf(Node.class, firstItem);
        assertEquals("A1", firstItemName);
        assertEquals(BigInteger.valueOf(42), secondItemValue);
    }

    @Test
    void shouldAccessTypeMetadata() {
        // given
        String itemTypePath = "/a/0/type/name";
        String metaTypePath = "/type/type/name";

        // when
        Object itemType = rootNode.get(itemTypePath);
        Object metaType = rootNode.get(metaTypePath);

        // then
        assertEquals("TypeA", itemType);
        assertEquals("MetaType", metaType);
    }

    @Test
    void shouldAccessBlueIdMetadata() {
        // given
        String rootBlueIdPath = "/blueId";
        String itemBlueIdPath = "/a/0/blueId";

        // when
        Object rootBlueId = rootNode.get(rootBlueIdPath);
        Object itemBlueId = rootNode.get(itemBlueIdPath);

        // then
        assertNotNull(rootBlueId);
        assertNotNull(itemBlueId);
    }

    @Test
    void shouldRejectInvalidAccessPath() {
        // given
        String missingPropertyPath = "/nonexistent";
        String outOfRangeItemPath = "/a/5";
        String nonPointerPath = "invalid";

        // when
        Throwable missingPropertyFailure =
                captureFailure(() -> rootNode.get(missingPropertyPath));
        Throwable outOfRangeItemFailure =
                captureFailure(() -> rootNode.get(outOfRangeItemPath));
        Throwable nonPointerFailure =
                captureFailure(() -> rootNode.get(nonPointerPath));

        // then
        assertInstanceOf(IllegalArgumentException.class, missingPropertyFailure);
        assertInstanceOf(IllegalArgumentException.class, outOfRangeItemFailure);
        assertInstanceOf(IllegalArgumentException.class, nonPointerFailure);
    }

    @Test
    void shouldListIndexesRemainAsciiAndUnicodeDigitsRemainPropertyNames() {
        // given
        Node node = new Node().properties("\u0660", new Node().value("property"));
        String unicodeDigitPropertyPath = "/\u0660";
        String unicodeDigitListPath = "/a/\u0660";

        // when
        Object propertyValue = NodePathAccessor.get(node, unicodeDigitPropertyPath);
        Throwable listAccessFailure =
                captureFailure(() -> NodePathAccessor.get(rootNode, unicodeDigitListPath));

        // then
        assertEquals("property", propertyValue);
        assertInstanceOf(IllegalArgumentException.class, listAccessFailure);
    }

    @Test
    void shouldPreferValuePayloadDuringAccess() {
        // given
        Node nodeWithValue = new Node().name("Test").value("TestValue");
        Node nodeWithoutValue = new Node().name("Test");

        // when
        Object rootValue = NodePathAccessor.get(nodeWithValue, "/");
        Object valueNodeName = NodePathAccessor.get(nodeWithValue, "/name");
        Object rootNodeWithoutValue = NodePathAccessor.get(nodeWithoutValue, "/");
        Object valuelessNodeName = NodePathAccessor.get(nodeWithoutValue, "/name");

        // then
        assertEquals("TestValue", rootValue);
        assertEquals("Test", valueNodeName);
        assertInstanceOf(Node.class, rootNodeWithoutValue);
        assertEquals("Test", valuelessNodeName);
    }

    @Test
    void shouldEscapeJsonPointer() throws Exception {
        // given
        Node node = YAML_MAPPER.readValue(
                "\"a/b\": slash\n" +
                "\"a~b\": tilde\n" +
                "nested:\n" +
                "  \"x/y\": value", Node.class);

        // when
        Object slashValue = node.get("/a~1b/value");
        Object tildeValue = node.get("/a~0b/value");
        Object nestedSlashValue = node.get("/nested/x~1y/value");

        // then
        assertEquals("slash", slashValue);
        assertEquals("tilde", tildeValue);
        assertEquals("value", nestedSlashValue);
    }

    @Test
    void shouldReadContractsWithNodePathAccessor() throws Exception {
        // given
        Node node = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  audit:\n" +
                "    enabled: true", Node.class);

        // when
        Object enabled = node.get("/contracts/audit/enabled/value");
        Node contracts = NodePathAccessor.getNode(node, "/contracts");

        // then
        assertEquals(Boolean.TRUE, enabled);
        assertSame(node.getContracts(), contracts);
    }

    @Test
    void shouldWriteContractsWithNodePathEditor() {
        // given
        Node node = new Node();

        // when
        NodePathEditor.put(node, "/contracts/audit/enabled", new Node().value(true));
        Node contracts = node.getContracts();
        Object enabled = node.get("/contracts/audit/enabled/value");
        boolean contractsStoredAsOrdinaryProperty =
                node.getProperties() != null
                        && node.getProperties().containsKey("contracts");

        // then
        assertNotNull(contracts);
        assertEquals(Boolean.TRUE, enabled);
        assertFalse(contractsStoredAsOrdinaryProperty);
    }

    @Test
    void shouldFindContractsWithNodePathSelector() throws Exception {
        // given
        Node node = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  audit:\n" +
                "    enabled: true\n" +
                "other:\n" +
                "  enabled: true", Node.class);

        // when
        List<String> selected = NodePathSelector.select(node,
                Arrays.asList("/contracts/*/enabled"),
                candidate -> Boolean.TRUE.equals(candidate.getValue()));

        // then
        assertEquals(Arrays.asList("/contracts/audit/enabled"), selected);
    }

    @Test
    void shouldJsonPointerContractsRoundTrip() throws Exception {
        // given
        Node node = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  \"a/b\":\n" +
                "    \"c~d\": value", Node.class);

        // when
        Object value = node.get("/contracts/a~1b/c~0d/value");

        // then
        assertEquals("value", value);
    }
}
