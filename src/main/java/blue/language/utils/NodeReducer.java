package blue.language.utils;

import blue.language.model.Node;
import blue.language.utils.limits.Limits;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class NodeReducer {

    public Node reduce(Node node, Limits limits) {
        Node clonedNode = node.clone();
        reduceNode(clonedNode, limits);
        return clonedNode;
    }

    private void reduceNode(Node currentNode, Limits currentLimits) {
        Map<String, Node> properties = currentNode.getProperties();
        if (properties != null) {
            Set<String> keysToRemove = properties.keySet().stream()
                    .filter(key -> !currentLimits.shouldProcessPathSegment(key))
                    .collect(Collectors.toSet());

            keysToRemove.forEach(key -> {
                Node removedNode = properties.remove(key);
                currentNode.properties(key, new Node().blueId(BlueIdCalculator.calculateBlueId(removedNode)));
            });

            properties.forEach((key, value) -> {
                if (!keysToRemove.contains(key)) {
                    currentLimits.enterPathSegment(key);
                    reduceNode(value, currentLimits);
                    currentLimits.exitPathSegment();
                }
            });
        }

        List<Node> children = currentNode.getItems();
        if (children != null) {
            IntStream.range(0, children.size())
                    .forEach(i -> {
                        currentLimits.enterPathSegment(String.valueOf(i));
                        reduceNode(children.get(i), currentLimits);
                        currentLimits.exitPathSegment();
                    });
        }

        if (currentNode.getType() != null) {
            reduceNode(currentNode.getType(), currentLimits);
        }
    }
}