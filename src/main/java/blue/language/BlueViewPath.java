package blue.language;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToBlueIdInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BlueViewPath {

    private BlueViewPath() {
    }

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

    public static Node select(Node root, String path) {
        Node current = root;
        List<String> segments = split(path);
        for (int i = 0; i < segments.size(); i++) {
            current = child(current, segments, i);
            if (current == null) {
                throw new IllegalArgumentException("Blue Language view path not found: " + path);
            }
            if ("items".equals(segments.get(i))) {
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
                return new Node().value(node.getRawValue());
            case "blueId":
                return new Node().value(BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node)));
            case "contracts":
                return node.getContracts();
            case "items":
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
        if (node.getItems() == null || !indexSegment.matches("0|[1-9]\\d*")) {
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
