package blue.language;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.DocumentProcessingResult;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.merge.ResolvedSnapshot;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class ProcessingSelectionCacheBenchmark {

    private Blue blue;
    private Node selected;
    private Node resolvedSelected;
    private ResolvedSnapshot snapshot;
    private Node event;

    @Setup
    public void setUp() {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node wideType = new Node().name("Wide Processing Selection Type");
        for (int index = 0; index < 128; index++) {
            wideType.properties("field" + index, new Node().value(index));
        }
        provider.addSingleNodes(wideType);

        blue = new Blue(provider);
        Node compact = new Node()
                .type(new Node().blueId(provider.getBlueIdByName("Wide Processing Selection Type")))
                .contracts(Nodes.emptyObject());
        DocumentProcessingResult initialized = blue.initializeDocument(compact);
        selected = initialized.document();
        snapshot = blue.loadSnapshot(selected);
        resolvedSelected = snapshot.resolvedRoot();
        event = new Node().properties("kind", new Node().value("noop"));
    }

    @Benchmark
    public DocumentProcessingResult processWarmSameNode() {
        return blue.processDocument(selected, event);
    }

    @Benchmark
    public DocumentProcessingResult processWarmClone() {
        return blue.processDocument(selected.clone(), event);
    }

    @Benchmark
    public DocumentProcessingResult processResolvedSnapshot() {
        return blue.processDocument(snapshot, event);
    }

    @Benchmark
    public DocumentProcessingResult processResolvedRootNode() {
        return blue.processDocument(resolvedSelected, event);
    }
}
