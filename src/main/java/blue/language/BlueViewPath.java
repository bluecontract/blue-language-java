package blue.language;

import blue.language.utils.Properties;

import blue.language.model.Node;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.SchemaToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves RFC 6901 pointers against the semantic fields of a mutable
 * {@link Node}.
 *
 * <p>Pure-reference {@code blueId} wrappers are representation details and are
 * intentionally not exposed as selectable semantic children.</p>
 */
public final class BlueViewPath {

    private BlueViewPath() {
    }

    /**
     * Parses and unescapes an absolute JSON Pointer.
     *
     * @param path pointer to parse
     * @return decoded segments
     * @throws IllegalArgumentException for null, relative, or malformed paths
     */
    public static List<String> split(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Blue Language view path must not be null.");
        }
        if (path.isEmpty()) {
            return new ArrayList<>();
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Blue Language view path must be an RFC 6901 JSON Pointer.");
        }
        String[] rawSegments = path.substring(1).split("/", -1);
        List<String> segments = new ArrayList<>(rawSegments.length);
        for (String raw : rawSegments) {
            segments.add(unescape(raw));
        }
        return segments;
    }

    /**
     * Selects a semantic node, returning {@code null} when the path is valid
     * but absent.
     *
     * @param root selection root
     * @param path RFC 6901 pointer
     * @return selected node, or {@code null} when absent
     * @throws IllegalArgumentException when the pointer or a list index is not
     *                                  canonical
     */
    public static Node select(Node root, String path) {
        Node current = root;
        List<String> segments = split(path);
        for (int i = 0; i < segments.size(); i++) {
            current = child(current, segments, i);
            if (current == null) {
                return null;
            }
            if (Properties.OBJECT_ITEMS.equals(segments.get(i))) {
                i++;
            }
        }
        return current;
    }

    private static Node child(Node node, List<String> segments, int index) {
        if (node == null) {
            return null;
        }
        String segment = segments.get(index);
        switch (segment) {
            case Properties.OBJECT_NAME:
                return node.getName() == null
                        ? null : new Node().value(node.getName());
            case Properties.OBJECT_DESCRIPTION:
                return node.getDescription() == null
                        ? null : new Node().value(node.getDescription());
            case Properties.OBJECT_TYPE:
                return node.getType();
            case Properties.OBJECT_ITEM_TYPE:
                return node.getItemType();
            case Properties.OBJECT_KEY_TYPE:
                return node.getKeyType();
            case Properties.OBJECT_VALUE_TYPE:
                return node.getValueType();
            case Properties.OBJECT_VALUE:
                return node.getRawValue() == null
                        ? null : new Node().value(node.getRawValue());
            case Properties.OBJECT_BLUE_ID:
                // A pure-reference wrapper is representation, not a semantic
                // A property child named blueId is distinct from the field.
                return null;
            case Properties.OBJECT_CONTRACTS:
                return node.getContracts();
            case Properties.OBJECT_SCHEMA:
                return node.getSchema() == null
                        ? null
                        : UncheckedObjectMapper.JSON_MAPPER.convertValue(
                        SchemaToMapListOrValue.get(
                                node.getSchema(), NodeToMapListOrValue::get),
                        Node.class);
            case Properties.OBJECT_ITEMS:
                if (node.getItems() == null) {
                    return null;
                }
                if (index + 1 >= segments.size()) {
                    return new Node().items(node.getItems());
                }
                return item(node, segments.get(index + 1));
            default:
                Map<String, Node> properties = node.getProperties();
                return properties == null ? null : properties.get(segment);
        }
    }

    private static Node item(Node node, String indexSegment) {
        if (!isCanonicalArrayIndex(indexSegment)) {
            throw new IllegalArgumentException(
                    "Blue Language list view path requires a canonical array index.");
        }
        if (node.getItems() == null) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(indexSegment);
        } catch (NumberFormatException e) {
            return null;
        }
        return index < node.getItems().size() ? node.getItems().get(index) : null;
    }

    private static boolean isCanonicalArrayIndex(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (first == '0') {
            return value.length() == 1;
        }
        if (first < '1' || first > '9') {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return true;
    }

    private static String unescape(String segment) {
        StringBuilder builder = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char current = segment.charAt(i);
            if (current != '~') {
                builder.append(current);
                continue;
            }
            if (i + 1 >= segment.length()) {
                throw new IllegalArgumentException("Invalid RFC 6901 escape in Blue Language view path.");
            }
            char next = segment.charAt(++i);
            if (next == '0') {
                builder.append('~');
            } else if (next == '1') {
                builder.append('/');
            } else {
                throw new IllegalArgumentException("Invalid RFC 6901 escape in Blue Language view path.");
            }
        }
        return builder.toString();
    }
}
