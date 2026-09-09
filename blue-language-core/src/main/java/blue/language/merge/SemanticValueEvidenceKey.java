package blue.language.merge;

import blue.language.model.value.BlueNumbers;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable semantic key for a node's raw scalar or JSON-container value. */
final class SemanticValueEvidenceKey {

    private static final long VALUE_OVERHEAD_BYTES = 64L;
    private static final long PROPERTY_OVERHEAD_BYTES = 32L;
    private static final long LIST_OVERHEAD_BYTES = 24L;
    private static final long REFERENCE_BYTES = 8L;
    private static final long STRING_OVERHEAD_BYTES = 40L;
    private static final long CHARACTER_BYTES = 2L;

    private enum Kind {
        SCALAR,
        SEQUENCE,
        MAP
    }

    private final Kind kind;
    private final Object scalar;
    private final List<SemanticValueEvidenceKey> elements;
    private final List<Property> properties;
    private final int hashCode;

    private SemanticValueEvidenceKey(Object scalar) {
        Object normalized = normalizeScalar(scalar);
        kind = Kind.SCALAR;
        this.scalar = normalized;
        elements = null;
        properties = null;
        hashCode = mix(Kind.SCALAR.hashCode(), normalized);
    }

    private SemanticValueEvidenceKey(
            Kind kind,
            Object source,
            IdentityHashMap<Object, SemanticValueEvidenceKey> completed) {
        this.kind = kind;
        scalar = null;
        elements = kind == Kind.SEQUENCE
                ? valueElements(source, completed)
                : null;
        properties = kind == Kind.MAP
                ? valueProperties((Map<?, ?>) source, completed)
                : null;
        int result = mix(1, kind);
        result = mixElements(result, elements);
        hashCode = mixProperties(result, properties);
    }

    static SemanticValueEvidenceKey copyOf(Object value) {
        if (!isContainer(value)) {
            return new SemanticValueEvidenceKey(value);
        }
        IdentityHashMap<Object, SemanticValueEvidenceKey> completed =
                new IdentityHashMap<>();
        Set<Object> active = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        Deque<Visit> pending = new ArrayDeque<>();
        pending.push(new Visit(value, false));
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            if (!isContainer(visit.value)
                    || completed.containsKey(visit.value)) {
                continue;
            }
            if (visit.exit) {
                completed.put(
                        visit.value,
                        new SemanticValueEvidenceKey(
                                kindOf(visit.value),
                                visit.value,
                                completed));
                active.remove(visit.value);
                continue;
            }
            if (!active.add(visit.value)) {
                throw new IllegalArgumentException(
                        "Semantic type evidence values must not contain "
                                + "container cycles");
            }
            pending.push(new Visit(visit.value, true));
            pushChildren(pending, visit.value);
        }
        return completed.get(value);
    }

    long approximateRetainedWeightBytes() {
        return calculateRetainedWeightBytes();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SemanticValueEvidenceKey)) {
            return false;
        }
        SemanticValueEvidenceKey that = (SemanticValueEvidenceKey) other;
        if (hashCode != that.hashCode || kind != that.kind) {
            return false;
        }
        if (kind == Kind.SCALAR) {
            return Objects.equals(scalar, that.scalar);
        }
        Deque<Pair> pending = new ArrayDeque<>();
        IdentityHashMap<SemanticValueEvidenceKey,
                IdentityHashMap<SemanticValueEvidenceKey, Boolean>> compared =
                new IdentityHashMap<>();
        pending.push(new Pair(
                this, (SemanticValueEvidenceKey) other));
        while (!pending.isEmpty()) {
            Pair pair = pending.pop();
            if (pair.left == pair.right) {
                continue;
            }
            if (pair.left == null || pair.right == null
                    || pair.left.hashCode != pair.right.hashCode
                    || pair.left.kind != pair.right.kind
                    || !Objects.equals(
                            pair.left.scalar,
                            pair.right.scalar)) {
                return false;
            }
            if (pair.left.kind == Kind.SCALAR
                    || alreadyCompared(pair.left, pair.right, compared)) {
                continue;
            }
            if (!addElements(
                            pair.left.elements,
                            pair.right.elements,
                            pending)
                    || !addProperties(
                            pair.left.properties,
                            pair.right.properties,
                            pending)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private long calculateRetainedWeightBytes() {
        long weight = 0L;
        Deque<Object> pending = new ArrayDeque<>();
        Set<Object> visited = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        pending.push(this);
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            if (!visited.add(value)) {
                continue;
            }
            if (value instanceof SemanticValueEvidenceKey) {
                SemanticValueEvidenceKey key =
                        (SemanticValueEvidenceKey) value;
                weight = saturatedAdd(weight, VALUE_OVERHEAD_BYTES);
                weight = saturatedAdd(weight, scalarWeight(key.scalar));
                pushList(pending, key.elements);
                pushList(pending, key.properties);
            } else if (value instanceof Property) {
                Property property = (Property) value;
                weight = saturatedAdd(weight, PROPERTY_OVERHEAD_BYTES);
                weight = addString(weight, property.name);
                pending.push(property.value);
            } else if (value instanceof RetainedList) {
                RetainedList list = (RetainedList) value;
                weight = saturatedAdd(weight, saturatedAdd(
                        LIST_OVERHEAD_BYTES,
                        saturatedMultiply(REFERENCE_BYTES, list.size)));
            }
        }
        return weight;
    }

    private static List<SemanticValueEvidenceKey> valueElements(
            Object source,
            IdentityHashMap<Object, SemanticValueEvidenceKey> completed) {
        int size = source instanceof List
                ? ((List<?>) source).size()
                : Array.getLength(source);
        List<SemanticValueEvidenceKey> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            Object value = source instanceof List
                    ? ((List<?>) source).get(index)
                    : Array.get(source, index);
            result.add(valueKey(value, completed));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Property> valueProperties(
            Map<?, ?> source,
            IdentityHashMap<Object, SemanticValueEvidenceKey> completed) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                        "Semantic type evidence object keys must be strings");
            }
            sorted.put((String) entry.getKey(), entry.getValue());
        }
        List<Property> result = new ArrayList<>(sorted.size());
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            result.add(new Property(
                    entry.getKey(),
                    valueKey(entry.getValue(), completed)));
        }
        return Collections.unmodifiableList(result);
    }

    private static SemanticValueEvidenceKey valueKey(
            Object value,
            IdentityHashMap<Object, SemanticValueEvidenceKey> completed) {
        return isContainer(value)
                ? Objects.requireNonNull(
                        completed.get(value),
                        "incomplete semantic value child")
                : new SemanticValueEvidenceKey(value);
    }

    private static void pushChildren(
            Deque<Visit> pending,
            Object source) {
        if (source instanceof List) {
            for (Object value : (List<?>) source) {
                push(pending, value);
            }
            return;
        }
        if (source instanceof Map) {
            for (Object value : ((Map<?, ?>) source).values()) {
                push(pending, value);
            }
            return;
        }
        for (int index = 0; index < Array.getLength(source); index++) {
            push(pending, Array.get(source, index));
        }
    }

    private static void push(Deque<Visit> pending, Object value) {
        if (isContainer(value)) {
            pending.push(new Visit(value, false));
        }
    }

    private static boolean isContainer(Object value) {
        return value instanceof List
                || value instanceof Map
                || value != null && value.getClass().isArray();
    }

    private static Object normalizeScalar(Object value) {
        if (value instanceof Float && !Float.isFinite((Float) value)
                || value instanceof Double
                && !Double.isFinite((Double) value)) {
            throw new IllegalArgumentException(
                    "Semantic type evidence values must be finite");
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger) {
            return value;
        }
        if (value instanceof Float
                || value instanceof Double
                || value instanceof BigDecimal) {
            return BlueNumbers.toCanonicalDoubleValue(value);
        }
        if (value instanceof Character) {
            return String.valueOf(value);
        }
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Enum) {
            return value;
        }
        throw new IllegalArgumentException(
                "Unsupported semantic type evidence value: "
                        + value.getClass().getName());
    }

    private static Kind kindOf(Object value) {
        return value instanceof Map ? Kind.MAP : Kind.SEQUENCE;
    }

    private static <T> boolean alreadyCompared(
            T left,
            T right,
            IdentityHashMap<T, IdentityHashMap<T, Boolean>> compared) {
        IdentityHashMap<T, Boolean> rights = compared.get(left);
        if (rights == null) {
            rights = new IdentityHashMap<>(1);
            compared.put(left, rights);
        }
        return rights.put(right, Boolean.TRUE) != null;
    }

    private static boolean addElements(
            List<SemanticValueEvidenceKey> left,
            List<SemanticValueEvidenceKey> right,
            Deque<Pair> pending) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            pending.push(new Pair(left.get(index), right.get(index)));
        }
        return true;
    }

    private static boolean addProperties(
            List<Property> left,
            List<Property> right,
            Deque<Pair> pending) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            Property leftProperty = left.get(index);
            Property rightProperty = right.get(index);
            if (!leftProperty.name.equals(rightProperty.name)) {
                return false;
            }
            pending.push(new Pair(
                    leftProperty.value,
                    rightProperty.value));
        }
        return true;
    }

    private static int mix(int result, Object value) {
        return 31 * result + Objects.hashCode(value);
    }

    private static int mixElements(
            int result,
            List<SemanticValueEvidenceKey> values) {
        if (values == null) {
            return 31 * result;
        }
        result = 31 * result + 1;
        for (SemanticValueEvidenceKey value : values) {
            result = mix(result, value);
        }
        return result;
    }

    private static int mixProperties(int result, List<Property> values) {
        if (values == null) {
            return 31 * result;
        }
        result = 31 * result + 1;
        for (Property value : values) {
            result = mix(result, value.name);
            result = mix(result, value.value);
        }
        return result;
    }

    private static void pushList(Deque<Object> pending, List<?> values) {
        if (values == null) {
            return;
        }
        pending.push(new RetainedList(values.size()));
        for (Object value : values) {
            pending.push(value);
        }
    }

    private static long addString(long weight, String value) {
        return saturatedAdd(weight, saturatedAdd(
                STRING_OVERHEAD_BYTES,
                saturatedMultiply(CHARACTER_BYTES, value.length())));
    }

    private static long scalarWeight(Object scalar) {
        if (scalar == null) {
            return 0L;
        }
        if (scalar instanceof String) {
            return saturatedAdd(
                    STRING_OVERHEAD_BYTES,
                    saturatedMultiply(
                            CHARACTER_BYTES,
                            ((String) scalar).length()));
        }
        if (scalar instanceof BigInteger) {
            return saturatedAdd(40L,
                    (((BigInteger) scalar).bitLength() + 7L) / 8L);
        }
        if (scalar instanceof BigDecimal) {
            return saturatedAdd(48L,
                    scalarWeight(((BigDecimal) scalar).unscaledValue()));
        }
        if (scalar instanceof Character || scalar instanceof Enum) {
            return 24L;
        }
        return 24L;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        return Long.MAX_VALUE / left < right
                ? Long.MAX_VALUE
                : left * right;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE
                : left + right;
    }

    private static final class Property {
        private final String name;
        private final SemanticValueEvidenceKey value;

        private Property(String name, SemanticValueEvidenceKey value) {
            this.name = name;
            this.value = value;
        }
    }

    private static final class Visit {
        private final Object value;
        private final boolean exit;

        private Visit(Object value, boolean exit) {
            this.value = value;
            this.exit = exit;
        }
    }

    private static final class Pair {
        private final SemanticValueEvidenceKey left;
        private final SemanticValueEvidenceKey right;

        private Pair(
                SemanticValueEvidenceKey left,
                SemanticValueEvidenceKey right) {
            this.left = left;
            this.right = right;
        }
    }

    private static final class RetainedList {
        private final int size;

        private RetainedList(int size) {
            this.size = size;
        }
    }
}
