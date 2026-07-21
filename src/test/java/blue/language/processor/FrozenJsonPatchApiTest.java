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
    void factoriesRetainAuthoredPathsAndImmutableValuesForEveryOperation() {
        FrozenNode value = FrozenNode.fromNode(new Node().value("value"));
        FrozenJsonPatch add = FrozenJsonPatch.add("/a~1b/~0key", value);
        FrozenJsonPatch replace = FrozenJsonPatch.replace("/rows/-", value);
        FrozenJsonPatch remove = FrozenJsonPatch.remove("/");

        assertEquals(JsonPatch.Op.ADD, add.getOp());
        assertEquals("/a~1b/~0key", add.getPath());
        assertEquals(Arrays.asList("a/b", "~key"), add.parsedPath().segments());
        assertSame(value, add.getValue());
        assertSame(value, add.getVal());
        assertEquals(add, FrozenJsonPatch.add("/a~1b/~0key", value));
        assertEquals(add.hashCode(), FrozenJsonPatch.add("/a~1b/~0key", value).hashCode());
        assertEquals(JsonPatch.Op.REPLACE, replace.getOp());
        assertTrue(replace.parsedPath().isAppend());
        assertEquals(JsonPatch.Op.REMOVE, remove.getOp());
        assertTrue(remove.parsedPath().isRoot());
        assertNull(remove.getValue());
        assertThrows(UnsupportedOperationException.class,
                () -> add.parsedPath().segments().add("mutation"));
    }

    @Test
    void equalityDoesNotAliasDistinctAuthoredRepresentationsWithTheSameBlueId() {
        Node materialized = new Node().properties("payload", new Node().value("value"));
        FrozenNode materializedValue = FrozenNode.fromNode(materialized);
        FrozenNode referenceValue = FrozenNode.fromNode(
                new Node().blueId(materializedValue.blueId()));

        FrozenJsonPatch materializedPatch = FrozenJsonPatch.add("/slot", materializedValue);
        FrozenJsonPatch referencePatch = FrozenJsonPatch.add("/slot", referenceValue);

        assertEquals(materializedValue.blueId(), referenceValue.blueId());
        assertNotEquals(materializedPatch, referencePatch);
    }

    @Test
    void historicalAndEmptyRootSpellingsShareParsedRootButPreserveAuthoredText() {
        FrozenJsonPatch empty = FrozenJsonPatch.remove("");
        FrozenJsonPatch slash = FrozenJsonPatch.remove("/");

        assertEquals("", empty.getPath());
        assertEquals("/", slash.getPath());
        assertSame(empty.parsedPath(), slash.parsedPath());
    }

    @Test
    void atomicRuntimeAcceptsBothSupportedRootSpellings() {
        Node slashDocument = new Node().properties("before", new Node().value(true));
        Node emptyDocument = slashDocument.clone();
        FrozenNode replacement = FrozenNode.fromNode(
                new Node().properties("after", new Node().value(true)));

        new DocumentProcessingRuntime(slashDocument).applyFrozenPatch(
                "/", FrozenJsonPatch.replace("/", replacement));
        new DocumentProcessingRuntime(emptyDocument).applyFrozenPatch(
                "/", FrozenJsonPatch.replace("", replacement));

        assertEquals(Boolean.TRUE, slashDocument.get("/after"));
        assertEquals(Boolean.TRUE, emptyDocument.get("/after"));
        assertNull(slashDocument.getProperties().get("before"));
        assertNull(emptyDocument.getProperties().get("before"));
    }

    @Test
    void resolvedDocumentViewsAreRejected() {
        FrozenNode resolved = FrozenNode.fromResolvedNode(new Node().value("inherited"));

        assertThrows(IllegalArgumentException.class,
                () -> FrozenJsonPatch.add("/value", resolved));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenJsonPatch.replace("/value", resolved));
    }

    @Test
    void historicalPointerNormalizationMatchesMutableBoundary() {
        FrozenNode value = FrozenNode.fromNode(new Node().value("value"));
        for (String path : Arrays.asList("relative", "/bad~2escape", "/bad~")) {
            JsonPatch mutable = JsonPatch.add(path, new Node().value("mutable"));
            FrozenJsonPatch frozen = FrozenJsonPatch.add(path, value);
            FrozenJsonPatch converted = FrozenJsonPatch.from(mutable);

            assertEquals(path, frozen.getPath());
            assertEquals(frozen.parsedPath(), converted.parsedPath());
            assertEquals(blue.language.utils.ParsedJsonPointer.parse(path),
                    frozen.parsedPath());

            Node mutableDocument = new Node();
            Node frozenDocument = new Node();
            new DocumentProcessingRuntime(mutableDocument).applyPatch("/", mutable);
            new DocumentProcessingRuntime(frozenDocument).applyFrozenPatch("/", frozen);
            assertEquals("mutable", mutableDocument.getNode(
                    frozen.parsedPath().pointer()).getValue());
            assertEquals("value", frozenDocument.getNode(
                    frozen.parsedPath().pointer()).getValue());
        }
    }

    @Test
    void conversionFromMutablePatchIsolatedFromCallerMutation() {
        Node authored = new Node().properties("nested", new Node().value("before"));
        FrozenJsonPatch patch = FrozenJsonPatch.from(JsonPatch.add("/payload", authored));

        authored.getProperties().get("nested").value("after");

        assertEquals("before", patch.getValue().property("nested").getValue());
    }

    @Test
    void rawJsonValuesAreDeeplySnapshottedForBothFrozenPatchEntryPoints() {
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

        convertedItems.set(0, "after");
        convertedRaw.put("extra", true);
        directItems.set(0, "after");
        directRaw.put("extra", true);

        assertRawJsonSnapshot(converted, convertedBlueId);
        assertRawJsonSnapshot(direct, directBlueId);
        assertSame(directValue, direct.getValue(),
                "the direct frozen handoff must remain allocation-free");

        Node document = new Node().properties("payload", new Node().value("old"));
        new DocumentProcessingRuntime(document).applyFrozenPatch("/", converted);
        Map<?, ?> applied = (Map<?, ?>) document.getNode("/payload").getValue();
        assertEquals(Collections.singletonList("before"), applied.get("items"));
        assertFalse(applied.containsKey("extra"));
    }

    @Test
    void runtimeFrozenBatchMatchesMutableBatchAndRemainsAtomic() {
        Node mutableDocument = new Node().properties("status", new Node().value("idle"));
        Node frozenDocument = mutableDocument.clone();
        List<JsonPatch> mutable = Arrays.asList(
                JsonPatch.replace("/status", new Node().value("active")),
                JsonPatch.add("/count", new Node().value(2)));
        List<FrozenJsonPatch> frozen = Arrays.asList(
                FrozenJsonPatch.from(mutable.get(0)),
                FrozenJsonPatch.from(mutable.get(1)));

        new DocumentProcessingRuntime(mutableDocument).applyPatches("/", mutable);
        new DocumentProcessingRuntime(frozenDocument).applyFrozenPatches("/", frozen);

        assertEquals(mutableDocument.getAsText("/status"), frozenDocument.getAsText("/status"));
        assertEquals(mutableDocument.getAsInteger("/count"), frozenDocument.getAsInteger("/count"));

        Node rollback = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(rollback);
        assertThrows(IllegalStateException.class, () -> runtime.applyFrozenPatches("/", Arrays.asList(
                FrozenJsonPatch.replace("/status", FrozenNode.fromNode(new Node().value("active"))),
                FrozenJsonPatch.remove("/missing"))));
        assertEquals("idle", rollback.getAsText("/status"));
    }

    @Test
    void escapedObjectPathsAndArrayAppendUseTheCapturedParsedPointer() {
        Node document = new Node().properties(
                "a/b", new Node().properties("~key", new Node().value("before")),
                "rows", new Node().items(new Node().value("first")));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);

        runtime.applyFrozenPatches("/", Arrays.asList(
                FrozenJsonPatch.replace("/a~1b/~0key",
                        FrozenNode.fromNode(new Node().value("after"))),
                FrozenJsonPatch.add("/rows/-",
                        FrozenNode.fromNode(new Node().value("second")))));

        assertEquals("after", document.getNode("/a~1b/~0key").getValue());
        assertEquals("second", document.getNode("/rows/1").getValue());
    }

    @Test
    void workingDocumentUsesFrozenValuesForApplyAndPreview() {
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node().properties("status", new Node().value("idle")));
        WorkingDocument working = runtime.workingDocument("/");

        working.applyFrozenPatch(FrozenJsonPatch.replace(
                "/status", FrozenNode.fromNode(new Node().value("active"))));
        WorkingDocument.Preview preview = working.previewAndApplyFrozenPatches(Collections.singletonList(
                FrozenJsonPatch.add("/count", FrozenNode.fromNode(new Node().value(2)))));

        assertEquals("active", working.resolvedAt("/status").getValue());
        assertEquals(2, ((Number) working.resolvedAt("/count").getValue()).intValue());
        assertEquals(1, preview.size());
        assertEquals("idle", runtime.document().getAsText("/status"));
    }

    @Test
    void frozenPatchWorksWithStrictAndUncheckedCanonicalSnapshotRoots() {
        Node document = new Node().properties("value", new Node().value("before"));
        FrozenJsonPatch patch = FrozenJsonPatch.replace(
                "/value", FrozenNode.fromNode(new Node().value("after")));

        ResolvedSnapshot strict = new ResolvedSnapshot(
                FrozenNode.fromNode(document), FrozenNode.fromResolvedNode(document));
        ResolvedSnapshot unchecked = new ResolvedSnapshot(
                FrozenNode.fromUncheckedCanonicalNode(document), FrozenNode.fromResolvedNode(document));
        WorkingDocument strictWorking = workingDocument(strict);
        WorkingDocument uncheckedWorking = workingDocument(unchecked);

        strictWorking.applyFrozenPatch(patch);
        uncheckedWorking.applyFrozenPatch(patch);

        assertEquals("after", strictWorking.resolvedAt("/value").getValue());
        assertEquals("after", uncheckedWorking.resolvedAt("/value").getValue());
        assertTrue(strictWorking.canonicalAt("/value").isStrictCanonical());
        assertTrue(uncheckedWorking.canonicalAt("/value").isStrictCanonical());
        assertFalse(uncheckedWorking.canonicalAt("/value").isStrictBlueIdValidation());
    }

    @Test
    void referenceTypedAndSchemaAuthoredValuesRemainAcceptedCanonicalValues() {
        FrozenJsonPatch reference = FrozenJsonPatch.add("/reference",
                FrozenNode.fromNode(new Node().blueId(TEXT_TYPE_BLUE_ID)));
        FrozenJsonPatch typed = FrozenJsonPatch.add("/typed", FrozenNode.fromNode(new Node()
                .type(new Node().blueId(TEXT_TYPE_BLUE_ID))
                .value("text")));
        FrozenJsonPatch schema = FrozenJsonPatch.add("/schema", FrozenNode.fromNode(new Node()
                .schema(new Schema().minLength(1))));
        FrozenJsonPatch cyclicMember = FrozenJsonPatch.add("/cyclic", FrozenNode.fromNode(
                new Node().blueId(TEXT_TYPE_BLUE_ID + "#0")));

        assertTrue(reference.getValue().isReferenceOnly());
        assertEquals(TEXT_TYPE_BLUE_ID, typed.getValue().getType().getReferenceBlueId());
        assertEquals(1, schema.getValue().getSchema().getMinLengthExact().intValue());
        assertEquals(TEXT_TYPE_BLUE_ID + "#0", cyclicMember.getValue().getReferenceBlueId());
    }

    @Test
    void authoredValueConstructionModeConversionMatchesLegacyMaterialization() {
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
        FrozenNode legacyUnchecked = FrozenNode.fromUncheckedCanonicalNode(authored);
        assertEquals(legacyResolved.resolvedStructuralKey(), resolvedMode.resolvedStructuralKey());
        assertEquals(legacyResolved.blueId(), resolvedMode.blueId());
        assertEquals(legacyUnchecked.blueId(), uncheckedMode.blueId());
        assertEquals(NodeCanonicalizer.canonicalSize(authored),
                NodeCanonicalizer.canonicalFrozenSize(frozen));
    }

    @Test
    void sameFrozenPatchCanBeReusedConcurrentlyAcrossIndependentRuntimes() throws Exception {
        final FrozenJsonPatch patch = FrozenJsonPatch.replace(
                "/status", FrozenNode.fromNode(new Node().value("after")));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = Arrays.asList(
                    task(patch), task(patch), task(patch), task(patch),
                    task(patch), task(patch), task(patch), task(patch));
            for (Future<String> result : executor.invokeAll(tasks)) {
                assertEquals("after", result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void frozenPathDoesNotFreezeOrMaterializePatchValues() {
        RecordingMetrics metrics = new RecordingMetrics();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), null, null, metrics);

        runtime.applyFrozenPatch("/", FrozenJsonPatch.add(
                "/value", FrozenNode.fromNode(new Node().value("already frozen"))));

        assertEquals(1, metrics.frozenAccepted);
        assertEquals(0, metrics.mutableFrozen);
        assertEquals(0, metrics.frozenMaterialized);
    }

    @Test
    void frozenProcessorGasChargeMatchesTheLegacyMutableValueCharge() {
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

            mutable.chargePatchAddOrReplace(authored);
            frozen.chargeFrozenPatchAddOrReplace(FrozenNode.fromNode(authored));

            assertEquals(mutable.totalGas(), frozen.totalGas());
        }
    }

    @Test
    void conversionRetainsLegacyGasSizeWhenCanonicalFreezeDropsEmptyFields() {
        Node authored = new Node().properties(
                "pad", new Node().value("12345"),
                "empty", new Node());
        FrozenJsonPatch converted = FrozenJsonPatch.from(
                JsonPatch.add("/payload", authored));
        GasMeter mutable = new GasMeter();
        GasMeter frozen = new GasMeter();

        assertEquals(101L, NodeCanonicalizer.canonicalSize(authored));
        assertEquals(90L, NodeCanonicalizer.canonicalFrozenSize(converted.getValue()));
        assertEquals(101L, converted.getAuthoredCanonicalSizeBytes());
        mutable.chargePatchAddOrReplace(authored);
        frozen.chargeFrozenPatchAddOrReplace(
                converted.getAuthoredCanonicalSizeBytes());

        assertEquals(22L, mutable.totalGas());
        assertEquals(mutable.totalGas(), frozen.totalGas());
    }

    @SuppressWarnings("unchecked")
    private void assertRawJsonSnapshot(FrozenJsonPatch patch, String expectedBlueId) {
        Map<String, Object> captured = (Map<String, Object>) patch.getValue().getValue();
        List<Object> capturedItems = (List<Object>) captured.get("items");
        assertEquals(Collections.singletonList("before"), capturedItems);
        assertFalse(captured.containsKey("extra"));
        assertEquals(expectedBlueId, patch.getValue().blueId());
        assertThrows(UnsupportedOperationException.class,
                () -> captured.put("mutation", true));
        assertThrows(UnsupportedOperationException.class,
                () -> capturedItems.set(0, "mutation"));
    }

    private Callable<String> task(final FrozenJsonPatch patch) {
        return () -> {
            Node document = new Node().properties("status", new Node().value("before"));
            new DocumentProcessingRuntime(document).applyFrozenPatch("/", patch);
            return document.getAsText("/status");
        };
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
                ProcessingMetricsSink.NOOP);
    }

    private static final class RecordingMetrics implements ProcessingMetricsSink {
        private int frozenAccepted;
        private int mutableFrozen;
        private int frozenMaterialized;

        @Override
        public void incrementFrozenPatchValuesAccepted() {
            frozenAccepted++;
        }

        @Override
        public void incrementMutablePatchValuesFrozen() {
            mutableFrozen++;
        }

        @Override
        public void incrementFrozenPatchValuesMaterialized() {
            frozenMaterialized++;
        }
    }
}
