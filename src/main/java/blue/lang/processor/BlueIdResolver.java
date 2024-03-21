package blue.lang.processor;

import blue.lang.Node;
import blue.lang.MergingProcessor;
import blue.lang.NodeProvider;
import blue.lang.NodeResolver;

public class BlueIdResolver implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        String blueId = source.getBlueId();
        if (blueId == null)
            return;

        Node resolved = nodeProvider.fetchByBlueId(blueId);
        if (resolved == null) {
            target.blueId(blueId);
            return;
        }

        source.blueId(null)
                .name(resolved.getName())
                .type(resolved.getType())
                .value(resolved.getValue())
                .items(resolved.getItems())
                .properties(resolved.getProperties());
    }
}