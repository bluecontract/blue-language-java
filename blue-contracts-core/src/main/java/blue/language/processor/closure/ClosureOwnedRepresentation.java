package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ownership eligibility, not semantic validation. Standard Node.clone owns its
 * edges and JSON containers, but preserves subclasses and arbitrary scalar
 * objects. Such extensible representations must keep ordinary verification.
 */
final class ClosureOwnedRepresentation {
    private ClosureOwnedRepresentation() { }

    static boolean detachedCopy(Node source, Node copy) {
        // A custom root clone can return an externally retained *standard* Node.
        return source.getClass() == Node.class && standardGraph(copy);
    }

    static boolean standardGraph(Object root) {
        ArrayDeque<Object> pending = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        add(pending, root);
        while (!pending.isEmpty()) {
            Object value = pending.removeLast();
            if (visited.put(value, Boolean.TRUE) != null) continue;
            Class<?> type = value.getClass();
            if (type == Node.class) {
                Node node = (Node) value;
                add(pending, node.getType()); add(pending, node.getItemType());
                add(pending, node.getKeyType()); add(pending, node.getValueType());
                add(pending, node.getContracts()); add(pending, node.getBlue());
                add(pending, node.getItems()); add(pending, node.getProperties());
                add(pending, node.getSchema());
                if (!standardPayload(node.getRawValue())) return false;
            } else if (type == Schema.class) {
                Schema schema = (Schema) value;
                add(pending, schema.getRequired()); add(pending, schema.getMinLength());
                add(pending, schema.getMaxLength()); add(pending, schema.getMinimum());
                add(pending, schema.getMaximum()); add(pending, schema.getExclusiveMinimum());
                add(pending, schema.getExclusiveMaximum()); add(pending, schema.getMultipleOf());
                add(pending, schema.getMinItems()); add(pending, schema.getMaxItems());
                add(pending, schema.getUniqueItems()); add(pending, schema.getMinFields());
                add(pending, schema.getMaxFields()); add(pending, schema.getEnum());
            } else if (value instanceof Node || value instanceof Schema) {
                return false; // Never invoke subclass getters or treat subclass fields as owned.
            } else if (value instanceof List) {
                for (Object item : (List<?>) value) add(pending, item);
            } else if (value instanceof Map) {
                // NodeGraphCopier retains a TreeMap comparator and map keys.
                if (value instanceof TreeMap && ((TreeMap<?, ?>) value).comparator() != null) return false;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) return false;
                    add(pending, entry.getValue());
                }
            } else if (type.isArray()) {
                for (int index = 0; index < Array.getLength(value); index++) add(pending, Array.get(value, index));
            } else if (type != String.class && type != Boolean.class && type != Character.class
                    && type != Byte.class && type != Short.class && type != Integer.class
                    && type != Long.class && type != Float.class && type != Double.class
                    && type != BigInteger.class && type != BigDecimal.class) {
                return false;
            }
        }
        return true;
    }

    private static void add(ArrayDeque<Object> pending, Object value) {
        if (value != null) pending.addLast(value);
    }

    private static boolean standardPayload(Object root) {
        if (root == null || immutableScalar(root.getClass())) return true;
        ArrayDeque<Object> pending = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        add(pending, root);
        while (!pending.isEmpty()) {
            Object value = pending.removeLast();
            if (visited.put(value, Boolean.TRUE) != null) continue;
            Class<?> type = value.getClass();
            // In raw payloads the copier preserves Node/Schema as unknown
            // objects; they are not owned structural edges, even if standard.
            if (value instanceof Node || value instanceof Schema) return false;
            if (value instanceof List) {
                for (Object item : (List<?>) value) add(pending, item);
            } else if (value instanceof Map) {
                if (value instanceof TreeMap && ((TreeMap<?, ?>) value).comparator() != null) return false;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) return false;
                    add(pending, entry.getValue());
                }
            } else if (type.isArray()) {
                for (int index = 0; index < Array.getLength(value); index++) add(pending, Array.get(value, index));
            } else if (!immutableScalar(type)) {
                return false;
            }
        }
        return true;
    }

    private static boolean immutableScalar(Class<?> type) {
        return type == String.class || type == Boolean.class || type == Character.class
                || type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class
                || type == BigInteger.class || type == BigDecimal.class;
    }
}
