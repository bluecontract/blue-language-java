package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.NodeResolver;
import blue.lang.ref.RefBasedEnricher;
import blue.lang.MergingProcessor;

public class RefPropagator implements MergingProcessor {

    private RefBasedEnricher refBasedEnricher;

    public RefPropagator(RefBasedEnricher refBasedEnricher) {
        this.refBasedEnricher = refBasedEnricher;
    }

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
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
