package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessingRuntimeJsonPatchTest {

    @Test
    void addNestedPropertyCreatesIntermediateObjects() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        JsonPatch patch = JsonPatch.add("/foo/bar/baz", new Node().value("qux"));
        DocumentProcessingRuntime.DocumentUpdateData data = runtime.applyPatch("/", patch);

        assertNull(data.before());
        assertEquals("qux", data.after().getValue());
        assertEquals("/foo/bar/baz", data.path());

        Node baz = property(property(property(document, "foo"), "bar"), "baz");
        assertEquals("qux", baz.getValue());
    }

    @Test
    void replaceUpsertsObjectProperty() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        JsonPatch replace = JsonPatch.replace("/alpha/beta", new Node().value("v1"));
        DocumentProcessingRuntime.DocumentUpdateData upsert = runtime.applyPatch("/", replace);
        assertNull(upsert.before());
        assertEquals("v1", upsert.after().getValue());

        JsonPatch replaceAgain = JsonPatch.replace("/alpha/beta", new Node().value("v2"));
        DocumentProcessingRuntime.DocumentUpdateData update = runtime.applyPatch("/", replaceAgain);
        assertEquals("v1", update.before().getValue());
        assertEquals("v2", update.after().getValue());

        Node beta = property(property(document, "alpha"), "beta");
        assertEquals("v2", beta.getValue());
    }

    @Test
    void removeObjectProperty() {
        Node document = new Node();
        document.properties("key", new Node().value("value"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        DocumentProcessingRuntime.DocumentUpdateData data = runtime.applyPatch("/", JsonPatch.remove("/key"));

        assertEquals("value", data.before().getValue());
        assertNull(data.after());
        assertNull(document.getProperties().get("key"));
    }

    @Test
    void removeMissingObjectPropertyFailsWithoutMutation() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.remove("/missing")));
        assertTrue(ex.getMessage().contains("missing"));
        assertNull(document.getProperties());
    }

    @Test
    void addArrayElementAtIndexShiftsExisting() {
        Node document = arrayDocument("items", 1, 2, 3);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        JsonPatch patch = JsonPatch.add("/items/1", new Node().value(99));
        DocumentProcessingRuntime.DocumentUpdateData data = runtime.applyPatch("/", patch);

        assertEquals(2, intValue(data.before()));
        assertEquals(99, intValue(data.after()));

        List<Node> items = array(document, "items");
        assertEquals(4, items.size());
        assertEquals(1, intValue(items.get(0)));
        assertEquals(99, intValue(items.get(1)));
        assertEquals(2, intValue(items.get(2)));
        assertEquals(3, intValue(items.get(3)));
    }

    @Test
    void addArrayElementAppendToken() {
        Node document = arrayDocument("values", 4, 5);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        JsonPatch patch = JsonPatch.add("/values/-", new Node().value(6));
        DocumentProcessingRuntime.DocumentUpdateData data = runtime.applyPatch("/", patch);

        assertNull(data.before());
        assertEquals(6, intValue(data.after()));

        List<Node> items = array(document, "values");
        assertEquals(3, items.size());
        assertEquals(6, intValue(items.get(2)));
    }

    @Test
    void replaceArrayElementRequiresExistingIndex() {
        Node document = arrayDocument("nums", 7, 8);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        DocumentProcessingRuntime.DocumentUpdateData data = runtime.applyPatch("/", JsonPatch.replace("/nums/1", new Node().value(80)));

        assertEquals(8, intValue(data.before()));
        assertEquals(80, intValue(data.after()));
        assertEquals(80, intValue(array(document, "nums").get(1)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.replace("/nums/5", new Node().value(123))));
        assertTrue(ex.getMessage().contains("out of bounds"));
        assertEquals(2, array(document, "nums").size());
    }

    @Test
    void removeArrayElement() {
        Node document = arrayDocument("letters", "a", "b", "c");
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        DocumentProcessingRuntime.DocumentUpdateData data = runtime.applyPatch("/", JsonPatch.remove("/letters/1"));

        assertEquals("b", data.before().getValue());
        assertNull(data.after());

        List<Node> items = array(document, "letters");
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).getValue());
        assertEquals("c", items.get(1).getValue());
    }

    @Test
    void removeArrayOutOfBoundsFailsWithoutMutation() {
        Node document = arrayDocument("letters", "x");
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.remove("/letters/5")));
        assertTrue(ex.getMessage().contains("out of bounds"));
        assertEquals(1, array(document, "letters").size());
    }

    @Test
    void arrayElementSubpathRequiresExistingElement() {
        Node array = new Node().items(new ArrayList<>());
        Node document = new Node().properties("arr", array);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.add("/arr/0/name", new Node().value("bad"))));
        assertTrue(ex.getMessage().toLowerCase().contains("array index"), ex.getMessage());
        assertTrue(array.getItems().isEmpty());
        Map<String, Node> arrProps = property(document, "arr").getProperties();
        if (arrProps != null) {
            assertTrue(arrProps.isEmpty());
        }
    }

    @Test
    void appendTokenOnObjectFailsAndRollsBack() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.add("/foo/-", new Node().value("nope"))));
        assertTrue(ex.getMessage().contains("Append token"));
        assertNull(document.getProperties());
    }

    @Test
    void addPropertyWithEmptySegmentsMaintainsLiteralPointer() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        runtime.applyPatch("/", JsonPatch.add("/foo//bar/", new Node().value("lit")));

        Node foo = property(document, "foo");
        Node emptyKey = property(foo, "");
        Node bar = property(emptyKey, "bar");
        Node trailingEmpty = property(bar, "");
        assertEquals("lit", trailingEmpty.getValue());
    }

    @Test
    void removePropertyWithEmptySegmentsCleansUpLeaf() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        runtime.applyPatch("/", JsonPatch.add("/foo//bar", new Node().value("lit")));
        runtime.applyPatch("/", JsonPatch.remove("/foo//bar"));

        Node foo = property(document, "foo");
        Node emptyKey = property(foo, "");
        Map<String, Node> props = emptyKey.getProperties();
        assertNotNull(props);
        assertFalse(props.containsKey("bar"));
    }

    @Test
    void tildeSegmentsAreNotUnescaped() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        runtime.applyPatch("/", JsonPatch.add("/tilde/~1key", new Node().value("value")));

        Node tilde = property(document, "tilde");
        Node literal = property(tilde, "~1key");
        assertEquals("value", literal.getValue());
    }

    @Test
    void appendObjectAllowsNestedStructure() {
        Node document = arrayDocument("rows", 1);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        Node nested = new Node().properties("c", new Node().value("v"));
        Node appended = new Node().properties("b", nested);
        runtime.applyPatch("/", JsonPatch.add("/rows/-", appended));

        List<Node> rows = array(document, "rows");
        Node created = rows.get(rows.size() - 1);
        Node child = property(created, "b");
        Node grandChild = property(child, "c");
        assertEquals("v", grandChild.getValue());
    }

    @Test
    void snapshotsAreClones() {
        Node document = arrayDocument("numbers", 1);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        DocumentProcessingRuntime.DocumentUpdateData data = runtime.applyPatch("/", JsonPatch.replace("/numbers/0", new Node().value(2)));

        // mutate returned nodes to ensure the document is unaffected
        data.before().properties("mutated", new Node().value(true));
        data.after().properties("mutated", new Node().value(true));

        Node stored = array(document, "numbers").get(0);
        assertNull(stored.getProperties());
        assertEquals(2, intValue(stored));
    }

    private Node property(Node node, String key) {
        Map<String, Node> properties = node.getProperties();
        assertNotNull(properties, "Expected properties to exist for key '" + key + "'");
        Node child = properties.get(key);
        assertNotNull(child, "Missing property '" + key + "'");
        return child;
    }

    private List<Node> array(Node document, String key) {
        Node arrayNode = property(document, key);
        List<Node> items = arrayNode.getItems();
        assertNotNull(items, "Expected array for '" + key + "'");
        return items;
    }

    private int intValue(Node node) {
        Object value = node.getValue();
        assertTrue(value instanceof BigInteger, "Expected BigInteger but got " + value);
        return ((BigInteger) value).intValue();
    }

    private Node arrayDocument(String key, Object... entries) {
        List<Node> items = new ArrayList<>();
        for (Object entry : entries) {
            items.add(new Node().value(entry));
        }
        Node arrayNode = new Node().items(items);
        return new Node().properties(key, arrayNode);
    }
}
