package blue.language.snapshot;

import blue.language.model.Node;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Converts between mutable boundary nodes and exact immutable snapshots. */
public final class FrozenNodeConverter {

    /** Shared stateless converter. */
    public static final FrozenNodeConverter INSTANCE =
            new FrozenNodeConverter();

    private FrozenNodeConverter() {
    }

    /**
     * Strictly validates and defensively freezes canonical content.
     *
     * @param node mutable canonical content to freeze
     * @return an immutable strict canonical representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the content is not valid strict
     *         canonical Blue input
     */
    public FrozenNode fromNode(Node node) {
        return freeze(node, true, null, true, false);
    }

    /**
     * Defensively freezes exact preprocessed Source input.
     *
     * <p>Source syntax may still contain authoring controls such as
     * {@code $pos} and {@code $previous}. It therefore must not be admitted
     * through the strict Canonical Identity Input constructor. The resulting
     * value is immutable but intentionally has no direct BlueId.</p>
     *
     * @param node mutable preprocessed Source input to freeze
     * @return an immutable Source representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the source contains an unsupported
     *         value graph or incompatible payload shapes
     */
    public FrozenNode fromSourceNode(Node node) {
        return freeze(node, false, null, false, false);
    }

    /**
     * Defensively freezes a completed resolved view.
     *
     * @param node mutable resolved content to freeze
     * @return an immutable resolved representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the node contains an unsupported
     *         value graph or incompatible payload shapes
     */
    public FrozenNode fromResolvedNode(Node node) {
        return freeze(node, false, null, false, false);
    }

    /**
     * Freezes and structurally interns a completed resolved view.
     *
     * @param node mutable resolved content to freeze
     * @param interner optional callback that may retain an equal representation
     * @return an immutable, optionally interned resolved representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the node contains an unsupported
     *         value graph or incompatible payload shapes
     */
    public FrozenNode fromResolvedNode(
            Node node,
            FrozenNode.ResolvedStructuralInterner interner) {
        return freeze(node, false, interner, false, false);
    }

    /**
     * Freezes canonical-shaped content without strict BlueId validation.
     *
     * @param node mutable canonical-shaped content to freeze
     * @return an immutable canonical-shaped representation
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalArgumentException when the node has an invalid canonical
     *         payload shape or unsupported value graph
     */
    public FrozenNode fromUncheckedCanonicalNode(Node node) {
        return freeze(node, true, null, false, false);
    }

    /**
     * Strictly freezes an ordered canonical node list.
     *
     * @param nodes canonical nodes to freeze, or {@code null}
     * @return an immutable frozen list, or {@code null} when {@code nodes} is
     *         {@code null}
     * @throws NullPointerException when a supplied list element is
     *         {@code null}
     * @throws IllegalArgumentException when an element is not valid strict
     *         canonical Blue input
     */
    public List<FrozenNode> fromNodes(List<Node> nodes) {
        if (nodes == null) {
            return null;
        }
        List<FrozenNode> frozen = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            frozen.add(fromNode(node));
        }
        return Collections.unmodifiableList(frozen);
    }

    /**
     * Returns a detached mutable materialization of an immutable graph.
     *
     * @param frozen immutable graph to materialize
     * @return a caller-owned mutable node graph
     * @throws NullPointerException when {@code frozen} is {@code null}
     */
    public Node toNode(FrozenNode frozen) {
        if (frozen == null) {
            throw new NullPointerException("frozen");
        }
        Node root = materializeLocalFields(frozen);
        Deque<MaterializeVisit> pending = new ArrayDeque<>();
        pending.push(new MaterializeVisit(frozen, root));
        while (!pending.isEmpty()) {
            MaterializeVisit visit = pending.pop();
            FrozenNode source = visit.frozen;
            Node target = visit.mutable;
            target.type(materializeChild(source.type, pending));
            target.itemType(materializeChild(source.itemType, pending));
            target.keyType(materializeChild(source.keyType, pending));
            target.valueType(materializeChild(source.valueType, pending));
            target.blue(materializeChild(source.blue, pending));
            target.contracts(materializeChild(source.contracts, pending));
            if (source.items != null) {
                List<Node> items = new ArrayList<>(source.items.size());
                for (FrozenNode item : source.items) {
                    items.add(materializeChild(item, pending));
                }
                target.items(items);
            }
            if (source.properties != null) {
                Map<String, Node> properties = new LinkedHashMap<>();
                for (Map.Entry<String, FrozenNode> entry
                        : source.properties.entrySet()) {
                    properties.put(
                            entry.getKey(),
                            materializeChild(entry.getValue(), pending));
                }
                target.properties(properties);
            }
        }
        return root;
    }

    private static Node materializeChild(
            FrozenNode source,
            Deque<MaterializeVisit> pending) {
        if (source == null) {
            return null;
        }
        Node child = materializeLocalFields(source);
        pending.push(new MaterializeVisit(source, child));
        return child;
    }

    private static Node materializeLocalFields(FrozenNode source) {
        return new Node()
                .name(source.name)
                .description(source.description)
                .value(mutableValueCopy(source.value))
                .blueId(source.referenceBlueId)
                .schema(source.schema != null ? source.schema.clone() : null)
                .mergePolicy(source.mergePolicy)
                .previousBlueId(source.previousBlueId)
                .position(source.position)
                .inlineValue(source.inlineValue);
    }

    /** Returns a defensive immutable public view of a frozen scalar graph. */
    Object publicValueView(Object source) {
        if (source instanceof List) {
            List<?> values = (List<?>) source;
            List<Object> copy = new ArrayList<>(values.size());
            for (Object value : values) {
                copy.add(publicValueView(value));
            }
            return Collections.unmodifiableList(copy);
        }
        if (source instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) source;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                copy.put(
                        (String) entry.getKey(),
                        publicValueView(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (source != null && source.getClass().isArray()) {
            return mutableValueCopy(source);
        }
        return source;
    }

    static Object freezeValue(Object source) {
        return freezeValue(
                source,
                new IdentityHashMap<Object, Boolean>());
    }

    static Object mutableValueCopy(Object source) {
        if (source instanceof List) {
            List<?> values = (List<?>) source;
            List<Object> copy = mutableListLike(values);
            for (Object value : values) {
                copy.add(mutableValueCopy(value));
            }
            return copy;
        }
        if (source instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) source;
            Map<String, Object> copy = mutableMapLike(values);
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                copy.put(
                        (String) entry.getKey(),
                        mutableValueCopy(entry.getValue()));
            }
            return copy;
        }
        if (source != null && source.getClass().isArray()) {
            int length = Array.getLength(source);
            Class<?> componentType = source.getClass().getComponentType();
            Object copy = Array.newInstance(componentType, length);
            if (componentType.isPrimitive()) {
                System.arraycopy(source, 0, copy, 0, length);
                return copy;
            }
            for (int index = 0; index < length; index++) {
                Array.set(
                        copy,
                        index,
                        mutableValueCopy(Array.get(source, index)));
            }
            return copy;
        }
        return source;
    }

    private FrozenNode freeze(
            Node node,
            boolean strictCanonical,
            FrozenNode.ResolvedStructuralInterner interner,
            boolean strictBlueIdValidation,
            boolean previousAnchorContext) {
        if (node == null) {
            throw new NullPointerException("node");
        }
        rejectSourceNullMarkers(node);
        IdentityHashMap<Node, FrozenNode> ordinary = new IdentityHashMap<>();
        IdentityHashMap<Node, FrozenNode> anchors = new IdentityHashMap<>();
        Set<Node> active = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Deque<FreezeVisit> pending = new ArrayDeque<>();
        pending.push(new FreezeVisit(
                node, previousAnchorContext, false));
        while (!pending.isEmpty()) {
            FreezeVisit visit = pending.pop();
            IdentityHashMap<Node, FrozenNode> completed = visit.anchor
                    ? anchors
                    : ordinary;
            if (completed.containsKey(visit.node)) {
                continue;
            }
            if (visit.exit) {
                FrozenNode frozen = buildFrozenNode(
                        visit.node,
                        strictCanonical,
                        interner,
                        strictBlueIdValidation,
                        visit.anchor,
                        ordinary,
                        anchors);
                completed.put(visit.node, frozen);
                active.remove(visit.node);
                continue;
            }
            if (!active.add(visit.node)) {
                throw new IllegalArgumentException(
                        "Frozen node graphs must not contain object cycles");
            }
            pending.push(new FreezeVisit(
                    visit.node, visit.anchor, true));
            pushChildren(pending, visit.node);
        }
        return (previousAnchorContext ? anchors : ordinary).get(node);
    }

    private FrozenNode buildFrozenNode(
            Node node,
            boolean strictCanonical,
            FrozenNode.ResolvedStructuralInterner interner,
            boolean strictBlueIdValidation,
            boolean previousAnchorContext,
            IdentityHashMap<Node, FrozenNode> ordinary,
            IdentityHashMap<Node, FrozenNode> anchors) {
        FrozenNode frozen = FrozenNodeBuilder.builder()
                .name(node.getName())
                .description(node.getDescription())
                .type(ordinary.get(node.getType()))
                .itemType(ordinary.get(node.getItemType()))
                .keyType(ordinary.get(node.getKeyType()))
                .valueType(ordinary.get(node.getValueType()))
                .value(node.getValue())
                .items(frozenItems(node.getItems(), anchors))
                .properties(frozenProperties(
                        node.getProperties(), ordinary, strictCanonical))
                .contracts(ordinary.get(node.getContracts()))
                .referenceBlueId(node.getBlueId())
                .schema(node.getSchema())
                .mergePolicy(node.getMergePolicy())
                .previousBlueId(node.getPreviousBlueId())
                .position(node.getPosition())
                .blue(ordinary.get(node.getBlue()))
                .inlineValue(node.isInlineValue())
                .strictCanonical(strictCanonical)
                .strictBlueIdValidation(strictBlueIdValidation)
                .previousAnchorContext(previousAnchorContext)
                .build();
        if (!strictCanonical && interner != null) {
            FrozenNode.ResolvedStructuralKey structuralKey =
                    frozen.resolvedStructuralKey();
            return interner.intern(structuralKey, frozen);
        }
        return frozen;
    }

    private static void pushChildren(
            Deque<FreezeVisit> pending,
            Node node) {
        push(pending, node.getBlue(), false);
        push(pending, node.getContracts(), false);
        if (node.getProperties() != null) {
            List<Node> properties = new ArrayList<>(
                    node.getProperties().values());
            for (int index = properties.size() - 1; index >= 0; index--) {
                push(pending, properties.get(index), false);
            }
        }
        if (node.getItems() != null) {
            for (int index = node.getItems().size() - 1;
                    index >= 0; index--) {
                push(pending, node.getItems().get(index), true);
            }
        }
        push(pending, node.getValueType(), false);
        push(pending, node.getKeyType(), false);
        push(pending, node.getItemType(), false);
        push(pending, node.getType(), false);
    }

    private static void push(
            Deque<FreezeVisit> pending,
            Node node,
            boolean anchor) {
        if (node != null) {
            pending.push(new FreezeVisit(node, anchor, false));
        }
    }

    private static List<FrozenNode> frozenItems(
            List<Node> source,
            IdentityHashMap<Node, FrozenNode> anchors) {
        if (source == null) {
            return null;
        }
        List<FrozenNode> result = new ArrayList<>(source.size());
        for (Node item : source) {
            result.add(anchors.get(item));
        }
        return result;
    }

    private static Map<String, FrozenNode> frozenProperties(
            Map<String, Node> source,
            IdentityHashMap<Node, FrozenNode> ordinary,
            boolean strictCanonical) {
        if (source == null) {
            return null;
        }
        Map<String, FrozenNode> result = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            FrozenNode child = ordinary.get(entry.getValue());
            result.put(entry.getKey(), child);
        }
        return result;
    }

    private static void rejectSourceNullMarkers(Node root) {
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node)) {
                continue;
            }
            if (blue.language.model.Nodes.isSourceNullLiteral(node)) {
                throw new IllegalArgumentException(
                        "Source null must be consumed before freezing Blue content");
            }
            pushNode(pending, node.getType());
            pushNode(pending, node.getItemType());
            pushNode(pending, node.getKeyType());
            pushNode(pending, node.getValueType());
            pushNode(pending, node.getBlue());
            pushNode(pending, node.getContracts());
            if (node.getItems() != null) {
                for (Node child : node.getItems()) {
                    pushNode(pending, child);
                }
            }
            if (node.getProperties() != null) {
                for (Node child : node.getProperties().values()) {
                    pushNode(pending, child);
                }
            }
        }
    }

    private static void pushNode(Deque<Node> pending, Node node) {
        if (node != null) {
            pending.push(node);
        }
    }

    private static final class FreezeVisit {
        private final Node node;
        private final boolean anchor;
        private final boolean exit;

        private FreezeVisit(Node node, boolean anchor, boolean exit) {
            this.node = node;
            this.anchor = anchor;
            this.exit = exit;
        }
    }

    private static final class MaterializeVisit {
        private final FrozenNode frozen;
        private final Node mutable;

        private MaterializeVisit(FrozenNode frozen, Node mutable) {
            this.frozen = frozen;
            this.mutable = mutable;
        }
    }

    private static Object freezeValue(
            Object source,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (source instanceof Float && !Float.isFinite((Float) source)
                || source instanceof Double && !Double.isFinite((Double) source)) {
            throw new IllegalArgumentException(
                    "Frozen node values must not contain non-finite numbers");
        }
        if (source == null
                || source instanceof String
                || source instanceof Boolean
                || source instanceof Character
                || source instanceof Enum
                || source instanceof BigInteger
                || source instanceof java.math.BigDecimal
                || source instanceof Byte
                || source instanceof Short
                || source instanceof Integer
                || source instanceof Long
                || source instanceof Float
                || source instanceof Double) {
            return source;
        }
        if (source instanceof List) {
            enterValueContainer(source, activeContainers);
            try {
                List<?> values = (List<?>) source;
                List<Object> snapshot = new ArrayList<>(values.size());
                for (Object value : values) {
                    snapshot.add(freezeValue(value, activeContainers));
                }
                return Collections.unmodifiableList(snapshot);
            } finally {
                activeContainers.remove(source);
            }
        }
        if (source instanceof Map) {
            enterValueContainer(source, activeContainers);
            try {
                Map<?, ?> values = (Map<?, ?>) source;
                Map<String, Object> snapshot = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (!(entry.getKey() instanceof String)) {
                        throw unsupportedValue(entry.getKey());
                    }
                    snapshot.put(
                            (String) entry.getKey(),
                            freezeValue(entry.getValue(), activeContainers));
                }
                return Collections.unmodifiableMap(snapshot);
            } finally {
                activeContainers.remove(source);
            }
        }
        if (source.getClass().isArray()) {
            return freezeArray(source, activeContainers);
        }
        throw unsupportedValue(source);
    }

    private static Object freezeArray(
            Object source,
            IdentityHashMap<Object, Boolean> activeContainers) {
        enterValueContainer(source, activeContainers);
        try {
            int length = Array.getLength(source);
            Class<?> componentType = source.getClass().getComponentType();
            Object snapshot = Array.newInstance(componentType, length);
            if (componentType.isPrimitive()) {
                if (componentType == float.class
                        || componentType == double.class) {
                    for (int index = 0; index < length; index++) {
                        freezeValue(Array.get(source, index), activeContainers);
                    }
                }
                System.arraycopy(source, 0, snapshot, 0, length);
                return snapshot;
            }
            for (int index = 0; index < length; index++) {
                Object element = Array.get(source, index);
                Object frozenElement = freezeValue(element, activeContainers);
                if (frozenElement != null
                        && !componentType.isInstance(frozenElement)) {
                    Object concrete = freezeConcreteArrayElement(
                            element,
                            componentType,
                            activeContainers);
                    if (concrete == null) {
                        Object[] fallback = new Object[length];
                        for (int copied = 0; copied < index; copied++) {
                            fallback[copied] = Array.get(snapshot, copied);
                        }
                        fallback[index] = frozenElement;
                        for (int remaining = index + 1;
                             remaining < length;
                             remaining++) {
                            fallback[remaining] = freezeValue(
                                    Array.get(source, remaining),
                                    activeContainers);
                        }
                        return fallback;
                    }
                    frozenElement = concrete;
                }
                Array.set(snapshot, index, frozenElement);
            }
            return snapshot;
        } finally {
            activeContainers.remove(source);
        }
    }

    private static Object freezeConcreteArrayElement(
            Object source,
            Class<?> componentType,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (source instanceof List) {
            List<?> values = (List<?>) source;
            List<Object> snapshot = mutableListLike(values);
            if (!componentType.isInstance(snapshot)) {
                return null;
            }
            enterValueContainer(source, activeContainers);
            try {
                for (Object value : values) {
                    snapshot.add(freezeValue(value, activeContainers));
                }
                return snapshot;
            } finally {
                activeContainers.remove(source);
            }
        }
        if (source instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) source;
            Map<String, Object> snapshot = mutableMapLike(values);
            if (!componentType.isInstance(snapshot)) {
                return null;
            }
            enterValueContainer(source, activeContainers);
            try {
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (!(entry.getKey() instanceof String)) {
                        throw unsupportedValue(entry.getKey());
                    }
                    snapshot.put(
                            (String) entry.getKey(),
                            freezeValue(entry.getValue(), activeContainers));
                }
                return snapshot;
            } finally {
                activeContainers.remove(source);
            }
        }
        return null;
    }

    private static void enterValueContainer(
            Object source,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (activeContainers.put(source, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "Frozen node values must not contain cycles");
        }
    }

    private static IllegalArgumentException unsupportedValue(Object value) {
        String type = value == null ? "null" : value.getClass().getName();
        return new IllegalArgumentException(
                "Frozen node values must contain only JSON-compatible values; found "
                        + type);
    }

    private static List<Object> mutableListLike(List<?> source) {
        return source instanceof LinkedList
                ? new LinkedList<Object>()
                : new ArrayList<Object>(source.size());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, Object> mutableMapLike(Map<?, ?> source) {
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

}
