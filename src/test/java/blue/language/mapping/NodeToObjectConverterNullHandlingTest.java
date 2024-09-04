package blue.language.mapping;

import blue.language.Blue;
import blue.language.mapping.model.Y;
import blue.language.model.Node;
import blue.language.utils.TypeClassResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class NodeToObjectConverterNullHandlingTest {

    private Blue blue;
    private NodeToObjectConverter converter;

    @BeforeEach
    void setUp() {
        blue = new Blue();
        converter = new NodeToObjectConverter(new TypeClassResolver("blue.language.mapping.model"));
    }

    @Test
    public void testNullHandling() throws Exception {
        String yaml = "type:\n" +
                      "  blueId: Y-BlueId\n" +
                      "xField:\n" +
                      "  intField: null\n" +
                      "  stringField: null\n" +
                      "x1Field:\n" +
                      "  intArrayField: null\n" +
                      "  stringListField: null\n" +
                      "  integerSetField: null\n" +
                      "x2Field:\n" +
                      "  stringIntMapField: null\n" +
                      "xListField: null\n" +
                      "xMapField: null\n" +
                      "x1SetField: null\n" +
                      "x2MapField: null\n" +
                      "xArrayField: null\n" +
                      "wildcardXListField: null\n" +
                      "name: null\n" +
                      "description: null";

        Node node = blue.yamlToNode(yaml);
        Y y = converter.convert(node, Y.class);

        assertNotNull(y);

        // Check X field
        assertNotNull(y.xField);
        assertEquals(0, y.xField.intField);
        assertNull(y.xField.stringField);

        assertNotNull(y.x1Field);
        assertNull(y.x1Field.intArrayField);
        assertNull(y.x1Field.stringListField);
        assertNull(y.x1Field.integerSetField);

        // Check X2 field
        assertNotNull(y.x2Field);
        assertNull(y.x2Field.stringIntMapField);

        // Check other fields
        assertNull(y.xListField);
        assertNull(y.xMapField);
        assertNull(y.x1SetField);
        assertNull(y.x2MapField);
        assertNull(y.xArrayField);
        assertNull(y.wildcardXListField);

        // Check name and description
        assertNull(node.getName());
        assertNull(node.getDescription());
    }

    @Test
    public void testPartialNullHandling() throws Exception {
        String yaml = "type:\n" +
                      "  blueId: Y-BlueId\n" +
                      "xField:\n" +
                      "  intField: 42\n" +
                      "  stringField: null\n" +
                      "x1Field:\n" +
                      "  intArrayField: [1, null, 3]\n" +
                      "  stringListField: [\"a\", null, \"c\"]\n" +
                      "  integerSetField: [10, null, 30]\n" +
                      "x2Field:\n" +
                      "  stringIntMapField:\n" +
                      "    key1: 100\n" +
                      "    key2: null\n" +
                      "name: \"Test Y\"\n" +
                      "description: null";

        Node node = blue.yamlToNode(yaml);
        Y y = converter.convert(node, Y.class);

        assertNotNull(y);

        // Check X field
        assertNotNull(y.xField);
        assertEquals(42, y.xField.intField);
        assertNull(y.xField.stringField);

        // Check X1 field
        assertNotNull(y.x1Field);
        assertNotNull(y.x1Field.intArrayField);
        assertArrayEquals(new int[]{1, 0, 3}, y.x1Field.intArrayField);  // Primitive array, 0 for null
        assertEquals(Arrays.asList("a", null, "c"), y.x1Field.stringListField);
        assertTrue(y.x1Field.integerSetField.contains(10));
        assertTrue(y.x1Field.integerSetField.contains(30));
        assertTrue(y.x1Field.integerSetField.contains(null));

        // Check X2 field
        assertNotNull(y.x2Field);
        assertNotNull(y.x2Field.stringIntMapField);
        assertEquals(100, y.x2Field.stringIntMapField.get("key1"));
        assertNull(y.x2Field.stringIntMapField.get("key2"));

        // Check name and description
        assertEquals("Test Y", node.getName());
        assertNull(node.getDescription());
    }

    @Test
    public void testEmptyCollectionsAndMaps() throws Exception {
        String yaml = "type:\n" +
                      "  blueId: Y-BlueId\n" +
                      "x1Field:\n" +
                      "  intArrayField: []\n" +
                      "  stringListField: []\n" +
                      "  integerSetField: []\n" +
                      "xListField: []\n" +
                      "xMapField: {}\n" +
                      "x1SetField: []\n" +
                      "x2MapField: {}";

        Node node = blue.yamlToNode(yaml);
        Y y = converter.convert(node, Y.class);

        assertNotNull(y);

        // Check X1 field
        assertNotNull(y.x1Field);
        assertNotNull(y.x1Field.intArrayField);
        assertEquals(0, y.x1Field.intArrayField.length);
        assertNotNull(y.x1Field.stringListField);
        assertTrue(y.x1Field.stringListField.isEmpty());
        assertNotNull(y.x1Field.integerSetField);
        assertTrue(y.x1Field.integerSetField.isEmpty());

        // Check other fields
        assertNotNull(y.xListField);
        assertTrue(y.xListField.isEmpty());
        assertNull(y.xMapField);
        assertNotNull(y.x1SetField);
        assertTrue(y.x1SetField.isEmpty());
        assertNull(y.x2MapField);
    }
}