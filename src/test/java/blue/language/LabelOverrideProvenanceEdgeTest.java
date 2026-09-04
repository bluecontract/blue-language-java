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
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.merge.Merger;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static blue.language.resolve.ResolutionLimits.NO_LIMITS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LabelOverrideProvenanceEdgeTest {

    @Test
    void shouldResolvedInlineDeclarationRemainsOverridableInPublicMerge() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Blue blue = new Blue(provider);
        Node detailDeclaration = new Node().properties(
                "field", new Node().type(reference(TEXT_TYPE_BLUE_ID)));
        Node holderDeclaration = new Node().properties(
                "item", new Node()
                        .name("Generic Item")
                        .type(detailDeclaration));
        Node target = blue.resolve(new Node().type(holderDeclaration));
        Node overlay = new Node().properties(
                "item", new Node()
                        .name("Specific Item")
                        .properties("field", new Node().value("x")));
        // when

        // then

        assertDoesNotThrow(() -> new Merger(blue.getMergingProcessor(), provider)
                .merge(target, overlay, NO_LIMITS));

        assertEquals("Specific Item", target.getAsNode("/item").getName());
        assertEquals("x", target.getAsText("/item/field"));
    }

    @Test
    void shouldEmptyStringPropertyKeyDoesNotCollideWithTheRootPath() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Empty Key Detail")
                .properties("field", new Node().type(reference(TEXT_TYPE_BLUE_ID))));
        String detailId = provider.getBlueIdByName("Empty Key Detail");
        provider.addSingleNodes(new Node()
                .name("Empty Key Holder")
                .properties("", new Node()
                        .name("Generic Item")
                        .type(reference(detailId))));
        String holderId = provider.getBlueIdByName("Empty Key Holder");
        Node source = new Node()
                .type(reference(holderId))
                .properties("", new Node()
                        .name("Specific Item")
                        .properties("field", new Node().value("x")));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> new Blue(provider).resolve(source));

        Node item = resolved.getProperties().get("");
        assertEquals("Specific Item", item.getName());
        assertEquals("x", item.getAsText("/field"));
    }

    @Test
    void shouldNestedResolvedDerivedDeclarationCanReplaceItsBaseLabel() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Leaf Shape")
                .type(reference(TEXT_TYPE_BLUE_ID)));
        String leafId = provider.getBlueIdByName("Leaf Shape");
        provider.addSingleNodes(new Node()
                .name("Base Detail")
                .properties("field", new Node()
                        .name("Base Field")
                        .type(reference(leafId))));
        String baseDetailId = provider.getBlueIdByName("Base Detail");
        provider.addSingleNodes(new Node()
                .name("Derived Detail")
                .type(reference(baseDetailId))
                .properties("field", new Node().name("Derived Field")));
        String derivedDetailId = provider.getBlueIdByName("Derived Detail");
        provider.addSingleNodes(new Node()
                .name("Nested Detail Holder")
                .properties("item", new Node().type(reference(baseDetailId))));
        String holderId = provider.getBlueIdByName("Nested Detail Holder");
        Node source = new Node()
                .type(reference(holderId))
                .properties("item", new Node().type(reference(derivedDetailId)));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> new Blue(provider).resolve(source));

        assertEquals("Derived Field", resolved.getAsNode("/item/field").getName());
    }

    @Test
    void shouldDeepTypeAncestryDoesNotOverflowTheLabelScanner() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Terminal Holder Type")
                .properties(
                "item", new Node()
                        .name("Generic Item")
                        .type(reference(TEXT_TYPE_BLUE_ID))));
        String deepTypeId = provider.getBlueIdByName("Terminal Holder Type");
        for (int depth = 0; depth < 30_000; depth++) {
            String typeName = "Type Layer " + depth;
            provider.addSingleNodes(new Node()
                    .name(typeName)
                    .type(reference(deepTypeId)));
            deepTypeId = provider.getBlueIdByName(typeName);
        }
        Node target = new Node()
                .type(reference(deepTypeId))
                .properties("item", new Node()
                        .name("Generic Item")
                        .type(reference(TEXT_TYPE_BLUE_ID)));
        Node overlay = new Node().properties(
                "item", new Node().name("Specific Item"));
        Blue blue = new Blue(provider);
        // when

        // then

        assertDoesNotThrow(() -> new Merger(
                blue.getMergingProcessor(), provider).merge(
                target, overlay, ResolutionLimits.withSinglePath("/item")));

        assertEquals("Specific Item", target.getAsNode("/item").getName());
    }

    @Test
    void shouldPurePositionDeclarationLayerRemainsOverridable() {
        // given
        BasicNodeProvider provider = positionalDeclarationProvider(1);
        Blue blue = new Blue(provider);
        String baseId = provider.getBlueIdByName("Positional Base Holder");
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  type:",
                "    blueId: " + baseId,
                "  entries:",
                "    items:",
                "      - $pos: 0",
                "        name: Revised Generic Item",
                "entries:",
                "  items:",
                "    - $pos: 0",
                "      name: Specific Item",
                "      field: x"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> blue.resolve(source));

        assertEquals("Specific Item", resolved.getAsNode("/entries").getItems().get(0).getName());
    }

    @Test
    void shouldMixedPositionAndAppendDeclarationUsesTheAppendedEffectivePosition() {
        // given
        BasicNodeProvider provider = positionalDeclarationProvider(3);
        Blue blue = new Blue(provider);
        String baseId = provider.getBlueIdByName("Positional Base Holder");
        String detailId = provider.getBlueIdByName("Positional Detail");
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  type:",
                "    blueId: " + baseId,
                "  entries:",
                "    items:",
                "      - $pos: 0",
                "        name: Revised Generic Item",
                "      - name: Appended Generic Item",
                "        type:",
                "          blueId: " + detailId,
                "entries:",
                "  items:",
                "    - $pos: 3",
                "      name: Specific Appended Item",
                "      field: x"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> blue.resolve(source));

        assertEquals("Specific Appended Item",
                resolved.getAsNode("/entries").getItems().get(3).getName());
    }

    @Test
    void shouldPositionalReplacementResetsFixedProvenanceAtTheReplacedPosition() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Replacement Detail",
                "field:",
                "  type: Text"));
        String detailId = provider.getBlueIdByName("Replacement Detail");
        provider.addSingleDocs(String.join("\n",
                "name: Replacement Base Holder",
                "entries:",
                "  type: List",
                "  items:",
                "    - name: Base Fixed Item",
                "      old: x"));
        String baseId = provider.getBlueIdByName("Replacement Base Holder");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  type:",
                "    blueId: " + baseId,
                "  entries:",
                "    items:",
                "      - $pos: 0",
                "        $replace:",
                "          name: Replacement Generic Item",
                "          type:",
                "            blueId: " + detailId,
                "entries:",
                "  items:",
                "    - $pos: 0",
                "      name: Specific Item",
                "      field: y"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> blue.resolve(source));

        Node item = resolved.getAsNode("/entries").getItems().get(0);
        assertEquals("Specific Item", item.getName());
        assertEquals("y", item.getAsText("/field"));
    }

    @Test
    void shouldReplacingAnEmptyPlaceholderResetsItsProvenance() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Empty Replacement Detail",
                "field:",
                "  type: Text"));
        String detailId = provider.getBlueIdByName("Empty Replacement Detail");
        provider.addSingleDocs(String.join("\n",
                "name: Empty Replacement Holder",
                "entries:",
                "  type: List",
                "  items:",
                "    - $empty: true"));
        String baseId = provider.getBlueIdByName("Empty Replacement Holder");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  type:",
                "    blueId: " + baseId,
                "  entries:",
                "    items:",
                "      - $pos: 0",
                "        name: Replacement Generic Item",
                "        type:",
                "          blueId: " + detailId,
                "entries:",
                "  items:",
                "    - $pos: 0",
                "      name: Specific Item",
                "      field: y"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> blue.resolve(source));

        assertEquals("Specific Item", resolved.getAsNode("/entries").getItems().get(0).getName());
    }

    @Test
    void shouldReplacementStillInheritsTheFixedTypeOfItsPosition() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Fixed Replacement Detail",
                "field:",
                "  type: Text",
                "hidden: fixed"));
        String fixedDetailId = provider.getBlueIdByName("Fixed Replacement Detail");
        provider.addSingleDocs(String.join("\n",
                "name: Fixed Replacement Holder",
                "entries:",
                "  type: List",
                "  items:",
                "    - name: Base Fixed Item",
                "      type:",
                "        blueId: " + fixedDetailId));
        String baseId = provider.getBlueIdByName("Fixed Replacement Holder");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  type:",
                "    blueId: " + baseId,
                "  entries:",
                "    items:",
                "      - $pos: 0",
                "        $replace:",
                "          name: Replacement Item",
                "entries:",
                "  items:",
                "    - $pos: 0",
                "      name: Illegal Item",
                "      field: y"));
        // when

        // then

        assertFixedValueConflict(() -> blue.resolve(source));
    }

    @Test
    void shouldAppendedFixedDescendantPreventsRelabelingWithFullOrLimitedResolution() {
        // given
        BasicNodeProvider provider = positionalDeclarationProvider(1);
        Blue blue = new Blue(provider);
        String baseId = provider.getBlueIdByName("Positional Base Holder");
        String detailId = provider.getBlueIdByName("Positional Detail");
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  type:",
                "    blueId: " + baseId,
                "  entries:",
                "    items:",
                "      - $pos: 0",
                "        name: Revised Generic Item",
                "      - name: Fixed Appended Item",
                "        type:",
                "          blueId: " + detailId,
                "        hidden: fixed",
                "entries:",
                "  items:",
                "    - $pos: 1",
                "      name: Illegal Item",
                "      field: x"));
        // when

        // then

        assertFixedValueConflict(() -> blue.resolve(source.clone()));
        assertFixedValueConflict(() -> blue.resolve(
                source.clone(), limitedSecondEntryField()));
    }

    @Test
    void shouldPreviousAnchorAppendDeclarationRemainsOverridable() {
        // given
        BasicNodeProvider provider = previousAppendProvider(false);
        Blue blue = new Blue(provider);
        String derivedId = provider.getBlueIdByName("Previous Derived Holder");
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + derivedId,
                "entries:",
                "  items:",
                "    - $pos: 1",
                "      name: Specific Appended Item",
                "      field: x"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> blue.resolve(source));

        assertEquals("Specific Appended Item",
                resolved.getAsNode("/entries").getItems().get(1).getName());
    }

    @Test
    void shouldPreviousAnchorFixedAppendPreventsRelabelingWithFullOrLimitedResolution() {
        // given
        BasicNodeProvider provider = previousAppendProvider(true);
        Blue blue = new Blue(provider);
        String derivedId = provider.getBlueIdByName("Previous Derived Holder");
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + derivedId,
                "entries:",
                "  items:",
                "    - $pos: 1",
                "      name: Illegal Appended Item",
                "      field: x"));
        // when

        // then

        assertFixedValueConflict(() -> blue.resolve(source.clone()));
        assertFixedValueConflict(() -> blue.resolve(
                source.clone(), limitedSecondEntryField()));
    }

    @Test
    void shouldFixedItemTypePreventsPlainAndPositionedRelabeling() {
        // given
        Blue blue = new Blue();
        Node holderType = blue.yamlToNode(String.join("\n",
                "name: Fixed ItemType Holder",
                "entries:",
                "  type: List",
                "  itemType:",
                "    fixed: value",
                "  items:",
                "    - name: Generic Item"));
        Node plain = blue.yamlToNode(String.join("\n",
                "entries:",
                "  items:",
                "    - name: Illegal Plain Item"))
                .type(holderType.clone());
        Node positioned = blue.yamlToNode(String.join("\n",
                "entries:",
                "  items:",
                "    - $pos: 0",
                "      name: Illegal Positioned Item"))
                .type(holderType.clone());
        // when

        // then

        assertFixedValueConflict(() -> blue.resolve(plain));
        assertFixedValueConflict(() -> blue.resolve(positioned));
    }

    private static BasicNodeProvider positionalDeclarationProvider(int itemCount) {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Positional Detail",
                "field:",
                "  type: Text"));
        String detailId = provider.getBlueIdByName("Positional Detail");
        StringBuilder holder = new StringBuilder()
                .append("name: Positional Base Holder\n")
                .append("entries:\n")
                .append("  type: List\n")
                .append("  items:\n");
        for (int index = 0; index < itemCount; index++) {
            holder.append("    - name: Generic Item ").append(index).append('\n')
                    .append("      type:\n")
                    .append("        blueId: ").append(detailId).append('\n');
        }
        provider.addSingleDocs(holder.toString());
        return provider;
    }

    private static BasicNodeProvider previousAppendProvider(boolean fixedAppend) {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Positional Detail",
                "field:",
                "  type: Text"));
        provider.addSingleDocs(String.join("\n",
                "name: Previous Base Holder",
                "entries:",
                "  type: List",
                "  items:",
                "    - base"));
        Node base = provider.getNodeByName("Previous Base Holder");
        List<Node> baseItems = base.getAsNode("/entries").getItems();
        String previousId = DirectBlueIdCalculator.calculateBlueId(baseItems);
        String detailId = provider.getBlueIdByName("Positional Detail");
        String appendedContent = fixedAppend
                ? "      hidden: fixed\n"
                : "";
        provider.addSingleDocs(String.join("\n",
                "name: Previous Derived Holder",
                "type:",
                "  blueId: " + provider.getBlueIdByName("Previous Base Holder"),
                "entries:",
                "  items:",
                "    - $previous:",
                "        blueId: " + previousId,
                "    - name: Appended Generic Item",
                "      type:",
                "        blueId: " + detailId,
                appendedContent));
        return provider;
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static ResolutionLimits limitedSecondEntryField() {
        return ResolutionLimits.builder()
                .addPath("/entries/0")
                .addPath("/entries/1/field")
                .build();
    }

    private static void assertFixedValueConflict(Executable executable) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, executable::execute);
        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(failure), failure.getMessage());
    }

    @FunctionalInterface
    private interface Executable {
        void execute();
    }
}
