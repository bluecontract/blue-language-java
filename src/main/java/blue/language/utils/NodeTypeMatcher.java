package blue.language.utils;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;
import blue.language.utils.limits.PathLimits;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class NodeTypeMatcher {

    private Blue blue;

    public NodeTypeMatcher(Blue blue) {
        this.blue = blue;
    }

    public boolean matchesType(Node node, Node targetType) {
        PathLimits limits = PathLimits.fromNode(targetType);
        return verifyMatch(node, targetType, limits) && recursiveMatchCheck(node, targetType);
    }

    private boolean verifyMatch(Node node, Node type, Limits limits) {
        Node extendedNode = node.clone();
        blue.extend(extendedNode, limits);
        Node resolvedNode = blue.resolve(extendedNode, limits);

        resolvedNode.type(type.clone());
        try {
            blue.resolve(resolvedNode, limits);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return true;
    }

    private boolean recursiveMatchCheck(Node node, Node targetType) {
        if (targetType.getType() != null &&
            (node.getType() == null || !Types.isSubtype(node.getType(), targetType.getType(), blue.getNodeProvider()))) {
            return false;
        }

        if (targetType.getValue() != null && !targetType.getValue().equals(node.getValue())) {
            return false;
        }

        if (targetType.getItems() != null) {
            List<Node> nodeItems = node.getItems() != null ? node.getItems() : Collections.emptyList();
            return IntStream.range(0, targetType.getItems().size())
                    .allMatch(i -> i < nodeItems.size()
                            ? recursiveMatchCheck(nodeItems.get(i), targetType.getItems().get(i))
                            : !hasValueInNestedStructure(targetType.getItems().get(i)));
        }

        if (targetType.getProperties() != null) {
            Map<String, Node> nodeProperties = node.getProperties() != null ? node.getProperties() : Collections.emptyMap();
            return targetType.getProperties().entrySet().stream()
                    .allMatch(entry -> nodeProperties.containsKey(entry.getKey())
                            ? recursiveMatchCheck(nodeProperties.get(entry.getKey()), entry.getValue())
                            : !hasValueInNestedStructure(entry.getValue()));
        }

        return true;
    }

    private boolean hasValueInNestedStructure(Node node) {
        if (node.getValue() != null) {
            return true;
        }

        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                if (hasValueInNestedStructure(item)) {
                    return true;
                }
            }
        }

        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                if (hasValueInNestedStructure(property)) {
                    return true;
                }
            }
        }

        return false;
    }

}