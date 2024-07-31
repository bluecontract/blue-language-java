package blue.language.preprocess.processor;

import blue.language.model.Node;
import blue.language.preprocess.TransformationProcessor;
import blue.language.utils.NodeTransformer;

import java.util.HashMap;
import java.util.Map;

public class ReplaceInlineValuesForTypeAttributesWithImports implements TransformationProcessor {

    private Map<String, String> mappings = new HashMap<>();

    public ReplaceInlineValuesForTypeAttributesWithImports(Node transformation) {
        transformation.getProperties().get("mappings").getProperties().forEach((key, node) ->
                mappings.put(key, (String) node.getValue()));
    }

    @Override
    public Node process(Node document) {
        return NodeTransformer.transform(document, this::transformNode);
    }

    private Node transformNode(Node node) {
        Node transformedNode = node.clone();
        transformTypeField(transformedNode, transformedNode.getType());
        transformTypeField(transformedNode, transformedNode.getItemType());
        transformTypeField(transformedNode, transformedNode.getKeyType());
        transformTypeField(transformedNode, transformedNode.getValueType());
        return transformedNode;
    }

    private void transformTypeField(Node node, Node typeNode) {
        if (typeNode != null && typeNode.isInlineValue() && typeNode.getValue() != null) {
            String typeValue = typeNode.getValue().toString();
            if (mappings.containsKey(typeValue)) {
                String blueId = mappings.get(typeValue);
                Node newTypeNode = new Node().blueId(blueId);
                if (typeNode == node.getType()) {
                    node.type(newTypeNode);
                } else if (typeNode == node.getItemType()) {
                    node.itemType(newTypeNode);
                } else if (typeNode == node.getKeyType()) {
                    node.keyType(newTypeNode);
                } else if (typeNode == node.getValueType()) {
                    node.valueType(newTypeNode);
                }
            }
        }
    }
}