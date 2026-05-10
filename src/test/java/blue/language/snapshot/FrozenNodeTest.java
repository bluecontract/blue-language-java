package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrozenNodeTest {

    @Test
    void blueIdMatchesMutableCalculatorForObjectsScalarsAndPureReferences() {
        Node node = YAML_MAPPER.readValue(
                "name: Product\n" +
                "count: 1\n" +
                "nested:\n" +
                "  label: abc\n" +
                "ref:\n" +
                "  blueId: SomeReference", Node.class);

        FrozenNode frozen = FrozenNode.fromNode(node);

        assertEquals(BlueIdCalculator.calculateBlueId(node), frozen.blueId());
        assertEquals("SomeReference", FrozenNode.fromNode(new Node().blueId("SomeReference")).blueId());
    }

    @Test
    void strictCanonicalModeDropsEmptyObjectPropertiesLikeMutableCalculator() {
        Node node = YAML_MAPPER.readValue(
                "a: 1\n" +
                "empty: {}\n" +
                "nested:\n" +
                "  empty: {}\n" +
                "  label: ok", Node.class);

        FrozenNode frozen = FrozenNode.fromNode(node);

        assertEquals(BlueIdCalculator.calculateBlueId(node), frozen.blueId());
        assertEquals(null, frozen.property("empty"));
        assertEquals(null, frozen.property("nested").property("empty"));
        assertEquals(BlueIdCalculator.calculateBlueId(frozen.toNode()), frozen.blueId());
    }

    @Test
    void blueIdMatchesMutableCalculatorForEmptySingletonAndNestedLists() {
        Node empty = YAML_MAPPER.readValue("items: []", Node.class);
        Node singleton = YAML_MAPPER.readValue("items:\n  - one", Node.class);
        Node nested = YAML_MAPPER.readValue("items:\n  - items:\n      - one\n  - two", Node.class);

        assertEquals(BlueIdCalculator.calculateBlueId(empty), FrozenNode.fromNode(empty).blueId());
        assertEquals(BlueIdCalculator.calculateBlueId(singleton), FrozenNode.fromNode(singleton).blueId());
        assertEquals(BlueIdCalculator.calculateBlueId(nested), FrozenNode.fromNode(nested).blueId());
    }

    @Test
    void blueIdMatchesMutableCalculatorForListControlForms() {
        Node node = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: PrevListHash\n" +
                "  - $pos: 2\n" +
                "    value: C\n" +
                "  - $pos: 0\n" +
                "    value: A\n" +
                "  - value: D", Node.class);

        assertEquals(BlueIdCalculator.calculateBlueId(node), FrozenNode.fromNode(node).blueId());
    }

    @Test
    void blueIdMatchesMutableCalculatorForTypedDoubleCanonicalization() {
        Node node = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                "value: 0.33333333333333333333333333333333333333", Node.class);

        assertEquals(BlueIdCalculator.calculateBlueId(node), FrozenNode.fromNode(node).blueId());
    }

    @Test
    void immutableViewsCannotBeMutatedAndToNodeReturnsFreshMutableCopies() {
        FrozenNode frozen = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a: 1\n" +
                "list:\n" +
                "  items:\n" +
                "    - x", Node.class));

        assertThrows(UnsupportedOperationException.class,
                () -> frozen.getProperties().put("b", FrozenNode.empty()));
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.property("list").getItems().add(FrozenNode.empty()));

        Node first = frozen.toNode();
        Node second = frozen.toNode();
        first.getProperties().put("mutated", new Node().value(true));

        assertNotSame(first, second);
        assertEquals(BlueIdCalculator.calculateBlueId(second), frozen.blueId());
    }

    @Test
    void pathIndexAndAtResolveObjectAndListPointersWithoutMaterializingWholeTree() {
        FrozenNode frozen = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "profile:\n" +
                "  label: Ana\n" +
                "rows:\n" +
                "  - id: a\n" +
                "  - id: b", Node.class));

        assertEquals(frozen.property("profile").property("label"), frozen.at("/profile/label"));
        assertEquals(frozen.property("rows").item(1).property("id"), frozen.at("/rows/1/id"));
        assertEquals(frozen.at("/rows/1/id"), frozen.pathIndex().get("/rows/1/id"));
        assertEquals(null, frozen.at("/rows/nope"));
        assertEquals(null, frozen.at("/rows/9"));
    }

    @Test
    void listBlueIdUsesCachedElementHashes() {
        FrozenNode one = FrozenNode.fromNode(new Node().value("one"));
        FrozenNode two = FrozenNode.fromNode(new Node().value("two"));
        String frozenListId = FrozenNode.calculateBlueId(Arrays.asList(one, two));
        String mutableListId = BlueIdCalculator.calculateBlueId(Arrays.asList(one.toNode(), two.toNode()));

        assertEquals(mutableListId, frozenListId);
        assertEquals(BlueIdCalculator.calculateBlueId(Collections.emptyList()), FrozenNode.calculateBlueId(Collections.emptyList()));
    }

    @Test
    void rejectsInvalidCanonicalPayloadShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().value("x").properties("y", new Node().value(1))));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().blueId("ref").properties("y", new Node().value(1))));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().previousBlueId("prev").value("x")));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().position(1)));
    }

    @Test
    void rejectsInvalidListControlFormsDuringHashing() {
        Node duplicatePosition = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $pos: 1\n" +
                "    value: A\n" +
                "  - $pos: 1\n" +
                "    value: B", Node.class);
        Node previousNotFirst = YAML_MAPPER.readValue(
                "items:\n" +
                "  - value: A\n" +
                "  - $previous:\n" +
                "      blueId: PrevListHash", Node.class);

        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(duplicatePosition));
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(previousNotFirst));
    }

    @Test
    void resolvedModeAllowsExpandedBlueIdMetadataButCanonicalModeRejectsIt() {
        Node resolvedLike = new Node()
                .blueId("ReferenceMetadata")
                .name("Expanded node");

        FrozenNode resolved = FrozenNode.fromResolvedNode(resolvedLike);

        assertEquals(BlueIdCalculator.calculateBlueId(resolved.toNode()), resolved.blueId());
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(resolvedLike));
    }
}
