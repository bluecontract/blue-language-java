package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessingRuntimeBatchPatchTest {

    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void shouldRejectLowLevelMaterializedRootRemoval() {
        // given
        Node document = new Node().properties(
                "status", new Node().value("retained"));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document);

        // when
        Throwable failure = captureFailure(
                () -> new MutationCommit(runtime)
                        .publishSelected(
                                "/",
                                null,
                                document.clone()));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals(
                "Direct materialized writes cannot remove the root document",
                failure.getMessage());
        assertEquals("retained", document.getAsText("/status"));
    }

    @Test
    void shouldApplyMultipleObjectPatchesAndCommitOnce() {
        // given
        Node document = Nodes.emptyObject();
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/a", new Node().value("one")),
                JsonPatch.add("/b", new Node().value("two")),
                JsonPatch.replace("/a", new Node().value("three"))
        );

        // when
        List<DocumentUpdateData> updates = runtime.applyPatches("/", patches);

        // then
        assertEquals(3, updates.size());
        assertEquals("three", document.getAsText("/a"));
        assertEquals("two", document.getAsText("/b"));
        assertEquals("one", updates.get(0).after().getValue());
        assertEquals("two", updates.get(1).after().getValue());
        assertEquals("three", updates.get(2).after().getValue());
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(1, runtime.countersForTest().batchPatchCalls());
        assertEquals(3, runtime.countersForTest().batchPatchEntries());
        assertEquals(0, runtime.countersForTest().batchPatchRollbackCopies());
    }

    @Test
    void shouldVerifyDuplicatePatchPathsPreserveUpdateOrder() {
        // given
        Node document = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        List<DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/status", new Node().value("first")),
                JsonPatch.replace("/status", new Node().value("second"))
        ));

        // then
        assertEquals("second", document.getAsText("/status"));
        assertEquals("idle", updates.get(0).before().getValue());
        assertEquals("first", updates.get(0).after().getValue());
        assertEquals("first", updates.get(1).before().getValue());
        assertEquals("second", updates.get(1).after().getValue());
    }

    @Test
    void shouldVerifyBatchRollsBackWhenLaterPatchFails() {
        // given
        Node document = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        Throwable failure = captureFailure(
                () -> runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/status", new Node().value("active")),
                JsonPatch.remove("/missing")
        )));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertEquals("idle", document.getAsText("/status"));
        assertNull(document.getProperties().get("missing"));
        assertEquals(0, runtime.countersForTest().batchPatchRollbackCopies());
    }

    @Test
    void shouldVerifyAtomicBatchRejectsCyclicMemberTraversalBeforeSnapshotProviderDemand() {
        // given
        Node document = new Node().properties(
                "cyclic",
                new Node().blueId(CYCLIC_MEMBER_BLUE_ID));
        String exactInput = document.toString();
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);

        // when
        Throwable failure = captureFailure(
                () -> runtime.applyPatches(
                        "/",
                        Collections.singletonList(
                                JsonPatch.add(
                                        "/cyclic/member",
                                        new Node().value(1)))));

        // then
        assertInstanceOf(ProcessorFailureException.class, failure);
        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                ((ProcessorFailureException) failure).errorCategory());
        assertEquals(exactInput, document.toString());
        assertEquals(0, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(0, manager.cacheSnapshotCalls);
    }

    @Test
    void shouldVerifyDirectWriteRejectsCyclicMemberTraversalBeforeSnapshotProviderDemand() {
        // given
        Node document = new Node().properties(
                "cyclic",
                new Node().blueId(CYCLIC_MEMBER_BLUE_ID));
        String exactInput = document.toString();
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);

        // when
        Throwable failure = captureFailure(
                () -> runtime.directWrite(
                        "/cyclic/member",
                        new Node().value(1)));

        // then
        assertInstanceOf(ProcessorFailureException.class, failure);
        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                ((ProcessorFailureException) failure).errorCategory());
        assertEquals(exactInput, document.toString());
        assertEquals(0, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(0, manager.cacheSnapshotCalls);
    }

    @Test
    void shouldVerifyIntrinsicCyclicMemberTraversalFailsBeforeSnapshotProviderDemand() {
        // given
        List<IntrinsicTraversalCase> cases =
                intrinsicTraversalCases();

        // when
        for (IntrinsicTraversalCase traversalCase : cases) {
            traversalCase.failure = captureFailure(
                    () -> traversalCase.runtime.applyPatches(
                            "/",
                            Collections.singletonList(
                                    JsonPatch.add(
                                            traversalCase.path,
                                            new Node().value(1)))));
        }

        // then
        for (IntrinsicTraversalCase traversalCase : cases) {
            assertInstanceOf(
                    ProcessorFailureException.class,
                    traversalCase.failure,
                    traversalCase.label());
            assertEquals(
                    ProcessorErrorCategory.CyclicSetMutationUnsupported,
                    ((ProcessorFailureException) traversalCase.failure)
                            .errorCategory(),
                    traversalCase.label());
            assertEquals(traversalCase.exactInput,
                    traversalCase.document.toString(),
                    traversalCase.label());
            assertEquals(0,
                    traversalCase.manager.fromDocumentCalls,
                    traversalCase.label());
            assertEquals(0,
                    traversalCase.manager.applyPatchCalls,
                    traversalCase.label());
            assertEquals(0,
                    traversalCase.manager.cacheSnapshotCalls,
                    traversalCase.label());
        }
    }

    @Test
    void shouldVerifyAtomicBatchPreflightTracksWholeReferenceReplacementBeforeDescendantPatch() {
        // given
        Node document = new Node().properties(
                "cyclic",
                new Node().blueId(CYCLIC_MEMBER_BLUE_ID));
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);

        // when
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

        // then
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
    void shouldVerifyAtomicBatchPreflightTracksIntroducedReferenceBeforeDescendantPatch() {
        // given
        Node document = Nodes.emptyObject();
        CountingSnapshotManager manager = new CountingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document, null, manager);

        // when
        Throwable failure = captureFailure(
                () -> runtime.applyPatches(
                        "/",
                        Arrays.asList(
                                JsonPatch.add(
                                        "/cyclic",
                                        new Node().blueId(CYCLIC_MEMBER_BLUE_ID)),
                                JsonPatch.add(
                                        "/cyclic/member",
                                        new Node().value("forbidden")))));

        // then
        assertInstanceOf(ProcessorFailureException.class, failure);
        assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported,
                ((ProcessorFailureException) failure).errorCategory());
        assertTrue(document.getProperties().isEmpty());
        assertEquals(0, manager.fromDocumentCalls);
    }

    private List<IntrinsicTraversalCase> intrinsicTraversalCases() {
        List<IntrinsicTraversalCase> cases = new ArrayList<>();
        for (boolean listPayload : Arrays.asList(false, true)) {
            for (String field : Arrays.asList(
                    "type",
                    "itemType",
                    "keyType",
                    "valueType",
                    "blue",
                    "contracts")) {
                Node intrinsic =
                        nodeWithIntrinsicCyclicReference(field);
                Node document;
                String path;
                if (listPayload) {
                    intrinsic.items(
                            new Node().value("retained item"));
                    document =
                            new Node().properties("list", intrinsic);
                    path = "/list/" + field + "/member";
                } else {
                    document = intrinsic;
                    path = "/" + field + "/member";
                }
                CountingSnapshotManager manager =
                        new CountingSnapshotManager();
                cases.add(new IntrinsicTraversalCase(
                        field,
                        listPayload,
                        document,
                        document.toString(),
                        manager,
                        new DocumentProcessingRuntime(
                                document, null, manager),
                        path));
            }
        }
        return cases;
    }

    private static final class IntrinsicTraversalCase {
        private final String field;
        private final boolean listPayload;
        private final Node document;
        private final String exactInput;
        private final CountingSnapshotManager manager;
        private final DocumentProcessingRuntime runtime;
        private final String path;
        private Throwable failure;

        private IntrinsicTraversalCase(
                String field,
                boolean listPayload,
                Node document,
                String exactInput,
                CountingSnapshotManager manager,
                DocumentProcessingRuntime runtime,
                String path) {
            this.field = field;
            this.listPayload = listPayload;
            this.document = document;
            this.exactInput = exactInput;
            this.manager = manager;
            this.runtime = runtime;
            this.path = path;
        }

        private String label() {
            return field + ", listPayload=" + listPayload;
        }
    }

    @Test
    void shouldVerifyBatchFailureDuringCommitLeavesDocumentUnchanged() {
        // given
        Node document = new Node().properties("status", new Node().value("idle"));
        CountingSnapshotManager manager = new CountingSnapshotManager();
        manager.failCacheSnapshot = true;
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, null, manager);

        // when
        Throwable failure = captureFailure(
                () -> runtime.applyPatches("/", Collections.singletonList(
                JsonPatch.replace("/status", new Node().value("active"))
        )));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertEquals("idle", document.getAsText("/status"));
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(0, runtime.countersForTest().batchPatchRollbackCopies());
    }

    @Test
    void shouldVerifyBatchArrayPatchesMatchSequentialArrayPatches() {
        // given
        Node batchDoc = arrayDocument("values", 1, 2, 3);
        Node sequentialDoc = arrayDocument("values", 1, 2, 3);
        DocumentProcessingRuntime batch =
                new DocumentProcessingRuntime(batchDoc);
        DocumentProcessingRuntime sequential =
                new DocumentProcessingRuntime(sequentialDoc);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.add("/values/1", new Node().value(99)),
                JsonPatch.replace("/values/2", new Node().value(100)),
                JsonPatch.remove("/values/0")
        );

        // when
        batch.applyPatches("/", patches);
        for (JsonPatch patch : patches) {
            sequential.applyPatch("/", patch);
        }

        // then
        assertEquals(Arrays.asList(99, 100, 3), integerValues(batchDoc, "/values"));
        assertEquals(integerValues(sequentialDoc, "/values"), integerValues(batchDoc, "/values"));
    }

    @Test
    void shouldDelegateApplyPatchToApplyPatchesSemantics() {
        // given
        Node one = Nodes.emptyObject();
        Node two = Nodes.emptyObject();
        DocumentProcessingRuntime oneRuntime =
                new DocumentProcessingRuntime(one);
        DocumentProcessingRuntime twoRuntime =
                new DocumentProcessingRuntime(two);

        // when
        oneRuntime.applyPatch("/", JsonPatch.add("/x", new Node().value(1)));
        twoRuntime.applyPatches("/", Collections.singletonList(
                JsonPatch.add("/x", new Node().value(1))
        ));

        // then
        assertEquals(one.getAsInteger("/x"), two.getAsInteger("/x"));
    }

    @Test
    void shouldPreserveOrderedUpdatesForAddRemoveAndRemoveAddOnSamePath() {
        // given
        Node document = new Node().properties("temp", new Node().value("old"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        List<DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                JsonPatch.remove("/temp"),
                JsonPatch.add("/temp", new Node().value("new")),
                JsonPatch.add("/scratch", new Node().value("value")),
                JsonPatch.remove("/scratch")
        ));
        Throwable missingScratchFailure = captureFailure(
                () -> document.getAsNode("/scratch"));

        // then
        assertEquals("new", document.getAsText("/temp"));
        assertEquals("old", updates.get(0).before().getValue());
        assertNull(updates.get(0).after());
        assertNull(updates.get(1).before());
        assertEquals("new", updates.get(1).after().getValue());
        assertNull(updates.get(2).before());
        assertEquals("value", updates.get(2).after().getValue());
        assertEquals("value", updates.get(3).before().getValue());
        assertNull(updates.get(3).after());
        assertTrue(missingScratchFailure instanceof IllegalArgumentException);
    }

    @Test
    void shouldMaterializeDetachedUpdateViewsOnlyWhenRead() {
        // given
        Node document = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        List<DocumentUpdateData> updates = runtime.applyPatches("/", Collections.singletonList(
                JsonPatch.replace("/status", new Node().value("active"))
        ));
        long beforeMaterializationsBeforeRead =
                runtime.countersForTest().documentUpdateBeforeNodeMaterializations();
        long afterMaterializationsBeforeRead =
                runtime.countersForTest().documentUpdateAfterNodeMaterializations();
        Object firstBefore = updates.get(0).before().getValue();
        Object firstAfter = updates.get(0).after().getValue();
        Object repeatedBefore = updates.get(0).before().getValue();
        Object repeatedAfter = updates.get(0).after().getValue();
        long beforeMaterializationsAfterRead =
                runtime.countersForTest().documentUpdateBeforeNodeMaterializations();
        long afterMaterializationsAfterRead =
                runtime.countersForTest().documentUpdateAfterNodeMaterializations();

        // then
        assertEquals(0, beforeMaterializationsBeforeRead);
        assertEquals(0, afterMaterializationsBeforeRead);
        assertEquals("idle", firstBefore);
        assertEquals("active", firstAfter);
        assertEquals("idle", repeatedBefore);
        assertEquals("active", repeatedAfter);
        assertEquals(2, beforeMaterializationsAfterRead);
        assertEquals(2, afterMaterializationsAfterRead);
    }

    @Test
    void shouldVerifyInheritedParentThenChildPatchDoesNotMinimizeMidBatch() {
        // given
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
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                snapshot,
                blue.getDocumentProcessor().conformanceEngine(),
                blue.getDocumentProcessor().snapshotManager());
        Node inheritedList = new Node().items(
                Collections.singletonList(
                        new Node().value("inherited")));

        // when
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/a", inheritedList),
                JsonPatch.add("/a/-", new Node().value("custom"))
        ));

        // then
        assertEquals("inherited", runtime.snapshot().canonicalRoot().getAsText("/a/0"));
        assertEquals("custom", runtime.snapshot().canonicalRoot().getAsText("/a/1"));
    }

    @Test
    void shouldVerifySameInheritedPathCanBeChangedAgainInSameBatch() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Mutable Status\n" +
                "status:\n" +
                "  type: Text");
        provider.addSingleDocs(
                "name: Has Inherited Status\n" +
                "type:\n" +
                "  blueId: " + provider.getBlueIdByName("Mutable Status") + "\n" +
                "status: idle");
        Blue blue = ProcessorTestSupport.blue(provider);
        Node canonical = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + provider.getBlueIdByName("Has Inherited Status") + "\n", Node.class);
        canonical.contracts(new Node().properties("generalization",
                new Node().type(new Node().blueId(RuntimeBlueIds.TYPE_GENERALIZATION_POLICY))
                        .properties("defaultMode", new Node().value("nearest-valid-ancestor"))));
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(canonical);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                snapshot,
                blue.getDocumentProcessor().conformanceEngine(),
                blue.getDocumentProcessor().snapshotManager());

        // when
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/status", new Node().value("idle")),
                JsonPatch.replace("/status", new Node().value("custom"))
        ));

        // then
        assertEquals("custom", runtime.snapshot().canonicalRoot().getAsText("/status"));
        assertEquals(
                provider.getBlueIdByName("Mutable Status"),
                runtime.snapshot().frozenCanonicalRoot()
                        .getType()
                        .getReferenceBlueId());
    }

    @Test
    void shouldVerifyEscapedPointerKeysWorkInBatch() {
        // given
        Node document = new Node().properties("tilde", Nodes.emptyObject());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.add("/tilde/a~1b", new Node().value("slash")),
                JsonPatch.add("/tilde/a~0b", new Node().value("tilde")),
                JsonPatch.add("/tilde/~01key", new Node().value("literal"))
        ));

        // then
        assertEquals("slash", document.getAsText("/tilde/a~1b"));
        assertEquals("tilde", document.getAsText("/tilde/a~0b"));
        assertEquals("literal", document.getAsText("/tilde/~01key"));
    }

    @Test
    void shouldVerifyBatchPatchAvoidsRepeatedSnapshotCommitCost() {
        // given
        Node document = new Node().properties("values", Nodes.emptyObject());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);
        List<JsonPatch> patches = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            patches.add(JsonPatch.add("/values/k" + i, new Node().value(i)));
        }

        // when
        long start = System.nanoTime();
        runtime.applyPatches("/", patches);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // then
        assertEquals(100, document.getAsNode("/values").getProperties().size());
        assertTrue(elapsedMs < 1000, "Batch patching should not be catastrophically slow; elapsedMs=" + elapsedMs);
        assertEquals(1, runtime.countersForTest().batchPatchCalls());
        assertEquals(100, runtime.countersForTest().batchPatchEntries());
        assertEquals(0, runtime.countersForTest().batchPatchRollbackCopies());
        assertTrue(runtime.countersForTest().batchPatchPlanningNanos() > 0);
        assertTrue(runtime.countersForTest().batchPatchBuildUpdatesNanos() > 0);
        assertTrue(runtime.countersForTest().batchPatchCommitNanos() > 0);
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
            CanonicalPatchResult patched = new CanonicalOverlayPatchEngine(
                    snapshot.frozenCanonicalRoot()).apply(patch);
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

}
