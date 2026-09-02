package blue.language.merge;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Rejects Java object cycles before inline type resolution clones the graph. */
final class InlineTypeCycleValidator {

    private InlineTypeCycleValidator() {
    }

    static void validate(Node root) {
        if (root == null) {
            return;
        }
        Set<Node> active = newIdentitySet();
        Set<Node> completed = newIdentitySet();
        Deque<Visit> pending = new ArrayDeque<>();
        pending.push(new Visit(root, false));
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            Node node = visit.node;
            if (visit.exit) {
                active.remove(node);
                completed.add(node);
                continue;
            }
            if (completed.contains(node)) {
                continue;
            }
            if (!active.add(node)) {
                throw new IllegalStateException(
                        "Cyclic inline type hierarchy");
            }
            pending.push(new Visit(node, true));
            List<Node> children = childrenOf(node);
            for (int index = children.size() - 1; index >= 0; index--) {
                Node child = children.get(index);
                if (child != null) {
                    pending.push(new Visit(child, false));
                }
            }
        }
    }

    private static List<Node> childrenOf(Node node) {
        List<Node> children = new ArrayList<>();
        addType(children, node.getType());
        addType(children, node.getItemType());
        addType(children, node.getKeyType());
        addType(children, node.getValueType());
        children.add(node.getContracts());
        children.add(node.getBlue());
        if (node.getItems() != null) {
            children.addAll(node.getItems());
        }
        if (node.getProperties() != null) {
            children.addAll(node.getProperties().values());
        }
        addSchemaChildren(children, node.getSchema());
        return children;
    }

    private static void addType(List<Node> children, Node type) {
        if (type != null && !type.isReferenceOnly()) {
            children.add(type);
        }
    }

    private static void addSchemaChildren(
            List<Node> children,
            Schema schema) {
        if (schema == null) {
            return;
        }
        children.add(schema.getRequired());
        children.add(schema.getMinLength());
        children.add(schema.getMaxLength());
        children.add(schema.getMinimum());
        children.add(schema.getMaximum());
        children.add(schema.getExclusiveMinimum());
        children.add(schema.getExclusiveMaximum());
        children.add(schema.getMultipleOf());
        children.add(schema.getMinItems());
        children.add(schema.getMaxItems());
        children.add(schema.getUniqueItems());
        children.add(schema.getMinFields());
        children.add(schema.getMaxFields());
        if (schema.getEnum() != null) {
            children.addAll(schema.getEnum());
        }
    }

    private static Set<Node> newIdentitySet() {
        return Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
    }

    private static final class Visit {
        private final Node node;
        private final boolean exit;

        private Visit(Node node, boolean exit) {
            this.node = node;
            this.exit = exit;
        }
    }
}
