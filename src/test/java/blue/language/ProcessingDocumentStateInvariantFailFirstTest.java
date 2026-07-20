package blue.language;

import blue.language.MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingRuntime;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.MergeReverser;
import blue.language.utils.NodeToMapListOrValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingDocumentStateInvariantFailFirstTest {

    @Test
    void snapshotConstructionPreservesSelectedStateBeforeAnyWrite() {
        AuditFixture fixture = new AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        Node callerInput = fixture.materializedSource();
        Node runtimeOwnedSelection = callerInput.clone();
        String callerBefore = blue.nodeToJson(callerInput);
        ResolvedSnapshot expectedSnapshot = blue.resolveToSnapshot(runtimeOwnedSelection.clone());
        ProcessingSnapshotManager manager = new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                return blue.resolveToSnapshot(document);
            }

            @Override
            public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
                throw new AssertionError("no write is allowed in this boundary characterization");
            }
        };
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(runtimeOwnedSelection, null, manager);

        ResolvedSnapshot snapshot = runtime.snapshot();

        assertEquals(callerBefore, blue.nodeToJson(callerInput));
        assertTrue(hasSelectedContract(callerInput, "audit"));
        assertEquals("materialized", callerInput.getAsText("/materializedField"));
        assertEquals(callerBefore, blue.nodeToJson(runtime.document()));
        assertTrue(hasSelectedContract(runtime.document(), "audit"));
        assertEquals("materialized", runtime.document().getAsText("/materializedField"));
        assertNull(firstDifference(expectedSnapshot.canonicalRoot(), snapshot.canonicalRoot()));
        assertNull(firstDifference(expectedSnapshot.resolvedRoot(), snapshot.resolvedRoot()));
        assertEquals(expectedSnapshot.blueId(), snapshot.blueId());
    }

    @Test
    void initializationMarkerInsertionSatisfiesThreeViewInvariant() {
        AuditFixture fixture = new AuditFixture();
        Node before = fixture.materializedSource();
        Node expected = expectedInitializedSelected(fixture, before);
        Blue executionBlue = fixture.newBlue(new AtomicInteger());

        Observation observation = observe(fixture,
                "initialization marker",
                executionBlue,
                before,
                expected,
                () -> executionBlue.initializeDocument(before));

        observation.assertThreeViewInvariant();
    }

    @Test
    void checkpointDirectWritesWithoutHandlerPatchSatisfyThreeViewInvariant() {
        AuditFixture fixture = new AuditFixture();
        Node eventA = fixture.auditEvent("A");
        Node before = expectedInitializedSelected(fixture, fixture.materializedSource());
        Node expected = expectedAfterEvent(fixture, before, eventA, false);
        AtomicInteger executions = new AtomicInteger();
        Blue executionBlue = fixture.newBlueWithoutHandlerPatch(executions);

        Observation observation = observe(fixture,
                "checkpoint Direct Writes without handler patch",
                executionBlue,
                before,
                expected,
                () -> executionBlue.processDocument(before, eventA));

        assertEquals(1, executions.get());
        observation.assertThreeViewInvariant();
    }

    @Test
    void ordinaryHandlerPatchAndCheckpointDirectWritesSatisfyThreeViewInvariant() {
        AuditFixture fixture = new AuditFixture();
        Node eventA = fixture.auditEvent("A");
        Node before = expectedInitializedSelected(fixture, fixture.materializedSource());
        Node expected = expectedAfterEvent(fixture, before, eventA, true);
        AtomicInteger executions = new AtomicInteger();
        Blue executionBlue = fixture.newBlue(executions);

        Observation observation = observe(fixture,
                "ordinary handler patch and checkpoint Direct Writes",
                executionBlue,
                before,
                expected,
                () -> executionBlue.processDocument(before, eventA));

        assertEquals(1, executions.get());
        observation.assertThreeViewInvariant();
    }

    @Test
    void combinedInitializationHandlerPatchAndCheckpointSatisfyThreeViewInvariant() {
        AuditFixture fixture = new AuditFixture();
        Node eventA = fixture.auditEvent("A");
        Node before = fixture.materializedSource();
        Node initialized = expectedInitializedSelected(fixture, before);
        Node expected = expectedAfterEvent(fixture, initialized, eventA, true);
        AtomicInteger executions = new AtomicInteger();
        Blue executionBlue = fixture.newBlue(executions);

        Observation observation = observe(fixture,
                "combined initialization, handler patch, and checkpoint",
                executionBlue,
                before,
                expected,
                () -> executionBlue.processDocument(before, eventA));

        assertEquals(1, executions.get());
        observation.assertThreeViewInvariant();
    }

    @Test
    void completedProcessingResultMinimizesAndReloadsWithSameIdentity() {
        AuditFixture fixture = new AuditFixture();
        Node eventA = fixture.auditEvent("A");
        AtomicInteger executions = new AtomicInteger();
        Blue processor = fixture.newBlue(executions);
        DocumentProcessingResult completed = processor.processDocument(
                fixture.materializedSource(), eventA);

        assertEquals(ProcessorStatus.SUCCESS, completed.status(), completed.failureReason());
        assertEquals(1, executions.get());
        assertTrue(hasSelectedContract(completed.document(), "audit"));
        assertEquals(Boolean.TRUE, completed.document().get("/auditRan"));

        Node minimized = new MergeReverser().reverseToMinimizedOverlay(completed.resolvedDocument());
        Node transported = processor.jsonToNode(processor.nodeToJson(minimized));
        Blue reloader = fixture.newBlue(new AtomicInteger());
        ResolvedSnapshot reloaded = reloader.resolveToSnapshot(transported);

        assertEquals(completed.blueId(), reloaded.blueId());
        assertNull(firstDifference(completed.resolvedDocument(), reloaded.resolvedRoot()));
        assertEquals(Boolean.TRUE, reloaded.resolvedNodeAt("/auditRan").getValue());
        assertEquals("A", reloaded.resolvedNodeAt(
                "/contracts/checkpoint/lastEvents/incoming/checkpointIdentity").getValue());
    }

    private static Observation observe(AuditFixture fixture,
                                       String label,
                                       Blue executionBlue,
                                       Node callerInput,
                                       Node expectedSelected,
                                       Transition transition) {
        String callerBefore = executionBlue.nodeToJson(callerInput);
        DocumentProcessingResult result = transition.apply();
        assertEquals(callerBefore, executionBlue.nodeToJson(callerInput), label + " mutated caller input");
        assertEquals(ProcessorStatus.SUCCESS, result.status(), label + ": " + result.failureReason());
        assertNotNull(result.snapshot(), label + " must return its semantic snapshot");

        Blue verifier = fixture.newBlue(new AtomicInteger());
        ResolvedSnapshot expectedSnapshot = verifier.resolveToSnapshot(expectedSelected.clone());
        return new Observation(label, expectedSelected, expectedSnapshot, result);
    }

    private static Node expectedInitializedSelected(AuditFixture fixture, Node selectedBefore) {
        Node expected = selectedBefore.clone();
        Blue identityBlue = fixture.newBlue(new AtomicInteger());
        String preInitializationIdentity = BlueIdCalculator.calculateUncheckedBlueId(
                identityBlue.resolveToSnapshot(selectedBefore.clone()).frozenCanonicalRoot().toNode());
        Node marker = new Node()
                .type(reference(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties("documentId", text(preInitializationIdentity));
        expected.getContracts().properties("initialized", marker);
        return expected;
    }

    private static Node expectedAfterEvent(AuditFixture fixture,
                                           Node selectedBefore,
                                           Node event,
                                           boolean handlerPatches) {
        Node expected = selectedBefore.clone();
        Blue normalizationBlue = fixture.newBlue(new AtomicInteger());
        Node normalizedEvent = normalizationBlue.preprocess(event.clone());
        Node checkpoint = new Node()
                .type(reference(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT))
                .properties("lastEvents", new Node().properties("incoming", normalizedEvent));
        expected.getContracts().properties("checkpoint", checkpoint);
        if (handlerPatches) {
            expected.properties("auditRan", bool(true));
        }
        return expected;
    }

    private static boolean hasSelectedContract(Node document, String key) {
        return document != null
                && document.getContracts() != null
                && document.getContracts().getProperties() != null
                && document.getContracts().getProperties().containsKey(key);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static Node text(String value) {
        return new Node().type(reference(TEXT_TYPE_BLUE_ID)).value(value);
    }

    private static Node bool(boolean value) {
        return new Node().type(reference(BOOLEAN_TYPE_BLUE_ID)).value(value);
    }

    private static String firstDifference(Node expected, Node actual) {
        return firstDifference(NodeToMapListOrValue.get(expected), NodeToMapListOrValue.get(actual), "");
    }

    private static String firstDifference(Object expected, Object actual, String path) {
        if (Objects.equals(expected, actual)) {
            return null;
        }
        if (expected instanceof Map && actual instanceof Map) {
            Map<?, ?> expectedMap = (Map<?, ?>) expected;
            Map<?, ?> actualMap = (Map<?, ?>) actual;
            for (Map.Entry<?, ?> entry : expectedMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path + "/" + escape(key);
                if (!actualMap.containsKey(entry.getKey())) {
                    return childPath;
                }
                String child = firstDifference(entry.getValue(), actualMap.get(entry.getKey()), childPath);
                if (child != null) {
                    return child;
                }
            }
            for (Object key : actualMap.keySet()) {
                if (!expectedMap.containsKey(key)) {
                    return path + "/" + escape(String.valueOf(key));
                }
            }
            return rootPath(path);
        }
        if (expected instanceof List && actual instanceof List) {
            List<?> expectedList = (List<?>) expected;
            List<?> actualList = (List<?>) actual;
            int commonSize = Math.min(expectedList.size(), actualList.size());
            for (int index = 0; index < commonSize; index++) {
                String child = firstDifference(expectedList.get(index), actualList.get(index), path + "/" + index);
                if (child != null) {
                    return child;
                }
            }
            return expectedList.size() == actualList.size() ? rootPath(path) : path + "/" + commonSize;
        }
        return rootPath(path);
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static String rootPath(String path) {
        return path.isEmpty() ? "/" : path;
    }

    private interface Transition {
        DocumentProcessingResult apply();
    }

    private static final class Observation {
        private final String label;
        private final Node expectedSelected;
        private final ResolvedSnapshot expectedSnapshot;
        private final DocumentProcessingResult actual;

        private Observation(String label,
                            Node expectedSelected,
                            ResolvedSnapshot expectedSnapshot,
                            DocumentProcessingResult actual) {
            this.label = label;
            this.expectedSelected = expectedSelected;
            this.expectedSnapshot = expectedSnapshot;
            this.actual = actual;
        }

        private void assertThreeViewInvariant() {
            String selectedDifference = firstDifference(expectedSelected, actual.document());
            String canonicalDifference = firstDifference(expectedSnapshot.canonicalRoot(), actual.canonicalDocument());
            String resolvedDifference = firstDifference(expectedSnapshot.resolvedRoot(), actual.resolvedDocument());
            List<String> diagnostics = new ArrayList<>();
            diagnostics.add("selected=" + selectedDifference);
            diagnostics.add("canonical=" + canonicalDifference);
            diagnostics.add("resolved=" + resolvedDifference);
            diagnostics.add("expectedBlueId=" + expectedSnapshot.blueId());
            diagnostics.add("actualBlueId=" + actual.blueId());
            String message = label + " divergence: " + diagnostics;

            assertAll(label,
                    () -> assertNull(selectedDifference, message),
                    () -> assertNull(canonicalDifference, message),
                    () -> assertNull(resolvedDifference, message),
                    () -> assertEquals(expectedSnapshot.blueId(), actual.blueId(), message));
        }
    }
}
