package blue.language;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.provider.BasicNodeProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Processing-snapshot initialization benchmarks around the configured provider
 * fallback. Invocation setup clears Blue's resolution caches outside the timed
 * method so both cases consistently measure a cold processing snapshot.
 */
public class ProcessingSnapshotProviderBenchmark {

    @Benchmark
    public DocumentProcessingResult schemaFreeInitialization(SchemaFreeState state) {
        return state.blue.initializeDocument(state.template.clone());
    }

    @Benchmark
    public DocumentProcessingResult exactOrdinaryProviderInitialization(ExactProviderState state) {
        return state.blue.initializeDocument(state.template.clone());
    }

    @State(Scope.Thread)
    public static class SchemaFreeState {
        private BasicNodeProvider provider;
        private Blue blue;
        private Node template;

        @Setup(Level.Trial)
        public void setUpTrial() {
            provider = new BasicNodeProvider();
            blue = new Blue(provider);
            template = document("schema-free");
        }

        @Setup(Level.Invocation)
        public void clearResolutionCaches() {
            blue.nodeProvider(provider);
        }
    }

    @State(Scope.Thread)
    public static class ExactProviderState {
        private BasicNodeProvider provider;
        private Blue blue;
        private Node template;

        @Setup(Level.Trial)
        public void setUpTrial() {
            provider = new BasicNodeProvider();
            provider.addSingleNodes(new Node().name("Exact Ordinary Provider Type")
                    .properties("provided", new Node().value("exact")));
            String typeBlueId = provider.getBlueIdByName("Exact Ordinary Provider Type");
            blue = new Blue(provider);
            template = document("ordinary-provider")
                    .type(new Node().blueId(typeBlueId));
        }

        @Setup(Level.Invocation)
        public void clearResolutionCaches() {
            blue.nodeProvider(provider);
        }
    }

    private static Node document(String name) {
        return new Node().name(name)
                .properties("payload", new Node().value("benchmark"))
                .contracts(new Node());
    }
}
