package blue.language;

import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Cold-resolution benchmarks for the whole-input BlueId validation boundary.
 * Provider construction and cache clearing happen outside the timed methods.
 */
public class ReferenceBlueIdValidationBenchmark {

    @Benchmark
    public Node resolveWideSchemaFreeDocument(WideDocumentState state) {
        return state.blue.resolve(state.template.clone());
    }

    @Benchmark
    public Node resolveDeepValidReferenceDocument(DeepReferenceState state) {
        return state.blue.resolve(state.template.clone());
    }

    @State(Scope.Thread)
    public static class WideDocumentState {
        private BasicNodeProvider provider;
        private Blue blue;
        private Node template;

        @Setup(Level.Trial)
        public void setUpTrial() {
            provider = new BasicNodeProvider();
            blue = new Blue(provider);
            template = new Node();
            for (int index = 0; index < 1_000; index++) {
                template.properties("field" + index, new Node().value("value-" + index));
            }
        }

        @Setup(Level.Invocation)
        public void clearResolutionState() {
            blue.nodeProvider(provider);
        }
    }

    @State(Scope.Thread)
    public static class DeepReferenceState {
        private BasicNodeProvider provider;
        private Blue blue;
        private Node template;

        @Setup(Level.Trial)
        public void setUpTrial() {
            provider = new BasicNodeProvider();
            template = new Node();
            Node parent = template;
            for (int level = 0; level < 64; level++) {
                String name = "Reference Level " + level;
                provider.addSingleNodes(new Node().name(name)
                        .properties("provided", new Node().value(level)));
                Node child = new Node().type(
                        new Node().blueId(provider.getBlueIdByName(name)));
                parent.properties("level" + level, child);
                parent = child;
            }
            blue = new Blue(provider);
        }

        @Setup(Level.Invocation)
        public void clearResolutionState() {
            blue.nodeProvider(provider);
        }
    }
}
