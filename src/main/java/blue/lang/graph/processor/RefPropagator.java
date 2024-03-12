package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.ref.RefBasedEnricher;
import blue.lang.graph.NodeProcessor;

public class RefPropagator implements NodeProcessor {

    private RefBasedEnricher refBasedEnricher;

    public RefPropagator(RefBasedEnricher refBasedEnricher) {
        this.refBasedEnricher = refBasedEnricher;
    }

    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        String ref = source.getRef();
        if (ref == null)
            return;
        boolean success = refBasedEnricher.enrich(source, ref);
        if (!success) {
            String errorMessage = String.format("Couldn't find any content to enrich the node for ref='%s'.", ref);
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
