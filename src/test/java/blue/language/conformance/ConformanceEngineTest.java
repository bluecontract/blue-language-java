package blue.language.conformance;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConformanceEngineTest {

    @Test
    void detectsFixedValueViolationAndGeneralizesToNearestConformingType() {
        BasicNodeProvider nodeProvider = priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));

        document.getProperties().get("price").getProperties().get("currency").value("USD");

        ConformanceEngine engine = blue.conformanceEngine();
        assertFalse(engine.conforms(document));

        ConformancePlan plan = engine.planGeneralization(FrozenNode.fromResolvedNode(document), "/price/currency");

        assertTrue(plan.generalized());
        assertTrue(engine.conforms(plan.rootNode()));
        assertEquals("Price", plan.root().property("price").getType().getName());
        assertEquals("Global Product", plan.root().getType().getName());
    }

    @Test
    void leavesAlreadyConformantDocumentUnchanged() {
        BasicNodeProvider nodeProvider = priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));

        ConformancePlan plan = blue.conformanceEngine()
                .planGeneralization(FrozenNode.fromResolvedNode(document), "/price/amount");

        assertFalse(plan.generalized());
        assertEquals("Price in EUR", plan.root().property("price").getType().getName());
        assertEquals("European Product", plan.root().getType().getName());
    }

    @Test
    void plansGeneralizationWithoutMutatingFrozenRoot() {
        BasicNodeProvider nodeProvider = priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        document.getProperties().get("price").getProperties().get("currency").value("USD");
        FrozenNode patchedRoot = FrozenNode.fromResolvedNode(document);

        ConformancePlan plan = blue.conformanceEngine().planGeneralization(patchedRoot, "/price/currency");

        assertTrue(plan.generalized());
        assertFalse(plan.fullSnapshotRebuildAvoidable());
        assertTrue(plan.canonicalPatches().isEmpty());
        assertEquals("Price in EUR", patchedRoot.property("price").getType().getName());
        assertEquals("European Product", patchedRoot.getType().getName());
        assertEquals("Price", plan.root().property("price").getType().getName());
        assertEquals("Global Product", plan.root().getType().getName());
        assertEquals("USD", plan.root().at("/price/currency").getValue());
    }

    @Test
    void plansCanonicalGeneralizationPatchesAndChangedPaths() {
        BasicNodeProvider nodeProvider = priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "metadata:\n" +
                "  owner: Shop\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        document.getProperties().get("price").getProperties().get("currency").value("USD");
        FrozenNode resolvedRoot = FrozenNode.fromResolvedNode(document);
        FrozenNode canonicalRoot = FrozenNode.fromNode(blue.reverse(document.clone()));

        ConformancePlan plan = blue.conformanceEngine()
                .planGeneralization(canonicalRoot, resolvedRoot, "/price/currency");

        assertTrue(plan.generalized());
        assertTrue(plan.fullSnapshotRebuildAvoidable());
        assertEquals("Price", plan.root().property("price").getType().getName());
        assertEquals("Global Product", plan.root().getType().getName());
        assertEquals(nodeProvider.getBlueIdByName("Global Product"), plan.canonicalRoot().getType().getReferenceBlueId());
        assertEquals(2, plan.canonicalPatches().size());
        assertEquals("/price", plan.canonicalPatches().get(0).path());
        assertEquals("/", plan.canonicalPatches().get(1).path());
        assertEquals("USD", plan.canonicalPatches().get(0).before().at("/currency").getValue());
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                plan.canonicalPatches().get(0).after().getType().getReferenceBlueId());
        assertEquals(nodeProvider.getBlueIdByName("European Product"),
                plan.canonicalPatches().get(1).before().getType().getReferenceBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Global Product"),
                plan.canonicalPatches().get(1).after().getType().getReferenceBlueId());
        assertTrue(plan.changedPaths().contains("/price/type"));
        assertTrue(plan.changedPaths().contains("/type"));
        assertSame(resolvedRoot.property("metadata"), plan.root().property("metadata"));
        assertThrows(UnsupportedOperationException.class, () -> plan.changedPaths().add("/other"));
        assertThrows(UnsupportedOperationException.class, () -> plan.canonicalPatches().clear());
    }

    @Test
    void generalizesRootWhenRootFixedValueIsViolated() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "status:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Draft Product\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "status: draft");
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Release\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Draft Product") + "\n" +
                "status: draft", Node.class));

        document.getProperties().get("status").value("published");
        FrozenNode resolvedRoot = FrozenNode.fromResolvedNode(document);
        FrozenNode canonicalRoot = FrozenNode.fromNode(blue.reverse(document.clone()));

        ConformancePlan plan = blue.conformanceEngine()
                .planGeneralization(canonicalRoot, resolvedRoot, "/status");

        assertTrue(blue.conformanceEngine().conforms(plan.rootNode()));
        assertTrue(plan.fullSnapshotRebuildAvoidable());
        assertEquals("Product", plan.root().getType().getName());
        assertEquals("published", plan.root().at("/status").getValue());
        assertEquals(1, plan.canonicalPatches().size());
        assertEquals("/", plan.canonicalPatches().get(0).path());
        assertEquals(nodeProvider.getBlueIdByName("Draft Product"),
                plan.canonicalPatches().get(0).before().getType().getReferenceBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Product"),
                plan.canonicalPatches().get(0).after().getType().getReferenceBlueId());
        assertTrue(plan.changedPaths().contains("/"));
        assertTrue(plan.changedPaths().contains("/type"));
        assertEquals("Draft Product", resolvedRoot.getType().getName());
    }

    @Test
    void generalizesRootWhenSchemaConstraintIsViolated() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Any Score\n" +
                "type: Integer");
        nodeProvider.addSingleDocs(
                "name: Positive Score\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Any Score") + "\n" +
                "schema:\n" +
                "  minimum: 0");
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Score\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Positive Score") + "\n" +
                "value: 5", Node.class));

        document.value(-1);

        ConformancePlan plan = blue.conformanceEngine()
                .planGeneralization(FrozenNode.fromResolvedNode(document), "/value");

        assertTrue(blue.conformanceEngine().conforms(plan.rootNode()));
        assertEquals("Any Score", plan.root().getType().getName());
        assertEquals(-1, plan.rootNode().getAsInteger("/"));
    }

    @Test
    void appendPointerGeneralizationUsesConcreteLastListIndexAndSharesUnchangedItems() {
        BasicNodeProvider nodeProvider = basketProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Cart\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Basket") + "\n" +
                "prices:\n" +
                "  type:\n" +
                "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "  items:\n" +
                "    - type:\n" +
                "        blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "      amount: 10\n" +
                "      currency: EUR\n" +
                "    - type:\n" +
                "        blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "      amount: 20\n" +
                "      currency: EUR", Node.class));

        document.getAsNode("/prices/1").getProperties().get("currency").value("USD");
        FrozenNode resolvedRoot = FrozenNode.fromResolvedNode(document);
        FrozenNode canonicalRoot = FrozenNode.fromNode(blue.reverse(document.clone()));

        ConformancePlan plan = blue.conformanceEngine()
                .planGeneralization(canonicalRoot, resolvedRoot, "/prices/-/currency");

        assertTrue(plan.generalized());
        assertTrue(blue.conformanceEngine().conforms(plan.rootNode()));
        assertEquals("Basket", plan.root().getType().getName());
        assertEquals("Price", plan.root().at("/prices/1").getType().getName());
        assertEquals("USD", plan.root().at("/prices/1/currency").getValue());
        assertSame(resolvedRoot.at("/prices/0"), plan.root().at("/prices/0"));
        assertTrue(plan.changedPaths().contains("/prices/1/type"));
        assertTrue(plan.changedPaths().contains("/prices/itemType"));
        assertTrue(plan.changedPaths().contains("/type"));
        assertFalse(plan.changedPaths().contains("/prices/-/type"));
    }

    @Test
    void dictionaryValueTypeGeneralizationUpdatesMetadataAndSharesUnchangedEntries() {
        BasicNodeProvider nodeProvider = catalogProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Catalog\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Catalog") + "\n" +
                "prices:\n" +
                "  type:\n" +
                "    blueId: " + Properties.DICTIONARY_TYPE_BLUE_ID + "\n" +
                "  keyType:\n" +
                "    blueId: " + Properties.TEXT_TYPE_BLUE_ID + "\n" +
                "  valueType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "  sku1:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "    amount: 10\n" +
                "    currency: EUR\n" +
                "  sku2:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "    amount: 20\n" +
                "    currency: EUR", Node.class));

        document.getAsNode("/prices/sku2").getProperties().get("currency").value("USD");
        FrozenNode resolvedRoot = FrozenNode.fromResolvedNode(document);
        FrozenNode canonicalRoot = FrozenNode.fromNode(blue.reverse(document.clone()));

        ConformancePlan plan = blue.conformanceEngine()
                .planGeneralization(canonicalRoot, resolvedRoot, "/prices/sku2/currency");

        assertTrue(plan.generalized());
        assertTrue(blue.conformanceEngine().conforms(plan.rootNode()));
        assertEquals("Catalog Type", plan.root().getType().getName());
        assertEquals("Price", plan.root().at("/prices/sku2").getType().getName());
        assertEquals("Price", plan.root().at("/prices").getValueType().getName());
        assertEquals("USD", plan.root().at("/prices/sku2/currency").getValue());
        assertSame(resolvedRoot.at("/prices/sku1"), plan.root().at("/prices/sku1"));
        assertTrue(plan.changedPaths().contains("/prices/sku2/type"));
        assertTrue(plan.changedPaths().contains("/prices/valueType"));
        assertTrue(plan.changedPaths().contains("/type"));
    }

    @Test
    void failedGeneralizationLeavesFrozenRootAndCanonicalRootUntouched() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Fixed One\n" +
                "x: 1");
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Fixed One") + "\n" +
                "x: 1", Node.class));
        document.getProperties().get("x").value(2);
        FrozenNode resolvedRoot = FrozenNode.fromResolvedNode(document);
        FrozenNode canonicalRoot = FrozenNode.fromNode(blue.reverse(document.clone()));

        assertThrows(IllegalArgumentException.class,
                () -> blue.conformanceEngine().planGeneralization(canonicalRoot, resolvedRoot, "/x"));

        assertEquals("Fixed One", resolvedRoot.getType().getName());
        assertEquals("2", resolvedRoot.at("/x").getValue().toString());
        assertEquals(nodeProvider.getBlueIdByName("Fixed One"), canonicalRoot.getType().getReferenceBlueId());
        assertEquals("2", canonicalRoot.at("/x").getValue().toString());
    }

    public static BasicNodeProvider priceProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Price\n" +
                "amount:\n" +
                "  type: Integer\n" +
                "currency:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Price in EUR\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Price") + "\n" +
                "currency: EUR");
        nodeProvider.addSingleDocs(
                "name: Global Product\n" +
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price"));
        nodeProvider.addSingleDocs(
                "name: European Product\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Global Product") + "\n" +
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR"));
        return nodeProvider;
    }

    private static BasicNodeProvider basketProvider() {
        BasicNodeProvider nodeProvider = priceProvider();
        nodeProvider.addSingleDocs(
                "name: Basket\n" +
                "prices:\n" +
                "  type:\n" +
                "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price"));
        nodeProvider.addSingleDocs(
                "name: European Basket\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Basket") + "\n" +
                "prices:\n" +
                "  type:\n" +
                "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR"));
        return nodeProvider;
    }

    private static BasicNodeProvider catalogProvider() {
        BasicNodeProvider nodeProvider = priceProvider();
        nodeProvider.addSingleDocs(
                "name: Catalog Type\n" +
                "prices:\n" +
                "  type:\n" +
                "    blueId: " + Properties.DICTIONARY_TYPE_BLUE_ID + "\n" +
                "  keyType:\n" +
                "    blueId: " + Properties.TEXT_TYPE_BLUE_ID + "\n" +
                "  valueType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price"));
        nodeProvider.addSingleDocs(
                "name: European Catalog\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Catalog Type") + "\n" +
                "prices:\n" +
                "  type:\n" +
                "    blueId: " + Properties.DICTIONARY_TYPE_BLUE_ID + "\n" +
                "  keyType:\n" +
                "    blueId: " + Properties.TEXT_TYPE_BLUE_ID + "\n" +
                "  valueType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR"));
        return nodeProvider;
    }
}
