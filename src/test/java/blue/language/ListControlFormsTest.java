package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.utils.Properties.LIST_MERGE_POLICY_APPEND_ONLY;
import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListControlFormsTest {

    @Test
    void appendOnlyListUsesPreviousAnchorForAppends() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - A\n" +
                "  - B");
        Node base = nodeProvider.getNodeByName("Base");
        String baseItemsBlueId = BlueIdCalculator.calculateBlueId(base.getItems());

        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + baseItemsBlueId + "\n" +
                "  - C");

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));

        assertEquals(LIST_MERGE_POLICY_APPEND_ONLY, resolved.getMergePolicy());
        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void standaloneListCanUsePreviousAnchorAsItsBase() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Node previous = new Blue(nodeProvider).yamlToNode(
                "items:\n" +
                "  - A\n" +
                "  - B");
        String previousBlueId = BlueIdCalculator.calculateBlueId(previous.getItems());
        nodeProvider.addListAndItsItems(previous.getItems());

        Node next = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + previousBlueId + "\n" +
                "  - C", Node.class);

        Node resolved = new Blue(nodeProvider).resolve(next);

        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void previousAnchorMustMatchInheritedList() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: staleHash\n" +
                "  - B");

        assertThrows(IllegalArgumentException.class,
                () -> new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived")));
    }

    @Test
    void appendOnlyListRejectsPositionalOverlay() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - A");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: B");

        assertThrows(IllegalArgumentException.class,
                () -> new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived")));
    }

    @Test
    void appendOnlyListRejectsChangedInheritedPrefixWithoutPreviousAnchor() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - A");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - B");

        assertThrows(IllegalArgumentException.class,
                () -> new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived")));
    }

    @Test
    void inheritedMergePolicyCannotBeChangedBySubtype() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - A");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "mergePolicy: positional\n" +
                "items:\n" +
                "  - A");

        assertThrows(IllegalArgumentException.class,
                () -> new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived")));
    }

    @Test
    void positionalListOverlaysInheritedIndexAndAppendsNormalItems() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $empty: true\n" +
                "  - B");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: A\n" +
                "  - C");

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));

        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void positionalListWithoutInheritedItemsAcceptsContiguousPositions() {
        Node node = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: A\n" +
                "  - $pos: 1\n" +
                "    value: B\n" +
                "  - C", Node.class);

        Node resolved = new Blue().resolve(node);

        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void positionalListWithoutInheritedItemsRejectsPositionGaps() {
        Node node = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $pos: 1\n" +
                "    value: B", Node.class);

        assertThrows(IllegalArgumentException.class, () -> new Blue().resolve(node));
    }

    @Test
    void positionalObjectOverlayReplacesEmptyPlaceholder() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $empty: true");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    name: Real item\n" +
                "    x: A");

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));
        Node item = resolved.getItems().get(0);

        assertEquals("Real item", item.getName());
        assertEquals("A", item.getProperties().get("x").getValue());
        assertFalse(item.getProperties().containsKey("$empty"));
    }

    @Test
    void falseEmptyPropertyIsNotTreatedAsPlaceholder() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $empty: false");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    x: A");

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));
        Node item = resolved.getItems().get(0);

        assertEquals(false, item.getProperties().get("$empty").getValue());
        assertEquals("A", item.getProperties().get("x").getValue());
    }

    @Test
    void positionalOverlayCanRefineInheritedItemType() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: A");
        nodeProvider.addSingleDocs(
                "name: B\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("A"));
        nodeProvider.addSingleDocs(
                "name: C\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("B"));
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("B"));
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("C"));

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));

        assertEquals("C", resolved.getItems().get(0).getType().getName());
    }

    @Test
    void positionalListCanOverlayNonZeroInheritedIndex() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A\n" +
                "  - $empty: true");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 1\n" +
                "    value: B");

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));

        assertEquals(Arrays.asList("A", "B"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue()));
    }

    @Test
    void previousAnchorCanBeCombinedWithPositionalOverlayAndAppend() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A\n" +
                "  - $empty: true");
        Node base = nodeProvider.getNodeByName("Base");
        String baseItemsBlueId = BlueIdCalculator.calculateBlueId(base.getItems());
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + baseItemsBlueId + "\n" +
                "  - $pos: 1\n" +
                "    value: B\n" +
                "  - C");

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));

        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void listHashAcceptsSparsePositionControlsForProviderIngestion() {
        String sparsePosition = "items:\n" +
                "  - $pos: 1\n" +
                "    value: B";

        BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(sparsePosition, Node.class));
        // nothing should be thrown
    }

    @Test
    void positionalListRejectsDuplicatePosition() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A");
        Node derived = YAML_MAPPER.readValue(
                "name: Derived\n" +
                        "type:\n" +
                        "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                        "items:\n" +
                        "  - $pos: 0\n" +
                        "    value: B\n" +
                        "  - $pos: 0\n" +
                        "    value: C", Node.class);

        assertThrows(IllegalArgumentException.class,
                () -> new Blue(nodeProvider).resolve(derived));
    }

    @Test
    void positionalListRejectsOutOfRangePosition() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A");
        Node derived = YAML_MAPPER.readValue(
                "name: Derived\n" +
                        "type:\n" +
                        "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                        "items:\n" +
                        "  - $pos: 1\n" +
                        "    value: B", Node.class);

        assertThrows(IllegalArgumentException.class,
                () -> new Blue(nodeProvider).resolve(derived));
    }

    @Test
    void listControlsRequireListType() {
        Node node = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: A", Node.class);

        assertThrows(IllegalArgumentException.class, () -> new Blue().resolve(node));
    }
}
