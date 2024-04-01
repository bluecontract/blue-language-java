package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.Node;
import blue.language.MergingProcessor;
import blue.language.NodeResolver;
import blue.language.feature.SupportedTypesFeature;
import blue.language.utils.Features;
import blue.language.utils.Nodes;

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
