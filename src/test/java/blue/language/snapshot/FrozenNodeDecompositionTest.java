package blue.language.snapshot;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FrozenNodeDecompositionTest {

    @Test
    void shouldKeepResolvedListIdentityEqualToTheMutableCompatibilityOracle() {
        // given
        String provenanceBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("provenance"));
        Node resolved = new Node().items(Arrays.asList(
                new Node()
                        .blueId(provenanceBlueId)
                        .properties("content", new Node().value("first")),
                new Node().items(Arrays.asList(
                        new Node().value("nested-first"),
                        new Node().value("nested-second"))),
                new Node().value("third")));
        FrozenNode frozen = FrozenNode.fromResolvedNode(resolved);
        List<Node> canonicalItems = new ArrayList<>();
        for (Node item : resolved.getItems()) {
            canonicalItems.add(NodeToBlueIdInput
                    .stripResolvedBlueIdMetadata(item.clone()));
        }
        String listBlueId = DirectBlueIdCalculator.calculateBlueId(canonicalItems);
        Map<String, Object> expectedInput = new LinkedHashMap<>();
        expectedInput.put(
                OBJECT_ITEMS,
                Collections.singletonMap(OBJECT_BLUE_ID, listBlueId));

        // when
        String actual = frozen.blueId();

        // then
        assertEquals(
                DirectBlueIdCalculator.INSTANCE.directBlueIdFromCanonicalInput(expectedInput),
                actual);
    }

    @Test
    void shouldPreserveNestedStructuralKeyCompatibilityType() {
        // given
        FrozenNode frozen = FrozenNode.fromResolvedNode(
                new Node().properties("value", new Node().value("content")));

        // when
        FrozenNode.ResolvedStructuralKey compatibilityKey =
                frozen.resolvedStructuralKey();
        FrozenNodeStructuralKey focusedKey = compatibilityKey.delegate();

        // then
        assertEquals(new FrozenNodeStructuralKey(frozen), focusedKey);
    }

    @Test
    void shouldDelegateNavigationWithoutCopyingAddressedFrozenNodes() {
        // given
        FrozenNode root = FrozenNode.fromNode(new Node().properties(
                "nested",
                new Node().properties("value", new Node().value("content"))));
        FrozenNode expected = root.getProperties()
                .get("nested")
                .getProperties()
                .get("value");

        // when
        FrozenNode throughFacade = root.at("/nested/value");
        FrozenNode throughService = FrozenNodeNavigator.INSTANCE.at(
                root,
                "/nested/value");

        // then
        assertSame(expected, throughFacade);
        assertSame(expected, throughService);
    }
}
