package blue.lang.processor;

import blue.lang.Resolver;
import blue.lang.Node;
import blue.lang.ref.RefBasedEnricher;
import blue.lang.NodeProcessor;

public class RefPropagator implements NodeProcessor {

    private RefBasedEnricher refBasedEnricher;

    public RefPropagator(RefBasedEnricher refBasedEnricher) {
        this.refBasedEnricher = refBasedEnricher;
    }

    @Override
    public void process(Node target, Node source, Resolver resolver) {
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