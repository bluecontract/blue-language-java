package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalOverlayPatchEngineTest {

    @Test
    void shouldCopyOnlyChangedObjectPathAndRecomputeRootBlueIdOnReplace() {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "left:\n" +
                "  keep: 1\n" +
                "right:\n" +
                "  child: old", Node.class));

        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.replace("/right/child", new Node().value("new")));
        // when
        FrozenNode patched = result.root();

        // then
        assertEquals("old", result.before().getValue());
        assertEquals("new", result.after().getValue());
        assertSame(root.property("left"), patched.property("left"));
        assertNotSame(root.property("right"), patched.property("right"));
        assertNotSame(root, patched);
        assertEquals(BlueIdCalculator.calculateBlueId(patched.toNode()), patched.blueId());
    }

    @Test
    void shouldCreateCanonicalOverlayAncestorsWithoutMutatingOriginalRoot() {
        // given
        FrozenNode root = FrozenNode.empty();

        // when
        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.add("/a/b/c", new Node().value(3)));

        // then
        assertNull(root.property("a"));
        assertEquals(3, result.root().toNode().getAsInteger("/a/b/c/value"));
        assertEquals(BlueIdCalculator.calculateBlueId(result.root().toNode()), result.blueId());
    }

    @Test
    void shouldDecodeJsonPointerEscapesForObjectKeyPatchPaths() {
        // given
        FrozenNode root = FrozenNode.empty();

        FrozenNode patched = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.add("/a~1b/c~0d", new Node().value("escaped")))
                .root();
        FrozenNode replaced = new CanonicalOverlayPatchEngine(patched)
                .apply(JsonPatch.replace("/a~1b/c~0d", new Node().value("updated")))
                .root();
        // when
        FrozenNode removed = new CanonicalOverlayPatchEngine(replaced)
                .apply(JsonPatch.remove("/a~1b/c~0d"))
                .root();

        // then
        assertEquals("escaped", patched.property("a/b").property("c~d").getValue());
        assertEquals("updated", replaced.property("a/b").property("c~d").getValue());
        assertNull(removed.property("a/b"));
    }

    @Test
    void shouldDeleteObjectPropertyAndReturnNullAfterSnapshotOnRemove() {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a: 1\n" +
                "b: 2", Node.class));

        // when
        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.remove("/a"));

        // then
        assertEquals(1, result.before().toNode().getAsInteger("/value"));
        assertNull(result.after());
        assertNull(result.root().property("a"));
        assertSame(root.property("b"), result.root().property("b"));
    }

    @Test
    void shouldUsePersistentPathCopyForArrayAddReplaceRemoveAndAppend() {
        // given
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
        // when
        FrozenNode removed = new CanonicalOverlayPatchEngine(replaced)
                .apply(JsonPatch.remove("/rows/0"))
                .root();

        // then
        assertEquals(3, appended.property("rows").getItems().size());
        assertSame(root.property("rows").item(0), appended.property("rows").item(0));
        assertEquals("bb", replaced.toNode().getAsText("/rows/1/id/value"));
        assertEquals(2, removed.property("rows").getItems().size());
        assertEquals(BlueIdCalculator.calculateBlueId(removed.toNode()), removed.blueId());
    }

    @Test
    void shouldUpsertMissingPropertyOnReplaceAndOverwriteExistingPropertyOnAdd() {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a: old", Node.class));

        FrozenNode replacedMissing = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.replace("/b", new Node().value("created")))
                .root();
        // when
        FrozenNode addedExisting = new CanonicalOverlayPatchEngine(replacedMissing)
                .apply(JsonPatch.add("/a", new Node().value("new")))
                .root();

        // then
        assertEquals("created", replacedMissing.property("b").getValue());
        assertEquals("new", addedExisting.property("a").getValue());
        assertEquals(BlueIdCalculator.calculateBlueId(addedExisting.toNode()), addedExisting.blueId());
    }

    @Test
    void shouldTraverseExistingNumericObjectPropertyButRequireArrayForMissingNumericAncestor() {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "\"0\":\n" +
                "  child: old", Node.class));

        // when
        FrozenNode patched = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.replace("/0/child", new Node().value("new")))
                .root();

        // then
        assertEquals("new", patched.property("0").property("child").getValue());
        assertThrows(IllegalStateException.class,
                () -> new CanonicalOverlayPatchEngine(FrozenNode.empty())
                        .apply(JsonPatch.add("/0/child", new Node().value("bad"))));
    }

    @Test
    void shouldFailAppendTokenOnObjectAndScalarTraversalWithoutMutatingOriginalRoot() {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "scalar: text\n" +
                "object:\n" +
                "  child: value", Node.class));

        // when
        Throwable objectAppendFailure = captureFailure(
                () -> new CanonicalOverlayPatchEngine(root)
                        .apply(JsonPatch.add("/object/-", new Node().value("bad"))));
        Throwable scalarTraversalFailure = captureFailure(
                () -> new CanonicalOverlayPatchEngine(root)
                        .apply(JsonPatch.add("/scalar/child", new Node().value("bad"))));

        // then
        assertInstanceOf(IllegalStateException.class, objectAppendFailure);
        assertInstanceOf(IllegalStateException.class, scalarTraversalFailure);
        assertEquals("text", root.property("scalar").getValue());
        assertEquals("value", root.property("object").property("child").getValue());
    }

    @Test
    void shouldNotChangeOriginalRootAfterFailedPatch() {
        // given
        FrozenNode root = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "items:\n" +
                "  - a", Node.class));
        // when
        CanonicalOverlayPatchEngine engine = new CanonicalOverlayPatchEngine(root);

        // then
        assertThrows(IllegalStateException.class,
                () -> engine.apply(JsonPatch.replace("/items/5", new Node().value("bad"))));

        assertEquals("a", root.item(0).getValue());
        assertEquals(BlueIdCalculator.calculateBlueId(root.toNode()), root.blueId());
    }

    @Test
    void shouldRejectRootPatchesToMatchProcessorBoundary() {
        // given
        FrozenNode root = FrozenNode.empty();

        // when
        Throwable failure = captureFailure(
                () -> new CanonicalOverlayPatchEngine(root)
                        .apply(JsonPatch.replace("/", new Node().value(1))));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldAllowProcessorMarkerBesideScalarRootPayload() {
        // given
        FrozenNode root = FrozenNode.fromNode(
                new Node().value(17));
        Node marker = new Node().properties(
                "documentId", new Node().value("scalar-root"));

        CanonicalPatchResult result =
                new CanonicalOverlayPatchEngine(root)
                        .apply(JsonPatch.add(
                                "/contracts/initialized",
                                marker));
        // when
        FrozenNode patched = result.root();

        // then
        assertSame(root.getValue(), patched.getValue());
        assertNull(root.getContracts());
        assertEquals(
                "scalar-root",
                patched.property("contracts")
                        .property("initialized")
                        .property("documentId")
                        .getValue());
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        patched.toNode()),
                patched.blueId());
    }

    @Test
    void shouldAllowProcessorMarkerBesideListRootPayload() {
        // given
        FrozenNode root = FrozenNode.fromNode(
                new Node().items(
                        new Node().value("kept"),
                        new Node().value("also-kept")));
        Node marker = new Node().properties(
                "documentId", new Node().value("list-root"));

        // when
        FrozenNode patched =
                new CanonicalOverlayPatchEngine(root)
                        .apply(JsonPatch.add(
                                "/contracts/initialized",
                                marker))
                        .root();

        // then
        assertEquals(2, patched.getItems().size());
        assertSame(root.item(0), patched.item(0));
        assertSame(root.item(1), patched.item(1));
        assertEquals(
                "list-root",
                patched.property("contracts")
                        .property("initialized")
                        .property("documentId")
                        .getValue());
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        patched.toNode()),
                patched.blueId());
    }

    @Test
    void shouldFallBackToLegacyNormalizationForMixedFreezeModeOverlay() {
        // given
        FrozenNode resolvedDescendant = FrozenNode.fromResolvedNode(
                new Node().properties("resolved", new Node().value("kept")));
        FrozenNode existing = FrozenNode.fromNode(
                new Node().properties("strict", new Node().value("kept")))
                .withProperty("mixed", resolvedDescendant);
        FrozenNode root = FrozenNode.empty().withProperty("target", existing);
        Node overlay = new Node().properties("added", new Node().value("new"));

        FrozenNode patched = new CanonicalOverlayPatchEngine(root)
                .apply(JsonPatch.replace("/target", overlay))
                .root()
                .property("target");

        Node legacyMerged = existing.toNode();
        legacyMerged.properties("added", new Node().value("new"));
        // when
        FrozenNode expected = FrozenNode.fromNode(legacyMerged);
        // then
        assertEquals(expected.resolvedStructuralKey(), patched.resolvedStructuralKey());
        assertEquals(expected.blueId(), patched.blueId());
    }
}
