package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.Properties.BASIC_TYPES;

public class Types {

    private final Map<String, Node> types;

    public Types(List<? extends Node> nodes) {
        types = nodes.stream()
                .collect(Collectors.toMap(Node::getName, node -> node));
    }

    public static boolean isSubtype(Node subtype, Node supertype, NodeProvider nodeProvider) {
        String subtypeBlueId = calculateBlueId(subtype);
        String supertypeBlueId = calculateBlueId(supertype);
        if (subtypeBlueId.equals(supertypeBlueId))
            return true;

        Node current = getType(subtype, nodeProvider);
        while (current != null) {
            String blueId = calculateBlueId(current);
            if (blueId.equals(supertypeBlueId))
                return true;
            current = getType(current, nodeProvider);
        }
        return false;
    }

    private static Node getType(Node node, NodeProvider nodeProvider) {
        Node type = node.getType();
        if (type == null) {
            return null;
        }

        if (type.getBlueId() != null) {
            List<Node> typeNodes = nodeProvider.fetchByBlueId(type.getBlueId());
            if (typeNodes == null || typeNodes.isEmpty())
                return null;
            if (typeNodes.size() > 1)
                throw new IllegalStateException(String.format(
                        "Expected a single node for type with blueId '%s', but found multiple.",
                        type.getBlueId()
                ));
            return typeNodes.get(0);
        }
        
        return type;
    }

    public static boolean isBasicType(String type) {
        return BASIC_TYPES.contains(type);
    }
}
