package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenJsonPatchApiTest {

    @Test
    void shouldVerifyFactoriesRetainAuthoredPathsAndImmutableValuesForEveryOperation() {
        // given
        FrozenNode value = FrozenNode.fromNode(new Node().value("value"));
        FrozenJsonPatch add = FrozenJsonPatch.add("/a~1b/~0key", value);
        FrozenJsonPatch replace = FrozenJsonPatch.replace("/rows/-", value);
        FrozenJsonPatch remove = FrozenJsonPatch.remove("/");

        // when
        Throwable mutationFailure = captureFailure(
                () -> add.parsedPath()
                        .segments()
                        .add("mutation"));

        // then
        assertEquals(JsonPatch.Op.ADD, add.getOp());
        assertEquals("/a~1b/~0key", add.getPath());
        assertEquals(Arrays.asList("a/b", "~key"), add.parsedPath().segments());
        assertSame(value, add.getValue());
        assertSame(value, add.getValue());
        assertEquals(add, FrozenJsonPatch.add("/a~1b/~0key", value));
        assertEquals(add.hashCode(), FrozenJsonPatch.add("/a~1b/~0key", value).hashCode());
        assertEquals(JsonPatch.Op.REPLACE, replace.getOp());
        assertTrue(replace.parsedPath().isAppend());
        assertEquals(JsonPatch.Op.REMOVE, remove.getOp());
        assertTrue(remove.parsedPath().isRoot());
        assertNull(remove.getValue());
        assertTrue(mutationFailure instanceof UnsupportedOperationException);
    }

    @Test
    void shouldVerifyEqualityDoesNotAliasDistinctAuthoredRepresentationsWithTheSameBlueId() {
        // given
        Node materialized = new Node().properties("payload", new Node().value("value"));
        FrozenNode materializedValue = FrozenNode.fromNode(materialized);
        FrozenNode referenceValue = FrozenNode.fromNode(
                new Node().blueId(materializedValue.blueId()));

        // when
        FrozenJsonPatch materializedPatch = FrozenJsonPatch.add("/slot", materializedValue);
        FrozenJsonPatch referencePatch = FrozenJsonPatch.add("/slot", referenceValue);

        // then
        assertEquals(materializedValue.blueId(), referenceValue.blueId());
        assertNotEquals(materializedPatch, referencePatch);
    }

    @Test
    void shouldVerifyHistoricalAndEmptyRootSpellingsShareParsedRootButPreserveAuthoredText() {
        // given
        FrozenJsonPatch empty = FrozenJsonPatch.remove("");
        // when
        FrozenJsonPatch slash = FrozenJsonPatch.remove("/");

        // then
        assertEquals("", empty.getPath());
        assertEquals("/", slash.getPath());
        assertSame(empty.parsedPath(), slash.parsedPath());
    }

    @Test
    void shouldVerifyAtomicRuntimeAcceptsBothSupportedRootSpellings() {
        // given
        Node slashDocument = new Node().properties("before", new Node().value(true));
        Node emptyDocument = slashDocument.clone();
        FrozenNode replacement = FrozenNode.fromNode(
                new Node().properties("after", new Node().value(true)));

        // when
        new DocumentProcessingRuntime(slashDocument).applyFrozenPatch(
                "/", FrozenJsonPatch.replace("/", replacement));
        new DocumentProcessingRuntime(emptyDocument).applyFrozenPatch(
                "/", FrozenJsonPatch.replace("", replacement));

        // then
        assertEquals(Boolean.TRUE, slashDocument.get("/after"));
        assertEquals(Boolean.TRUE, emptyDocument.get("/after"));
        assertNull(slashDocument.getProperties().get("before"));
        assertNull(emptyDocument.getProperties().get("before"));
    }

    @Test
    void shouldVerifyResolvedDocumentViewsAreRejected() {
        // given
        FrozenNode resolved = FrozenNode.fromResolvedNode(new Node().value("inherited"));

        // when
        IllegalArgumentException addFailure = captureFailure(
                () -> FrozenJsonPatch.add("/value", resolved));
        IllegalArgumentException replaceFailure = captureFailure(
                () -> FrozenJsonPatch.replace("/value", resolved));

        // then
        assertEquals(IllegalArgumentException.class,
                addFailure.getClass());
        assertEquals(IllegalArgumentException.class,
                replaceFailure.getClass());
    }

    @Test
    void shouldVerifyHistoricalPointerNormalizationMatchesMutableBoundary() {
        // given
        FrozenNode value = FrozenNode.fromNode(new Node().value("value"));
        List<String> paths =
                Arrays.asList(
                        "relative",
                        "/bad~2escape",
                        "/bad~");

        // when
        List<PointerNormalizationObservation> observations =
                new ArrayList<>(paths.size());
        for (String path : paths) {
            JsonPatch mutable = JsonPatch.add(path, new Node().value("mutable"));
            FrozenJsonPatch frozen = FrozenJsonPatch.add(path, value);
            FrozenJsonPatch converted = FrozenJsonPatch.from(mutable);

            Node mutableDocument = new Node();
            Node frozenDocument = new Node();
            new DocumentProcessingRuntime(mutableDocument).applyPatch("/", mutable);
            new DocumentProcessingRuntime(frozenDocument).applyFrozenPatch("/", frozen);
            observations.add(
                    new PointerNormalizationObservation(
                            path,
                            frozen,
                            converted,
                            mutableDocument,
                            frozenDocument));
        }

        // then
        for (PointerNormalizationObservation observation :
                observations) {
            assertEquals(
                    observation.path,
                    observation.frozen.getPath());
            assertEquals(
                    observation.frozen.parsedPath(),
                    observation.converted.parsedPath());
            assertEquals(
                    blue.language.utils.ParsedJsonPointer.parse(
                            observation.path),
                    observation.frozen.parsedPath());
            assertEquals(
                    "mutable",
                    observation.mutableDocument.getNode(
                            observation.frozen
                                    .parsedPath()
                                    .pointer()).getValue());
            assertEquals(
                    "value",
                    observation.frozenDocument.getNode(
                            observation.frozen
                                    .parsedPath()
                                    .pointer()).getValue());
        }
    }

    @Test
    void shouldVerifyConversionFromMutablePatchIsolatedFromCallerMutation() {
        // given
        Node authored = new Node().properties("nested", new Node().value("before"));
        FrozenJsonPatch patch = FrozenJsonPatch.from(JsonPatch.add("/payload", authored));

        // when
        authored.getProperties().get("nested").value("after");

        // then
        assertEquals("before", patch.getValue().property("nested").getValue());
    }

    @Test
    void shouldVerifyRawJsonValuesAreDeeplySnapshottedForBothFrozenPatchEntryPoints() {
        // given
        List<Object> convertedItems = new ArrayList<>();
        convertedItems.add("before");
        Map<String, Object> convertedRaw = new LinkedHashMap<>();
        convertedRaw.put("items", convertedItems);
        FrozenJsonPatch converted = FrozenJsonPatch.from(JsonPatch.replace(
                "/payload", new Node().value(convertedRaw)));
        String convertedBlueId = converted.getValue().blueId();

        List<Object> directItems = new ArrayList<>();
        directItems.add("before");
        Map<String, Object> directRaw = new LinkedHashMap<>();
        directRaw.put("items", directItems);
        FrozenNode directValue = FrozenNode.fromNode(new Node().value(directRaw));
        FrozenJsonPatch direct = FrozenJsonPatch.add("/payload", directValue);
        String directBlueId = direct.getValue().blueId();
        Node document = new Node().properties("payload", new Node().value("old"));

        // when
        convertedItems.set(0, "after");
        convertedRaw.put("extra", true);
        directItems.set(0, "after");
        directRaw.put("extra", true);
        new DocumentProcessingRuntime(document).applyFrozenPatch("/", converted);
        Map<?, ?> applied = (Map<?, ?>) document.getNode("/payload").getValue();
        RawJsonSnapshotObservation convertedObservation =
                observeRawJsonSnapshot(converted);
        RawJsonSnapshotObservation directObservation =
                observeRawJsonSnapshot(direct);

        // then
        assertRawJsonSnapshot(
                convertedObservation,
                convertedBlueId);
        assertRawJsonSnapshot(
                directObservation,
                directBlueId);
        assertSame(directValue, direct.getValue(),
                "the direct frozen handoff must remain allocation-free");

        assertEquals(Collections.singletonList("before"), applied.get("items"));
        assertFalse(applied.containsKey("extra"));
    }

    @Test
    void shouldVerifyRuntimeFrozenBatchMatchesMutableBatchAndRemainsAtomic() {
        // given
        Node mutableDocument = new Node().properties("status", new Node().value("idle"));
        Node frozenDocument = mutableDocument.clone();
        List<JsonPatch> mutable = Arrays.asList(
                JsonPatch.replace("/status", new Node().value("active")),
                JsonPatch.add("/count", new Node().value(2)));
        List<FrozenJsonPatch> frozen = Arrays.asList(
                FrozenJsonPatch.from(mutable.get(0)),
                FrozenJsonPatch.from(mutable.get(1)));
        Node rollback = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(rollback);

        // when
        new DocumentProcessingRuntime(mutableDocument).applyPatches("/", mutable);
        new DocumentProcessingRuntime(frozenDocument).applyFrozenPatches("/", frozen);
        Throwable rollbackFailure = captureFailure(
                () -> runtime.applyFrozenPatches("/", Arrays.asList(
                        FrozenJsonPatch.replace(
                                "/status",
                                FrozenNode.fromNode(
                                        new Node().value("active"))),
                        FrozenJsonPatch.remove("/missing"))));

        // then
        assertEquals(mutableDocument.getAsText("/status"), frozenDocument.getAsText("/status"));
        assertEquals(mutableDocument.getAsInteger("/count"), frozenDocument.getAsInteger("/count"));
        assertTrue(rollbackFailure instanceof IllegalStateException);
        assertEquals("idle", rollback.getAsText("/status"));
    }

    @Test
    void shouldVerifyEscapedObjectPathsAndArrayAppendUseTheCapturedParsedPointer() {
        // given
        Node document = new Node().properties(
                "a/b", new Node().properties("~key", new Node().value("before")),
                "rows", new Node().items(new Node().value("first")));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        // when
        runtime.applyFrozenPatches("/", Arrays.asList(
                FrozenJsonPatch.replace("/a~1b/~0key",
                        FrozenNode.fromNode(new Node().value("after"))),
                FrozenJsonPatch.add("/rows/-",
                        FrozenNode.fromNode(new Node().value("second")))));

        // then
        assertEquals("after", document.getNode("/a~1b/~0key").getValue());
        assertEquals("second", document.getNode("/rows/1").getValue());
    }

    @Test
    void shouldVerifyWorkingDocumentUsesFrozenValuesForApplyAndPreview() {
        // given
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node().properties("status", new Node().value("idle")));
        WorkingDocument working = runtime.workingDocument("/");

        // when
        working.applyFrozenPatch(FrozenJsonPatch.replace(
                "/status", FrozenNode.fromNode(new Node().value("active"))));
        WorkingDocument.Preview preview = working.previewAndApplyFrozenPatches(Collections.singletonList(
                FrozenJsonPatch.add("/count", FrozenNode.fromNode(new Node().value(2)))));

        // then
        assertEquals("active", working.resolvedAt("/status").getValue());
        assertEquals(2, ((Number) working.resolvedAt("/count").getValue()).intValue());
        assertEquals(1, preview.size());
        assertEquals("idle", runtime.document().getAsText("/status"));
    }

    @Test
    void shouldVerifyFrozenPatchWorksWithStrictAndUncheckedCanonicalSnapshotRoots() {
        // given
        Node document = new Node().properties("state", new Node().value("before"));
        FrozenJsonPatch patch = FrozenJsonPatch.replace(
                "/state", FrozenNode.fromNode(new Node().value("after")));

        ResolvedSnapshot strict = new ResolvedSnapshot(
                FrozenNode.fromNode(document), FrozenNode.fromResolvedNode(document));
        ResolvedSnapshot unchecked = new ResolvedSnapshot(
                FrozenNode.fromUncheckedCanonicalNode(document), FrozenNode.fromResolvedNode(document));
        WorkingDocument strictWorking = workingDocument(strict);
        WorkingDocument uncheckedWorking = workingDocument(unchecked);

        // when
        strictWorking.applyFrozenPatch(patch);
        uncheckedWorking.applyFrozenPatch(patch);

        // then
        assertEquals("after", strictWorking.resolvedAt("/state").getValue());
        assertEquals("after", uncheckedWorking.resolvedAt("/state").getValue());
        assertTrue(strictWorking.canonicalAt("/state").isStrictCanonical());
        assertTrue(uncheckedWorking.canonicalAt("/state").isStrictCanonical());
        assertFalse(uncheckedWorking.canonicalAt("/state").isStrictBlueIdValidation());
    }

    @Test
    void shouldVerifyReferenceTypedAndSchemaAuthoredValuesRemainAcceptedCanonicalValues() {
        // given
        FrozenJsonPatch reference = FrozenJsonPatch.add("/reference",
                FrozenNode.fromNode(new Node().blueId(TEXT_TYPE_BLUE_ID)));
        FrozenJsonPatch typed = FrozenJsonPatch.add("/typed", FrozenNode.fromNode(new Node()
                .type(new Node().blueId(TEXT_TYPE_BLUE_ID))
                .value("text")));
        FrozenJsonPatch schema = FrozenJsonPatch.add("/schema", FrozenNode.fromNode(new Node()
                .schema(new Schema().minLength(1))));
        // when
        FrozenJsonPatch cyclicMember = FrozenJsonPatch.add("/cyclic", FrozenNode.fromNode(
                new Node().blueId(TEXT_TYPE_BLUE_ID + "#0")));

        // then
        assertTrue(reference.getValue().isReferenceOnly());
        assertEquals(TEXT_TYPE_BLUE_ID, typed.getValue().getType().getReferenceBlueId());
        assertEquals(1, schema.getValue().getSchema().getMinLengthExact().intValue());
        assertEquals(TEXT_TYPE_BLUE_ID + "#0", cyclicMember.getValue().getReferenceBlueId());
    }

    @Test
    void shouldVerifyAuthoredValueConstructionModeConversionMatchesLegacyMaterialization() {
        // given
        Node authored = new Node().properties(
                "typed", new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID)).value("text"),
                "schema", new Node().schema(new Schema().minLength(1)),
                "reference", new Node().blueId(TEXT_TYPE_BLUE_ID),
                "items", new Node().items(new Node().value("first"), new Node().value("second")));
        FrozenNode frozen = FrozenNode.fromNode(authored);
        FrozenNode resolvedMode = FrozenNode.authoredValueInModeOf(
                frozen, FrozenNode.fromResolvedNode(new Node()));
        FrozenNode uncheckedMode = FrozenNode.authoredValueInModeOf(
                frozen, FrozenNode.fromUncheckedCanonicalNode(new Node()));

        FrozenNode legacyResolved = FrozenNode.fromResolvedNode(authored);
        // when
        FrozenNode legacyUnchecked = FrozenNode.fromUncheckedCanonicalNode(authored);
        // then
        assertEquals(legacyResolved.resolvedStructuralKey(), resolvedMode.resolvedStructuralKey());
        assertEquals(legacyResolved.blueId(), resolvedMode.blueId());
        assertEquals(legacyUnchecked.blueId(), uncheckedMode.blueId());
        assertEquals(NodeCanonicalizer.canonicalSize(authored),
                NodeCanonicalizer.canonicalFrozenSize(frozen));
    }

    @Test
    void shouldVerifySameFrozenPatchCanBeReusedConcurrentlyAcrossIndependentRuntimes() throws Exception {
        // given
        final FrozenJsonPatch patch = FrozenJsonPatch.replace(
                "/status", FrozenNode.fromNode(new Node().value("after")));
        ExecutorService executor = Executors.newFixedThreadPool(4);

        // when
        List<String> results = new ArrayList<>();
        try {
            List<Callable<String>> tasks = Arrays.asList(
                    task(patch), task(patch), task(patch), task(patch),
                    task(patch), task(patch), task(patch), task(patch));
            for (Future<String> result : executor.invokeAll(tasks)) {
                results.add(result.get());
            }
        } finally {
            executor.shutdownNow();
        }

        // then
        for (String result : results) {
            assertEquals("after", result);
        }
    }

    private static final class PointerNormalizationObservation {
        private final String path;
        private final FrozenJsonPatch frozen;
        private final FrozenJsonPatch converted;
        private final Node mutableDocument;
        private final Node frozenDocument;

        private PointerNormalizationObservation(
                String path,
                FrozenJsonPatch frozen,
                FrozenJsonPatch converted,
                Node mutableDocument,
                Node frozenDocument) {
            this.path = path;
            this.frozen = frozen;
            this.converted = converted;
            this.mutableDocument = mutableDocument;
            this.frozenDocument = frozenDocument;
        }
    }

    @Test
    void shouldVerifyFrozenPathDoesNotFreezeOrMaterializePatchValues() {
        // given
        RecordingMetrics metrics = new RecordingMetrics();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), null, null, metrics);

        // when
        runtime.applyFrozenPatch("/", FrozenJsonPatch.add(
                "/value", FrozenNode.fromNode(new Node().value("already frozen"))));

        // then
        assertEquals(1, metrics.frozenAccepted);
        assertEquals(0, metrics.mutableFrozen);
        assertEquals(0, metrics.frozenMaterialized);
    }

    @Test
    void shouldVerifyFrozenProcessorGasChargeMatchesTheLegacyMutableValueCharge() {
        // given
        Node structured = new Node().properties(
                "typed", new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID)).value("text"),
                "rows", new Node().items(new Node().value(1), new Node().value(2)));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("items", Arrays.<Object>asList("first", 2L, true));

        for (Node authored : Arrays.asList(
                structured,
                new Node().value(raw),
                new Node().value(Byte.valueOf((byte) 7)))) {
            GasMeter mutable = new GasMeter();
            GasMeter frozen = new GasMeter();

            // when
            mutable.chargePatchAddOrReplace(authored);
            frozen.chargeFrozenPatchAddOrReplace(FrozenNode.fromNode(authored));

            // then
            assertEquals(mutable.totalGas(), frozen.totalGas());
        }
    }

    @Test
    void shouldVerifyConversionRetainsAuthoredSizeWhilePortablePatchGasIsFixed() {
        // given
        Node authored = new Node().properties(
                "pad", new Node().value("12345"),
                "empty", new Node());
        FrozenJsonPatch converted = FrozenJsonPatch.from(
                JsonPatch.add("/payload", authored));
        GasMeter mutable = new GasMeter();
        GasMeter frozen = new GasMeter();

        // when
        long mutableSize = NodeCanonicalizer.canonicalSize(authored);
        long frozenSize =
                NodeCanonicalizer.canonicalFrozenSize(converted.getValue());
        mutable.chargePatchAddOrReplace(authored);
        frozen.chargeFrozenPatchAddOrReplace(
                converted.getAuthoredCanonicalSizeBytes());

        // then
        assertEquals(101L, mutableSize);
        assertEquals(90L, frozenSize);
        assertEquals(101L, converted.getAuthoredCanonicalSizeBytes());
        assertEquals(20L, mutable.totalGas());
        assertEquals(mutable.totalGas(), frozen.totalGas());
    }

    @SuppressWarnings("unchecked")
    private RawJsonSnapshotObservation observeRawJsonSnapshot(
            FrozenJsonPatch patch) {
        Map<String, Object> captured = (Map<String, Object>) patch.getValue().getValue();
        List<Object> capturedItems = (List<Object>) captured.get("items");
        Throwable mapMutationFailure = captureFailure(
                () -> captured.put("mutation", true));
        Throwable itemMutationFailure = captureFailure(
                () -> capturedItems.set(0, "mutation"));
        return new RawJsonSnapshotObservation(
                new ArrayList<>(capturedItems),
                captured.containsKey("extra"),
                patch.getValue().blueId(),
                mapMutationFailure,
                itemMutationFailure);
    }

    private void assertRawJsonSnapshot(
            RawJsonSnapshotObservation observation,
            String expectedBlueId) {
        assertEquals(
                Collections.singletonList("before"),
                observation.items);
        assertFalse(observation.extraPresent);
        assertEquals(expectedBlueId, observation.blueId);
        assertTrue(
                observation.mapMutationFailure
                        instanceof UnsupportedOperationException);
        assertTrue(
                observation.itemMutationFailure
                        instanceof UnsupportedOperationException);
    }

    private Callable<String> task(final FrozenJsonPatch patch) {
        return () -> {
            Node document = new Node().properties("status", new Node().value("before"));
            new DocumentProcessingRuntime(document).applyFrozenPatch("/", patch);
            return document.getAsText("/status");
        };
    }

    private static final class RawJsonSnapshotObservation {
        private final List<Object> items;
        private final boolean extraPresent;
        private final String blueId;
        private final Throwable mapMutationFailure;
        private final Throwable itemMutationFailure;

        private RawJsonSnapshotObservation(
                List<Object> items,
                boolean extraPresent,
                String blueId,
                Throwable mapMutationFailure,
                Throwable itemMutationFailure) {
            this.items = items;
            this.extraPresent = extraPresent;
            this.blueId = blueId;
            this.mapMutationFailure = mapMutationFailure;
            this.itemMutationFailure = itemMutationFailure;
        }
    }

    private WorkingDocument workingDocument(ResolvedSnapshot snapshot) {
        return new WorkingDocument("/",
                snapshot.frozenCanonicalRoot(),
                snapshot.frozenResolvedRoot(),
                null,
                null,
                null,
                snapshot,
                false,
                true,
                PatchSource.LEGACY_PUBLIC_API,
                NoOpProcessingObserver.INSTANCE);
    }

    private static final class RecordingMetrics implements ProcessingObserver {
        private int frozenAccepted;
        private int mutableFrozen;
        private int frozenMaterialized;

        @Override
        public void record(ProcessingObservation observation) {
            switch (observation.metricId()) {
                case FROZEN_PATCH_VALUES_ACCEPTED:
                    frozenAccepted += observation.value();
                    break;
                case MUTABLE_PATCH_VALUES_FROZEN:
                    mutableFrozen += observation.value();
                    break;
                case FROZEN_PATCH_VALUES_MATERIALIZED:
                    frozenMaterialized += observation.value();
                    break;
                default:
                    break;
            }
        }
    }
}
