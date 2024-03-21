package blue.lang;

import blue.lang.utils.BlueIdCalculator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.lang.utils.BlueIdCalculator.calculateBlueId;
import static blue.lang.utils.Properties.*;

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
        if (type == null)
            return null;

        if (type.getBlueId() != null)
            return nodeProvider.fetchByBlueId(type.getBlueId());
        return type;
    }

    public static boolean isBasicType(String type) {
        return BASIC_TYPES.contains(type);
    }
}
