package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessingRuntimeJsonPatchTest {

    @Test
    void shouldRejectMissingIntermediateParentsWithoutMutation() {
        // given
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        IllegalStateException failure = captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.add(
                                "/foo/bar/baz",
                                new Node().value("qux"))));

        // then
        assertEquals(
                "Final parent does not exist for patch path: /foo/bar/baz",
                failure.getMessage());
        assertNull(document.getProperties());
    }

    @Test
    void shouldPreserveCollectionParentRuleAcrossOrderedPatches() {
        Node document = new Node();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document);

        IllegalStateException missingParent = captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.add(
                                "/orders/order-1",
                                new Node().properties(
                                        "state", new Node().value("new")))));

        assertEquals(
                "Final parent does not exist for patch path: "
                        + "/orders/order-1",
                missingParent.getMessage());
        assertNull(document.getProperties());

        runtime.applyPatch(
                "/", JsonPatch.add("/orders", Nodes.emptyObject()));
        runtime.applyPatch(
                "/",
                JsonPatch.add(
                        "/orders/order-1",
                        new Node().properties(
                                "state", new Node().value("new"))));

        assertEquals(
                "new",
                property(property(document, "orders"), "order-1")
                        .getAsText("/state"));
    }

    @Test
    void shouldAllowAddingCompleteCollectionSubtreeAtExistingParent() {
        Node document = new Node();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document);
        Node orders = new Node().properties(
                "order-1",
                new Node().properties(
                        "state", new Node().value("new")));

        runtime.applyPatch("/", JsonPatch.add("/orders", orders));

        assertEquals(
                "new",
                property(property(document, "orders"), "order-1")
                        .getAsText("/state"));
    }

    @Test
    void shouldUpsertObjectPropertyOnReplace() {
        // given
        Node document = new Node().properties("alpha", new Node());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        JsonPatch replace = JsonPatch.replace("/alpha/beta", new Node().value("v1"));
        JsonPatch replaceAgain = JsonPatch.replace("/alpha/beta", new Node().value("v2"));

        // when
        DocumentUpdateData upsert = runtime.applyPatch("/", replace);
        DocumentUpdateData update = runtime.applyPatch("/", replaceAgain);
        Node beta = property(property(document, "alpha"), "beta");

        // then
        assertNull(upsert.before());
        assertEquals(JsonPatch.Op.ADD, upsert.op());
        assertEquals("v1", upsert.after().getValue());
        assertEquals("v1", update.before().getValue());
        assertEquals(JsonPatch.Op.REPLACE, update.op());
        assertEquals("v2", update.after().getValue());
        assertEquals("v2", beta.getValue());
    }

    @Test
    void shouldRenderAuthoredAddToExistingObjectPropertyAsReplace() {
        // given
        Node document = new Node().properties(
                "alpha",
                new Node().properties(
                        "beta",
                        new Node().value("v1")));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document);

        // when
        DocumentUpdateData update =
                runtime.applyPatch(
                        "/",
                        JsonPatch.add(
                                "/alpha/beta",
                                new Node().value("v2")));

        // then
        assertEquals("v1", update.before().getValue());
        assertEquals(JsonPatch.Op.REPLACE, update.op());
        assertEquals("v2", update.after().getValue());
        assertEquals(
                "v2",
                property(property(document, "alpha"), "beta")
                        .getValue());
    }

    @Test
    void shouldRemoveObjectProperty() {
        // given
        Node document = new Node();
        document.properties("key", new Node().value("value"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        DocumentUpdateData data = runtime.applyPatch("/", JsonPatch.remove("/key"));

        // then
        assertEquals("value", data.before().getValue());
        assertNull(data.after());
        assertTrue(document.getProperties() == null || !document.getProperties().containsKey("key"));
    }

    @Test
    void shouldFailWithoutMutationWhenRemovingMissingObjectProperty() {
        // given
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        IllegalStateException ex = captureFailure(
                () -> runtime.applyPatch("/", JsonPatch.remove("/missing")));

        // then
        assertEquals(IllegalStateException.class, ex.getClass());
        assertTrue(ex.getMessage().contains("missing"));
        assertNull(document.getProperties());
    }

    @Test
    void shouldShiftExistingElementsWhenAddingArrayElementAtIndex() {
        // given
        Node document = arrayDocument("items", 1, 2, 3);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        JsonPatch patch = JsonPatch.add("/items/1", new Node().value(99));
        DocumentUpdateData data = runtime.applyPatch("/", patch);
        List<Node> items = array(document, "items");

        // then
        assertEquals(2, intValue(data.before()));
        assertEquals(JsonPatch.Op.ADD, data.op());
        assertEquals(99, intValue(data.after()));
        assertEquals(4, items.size());
        assertEquals(1, intValue(items.get(0)));
        assertEquals(99, intValue(items.get(1)));
        assertEquals(2, intValue(items.get(2)));
        assertEquals(3, intValue(items.get(3)));
    }

    @Test
    void shouldAppendArrayElementWhenUsingAppendToken() {
        // given
        Node document = arrayDocument("values", 4, 5);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        JsonPatch patch = JsonPatch.add("/values/-", new Node().value(6));
        DocumentUpdateData data = runtime.applyPatch("/", patch);
        List<Node> items = array(document, "values");

        // then
        assertNull(data.before());
        assertEquals(JsonPatch.Op.ADD, data.op());
        assertEquals(6, intValue(data.after()));
        assertEquals(3, items.size());
        assertEquals(6, intValue(items.get(2)));
    }

    @Test
    void shouldReplaceExistingArrayElement() {
        // given
        Node document = arrayDocument("nums", 7, 8);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        DocumentUpdateData data = runtime.applyPatch("/", JsonPatch.replace("/nums/1", new Node().value(80)));

        // then
        assertEquals(8, intValue(data.before()));
        assertEquals(JsonPatch.Op.REPLACE, data.op());
        assertEquals(80, intValue(data.after()));
        assertEquals(80, intValue(array(document, "nums").get(1)));
    }

    @Test
    void shouldRejectOutOfBoundsArrayReplacement() {
        // given
        Node document = arrayDocument("nums", 7, 8);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document);

        // when
        IllegalStateException ex = captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.replace(
                                "/nums/5",
                                new Node().value(123))));

        // then
        assertTrue(ex.getMessage().contains("out of bounds"));
        assertEquals(2, array(document, "nums").size());
    }

    @Test
    void shouldRemoveArrayElement() {
        // given
        Node document = arrayDocument("letters", "a", "b", "c");
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        DocumentUpdateData data = runtime.applyPatch("/", JsonPatch.remove("/letters/1"));
        List<Node> items = array(document, "letters");

        // then
        assertEquals("b", data.before().getValue());
        assertNull(data.after());
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).getValue());
        assertEquals("c", items.get(1).getValue());
    }

    @Test
    void shouldFailWithoutMutationWhenRemovingOutOfBoundsArrayElement() {
        // given
        Node document = arrayDocument("letters", "x");
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        IllegalStateException ex = captureFailure(
                () -> runtime.applyPatch("/", JsonPatch.remove("/letters/5")));

        // then
        assertEquals(IllegalStateException.class, ex.getClass());
        assertTrue(ex.getMessage().contains(
                "Array index out of bounds for remove"));
        assertEquals(1, array(document, "letters").size());
    }

    @Test
    void shouldRejectMissingArrayElementParentWithoutMutation() {
        // given
        Node array = new Node().items(new ArrayList<>());
        Node document = new Node().properties("arr", array);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        IllegalStateException ex = captureFailure(
                () -> runtime.applyPatch("/", JsonPatch.add("/arr/0/name", new Node().value("bad"))));
        Map<String, Node> arrProps = property(document, "arr").getProperties();

        // then
        assertEquals(IllegalStateException.class, ex.getClass());
        assertEquals(
                "Final parent does not exist for patch path: /arr/0/name",
                ex.getMessage());
        assertTrue(array.getItems().isEmpty());
        assertTrue(arrProps == null || arrProps.isEmpty());
    }

    @Test
    void shouldFailAndRollBackWhenUsingAppendTokenOnObject() {
        // given
        Node document = new Node().properties("foo", new Node());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        IllegalStateException ex = captureFailure(
                () -> runtime.applyPatch("/", JsonPatch.add("/foo/-", new Node().value("nope"))));

        // then
        assertEquals(IllegalStateException.class, ex.getClass());
        assertTrue(ex.getMessage().contains("Append token"));
        assertNotNull(document.getProperties());
        assertNull(document.getProperties().get("foo").getProperties());
    }

    @Test
    void shouldMaintainLiteralPointerWhenAddingPropertyWithEmptySegments() {
        // given
        Node document = new Node().properties(
                "foo",
                new Node().properties(
                        "",
                        new Node().properties("bar", new Node())));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        runtime.applyPatch("/", JsonPatch.add("/foo//bar/", new Node().value("lit")));
        Node foo = property(document, "foo");
        Node emptyKey = property(foo, "");
        Node bar = property(emptyKey, "bar");
        Node trailingEmpty = property(bar, "");

        // then
        assertEquals("lit", trailingEmpty.getValue());
    }

    @Test
    void shouldCleanUpLeafWhenRemovingPropertyWithEmptySegments() {
        // given
        Node document = new Node().properties(
                "foo",
                new Node().properties("", new Node()));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        runtime.applyPatch("/", JsonPatch.add("/foo//bar", new Node().value("lit")));
        runtime.applyPatch("/", JsonPatch.remove("/foo//bar"));
        Node foo = property(document, "foo");
        Node emptyKey = property(foo, "");
        Map<String, Node> props = emptyKey.getProperties();

        // then
        assertTrue(props == null || !props.containsKey("bar"));
    }

    @Test
    void shouldAddressLiteralSlashAndTildeKeysUsingJsonPointerEscapes() {
        // given
        Node document = new Node().properties("tilde", new Node());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        runtime.applyPatch("/", JsonPatch.add("/tilde/a~1b", new Node().value("slash")));
        runtime.applyPatch("/", JsonPatch.add("/tilde/a~0b", new Node().value("tilde")));
        runtime.applyPatch("/", JsonPatch.add("/tilde/~01key", new Node().value("literal")));
        Node tilde = property(document, "tilde");

        // then
        assertEquals("slash", property(tilde, "a/b").getValue());
        assertEquals("tilde", property(tilde, "a~b").getValue());
        assertEquals("literal", property(tilde, "~1key").getValue());
    }

    @Test
    void shouldAllowNestedStructureWhenAppendingObject() {
        // given
        Node document = arrayDocument("rows", 1);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        Node nested = new Node().properties("c", new Node().value("v"));
        Node appended = new Node().properties("b", nested);
        runtime.applyPatch("/", JsonPatch.add("/rows/-", appended));
        List<Node> rows = array(document, "rows");
        Node created = rows.get(rows.size() - 1);
        Node child = property(created, "b");
        Node grandChild = property(child, "c");

        // then
        assertEquals("v", grandChild.getValue());
    }

    @Test
    void shouldReturnSnapshotsAsClones() {
        // given
        Node document = arrayDocument("numbers", 1);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        DocumentUpdateData data = runtime.applyPatch("/", JsonPatch.replace("/numbers/0", new Node().value(2)));

        // when
        // mutate returned nodes to ensure the document is unaffected
        data.before().properties("mutated", new Node().value(true));
        data.after().properties("mutated", new Node().value(true));
        Node stored = array(document, "numbers").get(0);

        // then
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
