package blue.language;

import blue.language.model.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueViewPathTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    void emptyStringSelectsRootAndSlashSelectsEmptyKeyMember() throws Exception {
        Node root = YAML_MAPPER.readValue(
                "\"\": empty-key\n" +
                "regular: value", Node.class);

        assertSame(root, BlueViewPath.select(root, ""));
        assertEquals("empty-key", BlueViewPath.select(root, "/").getValue());
        assertEquals("value", BlueViewPath.select(root, "/regular").getValue());
    }

    @Test
    void itemsSegmentSelectsListPayloadItemsInAbstractNodeModel() throws Exception {
        Node root = YAML_MAPPER.readValue(
                "regular:\n" +
                "  items:\n" +
                "    - first\n" +
                "    - second", Node.class);

        assertEquals("first", BlueViewPath.select(root, "/regular/items/0").getValue());
        assertEquals("second", BlueViewPath.select(root, "/regular/items/1").getValue());
    }

    @Test
    void escapesTildeAndSlashPerRfc6901() throws Exception {
        Node root = YAML_MAPPER.readValue(
                "\"a/b\":\n" +
                "  \"c~d\": escaped", Node.class);

        assertEquals("escaped", BlueViewPath.select(root, "/a~1b/c~0d").getValue());
    }

    @Test
    void badEscapesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlueViewPath.split("/bad~2escape"));
        assertThrows(IllegalArgumentException.class, () -> BlueViewPath.split("/bad~"));
    }

    @Test
    void arrayIndexesRemainCanonicalAsciiDecimals() throws Exception {
        Node root = YAML_MAPPER.readValue(
                "array:\n" +
                "  items:\n" +
                "    - first", Node.class);

        assertEquals("first", BlueViewPath.select(root, "/array/items/0").getValue());
        assertThrows(IllegalArgumentException.class,
                () -> BlueViewPath.select(root, "/array/items/00"));
        assertThrows(IllegalArgumentException.class,
                () -> BlueViewPath.select(root, "/array/items/\u0660"));
    }

    @Test
    void absentMetadataValueAndReferenceWrapperBlueIdAreNotSemanticChildren() {
        Node plain = new Node();
        assertNull(BlueViewPath.select(plain, "/name"));
        assertNull(BlueViewPath.select(plain, "/description"));
        assertNull(BlueViewPath.select(plain, "/value"));
        assertNull(BlueViewPath.select(plain, "/items"));

        Node reference = new Node().blueId(
                "5nWrS5wTB22MN7HHhyRUy7zQ83Qbf6QEcUY4soFir2Sq");
        assertNull(BlueViewPath.select(reference, "/blueId"));
    }
}
