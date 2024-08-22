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

    @FunctionalInterface
    public interface TargetTypeTransformer {
        Node transform(Node targetType);
    }

    public boolean matchesType(Node node, Node targetType) {
        return matchesType(node, targetType, null);
    }

    public boolean matchesType(Node node, Node targetType, TargetTypeTransformer transformer) {
        PathLimits limits = PathLimits.fromNode(targetType);
        Node transformedTargetType = (transformer != null) ? transformer.transform(targetType) : targetType;
        return verifyMatch(node, transformedTargetType, limits) && recursiveMatchCheck(node, transformedTargetType, transformer);
    }

    private boolean verifyMatch(Node node, Node type, Limits limits) {
        blue.extend(node, limits);
        Node extendedNode = node.clone();
        Node resolvedNode = blue.resolve(extendedNode, limits);

        resolvedNode.type(type.clone());
        try {
            blue.resolve(resolvedNode, limits);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return true;
    }

    private boolean recursiveMatchCheck(Node node, Node targetType, TargetTypeTransformer transformer) {
        final Node transformedTargetType = (transformer != null) ? transformer.transform(targetType) : targetType;

        if (transformedTargetType.getType() != null &&
            (node.getType() == null || !Types.isSubtype(node.getType(), transformedTargetType.getType(), blue.getNodeProvider()))) {
            return false;
        }

        if (transformedTargetType.getValue() != null && !transformedTargetType.getValue().equals(node.getValue())) {
            return false;
        }

        if (transformedTargetType.getItems() != null) {
            List<Node> nodeItems = node.getItems() != null ? node.getItems() : Collections.emptyList();
            return IntStream.range(0, transformedTargetType.getItems().size())
                    .allMatch(i -> i < nodeItems.size()
                            ? recursiveMatchCheck(nodeItems.get(i), transformedTargetType.getItems().get(i), transformer)
                            : !hasValueInNestedStructure(transformedTargetType.getItems().get(i)));
        }

        if (transformedTargetType.getProperties() != null) {
            Map<String, Node> nodeProperties = node.getProperties() != null ? node.getProperties() : Collections.emptyMap();
            return transformedTargetType.getProperties().entrySet().stream()
                    .allMatch(entry -> nodeProperties.containsKey(entry.getKey())
                            ? recursiveMatchCheck(nodeProperties.get(entry.getKey()), entry.getValue(), transformer)
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