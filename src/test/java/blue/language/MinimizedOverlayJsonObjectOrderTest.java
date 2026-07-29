package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.merge.Merger;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.MinimizedOverlayBuilder;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.limits.PathLimits;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.limits.Limits.NO_LIMITS;

class MinimizedOverlayJsonObjectOrderTest {

    @Test
    void shouldConflictingLabelOnInheritedFixedValueIsRejected() {
        // given
        BasicNodeProvider provider = fixedValueProvider();
        Blue blue = new Blue(provider);
        String fixedHolderType = provider.getBlueIdByName("Fixed City Holder");
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + fixedHolderType,
                "city:",
                "  name: Location",
                "  value: Warsaw"));
        // when

        // then

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> blue.resolveToSnapshot(source));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(failure));
    }

    @Test
    void shouldLabelMissingFromInheritedFixedValueCanBeAddedAndColdReloaded() throws Exception {
        // given
        BasicNodeProvider writerProvider = fixedValueProvider();
        Blue writer = new Blue(writerProvider);
        String holderType = writerProvider.getBlueIdByName("Unlabeled Fixed City Holder");
        Node source = writer.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + holderType,
                "city:",
                "  name: Location",
                "  description: Instance city label.",
                "  value: Warsaw"));
        ResolvedSnapshot original = writer.resolveToSnapshot(source);
        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());
        String reorderedJson = reorderAsJsonObjectStore(writer.nodeToJson(minimized));

        BasicNodeProvider readerProvider = fixedValueProvider();
        Blue reader = new Blue(readerProvider);
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(reader.jsonToNode(reorderedJson));

        Node resolvedCity = original.resolvedRoot().getProperties().get("city");
        // when

        // then
        assertEquals("Location", resolvedCity.getName());
        assertEquals("Instance city label.",
                resolvedCity.getDescription());
        assertEquals(original.blueId(), reloaded.blueId());
    }

    @Test
    void shouldInheritedPureReferenceRejectsLabelOverlay() {
        // given
        BasicNodeProvider provider = fixedValueProvider();
        Blue blue = new Blue(provider);
        String referencedBlueId = provider.getBlueIdByName("Referenced City");
        Node inherited = new Node().blueId(referencedBlueId);
        Node overlay = new Node().name("Location");
        Merger merger = new Merger(blue.getMergingProcessor(), provider);
        // when

        // then

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> merger.merge(inherited, overlay, NO_LIMITS));

        assertEquals(BlueLanguageErrorCategory.InvalidReferenceShape,
                BlueLanguageErrorClassifier.classify(failure));
    }

    @Test
    void shouldProviderSourceValidatesItsOwnFixedLabelsBeforeReferenceExpansion() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Fixed City Type",
                "city:",
                "  name: City",
                "  value: Warsaw"));
        String fixedTypeId = provider.getBlueIdByName("Fixed City Type");
        provider.addSingleDocs(String.join("\n",
                "name: Invalid Provider Document",
                "type:",
                "  blueId: " + fixedTypeId,
                "city:",
                "  name: Location",
                "  value: Warsaw"));
        String invalidDocumentId = provider.getBlueIdByName("Invalid Provider Document");
        provider.addSingleNodes(new Node()
                .name("Materializing Holder")
                .properties("payload", new Node().schema(new Schema().minFields(1))));
        String holderId = provider.getBlueIdByName("Materializing Holder");
        // when

        // then

        IllegalArgumentException directFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new Blue(provider).resolve(provider.getNodeByName("Invalid Provider Document")));
        IllegalArgumentException referencedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new Blue(provider).resolve(referenceHolder(holderId, invalidDocumentId)));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(directFailure));
        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(referencedFailure));
    }

    @Test
    void shouldCyclicReferenceMaterializationUsesTheOrdinaryExpansionLabelBoundary() {
        // given
        Node cyclicDocuments = YAML_MAPPER.readValue(String.join("\n",
                "- name: Person",
                "  friend:",
                "    blueId: this#1",
                "- name: Friend",
                "  person:",
                "    blueId: this#0"), Node.class);
        BasicNodeProvider provider = new BasicNodeProvider(cyclicDocuments);
        provider.addSingleNodes(new Node()
                .name("Ordinary Person")
                .properties("friend", new Node().value("present")));
        provider.addSingleNodes(new Node()
                .name("Labeled Payload Holder")
                .properties("payload", new Node()
                        .name("Payload Slot")
                        .schema(new Schema().minFields(1))));
        String holderId = provider.getBlueIdByName("Labeled Payload Holder");
        Blue blue = new Blue(provider);
        // when

        // then

        Node cyclic = assertDoesNotThrow(() -> blue.resolve(referenceHolder(
                holderId, provider.getBlueIdByName("Person"))));
        Node ordinary = assertDoesNotThrow(() -> blue.resolve(referenceHolder(
                holderId, provider.getBlueIdByName("Ordinary Person"))));

        assertEquals("Payload Slot", cyclic.getAsNode("/payload").getName());
        assertEquals("Payload Slot", ordinary.getAsNode("/payload").getName());
        assertTrue(cyclic.getAsNode("/payload/friend").isReferenceOnly());
        assertEquals("present", ordinary.getAsText("/payload/friend"));
    }

    @Test
    void shouldInlineTypeRootLabelsDoNotBecomeInstanceRootLabelsThroughPublicMerge() {
        // given
        Node target = new Node();
        Node source = new Node()
                .type(new Node()
                        .name("Base Type")
                        .description("Base label")
                        .properties("inherited", new Node().value("yes")))
                .properties("own", new Node().value("child"));
        Merger merger = new Merger(new Blue().getMergingProcessor(), new BasicNodeProvider());

        merger.merge(target, source, NO_LIMITS);
        // when

        // then

        assertNull(target.getName());
        assertNull(target.getDescription());
        assertEquals("yes", target.getAsText("/inherited"));
        assertEquals("child", target.getAsText("/own"));
    }

    @Test
    void shouldTypedDeclarationStructureDoesNotTurnItsFieldLabelIntoAFixedValue() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Detail Type",
                "field:",
                "  type: Text"));
        String detailTypeId = provider.getBlueIdByName("Detail Type");
        provider.addSingleDocs(String.join("\n",
                "name: Holder Type",
                "item:",
                "  name: Generic Item",
                "  description: Generic declaration label.",
                "  type:",
                "    blueId: " + detailTypeId));
        String holderTypeId = provider.getBlueIdByName("Holder Type");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + holderTypeId,
                "item:",
                "  name: Specific Item",
                "  description: Specific label.",
                "  field: value"));
        // when

        // then

        Node cold = assertDoesNotThrow(() -> blue.resolve(source.clone()));
        Node warm = assertDoesNotThrow(() -> blue.resolve(source.clone()));

        assertEquals("Specific Item", cold.getAsNode("/item").getName());
        assertEquals("Specific label.", cold.getAsNode("/item").getDescription());
        assertEquals("value", cold.getAsText("/item/field"));
        assertEquals(blue.nodeToJson(cold), blue.nodeToJson(warm));
    }

    @Test
    void shouldTypeMetadataChildLabelsAreIndependentOfResolvedTypeCacheHistory() {
        // given
        BasicNodeProvider coldProvider = metadataLabelProvider();
        String derivedTypeId = coldProvider.getBlueIdByName("Derived Entry Type");
        Blue coldBlue = new Blue(coldProvider);
        Node cold = coldBlue.resolve(listWithItemType(derivedTypeId));

        BasicNodeProvider warmProvider = metadataLabelProvider();
        // when

        // then
        assertEquals(derivedTypeId, warmProvider.getBlueIdByName("Derived Entry Type"));
        Blue warmBlue = new Blue(warmProvider);
        warmBlue.resolve(new Node().type(new Node().blueId(derivedTypeId)));
        Node warm = warmBlue.resolve(listWithItemType(derivedTypeId));

        assertEquals("Derived Field", cold.getAsNode("/itemType/field").getName());
        assertEquals("Derived label.", cold.getAsNode("/itemType/field").getDescription());
        assertEquals(coldBlue.nodeToJson(cold), warmBlue.nodeToJson(warm));
    }

    @Test
    void shouldDeclarationLabelProvenanceHonorsPartialResolutionLimits() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        String missingTypeId = BlueIdCalculator.calculateBlueId(
                new Node().name("Unavailable Nested Type"));
        provider.addSingleDocs(String.join("\n",
                "name: Partially Resolved Type",
                "visible:",
                "  type: Text",
                "hidden:",
                "  type:",
                "    blueId: " + missingTypeId));
        String outerTypeId = provider.getBlueIdByName("Partially Resolved Type");
        AtomicInteger missingTypeFetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            if (missingTypeId.equals(blueId)) {
                missingTypeFetches.incrementAndGet();
            }
            return provider.fetchByBlueId(blueId);
        });
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + outerTypeId,
                "visible:",
                "  name: Specific Value",
                "  value: shown"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> blue.resolve(
                source, PathLimits.withSinglePath("/visible")));

        assertEquals("Specific Value", resolved.getProperties().get("visible").getName());
        assertEquals("shown", resolved.getAsText("/visible"));
        assertFalse(resolved.getProperties().containsKey("hidden"));
        assertEquals(0, missingTypeFetches.get());
    }

    @Test
    void shouldPartialResolutionKeepsFixedLabelSemanticsForTheOverriddenSubtree() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Detail With Fixed Child",
                "visible:",
                "  type: Text",
                "hidden:",
                "  value: fixed"));
        String detailTypeId = provider.getBlueIdByName("Detail With Fixed Child");
        provider.addSingleDocs(String.join("\n",
                "name: Holder With Fixed Detail",
                "item:",
                "  name: Generic Item",
                "  type:",
                "    blueId: " + detailTypeId));
        String holderTypeId = provider.getBlueIdByName("Holder With Fixed Detail");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + holderTypeId,
                "item:",
                "  name: Specific Item",
                "  visible: x"));
        // when

        // then

        IllegalArgumentException fullFailure = assertThrows(
                IllegalArgumentException.class,
                () -> blue.resolve(source.clone()));
        IllegalArgumentException limitedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> blue.resolve(
                        source.clone(), PathLimits.withSinglePath("/item/visible")));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(fullFailure));
        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(limitedFailure));
    }

    @Test
    void shouldParentLabelClassificationResolvesRelevantNestedTypesBeyondTheProjection() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Fixed Nested Value",
                "value: fixed"));
        String fixedTypeId = provider.getBlueIdByName("Fixed Nested Value");
        provider.addSingleDocs(String.join("\n",
                "name: Detail With Typed Fixed Child",
                "visible:",
                "  type: Text",
                "hidden:",
                "  type:",
                "    blueId: " + fixedTypeId));
        String detailTypeId = provider.getBlueIdByName("Detail With Typed Fixed Child");
        provider.addSingleDocs(String.join("\n",
                "name: Holder With Typed Fixed Detail",
                "item:",
                "  name: Generic Item",
                "  type:",
                "    blueId: " + detailTypeId));
        String holderTypeId = provider.getBlueIdByName("Holder With Typed Fixed Detail");
        AtomicInteger fixedTypeFetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            if (fixedTypeId.equals(blueId)) {
                fixedTypeFetches.incrementAndGet();
            }
            return provider.fetchByBlueId(blueId);
        });
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + holderTypeId,
                "item:",
                "  name: Specific Item",
                "  visible: x"));
        // when

        // then

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> blue.resolve(
                        source, PathLimits.withSinglePath("/item/visible")));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(failure));
        assertTrue(fixedTypeFetches.get() > 0);
    }

    @Test
    void shouldUnrelatedLabeledPathDoesNotPreclassifyAnotherTypeOverride() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Detail Declaration",
                "field:",
                "  type: Text"));
        String detailTypeId = provider.getBlueIdByName("Detail Declaration");
        provider.addSingleDocs(String.join("\n",
                "name: Base Declared Holder",
                "item:",
                "  name: Generic Item",
                "  type:",
                "    blueId: " + detailTypeId));
        String baseTypeId = provider.getBlueIdByName("Base Declared Holder");
        provider.addSingleDocs(String.join("\n",
                "name: Derived Fixed Holder",
                "type:",
                "  blueId: " + baseTypeId,
                "item:",
                "  name: Fixed Item",
                "  field: x"));
        String derivedTypeId = provider.getBlueIdByName("Derived Fixed Holder");
        Node source = new Node()
                .type(new Node().blueId(derivedTypeId))
                .properties("other", new Node().name("Other Value").value("y"));

        Blue cold = new Blue(provider);
        // when

        // then
        Node coldResolved = assertDoesNotThrow(() -> cold.resolve(source.clone()));

        Blue warm = new Blue(provider);
        warm.resolve(new Node().type(new Node().blueId(derivedTypeId)));
        Node warmResolved = assertDoesNotThrow(() -> warm.resolve(source.clone()));

        assertEquals("Fixed Item", coldResolved.getAsNode("/item").getName());
        assertEquals(cold.nodeToJson(coldResolved), warm.nodeToJson(warmResolved));
    }

    @Test
    void shouldSharedAuthoredNodeIdentityDoesNotDropASecondLabelPath() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Shared Detail Declaration",
                "field:",
                "  type: Text"));
        String detailTypeId = provider.getBlueIdByName("Shared Detail Declaration");
        provider.addSingleDocs(String.join("\n",
                "name: Two Detail Holder",
                "left:",
                "  name: Generic Left",
                "  type:",
                "    blueId: " + detailTypeId,
                "right:",
                "  name: Generic Right",
                "  type:",
                "    blueId: " + detailTypeId));
        String holderTypeId = provider.getBlueIdByName("Two Detail Holder");
        Node sharedOverlay = new Node()
                .name("Specific Detail")
                .properties("field", new Node().value("x"));
        Node source = new Node()
                .type(new Node().blueId(holderTypeId))
                .properties("left", sharedOverlay)
                .properties("right", sharedOverlay);
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> new Blue(provider).resolve(source));

        assertEquals("Specific Detail", resolved.getAsNode("/left").getName());
        assertEquals("Specific Detail", resolved.getAsNode("/right").getName());
    }

    @Test
    void shouldPositionalListLabelUsesItsEffectiveTargetPath() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Positional Detail Declaration",
                "field:",
                "  type: Text"));
        String detailTypeId = provider.getBlueIdByName("Positional Detail Declaration");
        provider.addSingleDocs(String.join("\n",
                "name: Positional Detail Holder",
                "entries:",
                "  type: List",
                "  items:",
                "    - name: Generic First",
                "      type:",
                "        blueId: " + detailTypeId,
                "    - name: Generic Second",
                "      type:",
                "        blueId: " + detailTypeId,
                "    - name: Generic Third",
                "      type:",
                "        blueId: " + detailTypeId));
        String holderTypeId = provider.getBlueIdByName("Positional Detail Holder");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + holderTypeId,
                "entries:",
                "  items:",
                "    - $pos: 2",
                "      name: Specific Third",
                "      field: x"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> blue.resolve(source));

        Node third = resolved.getAsNode("/entries").getItems().get(2);
        assertEquals("Specific Third", third.getName());
        assertEquals("x", third.getAsText("/field"));
    }

    @Test
    void shouldPublicMergeUsesTheMaterializedTargetsTypeProvenance() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Public Merge Detail",
                "field:",
                "  type: Text"));
        String detailTypeId = provider.getBlueIdByName("Public Merge Detail");
        provider.addSingleDocs(String.join("\n",
                "name: Public Merge Holder",
                "item:",
                "  name: Generic Item",
                "  type:",
                "    blueId: " + detailTypeId));
        String holderTypeId = provider.getBlueIdByName("Public Merge Holder");
        Blue blue = new Blue(provider);
        Node target = blue.resolve(new Node().type(new Node().blueId(holderTypeId)));
        Node overlay = new Node().properties("item", new Node()
                .name("Specific Item")
                .properties("field", new Node().value("x")));
        // when

        // then

        assertDoesNotThrow(() -> new Merger(
                blue.getMergingProcessor(), provider).merge(target, overlay, NO_LIMITS));

        assertEquals("Specific Item", target.getAsNode("/item").getName());
        assertEquals("x", target.getAsText("/item/field"));
    }

    @Test
    void shouldPublicMergeDoesNotRelabelMaterializedInstancePayload() {
        // given
        BasicNodeProvider provider = publicMergeProvider();
        String holderTypeId = provider.getBlueIdByName("Public Merge Holder");
        Blue blue = new Blue(provider);
        Node target = blue.resolve(new Node()
                .type(new Node().blueId(holderTypeId))
                .properties("item", new Node()
                        .name("First Item")
                        .properties("field", new Node().value("x"))));
        Node overlay = new Node().properties("item", new Node()
                .name("Second Item")
                .properties("field", new Node().value("x")));
        // when

        // then

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new Merger(blue.getMergingProcessor(), provider)
                        .merge(target, overlay, NO_LIMITS));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(failure));
    }

    @Test
    void shouldDeepRelevantDeclarationClassificationDoesNotOverflowTheVmStack() {
        // given
        Node deepDeclaration = new Node().type(new Node().blueId(
                TEXT_TYPE_BLUE_ID));
        for (int depth = 0; depth < 30_000; depth++) {
            deepDeclaration = new Node().properties("next", deepDeclaration);
        }
        Node holderType = new Node().properties("item", new Node()
                .name("Generic Item")
                .type(deepDeclaration));
        Node source = new Node()
                .type(holderType)
                .properties("item", new Node().name("Specific Item"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> new Blue().resolve(
                source, PathLimits.withSinglePath("/item")));

        assertEquals("Specific Item", resolved.getAsNode("/item").getName());
    }

    @Test
    void shouldFailedPublicMergeProvenanceSetupDoesNotPoisonMergerReuse() {
        // given
        String missingTypeId = BlueIdCalculator.calculateBlueId(
                new Node().name("Unavailable Public Merge Type"));
        Merger merger = new Merger(new Blue().getMergingProcessor(), blueId -> null);
        Node invalidTarget = new Node().type(new Node().blueId(missingTypeId));
        Node labeledOverlay = new Node().properties(
                "item", new Node().name("Specific Item"));
        // when

        // then

        assertThrows(IllegalArgumentException.class,
                () -> merger.merge(invalidTarget, labeledOverlay, NO_LIMITS));
        IllegalArgumentException validationFailure = assertThrows(
                IllegalArgumentException.class,
                () -> merger.resolve(new Node()
                        .schema(new Schema().minLength(3))
                        .value("x"), NO_LIMITS));
        assertEquals(BlueLanguageErrorCategory.SchemaViolation,
                BlueLanguageErrorClassifier.classify(validationFailure));
        Node validTarget = new Node();
        assertDoesNotThrow(() -> merger.merge(
                validTarget, new Node().properties("ok", new Node().value("yes")), NO_LIMITS));

        assertEquals("yes", validTarget.getAsText("/ok"));
    }

    @Test
    void shouldInlineTypePositionalLayerUsesTheEffectiveTargetPath() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Inline Position Detail",
                "field:",
                "  type: Text"));
        String detailTypeId = provider.getBlueIdByName("Inline Position Detail");
        provider.addSingleDocs(String.join("\n",
                "name: Inline Position Base",
                "entries:",
                "  type: List",
                "  items:",
                "    - name: Generic First",
                "      type:",
                "        blueId: " + detailTypeId,
                "    - name: Generic Second",
                "      type:",
                "        blueId: " + detailTypeId,
                "    - name: Generic Third",
                "      type:",
                "        blueId: " + detailTypeId));
        String baseTypeId = provider.getBlueIdByName("Inline Position Base");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  type:",
                "    blueId: " + baseTypeId,
                "  entries:",
                "    items:",
                "      - $pos: 2",
                "        name: Fixed Third",
                "        field: x",
                "entries:",
                "  items:",
                "    - $pos: 2",
                "      name: Illegal Third",
                "      field: x"));
        // when

        // then

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> blue.resolve(source));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(failure));
    }

    @Test
    void shouldNearestFixedLabelRemainsFixedAcrossColdAndWarmTypeResolution() {
        // given
        BasicNodeProvider provider = layeredLabelProvider(false);
        String derivedTypeId = provider.getBlueIdByName("Derived Item Holder");
        Node source = conflictingLayeredLabelSource(derivedTypeId);

        Blue cold = new Blue(provider);
        // when

        // then
        IllegalArgumentException coldFailure = assertThrows(
                IllegalArgumentException.class,
                () -> cold.resolve(source.clone()));

        Blue warm = new Blue(provider);
        warm.resolve(new Node().type(new Node().blueId(derivedTypeId)));
        IllegalArgumentException warmFailure = assertThrows(
                IllegalArgumentException.class,
                () -> warm.resolve(source.clone()));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(coldFailure));
        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(warmFailure));
    }

    @Test
    void shouldDescendantDeclarationDoesNotEraseInheritedFixedLabel() {
        // given
        BasicNodeProvider provider = layeredLabelProvider(true);
        String derivedTypeId = provider.getBlueIdByName("Derived Item Holder");
        Node source = conflictingLayeredLabelSource(derivedTypeId);

        Blue cold = new Blue(provider);
        // when

        // then
        IllegalArgumentException coldFailure = assertThrows(
                IllegalArgumentException.class,
                () -> cold.resolve(source.clone()));

        Blue warm = new Blue(provider);
        warm.resolve(new Node().type(new Node().blueId(derivedTypeId)));
        IllegalArgumentException warmFailure = assertThrows(
                IllegalArgumentException.class,
                () -> warm.resolve(source.clone()));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(coldFailure));
        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(warmFailure));
    }

    @Test
    void shouldDeclarationOnlyContractsWrapperLabelCanBeOverridden() {
        // given
        BasicNodeProvider provider = contractsProvider(true);
        Blue blue = new Blue(provider);
        Node source = contractsInstance(
                blue, provider.getBlueIdByName("Labeled Contracts Holder"));
        // when

        // then

        Node resolved = assertDoesNotThrow(() -> blue.resolve(source));

        assertEquals("Instance Contracts", resolved.getContracts().getName());
        assertEquals("go", resolved.getContracts().getAsText("/action"));
    }

    @Test
    void shouldContractsWrapperWithFixedContentCannotBeRelabeled() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Fixed Contracts Holder",
                "contracts:",
                "  name: Declared Contracts",
                "  action: fixed"));
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + provider.getBlueIdByName("Fixed Contracts Holder"),
                "contracts:",
                "  name: Instance Contracts",
                "  action: fixed"));
        // when

        // then

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> blue.resolve(source));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(failure));
    }

    @Test
    void shouldAbsentContractsWrapperLabelCanBeSuppliedByTheInstance() {
        // given
        BasicNodeProvider provider = contractsProvider(false);
        Blue blue = new Blue(provider);
        Node source = contractsInstance(
                blue, provider.getBlueIdByName("Unlabeled Contracts Holder"));

        Node resolved = blue.resolve(source);
        // when

        // then

        assertEquals("Instance Contracts", resolved.getContracts().getName());
        assertEquals("go", resolved.getContracts().getAsText("/action"));
    }

    @Test
    void shouldPublicMergeCanRelabelADeclarationOnlyContractsWrapper() {
        // given
        Node target = new Node().contracts(new Node()
                .name("First Contracts")
                .properties("action", new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID))));
        Node overlay = new Node().contracts(new Node()
                .name("Second Contracts")
                .properties("action", new Node().value("go")));
        // when

        // then

        assertDoesNotThrow(() -> new Merger(
                new Blue().getMergingProcessor(), new BasicNodeProvider())
                .merge(target, overlay, NO_LIMITS));

        assertEquals("Second Contracts", target.getContracts().getName());
        assertEquals("go", target.getContracts().getAsText("/action"));
    }

    @Test
    void shouldPublicMergeRejectsRelabelingAContractsWrapperWithFixedContent() {
        // given
        Node target = new Node().contracts(new Node()
                .name("First Contracts")
                .properties("action", new Node().value("fixed")));
        Node overlay = new Node().contracts(new Node()
                .name("Second Contracts")
                .properties("action", new Node().value("fixed")));
        // when

        // then

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new Merger(new Blue().getMergingProcessor(), new BasicNodeProvider())
                        .merge(target, overlay, NO_LIMITS));

        assertEquals(BlueLanguageErrorCategory.FixedValueConflict,
                BlueLanguageErrorClassifier.classify(failure));
    }

    @Test
    void shouldPublicMergeCanAddAMissingContractsWrapperLabel() {
        // given
        Node target = new Node().contracts(new Node()
                .properties("action", new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID))));
        Node overlay = new Node().contracts(new Node()
                .name("Instance Contracts")
                .properties("action", new Node().value("go")));
        // when

        // then

        assertDoesNotThrow(() -> new Merger(
                new Blue().getMergingProcessor(), new BasicNodeProvider())
                .merge(target, overlay, NO_LIMITS));

        assertEquals("Instance Contracts", target.getContracts().getName());
        assertEquals("go", target.getContracts().getAsText("/action"));
    }

    @Test
    void shouldMinimizedTypedContractsRetainIdentityAcrossJsonObjectKeyOrdering() throws Exception {
        // given
        BasicNodeProvider writerProvider = provider();
        Blue writer = new Blue(writerProvider);
        ResolvedSnapshot original = writer.resolveToSnapshot(source(writer, writerProvider));
        Node minimized = new MinimizedOverlayBuilder().build(original.resolvedRoot());
        // when

        // then
        assertEquals("Value to subtract",
                original.resolvedRoot().getAsNode("/contracts/decrement/request").getDescription());
        assertEquals("Value to subtract",
                minimized.getAsNode("/contracts/decrement/request").getDescription());
        String reorderedJson = reorderAsJsonObjectStore(writer.nodeToJson(minimized));

        BasicNodeProvider readerProvider = provider();
        Blue reader = new Blue(readerProvider);
        Node reloadedSource = reader.jsonToNode(reorderedJson);
        assertEquals("Value to subtract",
                reloadedSource.getAsNode("/contracts/decrement/request").getDescription());
        Node preprocessed = reader.preprocess(reloadedSource);
        assertEquals("Value to subtract",
                preprocessed.getAsNode("/contracts/decrement/request").getDescription());
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(reloadedSource);

        assertTrue(original.frozenResolvedRoot().sameResolvedStructure(reloaded.frozenResolvedRoot()));
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.readTree(writer.nodeToJson(original.canonicalRoot())),
                mapper.readTree(reader.nodeToJson(reloaded.canonicalRoot())));
        assertEquals(original.blueId(), reloaded.blueId());
    }

    private static Node source(Blue blue, BasicNodeProvider provider) {
        String channelType = provider.getBlueIdByName("Provider Neutral Channel");
        String operationType = provider.getBlueIdByName("Provider Neutral Operation");
        return blue.yamlToNode(String.join("\n",
                "name: Counter",
                "counter: 0",
                "contracts:",
                "  owner:",
                "    type:",
                "      blueId: " + channelType,
                "    principalId: account-1",
                "    streamId: stream-1",
                "  increment:",
                "    type:",
                "      blueId: " + operationType,
                "    description: Increment the counter by the given number",
                "    channel: owner",
                "    request:",
                "      type: Integer",
                "      description: Represents a value by which the counter is incremented",
                "    steps:",
                "      - name: Increment",
                "        type: Text",
                "        value: increment",
                "  decrement:",
                "    type:",
                "      blueId: " + operationType,
                "    description: Decrement the counter by the given number",
                "    channel: owner",
                "    request:",
                "      type: Integer",
                "      description: Value to subtract",
                "    steps:",
                "      - name: Decrement",
                "        type: Text",
                "        value: decrement"));
    }

    private static BasicNodeProvider provider() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Provider Neutral Channel",
                "principalId:",
                "  type: Text",
                "  description: Provider-neutral principal identifier.",
                "streamId:",
                "  type: Text",
                "  description: Provider-neutral stream identifier."));
        provider.addSingleDocs(String.join("\n",
                "name: Provider Neutral Operation",
                "channel:",
                "  type: Text",
                "request:",
                "  description: Expected request payload shape for this operation.",
                "steps:",
                "  type: List"));
        return provider;
    }

    private static BasicNodeProvider fixedValueProvider() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Fixed City Holder",
                "city:",
                "  name: City",
                "  value: Warsaw"));
        provider.addSingleDocs(String.join("\n",
                "name: Unlabeled Fixed City Holder",
                "city:",
                "  value: Warsaw"));
        provider.addSingleDocs(String.join("\n",
                "name: Referenced City",
                "value: Warsaw"));
        return provider;
    }

    private static Node referenceHolder(String holderId, String payloadId) {
        return new Node()
                .type(new Node().blueId(holderId))
                .properties("payload", new Node().blueId(payloadId));
    }

    private static BasicNodeProvider contractsProvider(boolean labeled) {
        BasicNodeProvider provider = new BasicNodeProvider();
        String holder = labeled
                ? String.join("\n",
                        "name: Labeled Contracts Holder",
                        "contracts:",
                        "  name: Declared Contracts",
                        "  action:",
                        "    type: Text")
                : String.join("\n",
                        "name: Unlabeled Contracts Holder",
                        "contracts:",
                        "  action:",
                        "    type: Text");
        provider.addSingleDocs(holder);
        return provider;
    }

    private static BasicNodeProvider metadataLabelProvider() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Base Entry Type",
                "field:",
                "  name: Base Field",
                "  description: Base label.",
                "  type: Text"));
        String baseTypeId = provider.getBlueIdByName("Base Entry Type");
        provider.addSingleDocs(String.join("\n",
                "name: Derived Entry Type",
                "type:",
                "  blueId: " + baseTypeId,
                "field:",
                "  name: Derived Field",
                "  description: Derived label."));
        return provider;
    }

    private static BasicNodeProvider publicMergeProvider() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(String.join("\n",
                "name: Public Merge Detail",
                "field:",
                "  type: Text"));
        String detailTypeId = provider.getBlueIdByName("Public Merge Detail");
        provider.addSingleDocs(String.join("\n",
                "name: Public Merge Holder",
                "item:",
                "  name: Generic Item",
                "  type:",
                "    blueId: " + detailTypeId));
        return provider;
    }

    private static BasicNodeProvider layeredLabelProvider(boolean fixedInBase) {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(fixedInBase
                ? String.join("\n",
                        "name: Base Item Holder",
                        "item:",
                        "  name: Fixed Item",
                        "  value: x")
                : String.join("\n",
                        "name: Base Item Holder",
                        "item:",
                        "  name: Base Declaration",
                        "  type: Text"));
        String baseTypeId = provider.getBlueIdByName("Base Item Holder");
        provider.addSingleDocs(fixedInBase
                ? String.join("\n",
                        "name: Derived Item Holder",
                        "type:",
                        "  blueId: " + baseTypeId,
                        "item:",
                        "  type: Text")
                : String.join("\n",
                        "name: Derived Item Holder",
                        "type:",
                        "  blueId: " + baseTypeId,
                        "item:",
                        "  name: Fixed Item",
                        "  value: x"));
        return provider;
    }

    private static Node conflictingLayeredLabelSource(String derivedTypeId) {
        return new Node()
                .type(new Node().blueId(derivedTypeId))
                .properties("item", new Node().name("Illegal").value("x"));
    }

    private static Node listWithItemType(String itemTypeId) {
        return new Node()
                .type(new Node().blueId(LIST_TYPE_BLUE_ID))
                .itemType(new Node().blueId(itemTypeId));
    }

    private static Node contractsInstance(Blue blue, String holderId) {
        return blue.yamlToNode(String.join("\n",
                "type:",
                "  blueId: " + holderId,
                "contracts:",
                "  name: Instance Contracts",
                "  action: go"));
    }

    private static String reorderAsJsonObjectStore(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Object decoded = mapper.readValue(json, new TypeReference<Object>() { });
        return mapper.writeValueAsString(orderMaps(decoded));
    }

    private static Object orderMaps(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> ordered = new LinkedHashMap<>();
            source.entrySet().stream()
                    .sorted(Comparator
                            .comparingInt((Map.Entry<?, ?> entry) -> String.valueOf(entry.getKey()).length())
                            .thenComparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> ordered.put(
                            String.valueOf(entry.getKey()),
                            orderMaps(entry.getValue())));
            return ordered;
        }
        if (value instanceof List<?>) {
            List<?> items = (List<?>) value;
            return items.stream()
                    .map(MinimizedOverlayJsonObjectOrderTest::orderMaps)
                    .collect(Collectors.toList());
        }
        return value;
    }
}
