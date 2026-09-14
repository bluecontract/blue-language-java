package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngineTest;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static blue.language.processor.DocumentProcessingResultTestSupport.resolvedDocument;
import static blue.language.processor.DocumentProcessingResultTestSupport.snapshot;
import static blue.language.processor.DocumentProcessingResultTestSupport.diagnosticCategory;
import static blue.language.processor.DocumentProcessingResultTestSupport.diagnosticMessage;
import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorSnapshotTransactionTest {

    @Test
    void shouldUseCanonicalOverlaySnapshotForRuntimePatchWhenNoGeneralizationIsNeeded() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue("x: 1\nother: keep", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        // when
        runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2)));

        // then
        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(2, runtime.snapshot().canonicalRoot().getAsInteger("/x"));
        assertEquals("keep", runtime.snapshot().canonicalRoot().getAsText("/other"));
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldApplyWorkingDocumentPatchWithoutMutatingRuntime() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue("x: 1\nother: keep", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        runtime.snapshot();

        // when
        WorkingDocument working = runtime.workingDocument("/");
        working.applyPatch(JsonPatch.replace("/x", new Node().value(2)));
        Node materializedCanonical = working.materializeCanonicalRoot();

        // then
        assertFalse(working.usedMaterializedFallback());
        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(1, runtime.snapshot().canonicalRoot().getAsInteger("/x"));
        assertEquals(2, materializedCanonical.getAsInteger("/x"));
        assertEquals("keep", working.canonicalAt("/other").getValue());
        assertSnapshotConsistent(working.snapshot());
    }

    @Test
    void shouldAttributeWorkingDocumentMutablePatchToFixedCallerSource() {
        // given
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        Node document = YAML_MAPPER.readValue("x: 1\nother: keep", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, null, metrics);

        // when
        try (WorkingDocument externalWorking = runtime.workingDocument("/")) {
            externalWorking.applyPatch(JsonPatch.replace("/x", new Node().value(2)));
        }
        try (WorkingDocument processorWorking =
                     runtime.workingDocument("/", PatchSource.CUSTOM_PROCESSOR)) {
            processorWorking.applyPatch(JsonPatch.replace("/x", new Node().value(3)));
        }
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();

        // then
        assertEquals(2L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(1L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.LEGACY_PUBLIC_API"), snapshot.toString());
        assertEquals(1L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.CUSTOM_PROCESSOR"), snapshot.toString());
    }

    @Test
    void shouldCommitPrecomputedWorkingDocumentPreviewWithoutReplanning() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue("x: 1\nother: keep", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        runtime.snapshot();
        JsonPatch patch = JsonPatch.replace("/x", new Node().value(2));

        // when
        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(Collections.singletonList(patch));
        List<DocumentUpdateData> updates =
                runtime.applyPrecomputedPatch("/", patch, preview.patch(0));

        // then
        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(1, updates.size());
        assertEquals("/x", updates.get(0).path());
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(1, runtime.countersForTest().batchPatchCalls());
        assertEquals(1, runtime.countersForTest().batchPatchEntries());
        assertEquals(0, runtime.countersForTest().batchPatchPlanningNanos());
        assertEquals(0, runtime.countersForTest().batchPatchConformanceNanos());
        assertTrue(runtime.countersForTest().batchPatchBuildUpdatesNanos() > 0);
        assertTrue(runtime.countersForTest().batchPatchCommitNanos() > 0);
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldRecordMaterializedFallbackWhenRuntimeHasNoSnapshotManager() {
        // given
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null);

        // when
        WorkingDocument working = runtime.workingDocument("/");
        working.applyPatch(JsonPatch.replace("/x", new Node().value(2)));

        // then
        assertTrue(working.usedMaterializedFallback());
        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(2, working.commitToNode().getAsInteger("/x"));
        assertSnapshotConsistent(working.commitSnapshot());
    }

    @Test
    void shouldLeaveWorkingAndRuntimeUnchangedWhenWorkingDocumentPreviewFails() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Fixed One\n" +
                "x: 1");
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Fixed One") + "\n" +
                "x: 1", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document,
                blue.conformanceEngine(),
                new CountingSnapshotManager(blue));
        // when
        WorkingDocument working = runtime.workingDocument("/");
        Throwable failure = FailureCapture.captureFailure(
                () -> working.applyPatch(
                        JsonPatch.replace(
                                "/x",
                                new Node().value(2))));
        Node materializedResolved =
                working.materializeResolvedRoot();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(1, materializedResolved.getAsInteger("/x"));
        assertEquals(nodeProvider.getBlueIdByName("Fixed One"),
                materializedResolved.getType().getBlueId());
    }

    @Test
    void shouldRunWorkingDocumentGeneralizationPolicyOnFrozenPreviewState() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "  amount: 150\n" +
                "  currency: EUR\n", Node.class));
        document.contracts(new Node().properties("generalization",
                new Node()
                        .type(new Node().blueId(RuntimeBlueIds.TYPE_GENERALIZATION_POLICY))
                        .properties("rules", new Node().items(java.util.Collections.singletonList(
                                new Node().properties("path", new Node().value("/price"),
                                        "mode", new Node().value("nearest-valid-ancestor"),
                                        "mustRemainSubtypeOf", new Node().blueId(nodeProvider.getBlueIdByName("Price"))))))));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document,
                blue.conformanceEngine(),
                new CountingSnapshotManager(blue));

        // when
        WorkingDocument working = runtime.workingDocument("/")
                .applyPatch(JsonPatch.replace("/price/currency", new Node().value("USD")));

        // then
        assertEquals("EUR", document.getAsText("/price/currency"));
        assertEquals("USD", working.resolvedAt("/price/currency").getValue());
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                working.resolvedAt("/price").getType().getReferenceBlueId());
    }

    @Test
    void shouldUseResolvedSnapshotIndexForRuntimeReadsWhenSnapshotIsAvailable() {
        // given
        Node canonical = YAML_MAPPER.readValue("local: yes", Node.class);
        Node resolved = YAML_MAPPER.readValue("local: yes\ninherited: from-type", Node.class);
        CountingSnapshotManager manager = new CountingSnapshotManager(canonical, resolved);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(canonical.clone(), null, manager);

        // when
        runtime.snapshot();

        // then
        assertEquals("from-type", runtime.nodeAt("/inherited").getValue());
        assertTrue(runtime.contains("/inherited"));
        assertEquals(1, manager.fromDocumentCalls);
    }

    @Test
    void shouldKeepMutableViewCanonicalWhileResolvedNodeReadUsesSnapshotIndex() {
        // given
        Node canonical = YAML_MAPPER.readValue("local: yes", Node.class);
        Node resolved = YAML_MAPPER.readValue("local: yes\ninherited: from-type", Node.class);
        CountingSnapshotManager manager = new CountingSnapshotManager(canonical, resolved);

        // when
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(canonical.clone(), null, manager);
        Throwable missingInherited =
                missingPathFailure(
                        runtime.document(),
                        "/inherited");

        // then
        assertEquals("from-type", runtime.resolvedNodeAt("/inherited").getValue());
        assertMissing(missingInherited);
        assertEquals(1, manager.fromDocumentCalls);
    }

    @Test
    void shouldTreatSnapshotPlanAsAuthoritativeAfterImmutablePlanning() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.returnCurrentSnapshotOnApplyPatch = true;
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        // when
        runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2)));

        // then
        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(2, runtime.snapshot().resolvedRoot().getAsInteger("/x"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldTrackMixedAddReplaceRemoveAndArrayAppendPatchesInRuntimeSnapshot() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue(
                "profile:\n" +
                "  label: Ana\n" +
                "  location:\n" +
                "    existing: true\n" +
                "tags:\n" +
                "  - old\n" +
                "obsolete: true", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        // when
        runtime.applyPatch("/", JsonPatch.add("/profile/location/city", new Node().value("Warsaw")));
        runtime.applyPatch("/", JsonPatch.replace("/profile/label", new Node().value("Anna")));
        runtime.applyPatch("/", JsonPatch.add("/tags/-", new Node().value("new")));
        runtime.applyPatch("/", JsonPatch.remove("/obsolete"));
        Node canonical = runtime.snapshot().canonicalRoot();
        Throwable missingObsolete =
                missingPathFailure(canonical, "/obsolete");

        // then
        assertEquals("Warsaw", canonical.getAsText("/profile/location/city"));
        assertEquals("Anna", canonical.getAsText("/profile/label"));
        assertEquals("old", canonical.getAsText("/tags/0"));
        assertEquals("new", canonical.getAsText("/tags/1"));
        assertMissing(missingObsolete);
        assertEquals(4, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(4, manager.cacheSnapshotCalls);
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldRebuildRuntimeSnapshotFromGeneralizedDocumentWhenConformanceChangesTypes() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        CountingSnapshotManager manager = new CountingSnapshotManager(blue);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        permitGeneralization(document);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine(), manager);

        // when
        runtime.applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        // then
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals("Price", runtime.snapshot().resolvedRoot().getAsNode("/price/type").getName());
        assertEquals("Global Product", runtime.snapshot().resolvedRoot().getType().getName());
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldNotMutatePreviousSnapshotRootsDuringImmutableConformancePlanning() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        CountingSnapshotManager manager = new CountingSnapshotManager(blue);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        permitGeneralization(document);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine(), manager);
        ResolvedSnapshot before = runtime.snapshot();
        FrozenNode beforeResolvedRoot = before.frozenResolvedRoot();

        // when
        runtime.applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        // then
        assertEquals("EUR", beforeResolvedRoot.at("/price/currency").getValue());
        assertEquals("Price in EUR", beforeResolvedRoot.property("price").getType().getName());
        assertEquals("European Product", beforeResolvedRoot.getType().getName());
        assertEquals("USD", runtime.snapshot().resolvedAt("/price/currency").getValue());
        assertEquals("Price", runtime.snapshot().frozenResolvedRoot().property("price").getType().getName());
        assertEquals("Global Product", runtime.snapshot().frozenResolvedRoot().getType().getName());
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldNotPatchOrRebuildSnapshotWhenImmutableConformancePlanFails() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Fixed One\n" +
                "x: 1");
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        CountingSnapshotManager manager = new CountingSnapshotManager(blue);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Fixed One") + "\n" +
                "x: 1", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine(), manager);
        ResolvedSnapshot before = runtime.snapshot();
        String selectedBefore = blue.nodeToJson(document);
        String canonicalBefore = blue.nodeToJson(before.canonicalRoot());
        String resolvedBefore = blue.nodeToJson(before.resolvedRoot());

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.replace(
                                "/x",
                                new Node().value(2))));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(selectedBefore, blue.nodeToJson(document));
        assertEquals(canonicalBefore, blue.nodeToJson(runtime.snapshot().canonicalRoot()));
        assertEquals(resolvedBefore, blue.nodeToJson(runtime.snapshot().resolvedRoot()));
        assertEquals(nodeProvider.getBlueIdByName("Fixed One"), document.getType().getBlueId());
        assertEquals(1, runtime.snapshot().resolvedRoot().getAsInteger("/x"));
        assertEquals(before.blueId(), runtime.snapshot().blueId());
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(0, manager.cacheSnapshotCalls);
    }

    @Test
    void shouldUseResolvedSnapshotIndexesForInheritedUpdateMetadataValues() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Counter\n" +
                "x:\n" +
                "  type: Integer");
        nodeProvider.addSingleDocs(
                "name: Zero Counter\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Counter") + "\n" +
                "x: 0");
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        CountingSnapshotManager manager = new CountingSnapshotManager(blue);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Counter Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Zero Counter") + "\n", Node.class));
        permitGeneralization(document);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine(), manager);

        // when
        DocumentUpdateData data =
                runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(1)));

        // then
        assertEquals(0, ((BigInteger) data.before().getValue()).intValue());
        assertEquals(1, ((BigInteger) data.after().getValue()).intValue());
        assertEquals("Counter", runtime.snapshot().resolvedRoot().getType().getName());
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldKeepCanonicalSnapshotInSameRuntimeTransactionForDirectWrite() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = Nodes.emptyObject();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        // when
        runtime.directWrite("/checkpoint/lastEvent", new Node().value("evt-1"));
        runtime.directWrite("/checkpoint/lastEvent", null);
        Throwable missingDocumentValue =
                missingPathFailure(
                        document,
                        "/checkpoint/lastEvent");
        Throwable missingSnapshotValue =
                missingPathFailure(
                        runtime.snapshot()
                                .canonicalRoot(),
                        "/checkpoint/lastEvent");

        // then
        assertMissing(missingDocumentValue);
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(2, manager.cacheSnapshotCalls);
        assertMissing(missingSnapshotValue);
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldCommitBatchSnapshotForRuntimePatchWithoutSnapshotPatchManagerFallback() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failApplyPatch = true;
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        // when
        runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2)));

        // then
        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(2, runtime.snapshot().canonicalRoot().getAsInteger("/x"));
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void shouldRollBackDocumentAndSnapshotTogetherWhenBatchSnapshotCacheFails() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failCacheSnapshot = true;
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.replace(
                                "/x",
                                new Node().value(2))));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
    }

    @Test
    void shouldRollBackDocumentAndSnapshotTogetherWhenDirectWriteSnapshotFails() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failFromDocumentOnCall = 2;
        Node document = YAML_MAPPER.readValue("checkpoint:\n  lastEvent: evt-0", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        ResolvedSnapshot before = runtime.snapshot();

        // when
        IllegalStateException failure = captureFailure(
                () -> runtime.directWrite("/checkpoint/lastEvent", new Node().value("evt-1")));

        // then
        assertEquals(IllegalStateException.class, failure.getClass());
        assertEquals("snapshot rebuild failed", failure.getMessage());
        assertEquals("evt-0", document.getAsText("/checkpoint/lastEvent"));
        assertEquals(before.blueId(), runtime.snapshot().blueId());
        assertEquals("evt-0", runtime.snapshot().canonicalRoot().getAsText("/checkpoint/lastEvent"));
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(0, manager.cacheSnapshotCalls);
    }

    @Test
    void shouldNotTouchExistingRuntimeSnapshotWhenImmutablePatchPlanFails() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue("rows:\n  items:\n    - a", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        ResolvedSnapshot before = runtime.snapshot();

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.remove("/rows/5")));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertEquals("a", document.getAsText("/rows/0"));
        assertEquals(before.blueId(), runtime.snapshot().blueId());
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
    }

    @Test
    void shouldNotCallSnapshotPatchManagerForInvalidImmutablePatchPlan() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.returnCurrentSnapshotOnApplyPatch = true;
        Node document = YAML_MAPPER.readValue("rows:\n  items:\n    - a", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        ResolvedSnapshot before = runtime.snapshot();

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.remove("/rows/5")));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertEquals("a", document.getAsText("/rows/0"));
        assertEquals(before.blueId(), runtime.snapshot().blueId());
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
    }

    @Test
    void shouldCarryCanonicalRuntimeDocumentInProcessorResultWithoutBluePostProcessing() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessor processor =
                DocumentProcessorExactFeederSupport.processor(
                        manager,
                        new SetPropertyContractProcessor());
        Node document = YAML_MAPPER.readValue(
                "name: Runtime Snapshot\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 7\n", Node.class);

        // when
        DocumentProcessingResult initialized = processor.initializeDocument(document);
        Node event = new TestEvent()
                .eventId("evt-runtime-snapshot")
                .toNode();
        ProcessingDebugResult processedDebug =
                processor.processDocumentWithTrace(
                        initialized.document().clone(),
                        event);
        DocumentProcessingResult processed = processedDebug.processResult();

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                processed.status(),
                "category=" + diagnosticCategory(processed)
                        + ", diagnostic=" + diagnosticMessage(processed));
        assertNotNull(processedDebug.resultingSnapshot());
        assertEquals(
                processedDebug.resultingSnapshot().blueId(),
                DirectBlueIdCalculator.calculateBlueId(processed.document()));
        assertEquals(7, processed.document().getAsInteger("/x"));
        assertNotNull(processed.document().getAsText(
                "/contracts/checkpoint/entries/testChannel/domain/blueId"));
        assertEquals(DirectBlueIdCalculator.calculateBlueId(event),
                processed.document().getAsText(
                        "/contracts/checkpoint/entries/testChannel/subject/blueId"));
        assertTrue(processedDebug.resultingSnapshot().hasCanonicalIdentity());
        assertFalse(processedDebug.resultingSnapshot().isResolutionComplete());
        assertEquals(0, manager.cacheSnapshotCalls,
                "Deferred runtime snapshots must not enter the shared cache");
        assertSnapshotConsistent(processedDebug.resultingSnapshot());
    }

    @Test
    void shouldRebuildOnlyWritesThatRequireResolutionDuringSnapshotNativeProcessing() {
        // given
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessor processor =
                DocumentProcessorExactFeederSupport.processor(
                        manager,
                        new SetPropertyContractProcessor());
        Node initialized = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    document: doc-1\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 9\n", Node.class);
        FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(initialized);
        ResolvedSnapshot snapshot = new ResolvedSnapshot(canonical,
                FrozenNode.fromResolvedNode(initialized),
                canonical.blueId());

        // when
        ProcessingDebugResult debug = processor.processDocumentWithTrace(
                snapshot,
                new TestEvent().eventId("evt-snapshot-native").toNode());
        DocumentProcessingResult result = debug.processResult();

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                "category=" + diagnosticCategory(result)
                        + ", diagnostic=" + diagnosticMessage(result));
        assertTrue(manager.fromDocumentCalls >= 2,
                "feeder verification and scalar writes must use coherent immutable snapshots");
        assertTrue(manager.fromDocumentInputs.stream()
                .filter(node -> node.getType() == null)
                .allMatch(node -> node.getContracts() != null),
                "document writes requiring resolution must retain the "
                        + "complete canonical companion");
        assertTrue(manager.fromDocumentInputs.stream()
                .anyMatch(node -> node.getType() == null
                        && node.getContracts() != null));
        assertTrue(manager.cacheSnapshotCalls > 0);
        assertEquals(9, result.document().getAsInteger("/x"));
        assertSnapshotConsistent(debug.resultingSnapshot());
    }

    @Test
    void shouldMatchNodeBasedGasAndResultDuringBlueSnapshotNativeProcessing() {
        // given
        Node document = YAML_MAPPER.readValue(
                "name: Runtime Snapshot Parity\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 7\n", Node.class);
        DocumentProcessor nodeProcessor =
                DocumentProcessorExactFeederSupport.processor(
                        new CountingSnapshotManager(),
                        new SetPropertyContractProcessor());
        DocumentProcessor snapshotProcessor =
                DocumentProcessorExactFeederSupport.processor(
                        new CountingSnapshotManager(),
                        new SetPropertyContractProcessor());
        FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(document);
        ResolvedSnapshot inputSnapshot = new ResolvedSnapshot(canonical,
                FrozenNode.fromResolvedNode(document),
                canonical.blueId());
        Node event = new TestEvent().eventId("evt-parity").toNode();

        // when
        DocumentProcessingResult nodeInitialized = nodeProcessor.initializeDocument(document.clone());
        DocumentProcessingResult snapshotInitialized = snapshotProcessor.initializeDocument(inputSnapshot);
        DocumentProcessingResult nodeProcessed = nodeProcessor.processDocument(nodeInitialized.document().clone(), event.clone());
        DocumentProcessingResult snapshotProcessed = snapshotProcessor.processDocument(
                uncheckedSnapshot(snapshotInitialized.document()),
                event.clone());

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                nodeProcessed.status(),
                "node category=" + diagnosticCategory(nodeProcessed)
                        + ", diagnostic="
                        + diagnosticMessage(nodeProcessed));
        assertEquals(
                ProcessorStatus.SUCCESS,
                snapshotProcessed.status(),
                "snapshot category="
                        + diagnosticCategory(snapshotProcessed)
                        + ", diagnostic="
                        + diagnosticMessage(snapshotProcessed));
        String expectedSubject =
                DirectBlueIdCalculator.calculateBlueId(event);
        String nodeDomain = nodeProcessed.document().getAsText(
                "/contracts/checkpoint/entries/testChannel/domain/blueId");
        String snapshotDomain = snapshotProcessed.document().getAsText(
                "/contracts/checkpoint/entries/testChannel/domain/blueId");
        assertEquals(nodeInitialized.totalGas(), snapshotInitialized.totalGas());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(nodeInitialized.document()),
                DirectBlueIdCalculator.calculateBlueId(snapshotInitialized.document()));
        assertEquals(nodeProcessed.totalGas(), snapshotProcessed.totalGas());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(nodeProcessed.document()),
                DirectBlueIdCalculator.calculateBlueId(snapshotProcessed.document()));
        assertEquals(7, snapshotProcessed.document().getAsInteger("/x"));
        assertNotNull(nodeDomain);
        assertEquals(nodeDomain, snapshotDomain);
        assertEquals(expectedSubject,
                nodeProcessed.document().getAsText(
                        "/contracts/checkpoint/entries/testChannel/subject/blueId"));
        assertEquals(expectedSubject,
                snapshotProcessed.document().getAsText(
                        "/contracts/checkpoint/entries/testChannel/subject/blueId"));
    }

    @Test
    void shouldReuseInputFrozenTypeGraphDuringSnapshotNativeProcessing() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Typed Runtime Root\n" +
                "label:\n" +
                "  type: Text");
        Blue blue = ProcessorTestSupport.blue(
                DocumentProcessorExactFeederSupport
                        .strictDirectContentProvider(provider));
        ResolvedSnapshot input = blue.resolveToSnapshot(YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + provider.getBlueIdByName("Typed Runtime Root") + "\n" +
                "label: one", Node.class));

        // when
        DocumentProcessingResult initialized = blue.initializeDocument(input);
        ResolvedSnapshot initializedSnapshot = snapshot(blue, initialized);

        // then
        assertSame(input.frozenResolvedRoot().getType(), initializedSnapshot.frozenResolvedRoot().getType());
        assertEquals("Typed Runtime Root", initializedSnapshot.frozenResolvedRoot().getType().getName());
        assertSnapshotConsistent(initializedSnapshot);
    }

    @Test
    void shouldUseResolvedSnapshotIndexForExecutionContextReadsWhenSnapshotIsAvailable() {
        // given
        Node canonical = YAML_MAPPER.readValue("local: yes", Node.class);
        Node resolved = YAML_MAPPER.readValue("local: yes\ninherited: from-type", Node.class);
        CountingSnapshotManager manager = new CountingSnapshotManager(canonical, resolved);
        DocumentProcessor processor = DocumentProcessor.builder()
                .snapshotStore(manager)
                .build();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, canonical.clone());
        execution.preflightScope("/");
        execution.runtime().snapshot();
        // when
        ProcessorExecutionContext context = execution.createContext("/",
                execution.bundleForScope("/"),
                new Node(),
                false);

        // then
        assertEquals("from-type", context.documentAt("/inherited").getValue());
        assertTrue(context.documentContains("/inherited"));
        assertEquals(1, manager.fromDocumentCalls);
    }

    @Test
    void shouldOmitDerivableCanonicalOverrideWhenProcessorPatchesInheritedValue() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Money\n" +
                "cents: 0");
        String moneyId = provider.getBlueIdByName("Money");
        Blue blue = ProcessorTestSupport.blue(
                DocumentProcessorExactFeederSupport
                        .strictDirectContentProvider(provider));
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
        Node document = YAML_MAPPER.readValue(
                "name: Wallet\n" +
                "balance:\n" +
                "  type:\n" +
                "    blueId: " + moneyId + "\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    path: /balance\n" +
                "    propertyKey: cents\n" +
                "    propertyValue: 0\n", Node.class);
        DocumentProcessingResult initialized = blue.initializeDocument(document);

        // when
        ResolvedSnapshot initializedSnapshot = snapshot(blue, initialized);
        DocumentProcessingResult processed = blue.processDocument(initializedSnapshot,
                blue.objectToNode(new TestEvent().eventId("evt-inherited")));
        ResolvedSnapshot processedSnapshot = snapshot(blue, processed);

        // then
        assertNull(initializedSnapshot.canonicalAt("/balance/cents"));
        assertEquals(0, resolvedDocument(blue, processed).getAsInteger("/balance/cents"));
        assertNull(processedSnapshot.canonicalAt("/balance/cents"));
        assertSnapshotConsistent(processedSnapshot);
    }

    @Test
    void shouldParticipateWithInheritedEffectiveContractsWithoutMaterializingOverrides() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Event Driven Type\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 42\n");
        Blue blue = ProcessorTestSupport.blue(
                DocumentProcessorExactFeederSupport
                        .strictDirectContentProvider(provider));
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
        Node document = YAML_MAPPER.readValue(
                "name: Inherits Runtime Contracts\n" +
                "type:\n" +
                "  blueId: " + provider.getBlueIdByName("Event Driven Type") + "\n" +
                "x: 0\n", Node.class);

        // when
        DocumentProcessingResult initialized = blue.initializeDocument(document);
        DocumentProcessingResult processed = blue.processDocument(snapshot(blue, initialized),
                new TestEvent().eventId("evt-inherited-contract").toNode());
        Throwable missingTestChannel =
                missingPathFailure(
                        processed.document(),
                        "/contracts/testChannel");
        Throwable missingSetter =
                missingPathFailure(
                        processed.document(),
                        "/contracts/setter");

        // then
        assertEquals(42, resolvedDocument(blue, processed).getAsInteger("/x"));
        assertEquals(42, processed.document().getAsInteger("/x"));
        assertMissing(missingTestChannel);
        assertMissing(missingSetter);
        assertEquals("Event Driven Type", resolvedDocument(blue, processed).getType().getName());
        assertSnapshotConsistent(snapshot(blue, processed));
    }

    @Test
    void shouldUseInheritedEffectiveFieldsForSelectedTypeOnlyContract() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Event Driven Type\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 42\n");
        Blue blue = ProcessorTestSupport.blue(
                DocumentProcessorExactFeederSupport
                        .strictDirectContentProvider(provider));
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
        Node document = YAML_MAPPER.readValue(
                "name: Selects Runtime Contracts\n" +
                "type:\n" +
                "  blueId: " + provider.getBlueIdByName("Event Driven Type") + "\n" +
                "x: 0\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setter:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n", Node.class);

        // when
        DocumentProcessingResult initialized = blue.initializeDocument(document);
        DocumentProcessingResult processed = blue.processDocument(snapshot(blue, initialized),
                new TestEvent().eventId("evt-selected-contract").toNode());
        Throwable missingChannel =
                missingPathFailure(
                        processed.document(),
                        "/contracts/setter/channel");
        Throwable missingPropertyKey =
                missingPathFailure(
                        processed.document(),
                        "/contracts/setter/propertyKey");
        Throwable missingPropertyValue =
                missingPathFailure(
                        processed.document(),
                        "/contracts/setter/propertyValue");
        Node resolved = resolvedDocument(blue, processed);

        // then
        assertEquals(42, resolvedDocument(blue, processed).getAsInteger("/x"));
        assertEquals(42, processed.document().getAsInteger("/x"));
        assertMissing(missingChannel);
        assertMissing(missingPropertyKey);
        assertMissing(missingPropertyValue);
        assertEquals("testChannel", resolved.getAsText("/contracts/setter/channel"));
        assertEquals("/x", resolved.getAsText("/contracts/setter/propertyKey"));
        assertEquals(42, resolved.getAsInteger("/contracts/setter/propertyValue"));
        assertSnapshotConsistent(snapshot(blue, processed));
    }

    private static Throwable missingPathFailure(
            Node node,
            String path) {
        return FailureCapture.captureFailure(
                () -> node.getAsNode(path));
    }

    private static void assertMissing(Throwable failure) {
        assertTrue(failure instanceof IllegalArgumentException);
    }

    private static Node canonicalRoot(
            Blue blue,
            Node source) {
        return source;
    }

    private static void assertSnapshotConsistent(ResolvedSnapshot snapshot) {
        assertEquals(DirectBlueIdCalculator.calculateUncheckedBlueId(snapshot.canonicalRoot()), snapshot.blueId());
    }

    private static ResolvedSnapshot uncheckedSnapshot(Node canonicalRoot) {
        FrozenNode frozenCanonical =
                FrozenNode.fromUncheckedCanonicalNode(canonicalRoot);
        return new ResolvedSnapshot(
                frozenCanonical,
                FrozenNode.fromResolvedNode(canonicalRoot),
                frozenCanonical.blueId());
    }

    private static final class CountingSnapshotManager implements ProcessingSnapshotManager {
        private static final CanonicalTypeIdentityLookup
                COMPLETE_PURE_REFERENCE_EVIDENCE =
                new CanonicalTypeIdentityLookup() {
                    @Override
                    public boolean hasCompleteCoverage() {
                        return true;
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(
                            Node completedType) {
                        String blueId = requireCanonicalTypeBlueId(
                                completedType);
                        return Optional.of(CanonicalTypeIdentityEvidence
                                .referenceSource(blueId));
                    }

                    @Override
                    public String requireCanonicalTypeBlueId(
                            Node completedType) {
                        return CanonicalTypeIdentityLookup.incomplete()
                                .requireCanonicalTypeBlueId(completedType);
                    }
                };

        private final Blue blue;
        private final Node canonical;
        private final Node resolved;
        private int fromDocumentCalls;
        private int applyPatchCalls;
        private int cacheSnapshotCalls;
        private boolean failApplyPatch;
        private boolean failCacheSnapshot;
        private boolean returnCurrentSnapshotOnApplyPatch;
        private int failFromDocumentOnCall;
        private final List<Node> fromDocumentInputs = new java.util.ArrayList<>();
        private boolean canonicalIdentityResolution;

        private CountingSnapshotManager() {
            this(null, null, null);
        }

        private CountingSnapshotManager(Blue blue) {
            this(blue, null, null);
        }

        private CountingSnapshotManager(Node canonical, Node resolved) {
            this(null, canonical, resolved);
        }

        private CountingSnapshotManager(Blue blue, Node canonical, Node resolved) {
            this.blue = blue;
            this.canonical = canonical;
            this.resolved = resolved;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            fromDocumentCalls++;
            if (!canonicalIdentityResolution) {
                fromDocumentInputs.add(document.clone());
            }
            if (fromDocumentCalls == failFromDocumentOnCall) {
                throw new IllegalStateException("snapshot rebuild failed");
            }
            if (blue != null) {
                return blue.resolveToSnapshot(document);
            }
            Node canonicalSource = canonical != null ? canonical.clone() : document.clone();
            Node resolvedSource = resolved != null ? resolved.clone() : document.clone();
            FrozenNode canonicalRoot = FrozenNode.fromUncheckedCanonicalNode(canonicalSource);
            if (canonical == null && resolved == null) {
                return ResolvedSnapshot.withCanonicalTypeIdentities(
                        canonicalRoot,
                        FrozenNode.fromResolvedNode(resolvedSource),
                        COMPLETE_PURE_REFERENCE_EVIDENCE);
            }
            return new ResolvedSnapshot(canonicalRoot,
                    FrozenNode.fromResolvedNode(resolvedSource),
                    canonicalRoot.blueId());
        }

        @Override
        public ResolvedSnapshot fromDocumentTransientForCanonicalIdentity(
                Node document) {
            canonicalIdentityResolution = true;
            try {
                if (blue == null) {
                    return canonicalSnapshot(document, Collections.<String>emptySet());
                }
                return ProcessingSnapshotManager.super
                        .fromDocumentTransientForCanonicalIdentity(document);
            } finally {
                canonicalIdentityResolution = false;
            }
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document, Collection<String> preservedPaths) {
            if (blue == null) {
                // This synthetic manager never resolves any subtree, so all Source
                // paths remain intact in its existing canonical/resolved fixture.
                return fromDocument(document);
            }
            fromDocumentCalls++;
            if (!canonicalIdentityResolution) fromDocumentInputs.add(document.clone());
            if (fromDocumentCalls == failFromDocumentOnCall)
                throw new IllegalStateException("snapshot rebuild failed");
            return blue.getDocumentProcessor().snapshotManager()
                    .fromDocumentPreservingPaths(document, preservedPaths);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                Node document, Collection<String> preservedPaths) {
            if (blue == null) return canonicalSnapshot(document, preservedPaths);
            return blue.getDocumentProcessor().snapshotManager()
                    .fromDocumentTransientPreservingPaths(document, preservedPaths);
        }

        private ResolvedSnapshot canonicalSnapshot(
                Node document, Collection<String> preservedPaths) {
            fromDocumentCalls++;
            if (fromDocumentCalls == failFromDocumentOnCall) {
                throw new IllegalStateException("snapshot rebuild failed");
            }
            // Keep counting identity work without fabricating declaration evidence.
            try (BlueLanguage language = BlueLanguage.builder()
                    .nodeProvider(ProcessorTestSupport.providerWithTestContractTypes(
                            BlueRuntimeTypeRegistry.getDefault()
                                    .asProcessorSnapshotProvider())).build();
                 LanguageProcessing.Scope scope = language.processing().openScope()) {
                return scope.resolveTransientPreservingPaths(document, preservedPaths);
            }
        }

        @Override
        public ResolvedSnapshot fromCanonicalTransient(
                FrozenNode canonicalRoot,
                java.util.Collection<String> preservedPaths) {
            if (blue == null) {
                // Synthetic fixtures clone their lanes and never reinterpret list Source controls.
                return fromDocument(canonicalRoot.toNode());
            }
            fromDocumentCalls++;
            if (!canonicalIdentityResolution) fromDocumentInputs.add(canonicalRoot.toNode());
            if (fromDocumentCalls == failFromDocumentOnCall)
                throw new IllegalStateException("snapshot rebuild failed");
            return blue.getDocumentProcessor().snapshotManager()
                    .fromCanonicalTransient(canonicalRoot, preservedPaths);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            applyPatchCalls++;
            if (failApplyPatch) {
                throw new IllegalStateException("canonical overlay failed");
            }
            if (returnCurrentSnapshotOnApplyPatch) {
                return snapshot;
            }
            CanonicalPatchResult patched = new CanonicalOverlayPatchEngine(
                    snapshot.frozenCanonicalRoot()).apply(patch);
            Node resolved = patched.root().toNode();
            if (snapshot.canonicalTypeIdentities().hasCompleteCoverage()) {
                return ResolvedSnapshot.withCanonicalTypeIdentities(
                        patched.root(),
                        FrozenNode.fromResolvedNode(resolved),
                        snapshot.canonicalTypeIdentities());
            }
            return new ResolvedSnapshot(patched.root(),
                    FrozenNode.fromResolvedNode(resolved),
                    patched.blueId());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            cacheSnapshotCalls++;
            if (failCacheSnapshot) {
                throw new IllegalStateException("snapshot cache failed");
            }
            return snapshot;
        }
    }
    private static void permitGeneralization(Node document) {
        document.contracts(new Node().properties("generalization",
                new Node().type(new Node().blueId(RuntimeBlueIds.TYPE_GENERALIZATION_POLICY))
                        .properties("defaultMode", new Node().value("nearest-valid-ancestor"))));
    }

}
