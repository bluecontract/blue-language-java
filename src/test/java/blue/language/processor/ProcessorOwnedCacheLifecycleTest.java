package blue.language.processor;

import blue.language.BlueCachePolicy;
import blue.language.NodeProvider;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.TypeClassResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorOwnedCacheLifecycleTest {

    @Test
    void contractBundleCacheUsesDeterministicWeightedLruBounds() {
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .conformancePlans(3, 8_192L)
                .maximumDerivedEntryWeightBytes(8_192L)
                .build();
        ContractLoader loader = loader(policy);
        RecordingMetrics metrics = new RecordingMetrics();

        loadEmpty(loader, "/a", metrics);
        loadEmpty(loader, "/b", metrics);
        loadEmpty(loader, "/c", metrics);
        loadEmpty(loader, "/a", metrics);
        loadEmpty(loader, "/d", metrics);
        loadEmpty(loader, "/a", metrics);
        loadEmpty(loader, "/b", metrics);

        assertEquals(2L, metrics.hits);
        assertEquals(5L, metrics.misses);
        assertEquals(3, loader.cacheSize());
        assertTrue(loader.cacheWeightBytes() <= 8_192L);

        loader.clearCaches();

        assertEquals(0, loader.cacheSize());
        assertEquals(0L, loader.cacheWeightBytes());
    }

    @Test
    void declaredLineageCacheUsesPolicyBoundsAndCanBeCleared() {
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .conformancePlans(3, 2_048L)
                .maximumDerivedEntryWeightBytes(2_048L)
                .build();
        String parentId = blueId("parent");
        Map<String, Node> definitions = new HashMap<>();
        definitions.put(parentId, new Node().value("parent definition"));
        for (int index = 0; index < 12; index++) {
            definitions.put(blueId("child-" + index),
                    new Node().type(new Node().blueId(parentId)));
        }
        NodeProvider provider = blueId -> {
            Node definition = definitions.get(blueId);
            return definition != null
                    ? Collections.singletonList(definition.clone())
                    : Collections.<Node>emptyList();
        };
        DeclaredTypeLineageMatcher matcher = new DeclaredTypeLineageMatcher(provider, policy);

        for (String childId : definitions.keySet()) {
            if (!childId.equals(parentId)) {
                assertTrue(matcher.isSameOrDescendant(
                        new Node().blueId(childId), new Node().blueId(parentId)));
                assertTrue(matcher.cacheSize() <= 3);
                assertTrue(matcher.cacheWeightBytes() <= 2_048L);
            }
        }

        matcher.clearCaches();

        assertEquals(0, matcher.cacheSize());
        assertEquals(0L, matcher.cacheWeightBytes());
    }

    @Test
    void documentProcessorClearCachesCascadesToLoaderAndMatchingService() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create().registerDefaults().build();
        TypeClassResolver resolver = new TypeClassResolver("blue.language.processor.model");
        ContractMatchingService matchingService = new ContractMatchingService();
        DocumentProcessor processor = new DocumentProcessor(
                registry, resolver, null, null, matchingService, ProcessingMetricsSink.NOOP);

        loadEmpty(processor.contractLoader(), "/cached", ProcessingMetricsSink.NOOP);
        FrozenNode value = FrozenNode.fromResolvedNode(new Node().value("match"));
        assertTrue(matchingService.matches(value, value));
        assertTrue(processor.contractLoader().cacheSize() > 0);
        assertTrue(matchingService.matcherCacheSize() > 0);

        processor.clearCaches();

        assertEquals(0, processor.contractLoader().cacheSize());
        assertEquals(0, matchingService.matcherCacheSize());
        assertEquals(0, matchingService.declaredTypeLineageCacheSize());
        assertTrue(matchingService.matches(value, value),
                "clearing must not disable safe recomputation");
    }

    @Test
    void reentrantCloseDuringProcessingDefersDetachmentWithoutDeadlock() {
        AtomicReference<DocumentProcessor> reference = new AtomicReference<>();
        AtomicBoolean closeOnce = new AtomicBoolean();
        ProcessingMetricsSink metrics = new ProcessingMetricsSink() {
            @Override
            public void addEventPreprocessNanos(long nanos) {
                if (closeOnce.compareAndSet(false, true)) {
                    reference.get().close();
                }
            }
        };
        DocumentProcessor processor = DocumentProcessor.builder()
                .withProcessingMetricsSink(metrics)
                .build();
        reference.set(processor);

        DocumentProcessingResult result = processor.processDocument(
                new Node(), new Node().value("event"));

        assertTrue(result != null);
        assertTrue(processor.isClosed());
        assertFalse(processor.supportsSnapshotProcessing());
        assertEquals(0, processor.cacheEntryCount());
        assertEquals(0L, processor.cacheWeightBytes());
    }

    private ContractLoader loader(BlueCachePolicy policy) {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create().registerDefaults().build();
        TypeClassResolver resolver = new TypeClassResolver("blue.language.processor.model");
        return new ContractLoader(registry, new NodeToObjectConverter(resolver), resolver, policy);
    }

    private ContractBundle loadEmpty(ContractLoader loader,
                                     String scope,
                                     ProcessingMetricsSink metrics) {
        return loader.load((Node) null, (FrozenNode) null, scope, metrics);
    }

    private String blueId(String value) {
        return BlueIdCalculator.calculateBlueId(new Node().value(value));
    }

    private static final class RecordingMetrics implements ProcessingMetricsSink {
        private long hits;
        private long misses;

        @Override
        public void incrementBundleLoadCacheHits() {
            hits++;
        }

        @Override
        public void incrementBundleLoadCacheMisses() {
            misses++;
        }
    }
}
