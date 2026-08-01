package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture;
import blue.language.model.Node;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.DocumentProcessingRuntimeTestAccess;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.MinimizedOverlayBuilder;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingDocumentStateInvariantFailFirstTest {

    @Test
    void shouldPreserveSelectedStateBeforeAnyWriteDuringSnapshotConstruction() {
        // given
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
        DocumentProcessingRuntimeTestAccess.RuntimeSnapshot runtime =
                DocumentProcessingRuntimeTestAccess.snapshot(
                        runtimeOwnedSelection, manager);

        // when
        ResolvedSnapshot snapshot = runtime.snapshot();

        // then
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
    void shouldSatisfyThreeViewInvariantAfterInitializationMarkerInsertion() {
        // given
        AuditFixture fixture = new AuditFixture();
        Node before = fixture.materializedSource();
        Node expected = expectedInitializedSelected(fixture, before);
        Blue executionBlue = fixture.newBlue(new AtomicInteger());

        // when
        Observation observation = observe(fixture,
                "initialization marker",
                executionBlue,
                before,
                expected,
                () -> executionBlue.initializeDocument(before));

        // then
        observation.assertThreeViewInvariant();
    }

    @Test
    void shouldSatisfyThreeViewInvariantAfterCheckpointDirectWritesWithoutHandlerPatch() {
        // given
        AuditFixture fixture = new AuditFixture();
        Node eventA = fixture.auditEvent("A");
        Node before = expectedInitializedSelected(fixture, fixture.materializedSource());
        Node expected = expectedAfterEvent(fixture, before, eventA, false);
        AtomicInteger executions = new AtomicInteger();
        Blue executionBlue = fixture.newBlueWithoutHandlerPatch(executions);

        // when
        Observation observation = observe(fixture,
                "checkpoint Direct Writes without handler patch",
                executionBlue,
                before,
                expected,
                () -> executionBlue.processDocument(before, eventA));

        // then
        assertEquals(1, executions.get());
        observation.assertThreeViewInvariant();
    }

    @Test
    void shouldSatisfyThreeViewInvariantAfterOrdinaryHandlerPatchAndCheckpointDirectWrites() {
        // given
        AuditFixture fixture = new AuditFixture();
        Node eventA = fixture.auditEvent("A");
        Node before = expectedInitializedSelected(fixture, fixture.materializedSource());
        Node expected = expectedAfterEvent(fixture, before, eventA, true);
        AtomicInteger executions = new AtomicInteger();
        Blue executionBlue = fixture.newBlue(executions);

        // when
        Observation observation = observe(fixture,
                "ordinary handler patch and checkpoint Direct Writes",
                executionBlue,
                before,
                expected,
                () -> executionBlue.processDocument(before, eventA));

        // then
        assertEquals(1, executions.get());
        observation.assertThreeViewInvariant();
    }

    @Test
    void shouldSatisfyThreeViewInvariantAfterCombinedInitializationHandlerPatchAndCheckpoint() {
        // given
        AuditFixture fixture = new AuditFixture();
        Node eventA = fixture.auditEvent("A");
        Node before = fixture.materializedSource();
        Node initialized = expectedInitializedSelected(fixture, before);
        Node expected = expectedAfterEvent(fixture, initialized, eventA, true);
        AtomicInteger executions = new AtomicInteger();
        Blue executionBlue = fixture.newBlue(executions);

        // when
        Observation observation = observe(fixture,
                "combined initialization, handler patch, and checkpoint",
                executionBlue,
                before,
                expected,
                () -> executionBlue.processDocument(before, eventA));

        // then
        assertEquals(1, executions.get());
        observation.assertThreeViewInvariant();
    }

    @Test
    void shouldMinimizeCompletedProcessingResultAndReloadWithSameIdentity() {
        // given
        AuditFixture fixture = new AuditFixture();
        Node eventA = fixture.auditEvent("A");
        String eventBlueId = BlueIdCalculator.calculateBlueId(eventA);
        AtomicInteger executions = new AtomicInteger();
        Blue processor = fixture.newBlue(executions);
        // when
        DocumentProcessingResult completed = processor.processDocument(
                fixture.materializedSource(), eventA);
        ResolvedSnapshot completedSnapshot =
                snapshot(processor, completed);
        Node minimized = new MinimizedOverlayBuilder().build(
                completedSnapshot.resolvedRoot());
        Node transported = processor.jsonToNode(processor.nodeToJson(minimized));
        Blue reloader = fixture.newBlue(new AtomicInteger());
        ResolvedSnapshot reloaded = reloader.resolveToSnapshot(transported);

        // then
        assertEquals(ProcessorStatus.SUCCESS, completed.status(), diagnosticMessage(completed));
        assertEquals(1, executions.get());
        assertFalse(hasSelectedContract(completed.document(), "audit"),
                "the committed Root is Canonical, not a fifth materialized selection form");
        assertTrue(hasSelectedContract(
                completedSnapshot.resolvedRoot(), "audit"));
        assertEquals(Boolean.TRUE, completed.document().get("/auditRan"));
        assertEquals(completedSnapshot.blueId(), reloaded.blueId());
        assertNull(firstDifference(
                completedSnapshot.resolvedRoot(),
                reloaded.resolvedRoot()));
        assertEquals(Boolean.TRUE, reloaded.resolvedNodeAt("/auditRan").getValue());
        assertEquals(eventBlueId, reloaded.resolvedNodeAt(
                "/contracts/checkpoint/entries/incoming/subject").getBlueId());
    }

    private static Observation observe(AuditFixture fixture,
                                       String label,
                                       Blue executionBlue,
                                       Node callerInput,
                                       Node expectedSource,
                                       Transition transition) {
        String callerBefore = executionBlue.nodeToJson(callerInput);
        DocumentProcessingResult result = transition.apply();
        assertEquals(callerBefore, executionBlue.nodeToJson(callerInput), label + " mutated caller input");
        assertEquals(ProcessorStatus.SUCCESS, result.status(), label + ": " + diagnosticMessage(result));
        ResolvedSnapshot actualSnapshot =
                snapshot(executionBlue, result);
        assertNotNull(actualSnapshot,
                label + " must retain an out-of-band snapshot");

        Blue verifier = fixture.newBlue(new AtomicInteger());
        ResolvedSnapshot expectedSnapshot = verifier.resolveToSnapshot(expectedSource.clone());
        return new Observation(
                label,
                expectedSnapshot.canonicalRoot(),
                expectedSnapshot,
                result,
                actualSnapshot);
    }

    private static Node expectedInitializedSelected(AuditFixture fixture, Node selectedBefore) {
        Node expected = selectedBefore.clone();
        Blue identityBlue = fixture.newBlue(new AtomicInteger());
        ResolvedSnapshot preInitialization = identityBlue
                .resolveToSnapshot(selectedBefore.clone());
        Node marker = new Node()
                .type(reference(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties(
                        "document",
                        reference(preInitialization.blueId()));
        expected.getContracts().properties("initialized", marker);
        return expected;
    }

    private static Node expectedAfterEvent(AuditFixture fixture,
                                           Node selectedBefore,
                                           Node event,
                                           boolean handlerPatches) {
        Node expected = selectedBefore.clone();
        Node channel = selectedBefore.getContracts().getProperties().get("incoming");
        String contributionBlueId = BlueIdCalculator.calculateBlueId(channel);
        String domainBlueId = CheckpointDomain.derive(
                fixture.channelBlueId,
                Collections.singletonList(contributionBlueId),
                "audit-kind-v1");
        String subjectBlueId = BlueIdCalculator.calculateBlueId(event);
        Node entry = new Node()
                .properties("domain", reference(domainBlueId))
                .properties("subject", reference(subjectBlueId));
        Node checkpoint = new Node()
                .type(reference(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT))
                .properties("entries", new Node().properties("incoming", entry));
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
        return firstDifference(NodeWireForm.get(expected), NodeWireForm.get(actual), "");
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
        private final Node expectedDocument;
        private final ResolvedSnapshot expectedSnapshot;
        private final DocumentProcessingResult actual;
        private final ResolvedSnapshot actualSnapshot;

        private Observation(String label,
                            Node expectedDocument,
                            ResolvedSnapshot expectedSnapshot,
                            DocumentProcessingResult actual,
                            ResolvedSnapshot actualSnapshot) {
            this.label = label;
            this.expectedDocument = expectedDocument;
            this.expectedSnapshot = expectedSnapshot;
            this.actual = actual;
            this.actualSnapshot = actualSnapshot;
        }

        private void assertThreeViewInvariant() {
            String documentDifference = firstDifference(expectedDocument, actual.document());
            String canonicalDifference = firstDifference(expectedSnapshot.canonicalRoot(), actual.document());
            String resolvedDifference = firstDifference(
                    expectedSnapshot.resolvedRoot(),
                    actualSnapshot.resolvedRoot());
            List<String> diagnostics = new ArrayList<>();
            diagnostics.add("document=" + documentDifference);
            diagnostics.add("canonical=" + canonicalDifference);
            diagnostics.add("resolved=" + resolvedDifference);
            diagnostics.add("expectedBlueId=" + expectedSnapshot.blueId());
            diagnostics.add("actualBlueId="
                    + actualSnapshot.blueId());
            String message = label + " divergence: " + diagnostics;

            assertAll(label,
                    () -> assertNull(documentDifference, message),
                    () -> assertNull(canonicalDifference, message),
                    () -> assertNull(resolvedDifference, message),
                    () -> assertEquals(
                            expectedSnapshot.blueId(),
                            actualSnapshot.blueId(),
                            message));
        }
    }
}
