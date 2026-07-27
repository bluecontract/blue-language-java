package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngineTest;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.TestEvent;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

import static blue.language.processor.DocumentProcessingResultTestSupport.resolvedDocument;
import static blue.language.processor.DocumentProcessingResultTestSupport.snapshot;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorSnapshotTransactionTest {

    @Test
    void runtimePatchUsesCanonicalOverlaySnapshotWhenNoGeneralizationIsNeeded() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue("x: 1\nother: keep", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2)));

        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(2, runtime.snapshot().canonicalRoot().getAsInteger("/x"));
        assertEquals("keep", runtime.snapshot().canonicalRoot().getAsText("/other"));
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void workingDocumentAppliesPatchWithoutMutatingRuntime() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue("x: 1\nother: keep", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        runtime.snapshot();

        WorkingDocument working = runtime.workingDocument("/");
        working.applyPatch(JsonPatch.replace("/x", new Node().value(2)));

        assertFalse(working.usedMaterializedFallback());
        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(1, runtime.snapshot().canonicalRoot().getAsInteger("/x"));
        assertEquals(2, working.materializeCanonicalRoot().getAsInteger("/x"));
        assertEquals("keep", working.canonicalAt("/other").getValue());
        assertSnapshotConsistent(working.snapshot());
    }

    @Test
    void workingDocumentMutablePatchAttributionUsesFixedCallerSource() {
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        Node document = YAML_MAPPER.readValue("x: 1\nother: keep", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, null, metrics);

        try (WorkingDocument externalWorking = runtime.workingDocument("/")) {
            externalWorking.applyPatch(JsonPatch.replace("/x", new Node().value(2)));
        }
        try (WorkingDocument processorWorking =
                     runtime.workingDocument("/", PatchSource.CUSTOM_PROCESSOR)) {
            processorWorking.applyPatch(JsonPatch.replace("/x", new Node().value(3)));
        }

        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(1L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.LEGACY_PUBLIC_API"), snapshot.toString());
        assertEquals(1L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.CUSTOM_PROCESSOR"), snapshot.toString());
    }

    @Test
    void precomputedWorkingDocumentPreviewCommitsWithoutReplanning() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue("x: 1\nother: keep", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        runtime.snapshot();
        JsonPatch patch = JsonPatch.replace("/x", new Node().value(2));

        WorkingDocument.Preview preview = runtime.workingDocument("/")
                .previewAndApplyPatches(Collections.singletonList(patch));
        List<DocumentProcessingRuntime.DocumentUpdateData> updates =
                runtime.applyPrecomputedPatch("/", patch, preview.patch(0));

        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(1, updates.size());
        assertEquals("/x", updates.get(0).path());
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(1, runtime.batchPatchCallsForTest());
        assertEquals(1, runtime.batchPatchEntriesForTest());
        assertEquals(0, runtime.batchPatchPlanningNanosForTest());
        assertEquals(0, runtime.batchPatchConformanceNanosForTest());
        assertTrue(runtime.batchPatchBuildUpdatesNanosForTest() > 0);
        assertTrue(runtime.batchPatchCommitNanosForTest() > 0);
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void workingDocumentRecordsMaterializedFallbackWhenRuntimeHasNoSnapshotManager() {
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null);

        WorkingDocument working = runtime.workingDocument("/");
        working.applyPatch(JsonPatch.replace("/x", new Node().value(2)));

        assertTrue(working.usedMaterializedFallback());
        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(2, working.commitToNode().getAsInteger("/x"));
        assertSnapshotConsistent(working.commitSnapshot());
    }

    @Test
    void workingDocumentPreviewFailureDoesNotMutateWorkingOrRuntime() {
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
        WorkingDocument working = runtime.workingDocument("/");

        assertThrows(RuntimeException.class,
                () -> working.applyPatch(JsonPatch.replace("/x", new Node().value(2))));

        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(1, working.materializeResolvedRoot().getAsInteger("/x"));
        assertEquals(nodeProvider.getBlueIdByName("Fixed One"),
                working.materializeResolvedRoot().getType().getBlueId());
    }

    @Test
    void workingDocumentRunsGeneralizationPolicyOnFrozenPreviewState() {
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
                        .type(new Node().blueId("8VeXb3GgP88WtosVLu2mamHmbvY8f5cxA9z6yAETbbFz"))
                        .properties("rules", new Node().items(java.util.Collections.singletonList(
                                new Node().properties("path", new Node().value("/price"),
                                        "mode", new Node().value("nearest-valid-ancestor"),
                                        "mustRemainSubtypeOf", new Node().blueId(nodeProvider.getBlueIdByName("Price"))))))));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document,
                blue.conformanceEngine(),
                new CountingSnapshotManager(blue));

        WorkingDocument working = runtime.workingDocument("/")
                .applyPatch(JsonPatch.replace("/price/currency", new Node().value("USD")));

        assertEquals("EUR", document.getAsText("/price/currency"));
        assertEquals("USD", working.resolvedAt("/price/currency").getValue());
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                working.resolvedAt("/price").getType().getReferenceBlueId());
    }

    @Test
    void runtimeReadsUseResolvedSnapshotIndexWhenSnapshotIsAvailable() {
        Node canonical = YAML_MAPPER.readValue("local: yes", Node.class);
        Node resolved = YAML_MAPPER.readValue("local: yes\ninherited: from-type", Node.class);
        CountingSnapshotManager manager = new CountingSnapshotManager(canonical, resolved);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(canonical.clone(), null, manager);

        runtime.snapshot();

        assertEquals("from-type", runtime.nodeAt("/inherited").getValue());
        assertTrue(runtime.contains("/inherited"));
        assertEquals(1, manager.fromDocumentCalls);
    }

    @Test
    void resolvedNodeReadKeepsMutableViewCanonicalWhileUsingSnapshotIndex() {
        Node canonical = YAML_MAPPER.readValue("local: yes", Node.class);
        Node resolved = YAML_MAPPER.readValue("local: yes\ninherited: from-type", Node.class);
        CountingSnapshotManager manager = new CountingSnapshotManager(canonical, resolved);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(canonical.clone(), null, manager);

        assertEquals("from-type", runtime.resolvedNodeAt("/inherited").getValue());

        assertMissing(runtime.document(), "/inherited");
        assertEquals(1, manager.fromDocumentCalls);
    }

    @Test
    void snapshotPlanIsAuthoritativeAfterImmutablePlanning() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.returnCurrentSnapshotOnApplyPatch = true;
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2)));

        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(2, runtime.snapshot().resolvedRoot().getAsInteger("/x"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void runtimeSnapshotTracksMixedAddReplaceRemoveAndArrayAppendPatches() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue(
                "profile:\n" +
                "  label: Ana\n" +
                "tags:\n" +
                "  - old\n" +
                "obsolete: true", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        runtime.applyPatch("/", JsonPatch.add("/profile/location/city", new Node().value("Warsaw")));
        runtime.applyPatch("/", JsonPatch.replace("/profile/label", new Node().value("Anna")));
        runtime.applyPatch("/", JsonPatch.add("/tags/-", new Node().value("new")));
        runtime.applyPatch("/", JsonPatch.remove("/obsolete"));

        Node canonical = runtime.snapshot().canonicalRoot();
        assertEquals("Warsaw", canonical.getAsText("/profile/location/city"));
        assertEquals("Anna", canonical.getAsText("/profile/label"));
        assertEquals("old", canonical.getAsText("/tags/0"));
        assertEquals("new", canonical.getAsText("/tags/1"));
        assertMissing(canonical, "/obsolete");
        assertEquals(4, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(4, manager.cacheSnapshotCalls);
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void runtimeRebuildsSnapshotFromGeneralizedDocumentWhenConformanceChangesTypes() {
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
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine(), manager);

        runtime.applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals("Price", runtime.snapshot().resolvedRoot().getAsNode("/price/type").getName());
        assertEquals("Global Product", runtime.snapshot().resolvedRoot().getType().getName());
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void immutableConformancePlanningDoesNotMutatePreviousSnapshotRoots() {
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
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine(), manager);
        ResolvedSnapshot before = runtime.snapshot();
        FrozenNode beforeResolvedRoot = before.frozenResolvedRoot();

        runtime.applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        assertEquals("EUR", beforeResolvedRoot.at("/price/currency").getValue());
        assertEquals("Price in EUR", beforeResolvedRoot.property("price").getType().getName());
        assertEquals("European Product", beforeResolvedRoot.getType().getName());
        assertEquals("USD", runtime.snapshot().resolvedAt("/price/currency").getValue());
        assertEquals("Price", runtime.snapshot().frozenResolvedRoot().property("price").getType().getName());
        assertEquals("Global Product", runtime.snapshot().frozenResolvedRoot().getType().getName());
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void failedImmutableConformancePlanDoesNotPatchOrRebuildSnapshot() {
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

        assertThrows(IllegalArgumentException.class,
                () -> runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2))));

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
    void updateMetadataUsesResolvedSnapshotIndexesForInheritedValues() {
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
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine(), manager);

        DocumentProcessingRuntime.DocumentUpdateData data =
                runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(1)));

        assertEquals(0, ((BigInteger) data.before().getValue()).intValue());
        assertEquals(1, ((BigInteger) data.after().getValue()).intValue());
        assertEquals("Counter", runtime.snapshot().resolvedRoot().getType().getName());
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void directWriteKeepsCanonicalSnapshotInTheSameRuntimeTransaction() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        runtime.directWrite("/checkpoint/lastEvent", new Node().value("evt-1"));
        runtime.directWrite("/checkpoint/lastEvent", null);

        assertMissing(document, "/checkpoint/lastEvent");
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(2, manager.cacheSnapshotCalls);
        assertMissing(runtime.snapshot().canonicalRoot(), "/checkpoint/lastEvent");
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void runtimePatchCommitsBatchSnapshotWithoutSnapshotPatchManagerFallback() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failApplyPatch = true;
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2)));

        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(2, runtime.snapshot().canonicalRoot().getAsInteger("/x"));
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void batchSnapshotCacheFailureRollsBackDocumentAndSnapshotTogether() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failCacheSnapshot = true;
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2))));

        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
    }

    @Test
    void directWriteSnapshotFailureRollsBackDocumentAndSnapshotTogether() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failFromDocumentOnCall = 2;
        Node document = YAML_MAPPER.readValue("checkpoint:\n  lastEvent: evt-0", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        ResolvedSnapshot before = runtime.snapshot();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.directWrite("/checkpoint/lastEvent", new Node().value("evt-1")));

        assertEquals("snapshot rebuild failed", failure.getMessage());
        assertEquals("evt-0", document.getAsText("/checkpoint/lastEvent"));
        assertEquals(before.blueId(), runtime.snapshot().blueId());
        assertEquals("evt-0", runtime.snapshot().canonicalRoot().getAsText("/checkpoint/lastEvent"));
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(0, manager.cacheSnapshotCalls);
    }

    @Test
    void failedImmutablePatchPlanDoesNotTouchExistingRuntimeSnapshot() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        Node document = YAML_MAPPER.readValue("rows:\n  items:\n    - a", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        ResolvedSnapshot before = runtime.snapshot();

        assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.remove("/rows/5")));

        assertEquals("a", document.getAsText("/rows/0"));
        assertEquals(before.blueId(), runtime.snapshot().blueId());
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
    }

    @Test
    void invalidImmutablePatchPlanDoesNotCallSnapshotPatchManager() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.returnCurrentSnapshotOnApplyPatch = true;
        Node document = YAML_MAPPER.readValue("rows:\n  items:\n    - a", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        ResolvedSnapshot before = runtime.snapshot();

        assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.remove("/rows/5")));

        assertEquals("a", document.getAsText("/rows/0"));
        assertEquals(before.blueId(), runtime.snapshot().blueId());
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
    }

    @Test
    void processorResultCarriesCanonicalRuntimeDocumentWithoutBluePostProcessing() {
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
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 7\n", Node.class);

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        Node event = new TestEvent()
                .eventId("evt-runtime-snapshot")
                .toNode();
        ProcessingDebugResult processedDebug =
                processor.processDocumentWithTrace(
                        initialized.document().clone(),
                        event);
        DocumentProcessingResult processed = processedDebug.processResult();

        assertNotNull(processedDebug.resultingSnapshot());
        assertEquals(
                processedDebug.resultingSnapshot().blueId(),
                BlueIdCalculator.calculateBlueId(processed.document()));
        assertEquals(7, processed.document().getAsInteger("/x"));
        assertNotNull(processed.document().getAsText(
                "/contracts/checkpoint/entries/testChannel/domain/blueId"));
        assertEquals(BlueIdCalculator.calculateBlueId(event),
                processed.document().getAsText(
                        "/contracts/checkpoint/entries/testChannel/subject/blueId"));
        assertTrue(manager.cacheSnapshotCalls >= 2);
        assertSnapshotConsistent(processedDebug.resultingSnapshot());
    }

    @Test
    void snapshotNativeProcessingRebuildsOnlyWritesThatRequireResolution() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessor processor =
                DocumentProcessorExactFeederSupport.processor(
                        manager,
                        new SetPropertyContractProcessor());
        Node initialized = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: 5qrHeD39ytiuWtKXStznJHTjDfgAtiPAr3jwHibvQKvR\n" +
                "    documentId: doc-1\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 9\n", Node.class);
        FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(initialized);
        ResolvedSnapshot snapshot = new ResolvedSnapshot(canonical,
                FrozenNode.fromResolvedNode(initialized),
                canonical.blueId());

        ProcessingDebugResult debug = processor.processDocumentWithTrace(
                snapshot,
                new TestEvent().eventId("evt-snapshot-native").toNode());
        DocumentProcessingResult result = debug.processResult();

        assertTrue(manager.fromDocumentCalls >= 2,
                "feeder verification and scalar writes must use coherent immutable snapshots");
        assertTrue(manager.fromDocumentInputs.stream()
                .allMatch(node -> node.getContracts() != null),
                "writes requiring resolution must retain the complete canonical companion");
        assertTrue(manager.cacheSnapshotCalls > 0);
        assertEquals(9, result.document().getAsInteger("/x"));
        assertSnapshotConsistent(debug.resultingSnapshot());
    }

    @Test
    void blueSnapshotNativeProcessingMatchesNodeBasedGasAndResult() {
        Node document = YAML_MAPPER.readValue(
                "name: Runtime Snapshot Parity\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
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

        DocumentProcessingResult nodeInitialized = nodeProcessor.initializeDocument(document.clone());
        DocumentProcessingResult snapshotInitialized = snapshotProcessor.initializeDocument(inputSnapshot);

        assertEquals(nodeInitialized.totalGas(), snapshotInitialized.totalGas());
        assertEquals(
                BlueIdCalculator.calculateBlueId(nodeInitialized.document()),
                BlueIdCalculator.calculateBlueId(snapshotInitialized.document()));

        Node event = new TestEvent().eventId("evt-parity").toNode();
        DocumentProcessingResult nodeProcessed = nodeProcessor.processDocument(nodeInitialized.document().clone(), event.clone());
        DocumentProcessingResult snapshotProcessed = snapshotProcessor.processDocument(
                uncheckedSnapshot(snapshotInitialized.document()),
                event.clone());

        assertEquals(nodeProcessed.totalGas(), snapshotProcessed.totalGas());
        assertEquals(
                BlueIdCalculator.calculateBlueId(nodeProcessed.document()),
                BlueIdCalculator.calculateBlueId(snapshotProcessed.document()));
        assertEquals(7, snapshotProcessed.document().getAsInteger("/x"));
        String expectedSubject =
                BlueIdCalculator.calculateBlueId(event);
        String nodeDomain = nodeProcessed.document().getAsText(
                "/contracts/checkpoint/entries/testChannel/domain/blueId");
        String snapshotDomain = snapshotProcessed.document().getAsText(
                "/contracts/checkpoint/entries/testChannel/domain/blueId");
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
    void snapshotNativeProcessingReusesInputFrozenTypeGraph() {
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

        DocumentProcessingResult initialized = blue.initializeDocument(input);
        ResolvedSnapshot initializedSnapshot = snapshot(blue, initialized);

        assertSame(input.frozenResolvedRoot().getType(), initializedSnapshot.frozenResolvedRoot().getType());
        assertEquals("Typed Runtime Root", initializedSnapshot.frozenResolvedRoot().getType().getName());
        assertSnapshotConsistent(initializedSnapshot);
    }

    @Test
    void executionContextReadsUseResolvedSnapshotIndexWhenSnapshotIsAvailable() {
        Node canonical = YAML_MAPPER.readValue("local: yes", Node.class);
        Node resolved = YAML_MAPPER.readValue("local: yes\ninherited: from-type", Node.class);
        CountingSnapshotManager manager = new CountingSnapshotManager(canonical, resolved);
        DocumentProcessor processor = new DocumentProcessor(null, manager);
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, canonical.clone());
        execution.preflightScope("/");
        execution.runtime().snapshot();
        ProcessorExecutionContext context = execution.createContext("/",
                execution.bundleForScope("/"),
                new Node(),
                false);

        assertEquals("from-type", context.documentAt("/inherited").getValue());
        assertTrue(context.documentContains("/inherited"));
        assertEquals(1, manager.fromDocumentCalls);
    }

    @Test
    void processorPatchToInheritedValueOmitsDerivableCanonicalOverride() {
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
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    path: /balance\n" +
                "    propertyKey: cents\n" +
                "    propertyValue: 0\n", Node.class);
        DocumentProcessingResult initialized = blue.initializeDocument(document);
        ResolvedSnapshot initializedSnapshot = snapshot(blue, initialized);
        assertNull(initializedSnapshot.canonicalAt("/balance/cents"));

        DocumentProcessingResult processed = blue.processDocument(initializedSnapshot,
                blue.objectToNode(new TestEvent().eventId("evt-inherited")));
        ResolvedSnapshot processedSnapshot = snapshot(blue, processed);

        assertEquals(0, resolvedDocument(blue, processed).getAsInteger("/balance/cents"));
        assertNull(processedSnapshot.canonicalAt("/balance/cents"));
        assertSnapshotConsistent(processedSnapshot);
    }

    @Test
    void inheritedEffectiveContractsParticipateWithoutMaterializingOverrides() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Event Driven Type\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
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

        DocumentProcessingResult initialized = blue.initializeDocument(document);
        DocumentProcessingResult processed = blue.processDocument(snapshot(blue, initialized),
                new TestEvent().eventId("evt-inherited-contract").toNode());

        assertEquals(42, resolvedDocument(blue, processed).getAsInteger("/x"));
        assertEquals(42, processed.document().getAsInteger("/x"));
        assertMissing(processed.document(), "/contracts/testChannel");
        assertMissing(processed.document(), "/contracts/setter");
        assertEquals("Event Driven Type", resolvedDocument(blue, processed).getType().getName());
        assertSnapshotConsistent(snapshot(blue, processed));
    }

    @Test
    void selectedTypeOnlyContractUsesInheritedEffectiveFields() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Event Driven Type\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
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
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setter:\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n", Node.class);

        DocumentProcessingResult initialized = blue.initializeDocument(document);
        DocumentProcessingResult processed = blue.processDocument(snapshot(blue, initialized),
                new TestEvent().eventId("evt-selected-contract").toNode());

        assertEquals(42, resolvedDocument(blue, processed).getAsInteger("/x"));
        assertEquals(42, processed.document().getAsInteger("/x"));
        assertMissing(processed.document(), "/contracts/setter/channel");
        assertMissing(processed.document(), "/contracts/setter/propertyKey");
        assertMissing(processed.document(), "/contracts/setter/propertyValue");
        Node resolved = resolvedDocument(blue, processed);
        assertEquals("testChannel", resolved.getAsText("/contracts/setter/channel"));
        assertEquals("/x", resolved.getAsText("/contracts/setter/propertyKey"));
        assertEquals(42, resolved.getAsInteger("/contracts/setter/propertyValue"));
        assertSnapshotConsistent(snapshot(blue, processed));
    }

    private static void assertMissing(Node node, String path) {
        assertThrows(IllegalArgumentException.class, () -> node.getAsNode(path));
    }

    private static Node canonicalRoot(
            Blue blue,
            Node source) {
        return source;
    }

    private static void assertSnapshotConsistent(ResolvedSnapshot snapshot) {
        assertEquals(BlueIdCalculator.calculateUncheckedBlueId(snapshot.canonicalRoot()), snapshot.blueId());
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
            fromDocumentInputs.add(document.clone());
            if (fromDocumentCalls == failFromDocumentOnCall) {
                throw new IllegalStateException("snapshot rebuild failed");
            }
            if (blue != null) {
                return blue.resolveToSnapshot(document);
            }
            Node canonicalSource = canonical != null ? canonical.clone() : document.clone();
            Node resolvedSource = resolved != null ? resolved.clone() : document.clone();
            FrozenNode canonicalRoot = FrozenNode.fromUncheckedCanonicalNode(canonicalSource);
            return new ResolvedSnapshot(canonicalRoot,
                    FrozenNode.fromResolvedNode(resolvedSource),
                    canonicalRoot.blueId());
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
            CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
            Node resolved = patched.root().toNode();
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
}
