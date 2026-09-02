package blue.language.merge;

import blue.language.model.Node;
import blue.language.model.Schema;

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

/**
 * Exact semantic lookup key for canonical effective-type identity evidence.
 *
 * <p>The key retains every modeled field, including requested-reference
 * BlueIds, while deliberately omitting parser and snapshot construction
 * flags. Object-property and JSON-object names are sorted so equivalent
 * values do not depend on insertion history. Construction and comparison are
 * iterative and use identity-pair memoization, keeping deep resolved type
 * graphs stack safe without making object sharing part of the key.</p>
 */
final class SemanticTypeEvidenceKey {
    private static final long KEY_OVERHEAD_BYTES = 32L;
    private static final long NODE_OVERHEAD_BYTES = 160L;
    private static final long SCHEMA_OVERHEAD_BYTES = 64L;
    private static final long PROPERTY_OVERHEAD_BYTES = 32L;
    private static final long LIST_OVERHEAD_BYTES = 24L;
    private static final long REFERENCE_BYTES = 8L;
    private static final long STRING_OVERHEAD_BYTES = 40L;
    private static final long CHARACTER_BYTES = 2L;

    private final SemanticNode root;
    private final int hashCode;
    private final long retainedWeightBytes;

    private SemanticTypeEvidenceKey(SemanticNode root) {
        this.root = root;
        this.hashCode = root.hashCode;
        this.retainedWeightBytes = calculateRetainedWeightBytes();
    }

    static SemanticTypeEvidenceKey of(Node node) {
        Objects.requireNonNull(node, "node");
        return new SemanticTypeEvidenceKey(SemanticNode.copyOf(node));
    }

    /** Conservative retained-heap estimate used by cache admission bounds. */
    long approximateRetainedWeightBytes() {
        return retainedWeightBytes;
    }

    private long calculateRetainedWeightBytes() {
        long weight = KEY_OVERHEAD_BYTES;
        Deque<Object> pending = new ArrayDeque<>();
        Set<Object> visited = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        pending.push(root);
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            if (!visited.add(value)) {
                continue;
            }
            if (value instanceof SemanticNode) {
                SemanticNode node = (SemanticNode) value;
                weight = saturatedAdd(weight, NODE_OVERHEAD_BYTES);
                weight = addStrings(weight,
                        node.name,
                        node.description,
                        node.referenceBlueId,
                        node.mergePolicy,
                        node.previousBlueId);
                push(pending, node.type);
                push(pending, node.itemType);
                push(pending, node.keyType);
                push(pending, node.valueType);
                push(pending, node.value);
                pushList(pending, node.items);
                pushList(pending, node.properties);
                push(pending, node.contracts);
                push(pending, node.schema);
                push(pending, node.blue);
            } else if (value instanceof SemanticSchema) {
                SemanticSchema schema = (SemanticSchema) value;
                weight = saturatedAdd(weight, SCHEMA_OVERHEAD_BYTES);
                weight = addString(weight, schema.referenceBlueId);
                pushList(pending, schema.keywords);
                pushList(pending, schema.enumValues);
            } else if (value instanceof SemanticValueEvidenceKey) {
                weight = saturatedAdd(weight,
                        ((SemanticValueEvidenceKey) value)
                                .approximateRetainedWeightBytes());
            } else if (value instanceof Property) {
                Property property = (Property) value;
                weight = saturatedAdd(weight, PROPERTY_OVERHEAD_BYTES);
                weight = addString(weight, property.name);
                push(pending, property.value);
            } else if (value instanceof RetainedList) {
                RetainedList list = (RetainedList) value;
                weight = saturatedAdd(weight, saturatedAdd(
                        LIST_OVERHEAD_BYTES,
                        saturatedMultiply(REFERENCE_BYTES, list.size)));
            }
        }
        return weight;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof SemanticTypeEvidenceKey
                && hashCode == ((SemanticTypeEvidenceKey) other).hashCode
                && SemanticNode.same(
                        root,
                        ((SemanticTypeEvidenceKey) other).root);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private static final class SemanticNode {
        private final String name;
        private final String description;
        private final SemanticNode type;
        private final SemanticNode itemType;
        private final SemanticNode keyType;
        private final SemanticNode valueType;
        private final SemanticValueEvidenceKey value;
        private final List<SemanticNode> items;
        private final List<Property> properties;
        private final SemanticNode contracts;
        private final String referenceBlueId;
        private final SemanticSchema schema;
        private final String mergePolicy;
        private final String previousBlueId;
        private final Integer position;
        private final SemanticNode blue;
        private final int hashCode;
        private SemanticNode(
                Node source,
                IdentityHashMap<Node, SemanticNode> completed) {
            name = source.getName();
            description = source.getDescription();
            type = child(source.getType(), completed);
            itemType = child(source.getItemType(), completed);
            keyType = child(source.getKeyType(), completed);
            valueType = child(source.getValueType(), completed);
            value = SemanticValueEvidenceKey.copyOf(source.getValue());
            items = items(source.getItems(), completed);
            properties = properties(source.getProperties(), completed);
            contracts = child(source.getContracts(), completed);
            referenceBlueId = source.getBlueId();
            schema = SemanticSchema.copyOf(source.getSchema(), completed);
            mergePolicy = source.getMergePolicy();
            previousBlueId = source.getPreviousBlueId();
            position = source.getPosition();
            blue = child(source.getBlue(), completed);
            hashCode = calculateHash();
        }

        private static SemanticNode copyOf(Node root) {
            IdentityHashMap<Node, SemanticNode> completed =
                    new IdentityHashMap<>();
            Set<Node> active = Collections.newSetFromMap(
                    new IdentityHashMap<Node, Boolean>());
            Deque<NodeVisit> pending = new ArrayDeque<>();
            pending.push(new NodeVisit(root, false));
            while (!pending.isEmpty()) {
                NodeVisit visit = pending.pop();
                if (completed.containsKey(visit.node)) {
                    continue;
                }
                if (visit.exit) {
                    completed.put(
                            visit.node,
                            new SemanticNode(visit.node, completed));
                    active.remove(visit.node);
                    continue;
                }
                if (!active.add(visit.node)) {
                    throw new IllegalArgumentException(
                            "Semantic type evidence must not contain "
                                    + "object cycles");
                }
                pending.push(new NodeVisit(visit.node, true));
                pushChildren(pending, visit.node);
            }
            return completed.get(root);
        }

        private static boolean same(
                SemanticNode left,
                SemanticNode right) {
            Deque<Pair<SemanticNode>> pending = new ArrayDeque<>();
            IdentityHashMap<SemanticNode,
                    IdentityHashMap<SemanticNode, Boolean>> compared =
                    new IdentityHashMap<>();
            pending.push(new Pair<>(left, right));
            while (!pending.isEmpty()) {
                Pair<SemanticNode> pair = pending.pop();
                if (pair.left == pair.right) {
                    continue;
                }
                if (pair.left == null || pair.right == null
                        || pair.left.hashCode != pair.right.hashCode) {
                    return false;
                }
                if (alreadyCompared(pair.left, pair.right, compared)) {
                    continue;
                }
                if (!pair.left.sameLocalFields(pair.right)
                        || !pair.left.addChildren(pair.right, pending)) {
                    return false;
                }
            }
            return true;
        }

        private boolean sameLocalFields(SemanticNode other) {
            return Objects.equals(name, other.name)
                    && Objects.equals(description, other.description)
                    && Objects.equals(value, other.value)
                    && Objects.equals(referenceBlueId, other.referenceBlueId)
                    && Objects.equals(mergePolicy, other.mergePolicy)
                    && Objects.equals(previousBlueId, other.previousBlueId)
                    && Objects.equals(position, other.position)
                    && sameSchemaLocalFields(schema, other.schema);
        }

        private boolean addChildren(
                SemanticNode other,
                Deque<Pair<SemanticNode>> pending) {
            pending.push(new Pair<>(type, other.type));
            pending.push(new Pair<>(itemType, other.itemType));
            pending.push(new Pair<>(keyType, other.keyType));
            pending.push(new Pair<>(valueType, other.valueType));
            pending.push(new Pair<>(contracts, other.contracts));
            pending.push(new Pair<>(blue, other.blue));
            if (!addElements(items, other.items, pending)
                    || !addProperties(
                            properties,
                            other.properties,
                            pending)) {
                return false;
            }
            return addSchemaChildren(schema, other.schema, pending);
        }

        private int calculateHash() {
            int result = 1;
            result = mix(result, name);
            result = mix(result, description);
            result = mix(result, type);
            result = mix(result, itemType);
            result = mix(result, keyType);
            result = mix(result, valueType);
            result = mix(result, value);
            result = mixElements(result, items);
            result = mixProperties(result, properties);
            result = mix(result, contracts);
            result = mix(result, referenceBlueId);
            result = mix(result, schema);
            result = mix(result, mergePolicy);
            result = mix(result, previousBlueId);
            result = mix(result, position);
            return mix(result, blue);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class SemanticSchema {
        private final String referenceBlueId;
        private final List<SemanticNode> keywords;
        private final List<SemanticNode> enumValues;
        private final int hashCode;
        private SemanticSchema(
                Schema source,
                IdentityHashMap<Node, SemanticNode> completed) {
            referenceBlueId = source.getBlueId();
            List<SemanticNode> values = new ArrayList<>(13);
            values.add(child(source.getRequired(), completed));
            values.add(child(source.getMinLength(), completed));
            values.add(child(source.getMaxLength(), completed));
            values.add(child(source.getMinimum(), completed));
            values.add(child(source.getMaximum(), completed));
            values.add(child(source.getExclusiveMinimum(), completed));
            values.add(child(source.getExclusiveMaximum(), completed));
            values.add(child(source.getMultipleOf(), completed));
            values.add(child(source.getMinItems(), completed));
            values.add(child(source.getMaxItems(), completed));
            values.add(child(source.getUniqueItems(), completed));
            values.add(child(source.getMinFields(), completed));
            values.add(child(source.getMaxFields(), completed));
            keywords = Collections.unmodifiableList(values);
            enumValues = items(source.getEnum(), completed);
            hashCode = calculateHash();
        }

        private static SemanticSchema copyOf(
                Schema schema,
                IdentityHashMap<Node, SemanticNode> completed) {
            return schema != null
                    ? new SemanticSchema(schema, completed)
                    : null;
        }

        private int calculateHash() {
            int result = mix(1, referenceBlueId);
            result = mixElements(result, keywords);
            return mixElements(result, enumValues);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class Property {
        private final String name;
        private final SemanticNode value;

        private Property(String name, SemanticNode value) {
            this.name = name;
            this.value = value;
        }
    }

    private static void pushChildren(
            Deque<NodeVisit> pending,
            Node node) {
        push(pending, node.getBlue());
        push(pending, node.getContracts());
        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                push(pending, property);
            }
        }
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                push(pending, item);
            }
        }
        Schema schema = node.getSchema();
        if (schema != null) {
            if (schema.getEnum() != null) {
                for (Node value : schema.getEnum()) {
                    push(pending, value);
                }
            }
            push(pending, schema.getMaxFields());
            push(pending, schema.getMinFields());
            push(pending, schema.getUniqueItems());
            push(pending, schema.getMaxItems());
            push(pending, schema.getMinItems());
            push(pending, schema.getMultipleOf());
            push(pending, schema.getExclusiveMaximum());
            push(pending, schema.getExclusiveMinimum());
            push(pending, schema.getMaximum());
            push(pending, schema.getMinimum());
            push(pending, schema.getMaxLength());
            push(pending, schema.getMinLength());
            push(pending, schema.getRequired());
        }
        push(pending, node.getValueType());
        push(pending, node.getKeyType());
        push(pending, node.getItemType());
        push(pending, node.getType());
    }

    private static void push(Deque<NodeVisit> pending, Node child) {
        if (child != null) {
            pending.push(new NodeVisit(child, false));
        }
    }

    private static void push(Deque<Object> pending, Object value) {
        if (value != null) {
            pending.push(value);
        }
    }

    private static void pushList(
            Deque<Object> pending,
            List<?> values) {
        if (values == null) {
            return;
        }
        pending.push(new RetainedList(values));
        for (Object value : values) {
            push(pending, value);
        }
    }

    private static long addStrings(long weight, String... values) {
        long result = weight;
        for (String value : values) {
            result = addString(result, value);
        }
        return result;
    }

    private static long addString(long weight, String value) {
        if (value == null) {
            return weight;
        }
        return saturatedAdd(weight, saturatedAdd(
                STRING_OVERHEAD_BYTES,
                saturatedMultiply(CHARACTER_BYTES, value.length())));
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

    private static SemanticNode child(
            Node child,
            IdentityHashMap<Node, SemanticNode> completed) {
        if (child == null) {
            return null;
        }
        return Objects.requireNonNull(
                completed.get(child),
                "incomplete semantic type evidence child");
    }

    private static List<SemanticNode> items(
            List<Node> source,
            IdentityHashMap<Node, SemanticNode> completed) {
        if (source == null) {
            return null;
        }
        List<SemanticNode> result = new ArrayList<>(source.size());
        for (Node value : source) {
            result.add(child(
                    Objects.requireNonNull(value, "node list element"),
                    completed));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Property> properties(
            Map<String, Node> source,
            IdentityHashMap<Node, SemanticNode> completed) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, Node> sorted = new TreeMap<>(source);
        List<Property> result = new ArrayList<>(sorted.size());
        for (Map.Entry<String, Node> entry : sorted.entrySet()) {
            result.add(new Property(
                    entry.getKey(),
                    child(
                            Objects.requireNonNull(
                                    entry.getValue(),
                                    "node property value"),
                            completed)));
        }
        return Collections.unmodifiableList(result);
    }

    private static <T> boolean addElements(
            List<T> left,
            List<T> right,
            Deque<Pair<T>> pending) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            pending.push(new Pair<>(left.get(index), right.get(index)));
        }
        return true;
    }

    private static boolean addProperties(
            List<Property> left,
            List<Property> right,
            Deque<Pair<SemanticNode>> pending) {
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
            pending.push(new Pair<>(
                    leftProperty.value,
                    rightProperty.value));
        }
        return true;
    }

    private static boolean sameSchemaLocalFields(
            SemanticSchema left,
            SemanticSchema right) {
        return left == right
                || left != null
                && right != null
                && left.hashCode == right.hashCode
                && Objects.equals(
                        left.referenceBlueId,
                        right.referenceBlueId);
    }

    private static boolean addSchemaChildren(
            SemanticSchema left,
            SemanticSchema right,
            Deque<Pair<SemanticNode>> pending) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return addElements(left.keywords, right.keywords, pending)
                && addElements(left.enumValues, right.enumValues, pending);
    }

    private static <T> boolean alreadyCompared(
            T left,
            T right,
            IdentityHashMap<T, IdentityHashMap<T, Boolean>> compared) {
        IdentityHashMap<T, Boolean> rights = compared.get(left);
        if (rights == null) {
            rights = new IdentityHashMap<>();
            compared.put(left, rights);
        }
        return rights.put(right, Boolean.TRUE) != null;
    }

    private static int mix(int result, Object value) {
        return 31 * result + Objects.hashCode(value);
    }

    private static <T> int mixElements(
            int result,
            List<T> values) {
        if (values == null) {
            return 31 * result;
        }
        result = 31 * result + 1;
        for (T value : values) {
            result = mix(result, value);
        }
        return result;
    }

    private static int mixProperties(
            int result,
            List<Property> values) {
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

    private static final class NodeVisit {
        private final Node node;
        private final boolean exit;

        private NodeVisit(Node node, boolean exit) {
            this.node = node;
            this.exit = exit;
        }
    }

    private static final class Pair<T> {
        private final T left;
        private final T right;

        private Pair(T left, T right) {
            this.left = left;
            this.right = right;
        }
    }

    /** Queue marker that accounts for the retained list object and array. */
    private static final class RetainedList {
        private final int size;

        private RetainedList(List<?> values) {
            size = values.size();
        }
    }
}
