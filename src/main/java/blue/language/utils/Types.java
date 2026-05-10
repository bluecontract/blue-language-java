package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.Properties.*;

public class Types {

    private final Map<String, Node> types;

    public Types(List<? extends Node> nodes) {
        types = nodes.stream()
                .collect(Collectors.toMap(Node::getName, node -> node));
    }

    public static boolean isSubtype(Node subtype, Node supertype, NodeProvider nodeProvider) {
        if (subtype == null || supertype == null) {
            return false;
        }
        String subtypeBlueId = calculateBlueId(subtype);
        String supertypeBlueId = calculateBlueId(supertype);
        if (sameType(subtype, supertype, subtypeBlueId, supertypeBlueId))
            return true;

        if (CORE_TYPE_BLUE_IDS.contains(subtypeBlueId)) {
            Node current = supertype;
            while (current != null) {
                String currentBlueId = calculateBlueId(current);
                if (sameType(current, subtype, currentBlueId, subtypeBlueId))
                    return true;
                current = getType(current, nodeProvider);
            }
            return false;
        }

        Node current = firstSubtypeTraversalNode(subtype, nodeProvider);
        while (current != null) {
            String blueId = calculateBlueId(current);
            if (sameType(current, supertype, blueId, supertypeBlueId))
                return true;
            current = getType(current, nodeProvider);
        }
        return false;
    }

    private static Node firstSubtypeTraversalNode(Node subtype, NodeProvider nodeProvider) {
        if (subtype.getBlueId() != null && subtype.isReferenceOnly() && !CORE_TYPE_BLUE_IDS.contains(subtype.getBlueId())) {
            List<Node> referencedNodes = nodeProvider.fetchByBlueId(subtype.getBlueId());
            if (referencedNodes == null || referencedNodes.isEmpty()) {
                return null;
            }
            if (referencedNodes.size() > 1) {
                throw new IllegalStateException(String.format(
                        "Expected a single node for type with blueId '%s', but found multiple.",
                        subtype.getBlueId()
                ));
            }
            return referencedNodes.get(0);
        }
        return getType(subtype, nodeProvider);
    }

    private static boolean sameType(Node left, Node right, String leftBlueId, String rightBlueId) {
        if (left.getBlueId() != null && left.getBlueId().equals(right.getBlueId())) {
            return true;
        }
        if (left.getBlueId() != null && left.getBlueId().equals(rightBlueId)) {
            return true;
        }
        if (right.getBlueId() != null && right.getBlueId().equals(leftBlueId)) {
            return true;
        }
        if (leftBlueId.equals(rightBlueId)) {
            return true;
        }
        return left.getName() != null && left.getName().equals(right.getName());
    }

    public static boolean isSubtypeOfBasicType(Node type, NodeProvider nodeProvider) {
        return BASIC_TYPES.stream()
                .map(basicTypeName -> new Node().name(basicTypeName))
                .anyMatch(basicTypeNode -> isSubtype(type, basicTypeNode, nodeProvider));
    }

    public static String findBasicTypeName(Node type, NodeProvider nodeProvider) {
        return BASIC_TYPES.stream()
                .map(basicTypeName -> new Node().name(basicTypeName))
                .filter(basicTypeNode -> Types.isSubtype(type, basicTypeNode, nodeProvider))
                .findFirst()
                .map(Node::getName)
                .orElseThrow(() -> new IllegalArgumentException("Cannot determine the basic type for node of type \"" + type.getName() + "\"."));
    }

    private static Node getType(Node node, NodeProvider nodeProvider) {
        Node type = node.getType();
        if (type == null) {
            return null;
        }

        if (type.getBlueId() != null) {
            if (!type.isReferenceOnly()) {
                return type;
            }
            if (CORE_TYPE_BLUE_IDS.contains(type.getBlueId())) {
                return new Node().blueId(type.getBlueId());
            }
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

    public static boolean isBasicTypeName(String type) {
        return BASIC_TYPES.contains(type);
    }

    public static boolean isBasicType(Node typeNode, NodeProvider nodeProvider) {
        return BASIC_TYPE_BLUE_IDS.stream()
                .map(blueId -> new Node().blueId(blueId))
                .anyMatch(basicTypeNode -> isSubtype(typeNode, basicTypeNode, nodeProvider));
    }

    public static boolean isTextType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(TEXT_TYPE_BLUE_ID), nodeProvider);
    }

    public static boolean isNumberType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(DOUBLE_TYPE_BLUE_ID), nodeProvider);
    }

    public static boolean isIntegerType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(INTEGER_TYPE_BLUE_ID), nodeProvider);
    }

    public static boolean isBooleanType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(BOOLEAN_TYPE_BLUE_ID), nodeProvider);
    }


    public static boolean isListType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(LIST_TYPE_BLUE_ID), nodeProvider);
    }

    public static boolean isDictionaryType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(DICTIONARY_TYPE_BLUE_ID), nodeProvider);
    }

}
