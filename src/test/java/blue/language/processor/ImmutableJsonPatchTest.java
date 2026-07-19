package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmutableJsonPatchTest {

    @Test
    void freezesValueAndParsesPointerOnceAtSequenceBoundary() {
        FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(new Node());
        FrozenNode resolved = FrozenNode.fromResolvedNode(new Node());
        Node mutable = new Node().properties("nested", new Node().value("before"));
        RecordingMetrics metrics = new RecordingMetrics();
        ImmutableJsonPatch.PreparationContext context =
                ImmutableJsonPatch.preparationContext(metrics);

        ImmutableJsonPatch first = context.prepare(JsonPatch.add("/a/~0key", mutable), canonical, resolved);
        mutable.getProperties().get("nested").value("after");
        ImmutableJsonPatch second = context.prepare(
                JsonPatch.add("/a/~0key", new Node().value("other")), canonical, resolved);

        assertEquals("/a/~0key", first.normalizedPath());
        assertEquals("before", first.canonicalValue().property("nested").getValue());
        assertEquals(1, metrics.pointerMisses);
        assertEquals(1, metrics.pointerHits);
        assertTrue(!first.matches(second));
    }

    @Test
    void reusesFrozenValueWhenCanonicalAndResolvedModesAreTheSame() {
        FrozenNode root = FrozenNode.fromResolvedNode(new Node());
        RecordingMetrics metrics = new RecordingMetrics();

        ImmutableJsonPatch patch = ImmutableJsonPatch.preparationContext(metrics)
                .prepare(JsonPatch.add("/x", new Node().value(1)), root, root);

        assertSame(patch.canonicalValue(), patch.resolvedValue());
        assertEquals(1, metrics.frozenValueHits);
    }

    @Test
    void preparedPlannerMatchesLegacyPlannerAndReusesUnchangedSubtree() {
        Node input = new Node().properties(
                "left", new Node().properties("value", new Node().value(1)),
                "right", new Node().properties("value", new Node().value(2)));
        FrozenNode root = FrozenNode.fromResolvedNode(input);
        JsonPatch authored = JsonPatch.replace("/left/value", new Node().value(3));
        ImmutableJsonPatch prepared = ImmutableJsonPatch.from(authored, root, root);

        ImmutablePatchPlanner.PatchPlan legacy = ImmutablePatchPlanner.forFrozen(root).plan("/", authored);
        ImmutablePatchPlanner.PatchPlan optimized = ImmutablePatchPlanner.forFrozen(root).plan("/", prepared);

        assertEquals(legacy.root().blueId(), optimized.root().blueId());
        assertEquals(legacy.root().resolvedStructuralKey(), optimized.root().resolvedStructuralKey());
        assertNotSame(root.property("left"), optimized.root().property("left"));
        assertSame(root.property("right"), optimized.root().property("right"));
    }

    @Test
    void sequencePointerCacheIsBounded() {
        FrozenNode root = FrozenNode.fromResolvedNode(new Node());
        ImmutableJsonPatch.PreparationContext context =
                ImmutableJsonPatch.preparationContext(ProcessingMetricsSink.NOOP);

        for (int index = 0; index < 1_024; index++) {
            context.prepare(JsonPatch.remove("/distinct/" + index), root, root);
        }

        assertEquals(256, context.cachedPointerCount());
    }

    private static final class RecordingMetrics implements ProcessingMetricsSink {
        private long pointerHits;
        private long pointerMisses;
        private long frozenValueHits;

        @Override
        public void incrementParsedPointerCacheHits() {
            pointerHits++;
        }

        @Override
        public void incrementParsedPointerCacheMisses() {
            pointerMisses++;
        }

        @Override
        public void incrementFrozenPatchValueHits() {
            frozenValueHits++;
        }
    }
}
