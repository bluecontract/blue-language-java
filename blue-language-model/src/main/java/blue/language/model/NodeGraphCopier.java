package blue.language.model;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Iterative deep copier for mutable {@link Node} and JSON-container graphs.
 *
 * <p>An active-path map terminates back-edges while copying a shared acyclic
 * child independently at each edge. This deliberately preserves the released
 * {@link Node#clone()} ownership and aliasing behavior without consuming the
 * VM call stack for deeply nested documents.</p>
 */
final class NodeGraphCopier {

    private NodeGraphCopier() {
    }

    static Node copy(Node source) {
        Node root = source.shallowCopyForGraph();
        copyInto(source, root);
        return root;
    }

    static void copyInto(Node source, Node root) {
        IdentityHashMap<Node, Node> activeCopies = new IdentityHashMap<>();
        Deque<NodeCopy> pending = new ArrayDeque<>();
        pending.addLast(NodeCopy.enter(source, root));

        while (!pending.isEmpty()) {
            NodeCopy copy = pending.removeLast();
            if (copy.exit) {
                activeCopies.remove(copy.source);
                continue;
            }

            Node from = copy.source;
            Node to = copy.target;
            activeCopies.put(from, to);
            pending.addLast(NodeCopy.exit(from, to));

            Node type = copyNodeReference(
                    from.type, activeCopies, pending);
            Node itemType = copyNodeReference(
                    from.itemType, activeCopies, pending);
            Node keyType = copyNodeReference(
                    from.keyType, activeCopies, pending);
            Node valueType = copyNodeReference(
                    from.valueType, activeCopies, pending);
            Node contracts = copyNodeReference(
                    from.contracts, activeCopies, pending);
            Node blue = copyNodeReference(
                    from.blue, activeCopies, pending);

            List<Node> items = null;
            if (from.items != null) {
                items = new ArrayList<>(from.items.size());
                for (Node item : from.items) {
                    items.add(copyRequiredNodeReference(
                            item, activeCopies, pending));
                }
            }

            Map<String, Node> properties = null;
            if (from.properties != null) {
                properties = new LinkedHashMap<>();
                for (Map.Entry<String, Node> entry
                        : from.properties.entrySet()) {
                    properties.put(entry.getKey(),
                            copyRequiredNodeReference(
                                    entry.getValue(),
                                    activeCopies,
                                    pending));
                }
            }

            Schema schema = copySchemaReference(
                    from.schema, activeCopies, pending);
            Object value = copyValue(from.value,
                    new IdentityHashMap<Object, Object>());
            to.replaceCopiedState(
                    from,
                    value,
                    type,
                    itemType,
                    keyType,
                    valueType,
                    items,
                    properties,
                    contracts,
                    schema,
                    blue);
        }
    }

    private static Node copyNodeReference(
            Node source,
            IdentityHashMap<Node, Node> activeCopies,
            Deque<NodeCopy> pending) {
        if (source == null) {
            return null;
        }
        Node existing = activeCopies.get(source);
        if (existing != null) {
            return existing;
        }
        Node target = source.shallowCopyForGraph();
        pending.addLast(NodeCopy.enter(source, target));
        return target;
    }

    private static Node copyRequiredNodeReference(
            Node source,
            IdentityHashMap<Node, Node> activeCopies,
            Deque<NodeCopy> pending) {
        return copyNodeReference(
                Objects.requireNonNull(
                        source, "Node child must not be null"),
                activeCopies,
                pending);
    }

    private static Schema copySchemaReference(
            Schema source,
            IdentityHashMap<Node, Node> activeCopies,
            Deque<NodeCopy> pending) {
        if (source == null) {
            return null;
        }
        return source.copyWithNodeMapper(node ->
                copyRequiredNodeReference(node, activeCopies, pending));
    }

    /** Deep-copies JSON containers so each cloned node owns its payload. */
    private static Object copyValue(
            Object source,
            IdentityHashMap<Object, Object> copies) {
        if (source == null || source instanceof String
                || source instanceof Number
                || source instanceof Boolean
                || source instanceof Character
                || source instanceof Enum) {
            return source;
        }
        Object existing = copies.get(source);
        if (existing != null) {
            return existing;
        }
        if (source instanceof List) {
            List<?> values = (List<?>) source;
            List<Object> copy = copyListLike(values);
            copies.put(source, copy);
            for (Object value : values) {
                copy.add(copyValue(value, copies));
            }
            return copy;
        }
        if (source instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) source;
            Map<Object, Object> copy = copyMapLike(values);
            copies.put(source, copy);
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                copy.put(entry.getKey(),
                        copyValue(entry.getValue(), copies));
            }
            return copy;
        }
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            Class<?> componentType = source.getClass().getComponentType();
            Class<?> copyComponentType = canRetainArrayComponentType(
                    source,
                    componentType,
                    new IdentityHashMap<Object, Boolean>())
                    ? componentType
                    : Object.class;
            Object copy = Array.newInstance(copyComponentType, length);
            copies.put(source, copy);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index,
                        copyValue(Array.get(source, index), copies));
            }
            return copy;
        }
        return source;
    }

    /**
     * Predicts copied array element types before allocation so cyclic arrays
     * point at the final owned array rather than an abandoned typed copy.
     */
    private static boolean canRetainArrayComponentType(
            Object source,
            Class<?> componentType,
            IdentityHashMap<Object, Boolean> visitingArrays) {
        if (componentType.isPrimitive()) {
            return true;
        }
        if (visitingArrays.put(source, Boolean.TRUE) != null) {
            return true;
        }
        try {
            int length = Array.getLength(source);
            for (int index = 0; index < length; index++) {
                Class<?> copiedType = copiedValueType(
                        Array.get(source, index), visitingArrays);
                if (copiedType != null
                        && !componentType.isAssignableFrom(copiedType)) {
                    return false;
                }
            }
            return true;
        } finally {
            visitingArrays.remove(source);
        }
    }

    private static Class<?> copiedValueType(
            Object source,
            IdentityHashMap<Object, Boolean> visitingArrays) {
        if (source == null) {
            return null;
        }
        if (source instanceof List) {
            return source instanceof LinkedList
                    ? LinkedList.class
                    : ArrayList.class;
        }
        if (source instanceof Map) {
            if (source instanceof TreeMap) {
                return TreeMap.class;
            }
            if (source instanceof LinkedHashMap) {
                return LinkedHashMap.class;
            }
            if (source instanceof HashMap) {
                return HashMap.class;
            }
            return LinkedHashMap.class;
        }
        if (source.getClass().isArray()) {
            Class<?> componentType = source.getClass().getComponentType();
            return canRetainArrayComponentType(
                    source, componentType, visitingArrays)
                    ? source.getClass()
                    : Object[].class;
        }
        return source.getClass();
    }

    private static List<Object> copyListLike(List<?> source) {
        if (source instanceof LinkedList) {
            return new LinkedList<>();
        }
        return new ArrayList<>(source.size());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<Object, Object> copyMapLike(Map<?, ?> source) {
        if (source instanceof TreeMap) {
            return new TreeMap(((TreeMap) source).comparator());
        }
        if (source instanceof LinkedHashMap) {
            return new LinkedHashMap<>();
        }
        if (source instanceof HashMap) {
            return new HashMap<>();
        }
        return new LinkedHashMap<>();
    }

    private static final class NodeCopy {
        private final Node source;
        private final Node target;
        private final boolean exit;

        private NodeCopy(Node source, Node target, boolean exit) {
            this.source = source;
            this.target = target;
            this.exit = exit;
        }

        private static NodeCopy enter(Node source, Node target) {
            return new NodeCopy(source, target, false);
        }

        private static NodeCopy exit(Node source, Node target) {
            return new NodeCopy(source, target, true);
        }
    }
}
