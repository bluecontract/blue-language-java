package blue.language.utils;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NodePathEditor {

    private NodePathEditor() {
    }

    public static Node getOrNull(Node node, String pointer) {
        Node current = node;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null) {
                return null;
            }
            current = childAtOrNull(current, segment);
        }
        return current;
    }

    public static void put(Node root, String pointer, Node value) {
        List<String> segments = JsonPointer.split(pointer);
        if (segments.isEmpty()) {
            root.replaceWith(value);
            return;
        }

        Node parent = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            parent = childAtOrCreate(parent, segments.get(i));
        }
        setChild(parent, segments.get(segments.size() - 1), value);
    }

    private static Node childAtOrNull(Node node, String segment) {
        if ("type".equals(segment)) {
            return node.getType();
        }
        if ("itemType".equals(segment)) {
            return node.getItemType();
        }
        if ("keyType".equals(segment)) {
            return node.getKeyType();
        }
        if ("valueType".equals(segment)) {
            return node.getValueType();
        }
        if ("blue".equals(segment)) {
            return node.getBlue();
        }
        if (JsonPointer.isArrayIndexSegment(segment) && node.getItems() != null && !"-".equals(segment)) {
            int index = Integer.parseInt(segment);
            return index < node.getItems().size() ? node.getItems().get(index) : null;
        }
        return node.getProperties() != null ? node.getProperties().get(segment) : null;
    }

    private static Node childAtOrCreate(Node node, String segment) {
        Node child = childAtOrNull(node, segment);
        if (child != null) {
            return child;
        }
        child = new Node();
        setChild(node, segment, child);
        return child;
    }

    private static void setChild(Node node, String segment, Node value) {
        if ("type".equals(segment)) {
            node.type(value);
            return;
        }
        if ("itemType".equals(segment)) {
            node.itemType(value);
            return;
        }
        if ("keyType".equals(segment)) {
            node.keyType(value);
            return;
        }
        if ("valueType".equals(segment)) {
            node.valueType(value);
            return;
        }
        if ("blue".equals(segment)) {
            node.blue(value);
            return;
        }
        if (JsonPointer.isArrayIndexSegment(segment) && !"-".equals(segment)) {
            int index = Integer.parseInt(segment);
            List<Node> items = node.getItems();
            if (items == null) {
                items = new ArrayList<>();
                node.items(items);
            }
            while (items.size() <= index) {
                items.add(new Node());
            }
            items.set(index, value);
            return;
        }
        Map<String, Node> properties = node.getProperties();
        if (properties == null) {
            node.properties(new HashMap<>());
            properties = node.getProperties();
        }
        properties.put(segment, value);
    }
}
