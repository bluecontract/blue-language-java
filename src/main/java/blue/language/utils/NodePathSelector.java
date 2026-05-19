package blue.language.utils;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Selects concrete JSON Pointer paths from a node using simple path patterns.
 *
 * <p>Patterns are JSON Pointer-like paths. {@code *} matches any property key
 * or list index at one level. {@code -} matches every list item at one level,
 * which is useful for contract masks such as {@code /products/-/ean}.</p>
 */
public final class NodePathSelector {

    private NodePathSelector() {
    }

    public static List<String> select(Node root, Collection<String> patterns, Predicate<Node> predicate) {
        if (root == null || patterns == null || patterns.isEmpty()) {
            return new ArrayList<>();
        }
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }

        Set<String> selected = new LinkedHashSet<>();
        for (String pattern : patterns) {
            select(root, JsonPointer.split(pattern), 0, new ArrayList<>(), predicate, selected);
        }
        return new ArrayList<>(selected);
    }

    private static void select(Node current,
                               List<String> pattern,
                               int index,
                               List<String> currentPath,
                               Predicate<Node> predicate,
                               Set<String> selected) {
        if (current == null) {
            return;
        }
        if (index == pattern.size()) {
            if (predicate.test(current)) {
                selected.add(JsonPointer.toPointer(currentPath));
            }
            return;
        }

        String segment = pattern.get(index);
        if ("*".equals(segment)) {
            traverseAllChildren(current, pattern, index, currentPath, predicate, selected);
            return;
        }
        if ("-".equals(segment)) {
            traverseListItems(current, pattern, index, currentPath, predicate, selected);
            return;
        }

        Node child = childAtOrNull(current, segment);
        if (child != null) {
            currentPath.add(segment);
            select(child, pattern, index + 1, currentPath, predicate, selected);
            currentPath.remove(currentPath.size() - 1);
        }
    }

    private static void traverseAllChildren(Node current,
                                            List<String> pattern,
                                            int index,
                                            List<String> currentPath,
                                            Predicate<Node> predicate,
                                            Set<String> selected) {
        if (current.getItems() != null) {
            traverseListItems(current, pattern, index, currentPath, predicate, selected);
        }
        if (current.getProperties() != null) {
            for (Map.Entry<String, Node> entry : current.getProperties().entrySet()) {
                currentPath.add(entry.getKey());
                select(entry.getValue(), pattern, index + 1, currentPath, predicate, selected);
                currentPath.remove(currentPath.size() - 1);
            }
        }
    }

    private static void traverseListItems(Node current,
                                          List<String> pattern,
                                          int index,
                                          List<String> currentPath,
                                          Predicate<Node> predicate,
                                          Set<String> selected) {
        if (current.getItems() == null) {
            return;
        }
        for (int i = 0; i < current.getItems().size(); i++) {
            currentPath.add(String.valueOf(i));
            select(current.getItems().get(i), pattern, index + 1, currentPath, predicate, selected);
            currentPath.remove(currentPath.size() - 1);
        }
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
        if (node.getItems() != null && isListIndex(segment)) {
            int index = Integer.parseInt(segment);
            return index < node.getItems().size() ? node.getItems().get(index) : null;
        }
        return node.getProperties() != null ? node.getProperties().get(segment) : null;
    }

    private static boolean isListIndex(String segment) {
        return segment != null
                && !segment.isEmpty()
                && segment.chars().allMatch(Character::isDigit);
    }
}
