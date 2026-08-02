package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Comparison of standalone and reusable sequential planning, with the public
 * atomic runtime batch retained as the end-to-end throughput anchor.
 *
 * <p>Immutable patch fixtures and frozen planning roots are prepared once per
 * trial. The two sequential lanes measure planning only. A fresh mutable
 * runtime is created outside each timed invocation for the public atomic lane,
 * so fixture construction and document cloning are not charged to any path.</p>
 */
public class PatchSequenceBenchmark {

    private static final UpdateMaterializationMetrics NOOP_METRICS =
            new UpdateMaterializationMetrics() {
                @Override
                public void recordBeforeNodeMaterialization() {
                }

                @Override
                public void recordAfterNodeMaterialization() {
                }
            };

    @Benchmark
    public FrozenNode standaloneSingletonPlanning(SequenceState state) {
        FrozenNode canonical = state.initialFrozen;
        FrozenNode resolved = state.initialFrozen;
        for (JsonPatch patch : state.patches) {
            PatchPlanningContext planning =
                    DocumentProcessingRuntime.workingPlanningContext(
                            canonical, resolved, false, null);
            BatchPatchResult result = new BatchPatchTransaction("/",
                    Collections.singletonList(patch),
                    planning,
                    null,
                    null,
                    NOOP_METRICS,
                    false).apply();
            canonical = result.canonicalRoot();
            resolved = result.resolvedRoot();
        }
        return resolved;
    }

    @Benchmark
    public FrozenNode reusableSequentialPlanningSession(SequenceState state) {
        SequentialPatchPlanningSession session = new SequentialPatchPlanningSession("/",
                DocumentProcessingRuntime.workingPlanningContext(
                        state.initialFrozen, state.initialFrozen, false, null),
                null,
                null,
                NOOP_METRICS);
        for (JsonPatch patch : state.patches) {
            session.planNext(patch);
        }
        return session.resolvedRoot();
    }

    @Benchmark
    public Node publicAtomicBatch(SequenceState state) {
        state.runtime.applyPatches("/", state.patches);
        return state.runtime.document();
    }

    @State(Scope.Thread)
    public static class SequenceState {

        @Param({"1", "8", "64", "128"})
        public int patchCount;

        @Param({"medium-sibling", "medium-repeated", "deep-sibling", "deep-repeated"})
        public String pattern;

        private Node initial;
        private FrozenNode initialFrozen;
        private List<JsonPatch> patches;
        private DocumentProcessingRuntime runtime;

        @Setup(Level.Trial)
        public void prepareFixture() {
            Fixture fixture = fixture(patchCount, pattern);
            initial = fixture.initial;
            initialFrozen = FrozenNode.fromResolvedNode(initial.clone());
            patches = fixture.patches;
        }

        @Setup(Level.Invocation)
        public void prepareRuntime() {
            runtime = new DocumentProcessingRuntime(initial.clone());
        }
    }

    private static Fixture fixture(int patchCount, String pattern) {
        boolean deep = pattern.startsWith("deep-");
        boolean sibling = pattern.endsWith("-sibling");
        int depth = deep ? 24 : 4;
        Node root = new Node();
        Node cursor = root;
        StringBuilder parentPath = new StringBuilder();
        for (int level = 0; level < depth; level++) {
            String segment = "level" + level;
            Node child = new Node();
            cursor.properties(segment, child);
            cursor = child;
            parentPath.append('/').append(segment);
        }

        List<JsonPatch> patches = new ArrayList<>(patchCount);
        if (sibling) {
            for (int index = 0; index < patchCount; index++) {
                patches.add(JsonPatch.add(parentPath + "/field" + index, value(index)));
            }
        } else {
            cursor.properties("value", value(-1));
            String path = parentPath + "/value";
            for (int index = 0; index < patchCount; index++) {
                patches.add(JsonPatch.replace(path, value(index)));
            }
        }
        return new Fixture(root, Collections.unmodifiableList(patches));
    }

    private static Node value(int index) {
        return new Node()
                .properties("ordinal", new Node().value(index))
                .properties("payload", new Node().value("patch-" + index));
    }

    private static final class Fixture {
        private final Node initial;
        private final List<JsonPatch> patches;

        private Fixture(Node initial, List<JsonPatch> patches) {
            this.initial = initial;
            this.patches = patches;
        }
    }
}
