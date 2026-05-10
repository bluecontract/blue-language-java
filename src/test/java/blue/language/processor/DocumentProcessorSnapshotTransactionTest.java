package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngineTest;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.TestEvent;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals(1, manager.applyPatchCalls);
        assertEquals(2, runtime.snapshot().canonicalRoot().getAsInteger("/x"));
        assertEquals("keep", runtime.snapshot().canonicalRoot().getAsText("/other"));
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
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(4, manager.applyPatchCalls);
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void runtimeRebuildsSnapshotFromGeneralizedDocumentWhenConformanceChangesTypes() {
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = new Blue(nodeProvider);
        CountingSnapshotManager manager = new CountingSnapshotManager(blue);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine(), manager);

        runtime.applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        assertEquals("Price", document.getAsNode("/price/type").getName());
        assertEquals("Global Product", document.getType().getName());
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals("Price", runtime.snapshot().resolvedRoot().getAsNode("/price/type").getName());
        assertEquals("Global Product", runtime.snapshot().resolvedRoot().getType().getName());
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
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(2, manager.applyPatchCalls);
        assertMissing(runtime.snapshot().canonicalRoot(), "/checkpoint/lastEvent");
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void canonicalOverlayFailureFallsBackToFullSnapshotRebuildWithoutChangingGasPath() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failApplyPatch = true;
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2)));

        assertEquals(2, document.getAsInteger("/x"));
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(1, manager.applyPatchCalls);
        assertEquals(2, runtime.snapshot().canonicalRoot().getAsInteger("/x"));
        assertSnapshotConsistent(runtime.snapshot());
    }

    @Test
    void snapshotRebuildFailureRollsBackDocumentAndSnapshotTogether() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failApplyPatch = true;
        manager.failFromDocumentOnCall = 2;
        Node document = YAML_MAPPER.readValue("x: 1", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2))));

        assertEquals(1, document.getAsInteger("/x"));
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(1, manager.applyPatchCalls);
    }

    @Test
    void directWriteSnapshotFailureRollsBackDocumentAndSnapshotTogether() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failApplyPatch = true;
        manager.failFromDocumentOnCall = 2;
        Node document = YAML_MAPPER.readValue("checkpoint:\n  lastEvent: evt-0", Node.class);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        assertThrows(IllegalStateException.class,
                () -> runtime.directWrite("/checkpoint/lastEvent", new Node().value("evt-1")));

        assertEquals("evt-0", document.getAsText("/checkpoint/lastEvent"));
        assertEquals(2, manager.fromDocumentCalls);
        assertEquals(1, manager.applyPatchCalls);
    }

    @Test
    void failedMutablePatchDoesNotTouchExistingRuntimeSnapshot() {
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
    void processorResultCarriesRuntimeSnapshotWithoutBluePostProcessing() {
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessor processor = new DocumentProcessor(null, manager)
                .registerContractProcessor(new TestEventChannelProcessor())
                .registerContractProcessor(new SetPropertyContractProcessor());
        Node document = YAML_MAPPER.readValue(
                "name: Runtime Snapshot\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 7\n", Node.class);

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        DocumentProcessingResult processed = processor.processDocument(initialized.document().clone(),
                new TestEvent().eventId("evt-runtime-snapshot").toNode());

        assertNotNull(initialized.snapshot());
        assertNotNull(processed.snapshot());
        assertEquals(processed.snapshot().blueId(), processed.blueId());
        assertEquals(7, processed.canonicalDocument().getAsInteger("/x"));
        assertEquals("evt-runtime-snapshot",
                processed.canonicalDocument().getAsText("/contracts/checkpoint/lastSignatures/testChannel"));
        assertEquals("evt-runtime-snapshot",
                processed.canonicalDocument().getAsText("/contracts/checkpoint/lastEvents/testChannel/eventId"));
        assertTrue(manager.applyPatchCalls >= 2);
        assertSnapshotConsistent(processed.snapshot());
    }

    private static void assertMissing(Node node, String path) {
        assertThrows(IllegalArgumentException.class, () -> node.getAsNode(path));
    }

    private static void assertSnapshotConsistent(ResolvedSnapshot snapshot) {
        assertEquals(BlueIdCalculator.calculateBlueId(snapshot.canonicalRoot()), snapshot.blueId());
    }

    private static final class CountingSnapshotManager implements ProcessingSnapshotManager {
        private final Blue blue;
        private int fromDocumentCalls;
        private int applyPatchCalls;
        private boolean failApplyPatch;
        private int failFromDocumentOnCall;

        private CountingSnapshotManager() {
            this.blue = null;
        }

        private CountingSnapshotManager(Blue blue) {
            this.blue = blue;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            fromDocumentCalls++;
            if (fromDocumentCalls == failFromDocumentOnCall) {
                throw new IllegalStateException("snapshot rebuild failed");
            }
            if (blue != null) {
                return blue.resolveToSnapshot(document);
            }
            FrozenNode canonicalRoot = FrozenNode.fromNode(document.clone());
            return new ResolvedSnapshot(canonicalRoot,
                    FrozenNode.fromResolvedNode(document.clone()),
                    canonicalRoot.blueId());
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            applyPatchCalls++;
            if (failApplyPatch) {
                throw new IllegalStateException("canonical overlay failed");
            }
            CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
            Node resolved = patched.root().toNode();
            return new ResolvedSnapshot(patched.root(),
                    FrozenNode.fromResolvedNode(resolved),
                    patched.blueId());
        }
    }
}
