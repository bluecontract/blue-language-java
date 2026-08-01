package blue.language.utils;

import blue.language.model.wire.JsonPointer;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_CONTRACTS;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_ITEM_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_KEY_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE_TYPE;

/**
 * Reads or writes structural children of a mutable node graph by RFC 6901
 * pointer.
 *
 * <p>Writes create missing object/list containers and grow lists with empty
 * nodes. Writing the root delegates to {@link Node#replaceWith(Node)}.</p>
 */
public final class NodePathEditor {

    private static final String ARRAY_APPEND_TOKEN = "-";

    private NodePathEditor() {
    }

    /**
     * Returns the structural child at a pointer.
     *
     * @param node graph root to read
     * @param pointer canonical pointer to the child
     * @return structural child, or {@code null} if absent
     */
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

    /**
     * Writes a value in place, creating missing intermediate containers.
     *
     * @param root mutable graph root
     * @param pointer canonical destination pointer
     * @param value node to write
     */
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
        if (OBJECT_TYPE.equals(segment)) {
            return node.getType();
        }
        if (OBJECT_ITEM_TYPE.equals(segment)) {
            return node.getItemType();
        }
        if (OBJECT_KEY_TYPE.equals(segment)) {
            return node.getKeyType();
        }
        if (OBJECT_VALUE_TYPE.equals(segment)) {
            return node.getValueType();
        }
        if (OBJECT_BLUE.equals(segment)) {
            return node.getBlue();
        }
        if (OBJECT_CONTRACTS.equals(segment)) {
            return node.getContracts();
        }
        if (JsonPointer.isArrayIndexSegment(segment)
                && node.getItems() != null
                && !ARRAY_APPEND_TOKEN.equals(segment)) {
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
        if (OBJECT_TYPE.equals(segment)) {
            node.type(value);
            return;
        }
        if (OBJECT_ITEM_TYPE.equals(segment)) {
            node.itemType(value);
            return;
        }
        if (OBJECT_KEY_TYPE.equals(segment)) {
            node.keyType(value);
            return;
        }
        if (OBJECT_VALUE_TYPE.equals(segment)) {
            node.valueType(value);
            return;
        }
        if (OBJECT_BLUE.equals(segment)) {
            node.blue(value);
            return;
        }
        if (OBJECT_CONTRACTS.equals(segment)) {
            node.contracts(value);
            return;
        }
        if (JsonPointer.isArrayIndexSegment(segment)
                && !ARRAY_APPEND_TOKEN.equals(segment)) {
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
            node.properties(new LinkedHashMap<>());
            properties = node.getProperties();
        }
        properties.put(segment, value);
    }
}
