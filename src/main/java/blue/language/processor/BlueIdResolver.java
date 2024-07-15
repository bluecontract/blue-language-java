package blue.language.processor;

import blue.language.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.NodeResolver;
import blue.language.model.Node;

public class BlueIdResolver implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        String blueId = source.getBlueId();
        if (blueId != null)
            target.blueId(blueId);
    }
}