package blue.language.preprocess;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic hosted bounds for the standard preprocessing implementation.
 *
 * <p>The values are deliberately independent of memory size, provider
 * transport, thread scheduling, and cache state. A bound failure never
 * returns a partially transformed result.</p>
 */
final class PreprocessingLimits {

    static final int MAX_TRANSFORMATIONS = 1_024;
    static final int MAX_REFERENCED_RESOURCES = 4_096;
    static final int MAX_NODE_COUNT = 1_000_000;
    static final int MAX_GRAPH_DEPTH = 1_024;
    static final long MAX_TEXT_CODE_POINTS = 67_108_864L;

    private PreprocessingLimits() {
    }

    static void requireTransformationCount(int count) {
        if (count > MAX_TRANSFORMATIONS) {
            throw exceeded("transformation count", count,
                    MAX_TRANSFORMATIONS);
        }
    }

    static void requireReferencedResourceCount(int count) {
        if (count > MAX_REFERENCED_RESOURCES) {
            throw exceeded("referenced resource count", count,
                    MAX_REFERENCED_RESOURCES);
        }
    }

    static void requireGraphWithinBounds(
            Node root,
            String role) {
        if (root == null) {
            return;
        }
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Deque<GraphEntry> pending = new ArrayDeque<>();
        pending.push(new GraphEntry(root, 0));
        long nodeCount = 0;
        long textCodePoints = 0;
        while (!pending.isEmpty()) {
            GraphEntry entry = pending.pop();
            Node node = entry.node;
            if (node == null || !visited.add(node)) {
                continue;
            }
            if (entry.depth > MAX_GRAPH_DEPTH) {
                throw exceeded(role + " graph depth", entry.depth,
                        MAX_GRAPH_DEPTH);
            }
            nodeCount++;
            if (nodeCount > MAX_NODE_COUNT) {
                throw exceeded(role + " node count", nodeCount,
                        MAX_NODE_COUNT);
            }
            textCodePoints += nodeTextCodePoints(node);
            if (node.getProperties() != null) {
                for (Map.Entry<String, Node> property
                        : node.getProperties().entrySet()) {
                    textCodePoints += codePoints(property.getKey());
                    push(pending, property.getValue(), entry.depth + 1);
                }
            }
            if (textCodePoints > MAX_TEXT_CODE_POINTS) {
                throw exceeded(role + " text code points",
                        textCodePoints, MAX_TEXT_CODE_POINTS);
            }
            push(pending, node.getType(), entry.depth + 1);
            push(pending, node.getItemType(), entry.depth + 1);
            push(pending, node.getKeyType(), entry.depth + 1);
            push(pending, node.getValueType(), entry.depth + 1);
            push(pending, node.getContracts(), entry.depth + 1);
            push(pending, node.getBlue(), entry.depth + 1);
            if (node.getItems() != null) {
                for (Node item : node.getItems()) {
                    push(pending, item, entry.depth + 1);
                }
            }
            pushSchema(pending, node.getSchema(), entry.depth + 1);
        }
    }

    private static long nodeTextCodePoints(Node node) {
        long count = codePoints(node.getName())
                + codePoints(node.getDescription())
                + codePoints(node.getBlueId())
                + codePoints(node.getMergePolicy())
                + codePoints(node.getPreviousBlueId());
        Object value = node.getRawValue();
        if (value instanceof String) {
            count += codePoints((String) value);
        }
        return count;
    }

    private static long codePoints(String value) {
        return value == null ? 0
                : value.codePointCount(0, value.length());
    }

    private static void pushSchema(
            Deque<GraphEntry> pending,
            Schema schema,
            int depth) {
        if (schema == null) {
            return;
        }
        push(pending, schema.getRequired(), depth);
        push(pending, schema.getMinLength(), depth);
        push(pending, schema.getMaxLength(), depth);
        push(pending, schema.getMinimum(), depth);
        push(pending, schema.getMaximum(), depth);
        push(pending, schema.getExclusiveMinimum(), depth);
        push(pending, schema.getExclusiveMaximum(), depth);
        push(pending, schema.getMultipleOf(), depth);
        push(pending, schema.getMinItems(), depth);
        push(pending, schema.getMaxItems(), depth);
        push(pending, schema.getUniqueItems(), depth);
        push(pending, schema.getMinFields(), depth);
        push(pending, schema.getMaxFields(), depth);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                push(pending, value, depth);
            }
        }
    }

    private static void push(
            Deque<GraphEntry> pending,
            Node node,
            int depth) {
        if (node != null) {
            pending.push(new GraphEntry(node, depth));
        }
    }

    private static IllegalArgumentException exceeded(
            String dimension,
            long observed,
            long maximum) {
        return new IllegalArgumentException(
                "Preprocessing limit exceeded for " + dimension
                        + ": observed " + observed
                        + ", maximum " + maximum + ".");
    }

    private static final class GraphEntry {
        private final Node node;
        private final int depth;

        private GraphEntry(Node node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }
}
