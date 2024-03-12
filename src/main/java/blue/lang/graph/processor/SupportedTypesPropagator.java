package blue.lang.graph.processor;

import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;
import blue.lang.graph.feature.SupportedTypesFeature;
import blue.lang.graph.utils.Features;
import blue.lang.graph.utils.Nodes;

import java.util.HashMap;
import java.util.Map;

public class SupportedTypesPropagator implements NodeProcessor {
    @Override
    public void process(Node target, Node source, NodeManager nodeManager) {
        SupportedTypesFeature sourceFeature = Features.getFeature(source, SupportedTypesFeature.class);
        if (sourceFeature == null)
            return;

        SupportedTypesFeature targetFeature = Features.getFeature(target, SupportedTypesFeature.class);
        if (targetFeature == null) {
            Nodes.addFeature(target, new SupportedTypesFeature(new HashMap<>(sourceFeature.getTypeToHash())));
        } else {
            Map<String, String> sourceTypeToHash = sourceFeature.getTypeToHash();
            Map<String, String> targetTypeToHash = targetFeature.getTypeToHash();

            sourceTypeToHash.forEach((type, hash) -> {
                String targetHash = targetTypeToHash.get(type);
                if (targetHash != null && !targetHash.equals(hash)) {
                    throw new RuntimeException("Hash mismatch found for type: " + type);
                }
            });

            targetTypeToHash.putAll(sourceTypeToHash);
        }
    }
}
