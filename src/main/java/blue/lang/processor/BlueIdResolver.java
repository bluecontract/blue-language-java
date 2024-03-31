package blue.lang.processor;

import blue.lang.Node;
import blue.lang.MergingProcessor;
import blue.lang.NodeProvider;
import blue.lang.NodeResolver;

import java.util.List;

public class BlueIdResolver implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        String blueId = source.getBlueId();
        if (blueId == null)
            return;

        List<Node> resolved = nodeProvider.fetchByBlueId(blueId);
        if (resolved == null) {
            target.blueId(blueId);
            return;
        }

        if (resolved.size() == 1) {
            Node element = resolved.get(0);
            source.blueId(null)
                    .name(element.getName())
                    .type(element.getType())
                    .value(element.getValue())
                    .items(element.getItems())
                    .properties(element.getProperties());
        } else {
            source.blueId(null)
                    .items(resolved);
        }


    }
}