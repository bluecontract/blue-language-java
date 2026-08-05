package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchSequenceRetentionStressTest {

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
    void shouldVerifyLiveReusableSessionDoesNotRetainMostSupersededRoots() {
        // given
        SequentialPatchPlanningSession session = session(initialDocument());
        List<WeakReference<FrozenNode>> superseded = new ArrayList<>();

        // when
        for (int index = 0; index < 96; index++) {
            superseded.add(new WeakReference<>(session.canonicalRoot()));
            session.planNext(JsonPatch.replace("/repeated", replacement(index)));
        }
        int cleared = encourageCollection(superseded, 72);
        Object finalIndex =
                session.resolvedRoot()
                        .at("/repeated/index").getValue();

        // then
        assertTrue(cleared >= 72,
                "a live session retained too many superseded roots: cleared=" + cleared
                        + "/" + superseded.size());
        assertEquals(BigInteger.valueOf(95), finalIndex);
    }

    @Test
    void shouldVerifyRepeatedBoundedSequencesHaveStableFinalIdentity() {
        // given
        List<JsonPatch> patches = stressPatches();

        // when
        List<String> canonicalIds = new ArrayList<>();
        List<String> resolvedIds = new ArrayList<>();
        for (int round = 0; round < 96; round++) {
            SequentialPatchPlanningSession session = session(initialDocument());
            for (JsonPatch patch : patches) {
                session.planNext(patch);
            }
            canonicalIds.add(
                    session.canonicalRoot().blueId());
            resolvedIds.add(
                    session.resolvedRoot().blueId());
        }

        // then
        for (int round = 1; round < 96; round++) {
            assertEquals(canonicalIds.get(0),
                    canonicalIds.get(round),
                    "canonical identity drift at round " + round);
            assertEquals(resolvedIds.get(0),
                    resolvedIds.get(round),
                    "resolved identity drift at round " + round);
        }
    }

    private SequentialPatchPlanningSession session(Node initial) {
        FrozenNode root = FrozenNode.fromResolvedNode(initial);
        return new SequentialPatchPlanningSession("/",
                DocumentProcessingRuntime.workingPlanningContext(root, root, false, null),
                null,
                null,
                NOOP_METRICS);
    }

    private Node initialDocument() {
        return new Node()
                .properties("repeated", replacement(-1))
                .properties("siblings", new Node())
                .properties("stable", new Node().value("retained"));
    }

    private List<JsonPatch> stressPatches() {
        List<JsonPatch> patches = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            if ((index & 1) == 0) {
                patches.add(JsonPatch.replace("/repeated", replacement(index)));
            } else {
                patches.add(JsonPatch.add("/siblings/key" + index, replacement(index)));
            }
        }
        return patches;
    }

    private Node replacement(int index) {
        return new Node()
                .properties("index", new Node().value(index))
                .properties("payload", new Node().value("replacement-" + index));
    }

    private int encourageCollection(List<WeakReference<FrozenNode>> references, int target) {
        int cleared = cleared(references);
        for (int attempt = 0; attempt < 12 && cleared < target; attempt++) {
            byte[][] pressure = new byte[8][];
            for (int index = 0; index < pressure.length; index++) {
                pressure[index] = new byte[512 * 1024];
            }
            pressure = null;
            System.gc();
            System.runFinalization();
            cleared = cleared(references);
        }
        return cleared;
    }

    private int cleared(List<WeakReference<FrozenNode>> references) {
        int cleared = 0;
        for (WeakReference<FrozenNode> reference : references) {
            if (reference.get() == null) {
                cleared++;
            }
        }
        return cleared;
    }
}
