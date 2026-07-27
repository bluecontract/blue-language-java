package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessingRuntimeBatchPatchTest {

    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void applyPatchesAppliesMultipleObjectPatchesAndCommitsOnce() {
        Node document = new Node();
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/a", new Node().value("one")),
                JsonPatch.add("/b", new Node().value("two")),
                JsonPatch.replace("/a", new Node().value("three"))
        );

        List<DocumentProcessingRuntime.DocumentUpdateData> updates = runtime.applyPatches("/", patches);

        assertEquals(3, updates.size());
        assertEquals("three", document.getAsText("/a"));
        assertEquals("two", document.getAsText("/b"));
        assertEquals("one", updates.get(0).after().getValue());
        assertEquals("two", updates.get(1).after().getValue());
        assertEquals("three", updates.get(2).after().getValue());
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(1, runtime.batchPatchCallsForTest());
        assertEquals(3, runtime.batchPatchEntriesForTest());
        assertEquals(0, runtime.batchPatchRollbackCopiesForTest());
    }

    @Test
    void duplicatePatchPathsPreserveUpdateOrder() {
        Node document = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        List<DocumentProcessingRuntime.DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/status", new Node().value("first")),
                JsonPatch.replace("/status", new Node().value("second"))
        ));

        assertEquals("second", document.getAsText("/status"));
        assertEquals("idle", updates.get(0).before().getValue());
        assertEquals("first", updates.get(0).after().getValue());
        assertEquals("first", updates.get(1).before().getValue());
        assertEquals("second", updates.get(1).after().getValue());
    }

    @Test
    void batchRollsBackWhenLaterPatchFails() {
        Node document = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        assertThrows(IllegalStateException.class, () -> runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/status", new Node().value("active")),
                JsonPatch.remove("/missing")
        )));

        assertEquals("idle", document.getAsText("/status"));
        assertNull(document.getProperties().get("missing"));
        assertEquals(0, runtime.batchPatchRollbackCopiesForTest());
    }

    @Test
    void atomicBatchRejectsCyclicMemberTraversalBeforeSnapshotProviderDemand() {
        Node document = new Node().properties(
                "cyclic",
                new Node().blueId(CYCLIC_MEMBER_BLUE_ID));
        String exactInput = document.toString();
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> runtime.applyPatches(
                        "/",
                        Collections.singletonList(
                                JsonPatch.add(
                                        "/cyclic/member",
                                        new Node().value(1)))));

        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                failure.errorCategory());
        assertEquals(exactInput, document.toString());
        assertEquals(0, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(0, manager.cacheSnapshotCalls);
    }

    @Test
    void directWriteRejectsCyclicMemberTraversalBeforeSnapshotProviderDemand() {
        Node document = new Node().properties(
                "cyclic",
                new Node().blueId(CYCLIC_MEMBER_BLUE_ID));
        String exactInput = document.toString();
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> runtime.directWrite(
                        "/cyclic/member",
                        new Node().value(1)));

        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                failure.errorCategory());
        assertEquals(exactInput, document.toString());
        assertEquals(0, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(0, manager.cacheSnapshotCalls);
    }

    @Test
    void intrinsicCyclicMemberTraversalFailsBeforeSnapshotProviderDemand() {
        for (boolean listPayload : Arrays.asList(false, true)) {
            for (String field : Arrays.asList(
                    "type",
                    "itemType",
                    "keyType",
                    "valueType",
                    "blue",
                    "contracts")) {
                Node intrinsic = nodeWithIntrinsicCyclicReference(field);
                Node document;
                String path;
                if (listPayload) {
                    intrinsic.items(new Node().value("retained item"));
                    document = new Node().properties("list", intrinsic);
                    path = "/list/" + field + "/member";
                } else {
                    document = intrinsic;
                    path = "/" + field + "/member";
                }
                String exactInput = document.toString();
                CountingSnapshotManager manager =
                        new CountingSnapshotManager();
                DocumentProcessingRuntime runtime =
                        new DocumentProcessingRuntime(document, null, manager);

                ProcessorFailureException failure = assertThrows(
                        ProcessorFailureException.class,
                        () -> runtime.applyPatches(
                                "/",
                                Collections.singletonList(
                                        JsonPatch.add(
                                                path,
                                                new Node().value(1)))),
                        field + ", listPayload=" + listPayload);

                assertEquals(
                        ProcessorErrorCategory.CyclicSetMutationUnsupported,
                        failure.errorCategory(),
                        field);
                assertEquals(exactInput, document.toString(), field);
                assertEquals(0, manager.fromDocumentCalls, field);
                assertEquals(0, manager.applyPatchCalls, field);
                assertEquals(0, manager.cacheSnapshotCalls, field);
            }
        }
    }

    @Test
    void atomicBatchPreflightTracksWholeReferenceReplacementBeforeDescendantPatch() {
        Node document = new Node().properties(
                "cyclic",
                new Node().blueId(CYCLIC_MEMBER_BLUE_ID));
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);

        runtime.applyPatches(
                "/",
                Arrays.asList(
                        JsonPatch.replace(
                                "/cyclic",
                                new Node().properties(
                                        "member",
                                        new Node().value("replacement"))),
                        JsonPatch.add(
                                "/cyclic/next",
                                new Node().value("allowed"))));

        assertEquals("replacement", document.getAsText("/cyclic/member"));
        assertEquals("allowed", document.getAsText("/cyclic/next"));
    }

    private Node nodeWithIntrinsicCyclicReference(String field) {
        Node root = new Node();
        Node reference = new Node().blueId(CYCLIC_MEMBER_BLUE_ID);
        if ("type".equals(field)) {
            return root.type(reference);
        }
        if ("itemType".equals(field)) {
            return root.itemType(reference);
        }
        if ("keyType".equals(field)) {
            return root.keyType(reference);
        }
        if ("valueType".equals(field)) {
            return root.valueType(reference);
        }
        if ("blue".equals(field)) {
            return root.blue(reference);
        }
        if ("contracts".equals(field)) {
            return root.contracts(reference);
        }
        throw new IllegalArgumentException("Unsupported intrinsic field: " + field);
    }

    @Test
    void atomicBatchPreflightTracksIntroducedReferenceBeforeDescendantPatch() {
        Node document = new Node();
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> runtime.applyPatches(
                        "/",
                        Arrays.asList(
                                JsonPatch.add(
                                        "/cyclic",
                                        new Node().blueId(CYCLIC_MEMBER_BLUE_ID)),
                                JsonPatch.add(
                                        "/cyclic/member",
                                        new Node().value("forbidden")))));

        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                failure.errorCategory());
        assertNull(document.getProperties());
        assertEquals(0, manager.fromDocumentCalls);
    }

    @Test
    void batchFailureDuringCommitLeavesDocumentUnchanged() {
        Node document = new Node().properties("status", new Node().value("idle"));
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failCacheSnapshot = true;
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        assertThrows(IllegalStateException.class, () -> runtime.applyPatches("/", Collections.singletonList(
                JsonPatch.replace("/status", new Node().value("active"))
        )));

        assertEquals("idle", document.getAsText("/status"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(0, runtime.batchPatchRollbackCopiesForTest());
    }

    @Test
    void batchArrayPatchesMatchSequentialArrayPatches() {
        Node batchDoc = arrayDocument("values", 1, 2, 3);
        Node sequentialDoc = arrayDocument("values", 1, 2, 3);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/values/1", new Node().value(99)),
                JsonPatch.replace("/values/2", new Node().value(100)),
                JsonPatch.remove("/values/0")
        );

        new DocumentProcessingRuntime(batchDoc).applyPatches("/", patches);
        DocumentProcessingRuntime sequential = new DocumentProcessingRuntime(sequentialDoc);
        for (JsonPatch patch : patches) {
            sequential.applyPatch("/", patch);
        }

        assertEquals(Arrays.asList(99, 100, 3), integerValues(batchDoc, "/values"));
        assertEquals(integerValues(sequentialDoc, "/values"), integerValues(batchDoc, "/values"));
    }

    @Test
    void applyPatchDelegatesToApplyPatchesSemantics() {
        Node one = new Node();
        Node two = new Node();

        new DocumentProcessingRuntime(one).applyPatch("/", JsonPatch.add("/x", new Node().value(1)));
        new DocumentProcessingRuntime(two).applyPatches("/", Collections.singletonList(
                JsonPatch.add("/x", new Node().value(1))
        ));

        assertEquals(one.getAsInteger("/x"), two.getAsInteger("/x"));
    }

    @Test
    void addRemoveAndRemoveAddSamePathPreserveOrderedUpdates() {
        Node document = new Node().properties("temp", new Node().value("old"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        List<DocumentProcessingRuntime.DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                JsonPatch.remove("/temp"),
                JsonPatch.add("/temp", new Node().value("new")),
                JsonPatch.add("/scratch", new Node().value("value")),
                JsonPatch.remove("/scratch")
        ));

        assertEquals("new", document.getAsText("/temp"));
        assertEquals("old", updates.get(0).before().getValue());
        assertNull(updates.get(0).after());
        assertNull(updates.get(1).before());
        assertEquals("new", updates.get(1).after().getValue());
        assertNull(updates.get(2).before());
        assertEquals("value", updates.get(2).after().getValue());
        assertEquals("value", updates.get(3).before().getValue());
        assertNull(updates.get(3).after());
        assertThrows(IllegalArgumentException.class, () -> document.getAsNode("/scratch"));
    }

    @Test
    void updateDataMaterializesBeforeAndAfterLazily() {
        Node document = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        List<DocumentProcessingRuntime.DocumentUpdateData> updates = runtime.applyPatches("/", Collections.singletonList(
                JsonPatch.replace("/status", new Node().value("active"))
        ));

        assertEquals(0, runtime.documentUpdateBeforeNodeMaterializationsForTest());
        assertEquals(0, runtime.documentUpdateAfterNodeMaterializationsForTest());

        assertEquals("idle", updates.get(0).before().getValue());
        assertEquals("active", updates.get(0).after().getValue());
        assertEquals("idle", updates.get(0).before().getValue());
        assertEquals("active", updates.get(0).after().getValue());

        assertEquals(1, runtime.documentUpdateBeforeNodeMaterializationsForTest());
        assertEquals(1, runtime.documentUpdateAfterNodeMaterializationsForTest());
    }

    @Test
    void inheritedParentThenChildPatchDoesNotMinimizeMidBatch() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Has Inherited List\n" +
                "a:\n" +
                "  - inherited");
        Blue blue = ProcessorTestSupport.blue(provider);
        Node canonical = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + provider.getBlueIdByName("Has Inherited List") + "\n", Node.class);
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(canonical);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(snapshot, null, new PassthroughSnapshotManager());

        Node inheritedList = new Node().items(Collections.singletonList(new Node().value("inherited")));
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/a", inheritedList),
                JsonPatch.add("/a/-", new Node().value("custom"))
        ));

        assertEquals("inherited", runtime.snapshot().canonicalRoot().getAsText("/a/0"));
        assertEquals("custom", runtime.snapshot().canonicalRoot().getAsText("/a/1"));
    }

    @Test
    void sameInheritedPathCanBeChangedAgainInSameBatch() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Has Inherited Status\n" +
                "status: idle");
        Blue blue = ProcessorTestSupport.blue(provider);
        Node canonical = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + provider.getBlueIdByName("Has Inherited Status") + "\n", Node.class);
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(canonical);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(snapshot, null, new PassthroughSnapshotManager());

        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/status", new Node().value("idle")),
                JsonPatch.replace("/status", new Node().value("custom"))
        ));

        assertEquals("custom", runtime.snapshot().canonicalRoot().getAsText("/status"));
    }

    @Test
    void escapedPointerKeysWorkInBatch() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.add("/tilde/a~1b", new Node().value("slash")),
                JsonPatch.add("/tilde/a~0b", new Node().value("tilde")),
                JsonPatch.add("/tilde/~01key", new Node().value("literal"))
        ));

        assertEquals("slash", document.getAsText("/tilde/a~1b"));
        assertEquals("tilde", document.getAsText("/tilde/a~0b"));
        assertEquals("literal", document.getAsText("/tilde/~01key"));
    }

    @Test
    void batchPatchAvoidsRepeatedSnapshotCommitCost() {
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);
        List<JsonPatch> patches = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            patches.add(JsonPatch.add("/values/k" + i, new Node().value(i)));
        }

        long start = System.nanoTime();
        runtime.applyPatches("/", patches);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(100, document.getAsNode("/values").getProperties().size());
        assertTrue(elapsedMs < 1000, "Batch patching should not be catastrophically slow; elapsedMs=" + elapsedMs);
        assertEquals(1, runtime.batchPatchCallsForTest());
        assertEquals(100, runtime.batchPatchEntriesForTest());
        assertEquals(0, runtime.batchPatchRollbackCopiesForTest());
        assertTrue(runtime.batchPatchPlanningNanosForTest() > 0);
        assertTrue(runtime.batchPatchBuildUpdatesNanosForTest() > 0);
        assertTrue(runtime.batchPatchCommitNanosForTest() > 0);
        System.out.printf("batchPatchEntries=%d planningMs=%d conformanceMs=%d buildUpdatesMs=%d commitMs=%d beforeAfterMaterializations=%d/%d%n",
                runtime.batchPatchEntriesForTest(),
                TimeUnit.NANOSECONDS.toMillis(runtime.batchPatchPlanningNanosForTest()),
                TimeUnit.NANOSECONDS.toMillis(runtime.batchPatchConformanceNanosForTest()),
                TimeUnit.NANOSECONDS.toMillis(runtime.batchPatchBuildUpdatesNanosForTest()),
                TimeUnit.NANOSECONDS.toMillis(runtime.batchPatchCommitNanosForTest()),
                runtime.documentUpdateBeforeNodeMaterializationsForTest(),
                runtime.documentUpdateAfterNodeMaterializationsForTest());
    }

    private List<Integer> integerValues(Node document, String path) {
        List<Integer> values = new ArrayList<>();
        for (Node item : document.getAsNode(path).getItems()) {
            Object value = item.getValue();
            assertTrue(value instanceof BigInteger);
            values.add(((BigInteger) value).intValue());
        }
        return values;
    }

    private Node arrayDocument(String key, Object... entries) {
        List<Node> items = new ArrayList<>();
        for (Object entry : entries) {
            items.add(new Node().value(entry));
        }
        Node arrayNode = new Node().items(items);
        return new Node().properties(key, arrayNode);
    }

    private static final class CountingSnapshotManager implements ProcessingSnapshotManager {
        private int fromDocumentCalls;
        private int applyPatchCalls;
        private int cacheSnapshotCalls;
        private boolean failCacheSnapshot;

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            fromDocumentCalls++;
            FrozenNode root = FrozenNode.fromNode(document.clone());
            return new ResolvedSnapshot(root, FrozenNode.fromResolvedNode(document.clone()), root.blueId());
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
            if (failCacheSnapshot) {
                throw new IllegalStateException("snapshot cache failed");
            }
            return snapshot;
        }
    }

    private static final class PassthroughSnapshotManager implements ProcessingSnapshotManager {
        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            FrozenNode root = FrozenNode.fromNode(document.clone());
            return new ResolvedSnapshot(root, FrozenNode.fromResolvedNode(document.clone()), root.blueId());
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
            return new ResolvedSnapshot(patched.root(),
                    FrozenNode.fromResolvedNode(patched.root().toNode()),
                    patched.blueId());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return snapshot;
        }
    }
}
