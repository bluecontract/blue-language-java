package blue.language.snapshot;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedSnapshotTest {

    @Test
    void resolveToSnapshotExposesCanonicalResolvedAndBlueIdAsImmutableViews() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "label: inherited");
        Blue blue = new Blue(nodeProvider);
        Node noisy = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "label: inherited\n" +
                "local: local-value", Node.class);

        ResolvedSnapshot snapshot = blue.resolveToSnapshot(noisy);
        Node canonical = snapshot.canonicalRoot();
        Node resolved = snapshot.resolvedRoot();

        assertEquals(snapshot.blueId(), BlueIdCalculator.calculateBlueId(canonical));
        assertFalse(canonical.getProperties().containsKey("label"));
        assertEquals("inherited", resolved.getAsText("/label"));

        canonical.properties("mutated", new Node().value(true));
        resolved.properties("label", new Node().value("changed"));

        assertFalse(snapshot.canonicalRoot().getProperties().containsKey("mutated"));
        assertEquals("inherited", snapshot.resolvedRoot().getAsText("/label"));
    }

    @Test
    void loadSnapshotTrustsCanonicalBlueIdButStillBuildsResolvedView() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "label: inherited");
        Blue blue = new Blue(nodeProvider);
        Node canonical = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "local: local-value", Node.class);

        String expectedBlueId = BlueIdCalculator.calculateBlueId(canonical);
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);
        canonical.properties("local", new Node().value("changed"));

        assertEquals(expectedBlueId, snapshot.blueId());
        assertEquals("inherited", snapshot.resolvedRoot().getAsText("/label"));
        assertEquals("local-value", snapshot.resolvedRoot().getAsText("/local"));
    }

    @Test
    void exposesFrozenCanonicalRootAndPatchEngine() {
        Node canonical = YAML_MAPPER.readValue(
                "left:\n" +
                "  child: keep\n" +
                "right:\n" +
                "  child: old", Node.class);
        ResolvedSnapshot snapshot = new Blue().loadSnapshot(canonical);

        CanonicalPatchResult result = snapshot.applyCanonicalPatch(
                JsonPatch.replace("/right/child", new Node().value("new")));

        assertSame(snapshot.frozenCanonicalRoot().property("left"), result.root().property("left"));
        assertEquals("new", result.after().getValue());
        assertEquals(BlueIdCalculator.calculateBlueId(result.root().toNode()), result.blueId());
    }

    @Test
    void blueCanApplyCanonicalPatchAndReturnNextResolvedSnapshot() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "label: inherited");
        Blue blue = new Blue(nodeProvider);
        Node canonical = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "local: old", Node.class);
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);

        ResolvedSnapshot next = blue.applyCanonicalPatch(snapshot,
                JsonPatch.replace("/local", new Node().value("new")));

        assertEquals("new", next.canonicalRoot().getAsText("/local/value"));
        assertEquals("inherited", next.resolvedRoot().getAsText("/label"));
        assertEquals(next.frozenCanonicalRoot().blueId(), next.blueId());
    }

    @Test
    void rejectsSnapshotBlueIdThatDoesNotMatchCanonicalRoot() {
        FrozenNode root = FrozenNode.fromNode(new Node().value("x"));

        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedSnapshot(root, root, "wrong"));
    }

    @Test
    void rejectsLenientResolvedNodeAsCanonicalRoot() {
        FrozenNode resolvedOnly = FrozenNode.fromResolvedNode(new Node()
                .blueId("ReferenceMetadata")
                .name("Expanded node"));

        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedSnapshot(resolvedOnly, resolvedOnly, resolvedOnly.blueId()));
    }

    @Test
    void loadSnapshotCachesResolvedSnapshotByBlueIdAndReusesFrozenRoots() {
        BasicNodeProvider delegate = productProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);
        Node canonical = productInstance(delegate, "old");

        ResolvedSnapshot first = blue.loadSnapshot(canonical);
        int fetchesAfterFirstLoad = countingProvider.fetchCount();
        ResolvedSnapshot second = blue.loadSnapshot(canonical.clone());

        assertTrue(fetchesAfterFirstLoad > 0);
        assertSame(first, second);
        assertSame(first.frozenCanonicalRoot(), second.frozenCanonicalRoot());
        assertSame(first.frozenResolvedRoot(), second.frozenResolvedRoot());
        assertEquals(fetchesAfterFirstLoad, countingProvider.fetchCount());
        assertEquals(1, blue.resolvedSnapshotCacheSize());
        assertSame(first, blue.cachedResolvedSnapshot(first.blueId()).orElseThrow(IllegalStateException::new));
    }

    @Test
    void preloadedResolvedSnapshotCanBeLoadedByBlueIdAtStartupWithoutProviderFetchOrFrozenClone() {
        BasicNodeProvider delegate = productProvider();
        Node canonical = productInstance(delegate, "old");
        ResolvedSnapshot precomputed = new Blue(delegate).loadSnapshot(canonical);

        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider).cacheResolvedSnapshot(precomputed);
        ResolvedSnapshot loaded = blue.loadSnapshot(precomputed.blueId());

        assertSame(precomputed, loaded);
        assertSame(precomputed.frozenResolvedRoot(), loaded.frozenResolvedRoot());
        assertEquals(0, countingProvider.fetchCount());
    }

    @Test
    void canonicalPatchReturnsCachedTargetSnapshotWhenPatchReachesKnownBlueId() {
        BasicNodeProvider delegate = productProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);
        ResolvedSnapshot original = blue.loadSnapshot(productInstance(delegate, "old"));
        ResolvedSnapshot expectedTarget = blue.loadSnapshot(productInstance(delegate, "new"));
        int fetchesAfterPreloadingTarget = countingProvider.fetchCount();

        ResolvedSnapshot patched = blue.applyCanonicalPatch(original,
                JsonPatch.replace("/local", new Node().value("new")));

        assertSame(expectedTarget, patched);
        assertSame(expectedTarget.frozenResolvedRoot(), patched.frozenResolvedRoot());
        assertEquals(fetchesAfterPreloadingTarget, countingProvider.fetchCount());
    }

    @Test
    void changingNodeProviderClearsResolvedSnapshotCache() {
        BasicNodeProvider delegate = productProvider();
        Blue blue = new Blue(delegate);
        blue.loadSnapshot(productInstance(delegate, "old"));

        assertEquals(1, blue.resolvedSnapshotCacheSize());

        blue.nodeProvider(productProvider());

        assertEquals(0, blue.resolvedSnapshotCacheSize());
    }

    @Test
    void differentSnapshotsReuseSameResolvedTypeFrozenNodeAndAvoidRefetchingTypeGraph() {
        BasicNodeProvider delegate = inheritedProductProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);

        ResolvedSnapshot first = blue.loadSnapshot(productInstance(delegate, "first"));
        int fetchesAfterFirst = countingProvider.fetchCount();
        ResolvedSnapshot second = blue.loadSnapshot(productInstance(delegate, "second"));

        assertTrue(fetchesAfterFirst > 0);
        assertEquals(fetchesAfterFirst, countingProvider.fetchCount());
        assertSame(first.frozenResolvedRoot().getType(), second.frozenResolvedRoot().getType());
        assertSame(first.frozenResolvedRoot().getType().getType(), second.frozenResolvedRoot().getType().getType());
        assertEquals(2, blue.resolvedSnapshotCacheSize());
        assertTrue(blue.resolvedReferenceCacheSize() >= 2);
    }

    @Test
    void preloadedResolvedTypeSnapshotIsUsedToResolveInstancesWithoutProviderFetches() {
        BasicNodeProvider delegate = inheritedProductProvider();
        Node productCanonical = YAML_MAPPER.readValue(
                "name: Product\n" +
                "type:\n" +
                "  blueId: " + delegate.getBlueIdByName("Base Product") + "\n" +
                "productLabel: product", Node.class);
        ResolvedSnapshot precomputedType = new Blue(delegate).loadSnapshot(productCanonical);

        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider).cacheResolvedSnapshot(precomputedType);
        ResolvedSnapshot instance = blue.loadSnapshot(productInstance(delegate, "from-preloaded-type"));

        assertEquals(0, countingProvider.fetchCount());
        assertSame(precomputedType.frozenResolvedRoot(), instance.frozenResolvedRoot().getType());
        assertEquals("base", instance.resolvedRoot().getAsText("/baseLabel"));
    }

    @Test
    void complexResolveMinimizeThenResolveAgainReusesSnapshotAndResolvedTypeGraph() {
        BasicNodeProvider delegate = complexCommerceProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);
        Node noisyOrder = complexOrder(delegate, "Order 1001");

        ResolvedSnapshot first = blue.resolveToSnapshot(noisyOrder);
        int fetchesAfterFirstResolve = countingProvider.fetchCount();
        Node canonical = first.canonicalRoot();

        assertEquals(1, countingProvider.fetchCount(delegate.getBlueIdByName("Commerce Order")));
        assertEquals(1, countingProvider.fetchCount(delegate.getBlueIdByName("Audited Entity")));
        assertEquals(1, countingProvider.fetchCount(delegate.getBlueIdByName("Postal Address")));
        assertEquals(1, countingProvider.fetchCount(delegate.getBlueIdByName("Money")));
        assertEquals(1, countingProvider.fetchCount(delegate.getBlueIdByName("Line Item")));
        assertEquals(1, countingProvider.fetchCount(delegate.getBlueIdByName("Delivery Window")));

        assertFalse(canonical.getProperties().containsKey("auditLevel"));
        assertFalse(canonical.getProperties().containsKey("metadata"));
        assertFalse(canonical.getProperties().containsKey("status"));
        assertFalse(canonical.getProperties().get("billingAddress").getProperties().containsKey("country"));
        assertFalse(canonical.getProperties().get("billingAddress").getProperties().containsKey("city"));
        assertFalse(canonical.getProperties().get("summary").getProperties().containsKey("currency"));
        assertFalse(canonical.getProperties().get("deliveryWindow").getProperties().containsKey("timezone"));

        ResolvedSnapshot fromMinimizedCanonical = blue.loadSnapshot(canonical);

        assertSame(first, fromMinimizedCanonical);
        assertEquals(fetchesAfterFirstResolve, countingProvider.fetchCount());

        Node nextCanonicalOrder = canonical.clone().name("Order 1002");
        ResolvedSnapshot secondOrder = blue.loadSnapshot(nextCanonicalOrder);

        assertNotSame(first, secondOrder);
        assertEquals(fetchesAfterFirstResolve, countingProvider.fetchCount());
        assertSame(first.frozenResolvedRoot().getType(), secondOrder.frozenResolvedRoot().getType());
        assertSame(first.frozenResolvedRoot().property("billingAddress").getType(),
                secondOrder.frozenResolvedRoot().property("billingAddress").getType());
        assertSame(first.frozenResolvedRoot().property("shippingAddress").getType(),
                secondOrder.frozenResolvedRoot().property("shippingAddress").getType());
        assertSame(first.frozenResolvedRoot().property("summary").getType(),
                secondOrder.frozenResolvedRoot().property("summary").getType());
        assertSame(first.frozenResolvedRoot().property("deliveryWindow").getType(),
                secondOrder.frozenResolvedRoot().property("deliveryWindow").getType());
        assertSame(first.frozenResolvedRoot().property("lineItems").item(0).getType(),
                secondOrder.frozenResolvedRoot().property("lineItems").item(0).getType());
        assertSame(first.frozenResolvedRoot().property("lineItems").item(0).property("unitPrice").getType(),
                secondOrder.frozenResolvedRoot().property("lineItems").item(0).property("unitPrice").getType());
        assertSame(first.frozenResolvedRoot().property("lineItems").item(0).property("shipTo").getType(),
                secondOrder.frozenResolvedRoot().property("lineItems").item(0).property("shipTo").getType());
    }

    private BasicNodeProvider productProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "label: inherited");
        return nodeProvider;
    }

    private Node productInstance(BasicNodeProvider nodeProvider, String localValue) {
        return YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "local: " + localValue, Node.class);
    }

    private BasicNodeProvider inheritedProductProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base Product\n" +
                "baseLabel: base");
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base Product") + "\n" +
                "productLabel: product");
        return nodeProvider;
    }

    private BasicNodeProvider complexCommerceProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Audited Entity\n" +
                "auditLevel: standard\n" +
                "metadata:\n" +
                "  source: catalog");
        nodeProvider.addSingleDocs(
                "name: Postal Address\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Audited Entity") + "\n" +
                "country: US\n" +
                "city: Default City\n" +
                "line1:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Money\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Audited Entity") + "\n" +
                "currency: USD\n" +
                "amount:\n" +
                "  type: Integer");
        nodeProvider.addSingleDocs(
                "name: Delivery Window\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Audited Entity") + "\n" +
                "timezone: UTC\n" +
                "start:\n" +
                "  type: Text\n" +
                "end:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Line Item\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Audited Entity") + "\n" +
                "sku:\n" +
                "  type: Text\n" +
                "quantity:\n" +
                "  type: Integer\n" +
                "unitPrice:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Money") + "\n" +
                "shipTo:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Postal Address"));
        nodeProvider.addSingleDocs(
                "name: Commerce Order\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Audited Entity") + "\n" +
                "status: draft\n" +
                "billingAddress:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Postal Address") + "\n" +
                "shippingAddress:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Postal Address") + "\n" +
                "summary:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Money") + "\n" +
                "deliveryWindow:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Delivery Window") + "\n" +
                "lineItems:\n" +
                "  type: List\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Line Item"));
        return nodeProvider;
    }

    private Node complexOrder(BasicNodeProvider nodeProvider, String name) {
        return YAML_MAPPER.readValue(
                "name: " + name + "\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Commerce Order") + "\n" +
                "auditLevel: standard\n" +
                "metadata:\n" +
                "  source: catalog\n" +
                "status: draft\n" +
                "billingAddress:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Postal Address") + "\n" +
                "  country: US\n" +
                "  city: Default City\n" +
                "  line1: 1 Main St\n" +
                "shippingAddress:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Postal Address") + "\n" +
                "  country: US\n" +
                "  city: Default City\n" +
                "  line1: 2 Warehouse Way\n" +
                "deliveryWindow:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Delivery Window") + "\n" +
                "  timezone: UTC\n" +
                "  start: \"09:00\"\n" +
                "  end: \"17:00\"\n" +
                "summary:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Money") + "\n" +
                "  currency: USD\n" +
                "  amount: 42\n" +
                "lineItems:\n" +
                "  type: List\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Line Item") + "\n" +
                "  items:\n" +
                "    - type:\n" +
                "        blueId: " + nodeProvider.getBlueIdByName("Line Item") + "\n" +
                "      auditLevel: standard\n" +
                "      sku: SKU-1\n" +
                "      quantity: 1\n" +
                "      unitPrice:\n" +
                "        type:\n" +
                "          blueId: " + nodeProvider.getBlueIdByName("Money") + "\n" +
                "        currency: USD\n" +
                "        amount: 12\n" +
                "      shipTo:\n" +
                "        type:\n" +
                "          blueId: " + nodeProvider.getBlueIdByName("Postal Address") + "\n" +
                "        country: US\n" +
                "        city: Default City\n" +
                "        line1: Dock 1\n" +
                "    - type:\n" +
                "        blueId: " + nodeProvider.getBlueIdByName("Line Item") + "\n" +
                "      auditLevel: standard\n" +
                "      sku: SKU-2\n" +
                "      quantity: 2\n" +
                "      unitPrice:\n" +
                "        type:\n" +
                "          blueId: " + nodeProvider.getBlueIdByName("Money") + "\n" +
                "        currency: USD\n" +
                "        amount: 15\n" +
                "      shipTo:\n" +
                "        type:\n" +
                "          blueId: " + nodeProvider.getBlueIdByName("Postal Address") + "\n" +
                "        country: US\n" +
                "        city: Default City\n" +
                "        line1: Dock 2", Node.class);
    }

    private static final class CountingNodeProvider implements NodeProvider {
        private final NodeProvider delegate;
        private int fetchCount;
        private final Map<String, Integer> fetchCountsByBlueId = new HashMap<>();

        private CountingNodeProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetchCount++;
            fetchCountsByBlueId.merge(blueId, 1, Integer::sum);
            return delegate.fetchByBlueId(blueId);
        }

        private int fetchCount() {
            return fetchCount;
        }

        private int fetchCount(String blueId) {
            return fetchCountsByBlueId.getOrDefault(blueId, 0);
        }
    }
}
