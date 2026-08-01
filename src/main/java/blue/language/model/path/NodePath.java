package blue.language.model.path;

import blue.language.model.Node;
import blue.language.model.NodeIdentities;
import blue.language.model.wire.JsonPointer;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static blue.language.model.wire.BlueLanguageConstants.*;

/** Pure model traversal behind {@link Node}'s compatibility path methods. */
public final class NodePath {

    private NodePath() {
    }

    public static Object get(Node node, String path) {
        return get(node, path, null);
    }

    public static Object get(
            Node node,
            String path,
            Function<Node, Node> linkingProvider) {
        return get(node, path, linkingProvider, true);
    }

    public static Object get(
            Node node,
            String path,
            Function<Node, Node> linkingProvider,
            boolean resolveFinalLink) {
        requireAbsolute(path);
        if (JsonPointer.ROOT.equals(path)) {
            return node.getValue() != null ? node.getValue() : node;
        }
        return getRecursive(node, JsonPointer.split(path), 0,
                linkingProvider, resolveFinalLink);
    }

    public static Node getNode(Node node, String path) {
        requireAbsolute(path);
        if (JsonPointer.ROOT.equals(path)) {
            return node;
        }
        Node current = node;
        for (String segment : JsonPointer.split(path)) {
            current = getStructuralNodeForSegment(current, segment);
        }
        return current;
    }

    private static void requireAbsolute(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    private static Object getRecursive(
            Node node,
            List<String> segments,
            int index,
            Function<Node, Node> linkingProvider,
            boolean resolveFinalLink) {
        if (index == segments.size() - 1 && !resolveFinalLink) {
            return getNodeForSegment(node, segments.get(index),
                    linkingProvider, false);
        }
        if (index == segments.size()) {
            return node != null && node.getValue() != null
                    ? node.getValue() : node;
        }
        Node nextNode = getNodeForSegment(
                node, segments.get(index), linkingProvider, true);
        return getRecursive(nextNode, segments, index + 1,
                linkingProvider, resolveFinalLink);
    }

    private static Node getNodeForSegment(
            Node node,
            String segment,
            Function<Node, Node> linkingProvider,
            boolean resolveLink) {
        Node result = metadataNode(node, segment, true);
        if (result == null) {
            result = payloadNode(node, segment);
        }
        return resolveLink && linkingProvider != null
                ? link(result, linkingProvider) : result;
    }

    private static Node getStructuralNodeForSegment(
            Node node, String segment) {
        Node result = metadataNode(node, segment, false);
        return result != null ? result : payloadNode(node, segment);
    }

    private static Node metadataNode(
            Node node, String segment, boolean normalizedValue) {
        switch (segment) {
            case OBJECT_NAME:
                return new Node().value(node.getName());
            case OBJECT_DESCRIPTION:
                return new Node().value(node.getDescription());
            case OBJECT_TYPE:
                return node.getType();
            case OBJECT_ITEM_TYPE:
                return node.getItemType();
            case OBJECT_KEY_TYPE:
                return node.getKeyType();
            case OBJECT_VALUE_TYPE:
                return node.getValueType();
            case OBJECT_VALUE:
                return new Node().value(normalizedValue
                        ? node.getValue() : node.getRawValue());
            case OBJECT_BLUE_ID:
                return new Node().value(NodeIdentities.calculate(node));
            case OBJECT_CONTRACTS:
                return node.getContracts();
            default:
                return null;
        }
    }

    private static Node payloadNode(Node node, String segment) {
        if (isAsciiDigits(segment)) {
            int itemIndex = Integer.parseInt(segment);
            List<Node> items = node.getItems();
            if (items == null || itemIndex >= items.size()) {
                throw new IllegalArgumentException(
                        "Invalid item index: " + itemIndex);
            }
            return items.get(itemIndex);
        }
        Map<String, Node> properties = node.getProperties();
        if (properties == null || !properties.containsKey(segment)) {
            throw new IllegalArgumentException(
                    "Property not found: " + segment);
        }
        return properties.get(segment);
    }

    private static boolean isAsciiDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return true;
    }

    private static Node link(
            Node node, Function<Node, Node> linkingProvider) {
        Node linked = linkingProvider.apply(node);
        return linked == null ? node : linked;
    }
}
