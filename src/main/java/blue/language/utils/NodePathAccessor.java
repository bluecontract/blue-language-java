package blue.language.utils;

import blue.language.model.Node;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class NodePathAccessor {

    public static Object get(Node node, String path) {
        return get(node, path, null);
    }
    
    public static Object get(Node node, String path, Function<Node, Node> linkingProvider) {
        return get(node, path, linkingProvider, true);
    }

    public static Object get(Node node, String path, Function<Node, Node> linkingProvider, boolean resolveFinalLink) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        if (path.equals("/")) {
            return node.getValue() != null ? node.getValue() : node;
        }

        String[] segments = path.substring(1).split("/");
        return getRecursive(node, segments, 0, linkingProvider, resolveFinalLink);
    }

    private static Object getRecursive(Node node, String[] segments, int index, Function<Node, Node> linkingProvider, boolean resolveFinalLink) {
        if (index == segments.length - 1 && !resolveFinalLink) {
            // Return the node itself for the last segment if we're not resolving the final link
            return getNodeForSegment(node, segments[index], linkingProvider, false);
        }

        if (index == segments.length) {
            return node != null && node.getValue() != null ? node.getValue() : node;
        }

        String segment = segments[index];
        Node nextNode = getNodeForSegment(node, segment, linkingProvider, true);
        return getRecursive(nextNode, segments, index + 1, linkingProvider, resolveFinalLink);
    }

    private static Node getNodeForSegment(Node node, String segment, Function<Node, Node> linkingProvider, boolean resolveLink) {
        Node result;

        switch (segment) {
            case "name":
                return new Node().value(node.getName());
            case "description":
                return new Node().value(node.getDescription());
            case "type":
                return node.getType();
            case "itemType":
                return node.getItemType();
            case "keyType":
                return node.getKeyType();
            case "valueType":
                return node.getValueType();
            case "value":
                return new Node().value(node.getValue());
            case "blueId":
                return new Node().value(BlueIdCalculator.calculateBlueId(node));
        }

        if (segment.matches("\\d+")) {
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

    private static Node link(Node node, Function<Node, Node> linkingProvider) {
        Node linked = linkingProvider.apply(node);
        return linked == null ? node : linked;
    }
}