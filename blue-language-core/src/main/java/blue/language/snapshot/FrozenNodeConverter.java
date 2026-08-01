package blue.language.snapshot;

import blue.language.model.Node;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Converts between mutable boundary nodes and exact immutable snapshots. */
public final class FrozenNodeConverter {

    /** Shared stateless converter. */
    public static final FrozenNodeConverter INSTANCE =
            new FrozenNodeConverter();

    private FrozenNodeConverter() {
    }

    /** Strictly freezes canonical content. */
    public FrozenNode fromNode(Node node) {
        return freeze(node, true, null, true, false);
    }

    /** Freezes a completed resolved view. */
    public FrozenNode fromResolvedNode(Node node) {
        return freeze(node, false, null, false, false);
    }

    /** Freezes and structurally interns a completed resolved view. */
    public FrozenNode fromResolvedNode(
            Node node,
            FrozenNode.ResolvedStructuralInterner interner) {
        return freeze(node, false, interner, false, false);
    }

    /** Freezes canonical-shaped content without strict BlueId validation. */
    public FrozenNode fromUncheckedCanonicalNode(Node node) {
        return freeze(node, true, null, false, false);
    }

    /** Strictly freezes an ordered canonical node list. */
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

    /** Returns a detached mutable materialization of an immutable graph. */
    public Node toNode(FrozenNode frozen) {
        Node node = new Node()
                .name(frozen.name)
                .description(frozen.description)
                .type(toNodeOrNull(frozen.type))
                .itemType(toNodeOrNull(frozen.itemType))
                .keyType(toNodeOrNull(frozen.keyType))
                .valueType(toNodeOrNull(frozen.valueType))
                .value(mutableValueCopy(frozen.value))
                .blueId(frozen.referenceBlueId)
                .schema(frozen.schema != null ? frozen.schema.clone() : null)
                .mergePolicy(frozen.mergePolicy)
                .previousBlueId(frozen.previousBlueId)
                .position(frozen.position)
                .blue(toNodeOrNull(frozen.blue))
                .contracts(toNodeOrNull(frozen.contracts))
                .inlineValue(frozen.inlineValue);
        if (frozen.items != null) {
            List<Node> items = new ArrayList<>(frozen.items.size());
            for (FrozenNode item : frozen.items) {
                items.add(toNode(item));
            }
            node.items(items);
        }
        if (frozen.properties != null) {
            Map<String, Node> properties = new LinkedHashMap<>();
            for (Map.Entry<String, FrozenNode> entry
                    : frozen.properties.entrySet()) {
                properties.put(entry.getKey(), toNode(entry.getValue()));
            }
            node.properties(properties);
        }
        return node;
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
        FrozenNode frozen = FrozenNodeBuilder.builder()
                .name(node.getName())
                .description(node.getDescription())
                .type(freezeNullable(
                        node.getType(),
                        strictCanonical,
                        interner,
                        strictBlueIdValidation,
                        false))
                .itemType(freezeNullable(
                        node.getItemType(),
                        strictCanonical,
                        interner,
                        strictBlueIdValidation,
                        false))
                .keyType(freezeNullable(
                        node.getKeyType(),
                        strictCanonical,
                        interner,
                        strictBlueIdValidation,
                        false))
                .valueType(freezeNullable(
                        node.getValueType(),
                        strictCanonical,
                        interner,
                        strictBlueIdValidation,
                        false))
                .value(node.getValue())
                .items(freezeItems(
                        node.getItems(),
                        strictCanonical,
                        interner,
                        strictBlueIdValidation))
                .properties(freezeProperties(
                        node.getProperties(),
                        strictCanonical,
                        interner,
                        strictBlueIdValidation))
                .contracts(freezeNullable(
                        node.getContracts(),
                        strictCanonical,
                        interner,
                        strictBlueIdValidation,
                        false))
                .referenceBlueId(node.getBlueId())
                .schema(node.getSchema())
                .mergePolicy(node.getMergePolicy())
                .previousBlueId(node.getPreviousBlueId())
                .position(node.getPosition())
                .blue(freezeNullable(
                        node.getBlue(),
                        strictCanonical,
                        interner,
                        strictBlueIdValidation,
                        false))
                .inlineValue(node.isInlineValue())
                .strictCanonical(strictCanonical)
                .strictBlueIdValidation(strictBlueIdValidation)
                .previousAnchorContext(previousAnchorContext)
                .build();
        if (!strictCanonical && interner != null) {
            return interner.intern(frozen.resolvedStructuralKey(), frozen);
        }
        return frozen;
    }

    private FrozenNode freezeNullable(
            Node node,
            boolean strictCanonical,
            FrozenNode.ResolvedStructuralInterner interner,
            boolean strictBlueIdValidation,
            boolean previousAnchorContext) {
        return node == null
                ? null
                : freeze(
                        node,
                        strictCanonical,
                        interner,
                        strictBlueIdValidation,
                        previousAnchorContext);
    }

    private List<FrozenNode> freezeItems(
            List<Node> source,
            boolean strictCanonical,
            FrozenNode.ResolvedStructuralInterner interner,
            boolean strictBlueIdValidation) {
        if (source == null) {
            return null;
        }
        List<FrozenNode> result = new ArrayList<>(source.size());
        for (Node item : source) {
            result.add(freeze(
                    item,
                    strictCanonical,
                    interner,
                    strictBlueIdValidation,
                    true));
        }
        return result;
    }

    private Map<String, FrozenNode> freezeProperties(
            Map<String, Node> source,
            boolean strictCanonical,
            FrozenNode.ResolvedStructuralInterner interner,
            boolean strictBlueIdValidation) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, FrozenNode> result = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            FrozenNode child = freeze(
                    entry.getValue(),
                    strictCanonical,
                    interner,
                    strictBlueIdValidation,
                    false);
            if (!strictCanonical || !child.isEmptyNode()) {
                result.put(entry.getKey(), child);
            }
        }
        return result.isEmpty() ? null : result;
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

    private Node toNodeOrNull(FrozenNode node) {
        return node == null ? null : toNode(node);
    }
}
