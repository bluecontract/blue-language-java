package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.provider.NodeProvider;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.mapping.TypeClassResolver;
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
    void shouldVerifyContractBundleCacheUsesDeterministicWeightedLruBounds() {
        // given
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .conformancePlans(3, 8_192L)
                .maximumDerivedEntryWeightBytes(8_192L)
                .build();
        ContractLoader loader = loader(policy);
        RecordingMetrics metrics = new RecordingMetrics();

        // when
        loadEmpty(loader, "/a", metrics);
        loadEmpty(loader, "/b", metrics);
        loadEmpty(loader, "/c", metrics);
        loadEmpty(loader, "/a", metrics);
        loadEmpty(loader, "/d", metrics);
        loadEmpty(loader, "/a", metrics);
        loadEmpty(loader, "/b", metrics);
        long hitsBeforeClear = metrics.hits;
        long missesBeforeClear = metrics.misses;
        int sizeBeforeClear = loader.cacheSize();
        long weightBeforeClear = loader.cacheWeightBytes();
        loader.clearCaches();

        // then
        assertEquals(2L, hitsBeforeClear);
        assertEquals(5L, missesBeforeClear);
        assertEquals(3, sizeBeforeClear);
        assertTrue(weightBeforeClear <= 8_192L);
        assertEquals(0, loader.cacheSize());
        assertEquals(0L, loader.cacheWeightBytes());
    }

    @Test
    void shouldVerifyDeclaredLineageCacheUsesPolicyBoundsAndCanBeCleared() {
        // given
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

        // when
        boolean everyChildMatches = true;
        boolean stayedWithinEntryLimit = true;
        boolean stayedWithinWeightLimit = true;
        for (String childId : definitions.keySet()) {
            if (!childId.equals(parentId)) {
                everyChildMatches &= matcher.isSameOrDescendant(
                        new Node().blueId(childId),
                        new Node().blueId(parentId));
                stayedWithinEntryLimit &= matcher.cacheSize() <= 3;
                stayedWithinWeightLimit &=
                        matcher.cacheWeightBytes() <= 2_048L;
            }
        }
        matcher.clearCaches();

        // then
        assertTrue(everyChildMatches);
        assertTrue(stayedWithinEntryLimit);
        assertTrue(stayedWithinWeightLimit);
        assertEquals(0, matcher.cacheSize());
        assertEquals(0L, matcher.cacheWeightBytes());
    }

    @Test
    void shouldVerifyDocumentProcessorClearCachesCascadesToLoaderAndMatchingService() {
        // given
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create().registerDefaults().build();
        TypeClassResolver resolver = new TypeClassResolver("blue.language.processor.model");
        ContractMatchingService matchingService = new ContractMatchingService();
        DocumentProcessor processor = new DocumentProcessor(
                registry, resolver, null, null, matchingService, NoOpProcessingObserver.INSTANCE);

        loadEmpty(processor.contractLoader(), "/cached", NoOpProcessingObserver.INSTANCE);

        // when
        FrozenNode value = FrozenNode.fromResolvedNode(new Node().value("match"));
        boolean matchedBeforeClear =
                matchingService.matches(value, value);
        int loaderSizeBeforeClear =
                processor.contractLoader().cacheSize();
        int matcherSizeBeforeClear =
                matchingService.matcherCacheSize();
        processor.clearCaches();
        int loaderSizeAfterClear =
                processor.contractLoader().cacheSize();
        int matcherSizeAfterClear =
                matchingService.matcherCacheSize();
        int lineageSizeAfterClear =
                matchingService.declaredTypeLineageCacheSize();
        boolean matchedAfterClear =
                matchingService.matches(value, value);

        // then
        assertTrue(matchedBeforeClear);
        assertTrue(loaderSizeBeforeClear > 0);
        assertTrue(matcherSizeBeforeClear > 0);
        assertEquals(0, loaderSizeAfterClear);
        assertEquals(0, matcherSizeAfterClear);
        assertEquals(0, lineageSizeAfterClear);
        assertTrue(matchedAfterClear,
                "clearing must not disable safe recomputation");
    }

    @Test
    void shouldVerifyReentrantCloseDuringProcessingDefersDetachmentWithoutDeadlock() {
        // given
        AtomicReference<DocumentProcessor> reference = new AtomicReference<>();
        AtomicBoolean closeOnce = new AtomicBoolean();
        ProcessingObserver metrics = new ProcessingObserver() {
            @Override
            public void record(ProcessingObservation observation) {
                if (observation.metricId()
                        == ProcessingMetricId.EVENT_PREPROCESS_NANOS
                        && closeOnce.compareAndSet(false, true)) {
                    reference.get().close();
                }
            }
        };
        DocumentProcessor processor = DocumentProcessor.builder()
                .observer(metrics)
                .build();
        reference.set(processor);

        // when
        DocumentProcessingResult result = processor.processDocument(
                new Node(), new Node().value("event"));

        // then
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
                                     ProcessingObserver metrics) {
        return loader.load((Node) null, (FrozenNode) null, scope, metrics);
    }

    private String blueId(String value) {
        return BlueIdCalculator.calculateBlueId(new Node().value(value));
    }

    private static final class RecordingMetrics implements ProcessingObserver {
        private long hits;
        private long misses;

        @Override
        public void record(ProcessingObservation observation) {
            if (observation.metricId()
                    == ProcessingMetricId.BUNDLE_LOAD_CACHE_HITS) {
                hits += observation.value();
            } else if (observation.metricId()
                    == ProcessingMetricId.BUNDLE_LOAD_CACHE_MISSES) {
                misses += observation.value();
            }
        }
    }
}
