package blue.language;

import blue.language.model.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
