package blue.language.merge;

import blue.language.Blue;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedSnapshotTest {

    @Test
    void shouldMatchDeferredSnapshotIdentityWithValidatedConstructor() {
        // given
        FrozenNode canonical = FrozenNode.fromNode(
                new Node().properties("value", new Node().value("stable")));
        FrozenNode resolved = FrozenNode.fromResolvedNode(canonical.toNode());

        ResolvedSnapshot deferred = new ResolvedSnapshot(canonical, resolved);
        // when
        ResolvedSnapshot validated = new ResolvedSnapshot(canonical, resolved, canonical.blueId());

        // then
        assertEquals(validated.blueId(), deferred.blueId());
        assertSame(deferred.blueId(), deferred.blueId());
    }

    @Test
    void shouldExposeCanonicalResolvedAndBlueIdAsImmutableViewsAfterResolution() {
        // given
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

        // when
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(noisy);
        Node canonical = snapshot.canonicalRoot();
        Node resolved = snapshot.resolvedRoot();
        String snapshotBlueId = snapshot.blueId();
        String canonicalBlueId = DirectBlueIdCalculator.calculateBlueId(canonical);
        boolean inheritedLabelWasMinimized =
                !canonical.getProperties().containsKey("label");
        String resolvedLabel = resolved.getAsText("/label");
        canonical.properties("mutated", new Node().value(true));
        resolved.properties("label", new Node().value("changed"));
        boolean snapshotContainsCallerMutation =
                snapshot.canonicalRoot().getProperties()
                        .containsKey("mutated");
        String snapshotLabelAfterCallerMutation =
                snapshot.resolvedRoot().getAsText("/label");

        // then
        assertEquals(snapshotBlueId, canonicalBlueId);
        assertTrue(inheritedLabelWasMinimized);
        assertEquals("inherited", resolvedLabel);
        assertFalse(snapshotContainsCallerMutation);
        assertEquals("inherited", snapshotLabelAfterCallerMutation);
    }

    @Test
    void shouldTrustCanonicalBlueIdAndBuildResolvedViewWhenLoadingSnapshot() {
        // given
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

        String expectedBlueId = DirectBlueIdCalculator.calculateBlueId(canonical);
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);
        // when
        canonical.properties("local", new Node().value("changed"));

        // then
        assertEquals(expectedBlueId, snapshot.blueId());
        assertEquals("inherited", snapshot.resolvedRoot().getAsText("/label"));
        assertEquals("local-value", snapshot.resolvedRoot().getAsText("/local"));
    }

    @Test
    void shouldExposeFrozenCanonicalRootAndPatchEngine() {
        // given
        Node canonical = YAML_MAPPER.readValue(
                "left:\n" +
                "  child: keep\n" +
                "right:\n" +
                "  child: old", Node.class);
        ResolvedSnapshot snapshot = new Blue().loadSnapshot(canonical);

        // when
        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(
                snapshot.frozenCanonicalRoot()).apply(
                JsonPatch.replace("/right/child", new Node().value("new")));

        // then
        assertSame(snapshot.frozenCanonicalRoot().property("left"), result.root().property("left"));
        assertEquals("new", result.after().getValue());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(result.root().toNode()), result.blueId());
    }

    @Test
    void shouldExposeCanonicalAndResolvedPathIndexes() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "inherited: inherited-value");
        Blue blue = new Blue(nodeProvider);
        Node canonical = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "local:\n" +
                "  nested: value\n" +
                "rows:\n" +
                "  - a", Node.class);

        // when
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);

        // then
        assertEquals("value", snapshot.canonicalNodeAt("/local/nested").getValue());
        assertEquals("value", snapshot.resolvedNodeAt("/local/nested").getValue());
        assertEquals("inherited-value", snapshot.resolvedNodeAt("/inherited").getValue());
        assertEquals(null, snapshot.canonicalAt("/inherited"));
        assertEquals(snapshot.frozenResolvedRoot().at("/rows/0"), snapshot.resolvedAt("/rows/0"));
        assertTrue(snapshot.resolvedIndex().containsKey("/"));
    }

    @Test
    void shouldUseResolvedPathIndexForResolvedAt() {
        // given
        Blue blue = new Blue();
        Node source = YAML_MAPPER.readValue(
                "deep:\n" +
                "  nested:\n" +
                "    value: ok", Node.class);

        // when
        ResolvedSnapshot snapshot = blue.loadSnapshot(source);
        FrozenNode indexedNode =
                snapshot.resolvedIndex().get("/deep/nested");
        FrozenNode resolvedNode =
                snapshot.resolvedAt("/deep/nested");

        // then
        assertSame(indexedNode, resolvedNode);
    }

    @Test
    void shouldUseCanonicalPathIndexForCanonicalAt() {
        // given
        Blue blue = new Blue();
        Node source = YAML_MAPPER.readValue(
                "deep:\n" +
                "  nested:\n" +
                "    value: ok", Node.class);

        // when
        ResolvedSnapshot snapshot = blue.loadSnapshot(source);
        FrozenNode indexedNode =
                snapshot.canonicalIndex().get("/deep/nested");
        FrozenNode canonicalNode =
                snapshot.canonicalAt("/deep/nested");

        // then
        assertSame(indexedNode, canonicalNode);
    }

    @Test
    void shouldBuildPathIndexesLazilyAndIndependentlyAndPublishEachOnce() throws Exception {
        // given
        ResolvedSnapshot snapshot = new Blue().loadSnapshot(YAML_MAPPER.readValue(
                "deep:\n" +
                "  nested:\n" +
                "    value: ok", Node.class));
        Field canonicalIndexField = ResolvedSnapshot.class.getDeclaredField("canonicalIndex");
        Field resolvedIndexField = ResolvedSnapshot.class.getDeclaredField("resolvedIndex");
        canonicalIndexField.setAccessible(true);
        resolvedIndexField.setAccessible(true);

        // when
        Object canonicalIndexBeforeIdentity = canonicalIndexField.get(snapshot);
        Object resolvedIndexBeforeIdentity = resolvedIndexField.get(snapshot);
        String canonicalBlueId = snapshot.frozenCanonicalRoot().blueId();
        String snapshotBlueId = snapshot.blueId();
        Object canonicalIndexAfterIdentity = canonicalIndexField.get(snapshot);
        Object resolvedIndexAfterIdentity = resolvedIndexField.get(snapshot);
        Object nestedValue = snapshot.canonicalAt("/deep/nested").getValue();
        Map<String, FrozenNode> canonicalIndex = snapshot.canonicalIndex();
        Object publishedCanonicalIndex = canonicalIndexField.get(snapshot);
        Object resolvedIndexBeforePublication = resolvedIndexField.get(snapshot);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        Map<String, FrozenNode> resolvedIndex;
        Object publishedResolvedIndex;
        boolean allResolvedIndexesSame = true;
        try {
            List<Callable<Map<String, FrozenNode>>> calls = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                calls.add(snapshot::resolvedIndex);
            }
            List<Future<Map<String, FrozenNode>>> futures = executor.invokeAll(calls);
            resolvedIndex = futures.get(0).get(10, TimeUnit.SECONDS);
            publishedResolvedIndex = resolvedIndexField.get(snapshot);
            for (Future<Map<String, FrozenNode>> future : futures) {
                allResolvedIndexesSame &=
                        resolvedIndex
                                == future.get(
                                10,
                                TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // then
        assertNull(canonicalIndexBeforeIdentity);
        assertNull(resolvedIndexBeforeIdentity);
        assertEquals(canonicalBlueId, snapshotBlueId);
        assertNull(canonicalIndexAfterIdentity);
        assertNull(resolvedIndexAfterIdentity);
        assertEquals("ok", nestedValue);
        assertSame(canonicalIndex, publishedCanonicalIndex);
        assertNull(resolvedIndexBeforePublication);
        assertNotNull(publishedResolvedIndex);
        assertTrue(allResolvedIndexesSame);
    }

    @Test
    void shouldUseCanonicalRootBlueIdForResolvedSnapshotBlueId() {
        // given
        Blue blue = new Blue();
        Node canonical =
                YAML_MAPPER.readValue("value: ok", Node.class);

        // when
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);
        String canonicalBlueId =
                snapshot.frozenCanonicalRoot().blueId();
        String snapshotBlueId = snapshot.blueId();

        // then
        assertEquals(canonicalBlueId, snapshotBlueId);
    }

    @Test
    void shouldNotUseResolvedRootHashAsContentBlueId() {
        // given
        BasicNodeProvider nodeProvider = productProvider();
        Blue blue = new Blue(nodeProvider);
        Node canonical = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product"), Node.class);

        // when
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);

        // then
        assertEquals(snapshot.frozenCanonicalRoot().blueId(), snapshot.blueId());
        assertFalse(snapshot.frozenResolvedRoot().blueId().equals(snapshot.blueId()));
    }

    @Test
    void shouldAllowBlueToApplyCanonicalPatchAndReturnNextResolvedSnapshot() {
        // given
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

        // when
        ResolvedSnapshot next = blue.applyCanonicalPatch(snapshot,
                JsonPatch.replace("/local", new Node().value("new")));

        // then
        assertEquals("new", next.canonicalRoot().getAsText("/local/value"));
        assertEquals("inherited", next.resolvedRoot().getAsText("/label"));
        assertEquals(next.frozenCanonicalRoot().blueId(), next.blueId());
    }

    @Test
    void shouldRemoveRedundantOverrideWhenCanonicalPatchMatchesInheritedResolvedState() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Money\n" +
                "currency: USD\n" +
                "cents: 0");
        Blue blue = new Blue(nodeProvider);
        Node canonical = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Money"), Node.class);
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);

        // when
        ResolvedSnapshot next = blue.applyCanonicalPatch(snapshot,
                JsonPatch.add("/currency", new Node().value("USD")));

        // then
        assertEquals(snapshot.blueId(), next.blueId());
        assertEquals(null, next.canonicalAt("/currency"));
        assertEquals("USD", next.resolvedNodeAt("/currency").getValue());
    }

    @Test
    void shouldRemoveExistingRedundantOverrideWhenCanonicalReplaceMatchesInheritedResolvedState() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Money\n" +
                "currency: USD\n" +
                "cents: 0");
        Blue blue = new Blue(nodeProvider);
        Node canonical = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Money") + "\n" +
                "currency: USD", Node.class);
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);

        // when
        ResolvedSnapshot next = blue.applyCanonicalPatch(snapshot,
                JsonPatch.replace("/currency", new Node().value("USD")));

        // then
        assertFalse(snapshot.blueId().equals(next.blueId()));
        assertEquals(null, next.canonicalAt("/currency"));
        assertEquals("USD", next.resolvedNodeAt("/currency").getValue());
    }

    @Test
    void shouldKeepOverrideWhenCanonicalPatchDiffersFromInheritedResolvedState() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Money\n" +
                "currency:\n" +
                "  type: Text\n" +
                "cents: 0");
        Blue blue = new Blue(nodeProvider);
        Node canonical = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Money"), Node.class);
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);

        // when
        ResolvedSnapshot next = blue.applyCanonicalPatch(snapshot,
                JsonPatch.add("/currency", new Node().value("EUR")));

        // then
        assertFalse(snapshot.blueId().equals(next.blueId()));
        assertEquals("EUR", next.canonicalNodeAt("/currency").getValue());
        assertEquals("EUR", next.resolvedNodeAt("/currency").getValue());
    }

    @Test
    void shouldRejectSnapshotBlueIdThatDoesNotMatchCanonicalRoot() {
        // given
        FrozenNode root = FrozenNode.fromNode(new Node().value("x"));

        // when
        Throwable failure = captureFailure(
                () -> new ResolvedSnapshot(root, root, "wrong"));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectLenientResolvedNodeAsCanonicalRoot() {
        // given
        FrozenNode resolvedOnly = FrozenNode.fromResolvedNode(new Node()
                .blueId("ReferenceMetadata")
                .name("Expanded node"));

        // when
        Throwable failure = captureFailure(
                () -> new ResolvedSnapshot(
                        resolvedOnly,
                        resolvedOnly,
                        resolvedOnly.blueId()));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldCacheResolvedSnapshotByBlueIdAndReuseFrozenRootsWhenLoadingSnapshot() {
        // given
        BasicNodeProvider delegate = productProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);
        Node canonical = productInstance(delegate, "old");

        ResolvedSnapshot first = blue.loadSnapshot(canonical);
        int fetchesAfterFirstLoad = countingProvider.fetchCount();
        // when
        ResolvedSnapshot second = blue.loadSnapshot(canonical.clone());

        // then
        assertTrue(fetchesAfterFirstLoad > 0);
        assertSame(first, second);
        assertSame(first.frozenCanonicalRoot(), second.frozenCanonicalRoot());
        assertSame(first.frozenResolvedRoot(), second.frozenResolvedRoot());
        assertEquals(fetchesAfterFirstLoad, countingProvider.fetchCount());
        assertEquals(1, blue.resolvedSnapshotCacheSize());
        assertSame(first, blue.cachedResolvedSnapshot(first.blueId()).orElseThrow(IllegalStateException::new));
    }

    @Test
    void shouldLoadPreloadedResolvedSnapshotByBlueIdWithoutProviderFetchOrFrozenClone() {
        // given
        BasicNodeProvider delegate = productProvider();
        Node canonical = productInstance(delegate, "old");
        ResolvedSnapshot precomputed = new Blue(delegate).loadSnapshot(canonical);

        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider).cacheResolvedSnapshot(precomputed);
        // when
        ResolvedSnapshot loaded = blue.loadSnapshot(precomputed.blueId());

        // then
        assertSame(precomputed, loaded);
        assertSame(precomputed.frozenResolvedRoot(), loaded.frozenResolvedRoot());
        assertEquals(0, countingProvider.fetchCount());
    }

    @Test
    void shouldStripProviderRootIdentityWhenLoadingSnapshotByBlueIdOnCacheMiss() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "label: inherited");
        String blueId = nodeProvider.getBlueIdByName("Product");
        Blue blue = new Blue(nodeProvider);
        blue.clearResolvedSnapshotCache();

        // when
        ResolvedSnapshot snapshot = blue.loadSnapshot(blueId);

        // then
        assertEquals(blueId, snapshot.blueId());
        assertEquals("Product", snapshot.canonicalRoot().getName());
        assertNull(snapshot.canonicalRoot().getBlueId());
        assertEquals("inherited", snapshot.resolvedRoot().getAsText("/label"));
    }

    @Test
    void shouldReturnCachedTargetSnapshotWhenCanonicalPatchReachesKnownBlueId() {
        // given
        BasicNodeProvider delegate = productProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);
        ResolvedSnapshot original = blue.loadSnapshot(productInstance(delegate, "old"));
        Node exactPatchedTarget = productInstance(delegate, "old")
                .properties("local", new Node().value("new"));
        ResolvedSnapshot expectedTarget = blue.loadSnapshot(exactPatchedTarget);
        int fetchesAfterPreloadingTarget = countingProvider.fetchCount();

        // when
        ResolvedSnapshot patched = blue.applyCanonicalPatch(original,
                JsonPatch.replace("/local", new Node().value("new")));

        // then
        assertSame(expectedTarget, patched);
        assertSame(expectedTarget.frozenResolvedRoot(), patched.frozenResolvedRoot());
        assertEquals(fetchesAfterPreloadingTarget, countingProvider.fetchCount());
    }

    @Test
    void shouldClearResolvedSnapshotCacheWhenNodeProviderChanges() {
        // given
        BasicNodeProvider delegate = productProvider();
        Blue blue = new Blue(delegate);

        // when
        blue.loadSnapshot(productInstance(delegate, "old"));
        int cacheSizeBeforeProviderChange =
                blue.resolvedSnapshotCacheSize();
        blue.nodeProvider(productProvider());
        int cacheSizeAfterProviderChange =
                blue.resolvedSnapshotCacheSize();

        // then
        assertEquals(1, cacheSizeBeforeProviderChange);
        assertEquals(0, cacheSizeAfterProviderChange);
    }

    @Test
    void shouldReuseResolvedTypeFrozenNodeAcrossSnapshotsAndAvoidRefetchingTypeGraph() {
        // given
        BasicNodeProvider delegate = inheritedProductProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);

        ResolvedSnapshot first = blue.loadSnapshot(productInstance(delegate, "first"));
        int fetchesAfterFirst = countingProvider.fetchCount();
        // when
        ResolvedSnapshot second = blue.loadSnapshot(productInstance(delegate, "second"));

        // then
        assertTrue(fetchesAfterFirst > 0);
        assertEquals(fetchesAfterFirst, countingProvider.fetchCount());
        assertSame(first.frozenResolvedRoot().getType(), second.frozenResolvedRoot().getType());
        assertSame(first.frozenResolvedRoot().getType().getType(), second.frozenResolvedRoot().getType().getType());
        assertEquals(2, blue.resolvedSnapshotCacheSize());
        assertTrue(blue.resolvedReferenceCacheSize() >= 2);
    }

    @Test
    void shouldNotIncreaseProviderFetchCountForCachedResolvedTypes() {
        // given
        BasicNodeProvider delegate = inheritedProductProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);

        blue.loadSnapshot(productInstance(delegate, "first"));
        int fetchesAfterFirst = countingProvider.fetchCount();
        // when
        blue.loadSnapshot(productInstance(delegate, "second"));

        // then
        assertTrue(fetchesAfterFirst > 0);
        assertEquals(fetchesAfterFirst, countingProvider.fetchCount());
    }

    @Test
    void shouldUsePreloadedResolvedTypeSnapshotToResolveInstancesWithoutProviderFetches() {
        // given
        BasicNodeProvider delegate = inheritedProductProvider();
        Node productCanonical = YAML_MAPPER.readValue(
                "name: Product\n" +
                "type:\n" +
                "  blueId: " + delegate.getBlueIdByName("Base Product") + "\n" +
                "productLabel: product", Node.class);
        ResolvedSnapshot precomputedType = new Blue(delegate).loadSnapshot(productCanonical);

        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider).cacheResolvedSnapshot(precomputedType);
        // when
        ResolvedSnapshot instance = blue.loadSnapshot(productInstance(delegate, "from-preloaded-type"));

        // then
        assertEquals(0, countingProvider.fetchCount());
        assertNotSame(precomputedType.frozenResolvedRoot(), instance.frozenResolvedRoot().getType());
        assertNull(precomputedType.frozenResolvedRoot().getReferenceBlueId());
        assertEquals(precomputedType.blueId(),
                instance.frozenResolvedRoot().getType().getReferenceBlueId());
        assertEquals("base", instance.resolvedRoot().getAsText("/baseLabel"));
    }

    @Test
    void shouldReuseSnapshotAndResolvedTypeGraphAcrossResolveMinimizeResolveCycle() {
        // given
        BasicNodeProvider delegate = complexCommerceProvider();
        CountingNodeProvider countingProvider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(countingProvider);
        Node noisyOrder = complexOrder(delegate, "Order 1001");

        // when
        ResolvedSnapshot first = blue.resolveToSnapshot(noisyOrder);
        int fetchesAfterFirstResolve = countingProvider.fetchCount();
        Node canonical = first.canonicalRoot();
        int commerceOrderFetches = countingProvider.fetchCount(
                delegate.getBlueIdByName("Commerce Order"));
        int auditedEntityFetches = countingProvider.fetchCount(
                delegate.getBlueIdByName("Audited Entity"));
        int postalAddressFetches = countingProvider.fetchCount(
                delegate.getBlueIdByName("Postal Address"));
        int moneyFetches = countingProvider.fetchCount(
                delegate.getBlueIdByName("Money"));
        int lineItemFetches = countingProvider.fetchCount(
                delegate.getBlueIdByName("Line Item"));
        int deliveryWindowFetches = countingProvider.fetchCount(
                delegate.getBlueIdByName("Delivery Window"));
        ResolvedSnapshot fromMinimizedCanonical = blue.loadSnapshot(canonical);
        int fetchesAfterMinimizedReload = countingProvider.fetchCount();
        Node nextCanonicalOrder = canonical.clone().name("Order 1002");
        ResolvedSnapshot secondOrder = blue.loadSnapshot(nextCanonicalOrder);
        int fetchesAfterSecondOrder = countingProvider.fetchCount();

        // then
        assertEquals(1, commerceOrderFetches);
        assertEquals(1, auditedEntityFetches);
        assertEquals(1, postalAddressFetches);
        assertEquals(1, moneyFetches);
        assertEquals(1, lineItemFetches);
        assertEquals(1, deliveryWindowFetches);
        assertFalse(canonical.getProperties().containsKey("auditLevel"));
        assertFalse(canonical.getProperties().containsKey("metadata"));
        assertFalse(canonical.getProperties().containsKey("status"));
        assertFalse(canonical.getProperties().get("billingAddress")
                .getProperties().containsKey("country"));
        assertFalse(canonical.getProperties().get("billingAddress")
                .getProperties().containsKey("city"));
        assertFalse(canonical.getProperties().get("summary")
                .getProperties().containsKey("currency"));
        assertFalse(canonical.getProperties().get("deliveryWindow")
                .getProperties().containsKey("timezone"));
        assertTrue(first.isSourceBacked());
        assertFalse(fromMinimizedCanonical.isSourceBacked());
        assertNotSame(first, fromMinimizedCanonical);
        assertSame(first.frozenCanonicalRoot(),
                fromMinimizedCanonical.frozenCanonicalRoot());
        assertSame(first.frozenResolvedRoot(),
                fromMinimizedCanonical.frozenResolvedRoot());
        assertEquals(fetchesAfterFirstResolve,
                fetchesAfterMinimizedReload);
        assertNotSame(first, secondOrder);
        assertEquals(fetchesAfterFirstResolve, fetchesAfterSecondOrder);
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
