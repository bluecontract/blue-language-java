package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.processor.ContractProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingMetricsSnapshot;
import blue.language.processor.ProcessingMetricId;
import blue.language.processor.ProcessingObservation;
import blue.language.processor.ProcessingObserver;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.RecordingProcessingObserver;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueCacheLifecycleTest {

    @Test
    void shouldBoundDerivedSnapshotsWithoutChangingReloadIdentity() {
        // given
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(2, 1024L * 1024L)
                .canonicalAliases(2, 1024L)
                .maximumDerivedEntryWeightBytes(1024L * 1024L)
                .build();
        Blue blue = Blue.withCachePolicy(policy);

        // when
        ResolvedSnapshot first = null;
        for (int index = 0; index < 6; index++) {
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(index));
            if (index == 0) {
                first = snapshot;
            }
        }
        BlueCacheStats.Region derived = blue.cacheStats().region("derivedResolvedSnapshots");
        ResolvedSnapshot reloaded = blue.resolveToSnapshot(first.canonicalRoot());

        // then
        assertTrue(derived.entries() <= 2);
        assertTrue(derived.evictions() >= 4L);
        assertEquals(first.blueId(), reloaded.blueId());
        assertEquals(blue.nodeToJson(first.resolvedRoot()), blue.nodeToJson(reloaded.resolvedRoot()));
    }

    @Test
    void shouldKeepPublicAuthoritativeSnapshotPinnedAcrossDerivedEviction() {
        // given
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(1, 1024L * 1024L)
                .canonicalAliases(1, 1024L)
                .maximumDerivedEntryWeightBytes(1024L * 1024L)
                .build();
        Blue blue = Blue.withCachePolicy(policy);

        // when
        ResolvedSnapshot authoritative = blue.resolveToSnapshot(document(10));
        blue.clearResolvedSnapshotCache();
        blue.cacheResolvedSnapshot(authoritative);
        for (int index = 0; index < 5; index++) {
            blue.resolveToSnapshot(document(100 + index));
        }
        ResolvedSnapshot loaded = blue.loadSnapshot(authoritative.canonicalRoot());
        ResolvedSnapshot pinned = blue.cachedResolvedSnapshot(
                        authoritative.blueId())
                .orElseThrow(AssertionError::new);
        int pinnedEntries =
                blue.cacheStats().region("pinnedAuthoritativeSnapshots").entries();
        int derivedEntries =
                blue.cacheStats().region("derivedResolvedSnapshots").entries();

        // then
        assertTrue(authoritative.isSourceBacked());
        assertFalse(pinned.isSourceBacked());
        assertNotSame(authoritative, loaded);
        assertSame(pinned, loaded);
        assertSame(authoritative.frozenCanonicalRoot(),
                loaded.frozenCanonicalRoot());
        assertSame(authoritative.frozenResolvedRoot(),
                loaded.frozenResolvedRoot());
        assertEquals(1, pinnedEntries);
        assertTrue(derivedEntries <= 1);
    }

    @Test
    void shouldSkipReloadableRetentionWhenCachingIsDisabled() {
        // given
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.disabled());

        // when
        blue.resolveToSnapshot(document(20));
        BlueCacheStats stats = blue.cacheStats();

        // then
        assertEquals(0, stats.region("derivedResolvedSnapshots").entries());
        assertEquals(0, stats.region("canonicalAliases").entries());
        assertEquals(0, stats.region("recentProcessingSnapshots").entries());
        assertEquals(0, stats.region("verifiedReferences").entries());
        assertTrue(stats.region("derivedResolvedSnapshots").oversizedRejections() > 0L);
    }

    @Test
    void shouldKeepExplicitPinsWhenCachingIsDisabled() {
        // given
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.disabled());

        // when
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(20));
        blue.cacheResolvedSnapshot(snapshot);
        ResolvedSnapshot cached = blue.cachedResolvedSnapshot(snapshot.blueId())
                .orElseThrow(AssertionError::new);
        int pinnedEntries =
                blue.cacheStats().region("pinnedAuthoritativeSnapshots").entries();

        // then
        assertTrue(snapshot.isSourceBacked());
        assertFalse(cached.isSourceBacked());
        assertNotSame(snapshot, cached);
        assertSame(snapshot.frozenCanonicalRoot(),
                cached.frozenCanonicalRoot());
        assertSame(snapshot.frozenResolvedRoot(),
                cached.frozenResolvedRoot());
        assertEquals(1, pinnedEntries);
    }

    @Test
    void shouldPreserveCallerPinnedAuthoritativeContentAcrossConfigurationRefresh() {
        // given
        Blue blue = new Blue(node -> null);
        ResolvedSnapshot authoritative = blue.resolveToSnapshot(document(17));
        blue.cacheResolvedSnapshot(authoritative);

        // when
        blue.preprocessingAliases(Collections.singletonMap("alias", authoritative.blueId()));
        blue.setGlobalLimits(ResolutionLimits.NO_LIMITS);
        blue.nodeProvider(node -> null);
        ResolvedSnapshot loaded = blue.loadSnapshot(authoritative.blueId());
        int pinnedEntries =
                blue.cacheStats().region("pinnedAuthoritativeSnapshots").entries();

        // then
        assertEquals(authoritative.blueId(), loaded.blueId());
        assertEquals(blue.nodeToJson(authoritative.resolvedRoot()),
                blue.nodeToJson(loaded.resolvedRoot()));
        assertTrue(pinnedEntries > 0);
    }

    @Test
    void shouldSnapshotBorrowedRegistryAndTypeResolverDuringRefresh() {
        // given
        DocumentProcessor shared = new DocumentProcessor();
        Blue first = new Blue().documentProcessor(shared);

        // when
        first.nodeProvider(node -> null);
        DocumentProcessor refreshed = first.getDocumentProcessor();

        // then
        assertNotSame(shared.administration().contractRegistry(), refreshed.administration().contractRegistry());
        assertNotSame(shared.administration().contractTypeResolver(), refreshed.administration().contractTypeResolver());
        assertEquals(shared.administration().contractRegistry().processors(),
                refreshed.administration().contractRegistry().processors());
    }

    @Test
    void shouldIsolateRegistrationIntoOneRuntimeSuccessorGeneration() {
        // given
        DocumentProcessor shared = new DocumentProcessor();
        Blue first = new Blue().documentProcessor(shared);
        Blue second = new Blue().documentProcessor(shared);
        RegistrationMarkerProcessor processor = new RegistrationMarkerProcessor();

        // when
        first.nodeProvider(node -> null);
        DocumentProcessor refreshed = first.getDocumentProcessor();
        second.registerContractProcessor("shared-registration", processor);
        ContractProcessor<?> registeredInFirst =
                refreshed.administration().contractRegistry().processors().get("shared-registration");
        DocumentProcessor secondGeneration = second.getDocumentProcessor();
        ContractProcessor<?> registeredInSecond =
                secondGeneration.administration().contractRegistry()
                        .processors().get("shared-registration");
        Class<?> registeredType = secondGeneration
                .administration().contractTypeResolver().resolveClass("shared-registration");

        // then
        assertNull(registeredInFirst);
        assertSame(processor, registeredInSecond);
        assertSame(RegistrationMarker.class, registeredType);
    }

    @Test
    void shouldKeepOwnedProcessorUsableAfterOrdinaryCacheClear() {
        // given
        Blue blue = new Blue();
        DocumentProcessor processor = blue.getDocumentProcessor();

        // when
        blue.clearResolvedSnapshotCache();
        boolean processorClosed = processor.isClosed();
        Node initializedDocument = blue.initializeDocument(
                Nodes.emptyObject()).document();

        // then
        assertFalse(processorClosed);
        assertTrue(initializedDocument != null);
    }

    @Test
    void shouldKeepInjectedProcessorBorrowedAcrossRuntimeClose() {
        // given
        DocumentProcessor shared = new DocumentProcessor();
        Blue first = new Blue().documentProcessor(shared);
        Blue second = new Blue().documentProcessor(shared);

        // when
        first.close();
        boolean closedAfterFirstClose = shared.isClosed();
        DocumentProcessor secondProcessor = second.getDocumentProcessor();
        DocumentProcessingResult initialized = second.initializeDocument(
                Nodes.emptyObject());
        second.close();
        boolean closedAfterSecondClose = shared.isClosed();

        // then
        assertFalse(closedAfterFirstClose);
        assertSame(shared, secondProcessor);
        assertTrue(initialized.document() != null);
        assertFalse(closedAfterSecondClose);
    }

    @Test
    void shouldCloseOnlyDisplacedOwnedProcessorWhenInjectingBorrowedProcessor() {
        // given
        Blue blue = new Blue();
        DocumentProcessor owned = blue.getDocumentProcessor();
        owned.administration().markersFor(Nodes.emptyObject(), "/");
        DocumentProcessor borrowed = new DocumentProcessor();

        // when
        blue.documentProcessor(borrowed);
        boolean ownedClosed = owned.isClosed();
        int ownedEntries = owned.administration().cacheEntryCount();
        boolean borrowedClosedAfterInjection = borrowed.isClosed();
        blue.close();
        boolean borrowedClosedAfterRuntimeClose = borrowed.isClosed();

        // then
        assertTrue(ownedClosed);
        assertEquals(0, ownedEntries);
        assertFalse(borrowedClosedAfterInjection);
        assertFalse(borrowedClosedAfterRuntimeClose);
    }

    @Test
    void shouldNotLaunderOwnershipWhenReinjectingSameOwnedProcessor() {
        // given
        Blue blue = new Blue();
        DocumentProcessor owned = blue.getDocumentProcessor();

        blue.documentProcessor(owned);
        // when
        blue.close();
        boolean ownedClosed = owned.isClosed();
        Throwable useAfterCloseFailure =
                captureFailure(() -> owned.administration().markersFor(new Node(), "/"));

        // then
        assertTrue(ownedClosed);
        assertTrue(useAfterCloseFailure instanceof IllegalStateException);
    }

    @Test
    void shouldPreserveBorrowedProcessorOwnershipAcrossAliasAndLimitChanges() {
        // given
        DocumentProcessor borrowed = new DocumentProcessor();
        Blue blue = new Blue().documentProcessor(borrowed);

        // when
        blue.addPreprocessingAliases(Collections.singletonMap("one", "value"));
        DocumentProcessor afterAliasAddition = blue.getDocumentProcessor();
        blue.preprocessingAliases(Collections.singletonMap("two", "value"));
        DocumentProcessor afterAliasReplacement = blue.getDocumentProcessor();
        blue.setGlobalLimits(ResolutionLimits.NO_LIMITS);
        DocumentProcessor afterLimitReplacement = blue.getDocumentProcessor();
        blue.close();
        boolean borrowedClosed = borrowed.isClosed();

        // then
        assertSame(borrowed, afterAliasAddition);
        assertSame(borrowed, afterAliasReplacement);
        assertSame(borrowed, afterLimitReplacement);
        assertFalse(borrowedClosed);
    }

    @Test
    void shouldRejectReentrantMetricsCloseWithoutDeadlockOrImplicitShutdown() {
        // given
        Blue blue = new Blue();
        AtomicBoolean closeOnce = new AtomicBoolean();
        AtomicBoolean armed = new AtomicBoolean();
        blue.processingObserver(new ProcessingObserver() {
            @Override
            public void record(ProcessingObservation observation) {
                if (observation.metricId()
                        == ProcessingMetricId.CACHE_CURRENT_WEIGHT_BYTES
                        && armed.get()
                        && closeOnce.compareAndSet(false, true)) {
                    blue.close();
                }
            }
        });
        armed.set(true);

        // when
        Throwable failure = captureFailure(() -> blue.resolveToSnapshot(document(1)));
        boolean closedAfterRejectedClose = blue.isClosed();
        blue.close();
        boolean closedAfterExplicitClose = blue.isClosed();
        BlueCacheStats closedStats = blue.cacheStats();

        // then
        assertNull(failure,
                "observer failures must not escape deterministic runtime work");
        assertFalse(closedAfterRejectedClose);
        assertTrue(closedAfterExplicitClose);
        assertEquals(0, closedStats.entries());
        assertEquals(0L, closedStats.currentWeightBytes());
    }

    @Test
    void shouldAllowCloseTimeMetricsToReenterCloseWithoutRecursion() {
        // given
        Blue blue = new Blue();
        AtomicInteger callbacks = new AtomicInteger();
        blue.processingObserver(new ProcessingObserver() {
            @Override
            public void record(ProcessingObservation observation) {
                if (observation.metricId()
                        == ProcessingMetricId.RUNTIME_CLOSE_CALLS) {
                    callbacks.incrementAndGet();
                    blue.close();
                }
            }
        });

        // when
        blue.close();
        boolean closed = blue.isClosed();
        int callbackCount = callbacks.get();
        int retainedEntries = blue.cacheStats().entries();

        // then
        assertTrue(closed);
        assertEquals(1, callbackCount);
        assertEquals(0, retainedEntries);
    }

    @Test
    void shouldWaitForOwnedProcessorReleaseDuringConcurrentClose() throws Exception {
        // given
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

        // when
        first.start();
        boolean firstEnteredClose = processor.closeEntered.await(5L, TimeUnit.SECONDS);
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
        boolean secondReturnedBeforeRelease =
                secondReturned.await(200L, TimeUnit.MILLISECONDS);
        boolean closedBeforeRelease = processor.isClosed();
        processor.allowClose.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5L));
        second.join(TimeUnit.SECONDS.toMillis(5L));
        boolean firstAlive = first.isAlive();
        boolean secondAlive = second.isAlive();
        Throwable closeFailure = failure.get();
        boolean processorClosed = processor.isClosed();

        // then
        assertTrue(firstEnteredClose);
        assertFalse(secondReturnedBeforeRelease);
        assertFalse(closedBeforeRelease);
        assertFalse(firstAlive);
        assertFalse(secondAlive);
        assertNull(closeFailure);
        assertTrue(processorClosed);
    }

    @Test
    void shouldShareOneCompletionAmongConcurrentClosersQueuedBehindInvalidation()
            throws Exception {
        // given
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

        // when
        clearing.start();
        boolean clearEntered = processor.clearEntered.await(5L, TimeUnit.SECONDS);

        CountDownLatch closersStarted = new CountDownLatch(2);
        CountDownLatch anyCloserReturned = new CountDownLatch(1);
        Thread first = closingThread(blue, failure, closersStarted, anyCloserReturned);
        Thread second = closingThread(blue, failure, closersStarted, anyCloserReturned);
        first.start();
        second.start();
        boolean bothClosersStarted = closersStarted.await(5L, TimeUnit.SECONDS);
        boolean closerReturnedDuringClear =
                anyCloserReturned.await(200L, TimeUnit.MILLISECONDS);

        processor.allowClear.countDown();
        boolean closeEntered = processor.closeEntered.await(5L, TimeUnit.SECONDS);
        boolean closerReturnedDuringClose =
                anyCloserReturned.await(200L, TimeUnit.MILLISECONDS);

        processor.allowClose.countDown();
        clearing.join(TimeUnit.SECONDS.toMillis(5L));
        first.join(TimeUnit.SECONDS.toMillis(5L));
        second.join(TimeUnit.SECONDS.toMillis(5L));
        boolean clearingAlive = clearing.isAlive();
        boolean firstAlive = first.isAlive();
        boolean secondAlive = second.isAlive();
        Throwable concurrentFailure = failure.get();
        boolean processorClosed = processor.isClosed();

        // then
        assertTrue(clearEntered);
        assertTrue(bothClosersStarted);
        assertFalse(closerReturnedDuringClear);
        assertTrue(closeEntered);
        assertFalse(closerReturnedDuringClose,
                "all concurrent close callers must await the owned close cleanup");
        assertFalse(clearingAlive);
        assertFalse(firstAlive);
        assertFalse(secondAlive);
        assertNull(concurrentFailure);
        assertTrue(processorClosed);
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
    void shouldUseButNotRetainOversizedDerivedSnapshotAndStillAllowPinning() {
        // given
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .derivedSnapshots(4, 4096L)
                .maximumDerivedEntryWeightBytes(64L)
                .build();
        Blue blue = Blue.withCachePolicy(policy);

        // when
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(1));
        BlueCacheStats.Region derivedBeforePin =
                blue.cacheStats().region("derivedResolvedSnapshots");
        blue.cacheResolvedSnapshot(snapshot);
        int pinnedEntries =
                blue.cacheStats().region("pinnedAuthoritativeSnapshots").entries();

        // then
        assertEquals(0, derivedBeforePin.entries());
        assertEquals(1L, derivedBeforePin.oversizedRejections());
        assertEquals(1, pinnedEntries);
    }

    @Test
    void shouldReleaseOwnedStateIdempotentlyAndRecordCloseMetrics() {
        // given
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.boundedDefaults());
        blue.processingObserver(metrics);
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(1));
        blue.cacheResolvedSnapshot(snapshot);
        long retainedBeforeClose = blue.cacheStats().currentWeightBytes();

        // when
        blue.close();
        blue.close();
        BlueCacheStats closedStats = blue.cacheStats();
        ProcessingMetricsSnapshot recorded = metrics.snapshot();

        // then
        assertTrue(retainedBeforeClose > 0L);
        assertTrue(blue.isClosed());
        assertEquals(0L, closedStats.currentWeightBytes());
        assertEquals(0, closedStats.entries());
        assertEquals(2L, recorded.counter("runtimeCloseCalls"));
        assertTrue(recorded.counter("runtimeCloseReleasedWeightBytes") > 0L);
    }

    @Test
    void shouldRejectEveryStatefulOperationAfterRuntimeClose() {
        // given
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.boundedDefaults());
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(document(1));
        List<Runnable> operations = java.util.Arrays.asList(
                () -> blue.resolveToSnapshot(document(2)),
                () -> blue.cacheResolvedSnapshot(snapshot),
                () -> blue.cacheResolvedSnapshots(Collections.emptyList()),
                blue::clearResolvedSnapshotCache,
                () -> blue.registerTypeDictionaries(Collections.emptyList()),
                () -> blue.registerExternalContractType("closed", null, null),
                () -> blue.isInitialized(document(2)),
                () -> blue.isInitialized(snapshot),
                () -> blue.resolvePreservingPaths(document(2),
                        ResolutionLimits.NO_LIMITS,
                        Collections.singletonList("/")),
                () -> blue.nodeMatchesType(new Node(), new Node()),
                () -> blue.nodeMatchesType(
                        snapshot.frozenResolvedRoot(),
                        snapshot.frozenResolvedRoot()),
                () -> blue.nodeMatchesType(
                        snapshot, "/", snapshot.frozenResolvedRoot()),
                () -> blue.expand(document(2), ResolutionLimits.NO_LIMITS),
                () -> blue.preprocess(document(2)),
                () -> blue.yamlToNode("value: 2"),
                () -> blue.jsonToNode("{\"value\":2}"),
                () -> blue.determineClass(document(2)),
                () -> blue.nodeToObject(document(2), Node.class),
                () -> blue.isNodeSubtypeOf(document(2), document(3)),
                () -> blue.cachedResolvedSnapshot(snapshot.blueId()),
                blue::conformanceEngine);

        // when
        blue.close();
        List<Throwable> failures = new ArrayList<>();
        for (Runnable operation : operations) {
            failures.add(captureFailure(operation));
        }

        // then
        assertEquals(operations.size(), failures.size());
        assertTrue(failures.stream()
                .allMatch(IllegalStateException.class::isInstance));
    }

    @Test
    void shouldInvalidateProcessorHandleObtainedBeforeClose() {
        // given
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.boundedDefaults());
        DocumentProcessor leakedProcessor = blue.getDocumentProcessor();

        // when
        blue.close();
        Throwable initializationFailure = captureFailure(
                () -> leakedProcessor.initializeDocument(document(4)));
        Throwable markerFailure = captureFailure(
                () -> leakedProcessor.administration().markersFor(new Node(), "/"));
        boolean closed = leakedProcessor.isClosed();
        boolean supportsSnapshots = leakedProcessor.supportsSnapshotProcessing();
        int retainedEntries = leakedProcessor.administration()
                .cacheEntryCount();

        // then
        assertTrue(initializationFailure instanceof IllegalStateException,
                "a processor handle obtained before close must observe cache invalidation");
        assertTrue(markerFailure instanceof IllegalStateException,
                "a leaked processor handle must not repopulate owned caches after runtime close");
        assertTrue(closed);
        assertFalse(supportsSnapshots,
                "closed leaked handles must detach the runtime snapshot collaborator");
        assertEquals(0, retainedEntries);
    }

    @Test
    void shouldKeepPureSerializationAvailableAfterClose() {
        // given
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.boundedDefaults());

        // when
        blue.close();
        String json = blue.nodeToJson(document(3));
        Node parsed = blue.parseSourceJson("{\"value\":3}");

        // then
        assertTrue(json.contains("value"));
        assertEquals("3", parsed.getValue().toString());
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @Test
    void shouldNotDeadlockWhenClosingWithConcurrentCacheReaders() throws Exception {
        // given
        Blue blue = Blue.withCachePolicy(BlueCachePolicy.boundedDefaults());
        blue.resolveToSnapshot(document(1));
        Thread reader = new Thread(() -> {
            for (int index = 0; index < 10_000 && !blue.isClosed(); index++) {
                blue.cacheStats();
            }
        });

        // when
        reader.start();
        blue.close();
        reader.join(TimeUnit.SECONDS.toMillis(5L));
        boolean readerAlive = reader.isAlive();

        // then
        assertFalse(readerAlive, "cache reader must finish when close completes");
    }

    @Test
    void shouldRejectCloseFromPreservedPathPredicateForWholeCompositeOperation() {
        // given
        Blue blue = new Blue();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        // when
        Node resolved = blue.resolvePreservingMatchingPaths(
                document(5),
                Collections.singletonList("/value"),
                node -> {
                    closeFailure.set(captureFailure(blue::close));
                    return true;
                });
        boolean closedAfterCompositeOperation = blue.isClosed();
        blue.close();
        boolean closedAfterCleanup = blue.isClosed();

        // then
        assertTrue(closeFailure.get() instanceof IllegalStateException);
        assertEquals("Blue runtime cannot close from active runtime work",
                closeFailure.get().getMessage());
        assertFalse(closedAfterCompositeOperation);
        assertTrue(resolved != null);
        assertTrue(closedAfterCleanup);
    }

    @Test
    void shouldWaitForLazyProcessorPublicationAndReleaseItWhenClosing() throws Exception {
        // given
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

        // when
        getter.start();
        boolean providerEntered = blue.providerEntered.await(5L, TimeUnit.SECONDS);

        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            blue.close();
            closeReturned.countDown();
        });
        closer.start();
        boolean closeStartedObserved = closeStarted.await(5L, TimeUnit.SECONDS);
        boolean closeReturnedBeforePublication =
                closeReturned.await(200L, TimeUnit.MILLISECONDS);

        blue.releaseProvider.countDown();
        getter.join(TimeUnit.SECONDS.toMillis(5L));
        closer.join(TimeUnit.SECONDS.toMillis(5L));
        boolean getterAlive = getter.isAlive();
        boolean closerAlive = closer.isAlive();
        Throwable publicationFailure = failure.get();
        boolean closed = blue.isClosed();
        Object publishedProcessor = processorField.get(blue);
        BlueCacheStats closedStats = blue.cacheStats();

        // then
        assertTrue(providerEntered);
        assertTrue(closeStartedObserved);
        assertFalse(closeReturnedBeforePublication,
                "close must serialize with an in-flight lazy processor publication");
        assertFalse(getterAlive);
        assertFalse(closerAlive);
        assertNull(publicationFailure);
        assertTrue(closed);
        assertNull(publishedProcessor);
        assertEquals(0, closedStats.entries());
        assertEquals(0L, closedStats.currentWeightBytes());
    }

    @Test
    void shouldWaitForAdmittedOwnedProcessingAndReleaseItsPublicationWhenClosing()
            throws Exception {
        // given
        Node completedDocument = document(42);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                DirectBlueIdCalculator.calculateBlueId(completedDocument));
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

        // when
        processing.start();
        boolean processingEntered = processor.entered.await(5L, TimeUnit.SECONDS);

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
        boolean closeReturnedBeforeProcessing =
                closeReturned.await(200L, TimeUnit.MILLISECONDS);
        processor.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        closing.join(TimeUnit.SECONDS.toMillis(5L));
        boolean processingAlive = processing.isAlive();
        boolean closingAlive = closing.isAlive();
        Throwable processingFailure = failure.get();
        boolean blueClosed = blue.isClosed();
        boolean processorClosed = processor.isClosed();
        BlueCacheStats.Region recentSnapshots =
                blue.cacheStats().region("recentProcessingSnapshots");

        // then
        assertTrue(processingEntered);
        assertFalse(closeReturnedBeforeProcessing);
        assertFalse(processingAlive);
        assertFalse(closingAlive);
        assertNull(processingFailure);
        assertTrue(blueClosed);
        assertTrue(processorClosed);
        assertEquals(0, recentSnapshots.entries());
        assertEquals(0L, recentSnapshots.currentWeightBytes());
    }

    @Test
    void shouldWaitForAdmittedDirectResolutionBeforeReleasingCachesWhenClosing()
            throws Exception {
        // given
        Node canonical = document(52);
        String blueId = DirectBlueIdCalculator.calculateBlueId(canonical);
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

        // when
        resolving.start();
        boolean providerEnteredObserved = providerEntered.await(5L, TimeUnit.SECONDS);

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
        boolean closeReturnedBeforeResolution =
                closeReturned.await(200L, TimeUnit.MILLISECONDS);
        Throwable newWorkFailure =
                captureFailure(() -> blue.loadSnapshot(blueId));

        releaseProvider.countDown();
        resolving.join(TimeUnit.SECONDS.toMillis(5L));
        closing.join(TimeUnit.SECONDS.toMillis(5L));
        boolean resolvingAlive = resolving.isAlive();
        boolean closingAlive = closing.isAlive();
        Throwable resolutionFailure = failure.get();
        ResolvedSnapshot resolved = result.get();
        boolean closed = blue.isClosed();
        int retainedEntries = blue.cacheStats().entries();

        // then
        assertTrue(providerEnteredObserved);
        assertFalse(closeReturnedBeforeResolution);
        assertTrue(newWorkFailure instanceof IllegalStateException,
                "close must reject new work while draining the admitted resolution");
        assertFalse(resolvingAlive);
        assertFalse(closingAlive);
        assertNull(resolutionFailure);
        assertEquals(blueId, resolved.blueId());
        assertTrue(closed);
        assertEquals(0, retainedEntries);
    }

    @Test
    void shouldWaitAcrossCompositeObjectConversionAndRuntimePhaseWhenClosing()
            throws Exception {
        // given
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

        // when
        resolving.start();
        boolean conversionCompleted =
                blue.conversionCompleted.await(5L, TimeUnit.SECONDS);

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
        boolean closeReturnedBeforeConversion =
                closeReturned.await(200L, TimeUnit.MILLISECONDS);

        blue.releaseConversion.countDown();
        resolving.join(TimeUnit.SECONDS.toMillis(5L));
        closing.join(TimeUnit.SECONDS.toMillis(5L));
        boolean resolvingAlive = resolving.isAlive();
        boolean closingAlive = closing.isAlive();
        Throwable conversionFailure = failure.get();
        ResolvedSnapshot resolved = result.get();
        boolean closed = blue.isClosed();

        // then
        assertTrue(conversionCompleted);
        assertFalse(closeReturnedBeforeConversion,
                "close must wait across conversion and the runtime-backed second phase");
        assertFalse(resolvingAlive);
        assertFalse(closingAlive);
        assertNull(conversionFailure);
        assertTrue(resolved != null);
        assertTrue(closed);
    }

    @Test
    void shouldWaitForRecursiveExpandBeforeReplacingProviderWithoutMixingProviders()
            throws Exception {
        // given
        CountDownLatch rootFetchEntered = new CountDownLatch(1);
        CountDownLatch releaseRootFetch = new CountDownLatch(1);
        Node originalLeaf = new Node().value("original");
        String originalLeafBlueId = DirectBlueIdCalculator.calculateBlueId(originalLeaf);
        Node originalRoot = new Node().properties(
                "child", new Node().blueId(originalLeafBlueId));
        String originalRootBlueId = DirectBlueIdCalculator.calculateBlueId(originalRoot);
        Node replacementLeaf = new Node().value("replacement");
        String replacementLeafBlueId = DirectBlueIdCalculator.calculateBlueId(replacementLeaf);
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

        // when
        expanding.start();
        boolean rootFetchEnteredObserved =
                rootFetchEntered.await(5L, TimeUnit.SECONDS);

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
        boolean replacementReturnedBeforeExpansion =
                replacementReturned.await(200L, TimeUnit.MILLISECONDS);

        releaseRootFetch.countDown();
        expanding.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));
        boolean expandingAlive = expanding.isAlive();
        boolean replacementAlive = replacement.isAlive();
        Throwable expansionFailure = failure.get();
        Object originalValue =
                expanded.get().getProperties().get("child").getValue();
        Object replacementValue =
                blue.expand(new Node().blueId(replacementLeafBlueId)).getValue();

        // then
        assertTrue(rootFetchEnteredObserved);
        assertFalse(replacementReturnedBeforeExpansion);
        assertFalse(expandingAlive);
        assertFalse(replacementAlive);
        assertNull(expansionFailure);
        assertEquals("original", originalValue);
        assertEquals("replacement", replacementValue);
    }

    @Test
    void shouldWaitForSubtypeTraversalBeforeReplacingProviderWithoutMixingProviders()
            throws Exception {
        // given
        Node superType = new Node().name("Subtype gate supertype");
        String superTypeBlueId = DirectBlueIdCalculator.calculateBlueId(superType);
        Node candidateType = new Node()
                .name("Subtype gate candidate")
                .type(new Node().blueId(superTypeBlueId));
        String candidateTypeBlueId = DirectBlueIdCalculator.calculateBlueId(candidateType);
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

        // when
        matching.start();
        boolean candidateFetchEnteredObserved =
                candidateFetchEntered.await(5L, TimeUnit.SECONDS);

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
        boolean replacementReturnedBeforeTraversal =
                replacementReturned.await(200L, TimeUnit.MILLISECONDS);

        releaseCandidateFetch.countDown();
        matching.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));
        boolean matchingAlive = matching.isAlive();
        boolean replacementAlive = replacement.isAlive();
        Throwable traversalFailure = failure.get();
        Boolean originalProviderResult = result.get();
        Throwable replacementProviderFailure = captureFailure(() ->
                blue.isNodeSubtypeOf(
                        new Node().blueId(candidateTypeBlueId),
                        new Node().blueId(superTypeBlueId)));

        // then
        assertTrue(candidateFetchEnteredObserved);
        assertFalse(replacementReturnedBeforeTraversal);
        assertFalse(matchingAlive);
        assertFalse(replacementAlive);
        assertNull(traversalFailure);
        assertEquals(Boolean.TRUE, originalProviderResult);
        assertTrue(replacementProviderFailure instanceof IllegalArgumentException);
        assertTrue(replacementProviderFailure.getMessage()
                .contains(candidateTypeBlueId));
    }

    @Test
    void shouldPreventRetainedConformanceEngineFromPublishingStaleEvidenceAfterRefresh() {
        // given
        Node type = new Node().properties("typeMarker", new Node().value(true));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(type);
        NodeProvider oldProvider = blueId -> typeBlueId.equals(blueId)
                ? Collections.singletonList(type.clone()) : null;
        NodeProvider newProvider = blueId -> typeBlueId.equals(blueId)
                ? Collections.singletonList(type.clone()) : null;
        Blue blue = new Blue(oldProvider, new EvidenceMergingProcessor("oldEvidence"));
        ConformanceEngine staleEngine = blue.conformanceEngine();

        // when
        int referencesAfterRefresh;
        boolean staleConforms;
        int referencesAfterStaleUse;
        boolean hasNewEvidence;
        boolean hasOldEvidence;
        try {
            blue.nodeProvider(newProvider);
            blue.mergingProcessor(new EvidenceMergingProcessor("newEvidence"));
            referencesAfterRefresh = blue.resolvedReferenceCacheSize();
            staleConforms = staleEngine.conforms(
                    new Node().type(new Node().blueId(typeBlueId)));
            referencesAfterStaleUse = blue.resolvedReferenceCacheSize();
            Node resolved = blue.resolve(new Node().type(new Node().blueId(typeBlueId)));
            hasNewEvidence = resolved.getProperties().get("newEvidence") != null;
            hasOldEvidence = resolved.getProperties().get("oldEvidence") != null;
        } finally {
            staleEngine.close();
        }

        // then
        assertEquals(0, referencesAfterRefresh);
        assertTrue(staleConforms);
        assertEquals(0, referencesAfterStaleUse,
                "a retained engine must not publish into Blue's current cache generation");
        assertTrue(hasNewEvidence);
        assertFalse(hasOldEvidence,
                "current resolution must not consume stale merger output");
    }

    @Test
    void shouldRetainCallerPinnedVerifiedSnapshotVisibilityInConformanceEngine() {
        // given
        Node type = new Node().properties("pinnedMarker", new Node().value(true));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(type);
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(type);
        Blue source = new Blue(provider);
        Blue target = new Blue(blueId -> null);
        ConformanceEngine engine = null;

        // when
        boolean conformsBeforeClear;
        boolean conformsAfterClear;
        try {
            ResolvedSnapshot verifiedType = source.loadSnapshot(typeBlueId);
            target.cacheResolvedSnapshot(verifiedType);

            engine = target.conformanceEngine();
            conformsBeforeClear = engine.conforms(
                    new Node().type(new Node().blueId(typeBlueId)));
            target.clearResolvedSnapshotCache();
            conformsAfterClear = engine.conforms(
                    new Node().type(new Node().blueId(typeBlueId)));
        } finally {
            if (engine != null) {
                engine.close();
            }
            target.close();
            source.close();
        }

        // then
        assertTrue(conformsBeforeClear);
        assertTrue(conformsAfterClear,
                "the retained handle must own its pinned-evidence snapshot");
    }

    @Test
    void shouldPreventDisplacedProcessorFromPublishingSnapshotAfterProviderReplacement()
            throws Exception {
        // given
        Node completedDocument = document(77);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                DirectBlueIdCalculator.calculateBlueId(completedDocument));
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

        // when
        processing.start();
        boolean processingEntered = processor.entered.await(5L, TimeUnit.SECONDS);

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
        boolean replacementReturnedBeforeProcessing =
                replacementReturned.await(200L, TimeUnit.MILLISECONDS);
        processor.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));
        boolean processingAlive = processing.isAlive();
        boolean replacementAlive = replacement.isAlive();
        Throwable processingFailure = failure.get();
        Throwable providerReplacementFailure = replacementFailure.get();
        BlueCacheStats stats = blue.cacheStats();

        // then
        assertTrue(processingEntered);
        assertFalse(replacementReturnedBeforeProcessing);
        assertFalse(processingAlive);
        assertFalse(replacementAlive);
        assertNull(processingFailure);
        assertNull(providerReplacementFailure);
        assertEquals(0, stats.region("recentProcessingSnapshots").entries());
        assertEquals(0, stats.region("derivedResolvedSnapshots").entries());
    }

    @Test
    void shouldWaitForConfigurationRefreshBeforeRegisteringWithPublishedProcessor()
            throws Exception {
        // given
        Node completedDocument = document(78);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                DirectBlueIdCalculator.calculateBlueId(completedDocument));
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

        // when
        processing.start();
        boolean processingEntered = displaced.entered.await(5L, TimeUnit.SECONDS);

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
        boolean replacementReturnedBeforeProcessing =
                replacementReturned.await(200L, TimeUnit.MILLISECONDS);

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
        boolean displacedRegistrationEntered =
                displaced.registrationEntered.await(200L, TimeUnit.MILLISECONDS);
        boolean registrationReturnedBeforeRefresh =
                registrationReturned.await(200L, TimeUnit.MILLISECONDS);

        displaced.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));
        registration.join(TimeUnit.SECONDS.toMillis(5L));
        boolean processingAlive = processing.isAlive();
        boolean replacementAlive = replacement.isAlive();
        boolean registrationAlive = registration.isAlive();
        Throwable concurrentFailure = failure.get();
        ContractProcessor<?> registered = blue.getDocumentProcessor().administration().contractRegistry()
                .processors().get("registration-race");

        // then
        assertTrue(processingEntered);
        assertFalse(replacementReturnedBeforeProcessing);
        assertFalse(displacedRegistrationEntered,
                "registration must not mutate the displaced processor during refresh");
        assertFalse(registrationReturnedBeforeRefresh);
        assertFalse(processingAlive);
        assertFalse(replacementAlive);
        assertFalse(registrationAlive);
        assertNull(concurrentFailure);
        assertSame(processor, registered);
    }

    @Test
    void shouldRejectLateBorrowedProcessorPublicationAfterExplicitClear() throws Exception {
        // given
        Node completedDocument = document(88);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                DirectBlueIdCalculator.calculateBlueId(completedDocument));
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

        // when
        processing.start();
        boolean processingEntered = processor.entered.await(5L, TimeUnit.SECONDS);

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
        boolean clearReturnedBeforeProcessing =
                clearReturned.await(200L, TimeUnit.MILLISECONDS);
        processor.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        clearing.join(TimeUnit.SECONDS.toMillis(5L));
        boolean processingAlive = processing.isAlive();
        boolean clearingAlive = clearing.isAlive();
        Throwable processingFailure = failure.get();
        BlueCacheStats stats = blue.cacheStats();

        // then
        assertTrue(processingEntered);
        assertFalse(clearReturnedBeforeProcessing);
        assertFalse(processingAlive);
        assertFalse(clearingAlive);
        assertNull(processingFailure);
        assertEquals(0, stats.region("recentProcessingSnapshots").entries());
        assertEquals(0, stats.region("derivedResolvedSnapshots").entries());
    }

    @Test
    void shouldWaitForInProgressInvalidationWithoutStrandingConcurrentCloseGate()
            throws Exception {
        // given
        Node completedDocument = document(89);
        ResolvedSnapshot completedSnapshot = new ResolvedSnapshot(
                completedDocument,
                completedDocument.clone(),
                DirectBlueIdCalculator.calculateBlueId(completedDocument));
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

        // when
        processing.start();
        boolean processingEntered = processor.entered.await(5L, TimeUnit.SECONDS);

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
        boolean clearReturnedBeforeProcessing =
                clearReturned.await(200L, TimeUnit.MILLISECONDS);

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
        boolean closeReturnedBeforeProcessing =
                closeReturned.await(200L, TimeUnit.MILLISECONDS);

        processor.release.countDown();
        processing.join(TimeUnit.SECONDS.toMillis(5L));
        clearing.join(TimeUnit.SECONDS.toMillis(5L));
        closing.join(TimeUnit.SECONDS.toMillis(5L));
        boolean processingAlive = processing.isAlive();
        boolean clearingAlive = clearing.isAlive();
        boolean closingAlive = closing.isAlive();
        Throwable concurrentFailure = failure.get();
        boolean closed = blue.isClosed();
        AtomicReference<Throwable> postCloseFailure = new AtomicReference<>();
        Thread rejectedWork = new Thread(() -> postCloseFailure.set(
                captureFailure(() -> blue.processDocument(document(2), new Node()))));
        rejectedWork.setDaemon(true);
        rejectedWork.start();
        rejectedWork.join(TimeUnit.SECONDS.toMillis(2L));
        boolean rejectedWorkAlive = rejectedWork.isAlive();
        if (rejectedWorkAlive) {
            rejectedWork.interrupt();
        }

        // then
        assertTrue(processingEntered);
        assertFalse(clearReturnedBeforeProcessing);
        assertFalse(closeReturnedBeforeProcessing);
        assertFalse(processingAlive);
        assertFalse(clearingAlive);
        assertFalse(closingAlive);
        assertNull(concurrentFailure);
        assertTrue(closed);
        assertFalse(rejectedWorkAlive,
                "closed runtime rejection must not strand the lifecycle gate");
        assertTrue(postCloseFailure.get() instanceof IllegalStateException);
    }

    @Test
    void shouldWaitForDirectResolutionAndClearItsResultWhenReplacingMerger()
            throws Exception {
        // given
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

        // when
        resolving.start();
        boolean resolutionEntered = blocking.entered.await(5L, TimeUnit.SECONDS);

        CountDownLatch replacementReturned = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            try {
                blue.mergingProcessor((target, source, provider, resolver,
                                       typeIdentities) -> { });
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                replacementReturned.countDown();
            }
        });
        replacement.start();
        boolean replacementReturnedBeforeResolution =
                replacementReturned.await(200L, TimeUnit.MILLISECONDS);

        blocking.release.countDown();
        resolving.join(TimeUnit.SECONDS.toMillis(5L));
        replacement.join(TimeUnit.SECONDS.toMillis(5L));
        boolean resolvingAlive = resolving.isAlive();
        boolean replacementAlive = replacement.isAlive();
        Throwable resolutionFailure = failure.get();
        int derivedEntries =
                blue.cacheStats().region("derivedResolvedSnapshots").entries();
        int referenceEntries = blue.resolvedReferenceCacheSize();

        // then
        assertTrue(resolutionEntered);
        assertFalse(replacementReturnedBeforeResolution);
        assertFalse(resolvingAlive);
        assertFalse(replacementAlive);
        assertNull(resolutionFailure);
        assertEquals(0, derivedEntries);
        assertEquals(0, referenceEntries);
    }

    @Test
    void shouldRefreshEagerProcessorAndOwnCallerMapWhenReplacingAliases() throws Exception {
        // given
        Node aliasTarget = new Node()
                .name("Alias Target")
                .properties("provided", new Node().value(true));
        BasicNodeProvider provider = new BasicNodeProvider(aliasTarget);
        String targetBlueId = provider.getBlueIdByName("Alias Target");
        Blue blue = new Blue(provider);
        Map<String, String> aliases = new HashMap<>();
        aliases.put("friendly", targetBlueId);

        // when
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
        String publishedAlias = blue.getPreprocessingAliases().get("friendly");
        Throwable mutationFailure = captureFailure(
                () -> blue.getPreprocessingAliases().put("other", targetBlueId));

        // then
        assertEquals(targetBlueId, capturedAliases.get("friendly"));
        assertEquals(targetBlueId, publishedAlias);
        assertTrue(mutationFailure instanceof UnsupportedOperationException);
    }

    @Test
    void shouldBoundVerifiedReferenceAccelerationWhilePinningExplicitRegistration() {
        // given
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

        // when
        ResolvedSnapshot authoritative = blue.loadSnapshot(
                provider.getBlueIdByName("Reference Type 0"));
        blue.clearResolvedSnapshotCache();
        blue.cacheResolvedSnapshot(authoritative);

        for (int index = 1; index < 6; index++) {
            blue.loadSnapshot(provider.getBlueIdByName("Reference Type " + index));
        }
        BlueCacheStats.Region references = blue.cacheStats().region("verifiedReferences");
        ResolvedSnapshot cached = blue.cachedResolvedSnapshot(authoritative.blueId())
                .orElseThrow(AssertionError::new);

        // then
        assertTrue(references.entries() <= 2,
                "one pinned entry plus bounded derived reference evidence");
        assertTrue(references.evictions() > 0L);
        assertSame(authoritative, cached);
    }

    @Test
    void shouldPromoteReferenceEvidenceWhenReplacingPinnedSnapshotWithVerifiedSnapshot() {
        // given
        BlueCachePolicy policy = BlueCachePolicy.builder()
                .transientReferences(1, 1024L * 1024L)
                .maximumDerivedEntryWeightBytes(1024L * 1024L)
                .build();
        Blue blue = Blue.withCachePolicy(policy);
        Node canonical = document(7);
        String blueId = blue.calculateBlueId(canonical);
        ResolvedSnapshot unverified = new ResolvedSnapshot(
                canonical, canonical.clone(), blueId);

        // when
        blue.cacheResolvedSnapshot(unverified);
        ResolvedSnapshot verified = blue.loadSnapshot(canonical);
        boolean verifiedReferencePresent =
                verified.verifiedReferenceResolution() != null;
        boolean verifiedReferencePinned =
                blue.cacheStats().region("verifiedReferences").isPinned();
        ResolvedSnapshot cached = blue.cachedResolvedSnapshot(blueId)
                .orElseThrow(AssertionError::new);

        // then
        assertTrue(verifiedReferencePresent);
        assertTrue(verifiedReferencePinned);
        assertSame(verified, cached);
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
                            NodeResolver nodeResolver,
                            CanonicalTypeIdentityLookup typeIdentities) {
            entered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to complete merge");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            target.replaceWith(source);
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
                            NodeResolver nodeResolver,
                            CanonicalTypeIdentityLookup typeIdentities) {
            if (source.getProperties() != null
                    && source.getProperties().containsKey("typeMarker")) {
                target.replaceWith(source);
                target.properties(evidenceProperty, new Node().value(true));
            }
        }
    }
}
