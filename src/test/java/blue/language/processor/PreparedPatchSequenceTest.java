package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedPatchSequenceTest {

    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void shouldVerifyPreparedSequenceDefersSnapshotAndPlanningUntilPatchZeroApplication() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        RecordingMetrics metrics = new RecordingMetrics();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(),
                null,
                unchangedConformanceOverride(),
                manager,
                metrics);
        JsonPatch patch = JsonPatch.add("/first", new Node().value(1));

        // when
        JsonPatch validationPatch;
        int fromDocumentBeforeApplication;
        int applyPatchBeforeApplication;
        int cacheSnapshotBeforeApplication;
        long preparedSequencesBeforeApplication;
        long preparedPatchesBeforeApplication;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", Arrays.asList(patch), null)) {
            validationPatch = sequence.patchForValidation(0);
            fromDocumentBeforeApplication =
                    manager.fromDocumentCalls;
            applyPatchBeforeApplication = manager.applyPatchCalls;
            cacheSnapshotBeforeApplication =
                    manager.cacheSnapshotCalls;
            preparedSequencesBeforeApplication =
                    metrics.patchSequencesPrepared;
            preparedPatchesBeforeApplication =
                    metrics.patchesPrepared;
            sequence.applyNext(0);
        }

        // then
        assertEquals("/first", validationPatch.getPath());
        assertEquals(0, fromDocumentBeforeApplication,
                "validation must precede snapshot/planning initialization");
        assertEquals(0, applyPatchBeforeApplication);
        assertEquals(0, cacheSnapshotBeforeApplication);
        assertEquals(0L, preparedSequencesBeforeApplication);
        assertEquals(0L, preparedPatchesBeforeApplication);
        assertTrue(manager.fromDocumentCalls > 0);
        assertEquals(1, metrics.patchSequencesPrepared);
        assertEquals(1, metrics.patchesPrepared);
    }

    @Test
    void shouldVerifyForbiddenCyclicMemberTraversalFailsBeforeAnySnapshotProviderDemand() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node().properties(
                        "cyclic",
                        new Node().blueId(CYCLIC_MEMBER_BLUE_ID)),
                null,
                unchangedConformanceOverride(),
                manager,
                new RecordingMetrics());

        // when
        ProcessorFailureException failure;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence(
                             "/",
                             Arrays.asList(JsonPatch.add(
                                     "/cyclic/member",
                                     new Node().value(1))),
                             null)) {
            failure = FailureCapture.captureFailure(
                    () -> sequence.applyNext(0));
        }

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.CyclicSetMutationUnsupported,
                failure.errorCategory());
        assertEquals(0, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(0, manager.cacheSnapshotCalls);
    }

    @Test
    void shouldVerifySequentialWholeReferenceReplacementAllowsFollowingDescendantMutation() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = new Node().properties(
                "cyclic",
                new Node().blueId(CYCLIC_MEMBER_BLUE_ID));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace(
                        "/cyclic",
                        new Node().properties("member", new Node().value("replacement"))),
                JsonPatch.add("/cyclic/next", new Node().value("allowed")));

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            sequence.applyNext(1);
        }

        // then
        assertEquals("replacement", document.getAsText("/cyclic/member"));
        assertEquals("allowed", document.getAsText("/cyclic/next"));
    }

    @Test
    void shouldVerifyPreparedSequenceMembershipIsIndependentOfCallerListMutation() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(new Node(), null, manager);
        List<JsonPatch> callerPatches = new ArrayList<>(Arrays.asList(
                JsonPatch.add("/first", new Node().value(1)),
                JsonPatch.add("/second", new Node().value(2))));

        // when
        int preparedSize;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", callerPatches, null)) {
            callerPatches.clear();
            preparedSize = sequence.size();
            sequence.applyNext(0);
            sequence.applyNext(1);
        }

        // then
        assertEquals(2, preparedSize);
        assertEquals(1, runtime.document().getAsInteger("/first"));
        assertEquals(2, runtime.document().getAsInteger("/second"));
    }

    @Test
    void shouldVerifyPreparedSequenceRecordsEveryCommittedChangedPath() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(new Node(), null, manager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/first", new Node().value(1)),
                JsonPatch.add("/second", new Node().value(2)));

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            sequence.applyNext(1);
        }

        // then
        assertEquals(2, runtime.changedPaths().size());
        assertTrue(runtime.changedPaths().contains("/first"));
        assertTrue(runtime.changedPaths().contains("/second"));
    }

    @Test
    void shouldVerifyScopeExecutorUsesOneReusableSessionForLongUnpreviewedSequence() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        RecordingMetrics metrics = new RecordingMetrics();
        ProcessorEngine.Execution execution = execution(new Node(), manager, metrics);
        DocumentProcessingRuntime runtime = execution.runtime();
        List<JsonPatch> patches = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            patches.add(JsonPatch.add("/k" + index, new Node().value(index)));
        }

        // when
        execution.handlePatches("/", ContractBundle.builder().build(), patches, false);

        // then
        for (int index = 0; index < 9; index++) {
            assertEquals(index, runtime.document().getAsInteger("/k" + index));
        }
        assertEquals(1, runtime.patchSequencesPreparedForTest());
        assertEquals(1, runtime.batchPatchCallsForTest());
        assertEquals(9, runtime.batchPatchEntriesForTest());
        assertEquals(8, runtime.sequenceIntermediateSnapshotAdvancesForTest());
        assertEquals(1, runtime.sequenceSharedSnapshotCacheInsertsForTest());
        assertEquals(1, runtime.sequenceFinalSnapshotCacheInsertsForTest());
        assertEquals(1, manager.cacheSnapshotCalls());
        assertEquals(1, metrics.patchSequencesPrepared);
        assertEquals(9, metrics.patchesPrepared);
        assertEquals(0, metrics.singletonPatchTransactions);
    }

    @Test
    void shouldVerifyMatchingPreviewCommitsWithoutReplanningAndOnlyFinalStepEntersSharedCache() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        RecordingMetrics metrics = new RecordingMetrics();
        ProcessorEngine.Execution execution = execution(new Node(), manager, metrics);
        DocumentProcessingRuntime runtime = execution.runtime();
        List<JsonPatch> patches = patchesAdding("p", 5);
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);

        // when
        execution.handlePatches("/", ContractBundle.builder().build(), patches, false, preview);

        // then
        assertEquals(0, runtime.batchPatchPlanningNanosForTest());
        assertEquals(0, runtime.batchPatchConformanceNanosForTest());
        assertEquals(0, runtime.sequenceSuffixRebasesForTest());
        assertEquals(0, runtime.sequenceStalePreviewFallbacksForTest());
        assertEquals(4, runtime.sequenceIntermediateSnapshotAdvancesForTest());
        assertEquals(1, runtime.sequenceSharedSnapshotCacheInsertsForTest());
        assertEquals(1, runtime.sequenceFinalSnapshotCacheInsertsForTest());
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(0, metrics.singletonPatchTransactions);
        for (int index = 0; index < preview.size(); index++) {
            assertNull(preview.patch(index), "consumed preview entry " + index + " should be releasable");
        }
    }

    @Test
    void shouldVerifyFrozenPreviewWithIdentityEquivalentDifferentRepresentationIsReplanned() {
        // given
        Node materialized = new Node().properties("payload", new Node().value("value"));
        FrozenNode materializedValue = FrozenNode.fromNode(materialized);
        FrozenNode referenceValue = FrozenNode.fromNode(
                new Node().blueId(materializedValue.blueId()));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(new Node());
        List<FrozenJsonPatch> previewPatches = Arrays.asList(
                FrozenJsonPatch.add("/slot", materializedValue));
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyFrozenPatches(previewPatches);
        List<FrozenJsonPatch> requested = Arrays.asList(
                FrozenJsonPatch.add("/slot", referenceValue));

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.prepareFrozenPatchSequence("/", requested, preview)) {
            sequence.applyNext(0);
        }
        Node committed = runtime.document().getNode("/slot");

        // then
        assertTrue(committed.isReferenceOnly());
        assertEquals(referenceValue.blueId(), committed.getBlueId());
        assertEquals(1, runtime.sequenceStalePreviewFallbacksForTest());
    }

    @Test
    void shouldVerifyMutablePreviewWithIdentityEquivalentDifferentRepresentationIsReplanned() {
        // given
        Node materialized = new Node().properties("payload", new Node().value("value"));
        String blueId = FrozenNode.fromNode(materialized).blueId();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(new Node());
        List<JsonPatch> previewPatches = Arrays.asList(
                JsonPatch.add("/slot", materialized));
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(previewPatches);
        List<JsonPatch> requested = Arrays.asList(
                JsonPatch.add("/slot", new Node().blueId(blueId)));

        // when
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", requested, preview)) {
            sequence.applyNext(0);
        }
        Node committed = runtime.document().getNode("/slot");

        // then
        assertTrue(committed.isReferenceOnly());
        assertEquals(blueId, committed.getBlueId());
        assertEquals(1, runtime.sequenceStalePreviewFallbacksForTest());
    }

    @Test
    void shouldVerifyMutationBetweenPreparedStepsRebasesSuffixAndUsesActualBeforeState() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        RecordingMetrics metrics = new RecordingMetrics();
        Node document = new Node().properties("counter", new Node().value(0));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager, metrics);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/counter", new Node().value(1)),
                JsonPatch.replace("/counter", new Node().value(2)));
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);

        // when
        List<DocumentProcessingRuntime.DocumentUpdateData> secondUpdates;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
            runtime.applyPatch("/", JsonPatch.replace("/counter", new Node().value(41)));
            secondUpdates = sequence.applyNext(1);
        }

        // then
        assertEquals(1, secondUpdates.size());
        assertEquals(41, integerValue(secondUpdates.get(0).before()));
        assertEquals(2, integerValue(secondUpdates.get(0).after()));
        assertEquals(2, document.getAsInteger("/counter"));
        assertEquals(1, runtime.sequenceSuffixRebasesForTest());
        assertEquals(1, runtime.sequenceStalePreviewFallbacksForTest());
        assertEquals(0, runtime.sequenceFallbackPatchesForTest());
        assertEquals(2, manager.cacheSnapshotCalls,
                "the simulated handler write and final outer step each promote their own result");
        assertEquals(1, metrics.singletonPatchTransactions,
                "only the simulated handler patch uses the standalone singleton transaction");
        assertNull(preview.patch(0));
        assertNull(preview.patch(1));
    }

    @Test
    void shouldVerifyRepeatedReentryKeepsEveryActualIntermediateStateObservable() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        RecordingMetrics metrics = new RecordingMetrics();
        Node document = new Node().properties("counter", new Node().value(0));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager, metrics);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/counter", new Node().value(1)),
                JsonPatch.replace("/counter", new Node().value(2)),
                JsonPatch.replace("/counter", new Node().value(3)),
                JsonPatch.replace("/counter", new Node().value(4)));
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);

        // when
        int secondBefore;
        int thirdBefore;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
            runtime.applyPatch("/", JsonPatch.replace("/counter", new Node().value(10)));
            List<DocumentProcessingRuntime.DocumentUpdateData> second = sequence.applyNext(1);
            secondBefore = integerValue(second.get(0).before());

            runtime.applyPatch("/", JsonPatch.replace("/counter", new Node().value(20)));
            List<DocumentProcessingRuntime.DocumentUpdateData> third = sequence.applyNext(2);
            thirdBefore = integerValue(third.get(0).before());
            sequence.applyNext(3);
        }

        // then
        assertEquals(10, secondBefore);
        assertEquals(20, thirdBefore);
        assertEquals(4, document.getAsInteger("/counter"));
        assertEquals(2, runtime.sequenceSuffixRebasesForTest(),
                "each actual intervening mutation rebases the same reusable suffix session once");
        assertEquals(0, runtime.sequenceFallbackPatchesForTest());
        assertEquals(2, metrics.singletonPatchTransactions,
                "only the two simulated reentrant handler patches are standalone singletons");
    }

    @Test
    void shouldVerifyFailureInLaterStepKeepsPrefixAndClosePromotesCurrentSnapshot() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        RecordingMetrics metrics = new RecordingMetrics();
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager, metrics);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/prefix", new Node().value("committed")),
                JsonPatch.remove("/missing"),
                JsonPatch.add("/tail", new Node().value("not-run")));

        // when
        IllegalStateException sequenceFailure =
                FailureCapture.captureFailure(() -> {
            try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                         runtime.preparePatchSequence("/", patches, null)) {
                sequence.applyNext(0);
                sequence.applyNext(1);
            }
        });
        IllegalArgumentException tailFailure =
                FailureCapture.captureFailure(
                        () -> document.getAsNode("/tail"));

        // then
        assertNotNull(sequenceFailure);
        assertEquals("committed", document.getAsText("/prefix"));
        assertNotNull(tailFailure);
        assertEquals(1, runtime.sequenceIntermediateSnapshotAdvancesForTest());
        assertEquals(1, runtime.sequenceSharedSnapshotCacheInsertsForTest());
        assertEquals(1, runtime.sequenceFinalSnapshotCacheInsertsForTest());
        assertEquals(1, manager.cacheSnapshotCalls);
        assertNotNull(runtime.snapshot());
        assertEquals("committed", runtime.snapshot().resolvedRoot().getAsText("/prefix"));
    }

    @Test
    void shouldVerifyPublicAtomicBatchStillRollsBackEveryPatchWhenLaterEntryFails() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        RecordingMetrics metrics = new RecordingMetrics();
        Node document = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager, metrics);

        // when
        IllegalStateException failure =
                FailureCapture.captureFailure(
                        () -> runtime.applyPatches(
                                "/",
                                Arrays.asList(
                                        JsonPatch.replace(
                                                "/status",
                                                new Node().value(
                                                        "not-committed")),
                                        JsonPatch.remove("/missing"))));

        // then
        assertNotNull(failure);
        assertEquals("idle", document.getAsText("/status"));
        assertEquals(0, manager.cacheSnapshotCalls);
        assertEquals(0, runtime.patchSequencesPreparedForTest());
        assertEquals(1, runtime.batchPatchCallsForTest());
        assertEquals(2, runtime.batchPatchEntriesForTest());
    }

    @Test
    void shouldVerifyClosingPartiallyConsumedPreviewReleasesUnconsumedSuffix() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(new Node(), null, manager);
        List<JsonPatch> patches = patchesAdding("release", 3);
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(patches);

        // when
        WorkingDocument.PatchPreview consumedBeforeClose;
        WorkingDocument.PatchPreview secondBeforeClose;
        WorkingDocument.PatchPreview thirdBeforeClose;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, preview)) {
            sequence.applyNext(0);
            consumedBeforeClose = preview.patch(0);
            secondBeforeClose = preview.patch(1);
            thirdBeforeClose = preview.patch(2);
        }

        // then
        assertNull(consumedBeforeClose);
        assertNotNull(secondBeforeClose);
        assertNotNull(thirdBeforeClose);
        assertNull(preview.patch(0));
        assertNull(preview.patch(1));
        assertNull(preview.patch(2));
        assertEquals(1, manager.cacheSnapshotCalls,
                "closing after an intermediate advance must promote the surviving prefix");
    }

    @Test
    void shouldReleaseDiscardedWorkingPreviewScope() {
        // given
        ReleasingSnapshotManager manager = new ReleasingSnapshotManager();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), null, manager);
        List<JsonPatch> patches = Collections.singletonList(
                JsonPatch.add("/value", new Node().value(1)));

        WorkingDocument firstWorking = runtime.workingDocument("/");
        WorkingDocument.Preview discarded = firstWorking.previewAndApplyPatches(patches);
        firstWorking.close();

        // when
        discarded.close();

        // then
        assertEquals(2, manager.releaseCalls);
    }

    @Test
    void shouldTransferPreviewScopeOwnershipToPreparedSequence() {
        // given
        ReleasingSnapshotManager manager =
                new ReleasingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node(), null, manager);
        List<JsonPatch> patches = Collections.singletonList(
                JsonPatch.add("/value", new Node().value(1)));
        WorkingDocument working = runtime.workingDocument("/");
        WorkingDocument.Preview transferred =
                working.previewAndApplyPatches(patches);
        working.close();
        int beforeTransfer = manager.releaseCalls;

        // when
        int releasesWhileTransferred;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, transferred)) {
            sequence.applyNext(0);
            transferred.close();
            releasesWhileTransferred = manager.releaseCalls;
        }
        IllegalStateException closedWorkingFailure =
                FailureCapture.captureFailure(
                        () -> working.applyPatch(
                                JsonPatch.remove("/value")));

        // then
        assertEquals(beforeTransfer, releasesWhileTransferred,
                "a transferred preview no longer owns the handoff scope");
        assertEquals(beforeTransfer + 1, manager.releaseCalls,
                "the prepared sequence releases the transferred scope");
        assertEquals(manager.openCalls, manager.releaseCalls);
        assertNotNull(closedWorkingFailure);
    }

    @Test
    void shouldVerifySequenceCopiesEveryAuthoredPatchValueBeforeTheFirstStep() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        Node firstValue = new Node().properties("payload", new Node().value("first-before"));
        Node secondValue = new Node().properties("payload", new Node().value("second-before"));
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/first", firstValue),
                JsonPatch.add("/second", secondValue));

        // when
        String firstPreparedValue;
        String secondPreparedValue;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            firstValue.getProperties().get("payload").value("first-after");
            secondValue.getProperties().get("payload").value("second-after");
            firstPreparedValue = sequence.patchForValidation(0)
                    .getVal().getAsText("/payload");
            sequence.applyNext(0);
            secondPreparedValue = sequence.patchForValidation(1)
                    .getVal().getAsText("/payload");
            sequence.applyNext(1);
        }

        // then
        assertEquals("first-before", firstPreparedValue);
        assertEquals("second-before", secondPreparedValue);
        assertEquals("first-before", document.getAsText("/first/payload"));
        assertEquals("second-before", document.getAsText("/second/payload"));
    }

    @Test
    void shouldVerifyInvalidLaterValueIsFrozenOnlyAfterTheCommittedPrefix() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        Node invalidReferenceOverlay = new Node()
                .blueId("not-a-valid-reference")
                .properties("forbiddenSibling", new Node().value(true));
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/prefix", new Node().value("committed")),
                JsonPatch.add("/invalid", invalidReferenceOverlay),
                JsonPatch.add("/suffix", new Node().value("not-run")));

        // when
        IllegalArgumentException invalidPatchFailure;
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            invalidPatchFailure =
                    FailureCapture.captureFailure(
                            () -> sequence.applyNext(1));
        }
        IllegalArgumentException suffixFailure =
                FailureCapture.captureFailure(
                        () -> document.getAsNode("/suffix"));

        // then
        assertNotNull(invalidPatchFailure);
        assertEquals("committed", document.getAsText("/prefix"));
        assertNotNull(suffixFailure);
        assertEquals(1, manager.cacheSnapshotCalls());
    }

    @Test
    void shouldVerifyEarlierBoundaryFailureWinsOverMalformedSuffixValue() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = new Node().properties("scope", new Node());
        ProcessorEngine.Execution execution = execution(document, manager, new RecordingMetrics());
        Node invalidReferenceOverlay = new Node()
                .blueId("not-a-valid-reference")
                .properties("forbiddenSibling", new Node().value(true));

        // when
        RunTerminationException processingFailure =
                FailureCapture.captureFailure(
                () -> execution.handlePatches(
                        "/scope",
                        ContractBundle.builder().build(),
                        Arrays.asList(
                                JsonPatch.add(
                                        "/outside",
                                        new Node().value("forbidden")),
                                JsonPatch.add(
                                        "/scope/invalid",
                                        invalidReferenceOverlay)),
                        false));
        DocumentProcessingResult result = execution.result();
        IllegalArgumentException outsideFailure =
                FailureCapture.captureFailure(
                        () -> result.document()
                                .getAsNode("/outside"));
        IllegalArgumentException invalidSuffixFailure =
                FailureCapture.captureFailure(
                        () -> document.getAsNode(
                                "/scope/invalid"));

        // then
        assertNotNull(processingFailure);
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result));
        assertNotNull(outsideFailure);
        assertNotNull(invalidSuffixFailure);
    }

    @Test
    void shouldVerifyGasUsesTheAuthoredValueBeforeCanonicalEmptyNodeElision() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        ProcessorEngine.Execution execution = execution(new Node(), manager, new RecordingMetrics());
        Map<String, Node> authoredProperties = new LinkedHashMap<>();
        for (int index = 0; index < 40; index++) {
            authoredProperties.put("empty-child-with-a-long-key-" + index, new Node());
        }
        Node authoredValue = new Node().properties(authoredProperties);
        long authoredSizeCharge = (NodeCanonicalizer.canonicalSize(authoredValue) + 99L) / 100L;

        // when
        execution.handlePatches("/", ContractBundle.builder().build(),
                Arrays.asList(JsonPatch.add("/payload", authoredValue)), false);

        // then
        assertEquals(2L + 20L + authoredSizeCharge + 109L,
                execution.runtime().totalGas());
    }

    @Test
    void shouldVerifyFailedFinalPromotionKeepsTheCommittedPrefixAndCanBeRetried() {
        // given
        FailOnceSnapshotManager manager = new FailOnceSnapshotManager();
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/prefix", new Node().value("committed")),
                JsonPatch.add("/suffix", new Node().value("not-consumed")));
        DocumentProcessingRuntime.PreparedPatchSequence sequence =
                runtime.preparePatchSequence("/", patches, null);

        // when
        sequence.applyNext(0);
        IllegalStateException firstCloseFailure =
                FailureCapture.captureFailure(sequence::close);
        sequence.close();

        // then
        assertNotNull(firstCloseFailure);
        assertEquals("committed", document.getAsText("/prefix"));
        assertEquals(1, manager.cacheSnapshotCalls());
        assertEquals(1, runtime.sequenceFinalSnapshotCacheInsertsForTest());
    }

    private ProcessorEngine.Execution execution(Node document,
                                                CountingSnapshotManager manager,
                                                RecordingMetrics metrics) {
        DocumentProcessor processor = DocumentProcessor.builder()
                .withSnapshotManager(manager)
                .withProcessingMetricsSink(metrics)
                .build();
        return new ProcessorEngine.Execution(processor, document);
    }

    private List<JsonPatch> patchesAdding(String prefix, int count) {
        List<JsonPatch> patches = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            patches.add(JsonPatch.add("/" + prefix + index, new Node().value(index)));
        }
        return patches;
    }

    private static ConformancePlannerOverride unchangedConformanceOverride() {
        return new ConformancePlannerOverride() {
            @Override
            public boolean applies() {
                return true;
            }

            @Override
            public ConformancePlan plan(FrozenNode canonicalRoot,
                                        FrozenNode resolvedRoot,
                                        List<ConformanceChangedPath> changedPaths) {
                return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
            }
        };
    }

    private int integerValue(Node node) {
        return ((BigInteger) node.getValue()).intValue();
    }

    private static class CountingSnapshotManager implements ProcessingSnapshotManager {
        private int fromDocumentCalls;
        private int applyPatchCalls;
        private int cacheSnapshotCalls;

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            fromDocumentCalls++;
            FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(document.clone());
            return new ResolvedSnapshot(canonical,
                    FrozenNode.fromResolvedNode(document.clone()),
                    canonical.blueId());
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            applyPatchCalls++;
            CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
            return new ResolvedSnapshot(patched.root(),
                    FrozenNode.fromResolvedNode(patched.root().toNode()),
                    patched.blueId());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            cacheSnapshotCalls++;
            return snapshot;
        }

        int cacheSnapshotCalls() {
            return cacheSnapshotCalls;
        }
    }

    private static final class FailOnceSnapshotManager extends CountingSnapshotManager {
        private boolean fail = true;

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            if (fail) {
                fail = false;
                throw new IllegalStateException("simulated final cache failure");
            }
            return super.cacheSnapshot(snapshot);
        }
    }

    private static final class ReleasingSnapshotManager extends CountingSnapshotManager {
        private int openCalls;
        private int releaseCalls;

        @Override
        public ProcessingSnapshotManager transientSequence() {
            openCalls++;
            return new ReleasingScope(this);
        }
    }

    private static final class ReleasingScope implements ProcessingSnapshotManager {
        private final ReleasingSnapshotManager owner;
        private boolean released;

        private ReleasingScope(ReleasingSnapshotManager owner) {
            this.owner = owner;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return owner.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return owner.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            return owner.applyPatch(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return owner.cacheSnapshot(snapshot);
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            return this;
        }

        @Override
        public ProcessingSnapshotManager forkTransientSequence() {
            owner.openCalls++;
            return new ReleasingScope(owner);
        }

        @Override
        public void releaseTransientState() {
            if (!released) {
                released = true;
                owner.releaseCalls++;
            }
        }
    }

    private static final class RecordingMetrics implements ProcessingMetricsSink {
        private long patchSequencesPrepared;
        private long patchesPrepared;
        private long singletonPatchTransactions;

        @Override
        public void incrementPatchSequencesPrepared() {
            patchSequencesPrepared++;
        }

        @Override
        public void addPatchesPrepared(long count) {
            patchesPrepared += count;
        }

        @Override
        public void incrementSingletonPatchTransactions() {
            singletonPatchTransactions++;
        }
    }
}
