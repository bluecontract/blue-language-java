package blue.language.processor;

import blue.language.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.NodeResolver;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.Nodes;

import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Collectors;

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
            if (target.getItems() != null) {
                node.items(target.getItems());
            }
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

        if (target.getItems() != null) {
            if (source.getItems() == null) {
                node.items(target.getItems().stream().map(this::forceProcess).collect(Collectors.toList()));
            } else {
                List<Node> result = subtractItems(target.getItems(), source.getItems());
                if (!result.isEmpty()) {
                    node.items(result);
                }
            }
        }

        return node;
    }

    List<Node> subtractItems(List<Node> target, List<Node> source) {
        List<Node> result = new ArrayList<>();
        List<Node> processedTarget = target.stream().map(this::forceProcess).collect(Collectors.toList());
        List<Node> processedSource = source.stream().map(this::forceProcess).collect(Collectors.toList());
        if (processedTarget.size() < processedSource.size()) {
            return processedTarget;
        }
        String blueId = BlueIdCalculator.calculateBlueId(processedSource);
        String targetBlueId = BlueIdCalculator.calculateBlueId(processedTarget);
        if (targetBlueId.equals(blueId)) {
            return result;
        }
        String accumulatedBlueId = null;
        for (int i = 0; i < processedTarget.size(); i++) {
            if (accumulatedBlueId == null) {
                accumulatedBlueId = BlueIdCalculator.calculateBlueId(processedTarget.get(i));
            } else {
                Node n = new Node().blueId(accumulatedBlueId);
                Node rest = new Node().blueId(BlueIdCalculator.calculateBlueId(processedTarget.get(i)));
                accumulatedBlueId = BlueIdCalculator.calculateBlueId(Arrays.asList(n, rest));
            }
            if (accumulatedBlueId.equals(blueId)) {
                Node blueIdNode = new Node().blueId(blueId);
                result.add(blueIdNode);
                result.addAll(target.subList(i + 1, target.size()));
                break;
            }
        }
        return result;
    }
}
