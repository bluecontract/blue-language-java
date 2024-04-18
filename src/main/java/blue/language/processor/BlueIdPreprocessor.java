package blue.language.processor;

import blue.language.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.NodeResolver;
import blue.language.model.Node;
import blue.language.utils.Nodes;

import java.util.HashMap;
import java.util.Optional;

public class BlueIdPreprocessor {
    NodeProvider nodeProvider;

    NodeResolver nodeResolver;

    public BlueIdPreprocessor(NodeProvider nodeProvider, NodeResolver nodeResolver) {
        this.nodeProvider = nodeProvider;
        this.nodeResolver = nodeResolver;
    }
    public Node process(Node target) {
        String blueId = target.getBlueId();
        if (blueId != null) {
            return target;
        }
        return forceProcess(target);
    }

    private Node forceProcess(Node target) {
        Node node;
        Node typeNode;
        if (target.getType() != null) {
            typeNode = nodeResolver.resolve(target.getType());
            node = subtract(target, typeNode);
            node.type(forceProcess(typeNode));
        } else {
            node = new Node();
            typeNode = null;
            node.value(target.getValue());
        }

        if (target.getName() != null) {
            node.name(target.getName());
        }

        if (target.getDescription() != null) {
            node.description(target.getDescription());
        }

        if (target.getProperties() != null) {
            target.getProperties().forEach((key, value) -> {
                Node processedValue = forceProcess(value);
                if (typeNode != null && typeNode.getProperties() != null && typeNode.getProperties().get(key) != null) {
                    Node subtracted = subtract(processedValue, typeNode.getProperties().get(key));
                    addProperty(node, key, subtracted);
                    return;
                }
                addProperty(node, key, processedValue);
            });
        }

        return node;
    }

    void addProperty(Node node, String key, Node value) {
        if (!Nodes.isEmptyNode(value)) {
            if (node.getProperties() == null) {
                node.properties(new HashMap<>());
            }
            node.getProperties().put(key, value);
        }
    }

    Node subtract(Node target, Node source) {
        Node node = new Node();

        if (target.getValue() != source.getValue()) {
            node.value(target.getValue());
        }


        if (target.getProperties() != null) {
            target.getProperties().forEach((key, value) -> {
                if (source.getProperties() == null || source.getProperties().get(key) == null) {
                    addProperty(node, key, value);
                    return;
                }
                Node processedValue = subtract(value, source.getProperties().get(key));
                addProperty(node, key, processedValue);
            });
        }

        return node;
    }
}
