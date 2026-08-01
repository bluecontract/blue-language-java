package blue.language.provider;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Nodes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.identity.DirectBlueIdCalculator.calculateUncheckedBlueId;
import static blue.language.model.wire.BlueLanguageConstants.*;

/**
 * Compatibility helpers for nominal Blue type identity and subtype traversal.
 *
 * <p>Type labels are ignored where identity requires it, while released core
 * types retain their fixed identities. Provider-backed traversal requires each
 * non-core reference to resolve to exactly one type definition.</p>
 */
public class Types {

    private final Map<String, Node> types;

    /**
     * Indexes named type nodes by name.
     *
     * @param nodes type definitions to index; duplicate names are rejected
     */
    public Types(List<? extends Node> nodes) {
        types = nodes.stream()
                .collect(Collectors.toMap(Node::getName, node -> node));
    }

    /**
     * Tests whether one type is identical to or derives from another.
     *
     * @param subtype candidate subtype
     * @param supertype required supertype
     * @param nodeProvider provider used to traverse non-core type references
     * @return {@code true} when the candidate is the same type or a subtype
     */
    public static boolean isSubtype(Node subtype, Node supertype, NodeProvider nodeProvider) {
        if (subtype == null || supertype == null) {
            return false;
        }
        String subtypeBlueId = typeBlueId(subtype);
        String supertypeBlueId = typeBlueId(supertype);
        if (sameType(subtype, supertype, subtypeBlueId, supertypeBlueId))
            return true;
        if (isCoreTypeIdentity(supertype, supertypeBlueId) && isAnonymousCoreAlias(subtype)) {
            return false;
        }

        if (CORE_TYPE_BLUE_IDS.contains(subtypeBlueId)) {
            Node current = supertype;
            while (current != null) {
                String currentBlueId = typeBlueId(current);
                if (sameType(current, subtype, currentBlueId, subtypeBlueId))
                    return true;
                current = getType(current, nodeProvider);
            }
            return false;
        }

        Node current = firstSubtypeTraversalNode(subtype, nodeProvider);
        while (current != null) {
            String blueId = typeBlueId(current);
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
        String leftCompatibility = compatibilityBlueId(left);
        String rightCompatibility = compatibilityBlueId(right);
        if (CORE_TYPE_BLUE_IDS.contains(leftCompatibility) || CORE_TYPE_BLUE_IDS.contains(rightCompatibility)) {
            return leftCompatibility.equals(rightCompatibility);
        }
        return leftCompatibility.equals(rightCompatibility);
    }

    private static boolean isCoreTypeIdentity(Node node, String blueId) {
        return CORE_TYPE_BLUE_IDS.contains(blueId) || isBareCoreTypeName(node);
    }

    private static boolean isAnonymousCoreAlias(Node node) {
        return node.getName() == null
                && node.getDescription() == null
                && node.getBlueId() == null
                && node.getType() != null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getValue() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null
                && CORE_TYPE_BLUE_IDS.contains(typeBlueId(node.getType()));
    }

    private static String typeBlueId(Node node) {
        return node.getBlueId() != null ? node.getBlueId() : calculateUncheckedBlueId(node);
    }

    private static String compatibilityBlueId(Node node) {
        if (node.getBlueId() != null && node.isReferenceOnly()) {
            return node.getBlueId();
        }
        if (node.getBlueId() != null && CORE_TYPE_BLUE_IDS.contains(node.getBlueId())) {
            return node.getBlueId();
        }
        if (isBareCoreTypeName(node)) {
            return CORE_TYPE_NAME_TO_BLUE_ID_MAP.get(node.getName());
        }
        Node stripped = node.clone();
        stripLabels(stripped);
        return calculateUncheckedBlueId(stripped);
    }

    private static boolean isBareCoreTypeName(Node node) {
        return node.getName() != null
                && CORE_TYPE_NAME_TO_BLUE_ID_MAP.containsKey(node.getName())
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getValue() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    private static void stripLabels(Node node) {
        if (node == null) {
            return;
        }
        node.name(null);
        node.description(null);
        if (node.getBlueId() != null && !node.isReferenceOnly()) {
            node.blueId(null);
        }
        stripLabels(node.getType());
        stripLabels(node.getItemType());
        stripLabels(node.getKeyType());
        stripLabels(node.getValueType());
        stripLabels(node.getBlue());
        stripLabels(node.getContracts());
        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                Node item = node.getItems().get(i);
                stripLabels(item);
                if (Nodes.isEmptyNode(item)) {
                    node.getItems().set(i, Nodes.emptyPlaceholder());
                }
            }
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(Types::stripLabels);
        }
        stripSchemaLabels(node.getSchema());
    }

    private static void stripSchemaLabels(blue.language.model.Schema schema) {
        if (schema == null) {
            return;
        }
        stripLabels(schema.getRequired());
        stripLabels(schema.getMinLength());
        stripLabels(schema.getMaxLength());
        stripLabels(schema.getMinimum());
        stripLabels(schema.getMaximum());
        stripLabels(schema.getExclusiveMinimum());
        stripLabels(schema.getExclusiveMaximum());
        stripLabels(schema.getMultipleOf());
        stripLabels(schema.getMinItems());
        stripLabels(schema.getMaxItems());
        stripLabels(schema.getUniqueItems());
        stripLabels(schema.getMinFields());
        stripLabels(schema.getMaxFields());
        if (schema.getEnum() != null) {
            schema.getEnum().forEach(Types::stripLabels);
        }
    }

    /**
     * Tests whether a type resolves to one of the released basic scalar types.
     *
     * @param type type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return {@code true} when the type derives from a basic scalar type
     */
    public static boolean isSubtypeOfBasicType(Node type, NodeProvider nodeProvider) {
        return BASIC_TYPE_BLUE_IDS.stream()
                .map(blueId -> new Node().blueId(blueId))
                .anyMatch(basicTypeNode -> isSubtype(type, basicTypeNode, nodeProvider));
    }

    /**
     * Returns the released basic type name reached by a type chain.
     *
     * @param type type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return released basic type name
     */
    public static String findBasicTypeName(Node type, NodeProvider nodeProvider) {
        return BASIC_TYPE_BLUE_IDS.stream()
                .filter(blueId -> Types.isSubtype(type, new Node().blueId(blueId), nodeProvider))
                .findFirst()
                .map(CORE_TYPE_BLUE_ID_TO_NAME_MAP::get)
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

    /**
     * Tests whether a string is a released basic scalar type name.
     *
     * @param type candidate type name
     * @return {@code true} when the name identifies a basic scalar type
     */
    public static boolean isBasicTypeName(String type) {
        return BASIC_TYPES.contains(type);
    }

    /**
     * Tests whether a node is or derives from a released basic scalar type.
     *
     * @param typeNode type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return {@code true} when the node is a basic scalar type
     */
    public static boolean isBasicType(Node typeNode, NodeProvider nodeProvider) {
        return BASIC_TYPE_BLUE_IDS.stream()
                .map(blueId -> new Node().blueId(blueId))
                .anyMatch(basicTypeNode -> isSubtype(typeNode, basicTypeNode, nodeProvider));
    }

    /**
     * Tests whether a type is or derives from the released Text type.
     *
     * @param typeNode type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return {@code true} when the type is textual
     */
    public static boolean isTextType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(TEXT_TYPE_BLUE_ID), nodeProvider);
    }

    /**
     * Tests whether a type is or derives from the released Number type.
     *
     * @param typeNode type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return {@code true} when the type is numeric
     */
    public static boolean isNumberType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(DOUBLE_TYPE_BLUE_ID), nodeProvider);
    }

    /**
     * Tests whether a type is or derives from the released Integer type.
     *
     * @param typeNode type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return {@code true} when the type is integral
     */
    public static boolean isIntegerType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(INTEGER_TYPE_BLUE_ID), nodeProvider);
    }

    /**
     * Tests whether a type is or derives from the released Boolean type.
     *
     * @param typeNode type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return {@code true} when the type is Boolean
     */
    public static boolean isBooleanType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(BOOLEAN_TYPE_BLUE_ID), nodeProvider);
    }


    /**
     * Tests whether a type is or derives from the released List type.
     *
     * @param typeNode type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return {@code true} when the type is a list
     */
    public static boolean isListType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(LIST_TYPE_BLUE_ID), nodeProvider);
    }

    /**
     * Tests whether a type is or derives from the released Dictionary type.
     *
     * @param typeNode type to inspect
     * @param nodeProvider provider used to traverse its type chain
     * @return {@code true} when the type is a dictionary
     */
    public static boolean isDictionaryType(Node typeNode, NodeProvider nodeProvider) {
        return isSubtype(typeNode, new Node().blueId(DICTIONARY_TYPE_BLUE_ID), nodeProvider);
    }

}
