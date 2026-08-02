package blue.language.processor;

import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class SequentialPatchPlanningSessionTest {

    private static final UpdateMaterializationMetrics NOOP_METRICS =
            new UpdateMaterializationMetrics() {
                @Override
                public void recordBeforeNodeMaterialization() {
                }

                @Override
                public void recordAfterNodeMaterialization() {
                }
            };

    @Test
    void shouldFinishConformanceAfterEachSequentialPatch() {
        // given
        Node initial = typedRoot();
        RecordingConformanceOverride sequentialOverride = new RecordingConformanceOverride();
        SequentialPatchPlanningSession session = session(initial, sequentialOverride);

        // when
        session.planNext(JsonPatch.add("/a", new Node().value("one")));
        session.planNext(JsonPatch.add("/b", new Node().value("two")));

        // then
        assertEquals(2, sequentialOverride.seenRoots.size());
        assertEquals("one", sequentialOverride.seenRoots.get(1).getAsText("/a"));
        assertEquals("two", session.resolvedRoot().at("/b").getValue());
    }

    @Test
    void shouldFinishConformanceOnceForAnAtomicPatchBatch() {
        // given
        Node initial = typedRoot();
        RecordingConformanceOverride atomicOverride = new RecordingConformanceOverride();
        PatchPlanningContext atomicPlanning = planning(initial);

        // when
        new BatchPatchTransaction("/",
                Arrays.asList(
                        JsonPatch.add("/a", new Node().value("one")),
                        JsonPatch.add("/b", new Node().value("two"))),
                atomicPlanning,
                null,
                atomicOverride,
                NOOP_METRICS,
                false).apply();

        // then
        assertEquals(1, atomicOverride.seenRoots.size());
        assertEquals("one", atomicOverride.seenRoots.get(0).getAsText("/a"));
        assertEquals("two", atomicOverride.seenRoots.get(0).getAsText("/b"));
    }

    @Test
    void shouldVerifyFailedStepDoesNotAdvanceReusableSession() {
        // given
        Node initial = new Node().properties("status", new Node().value("idle"));
        SequentialPatchPlanningSession session = session(initial, null);
        session.planNext(JsonPatch.replace("/status", new Node().value("active")));
        FrozenNode canonicalAfterFirst = session.canonicalRoot();
        FrozenNode resolvedAfterFirst = session.resolvedRoot();

        // when
        Throwable failure = captureFailure(
                () -> session.planNext(
                        JsonPatch.remove("/missing")));

        // then
        assertInstanceOf(IllegalStateException.class, failure);
        assertSame(canonicalAfterFirst, session.canonicalRoot());
        assertSame(resolvedAfterFirst, session.resolvedRoot());
        assertEquals("active", session.resolvedRoot().at("/status").getValue());
    }

    @Test
    void shouldVerifyRebaseMakesTheObservedRuntimeRootsTheNextStepBase() {
        // given
        Node initial = new Node().properties("status", new Node().value("idle"));
        SequentialPatchPlanningSession session = session(initial, null);
        SequentialPatchPlanningSession.PlannedStep first =
                session.planNext(JsonPatch.replace("/status", new Node().value("active")));
        FrozenNode actualCanonical = ImmutablePatchPlanner.forFrozen(first.result().canonicalRoot())
                .plan("/", JsonPatch.add("/handlerWrite", new Node().value(true)))
                .root();
        FrozenNode actualResolved = ImmutablePatchPlanner.forFrozen(first.result().resolvedRoot())
                .plan("/", JsonPatch.add("/handlerWrite", new Node().value(true)))
                .root();

        // when
        session.rebase(actualCanonical, actualResolved);
        SequentialPatchPlanningSession.PlannedStep second =
                session.planNext(JsonPatch.add("/tail", new Node().value("kept")));

        // then
        assertSame(actualCanonical, second.baseCanonical());
        assertSame(actualResolved, second.baseResolved());
        assertEquals(true, second.result().resolvedRoot().at("/handlerWrite").getValue());
        assertEquals("kept", second.result().resolvedRoot().at("/tail").getValue());
    }

    @Test
    void shouldVerifyWorkingDocumentRestoresItsReusableSessionAfterLaterPreviewFailure() {
        // given
        Node document = new Node().properties("status", new Node().value("idle"));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document);
        WorkingDocument working = runtime.workingDocument("/");
        working.applyPatch(JsonPatch.replace("/status", new Node().value("active")));
        FrozenNode afterSuccessfulPrefix = working.canonicalRoot();

        // when
        Throwable failure = captureFailure(
                () -> working.applyPatches(Arrays.asList(
                        JsonPatch.replace(
                                "/status",
                                new Node().value("uncommitted")),
                        JsonPatch.remove("/missing"))));
        FrozenNode afterFailedBatch = working.canonicalRoot();
        WorkingDocument.Preview recovered =
                working.previewAndApplyPatches(Arrays.asList(
                        JsonPatch.replace(
                                "/status",
                                new Node().value("recovered"))));

        // then
        assertInstanceOf(IllegalStateException.class, failure);
        assertSame(afterSuccessfulPrefix, afterFailedBatch);
        assertSame(afterSuccessfulPrefix, recovered.patch(0).baseCanonical());
        assertEquals("recovered", working.resolvedAt("/status").getValue());
        assertEquals("idle", document.getAsText("/status"));
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private SequentialPatchPlanningSession session(Node root,
                                                   ConformancePlannerOverride conformanceOverride) {
        return new SequentialPatchPlanningSession("/",
                planning(root),
                null,
                conformanceOverride,
                NOOP_METRICS);
    }

    private PatchPlanningContext planning(Node root) {
        FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(root.clone());
        FrozenNode resolved = FrozenNode.fromResolvedNode(root.clone());
        return DocumentProcessingRuntime.workingPlanningContext(canonical,
                resolved,
                false,
                null);
    }

    private Node typedRoot() {
        return new Node()
                .type(new Node().blueId(ProcessorTestTypeBlueIds.LEGACY_BLUE_ID_TYPE))
                .properties("seed", new Node().value("value"));
    }

    private static final class RecordingConformanceOverride implements ConformancePlannerOverride {
        private final List<Node> seenRoots = new ArrayList<>();

        @Override
        public boolean applies() {
            return true;
        }

        @Override
        public ConformancePlan plan(FrozenNode canonicalRoot,
                                    FrozenNode resolvedRoot,
                                    List<ConformanceChangedPath> changedPaths) {
            seenRoots.add(resolvedRoot.toNode());
            return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
        }
    }
}
