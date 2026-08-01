package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.IdentityHashMap;

/**
 * Removes provider-materialization provenance from an owned exact Node.
 *
 * <p>A resolved nominal type may carry both its published BlueId and its
 * materialized body. Semantic header identity must retain the published
 * nominal identity, not hash whichever resolved representation happened to
 * reach the current processing phase.</p>
 */
final class MaterializationProvenance {

    private MaterializationProvenance() {
    }

    static void clear(Node node) {
        clear(node, new IdentityHashMap<Node, Boolean>());
    }

    private static void clear(
            Node node,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            return;
        }
        if (node.getBlueId() != null) {
            node.blueId(null);
        }
        node.type(nominalReference(node.getType()));
        node.itemType(nominalReference(node.getItemType()));
        node.keyType(nominalReference(node.getKeyType()));
        node.valueType(nominalReference(node.getValueType()));
        clear(node.getType(), visited);
        clear(node.getItemType(), visited);
        clear(node.getKeyType(), visited);
        clear(node.getValueType(), visited);
        clear(node.getBlue(), visited);
        clearSchema(node.getSchema(), visited);
        clear(node.getContracts(), visited);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                clear(child, visited);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                clear(child, visited);
            }
        }
    }

    private static Node nominalReference(Node type) {
        if (type == null
                || type.getBlueId() == null
                || type.isReferenceOnly()) {
            return type;
        }
        return new Node().blueId(type.getBlueId());
    }

    private static void clearSchema(
            Schema schema,
            IdentityHashMap<Node, Boolean> visited) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        if (schema.getBlueId() != null) {
            schema.blueId(null);
        }
        clear(schema.getRequired(), visited);
        clear(schema.getMinLength(), visited);
        clear(schema.getMaxLength(), visited);
        clear(schema.getMinimum(), visited);
        clear(schema.getMaximum(), visited);
        clear(schema.getExclusiveMinimum(), visited);
        clear(schema.getExclusiveMaximum(), visited);
        clear(schema.getMultipleOf(), visited);
        clear(schema.getMinItems(), visited);
        clear(schema.getMaxItems(), visited);
        clear(schema.getUniqueItems(), visited);
        clear(schema.getMinFields(), visited);
        clear(schema.getMaxFields(), visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                clear(value, visited);
            }
        }
    }
}
