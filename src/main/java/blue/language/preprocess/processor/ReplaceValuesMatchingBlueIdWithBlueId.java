package blue.language.preprocess.processor;

import blue.language.model.Node;
import blue.language.preprocess.TransformationProcessor;
import blue.language.utils.BlueIds;
import blue.language.utils.NodeTransformer;

public class ReplaceValuesMatchingBlueIdWithBlueId implements TransformationProcessor {
    @Override
    public Node process(Node document) {
        return NodeTransformer.transform(document, this::transformNode);
    }

    private Node transformNode(Node node) {
        if (node.isInlineValue() && node.getValue() != null && node.getValue() instanceof String) {
            String value = (String) node.getValue();
            if (BlueIds.isPotentialBlueId(value)) {
                Node transformedNode = node.clone();
                transformedNode.value(null);
                transformedNode.blueId(value);
                return transformedNode;
            }
        }
        return node;
    }
}