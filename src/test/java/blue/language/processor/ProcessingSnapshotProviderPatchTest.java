package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingSnapshotProviderPatchTest {

    @Test
    void shouldVerifyRemovedTypedIntermediateStateDoesNotPolluteBlueCaches() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Ephemeral Processing Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("Ephemeral Processing Type");
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/temporary", new Node()
                        .type(new Node().blueId(typeBlueId))
                        .properties("local", new Node().value("intermediate"))),
                JsonPatch.remove("/temporary"));

        // when
        String intermediateInherited;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            intermediateInherited = runtime.snapshot()
                    .resolvedRoot()
                    .getAsText("/temporary/inherited");
            sequence.applyNext(1);
        }
        ResolvedSnapshot finalSnapshot = runtime.snapshot();
        Blue finalOnly = new Blue(provider);
        finalOnly.clearResolvedSnapshotCache();
        finalOnly.cacheResolvedSnapshot(finalSnapshot);
        int finalOnlySnapshotCacheSize =
                finalOnly.resolvedSnapshotCacheSize();
        int finalOnlyReferenceCacheSize =
                finalOnly.resolvedReferenceCacheSize();
        int finalOnlyStructuralCacheSize =
                finalOnly.resolvedStructuralCacheSize();

        // then
        assertEquals("from-provider", intermediateInherited);
        assertNull(finalSnapshot.resolvedNodeAt("/temporary"));
        assertEquals(finalOnlySnapshotCacheSize,
                blue.resolvedSnapshotCacheSize());
        assertEquals(finalOnlyReferenceCacheSize,
                blue.resolvedReferenceCacheSize(),
                "removed typed references must remain sequence-local");
        assertEquals(finalOnlyStructuralCacheSize,
                blue.resolvedStructuralCacheSize(),
                "shared structural retention must equal final-only publication");
    }

    @Test
    void shouldVerifyRetainedTypedReferenceIsResolvedOncePerSequenceAndPromotedAtTheEnd() {
        // given
        CountingBasicNodeProvider provider = new CountingBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Retained Processing Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("Retained Processing Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/retained", new Node().type(new Node().blueId(typeBlueId))),
                JsonPatch.add("/first", new Node().value(1)),
                JsonPatch.add("/second", new Node().value(2)));

        // when
        int firstStepFetches;
        int fetchesAfterSequence;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            firstStepFetches = provider.fetchesFor(typeBlueId);
            sequence.applyNext(1);
            sequence.applyNext(2);
            fetchesAfterSequence = provider.fetchesFor(typeBlueId);
        }
        blue.resolve(new Node().type(new Node().blueId(typeBlueId)));
        int fetchesAfterSharedResolve =
                provider.fetchesFor(typeBlueId);

        // then
        assertEquals(1, firstStepFetches,
                "conformance and commit must share one sequence-local resolver cache");
        assertEquals(firstStepFetches, fetchesAfterSequence,
                "a retained reference must reuse the sequence-local resolver cache");
        assertEquals(fetchesAfterSequence, fetchesAfterSharedResolve,
                "final reachable references must be promoted to the shared verified cache");
        assertTrue(blue.resolvedReferenceCacheSize() > 0);
    }

    @Test
    void shouldVerifyWorkingDocumentDiscardsPerCallTypeCachesAndPublishesOnlyItsCommittedGraph() {
        // given
        CountingBasicNodeProvider provider = new CountingBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Working Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("Working Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(),
                blue.getDocumentProcessor().conformanceEngine(),
                blue.getDocumentProcessor().snapshotManager());
        WorkingDocument working = runtime.workingDocument("/");

        // when
        for (int index = 0; index < 12; index++) {
            working.applyPatch(JsonPatch.add("/temporary", new Node()
                    .type(new Node().blueId(typeBlueId))
                    .properties("round", new Node().value(index))));
            working.applyPatch(JsonPatch.remove("/temporary"));
        }
        int referenceCacheBeforeCommit =
                blue.resolvedReferenceCacheSize();
        working.applyPatch(JsonPatch.add("/retained",
                new Node().type(new Node().blueId(typeBlueId))));
        ResolvedSnapshot committed = working.commitSnapshot();
        int referenceCacheAfterCommit =
                blue.resolvedReferenceCacheSize();
        int afterCommit = provider.fetchesFor(typeBlueId);
        blue.resolve(new Node().type(new Node().blueId(typeBlueId)));
        int afterSharedResolve = provider.fetchesFor(typeBlueId);

        // then
        assertEquals(0, referenceCacheBeforeCommit,
                "preview-only references must never enter Blue's shared cache");
        assertEquals("from-provider",
                committed.resolvedRoot()
                        .getAsText("/retained/inherited"));
        assertEquals(
                committed.frozenCanonicalRoot(),
                working.canonicalRoot());
        assertEquals(
                committed.frozenResolvedRoot(),
                working.resolvedRoot());
        assertTrue(referenceCacheAfterCommit > 0);
        assertEquals(afterCommit, afterSharedResolve);
    }

    @Test
    void shouldVerifyWorkingDocumentReusesOneShotVerifiedEvidenceAcrossCallsAndCommit() {
        // given
        OneShotBasicNodeProvider provider = new OneShotBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("One Shot Working Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("One Shot Working Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        WorkingDocument working = runtime.workingDocument("/");

        // when
        working.applyPatch(JsonPatch.add("/typed",
                new Node().type(new Node().blueId(typeBlueId))));
        working.applyPatch(JsonPatch.add("/unrelated", new Node().value("later")));
        ResolvedSnapshot committed = working.commitSnapshot();

        // then
        assertEquals("from-provider", committed.resolvedRoot().getAsText("/typed/inherited"));
        assertEquals("later", committed.resolvedRoot().getAsText("/unrelated"));
        assertEquals(1, provider.fetchesFor(typeBlueId),
                "a retained verified reference must survive WorkingDocument call boundaries");
    }

    @Test
    void shouldVerifyWorkingDocumentCommitDoesNotRefetchVerifiedOneShotContent() {
        // given
        Node requestedType = new Node().name("Requested One Shot Type")
                .properties("inherited", new Node().value("requested"));
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(requestedType);
        AtomicInteger providerFetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            if (!requestedBlueId.equals(blueId)) {
                return null;
            }
            return providerFetches.incrementAndGet() == 1
                    ? Collections.singletonList(requestedType.clone())
                    : null;
        });
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        WorkingDocument working = runtime.workingDocument("/");

        // when
        working.applyPatch(JsonPatch.add("/typed",
                new Node().type(new Node().blueId(requestedBlueId))));
        ResolvedSnapshot committed = working.commitSnapshot();

        // then
        assertEquals("requested", committed.resolvedRoot().getAsText("/typed/inherited"));
        assertEquals(1, providerFetches.get(),
                "commit must publish the already verified resolution");
        assertTrue(blue.resolvedReferenceCacheSize() > 0,
                "final reachable exact evidence must be promoted");
    }

    @Test
    void shouldVerifyPreviewHandoffReusesVerifiedOneShotContentAndPromotesIt() {
        // given
        Node requestedType = new Node().name("Requested Preview One Shot Type")
                .properties("inherited", new Node().value("requested"));
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(requestedType);
        AtomicInteger providerFetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            if (!requestedBlueId.equals(blueId)) {
                return null;
            }
            return providerFetches.incrementAndGet() == 1
                    ? Collections.singletonList(requestedType.clone())
                    : null;
        });
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Collections.singletonList(JsonPatch.add("/typed",
                new Node().type(new Node().blueId(requestedBlueId))));
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
        }

        // then
        assertEquals("requested", runtime.snapshot().resolvedRoot().getAsText("/typed/inherited"));
        assertEquals(1, providerFetches.get(),
                "runtime commit must consume the preview's transient verified lookup");
        assertTrue(blue.resolvedReferenceCacheSize() > 0,
                "reachable exact evidence must be promoted after handoff");
    }

    @Test
    void shouldVerifyMatchingPreviewTransfersItsVerifiedReferenceCacheToRuntimeCommit() {
        // given
        CountingBasicNodeProvider provider = new CountingBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Preview Transfer Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("Preview Transfer Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Arrays.asList(JsonPatch.add("/typed",
                new Node().type(new Node().blueId(typeBlueId))));
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);
        int previewFetches = provider.fetchesFor(typeBlueId);

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
        }

        // then
        assertEquals(1, previewFetches);
        assertEquals(previewFetches, provider.fetchesFor(typeBlueId),
                "a matching handoff must reuse the exact preview resolution scope");
        assertEquals("from-provider", runtime.snapshot().resolvedRoot().getAsText("/typed/inherited"));
    }

    @Test
    void shouldVerifyPreviewHandoffRetainsEvidenceNeededByAnIntermediateStateOnly() {
        // given
        OneShotBasicNodeProvider provider = new OneShotBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("One Shot Preview Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("One Shot Preview Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/temporary", new Node().type(new Node().blueId(typeBlueId))),
                JsonPatch.remove("/temporary"));
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);

        // when
        String intermediateInherited;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
            intermediateInherited = runtime.snapshot()
                    .resolvedRoot()
                    .getAsText("/temporary/inherited");
            sequence.applyNext(1);
        }

        // then
        assertEquals("from-provider", intermediateInherited);
        assertNull(runtime.snapshot().resolvedNodeAt("/temporary"));
        assertEquals(1, provider.fetchesFor(typeBlueId),
                "the handoff must fork before the WorkingDocument prunes its final graph");
        assertEquals(0, blue.resolvedReferenceCacheSize(),
                "intermediate-only evidence must not be globally promoted");
    }

    @Test
    void shouldVerifyCacheInvalidationMakesPreviewReplanWithFreshProviderEvidence() {
        // given
        CountingBasicNodeProvider provider = new CountingBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Invalidated Preview Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("Invalidated Preview Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Collections.singletonList(JsonPatch.add("/typed",
                new Node().type(new Node().blueId(typeBlueId))));

        // when
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);
        int previewFetches = provider.fetchesFor(typeBlueId);
        blue.clearResolvedSnapshotCache();
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
        }
        int committedFetches = provider.fetchesFor(typeBlueId);

        // then
        assertEquals(1, previewFetches);
        assertEquals(2, committedFetches,
                "an invalid preview generation must be discarded and resolved again");
        assertEquals("from-provider", runtime.snapshot().resolvedRoot().getAsText("/typed/inherited"));
    }

    @Test
    void shouldVerifyInvalidationBetweenPreviewedStepsReopensTheSequenceScope() {
        // given
        CountingBasicNodeProvider provider = new CountingBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Mid Sequence Invalidation Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("Mid Sequence Invalidation Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/first", new Node().type(new Node().blueId(typeBlueId))),
                JsonPatch.add("/second", new Node().type(new Node().blueId(typeBlueId))));

        // when
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);
        int previewFetches = provider.fetchesFor(typeBlueId);
        int firstStepFetches;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
            firstStepFetches = provider.fetchesFor(typeBlueId);
            blue.clearResolvedSnapshotCache();
            sequence.applyNext(1);
        }
        int afterCommit = provider.fetchesFor(typeBlueId);
        blue.resolve(new Node().type(new Node().blueId(typeBlueId)));
        int afterSharedResolve = provider.fetchesFor(typeBlueId);

        // then
        assertEquals(1, previewFetches);
        assertEquals(1, firstStepFetches);
        assertEquals(2, afterCommit,
                "the stale suffix must replan in a newly opened cache generation");
        assertEquals("from-provider",
                runtime.snapshot().resolvedRoot()
                        .getAsText("/first/inherited"));
        assertEquals("from-provider",
                runtime.snapshot().resolvedRoot()
                        .getAsText("/second/inherited"));
        assertEquals(afterCommit, afterSharedResolve,
                "the replacement sequence scope must promote final reachable evidence");
    }

    @Test
    void shouldVerifyLiveRuntimeUsesCurrentProviderForConformanceAfterReplacement() {
        // given
        Node requestedType = new Node().name("Live Runtime Requested Type")
                .properties("inherited", new Node().value("stable"));
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(requestedType);
        AtomicInteger oldFetches = new AtomicInteger();
        AtomicInteger newFetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            if (!requestedBlueId.equals(blueId)) {
                return null;
            }
            oldFetches.incrementAndGet();
            return Collections.singletonList(requestedType.clone());
        });
        DocumentProcessor originalProcessor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), originalProcessor.conformanceEngine(), originalProcessor.snapshotManager());

        // when
        blue.nodeProvider(blueId -> {
            if (!requestedBlueId.equals(blueId)) {
                return null;
            }
            newFetches.incrementAndGet();
            return Collections.singletonList(requestedType.clone());
        });
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", Collections.singletonList(
                             JsonPatch.add("/typed",
                                     new Node().type(new Node().blueId(requestedBlueId)))), null)) {
            sequence.applyNext(0);
        }

        // then
        assertEquals("stable", runtime.snapshot().resolvedRoot().getAsText("/typed/inherited"));
        assertEquals(0, oldFetches.get(),
                "an existing runtime must not plan with a provider superseded before its sequence");
        assertEquals(1, newFetches.get());
    }

    @Test
    void shouldVerifyPreparedSequencePreservesAnExplicitCustomConformanceEngine() {
        // given
        Node customType = new Node().name("Explicit Custom Conformance Type")
                .properties("inherited", new Node().value("shared"));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(customType);
        AtomicInteger blueProviderFetches = new AtomicInteger();
        AtomicInteger customProviderFetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            if (!typeBlueId.equals(blueId)) {
                return null;
            }
            blueProviderFetches.incrementAndGet();
            return Collections.singletonList(customType.clone());
        });
        DocumentProcessor processor = blue.getDocumentProcessor();
        ConformanceEngine customEngine = new ConformanceEngine(blueId -> {
            if (!typeBlueId.equals(blueId)) {
                return null;
            }
            customProviderFetches.incrementAndGet();
            return Collections.singletonList(customType.clone());
        }, blue.getMergingProcessor());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), customEngine, processor.snapshotManager());

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", Collections.singletonList(
                             JsonPatch.add("/typed",
                                     new Node().type(new Node().blueId(typeBlueId)))), null)) {
            sequence.applyNext(0);
        }

        // then
        assertTrue(customProviderFetches.get() > 0,
                "the sequence must transient-wrap, not replace, an explicit custom engine");
        assertEquals("shared", runtime.snapshot().resolvedRoot().getAsText("/typed/inherited"));
        assertEquals(0, blueProviderFetches.get(),
                "the shared sequence cache should carry the custom engine's verified lookup");
    }

    @Test
    void shouldVerifyStaleEarlyCloseDoesNotRepublishAPrefixAfterProviderReplacement() {
        // given
        Node requestedType = new Node().name("Stale Close Requested Type")
                .properties("inherited", new Node().value("stable"));
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(requestedType);
        Blue blue = new Blue(blueId ->
                requestedBlueId.equals(blueId)
                        ? Collections.singletonList(requestedType.clone())
                        : null);
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/typed", new Node().type(new Node().blueId(requestedBlueId))),
                JsonPatch.add("/suffix", new Node().value("not-applied")));

        // when
        String intermediateInherited;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            intermediateInherited = runtime.snapshot()
                    .resolvedRoot()
                    .getAsText("/typed/inherited");
            blue.nodeProvider(blueId ->
                    requestedBlueId.equals(blueId)
                            ? Collections.singletonList(requestedType.clone())
                            : null);
        }
        int snapshotCacheAfterReplacement =
                blue.resolvedSnapshotCacheSize();
        int referenceCacheAfterReplacement =
                blue.resolvedReferenceCacheSize();
        String resolvedInherited = blue.resolve(
                        new Node().type(
                                new Node().blueId(
                                        requestedBlueId)))
                .getAsText("/inherited");

        // then
        assertEquals("stable", intermediateInherited);
        assertEquals(0, snapshotCacheAfterReplacement,
                "closing a stale partial sequence must respect explicit cache invalidation");
        assertEquals(0, referenceCacheAfterReplacement);
        assertEquals("stable", resolvedInherited);
    }

    @Test
    void shouldVerifyVerifiedOuterReferencePromotesItsVerifiedNestedDependency() {
        // given
        Node requestedNested = new Node().name("Requested Nested Type")
                .properties("inherited", new Node().value("exact"));
        String nestedBlueId = DirectBlueIdCalculator.calculateBlueId(requestedNested);
        Node outerType = new Node().name("Verified Outer Type")
                .properties("nested", new Node().type(new Node().blueId(nestedBlueId)));
        String outerBlueId = DirectBlueIdCalculator.calculateBlueId(outerType);
        AtomicInteger nestedFetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            if (outerBlueId.equals(blueId)) {
                return Collections.singletonList(outerType.clone());
            }
            if (!nestedBlueId.equals(blueId)) {
                return null;
            }
            nestedFetches.incrementAndGet();
            return Collections.singletonList(requestedNested.clone());
        });
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", Collections.singletonList(
                             JsonPatch.add("/retained",
                                     new Node().type(new Node().blueId(outerBlueId)))), null)) {
            sequence.applyNext(0);
        }
        Node independentlyResolved = blue.resolve(
                new Node().type(new Node().blueId(outerBlueId)));

        // then
        assertEquals("exact",
                runtime.snapshot().resolvedRoot().getAsText("/retained/nested/inherited"));
        assertEquals(1, nestedFetches.get());
        assertEquals("exact", independentlyResolved.getAsText("/nested/inherited"));
        assertEquals(1, nestedFetches.get(),
                "the retained verified dependency closure must be reusable");
    }

    @Test
    void shouldVerifyFinalReferencePromotionIncludesTransitiveProviderDependencies() {
        // given
        CountingBasicNodeProvider provider = new CountingBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Dependency Type")
                .properties("dependencyField", new Node().value("dependency")));
        String dependencyBlueId = provider.getBlueIdByName("Dependency Type");
        provider.addSingleNodes(new Node()
                .name("Retained Composite Type")
                .type(new Node().blueId(dependencyBlueId))
                .properties("compositeField", new Node().value("composite")));
        String compositeBlueId = provider.getBlueIdByName("Retained Composite Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", Arrays.asList(JsonPatch.add("/retained",
                             new Node().type(new Node().blueId(compositeBlueId)))), null)) {
            sequence.applyNext(0);
        }
        int dependencyFetches =
                provider.fetchesFor(dependencyBlueId);
        blue.resolve(
                new Node().type(
                        new Node().blueId(dependencyBlueId)));
        int afterSharedResolve =
                provider.fetchesFor(dependencyBlueId);

        // then
        assertTrue(dependencyFetches > 0);
        assertEquals(dependencyFetches, afterSharedResolve,
                "final promotion must include the retained reference's provider dependency closure");
    }

    @Test
    void shouldVerifySnapshotBackedMatchingPreviewPromotesItsFinalReachableReferences() {
        // given
        CountingBasicNodeProvider provider = new CountingBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Snapshot Preview Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("Snapshot Preview Type");
        Blue blue = new Blue(provider);
        ResolvedSnapshot initial = blue.resolveToSnapshot(
                new Node().properties("seed", new Node().value(0)));
        blue.clearResolvedSnapshotCache();
        provider.resetFetches();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                initial, processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Arrays.asList(JsonPatch.add("/typed",
                new Node().type(new Node().blueId(typeBlueId))));
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
        }
        int afterCommit = provider.fetchesFor(typeBlueId);
        blue.resolve(
                new Node().type(new Node().blueId(typeBlueId)));
        int afterSharedResolve = provider.fetchesFor(typeBlueId);

        // then
        assertEquals(1, afterCommit);
        assertEquals(afterCommit, afterSharedResolve);
    }

    @Test
    void shouldVerifyReentrantPatchReusesAndDoesNotPopTheOuterSequenceResolverScope() {
        // given
        CountingBasicNodeProvider provider = new CountingBasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Reentrant Retained Type")
                .properties("inherited", new Node().value("from-provider")));
        String typeBlueId = provider.getBlueIdByName("Reentrant Retained Type");
        provider.resetFetches();
        Blue blue = new Blue(provider);
        blue.clearResolvedSnapshotCache();
        DocumentProcessor processor = blue.getDocumentProcessor();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), processor.conformanceEngine(), processor.snapshotManager());
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/retained", new Node().type(new Node().blueId(typeBlueId))),
                JsonPatch.add("/tail", new Node().value("outer")));

        // when
        int afterFirstStep;
        int afterNestedStep;
        int afterFinalStep;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            afterFirstStep = provider.fetchesFor(typeBlueId);
            try (DocumentProcessingRuntime.PreparedPatchSequence nested =
                         runtime.preparePatchSequence("/", Collections.singletonList(
                                 JsonPatch.add("/nested", new Node().value("reentrant"))), null)) {
                nested.applyNext(0);
            }
            afterNestedStep = provider.fetchesFor(typeBlueId);
            sequence.applyNext(1);
            afterFinalStep = provider.fetchesFor(typeBlueId);
        }

        // then
        assertEquals(1, afterFirstStep);
        assertEquals(1, afterNestedStep);
        assertEquals(1, afterFinalStep,
                "the nested commit must leave the outer sequence cache active");
        assertEquals("reentrant", runtime.snapshot().resolvedRoot().getAsText("/nested"));
        assertEquals("outer", runtime.snapshot().resolvedRoot().getAsText("/tail"));
    }

    @Test
    void shouldVerifySequentialIntermediateStatesUseBlueTransientResolutionAndOnlyPublishTheFinalSnapshot() {
        // given
        Blue blue = new Blue();
        blue.clearResolvedSnapshotCache();
        CountingSnapshotManager manager = new CountingSnapshotManager(
                blue.getDocumentProcessor().snapshotManager());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(new Node(), null, manager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/first", new Node().value(1)),
                JsonPatch.add("/second", new Node().value(2)),
                JsonPatch.add("/third", new Node().value(3)));

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            sequence.applyNext(1);
            sequence.applyNext(2);
        }
        Blue finalOnly = new Blue();
        finalOnly.clearResolvedSnapshotCache();
        finalOnly.cacheResolvedSnapshot(runtime.snapshot());
        int finalOnlyStructuralCacheSize =
                finalOnly.resolvedStructuralCacheSize();

        // then
        assertEquals(0, manager.fromDocumentCalls);
        assertEquals(3, manager.transientFromDocumentCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(1, blue.resolvedSnapshotCacheSize(),
                "only the final sequence state belongs in Blue's shared snapshot cache");
        assertEquals(finalOnlyStructuralCacheSize,
                blue.resolvedStructuralCacheSize(),
                "the resolved interner must retain no more than the final graph itself");
    }

    @Test
    void shouldVerifyDirectWriteCanonicalPatchPreservesVerifiedProviderProvenance() {
        // given
        Node requestedType = new Node().name("Requested Patch Type")
                .properties("inherited", new Node().value("requested"));
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(requestedType);
        AtomicInteger providerFetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            providerFetches.incrementAndGet();
            return requestedBlueId.equals(blueId)
                    ? Collections.singletonList(requestedType.clone())
                    : null;
        });
        CountingSnapshotManager manager = new CountingSnapshotManager(
                blue.getDocumentProcessor().snapshotManager());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node().type(new Node().blueId(requestedBlueId)), null, manager);

        // when
        runtime.directWrite("/state", new Node().value("written"));
        ResolvedSnapshot snapshot = runtime.snapshot();

        // then
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(1, providerFetches.get());
        assertEquals("written", snapshot.canonicalRoot().getAsText("/state"));
        assertEquals("written", snapshot.resolvedRoot().getAsText("/state"));
        assertEquals("requested", snapshot.resolvedRoot().getAsText("/inherited"));
        assertEquals(requestedBlueId, snapshot.canonicalRoot().getType().getBlueId());
        assertEquals(requestedBlueId, snapshot.resolvedRoot().getType().getBlueId());
        assertEquals(snapshot.blueId(), snapshot.frozenCanonicalRoot().blueId());
        assertEquals(snapshot.canonicalAt("/state").blueId(), snapshot.resolvedAt("/state").blueId());
        assertTrue(blue.resolvedReferenceCacheSize() > 0);
    }

    private static final class CountingSnapshotManager implements ProcessingSnapshotManager {
        private final ProcessingSnapshotManager delegate;
        private int fromDocumentCalls;
        private int transientFromDocumentCalls;
        private int applyPatchCalls;
        private int cacheSnapshotCalls;

        private CountingSnapshotManager(ProcessingSnapshotManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            fromDocumentCalls++;
            return delegate.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            transientFromDocumentCalls++;
            return delegate.fromDocumentTransient(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            applyPatchCalls++;
            return delegate.applyPatch(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            cacheSnapshotCalls++;
            return delegate.cacheSnapshot(snapshot);
        }
    }

    private static final class CountingBasicNodeProvider extends BasicNodeProvider {
        private final java.util.Map<String, AtomicInteger> fetches = new java.util.HashMap<>();

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetches.computeIfAbsent(blueId, ignored -> new AtomicInteger()).incrementAndGet();
            return super.fetchByBlueId(blueId);
        }

        int fetchesFor(String blueId) {
            AtomicInteger count = fetches.get(blueId);
            return count != null ? count.get() : 0;
        }

        void resetFetches() {
            fetches.clear();
        }
    }

    private static final class OneShotBasicNodeProvider extends BasicNodeProvider {
        private final java.util.Map<String, AtomicInteger> fetches = new java.util.HashMap<>();

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            int fetch = fetches.computeIfAbsent(blueId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            return fetch == 1 ? super.fetchByBlueId(blueId) : null;
        }

        int fetchesFor(String blueId) {
            AtomicInteger count = fetches.get(blueId);
            return count != null ? count.get() : 0;
        }

        void resetFetches() {
            fetches.clear();
        }
    }
}
