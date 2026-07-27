package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.MinimizedOverlayBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinimizedOverlayInlineTypeTest {

    @Test
    void anonymousAppendOnlyTypeRoundTripsAcrossIndependentBlueInstances() {
        Blue writer = new Blue();
        String inheritedItemsBlueId = inheritedAbBlueId(writer);
        Node source = writer.yamlToNode(
                "type:\n" +
                "  type: List\n" +
                "  mergePolicy: append-only\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B\n" +
                "items:\n" +
                "  - A\n" +
                "  - B\n" +
                "  - C");

        RoundTrip roundTrip = assertIndependentRoundTrip(writer, source);

        assertAnonymousListType(roundTrip.minimized.getType(), 2);
        assertEquals(inheritedItemsBlueId,
                roundTrip.minimized.getItems().get(0).getPreviousBlueId());
        assertEquals("C", roundTrip.minimized.getItems().get(1).getValue());
    }

    @Test
    void existingPreviousAnchorRoundTripsWithoutRuntimeLocalTypeStorage() {
        Blue writer = new Blue();
        String inheritedItemsBlueId = inheritedAbBlueId(writer);
        Node source = writer.yamlToNode(
                "type:\n" +
                "  type: List\n" +
                "  mergePolicy: append-only\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + inheritedItemsBlueId + "\n" +
                "  - C");

        RoundTrip roundTrip = assertIndependentRoundTrip(writer, source);

        assertAnonymousListType(roundTrip.minimized.getType(), 2);
        assertEquals(inheritedItemsBlueId,
                roundTrip.minimized.getItems().get(0).getPreviousBlueId());
    }

    @Test
    void anonymousItemTypeRoundTripsAcrossIndependentBlueInstances() {
        Blue writer = new Blue();
        Node source = writer.yamlToNode(
                "type: List\n" +
                "itemType:\n" +
                "  type: Text\n" +
                "  schema:\n" +
                "    required: true\n" +
                "items:\n" +
                "  - A");

        RoundTrip roundTrip = assertIndependentRoundTrip(writer, source);

        assertNotNull(roundTrip.minimized.getItemType());
        assertNull(roundTrip.minimized.getItemType().getBlueId());
        assertNotNull(roundTrip.minimized.getItemType().getType());
        assertNotNull(roundTrip.minimized.getItemType().getSchema());
    }

    @Test
    void anonymousDictionaryKeyAndValueTypesRoundTripAcrossIndependentBlueInstances() {
        Blue writer = new Blue();
        Node source = writer.yamlToNode(
                "type: Dictionary\n" +
                "keyType:\n" +
                "  type: Text\n" +
                "  schema:\n" +
                "    required: true\n" +
                "valueType:\n" +
                "  type: Integer\n" +
                "  schema:\n" +
                "    required: true\n" +
                "answer: 42");

        RoundTrip roundTrip = assertIndependentRoundTrip(writer, source);

        assertInlineType(roundTrip.minimized.getKeyType());
        assertInlineType(roundTrip.minimized.getValueType());
    }

    @Test
    void nestedAnonymousAppendOnlyTypeRoundTripsAcrossIndependentBlueInstances() {
        Blue writer = new Blue();
        Node source = writer.yamlToNode(
                "nested:\n" +
                "  type:\n" +
                "    type: List\n" +
                "    mergePolicy: append-only\n" +
                "    items:\n" +
                "      - A\n" +
                "      - B\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B\n" +
                "    - C");

        RoundTrip roundTrip = assertIndependentRoundTrip(writer, source);

        assertAnonymousListType(roundTrip.minimized.getAsNode("/nested/type"), 2);
        assertEquals(2, roundTrip.minimized.getAsNode("/nested").getItems().size());
        assertEquals(inheritedAbBlueId(writer),
                roundTrip.minimized.getAsNode("/nested").getItems().get(0).getPreviousBlueId());
        assertEquals("C", roundTrip.minimized.getAsNode("/nested").getItems().get(1).getValue());
    }

    @Test
    void namedTypeRemainsAReferenceInTheMinimizedOverlay() {
        BasicNodeProvider writerProvider = providerWithNamedAppendOnlyType();
        BasicNodeProvider readerProvider = providerWithNamedAppendOnlyType();
        String typeBlueId = writerProvider.getBlueIdByName("Named Append Only List");
        Blue writer = new Blue(writerProvider);
        Node source = writer.yamlToNode(
                "type:\n" +
                "  blueId: " + typeBlueId + "\n" +
                "items:\n" +
                "  - A\n" +
                "  - B");

        ResolvedSnapshot original = writer.resolveToSnapshot(source);
        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());
        Blue reader = new Blue(readerProvider);
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(reader.jsonToNode(writer.nodeToJson(minimized)));

        assertEquals(typeBlueId, minimized.getType().getBlueId());
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(writer.nodeToJson(original.resolvedRoot()), reader.nodeToJson(reloaded.resolvedRoot()));
    }

    private static RoundTrip assertIndependentRoundTrip(Blue writer, Node source) {
        ResolvedSnapshot original = writer.resolveToSnapshot(source);
        String resolvedBefore = writer.nodeToJson(original.resolvedRoot());

        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());

        assertEquals(resolvedBefore, writer.nodeToJson(original.resolvedRoot()),
                "Minimization must not mutate the resolved snapshot.");

        Blue jsonReader = new Blue();
        ResolvedSnapshot fromJson = jsonReader.resolveToSnapshot(
                jsonReader.jsonToNode(writer.nodeToJson(minimized)));
        Blue yamlReader = new Blue();
        ResolvedSnapshot fromYaml = yamlReader.resolveToSnapshot(
                yamlReader.yamlToNode(writer.nodeToYaml(minimized)));

        assertEquals(original.blueId(), fromJson.blueId());
        assertEquals(original.blueId(), fromYaml.blueId());
        assertEquals(resolvedBefore, jsonReader.nodeToJson(fromJson.resolvedRoot()));
        assertEquals(resolvedBefore, yamlReader.nodeToJson(fromYaml.resolvedRoot()));
        return new RoundTrip(minimized);
    }

    private static void assertAnonymousListType(Node type, int inheritedItems) {
        assertInlineType(type);
        assertEquals("append-only", type.getMergePolicy());
        assertNotNull(type.getItems());
        assertEquals(inheritedItems, type.getItems().size());
    }

    private static void assertInlineType(Node type) {
        assertNotNull(type);
        assertNull(type.getBlueId(), "Anonymous types must remain inline.");
        assertNotNull(type.getType(), "The inline type must retain its own effective type.");
        assertFalse(type.isReferenceOnly());
    }

    private static BasicNodeProvider providerWithNamedAppendOnlyType() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Named Append Only List\n" +
                "type: List\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - A");
        return provider;
    }

    private static String inheritedAbBlueId(Blue blue) {
        Node inheritedList = blue.resolveToSnapshot(blue.yamlToNode(
                "type: List\n" +
                "items:\n" +
                "  - A\n" +
                "  - B")).resolvedRoot();
        return BlueIdCalculator.calculateBlueId(inheritedList.getItems());
    }

    private static final class RoundTrip {
        private final Node minimized;

        private RoundTrip(Node minimized) {
            this.minimized = minimized;
        }
    }
}
