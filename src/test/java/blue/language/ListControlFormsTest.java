package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.LIST_MERGE_POLICY_APPEND_ONLY;
import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ListControlFormsTest {

    @Test
    void shouldUsePreviousAnchorForAppendOnlyListAppends() {
        // given
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
        String baseItemsBlueId = DirectBlueIdCalculator.calculateBlueId(base.getItems());

        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + baseItemsBlueId + "\n" +
                "  - C");

        // when
        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));

        // then
        assertEquals(LIST_MERGE_POLICY_APPEND_ONLY, resolved.getMergePolicy());
        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void shouldAllowStandaloneListToUsePreviousAnchorAsBase() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Node previous = new Blue(nodeProvider).yamlToNode(
                "items:\n" +
                "  - A\n" +
                "  - B");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(previous.getItems());
        nodeProvider.addListAndItsItems(previous.getItems());

        Node next = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + previousBlueId + "\n" +
                "  - C", Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(next);

        // then
        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void shouldRequirePreviousAnchorToMatchInheritedList() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        String wrongButValidBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().value("stale"));
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
                "      blueId: " + wrongButValidBlueId + "\n" +
                "  - B");

        // when
        Throwable failure = captureFailure(
                () -> new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived")));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldRejectPositionalOverlayForAppendOnlyList() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - A");
        Node derived = YAML_MAPPER.readValue(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: B", Node.class);

        // when
        Throwable failure = captureFailure(() -> new Blue(nodeProvider).resolve(derived));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldAppendNormalItemsWithoutRequiringPreviousAnchor() {
        // given
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

        // when
        Node resolved = new Blue(nodeProvider)
                .resolve(nodeProvider.getNodeByName("Derived"));

        // then
        assertEquals(Arrays.asList("A", "B"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue()));
    }

    @Test
    void shouldPreventSubtypeFromChangingInheritedMergePolicy() {
        // given
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

        // when
        Throwable failure = captureFailure(
                () -> new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived")));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldOverlayInheritedIndexAndAppendNormalItemsForPositionalList() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $empty: true\n" +
                "  - B");
        Node derived = YAML_MAPPER.readValue(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: A\n" +
                "  - C", Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(derived);

        // then
        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void shouldAcceptContiguousPositionsForPositionalListWithoutInheritedItems() {
        // given
        Node node = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: A\n" +
                "  - $pos: 1\n" +
                "    value: B\n" +
                "  - C", Node.class);

        // when
        Node resolved = new Blue().resolve(node);

        // then
        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void shouldRejectPositionGapsForPositionalListWithoutInheritedItems() {
        // given
        Node node = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $pos: 1\n" +
                "    value: B", Node.class);

        // when
        Throwable failure = captureFailure(() -> new Blue().resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldReplaceEmptyPlaceholderWithPositionalObjectOverlay() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $empty: true");
        Node derived = YAML_MAPPER.readValue(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    name: Real item\n" +
                "    x: A", Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(derived);
        Node item = resolved.getItems().get(0);

        // then
        assertEquals("Real item", item.getName());
        assertEquals("A", item.getProperties().get("x").getValue());
        assertFalse(item.getProperties().containsKey("$empty"));
    }

    @Test
    void shouldRejectMalformedEmptyPlaceholder() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        String malformedPlaceholder =
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - $empty: false";

        // when
        Throwable failure = captureFailure(
                () -> nodeProvider.addSingleDocs(malformedPlaceholder));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldAllowPositionalOverlayToRefineInheritedItemType() {
        // given
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
        Node derived = YAML_MAPPER.readValue(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("C"), Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(derived);

        // then
        assertEquals("C", resolved.getItems().get(0).getType().getName());
    }

    @Test
    void shouldAllowPositionalListToOverlayNonZeroInheritedIndex() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A\n" +
                "  - $empty: true");
        Node derived = YAML_MAPPER.readValue(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 1\n" +
                "    value: B", Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(derived);

        // then
        assertEquals(Arrays.asList("A", "B"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue()));
    }

    @Test
    void shouldCombinePreviousAnchorWithPositionalOverlayAndAppend() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A\n" +
                "  - $empty: true");
        Node base = nodeProvider.getNodeByName("Base");
        String baseItemsBlueId = DirectBlueIdCalculator.calculateBlueId(base.getItems());
        Node derived = YAML_MAPPER.readValue(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + baseItemsBlueId + "\n" +
                "  - $pos: 1\n" +
                "    value: B\n" +
                "  - C", Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(derived);

        // then
        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                resolved.getItems().get(0).getValue(),
                resolved.getItems().get(1).getValue(),
                resolved.getItems().get(2).getValue()));
    }

    @Test
    void shouldRejectSparsePositionControlsDuringDirectListHashing() {
        // given
        String sparsePosition = "items:\n" +
                "  - $pos: 1\n" +
                "    value: B";
        Node sparseList = YAML_MAPPER.readValue(sparsePosition, Node.class);

        // when
        Throwable failure = captureFailure(
                () -> DirectBlueIdCalculator.calculateBlueId(sparseList));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldRejectDuplicatePositionInPositionalList() {
        // given
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

        // when
        Throwable failure = captureFailure(() -> new Blue(nodeProvider).resolve(derived));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldReplaceInheritedObjectWithPosReplace() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - inherited: yes\n");
        Node derived = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    $replace:\n" +
                "      replacement: replaced", Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(derived);

        // then
        assertFalse(resolved.getItems().get(0).getProperties().containsKey("inherited"));
        assertEquals("replaced", resolved.getItems().get(0).getAsText("/replacement"));
    }

    @Test
    void shouldReplaceInheritedListWithPosReplace() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - items:\n" +
                "      - A\n");
        Node derived = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    $replace:\n" +
                "      items:\n" +
                "        - B\n" +
                "        - C", Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(derived);

        // then
        assertEquals(Arrays.asList("B", "C"), Arrays.asList(
                resolved.getItems().get(0).getItems().get(0).getValue(),
                resolved.getItems().get(0).getItems().get(1).getValue()));
    }

    @Test
    void shouldReplaceInheritedReferenceWithPosReplace() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: Referenced\nvalue: R");
        String referenceBlueId = nodeProvider.getBlueIdByName("Referenced");
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A\n");
        Node derived = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    $replace:\n" +
                "      blueId: " + referenceBlueId, Node.class);

        // when
        Node resolved = new Blue(nodeProvider).resolve(derived);

        // then
        assertEquals(null, resolved.getItems().get(0).getValue());
        assertEquals(referenceBlueId, resolved.getItems().get(0).getBlueId());
    }

    @Test
    void shouldSupportScalarValueShorthandAndRejectCollectionValues() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A");
        Node scalarOverlay = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: B", Node.class);
        String objectValue = "items:\n" +
                "  - $pos: 0\n" +
                "    value:\n" +
                "      x: y";
        String listValue = "items:\n" +
                "  - $pos: 0\n" +
                "    value:\n" +
                "      - A";

        // when
        Node resolved = new Blue(nodeProvider).resolve(scalarOverlay);
        Throwable objectValueFailure = captureFailure(
                () -> YAML_MAPPER.readValue(objectValue, Node.class));
        Throwable listValueFailure = captureFailure(
                () -> YAML_MAPPER.readValue(listValue, Node.class));

        // then
        assertEquals("B", resolved.getItems().get(0).getValue());
        assertInstanceOf(RuntimeException.class, objectValueFailure);
        assertInstanceOf(RuntimeException.class, listValueFailure);
    }

    @Test
    void shouldRejectMapOverlayOnInheritedScalarItem() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "type:\n" +
                "  blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "items:\n" +
                "  - A");
        Node objectOverlay = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "items:\n" +
                "  - $pos: 0\n" +
                "    x: B", Node.class);

        // when
        Throwable failure = captureFailure(() -> new Blue(nodeProvider).resolve(objectOverlay));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldRejectReplaceWithoutPosAndReplaceWithSiblingOverlay() {
        // given
        String replaceWithoutPosition = "items:\n" +
                "  - $replace:\n" +
                "      value: A";
        String replaceWithSibling = "items:\n" +
                "  - $pos: 0\n" +
                "    $replace:\n" +
                "      value: A\n" +
                "    sibling: B";

        // when
        Throwable missingPositionFailure = captureFailure(
                () -> YAML_MAPPER.readValue(replaceWithoutPosition, Node.class));
        Throwable siblingFailure = captureFailure(
                () -> YAML_MAPPER.readValue(replaceWithSibling, Node.class));

        // then
        assertInstanceOf(RuntimeException.class, missingPositionFailure);
        assertInstanceOf(RuntimeException.class, siblingFailure);
    }

    @Test
    void shouldRejectOutOfRangePositionInPositionalList() {
        // given
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

        // when
        Throwable failure = captureFailure(() -> new Blue(nodeProvider).resolve(derived));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldRequireListTypeForListControls() {
        // given
        Node node = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: A", Node.class);

        // when
        Throwable failure = captureFailure(() -> new Blue().resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }
}
