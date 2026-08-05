package blue.language.snapshot;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.wire.JsonPointer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_CONTRACTS;

/** Performs read-only path and child navigation over immutable nodes. */
public final class FrozenNodeNavigator {

    /** Shared stateless navigator. */
    public static final FrozenNodeNavigator INSTANCE =
            new FrozenNodeNavigator();

    private FrozenNodeNavigator() {
    }

    /**
     * Returns an object child, including the distinguished contracts child.
     *
     * @param node immutable object node to inspect
     * @param key raw object-property key
     * @return the selected child, or {@code null} when it is absent
     * @throws NullPointerException when {@code node} is {@code null}
     */
    public FrozenNode property(FrozenNode node, String key) {
        if (OBJECT_CONTRACTS.equals(key)) {
            return node.contracts;
        }
        return node.properties != null ? node.properties.get(key) : null;
    }

    /**
     * Returns a list item by zero-based index.
     *
     * @param node immutable list node to inspect
     * @param index zero-based list index
     * @return the selected item, or {@code null} when it is absent
     * @throws NullPointerException when {@code node} is {@code null}
     */
    public FrozenNode item(FrozenNode node, int index) {
        if (node.items == null || index < 0 || index >= node.items.size()) {
            return null;
        }
        return node.items.get(index);
    }

    /**
     * Resolves an encoded RFC 6901 pointer from an immutable node.
     *
     * @param node immutable root, or {@code null}
     * @param pointer encoded pointer; {@code null} selects {@code node}
     * @return the selected node, or {@code null} when the path is absent
     */
    public FrozenNode at(FrozenNode node, String pointer) {
        return at(node, JsonPointer.split(pointer));
    }

    /**
     * Resolves decoded RFC 6901 pointer segments from an immutable node.
     *
     * @param node immutable root, or {@code null}
     * @param pointerSegments decoded path segments; {@code null} selects
     *        {@code node}
     * @return the selected node, or {@code null} when the path is absent
     */
    public FrozenNode at(FrozenNode node, List<String> pointerSegments) {
        List<String> segments = pointerSegments != null
                ? pointerSegments
                : Collections.<String>emptyList();
        FrozenNode current = node;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            current = current.items != null
                    && !OBJECT_CONTRACTS.equals(segment)
                    ? item(current, parseArrayIndex(segment))
                    : property(current, segment);
        }
        return current;
    }

    /**
     * Builds an immutable RFC 6901 path index including the supplied root.
     *
     * @param node immutable root to index
     * @return every reachable node keyed by its encoded RFC 6901 path
     * @throws NullPointerException when {@code node} is {@code null}
     */
    public Map<String, FrozenNode> pathIndex(FrozenNode node) {
        Map<String, FrozenNode> index = new LinkedHashMap<>();
        indexPaths(node, JsonPointer.ROOT, index);
        return Collections.unmodifiableMap(index);
    }

    private void indexPaths(
            FrozenNode node,
            String path,
            Map<String, FrozenNode> index) {
        index.put(path, node);
        if (node.items != null) {
            for (int itemIndex = 0;
                 itemIndex < node.items.size();
                 itemIndex++) {
                indexPaths(
                        node.items.get(itemIndex),
                        JsonPointer.append(path, String.valueOf(itemIndex)),
                        index);
            }
        }
        if (node.properties != null) {
            for (Map.Entry<String, FrozenNode> entry
                    : node.properties.entrySet()) {
                indexPaths(
                        entry.getValue(),
                        JsonPointer.append(path, entry.getKey()),
                        index);
            }
        }
        if (node.contracts != null) {
            indexPaths(
                    node.contracts,
                    JsonPointer.append(path, OBJECT_CONTRACTS),
                    index);
        }
    }

    private int parseArrayIndex(String segment) {
        try {
            int index = Integer.parseInt(segment);
            return index >= 0 ? index : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
