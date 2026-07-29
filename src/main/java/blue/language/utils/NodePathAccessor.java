package blue.language.utils;

import blue.language.model.Node;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static blue.language.utils.Properties.*;

/**
 * Reads values or structural nodes from a mutable Blue graph by RFC 6901
 * pointer.
 *
 * <p>The value-oriented methods unwrap a terminal scalar and can follow links
 * through a caller-supplied materializer. {@link #getNode(Node, String)}
 * performs structural traversal only and returns the actual mutable node.</p>
 */
public class NodePathAccessor {

    /**
     * Creates a node-path accessor.
     */
    public NodePathAccessor() {
    }

    /**
     * Reads a path without resolving links.
     *
     * @param node graph root to read
     * @param path absolute pointer path
     * @return terminal scalar value or structural node
     */
    public static Object get(Node node, String path) {
        return get(node, path, null);
    }
    
    /**
     * Reads a path, materializing intermediate and final links when possible.
     *
     * @param node graph root to read
     * @param path absolute pointer path
     * @param linkingProvider optional reference materializer
     * @return terminal scalar value or structural node
     */
    public static Object get(Node node, String path, Function<Node, Node> linkingProvider) {
        return get(node, path, linkingProvider, true);
    }

    /**
     * Reads a path with explicit control over whether the final link is
     * materialized.
     *
     * @param node graph root to read
     * @param path absolute pointer path
     * @param linkingProvider optional reference materializer
     * @param resolveFinalLink whether to materialize a reference at the terminal segment
     * @return terminal scalar value or structural node
     */
    public static Object get(Node node, String path, Function<Node, Node> linkingProvider, boolean resolveFinalLink) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        if (path.equals("/")) {
            return node.getValue() != null ? node.getValue() : node;
        }

        List<String> segments = JsonPointer.split(path);
        return getRecursive(node, segments, 0, linkingProvider, resolveFinalLink);
    }

    /**
     * Returns the mutable structural node at a path without link resolution.
     *
     * @param node graph root to read
     * @param path absolute pointer path
     * @return mutable structural node at the path
     */
    public static Node getNode(Node node, String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        if (path.equals("/")) {
            return node;
        }

        Node current = node;
        for (String segment : JsonPointer.split(path)) {
            current = getStructuralNodeForSegment(current, segment);
        }
        return current;
    }

    private static Object getRecursive(Node node, List<String> segments, int index, Function<Node, Node> linkingProvider, boolean resolveFinalLink) {
        if (index == segments.size() - 1 && !resolveFinalLink) {
            // Return the node itself for the last segment if we're not resolving the final link
            return getNodeForSegment(node, segments.get(index), linkingProvider, false);
        }

        if (index == segments.size()) {
            return node != null && node.getValue() != null ? node.getValue() : node;
        }

        String segment = segments.get(index);
        Node nextNode = getNodeForSegment(node, segment, linkingProvider, true);
        return getRecursive(nextNode, segments, index + 1, linkingProvider, resolveFinalLink);
    }

    private static Node getNodeForSegment(Node node, String segment, Function<Node, Node> linkingProvider, boolean resolveLink) {
        Node result;

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
                return new Node().value(node.getValue());
            case OBJECT_BLUE_ID:
                return new Node().value(BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node)));
            case OBJECT_CONTRACTS:
                return node.getContracts();
        }

        if (isAsciiDigits(segment)) {
            int itemIndex = Integer.parseInt(segment);
            List<Node> items = node.getItems();
            if (items == null || itemIndex >= items.size()) {
                throw new IllegalArgumentException("Invalid item index: " + itemIndex);
            }
            result = items.get(itemIndex);
        } else {
            Map<String, Node> properties = node.getProperties();
            if (properties == null || !properties.containsKey(segment)) {
                throw new IllegalArgumentException("Property not found: " + segment);
            }
            result = properties.get(segment);
        }

        return resolveLink && linkingProvider != null ? link(result, linkingProvider) : result;
    }

    private static Node getStructuralNodeForSegment(Node node, String segment) {
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
                return new Node().value(node.getRawValue());
            case OBJECT_BLUE_ID:
                return new Node().value(BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node)));
            case OBJECT_CONTRACTS:
                return node.getContracts();
        }

        if (isAsciiDigits(segment)) {
            int itemIndex = Integer.parseInt(segment);
            List<Node> items = node.getItems();
            if (items == null || itemIndex >= items.size()) {
                throw new IllegalArgumentException("Invalid item index: " + itemIndex);
            }
            return items.get(itemIndex);
        }

        Map<String, Node> properties = node.getProperties();
        if (properties == null || !properties.containsKey(segment)) {
            throw new IllegalArgumentException("Property not found: " + segment);
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

    private static Node link(Node node, Function<Node, Node> linkingProvider) {
        Node linked = linkingProvider.apply(node);
        return linked == null ? node : linked;
    }
}
