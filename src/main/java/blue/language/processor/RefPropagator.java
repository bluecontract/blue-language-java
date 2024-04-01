package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.Node;
import blue.language.NodeResolver;
import blue.language.ref.RefBasedEnricher;
import blue.language.MergingProcessor;

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
