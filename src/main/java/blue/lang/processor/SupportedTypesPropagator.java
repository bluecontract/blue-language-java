package blue.lang.processor;

import blue.lang.NodeProvider;
import blue.lang.Node;
import blue.lang.MergingProcessor;
import blue.lang.NodeResolver;
import blue.lang.feature.SupportedTypesFeature;
import blue.lang.utils.Features;
import blue.lang.utils.Nodes;

import java.util.HashMap;
import java.util.Map;

public class SupportedTypesPropagator implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
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