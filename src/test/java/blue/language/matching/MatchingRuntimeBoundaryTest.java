package blue.language.matching;

import blue.language.api.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.matching.FrozenTypeMatcher;
import blue.language.matching.NodeTypeMatcher;
import blue.language.utils.limits.Limits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingRuntimeBoundaryTest {

    @Test
    void shouldUseOnlyTheFocusedRuntimeSurfaceForMutableMatching() {
        // given
        RecordingRuntime runtime = new RecordingRuntime();
        NodeTypeMatcher matcher = new NodeTypeMatcher(runtime);
        Node candidate = new Node().value("same");
        Node target = new Node().value("same");

        // when
        boolean matched = matcher.matchesType(candidate, target);

        // then
        assertTrue(matched);
        assertEquals(2, runtime.preprocessCalls);
        assertEquals(1, runtime.expandCalls);
        assertEquals(1, runtime.resolveCalls);
        assertEquals(0, runtime.materializationCalls);
    }

    @Test
    void shouldFailClosedWhenRuntimeTypeMaterializationFails() {
        // given
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.failMaterialization = true;
        FrozenTypeMatcher matcher = new FrozenTypeMatcher(runtime);
        FrozenNode candidate = FrozenNode.fromResolvedNode(new Node()
                .type(reference(typeBlueId("candidate type")))
                .value("candidate"));
        FrozenNode target = FrozenNode.fromResolvedNode(new Node()
                .type(reference(typeBlueId("target type"))));

        // when
        boolean matched = matcher.matchesType(candidate, target);

        // then
        assertFalse(matched);
        assertTrue(runtime.materializationCalls > 0);
    }

    private String typeBlueId(String value) {
        return DirectBlueIdCalculator.calculateBlueId(new Node().value(value));
    }

    private Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class RecordingRuntime implements MatchingRuntime {
        private int preprocessCalls;
        private int expandCalls;
        private int resolveCalls;
        private int materializationCalls;
        private boolean failMaterialization;

        @Override
        public BlueCachePolicy matchingCachePolicy() {
            return BlueCachePolicy.boundedDefaults();
        }

        @Override
        public Node preprocessForMatching(Node source) {
            preprocessCalls++;
            return source;
        }

        @Override
        public void expandForMatching(Node source, Limits limits) {
            expandCalls++;
        }

        @Override
        public Node resolveForMatching(Node source, Limits limits) {
            resolveCalls++;
            return source;
        }

        @Override
        public FrozenNode materializeTypeReferenceForMatching(
                FrozenNode reference) {
            materializationCalls++;
            if (failMaterialization) {
                throw new IllegalArgumentException(
                        "simulated unavailable type evidence");
            }
            return null;
        }
    }
}
