package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PatchPlanningEngineCollectionTest {

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
    void shouldAllowWholeCollectionGeneratedChildRemovalToDropItsHistory() {
        // given
        Node root = rootWithCollectionMember(
                childWithCheckpoint("before"));
        EmbeddedScopePlan entryPlan = embeddedScopePlan(root);
        PatchPlanningContext planning = planningContext(root, entryPlan);
        SequentialPatchPlanningSession session =
                new SequentialPatchPlanningSession(
                        "/",
                        planning,
                        null,
                        null,
                        NOOP_METRICS);

        // when
        SequentialPatchPlanningSession.PlannedStep result =
                session.planNext(
                        JsonPatch.remove("/lessons/lesson-a"));

        // then
        assertNull(result.result().resolvedRoot()
                .at("/lessons/lesson-a"));
    }

    @Test
    void shouldNotReopenAddedCollectionMemberForWholeChildStateRemoval() {
        // given
        Node entryRoot = rootWithCollectionMember(new Node());
        EmbeddedScopePlan entryPlan = embeddedScopePlan(entryRoot);
        SequentialPatchPlanningSession rootSession =
                new SequentialPatchPlanningSession(
                        "/",
                        planningContext(entryRoot, entryPlan),
                        null,
                        null,
                        NOOP_METRICS);
        SequentialPatchPlanningSession.PlannedStep added =
                rootSession.planNext(JsonPatch.add(
                        "/lessons/lesson-added",
                        childWithApplicationContract()));
        SequentialPatchPlanningSession childSession =
                new SequentialPatchPlanningSession(
                        "/lessons/lesson-added",
                        planningContext(
                                added.result().canonicalRoot(),
                                added.result().resolvedRoot(),
                                entryPlan),
                        null,
                        null,
                        NOOP_METRICS);
        SequentialPatchPlanningSession.PlannedStep initialized =
                childSession.planNext(JsonPatch.add(
                        "/lessons/lesson-added"
                                + ProcessorPointerConstants
                                .RELATIVE_INITIALIZED,
                        new Node().value("processor-state")));
        rootSession.rebase(
                initialized.result().canonicalRoot(),
                initialized.result().resolvedRoot());

        // when
        SequentialPatchPlanningSession.PlannedStep removed =
                rootSession.planNext(
                        JsonPatch.remove("/lessons/lesson-added"));

        // then
        assertNull(removed.result().resolvedRoot()
                .at("/lessons/lesson-added"));
    }

    private static PatchPlanningContext planningContext(
            Node currentRoot,
            EmbeddedScopePlan entryPlan) {
        return planningContext(
                FrozenNode.fromNode(currentRoot),
                FrozenNode.fromResolvedNode(currentRoot),
                entryPlan);
    }

    private static PatchPlanningContext planningContext(
            FrozenNode canonical,
            FrozenNode resolved,
            EmbeddedScopePlan entryPlan) {
        return DocumentProcessingRuntime.workingPlanningContext(
                canonical,
                resolved,
                false,
                null,
                Collections.singletonMap("/", entryPlan));
    }

    private static EmbeddedScopePlan embeddedScopePlan(Node root) {
        return ProcessingSnapshotBootstrap.embeddedScopePlan(
                FrozenNode.fromResolvedNode(root), "/", null);
    }

    private static Node rootWithCollectionMember(Node child) {
        return rootWithCollectionMembers(
                Collections.singletonMap("lesson-a", child));
    }

    private static Node rootWithCollectionMembers(
            Map<String, Node> members) {
        Node embedded = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "collectionPaths",
                        new Node().items(
                                new Node().value("/lessons")));
        return new Node()
                .contracts(new Node().properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        embedded))
                .properties(
                        "lessons",
                        new Node().properties(members));
    }

    private static Node childWithCheckpoint(String value) {
        return new Node().contracts(
                new Node().properties(
                        "checkpoint",
                        new Node().properties(
                                "value", new Node().value(value))));
    }

    private static Node childWithApplicationContract() {
        return new Node().contracts(
                new Node().properties(
                        "application",
                        new Node().value(true)));
    }
}
