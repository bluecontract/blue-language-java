package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalOverlayPatchEngineTest {

    @Test
    void replaceCopiesOnlyChangedObjectPathAndRecomputesRootBlueId() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "left:\n" +
                "  keep: 1\n" +
                "right:\n" +
                "  child: old", Node.class));

        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.replace("/right/child", new Node().value("new")));
        FrozenNode patched = result.root();

        assertEquals("old", result.before().getValue());
        assertEquals("new", result.after().getValue());
        assertSame(root.property("left"), patched.property("left"));
        assertNotSame(root.property("right"), patched.property("right"));
        assertNotSame(root, patched);
        assertEquals(BlueIdCalculator.calculateBlueId(patched.toNode()), patched.blueId());
    }

    @Test
    void addCreatesMissingObjectAncestorsWithoutMutatingOriginalRoot() {
        FrozenNode root = FrozenNode.empty();

        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.add("/a/b/c", new Node().value(3)));

        assertNull(root.property("a"));
        assertEquals(3, result.root().toNode().getAsInteger("/a/b/c/value"));
        assertEquals(BlueIdCalculator.calculateBlueId(result.root().toNode()), result.blueId());
    }

    @Test
    void patchPathsDecodeJsonPointerEscapesForObjectKeys() {
        FrozenNode root = FrozenNode.empty();

        FrozenNode patched = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.add("/a~1b/c~0d", new Node().value("escaped")))
                .root();
        FrozenNode replaced = new CanonicalOverlayPatchEngine(patched)
                .apply(JsonPatch.replace("/a~1b/c~0d", new Node().value("updated")))
                .root();
        FrozenNode removed = new CanonicalOverlayPatchEngine(replaced)
                .apply(JsonPatch.remove("/a~1b/c~0d"))
                .root();

        assertEquals("escaped", patched.property("a/b").property("c~d").getValue());
        assertEquals("updated", replaced.property("a/b").property("c~d").getValue());
        assertNull(removed.property("a/b"));
    }

    @Test
    void removeDeletesObjectPropertyAndReturnsNullAfterSnapshot() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a: 1\n" +
                "b: 2", Node.class));

        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.remove("/a"));

        assertEquals(1, result.before().toNode().getAsInteger("/value"));
        assertNull(result.after());
        assertNull(result.root().property("a"));
        assertSame(root.property("b"), result.root().property("b"));
    }

    @Test
    void arrayAddReplaceRemoveAndAppendUsePersistentPathCopy() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "rows:\n" +
                "  items:\n" +
                "    - id: a\n" +
                "    - id: b", Node.class));

        FrozenNode appended = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.add("/rows/-", YAML_MAPPER.readValue("id: c", Node.class)))
                .root();
        FrozenNode replaced = new CanonicalOverlayPatchEngine(appended)
                .apply(JsonPatch.replace("/rows/1/id", new Node().value("bb")))
                .root();
        FrozenNode removed = new CanonicalOverlayPatchEngine(replaced)
                .apply(JsonPatch.remove("/rows/0"))
                .root();

        assertEquals(3, appended.property("rows").getItems().size());
        assertSame(root.property("rows").item(0), appended.property("rows").item(0));
        assertEquals("bb", replaced.toNode().getAsText("/rows/1/id/value"));
        assertEquals(2, removed.property("rows").getItems().size());
        assertEquals(BlueIdCalculator.calculateBlueId(removed.toNode()), removed.blueId());
    }

    @Test
    void replaceUpsertsMissingObjectPropertyAndAddOverwritesExistingProperty() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a: old", Node.class));

        FrozenNode replacedMissing = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.replace("/b", new Node().value("created")))
                .root();
        FrozenNode addedExisting = new CanonicalOverlayPatchEngine(replacedMissing)
                .apply(JsonPatch.add("/a", new Node().value("new")))
                .root();

        assertEquals("created", replacedMissing.property("b").getValue());
        assertEquals("new", addedExisting.property("a").getValue());
        assertEquals(BlueIdCalculator.calculateBlueId(addedExisting.toNode()), addedExisting.blueId());
    }

    @Test
    void existingNumericObjectPropertyCanBeTraversedButMissingNumericAncestorIsArrayOnly() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "\"0\":\n" +
                "  child: old", Node.class));

        FrozenNode patched = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.replace("/0/child", new Node().value("new")))
                .root();

        assertEquals("new", patched.property("0").property("child").getValue());
        assertThrows(IllegalStateException.class,
                () -> new CanonicalOverlayPatchEngine(FrozenNode.empty())
                        .apply(JsonPatch.add("/0/child", new Node().value("bad"))));
    }

    @Test
    void appendTokenOnObjectAndScalarTraversalFailWithoutMutatingOriginalRoot() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "scalar: text\n" +
                "object:\n" +
                "  child: value", Node.class));

        assertThrows(IllegalStateException.class,
                () -> new CanonicalOverlayPatchEngine(root)
                        .apply(JsonPatch.add("/object/-", new Node().value("bad"))));
        assertThrows(IllegalStateException.class,
                () -> new CanonicalOverlayPatchEngine(root)
                        .apply(JsonPatch.add("/scalar/child", new Node().value("bad"))));
        assertEquals("text", root.property("scalar").getValue());
        assertEquals("value", root.property("object").property("child").getValue());
    }

    @Test
    void failedPatchDoesNotChangeOriginalRoot() {
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "items:\n" +
                "  - a", Node.class));
        CanonicalOverlayPatchEngine engine = new CanonicalOverlayPatchEngine(root);

        assertThrows(IllegalStateException.class,
                () -> engine.apply(JsonPatch.replace("/items/5", new Node().value("bad"))));

        assertEquals("a", root.item(0).getValue());
        assertEquals(BlueIdCalculator.calculateBlueId(root.toNode()), root.blueId());
    }

    @Test
    void rootPatchesAreRejectedToMatchProcessorBoundary() {
        FrozenNode root = FrozenNode.empty();

        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalOverlayPatchEngine(root).apply(JsonPatch.replace("/", new Node().value(1))));
    }
}
