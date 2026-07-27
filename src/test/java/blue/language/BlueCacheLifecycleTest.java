package blue.language;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.processor.ProcessingMetricsSnapshot;
import blue.language.processor.ProcessingMetricsSink;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.RecordingProcessingMetricsSink;
import blue.language.processor.ContractProcessor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.limits.Limits;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueCacheLifecycleTest {

    @Test
    void derivedSnapshotsAreWeightAndEntryBoundedWithoutChangingReloadIdentity() {
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(2, 1024L * 1024L)
                .canonicalAliases(2, 1024L)
                .maximumDerivedEntryWeightBytes(1024L * 1024L)
                .build();
        Blue blue = Blue.withCachePolicy(policy);
        ResolvedSnapshot first = null;

        for (int index = 0; index < 6; index++) {
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(index));
            if (index == 0) {
                first = snapshot;
            }
        }

        BlueCacheStats.Region derived = blue.cacheStats().region("derivedResolvedSnapshots");
        assertTrue(derived.entries() <= 2);
        assertTrue(derived.evictions() >= 4L);
        ResolvedSnapshot reloaded = blue.resolveToSnapshot(first.canonicalRoot());
        assertEquals(first.blueId(), reloaded.blueId());
        assertEquals(blue.nodeToJson(first.resolvedRoot()), blue.nodeToJson(reloaded.resolvedRoot()));
    }

    @Test
    void publicAuthoritativeSnapshotRegistrationRemainsPinnedAcrossDerivedEviction() {
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(1, 1024L * 1024L)
                .canonicalAliases(1, 1024L)
                .maximumDerivedEntryWeightBytes(1024L * 1024L)
                .build();
        Blue blue = Blue.withCachePolicy(policy);
        ResolvedSnapshot authoritative = blue.resolveToSnapshot(document(10));
        blue.clearResolvedSnapshotCache();
        blue.cacheResolvedSnapshot(authoritative);

        for (int index = 0; index < 5; index++) {
            blue.resolveToSnapshot(document(100 + index));
        }

        ResolvedSnapshot loaded = blue.loadSnapshot(authoritative.canonicalRoot());
        assertSame(authoritative, loaded);
        assertEquals(1, blue.cacheStats().region("pinnedAuthoritativeSnapshots").entries());
        assertTrue(blue.cacheStats().region("derivedResolvedSnapshots").entries() <= 1);
    }

    @Test
    void disabledPolicySkipsReloadableRetentionButKeepsExplicitPins() {
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.disabled());
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(20));

        assertEquals(0, blue.cacheStats().region("derivedResolvedSnapshots").entries());
        assertEquals(0, blue.cacheStats().region("canonicalAliases").entries());
        assertEquals(0, blue.cacheStats().region("recentProcessingSnapshots").entries());
        assertEquals(0, blue.cacheStats().region("verifiedReferences").entries());

        blue.cacheResolvedSnapshot(snapshot);

        assertSame(snapshot, blue.cachedResolvedSnapshot(snapshot.blueId())
                .orElseThrow(AssertionError::new));
        assertEquals(1, blue.cacheStats().region("pinnedAuthoritativeSnapshots").entries());
        assertTrue(blue.cacheStats().region("derivedResolvedSnapshots")
                .oversizedRejections() > 0L);
    }

    @Test
    void configurationRefreshPreservesCallerPinnedAuthoritativeContent() {
        Blue blue = new Blue(node -> null);
        ResolvedSnapshot authoritative = blue.resolveToSnapshot(document(17));
        blue.cacheResolvedSnapshot(authoritative);

        blue.preprocessingAliases(Collections.singletonMap("alias", authoritative.blueId()));
        blue.setGlobalLimits(Limits.NO_LIMITS);
        blue.nodeProvider(node -> null);

        ResolvedSnapshot loaded = blue.loadSnapshot(authoritative.blueId());
        assertEquals(authoritative.blueId(), loaded.blueId());
        assertEquals(blue.nodeToJson(authoritative.resolvedRoot()),
                blue.nodeToJson(loaded.resolvedRoot()));
        assertTrue(blue.cacheStats().region("pinnedAuthoritativeSnapshots").entries() > 0);
    }

    @Test
    void refreshedProcessorRetainsSharedBorrowedRegistryAndTypeMappingsSafely() {
        DocumentProcessor shared = new DocumentProcessor();
        Blue first = new Blue().documentProcessor(shared);
        Blue second = new Blue().documentProcessor(shared);

        first.nodeProvider(node -> null);
        DocumentProcessor refreshed = first.getDocumentProcessor();
        assertSame(shared.getContractRegistry(), refreshed.getContractRegistry());
        assertSame(shared.getContractTypeResolver(), refreshed.getContractTypeResolver());

        RegistrationMarkerProcessor processor = new RegistrationMarkerProcessor();
        second.registerContractProcessor("shared-registration", processor);

        assertSame(processor,
                refreshed.getContractRegistry().processors().get("shared-registration"));
        assertSame(RegistrationMarker.class,
                refreshed.getContractTypeResolver().resolveClass("shared-registration"));
    }

    @Test
    void ordinaryCacheClearKeepsOwnedProcessorUsable() {
        Blue blue = new Blue();
        DocumentProcessor processor = blue.getDocumentProcessor();

        blue.clearResolvedSnapshotCache();

        assertFalse(processor.isClosed());
        assertTrue(blue.initializeDocument(new Node()).document() != null);
    }

    @Test
    void injectedProcessorRemainsBorrowedAcrossRuntimeClose() {
        DocumentProcessor shared = new DocumentProcessor();
        Blue first = new Blue().documentProcessor(shared);
        Blue second = new Blue().documentProcessor(shared);

        first.close();

        assertFalse(shared.isClosed());
        assertSame(shared, second.getDocumentProcessor());
        second.initializeDocument(new Node());
        second.close();
        assertFalse(shared.isClosed());
    }

    @Test
    void injectingBorrowedProcessorClosesOnlyDisplacedOwnedProcessor() {
        Blue blue = new Blue();
        DocumentProcessor owned = blue.getDocumentProcessor();
        owned.markersFor(new Node(), "/");
        DocumentProcessor borrowed = new DocumentProcessor();

        blue.documentProcessor(borrowed);

        assertTrue(owned.isClosed());
        assertEquals(0, owned.cacheEntryCount());
        assertFalse(borrowed.isClosed());
        blue.close();
        assertFalse(borrowed.isClosed());
    }

    @Test
    void reinjectingSameOwnedProcessorDoesNotLaunderOwnership() {
        Blue blue = new Blue();
        DocumentProcessor owned = blue.getDocumentProcessor();

        blue.documentProcessor(owned);
        blue.close();

        assertTrue(owned.isClosed());
        assertThrows(IllegalStateException.class,
                () -> owned.markersFor(new Node(), "/"));
    }

    @Test
    void aliasAndLimitChangesPreserveBorrowedProcessorOwnership() {
        DocumentProcessor borrowed = new DocumentProcessor();
        Blue blue = new Blue().documentProcessor(borrowed);

        blue.addPreprocessingAliases(Collections.singletonMap("one", "value"));
        assertSame(borrowed, blue.getDocumentProcessor());
        blue.preprocessingAliases(Collections.singletonMap("two", "value"));
        assertSame(borrowed, blue.getDocumentProcessor());
        blue.setGlobalLimits(Limits.NO_LIMITS);
        assertSame(borrowed, blue.getDocumentProcessor());

        blue.close();
        assertFalse(borrowed.isClosed());
    }

    @Test
    void reentrantMetricsCloseIsRejectedWithoutDeadlockOrImplicitShutdown() {
        Blue blue = new Blue();
        AtomicBoolean closeOnce = new AtomicBoolean();
        blue.getDocumentProcessor().processingMetricsSink(new ProcessingMetricsSink() {
            @Override
            public void setCacheCurrentWeightBytes(String cacheName, long bytes) {
                if (closeOnce.compareAndSet(false, true)) {
                    blue.close();
                }
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> blue.resolveToSnapshot(document(1)));

        assertEquals("Blue runtime cannot close from active runtime work",
                failure.getMessage());
        assertFalse(blue.isClosed());
        blue.close();
        assertTrue(blue.isClosed());
        assertEquals(0, blue.cacheStats().entries());
        assertEquals(0L, blue.cacheStats().currentWeightBytes());
    }

    @Test
    void closeTimeMetricsMayReenterCloseWithoutRecursion() {
        Blue blue = new Blue();
        AtomicInteger callbacks = new AtomicInteger();
        blue.getDocumentProcessor().processingMetricsSink(new ProcessingMetricsSink() {
            @Override
            public void incrementRuntimeCloseCalls() {
                callbacks.incrementAndGet();
                blue.close();
            }
        });

        blue.close();

        assertTrue(blue.isClosed());
        assertEquals(1, callbacks.get());
        assertEquals(0, blue.cacheStats().entries());
    }

    @Test
    void concurrentCloseWaitsForOwnedProcessorRelease() throws Exception {
        BlockingCloseDocumentProcessor processor = new BlockingCloseDocumentProcessor();
        Blue blue = new Blue().documentProcessor(processor);
        Field ownership = Blue.class.getDeclaredField("documentProcessorOwned");
        ownership.setAccessible(true);
        ownership.setBoolean(blue, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch secondReturned = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                blue.close();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        first.start();
        assertTrue(processor.closeEntered.await(5L, TimeUnit.SECONDS));
        Thread second = new Thread(() -> {
            try {
                blue.close();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                secondReturned.countDown();
            }
        });
        second.start();

        assertFalse(secondReturned.await(200L, TimeUnit.MILLISECONDS));
        assertFalse(processor.isClosed());
        processor.allowClose.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5L));
        second.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(failure.get());
        assertTrue(processor.isClosed());
    }

    @Test
    void concurrentClosersQueuedBehindInvalidationShareOneCloseCompletion() throws Exception {
        BlockingCloseDocumentProcessor processor = new BlockingCloseDocumentProcessor();
        Blue blue = new Blue().documentProcessor(processor);
        Field ownership = Blue.class.getDeclaredField("documentProcessorOwned");
        ownership.setAccessible(true);
        ownership.setBoolean(blue, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread clearing = new Thread(() -> {
            try {
                blue.clearResolvedSnapshotCache();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        clearing.start();
        assertTrue(processor.clearEntered.await(5L, TimeUnit.SECONDS));

        CountDownLatch closersStarted = new CountDownLatch(2);
        CountDownLatch anyCloserReturned = new CountDownLatch(1);
        Thread first = closingThread(blue, failure, closersStarted, anyCloserReturned);
        Thread second = closingThread(blue, failure, closersStarted, anyCloserReturned);
        first.start();
        second.start();
        assertTrue(closersStarted.await(5L, TimeUnit.SECONDS));
        assertFalse(anyCloserReturned.await(200L, TimeUnit.MILLISECONDS));

        processor.allowClear.countDown();
        assertTrue(processor.closeEntered.await(5L, TimeUnit.SECONDS));
        assertFalse(anyCloserReturned.await(200L, TimeUnit.MILLISECONDS),
                "all concurrent close callers must await the owned close cleanup");

        processor.allowClose.countDown();
        clearing.join(TimeUnit.SECONDS.toMillis(5L));
        first.join(TimeUnit.SECONDS.toMillis(5L));
        second.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(clearing.isAlive());
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(failure.get());
        assertTrue(processor.isClosed());
    }

    private static Thread closingThread(Blue blue,
                                        AtomicReference<Throwable> failure,
                                        CountDownLatch started,
                                        CountDownLatch returned) {
        return new Thread(() -> {
            started.countDown();
            try {
                blue.close();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                returned.countDown();
            }
        });
    }

    @Test
    void oversizedDerivedSnapshotIsUsableButNotRetainedAndCanStillBePinned() {
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(4, 4096L)
                .maximumDerivedEntryWeightBytes(64L)
                .build();
        Blue blue = Blue.withCachePolicy(policy);

        ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(1));

        assertEquals(0, blue.cacheStats().region("derivedResolvedSnapshots").entries());
        assertEquals(1L,
                blue.cacheStats().region("derivedResolvedSnapshots").oversizedRejections());
        blue.cacheResolvedSnapshot(snapshot);
        assertEquals(1, blue.cacheStats().region("pinnedAuthoritativeSnapshots").entries());
    }

    @Test
    void closeIsIdempotentReleasesOwnedStateAndRejectsRuntimeWork() {
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.boundedDefaults());
        DocumentProcessor leakedProcessor = blue.getDocumentProcessor();
        leakedProcessor.processingMetricsSink(metrics);
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(1));
        blue.cacheResolvedSnapshot(snapshot);
        assertTrue(blue.cacheStats().currentWeightBytes() > 0L);

        blue.close();
        blue.close();

        assertTrue(blue.isClosed());
        assertEquals(0L, blue.cacheStats().currentWeightBytes());
        assertEquals(0, blue.cacheStats().entries());
        assertThrows(IllegalStateException.class,
                () -> blue.resolveToSnapshot(document(2)));
        assertThrows(IllegalStateException.class,
                () -> blue.cacheResolvedSnapshot(snapshot));
        assertThrows(IllegalStateException.class,
                () -> blue.cacheResolvedSnapshots(Collections.emptyList()));
        assertThrows(IllegalStateException.class, blue::clearResolvedSnapshotCache);
        assertThrows(IllegalStateException.class,
                () -> blue.registerTypeDictionaries(Collections.emptyList()));
        assertThrows(IllegalStateException.class,
                () -> blue.registerExternalContractType("closed", null, null));
        assertThrows(IllegalStateException.class,
                () -> blue.isInitialized(document(2)));
        assertThrows(IllegalStateException.class,
                () -> blue.isInitialized(snapshot));
        assertThrows(IllegalStateException.class,
                () -> blue.resolvePreservingPaths(document(2),
                        Limits.NO_LIMITS,
                        Collections.singletonList("/")));
        assertThrows(IllegalStateException.class,
                () -> blue.nodeMatchesType(new Node(), new Node()));
        assertThrows(IllegalStateException.class,
                () -> blue.nodeMatchesType(
                        snapshot.frozenResolvedRoot(), snapshot.frozenResolvedRoot()));
        assertThrows(IllegalStateException.class,
                () -> blue.nodeMatchesType(
                        snapshot, "/", snapshot.frozenResolvedRoot()));
        assertThrows(IllegalStateException.class,
                () -> blue.extend(document(2), Limits.NO_LIMITS));
        assertThrows(IllegalStateException.class,
                () -> blue.preprocess(document(2)));
        assertThrows(IllegalStateException.class,
                () -> blue.yamlToNode("value: 2"));
        assertThrows(IllegalStateException.class,
                () -> blue.jsonToNode("{\"value\":2}"));
        assertThrows(IllegalStateException.class,
                () -> blue.determineClass(document(2)));
        assertThrows(IllegalStateException.class,
                () -> blue.nodeToObject(document(2), Node.class));
        assertThrows(IllegalStateException.class,
                () -> blue.isNodeSubtypeOf(document(2), document(3)));
        assertThrows(IllegalStateException.class,
                () -> blue.cachedResolvedSnapshot(snapshot.blueId()));
        assertThrows(IllegalStateException.class, blue::conformanceEngine);
        assertThrows(IllegalStateException.class,
                () -> leakedProcessor.initializeDocument(document(4)),
                "a processor handle obtained before close must observe cache invalidation");
        assertThrows(IllegalStateException.class,
                () -> leakedProcessor.markersFor(new Node(), "/"),
                "a leaked processor handle must not repopulate owned caches after runtime close");
        assertTrue(leakedProcessor.isClosed());
        assertFalse(leakedProcessor.supportsSnapshotProcessing(),
                "closed leaked handles must detach the runtime snapshot collaborator");
        assertEquals(0, leakedProcessor.cacheEntryCount());
        assertTrue(blue.nodeToJson(document(3)).contains("value"),
                "pure serialization remains available after close");
        assertEquals("3", blue.parseSourceJson("{\"value\":3}").getValue().toString());

        ProcessingMetricsSnapshot recorded = metrics.snapshot();
        assertEquals(2L, recorded.counter("runtimeCloseCalls"));
        assertTrue(recorded.counter("runtimeCloseReleasedWeightBytes") > 0L);
    }

    @Test
    void closeDoesNotDeadlockWithConcurrentCacheReaders() throws Exception {
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.boundedDefaults());
        blue.resolveToSnapshot(document(1));
        Thread reader = new Thread(() -> {
            for (int index = 0; index < 10_000 && !blue.isClosed(); index++) {
                blue.cacheStats();
            }
        });
        reader.start();

        blue.close();
        reader.join(TimeUnit.SECONDS.toMillis(5L));

        assertTrue(!reader.isAlive(), "cache reader must finish when close completes");
    }

    @Test
    void closeFromPreservedPathPredicateIsRejectedForTheWholeCompositeOperation() {
        Blue blue = new Blue();
        AtomicReference<IllegalStateException> closeFailure = new AtomicReference<>();

        Node resolved = blue.resolvePreservingMatchingPaths(
                document(5),
                Collections.singletonList("/value"),
                node -> {
                    closeFailure.set(assertThrows(IllegalStateException.class, blue::close));
                    return true;
                });

        assertEquals("Blue runtime cannot close from active runtime work",
                closeFailure.get().getMessage());
        assertFalse(blue.isClosed());
        assertTrue(resolved != null);
        blue.close();
    }

    @Test
    void closeWaitsForLazyProcessorPublicationAndReleasesThePublishedProcessor() throws Exception {
        BlockingProviderBlue blue = new BlockingProviderBlue();
        Field processorField = Blue.class.getDeclaredField("documentProcessor");
        processorField.setAccessible(true);
        processorField.set(blue, null);
        blue.blockProvider = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread getter = new Thread(() -> {
            try {
                blue.getDocumentProcessor();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        getter.start();
        assertTrue(blue.providerEntered.await(5L, TimeUnit.SECONDS));

        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            blue.close();
            closeReturned.countDown();
        });
        closer.start();
        assertTrue(closeStarted.await(5L, TimeUnit.SECONDS));
        assertFalse(closeReturned.await(200L, TimeUnit.MILLISECONDS),
                "close must serialize with an in-flight lazy processor publication");

        blue.releaseProvider.countDown();
        getter.join(TimeUnit.SECONDS.toMillis(5L));
        closer.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(getter.isAlive());
        assertFalse(closer.isAlive());
        assertNull(failure.get());
        assertTrue(blue.isClosed());
        assertNull(processorField.get(blue));
        assertEquals(0, blue.cacheStats().entries());
        assertEquals(0L, blue.cacheStats().currentWeightBytes());
    }

    @Test
    void closeWaitsForAdmittedOwnedProcessingThenReleasesItsPublication() throws Exception {
        Node completedDocument = document(42);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                BlueIdCalculator.calculateBlueId(completedDocument));
        BlockingDocumentProcessor processor = new BlockingDocumentProcessor(completedSnapshot);
        Blue blue = new Blue().documentProcessor(processor);
        Field ownership = Blue.class.getDeclaredField("documentProcessorOwned");
        ownership.setAccessible(true);
        ownership.setBoolean(blue, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread processing = new Thread(() -> {
            try {
                blue.processDocument(document(1), new Node().value("event"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        processing.start();
        assertTrue(processor.entered.await(5L, TimeUnit.SECONDS));

        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closing = new Thread(() -> {
            try {
                blue.close();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                closeReturned.countDown();
            }
        });
        closing.start();
        assertFalse(closeReturned.await(200L, TimeUnit.MILLISECONDS));
        processor.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        closing.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(processing.isAlive());
        assertFalse(closing.isAlive());
        assertNull(failure.get());
        assertTrue(blue.isClosed());
        assertTrue(processor.isClosed());
        assertEquals(0, blue.cacheStats().region("recentProcessingSnapshots").entries());
        assertEquals(0L,
                blue.cacheStats().region("recentProcessingSnapshots").currentWeightBytes());
    }

    @Test
    void closeWaitsForAdmittedDirectResolutionBeforeReleasingCaches() throws Exception {
        Node canonical = document(52);
        String blueId = BlueIdCalculator.calculateBlueId(canonical);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        Blue blue = new Blue(requestedBlueId -> {
            providerEntered.countDown();
            try {
                if (!releaseProvider.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release snapshot provider");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return Collections.singletonList(canonical.clone());
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ResolvedSnapshot> result = new AtomicReference<>();
        Thread resolving = new Thread(() -> {
            try {
                result.set(blue.loadSnapshot(blueId));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        resolving.start();
        assertTrue(providerEntered.await(5L, TimeUnit.SECONDS));

        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closing = new Thread(() -> {
            try {
                blue.close();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                closeReturned.countDown();
            }
        });
        closing.start();
        assertFalse(closeReturned.await(200L, TimeUnit.MILLISECONDS));
        assertThrows(IllegalStateException.class, () -> blue.loadSnapshot(blueId),
                "close must reject new work while draining the admitted resolution");

        releaseProvider.countDown();
        resolving.join(TimeUnit.SECONDS.toMillis(5L));
        closing.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(resolving.isAlive());
        assertFalse(closing.isAlive());
        assertNull(failure.get());
        assertEquals(blueId, result.get().blueId());
        assertTrue(blue.isClosed());
        assertEquals(0, blue.cacheStats().entries());
    }

    @Test
    void closeWaitsAcrossCompositeObjectConversionAndRuntimePhase() throws Exception {
        BlockingObjectConversionBlue blue = new BlockingObjectConversionBlue();
        Map<String, Object> source = new HashMap<>();
        source.put("payload", "composite-operation");
        AtomicReference<ResolvedSnapshot> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread resolving = new Thread(() -> {
            try {
                result.set(blue.resolveToSnapshot((Object) source));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        resolving.start();
        assertTrue(blue.conversionCompleted.await(5L, TimeUnit.SECONDS));

        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closing = new Thread(() -> {
            try {
                blue.close();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                closeReturned.countDown();
            }
        });
        closing.start();
        assertFalse(closeReturned.await(200L, TimeUnit.MILLISECONDS),
                "close must wait across conversion and the runtime-backed second phase");

        blue.releaseConversion.countDown();
        resolving.join(TimeUnit.SECONDS.toMillis(5L));
        closing.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(resolving.isAlive());
        assertFalse(closing.isAlive());
        assertNull(failure.get());
        assertTrue(result.get() != null);
        assertTrue(blue.isClosed());
    }

    @Test
    void providerReplacementWaitsForRecursiveExpandAndCannotMixProviders() throws Exception {
        CountDownLatch rootFetchEntered = new CountDownLatch(1);
        CountDownLatch releaseRootFetch = new CountDownLatch(1);
        Node originalLeaf = new Node().value("original");
        String originalLeafBlueId = BlueIdCalculator.calculateBlueId(originalLeaf);
        Node originalRoot = new Node().properties(
                "child", new Node().blueId(originalLeafBlueId));
        String originalRootBlueId = BlueIdCalculator.calculateBlueId(originalRoot);
        Node replacementLeaf = new Node().value("replacement");
        String replacementLeafBlueId = BlueIdCalculator.calculateBlueId(replacementLeaf);
        NodeProvider original = blueId -> {
            if (originalRootBlueId.equals(blueId)) {
                rootFetchEntered.countDown();
                try {
                    if (!releaseRootFetch.await(5L, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release root expansion");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return Collections.singletonList(originalRoot.clone());
            }
            return originalLeafBlueId.equals(blueId)
                    ? Collections.singletonList(originalLeaf.clone())
                    : null;
        };
        Blue blue = new Blue(original);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Node> expanded = new AtomicReference<>();
        Thread expanding = new Thread(() -> {
            try {
                expanded.set(blue.expand(new Node().blueId(originalRootBlueId)));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        expanding.start();
        assertTrue(rootFetchEntered.await(5L, TimeUnit.SECONDS));

        CountDownLatch replacementReturned = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            try {
                blue.nodeProvider(blueId -> replacementLeafBlueId.equals(blueId)
                        ? Collections.singletonList(replacementLeaf.clone())
                        : null);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                replacementReturned.countDown();
            }
        });
        replacement.start();
        assertFalse(replacementReturned.await(200L, TimeUnit.MILLISECONDS));

        releaseRootFetch.countDown();
        expanding.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(expanding.isAlive());
        assertFalse(replacement.isAlive());
        assertNull(failure.get());
        assertEquals("original", expanded.get().getProperties().get("child").getValue());
        assertEquals("replacement",
                blue.expand(new Node().blueId(replacementLeafBlueId)).getValue());
    }

    @Test
    void providerReplacementWaitsForSubtypeTraversalAndCannotMixProviders() throws Exception {
        Node superType = new Node().name("Subtype gate supertype");
        String superTypeBlueId = BlueIdCalculator.calculateBlueId(superType);
        Node candidateType = new Node()
                .name("Subtype gate candidate")
                .type(new Node().blueId(superTypeBlueId));
        String candidateTypeBlueId = BlueIdCalculator.calculateBlueId(candidateType);
        CountDownLatch candidateFetchEntered = new CountDownLatch(1);
        CountDownLatch releaseCandidateFetch = new CountDownLatch(1);
        NodeProvider original = blueId -> {
            if (candidateTypeBlueId.equals(blueId)) {
                candidateFetchEntered.countDown();
                try {
                    if (!releaseCandidateFetch.await(5L, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release subtype lookup");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return Collections.singletonList(candidateType.clone());
            }
            if (superTypeBlueId.equals(blueId)) {
                return Collections.singletonList(superType.clone());
            }
            return null;
        };
        Blue blue = new Blue(original);
        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread matching = new Thread(() -> {
            try {
                result.set(blue.isNodeSubtypeOf(
                        new Node().blueId(candidateTypeBlueId),
                        new Node().blueId(superTypeBlueId)));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        matching.start();
        assertTrue(candidateFetchEntered.await(5L, TimeUnit.SECONDS));

        CountDownLatch replacementReturned = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            try {
                blue.nodeProvider(blueId -> null);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                replacementReturned.countDown();
            }
        });
        replacement.start();
        assertFalse(replacementReturned.await(200L, TimeUnit.MILLISECONDS));

        releaseCandidateFetch.countDown();
        matching.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(matching.isAlive());
        assertFalse(replacement.isAlive());
        assertNull(failure.get());
        assertEquals(Boolean.TRUE, result.get());
        assertFalse(blue.isNodeSubtypeOf(
                new Node().blueId(candidateTypeBlueId),
                new Node().blueId(superTypeBlueId)));
    }

    @Test
    void retainedConformanceEngineCannotPublishStaleMergerEvidenceAfterRefresh() {
        Node type = new Node().properties("typeMarker", new Node().value(true));
        String typeBlueId = BlueIdCalculator.calculateBlueId(type);
        NodeProvider oldProvider = blueId -> typeBlueId.equals(blueId)
                ? Collections.singletonList(type.clone()) : null;
        NodeProvider newProvider = blueId -> typeBlueId.equals(blueId)
                ? Collections.singletonList(type.clone()) : null;
        Blue blue = new Blue(oldProvider, new EvidenceMergingProcessor("oldEvidence"));
        ConformanceEngine staleEngine = blue.conformanceEngine();
        try {
            blue.nodeProvider(newProvider);
            blue.mergingProcessor(new EvidenceMergingProcessor("newEvidence"));
            assertEquals(0, blue.resolvedReferenceCacheSize());

            assertTrue(staleEngine.conforms(
                    new Node().type(new Node().blueId(typeBlueId))));
            assertEquals(0, blue.resolvedReferenceCacheSize(),
                    "a retained engine must not publish into Blue's current cache generation");

            Node resolved = blue.resolve(new Node().type(new Node().blueId(typeBlueId)));
            assertTrue(resolved.getProperties().get("newEvidence") != null);
            assertTrue(resolved.getProperties().get("oldEvidence") == null,
                    "current resolution must not consume stale merger output");
        } finally {
            staleEngine.close();
        }
    }

    @Test
    void conformanceEngineRetainsVisibilityOfCallerPinnedVerifiedSnapshots() {
        Node type = new Node().properties("pinnedMarker", new Node().value(true));
        String typeBlueId = BlueIdCalculator.calculateBlueId(type);
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(type);
        Blue source = new Blue(provider);
        Blue target = new Blue(blueId -> null);
        ConformanceEngine engine = null;
        try {
            ResolvedSnapshot verifiedType = source.loadSnapshot(typeBlueId);
            target.cacheResolvedSnapshot(verifiedType);

            engine = target.conformanceEngine();
            assertTrue(engine.conforms(
                    new Node().type(new Node().blueId(typeBlueId))));

            target.clearResolvedSnapshotCache();
            assertTrue(engine.conforms(
                    new Node().type(new Node().blueId(typeBlueId))),
                    "the retained handle must own its pinned-evidence snapshot");
        } finally {
            if (engine != null) {
                engine.close();
            }
            target.close();
            source.close();
        }
    }

    @Test
    void displacedProcessorCannotPublishOldSnapshotAfterProviderReplacement() throws Exception {
        Node completedDocument = document(77);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                BlueIdCalculator.calculateBlueId(completedDocument));
        BlockingDocumentProcessor processor = new BlockingDocumentProcessor(completedSnapshot);
        Blue blue = new Blue().documentProcessor(processor);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread processing = new Thread(() -> {
            try {
                blue.processDocument(document(1), new Node().value("event"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        processing.start();
        assertTrue(processor.entered.await(5L, TimeUnit.SECONDS));

        AtomicReference<Throwable> replacementFailure = new AtomicReference<>();
        CountDownLatch replacementReturned = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            try {
                blue.nodeProvider(node -> null);
            } catch (Throwable throwable) {
                replacementFailure.set(throwable);
            } finally {
                replacementReturned.countDown();
            }
        });
        replacement.start();
        // Configuration replacement is a cache-generation barrier: it must
        // wait until the old processor can no longer publish its result.
        assertFalse(replacementReturned.await(200L, TimeUnit.MILLISECONDS));
        processor.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(processing.isAlive());
        assertFalse(replacement.isAlive());
        assertNull(failure.get());
        assertNull(replacementFailure.get());
        assertEquals(0, blue.cacheStats().region("recentProcessingSnapshots").entries());
        assertEquals(0, blue.cacheStats().region("derivedResolvedSnapshots").entries());
    }

    @Test
    void processorRegistrationWaitsForConfigurationRefreshAndTargetsPublishedProcessor()
            throws Exception {
        Node completedDocument = document(78);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                BlueIdCalculator.calculateBlueId(completedDocument));
        BlockingDocumentProcessor displaced = new BlockingDocumentProcessor(completedSnapshot);
        Blue blue = new Blue().documentProcessor(displaced);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread processing = new Thread(() -> {
            try {
                blue.processDocument(document(1), new Node().value("event"));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        processing.start();
        assertTrue(displaced.entered.await(5L, TimeUnit.SECONDS));

        CountDownLatch replacementReturned = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            try {
                blue.nodeProvider(node -> null);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                replacementReturned.countDown();
            }
        });
        replacement.start();
        assertFalse(replacementReturned.await(200L, TimeUnit.MILLISECONDS));

        RegistrationMarkerProcessor processor = new RegistrationMarkerProcessor();
        CountDownLatch registrationReturned = new CountDownLatch(1);
        Thread registration = new Thread(() -> {
            try {
                blue.registerContractProcessor("registration-race", processor);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                registrationReturned.countDown();
            }
        });
        registration.start();
        assertFalse(displaced.registrationEntered.await(200L, TimeUnit.MILLISECONDS),
                "registration must not mutate the displaced processor during refresh");
        assertFalse(registrationReturned.await(200L, TimeUnit.MILLISECONDS));

        displaced.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));
        registration.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(processing.isAlive());
        assertFalse(replacement.isAlive());
        assertFalse(registration.isAlive());
        assertNull(failure.get());
        assertSame(processor, blue.getDocumentProcessor().getContractRegistry()
                .processors().get("registration-race"));
    }

    @Test
    void explicitClearRejectsLateBorrowedProcessorPublication() throws Exception {
        Node completedDocument = document(88);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                BlueIdCalculator.calculateBlueId(completedDocument));
        BlockingDocumentProcessor processor = new BlockingDocumentProcessor(completedSnapshot);
        Blue blue = new Blue().documentProcessor(processor);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread processing = new Thread(() -> {
            try {
                blue.processDocument(document(1), new Node().value("event"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        processing.start();
        assertTrue(processor.entered.await(5L, TimeUnit.SECONDS));

        CountDownLatch clearReturned = new CountDownLatch(1);
        Thread clearing = new Thread(() -> {
            try {
                blue.clearResolvedSnapshotCache();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                clearReturned.countDown();
            }
        });
        clearing.start();
        assertFalse(clearReturned.await(200L, TimeUnit.MILLISECONDS));
        processor.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        clearing.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(processing.isAlive());
        assertFalse(clearing.isAlive());
        assertNull(failure.get());
        assertEquals(0, blue.cacheStats().region("recentProcessingSnapshots").entries());
        assertEquals(0, blue.cacheStats().region("derivedResolvedSnapshots").entries());
    }

    @Test
    void concurrentCloseWaitsForInProgressInvalidationWithoutStrandingGate() throws Exception {
        Node completedDocument = document(89);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                BlueIdCalculator.calculateBlueId(completedDocument));
        BlockingDocumentProcessor processor = new BlockingDocumentProcessor(completedSnapshot);
        Blue blue = new Blue().documentProcessor(processor);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread processing = new Thread(() -> {
            try {
                blue.processDocument(document(1), new Node().value("event"));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        processing.start();
        assertTrue(processor.entered.await(5L, TimeUnit.SECONDS));

        CountDownLatch clearReturned = new CountDownLatch(1);
        Thread clearing = new Thread(() -> {
            try {
                blue.clearResolvedSnapshotCache();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                clearReturned.countDown();
            }
        });
        clearing.start();
        assertFalse(clearReturned.await(200L, TimeUnit.MILLISECONDS));

        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closing = new Thread(() -> {
            try {
                blue.close();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                closeReturned.countDown();
            }
        });
        closing.start();
        assertFalse(closeReturned.await(200L, TimeUnit.MILLISECONDS));

        processor.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        clearing.join(TimeUnit.SECONDS.toMillis(5L));
        closing.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(processing.isAlive());
        assertFalse(clearing.isAlive());
        assertFalse(closing.isAlive());
        assertNull(failure.get());
        assertTrue(blue.isClosed());
        assertTimeoutPreemptively(Duration.ofSeconds(2L), () ->
                assertThrows(IllegalStateException.class,
                        () -> blue.processDocument(document(2), new Node())));
    }

    @Test
    void mergerReplacementWaitsForDirectSnapshotResolutionThenClearsItsResult() throws Exception {
        BlockingMergingProcessor blocking = new BlockingMergingProcessor();
        Blue blue = new Blue(node -> null, blocking);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread resolving = new Thread(() -> {
            try {
                blue.resolveToSnapshot(document(5));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        resolving.start();
        assertTrue(blocking.entered.await(5L, TimeUnit.SECONDS));

        CountDownLatch replacementReturned = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            try {
                blue.mergingProcessor((target, source, provider, resolver) -> { });
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                replacementReturned.countDown();
            }
        });
        replacement.start();
        assertFalse(replacementReturned.await(200L, TimeUnit.MILLISECONDS));

        blocking.release.countDown();
        resolving.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));

        assertFalse(resolving.isAlive());
        assertFalse(replacement.isAlive());
        assertNull(failure.get());
        assertEquals(0, blue.cacheStats().region("derivedResolvedSnapshots").entries());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void aliasReplacementRefreshesTheEagerProcessorAndOwnsCallerMap() throws Exception {
        Node aliasTarget = new Node()
                .name("Alias Target")
                .properties("provided", new Node().value(true));
        BasicNodeProvider provider = new BasicNodeProvider(aliasTarget);
        String targetBlueId = provider.getBlueIdByName("Alias Target");
        Blue blue = new Blue(provider);
        Map<String, String> aliases = new HashMap<>();
        aliases.put("friendly", targetBlueId);

        blue.preprocessingAliases(aliases);
        aliases.put("friendly", "invalid-after-registration");
        Field managerField = DocumentProcessor.class.getDeclaredField("snapshotManager");
        managerField.setAccessible(true);
        ProcessingSnapshotManager manager = (ProcessingSnapshotManager) managerField.get(
                blue.getDocumentProcessor());
        Field aliasesField = manager.getClass().getDeclaredField("aliases");
        aliasesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> capturedAliases =
                (Map<String, String>) aliasesField.get(manager);

        assertEquals(targetBlueId, capturedAliases.get("friendly"));
        assertEquals(targetBlueId, blue.getPreprocessingAliases().get("friendly"));
        assertThrows(UnsupportedOperationException.class,
                () -> blue.getPreprocessingAliases().put("other", targetBlueId));
    }

    @Test
    void verifiedReferenceAccelerationIsBoundedWhileExplicitRegistrationPinsContent() {
        BasicNodeProvider provider = new BasicNodeProvider();
        for (int index = 0; index < 6; index++) {
            provider.addSingleNodes(new Node()
                    .name("Reference Type " + index)
                    .properties("ordinal", new Node().value(index)));
        }
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(2, 1024L * 1024L)
                .transientReferences(2, 1024L * 1024L)
                .maximumDerivedEntryWeightBytes(1024L * 1024L)
                .build();
        Blue blue = new Blue(provider, null, null, policy);
        ResolvedSnapshot authoritative = blue.loadSnapshot(
                provider.getBlueIdByName("Reference Type 0"));
        blue.clearResolvedSnapshotCache();
        blue.cacheResolvedSnapshot(authoritative);

        for (int index = 1; index < 6; index++) {
            blue.loadSnapshot(provider.getBlueIdByName("Reference Type " + index));
        }

        BlueCacheStats.Region references = blue.cacheStats().region("verifiedReferences");
        assertTrue(references.entries() <= 2,
                "one pinned entry plus bounded derived reference evidence");
        assertTrue(references.evictions() > 0L);
        assertSame(authoritative,
                blue.cachedResolvedSnapshot(authoritative.blueId()).orElseThrow(
                        AssertionError::new));
    }

    @Test
    void verifiedReplacementOfPinnedSnapshotPromotesItsReferenceEvidence() {
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .transientReferences(1, 1024L * 1024L)
                .maximumDerivedEntryWeightBytes(1024L * 1024L)
                .build();
        Blue blue = Blue.withCachePolicy(policy);
        Node canonical = document(7);
        String blueId = blue.calculateBlueId(canonical);
        ResolvedSnapshot unverified = new ResolvedSnapshot(
                canonical, canonical.clone(), blueId);

        blue.cacheResolvedSnapshot(unverified);
        ResolvedSnapshot verified = blue.loadSnapshot(canonical);

        assertTrue(verified.verifiedReferenceResolution() != null);
        assertTrue(blue.cacheStats().region("verifiedReferences").isPinned());
        assertSame(verified,
                blue.cachedResolvedSnapshot(blueId).orElseThrow(AssertionError::new));
    }

    private Node document(int value) {
        return new Node()
                .properties("value", new Node().value(value))
                .properties("payload", new Node().value("payload-" + value));
    }

    private static final class BlockingProviderBlue extends Blue {
        private final CountDownLatch providerEntered = new CountDownLatch(1);
        private final CountDownLatch releaseProvider = new CountDownLatch(1);
        private volatile boolean blockProvider;

        @Override
        public NodeProvider getNodeProvider() {
            if (blockProvider) {
                providerEntered.countDown();
                try {
                    if (!releaseProvider.await(5L, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release provider lookup");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return super.getNodeProvider();
        }
    }

    private static final class BlockingObjectConversionBlue extends Blue {
        private final CountDownLatch conversionCompleted = new CountDownLatch(1);
        private final CountDownLatch releaseConversion = new CountDownLatch(1);

        @Override
        public Node objectToNode(Object object) {
            Node converted = super.objectToNode(object);
            conversionCompleted.countDown();
            try {
                if (!releaseConversion.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release object conversion");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return converted;
        }
    }

    private static final class BlockingDocumentProcessor extends DocumentProcessor {
        private final ResolvedSnapshot resultSnapshot;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch registrationEntered = new CountDownLatch(1);

        private BlockingDocumentProcessor(ResolvedSnapshot resultSnapshot) {
            this.resultSnapshot = resultSnapshot;
        }

        @Override
        public DocumentProcessingResult processDocument(Node document, Node event) {
            entered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to complete processing");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return DocumentProcessingResult.of(
                    resultSnapshot.canonicalRoot(),
                    Collections.<Node>emptyList(),
                    0L);
        }

        @Override
        public DocumentProcessor registerContractProcessor(
                String blueId,
                ContractProcessor<? extends Contract> processor) {
            registrationEntered.countDown();
            return super.registerContractProcessor(blueId, processor);
        }
    }

    private static final class RegistrationMarker extends MarkerContract {
    }

    private static final class RegistrationMarkerProcessor
            implements ContractProcessor<RegistrationMarker> {
        @Override
        public Class<RegistrationMarker> contractType() {
            return RegistrationMarker.class;
        }
    }

    private static final class BlockingCloseDocumentProcessor extends DocumentProcessor {
        private final CountDownLatch clearEntered = new CountDownLatch(1);
        private final CountDownLatch allowClear = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);

        @Override
        public void clearCaches() {
            clearEntered.countDown();
            try {
                if (!allowClear.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to clear processor caches");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            super.clearCaches();
        }

        @Override
        public void close() {
            closeEntered.countDown();
            try {
                if (!allowClose.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to close processor");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            super.close();
        }
    }

    private static final class BlockingMergingProcessor implements MergingProcessor {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver) {
            entered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to complete merge");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private static final class EvidenceMergingProcessor implements MergingProcessor {
        private final String evidenceProperty;

        private EvidenceMergingProcessor(String evidenceProperty) {
            this.evidenceProperty = evidenceProperty;
        }

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver) {
            if (source.getProperties() != null
                    && source.getProperties().containsKey("typeMarker")) {
                target.properties(evidenceProperty, new Node().value(true));
            }
        }
    }
}
