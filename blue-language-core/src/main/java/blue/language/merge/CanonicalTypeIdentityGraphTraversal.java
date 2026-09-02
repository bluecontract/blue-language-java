package blue.language.merge;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Traverses the identity-bearing positions in a resolved Node graph.
 *
 * <p>This collaborator owns graph-shape concerns only. It deliberately knows
 * nothing about BlueId evidence, leaving evidence selection and mutation with
 * {@link CanonicalTypeIdentityIndex}.</p>
 */
final class CanonicalTypeIdentityGraphTraversal {

    private CanonicalTypeIdentityGraphTraversal() {
    }

    static void forEachInlineTypePosition(
            Node root,
            Consumer<Node> visitor) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(visitor, "visitor");
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        add(pending, root);
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (!visited.add(node)) {
                continue;
            }
            visitTypePosition(node.getType(), pending, visitor);
            visitTypePosition(node.getItemType(), pending, visitor);
            visitTypePosition(node.getKeyType(), pending, visitor);
            visitTypePosition(node.getValueType(), pending, visitor);
            addContentChildren(pending, node);
        }
    }

    static boolean hasInlineTypeWithoutEvidence(
            Node root,
            Predicate<Node> evidenceAvailable) {
        Objects.requireNonNull(evidenceAvailable, "evidenceAvailable");
        if (root == null) {
            return false;
        }
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (!visited.add(node)) {
                continue;
            }
            if (enqueueTypePosition(
                    node.getType(), pending, evidenceAvailable)
                    || enqueueTypePosition(
                    node.getItemType(), pending, evidenceAvailable)
                    || enqueueTypePosition(
                    node.getKeyType(), pending, evidenceAvailable)
                    || enqueueTypePosition(
                    node.getValueType(), pending, evidenceAvailable)) {
                return true;
            }
            addContentChildren(pending, node);
        }
        return false;
    }

    static void forEachEquivalentNodePair(
            Node original,
            Node copy,
            BiConsumer<Node, Node> visitor) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(copy, "copy");
        Objects.requireNonNull(visitor, "visitor");
        Deque<NodePair> pending = new ArrayDeque<>();
        Map<Node, Set<Node>> visited = new IdentityHashMap<>();
        pending.add(new NodePair(original, copy));
        while (!pending.isEmpty()) {
            NodePair pair = pending.removeLast();
            if (!markPairVisited(visited, pair.original, pair.copy)) {
                continue;
            }
            visitor.accept(pair.original, pair.copy);
            addPair(pending, pair.original.getType(), pair.copy.getType());
            addPair(
                    pending,
                    pair.original.getItemType(),
                    pair.copy.getItemType());
            addPair(
                    pending,
                    pair.original.getKeyType(),
                    pair.copy.getKeyType());
            addPair(
                    pending,
                    pair.original.getValueType(),
                    pair.copy.getValueType());
            addPair(
                    pending,
                    pair.original.getContracts(),
                    pair.copy.getContracts());
            addPair(pending, pair.original.getBlue(), pair.copy.getBlue());
            addItemPairs(pending, pair.original, pair.copy);
            addPropertyPairs(pending, pair.original, pair.copy);
            addSchemaPairs(
                    pending, pair.original.getSchema(), pair.copy.getSchema());
        }
    }

    private static void visitTypePosition(
            Node completedType,
            Deque<Node> pending,
            Consumer<Node> visitor) {
        if (completedType == null || completedType.isReferenceOnly()) {
            return;
        }
        visitor.accept(completedType);
        add(pending, completedType);
    }

    private static boolean enqueueTypePosition(
            Node completedType,
            Deque<Node> pending,
            Predicate<Node> evidenceAvailable) {
        if (completedType == null) {
            return false;
        }
        if (!completedType.isReferenceOnly()
                && !evidenceAvailable.test(completedType)) {
            return true;
        }
        pending.add(completedType);
        return false;
    }

    private static void addContentChildren(
            Deque<Node> pending,
            Node node) {
        add(pending, node.getContracts());
        add(pending, node.getBlue());
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                add(pending, item);
            }
        }
        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                add(pending, property);
            }
        }
        addSchemaValues(pending, node.getSchema());
    }

    private static void addSchemaValues(
            Deque<Node> pending,
            Schema schema) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        add(pending, schema.getRequired());
        add(pending, schema.getMinLength());
        add(pending, schema.getMaxLength());
        add(pending, schema.getMinimum());
        add(pending, schema.getMaximum());
        add(pending, schema.getExclusiveMinimum());
        add(pending, schema.getExclusiveMaximum());
        add(pending, schema.getMultipleOf());
        add(pending, schema.getMinItems());
        add(pending, schema.getMaxItems());
        add(pending, schema.getUniqueItems());
        add(pending, schema.getMinFields());
        add(pending, schema.getMaxFields());
        if (schema.getEnum() != null) {
            for (Node enumValue : schema.getEnum()) {
                add(pending, enumValue);
            }
        }
    }

    private static void addItemPairs(
            Deque<NodePair> pending,
            Node original,
            Node copy) {
        if (original.getItems() == null || copy.getItems() == null) {
            return;
        }
        int count = Math.min(
                original.getItems().size(), copy.getItems().size());
        for (int index = 0; index < count; index++) {
            addPair(
                    pending,
                    original.getItems().get(index),
                    copy.getItems().get(index));
        }
    }

    private static void addPropertyPairs(
            Deque<NodePair> pending,
            Node original,
            Node copy) {
        if (original.getProperties() == null
                || copy.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Node> property
                : original.getProperties().entrySet()) {
            addPair(
                    pending,
                    property.getValue(),
                    copy.getProperties().get(property.getKey()));
        }
    }

    private static boolean markPairVisited(
            Map<Node, Set<Node>> visited,
            Node original,
            Node copy) {
        Set<Node> copies = visited.get(original);
        if (copies == null) {
            copies = Collections.newSetFromMap(
                    new IdentityHashMap<Node, Boolean>());
            visited.put(original, copies);
        }
        return copies.add(copy);
    }

    private static void addSchemaPairs(
            Deque<NodePair> pending,
            Schema original,
            Schema copy) {
        if (original == null || copy == null) {
            return;
        }
        addPair(pending, original.getRequired(), copy.getRequired());
        addPair(pending, original.getMinLength(), copy.getMinLength());
        addPair(pending, original.getMaxLength(), copy.getMaxLength());
        addPair(pending, original.getMinimum(), copy.getMinimum());
        addPair(pending, original.getMaximum(), copy.getMaximum());
        addPair(pending,
                original.getExclusiveMinimum(), copy.getExclusiveMinimum());
        addPair(pending,
                original.getExclusiveMaximum(), copy.getExclusiveMaximum());
        addPair(pending, original.getMultipleOf(), copy.getMultipleOf());
        addPair(pending, original.getMinItems(), copy.getMinItems());
        addPair(pending, original.getMaxItems(), copy.getMaxItems());
        addPair(pending, original.getUniqueItems(), copy.getUniqueItems());
        addPair(pending, original.getMinFields(), copy.getMinFields());
        addPair(pending, original.getMaxFields(), copy.getMaxFields());
        if (original.getEnum() != null && copy.getEnum() != null) {
            int count = Math.min(
                    original.getEnum().size(), copy.getEnum().size());
            for (int index = 0; index < count; index++) {
                addPair(
                        pending,
                        original.getEnum().get(index),
                        copy.getEnum().get(index));
            }
        }
    }

    private static void add(Deque<Node> pending, Node node) {
        if (node != null) {
            pending.add(node);
        }
    }

    private static void addPair(
            Deque<NodePair> pending,
            Node original,
            Node copy) {
        if (original != null && copy != null) {
            pending.add(new NodePair(original, copy));
        }
    }

    private static final class NodePair {
        private final Node original;
        private final Node copy;

        private NodePair(Node original, Node copy) {
            this.original = original;
            this.copy = copy;
        }
    }
}
