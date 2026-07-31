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
    void shouldRoundTripAnonymousAppendOnlyTypeAcrossIndependentBlueInstances() {
        // given
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
                "  - C");

        // when
        RoundTrip roundTrip = independentRoundTrip(writer, source);

        // then
        assertIndependentRoundTrip(roundTrip);
        assertAnonymousListType(roundTrip.minimized.getType(), 2);
        assertEquals(inheritedItemsBlueId,
                roundTrip.minimized.getItems().get(0).getPreviousBlueId());
        assertEquals("C", roundTrip.minimized.getItems().get(1).getValue());
    }

    @Test
    void shouldRoundTripExistingPreviousAnchorWithoutRuntimeLocalTypeStorage() {
        // given
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

        // when
        RoundTrip roundTrip = independentRoundTrip(writer, source);

        // then
        assertIndependentRoundTrip(roundTrip);
        assertAnonymousListType(roundTrip.minimized.getType(), 2);
        assertEquals(inheritedItemsBlueId,
                roundTrip.minimized.getItems().get(0).getPreviousBlueId());
    }

    @Test
    void shouldRoundTripAnonymousItemTypeAcrossIndependentBlueInstances() {
        // given
        Blue writer = new Blue();
        Node source = writer.yamlToNode(
                "type: List\n" +
                "itemType:\n" +
                "  type: Text\n" +
                "  schema:\n" +
                "    required: true\n" +
                "items:\n" +
                "  - A");

        // when
        RoundTrip roundTrip = independentRoundTrip(writer, source);

        // then
        assertIndependentRoundTrip(roundTrip);
        assertNotNull(roundTrip.minimized.getItemType());
        assertNull(roundTrip.minimized.getItemType().getBlueId());
        assertNotNull(roundTrip.minimized.getItemType().getType());
        assertNotNull(roundTrip.minimized.getItemType().getSchema());
    }

    @Test
    void shouldRoundTripAnonymousDictionaryTypesAcrossIndependentBlueInstances() {
        // given
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

        // when
        RoundTrip roundTrip = independentRoundTrip(writer, source);

        // then
        assertIndependentRoundTrip(roundTrip);
        assertInlineType(roundTrip.minimized.getKeyType());
        assertInlineType(roundTrip.minimized.getValueType());
    }

    @Test
    void shouldRoundTripNestedAnonymousAppendOnlyTypeAcrossIndependentBlueInstances() {
        // given
        Blue writer = new Blue();
        String inheritedItemsBlueId = inheritedAbBlueId(writer);
        Node source = writer.yamlToNode(
                "nested:\n" +
                "  type:\n" +
                "    type: List\n" +
                "    mergePolicy: append-only\n" +
                "    items:\n" +
                "      - A\n" +
                "      - B\n" +
                "  items:\n" +
                "    - C");

        // when
        RoundTrip roundTrip = independentRoundTrip(writer, source);

        // then
        assertIndependentRoundTrip(roundTrip);
        assertAnonymousListType(roundTrip.minimized.getAsNode("/nested/type"), 2);
        assertEquals(2, roundTrip.minimized.getAsNode("/nested").getItems().size());
        assertEquals(inheritedItemsBlueId,
                roundTrip.minimized.getAsNode("/nested").getItems().get(0).getPreviousBlueId());
        assertEquals("C", roundTrip.minimized.getAsNode("/nested").getItems().get(1).getValue());
    }

    @Test
    void shouldKeepNamedTypeAsReferenceInMinimizedOverlay() {
        // given
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
        Blue reader = new Blue(readerProvider);

        // when
        ResolvedSnapshot original = writer.resolveToSnapshot(source);
        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(reader.jsonToNode(writer.nodeToJson(minimized)));

        // then
        assertEquals(typeBlueId, minimized.getType().getBlueId());
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(writer.nodeToJson(original.resolvedRoot()), reader.nodeToJson(reloaded.resolvedRoot()));
    }

    private static RoundTrip independentRoundTrip(Blue writer, Node source) {
        ResolvedSnapshot original = writer.resolveToSnapshot(source);
        String resolvedBefore = writer.nodeToJson(original.resolvedRoot());

        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());
        String resolvedAfter = writer.nodeToJson(original.resolvedRoot());

        Blue jsonReader = new Blue();
        ResolvedSnapshot fromJson = jsonReader.resolveToSnapshot(
                jsonReader.jsonToNode(writer.nodeToJson(minimized)));
        Blue yamlReader = new Blue();
        ResolvedSnapshot fromYaml = yamlReader.resolveToSnapshot(
                yamlReader.yamlToNode(writer.nodeToYaml(minimized)));

        return new RoundTrip(
                minimized,
                original.blueId(),
                fromJson.blueId(),
                fromYaml.blueId(),
                resolvedBefore,
                resolvedAfter,
                jsonReader.nodeToJson(fromJson.resolvedRoot()),
                yamlReader.nodeToJson(fromYaml.resolvedRoot()));
    }

    private static void assertIndependentRoundTrip(RoundTrip roundTrip) {
        assertEquals(
                roundTrip.resolvedBefore,
                roundTrip.resolvedAfter,
                "Minimization must not mutate the resolved snapshot.");
        assertEquals(roundTrip.originalBlueId, roundTrip.fromJsonBlueId);
        assertEquals(roundTrip.originalBlueId, roundTrip.fromYamlBlueId);
        assertEquals(roundTrip.resolvedBefore, roundTrip.fromJsonResolved);
        assertEquals(roundTrip.resolvedBefore, roundTrip.fromYamlResolved);
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
        private final String originalBlueId;
        private final String fromJsonBlueId;
        private final String fromYamlBlueId;
        private final String resolvedBefore;
        private final String resolvedAfter;
        private final String fromJsonResolved;
        private final String fromYamlResolved;

        private RoundTrip(
                Node minimized,
                String originalBlueId,
                String fromJsonBlueId,
                String fromYamlBlueId,
                String resolvedBefore,
                String resolvedAfter,
                String fromJsonResolved,
                String fromYamlResolved) {
            this.minimized = minimized;
            this.originalBlueId = originalBlueId;
            this.fromJsonBlueId = fromJsonBlueId;
            this.fromYamlBlueId = fromYamlBlueId;
            this.resolvedBefore = resolvedBefore;
            this.resolvedAfter = resolvedAfter;
            this.fromJsonResolved = fromJsonResolved;
            this.fromYamlResolved = fromYamlResolved;
        }
    }
}
