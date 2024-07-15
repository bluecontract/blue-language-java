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
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        if (path.equals("/")) {
            return node.getValue() != null ? node.getValue() : node;
        }

        String[] segments = path.substring(1).split("/");
        return getRecursive(node, segments, 0, linkingProvider);
    }

    private static Object getRecursive(Node node, String[] segments, int index, Function<Node, Node> linkingProvider) {
        if (index == segments.length) {
            return node.getValue() != null ? node.getValue() : node;
        }

        String segment = segments[index];

        switch (segment) {
            case "name":
                return node.getName();
            case "description":
                return node.getDescription();
            case "type":
                return getRecursive(node.getType(), segments, index + 1, linkingProvider);
            case "value":
                return node.getValue();
            case "blueId":
                return BlueIdCalculator.calculateBlueId(node);
        }

        if (segment.matches("\\d+")) {
            int itemIndex = Integer.parseInt(segment);
            List<Node> items = node.getItems();
            if (items == null || itemIndex >= items.size()) {
                throw new IllegalArgumentException("Invalid item index: " + itemIndex);
            }
            return getRecursive(link(items.get(itemIndex), linkingProvider), segments, index + 1, linkingProvider);
        } else {
            Map<String, Node> properties = node.getProperties();
            if (properties == null || !properties.containsKey(segment)) {
                throw new IllegalArgumentException("Property not found: " + segment);
            }
            return getRecursive(link(properties.get(segment), linkingProvider), segments, index + 1, linkingProvider);
        }
    }

    private static Node link(Node node, Function<Node, Node> linkingProvider) {
        if (linkingProvider == null)
            return node;
        Node linked = linkingProvider.apply(node);
        return linked == null ? node : linked;
    }
}