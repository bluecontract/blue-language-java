package blue.language.model;

import blue.language.utils.NodePathAccessor;
import blue.language.utils.BlueNumbers;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

import static blue.language.utils.Properties.*;

/**
 * Mutable Java representation of a Blue node.
 *
 * <p>A node carries language metadata plus at most one semantic payload kind:
 * scalar value, list items, or object fields. Parser and canonical boundaries
 * enforce that exclusivity; fluent authoring methods intentionally remain
 * mutable. Collection getters and setters expose/retain mutable graphs, while
 * {@link #clone()} and {@link #replaceWith(Node)} perform deep copies of Node
 * and JSON-container payloads.</p>
 */
@JsonDeserialize(using = NodeDeserializer.class)
@JsonSerialize(using = NodeSerializer.class)
public class Node implements Cloneable {

    private String name;
    private String description;
    private Node type;
    private Node itemType;
    private Node keyType;
    private Node valueType;
    private Object value;
    private List<Node> items;
    private Map<String, Node> properties;
    private Node contracts;
    private String blueId;
    private Schema schema;
    private String mergePolicy;
    private String previousBlueId;
    private Integer position;
    private Node blue;
    private boolean inlineValue;
    private boolean preprocessingTransformationConfiguration;

    /**
     * Creates an empty mutable node.
     */
    public Node() {
    }

    /**
     * Returns the human-readable node name.
     *
     * @return node name, or {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the human-readable node description.
     *
     * @return node description, or {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the declared type metadata.
     *
     * @return mutable type node, or {@code null}
     */
    public Node getType() {
        return type;
    }

    /**
     * Returns the list item-type metadata.
     *
     * @return mutable item-type node, or {@code null}
     */
    public Node getItemType() {
        return itemType;
    }

    /**
     * Returns the dictionary key-type metadata.
     *
     * @return mutable key-type node, or {@code null}
     */
    public Node getKeyType() {
        return keyType;
    }

    /**
     * Returns the dictionary value-type metadata.
     *
     * @return mutable value-type node, or {@code null}
     */
    public Node getValueType() {
        return valueType;
    }

    /**
     * Returns the semantic scalar value, normalizing explicitly typed Integer,
     * Double, and Boolean spellings.
     *
     * @return normalized scalar value, or {@code null}
     * @throws IllegalArgumentException for a noncanonical typed scalar
     */
    public Object getValue() {
        if (this.type != null && this.type.getBlueId() != null && this.value != null) {
            String typeBlueId = this.type.getBlueId();
            if (INTEGER_TYPE_BLUE_ID.equals(typeBlueId) && this.value instanceof String) {
                String decimal = (String) this.value;
                if (!decimal.matches("0|-?[1-9][0-9]*")) {
                    throw new IllegalArgumentException(
                            "Integer type is incompatible with noncanonical decimal text: " + decimal);
                }
                return new BigInteger(decimal);
            } else if (DOUBLE_TYPE_BLUE_ID.equals(typeBlueId)) {
                return BlueNumbers.toCanonicalDoubleValue(this.value);
            } else if (BOOLEAN_TYPE_BLUE_ID.equals(typeBlueId) && this.value instanceof String) {
                if (BOOLEAN_TEXT_TRUE.equals(this.value)) {
                    return true;
                }
                if (BOOLEAN_TEXT_FALSE.equals(this.value)) {
                    return false;
                }
                throw new IllegalArgumentException("Explicit Boolean scalar values must be \"true\" or \"false\".");
            }
        }
        return value;
    }

    /**
     * Returns the stored scalar without type-directed normalization.
     *
     * @return raw scalar value, or {@code null}
     */
    public Object getRawValue() {
        return value;
    }

    /**
     * Returns the mutable list payload.
     *
     * @return mutable item list, or {@code null}
     */
    public List<Node> getItems() {
        return items;
    }

    /**
     * Returns the mutable object-property payload.
     *
     * @return mutable property map, or {@code null}
     */
    public Map<String, Node> getProperties() {
        return properties;
    }

    /**
     * Returns the contracts metadata.
     *
     * @return mutable contracts node, or {@code null}
     */
    public Node getContracts() {
        return contracts;
    }

    /**
     * Returns the node's BlueId reference or metadata value.
     *
     * @return BlueId, or {@code null}
     */
    public String getBlueId() {
        return blueId;
    }

    /**
     * Tests whether this node has exactly one semantic field: {@code blueId}.
     *
     * @return {@code true} when this node is a pure reference
     */
    public boolean isReferenceOnly() {
        return blueId != null
                && name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && value == null
                && items == null
                && properties == null
                && contracts == null
                && schema == null
                && mergePolicy == null
                && previousBlueId == null
                && position == null
                && blue == null;
    }

    /**
     * Returns the schema metadata.
     *
     * @return mutable schema, or {@code null}
     */
    public Schema getSchema() {
        return schema;
    }

    /**
     * Returns the list merge-policy value.
     *
     * @return merge policy, or {@code null}
     */
    public String getMergePolicy() {
        return mergePolicy;
    }

    /**
     * Returns the previous-list anchor BlueId.
     *
     * @return previous-list BlueId, or {@code null}
     */
    public String getPreviousBlueId() {
        return previousBlueId;
    }

    /**
     * Returns the list overlay position.
     *
     * @return zero-based position, or {@code null}
     */
    public Integer getPosition() {
        return position;
    }

    /**
     * Returns the preprocessing directives.
     *
     * @return mutable Blue directive node, or {@code null}
     */
    public Node getBlue() {
        return blue;
    }

    /**
     * Reports whether this node originated from scalar or list syntax sugar.
     *
     * @return {@code true} when the node is an inline value
     */
    public boolean isInlineValue() {
        return inlineValue;
    }

    /**
     * Reports whether this node was parsed under the closed preprocessing
     * transformation-configuration grammar.
     *
     * <p>The marker is implementation context, not Blue content. It permits a
     * transformation configuration to use its specified {@code value} child
     * without changing how ordinary typed nodes serialize or hash.</p>
     *
     * @return {@code true} for a contextual transformation configuration
     */
    public boolean isPreprocessingTransformationConfiguration() {
        return preprocessingTransformationConfiguration;
    }

    /**
     * Sets the human-readable node name.
     *
     * @param name node name, or {@code null}
     * @return this node
     */
    public Node name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the human-readable node description.
     *
     * @param description node description, or {@code null}
     * @return this node
     */
    public Node description(String description) {
        this.description = description;
        return this;
    }

    /**
     * Sets the declared type metadata.
     *
     * @param type mutable type node, or {@code null}
     * @return this node
     */
    public Node type(Node type) {
        this.type = type;
        return this;
    }

    /**
     * Sets an unresolved inline type alias.
     *
     * @param type inline type alias
     * @return this node
     */
    public Node type(String type) {
        this.type = new Node().value(type).inlineValue(true);
        return this;
    }

    /**
     * Sets the list item-type metadata.
     *
     * @param itemType mutable item-type node, or {@code null}
     * @return this node
     */
    public Node itemType(Node itemType) {
        this.itemType = itemType;
        return this;
    }

    /**
     * Sets an unresolved inline list item-type alias.
     *
     * @param itemType inline item-type alias
     * @return this node
     */
    public Node itemType(String itemType) {
        this.itemType = new Node().value(itemType).inlineValue(true);
        return this;
    }

    /**
     * Sets the dictionary key-type metadata.
     *
     * @param keyType mutable key-type node, or {@code null}
     * @return this node
     */
    public Node keyType(Node keyType) {
        this.keyType = keyType;
        return this;
    }

    /**
     * Sets an unresolved inline dictionary key-type alias.
     *
     * @param keyType inline key-type alias
     * @return this node
     */
    public Node keyType(String keyType) {
        this.keyType = new Node().value(keyType).inlineValue(true);
        return this;
    }

    /**
     * Sets the dictionary value-type metadata.
     *
     * @param valueType mutable value-type node, or {@code null}
     * @return this node
     */
    public Node valueType(Node valueType) {
        this.valueType = valueType;
        return this;
    }

    /**
     * Sets an unresolved inline dictionary value-type alias.
     *
     * @param valueType inline value-type alias
     * @return this node
     */
    public Node valueType(String valueType) {
        this.valueType = new Node().value(valueType).inlineValue(true);
        return this;
    }

    /**
     * Sets the scalar payload, normalizing common Java integral and floating
     * wrappers to {@link BigInteger} and {@link BigDecimal}.
     *
     * @param value scalar payload, or {@code null}
     * @return this node
     */
    public Node value(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            this.value = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof Float || value instanceof Double) {
            this.value = BigDecimal.valueOf(((Number) value).doubleValue());
        } else {
            this.value = value;
        }
        return this;
    }

    /**
     * Sets an integral scalar payload.
     *
     * @param value integral value
     * @return this node
     */
    public Node value(long value) {
        this.value = BigInteger.valueOf(value);
        return this;
    }

    /**
     * Sets a decimal scalar payload.
     *
     * @param value decimal value
     * @return this node
     */
    public Node value(double value) {
        this.value = BigDecimal.valueOf(value);
        return this;
    }

    /**
     * Sets the mutable list payload.
     *
     * @param items item list, or {@code null}
     * @return this node
     */
    public Node items(List<Node> items) {
        this.items = items;
        return this;
    }

    /**
     * Sets a fixed-size list payload from supplied items.
     *
     * @param items list items
     * @return this node
     */
    public Node items(Node... items) {
        this.items = Arrays.asList(items);
        return this;
    }

    /**
     * Replaces object fields. A {@code contracts} entry is stored in the
     * dedicated contracts slot rather than the ordinary property map.
     *
     * @param properties object properties, or {@code null}
     * @return this node
     */
    public Node properties(Map<String, Node> properties) {
        this.properties = null;
        if (properties == null) {
            return this;
        }
        Map<String, Node> objectProperties = new LinkedHashMap<>(properties);
        if (objectProperties.containsKey(OBJECT_CONTRACTS)) {
            this.contracts = objectProperties.remove(OBJECT_CONTRACTS);
        }
        this.properties = objectProperties;
        return this;
    }

    /**
     * Adds or replaces one object property.
     *
     * @param key1 property key
     * @param value1 property value
     * @return this node
     */
    public Node properties(String key1, Node value1) {
        if (OBJECT_CONTRACTS.equals(key1)) {
            return contracts(value1);
        }
        if (this.properties == null) {
            this.properties = new LinkedHashMap<>();
        }
        this.properties.put(key1, value1);
        return this;
    }

    /**
     * Adds or replaces two object properties.
     *
     * @param key1 first property key
     * @param value1 first property value
     * @param key2 second property key
     * @param value2 second property value
     * @return this node
     */
    public Node properties(String key1, Node value1, String key2, Node value2) {
        properties(key1, value1);
        properties(key2, value2);
        return this;
    }

    /**
     * Adds or replaces three object properties.
     *
     * @param key1 first property key
     * @param value1 first property value
     * @param key2 second property key
     * @param value2 second property value
     * @param key3 third property key
     * @param value3 third property value
     * @return this node
     */
    public Node properties(String key1, Node value1, String key2, Node value2, String key3, Node value3) {
        properties(key1, value1, key2, value2);
        properties(key3, value3);
        return this;
    }

    /**
     * Adds or replaces four object properties.
     *
     * @param key1 first property key
     * @param value1 first property value
     * @param key2 second property key
     * @param value2 second property value
     * @param key3 third property key
     * @param value3 third property value
     * @param key4 fourth property key
     * @param value4 fourth property value
     * @return this node
     */
    public Node properties(String key1, Node value1, String key2, Node value2, String key3, Node value3, String key4, Node value4) {
        properties(key1, value1, key2, value2, key3, value3);
        properties(key4, value4);
        return this;
    }

    /**
     * Sets the BlueId reference or metadata value.
     *
     * @param blueId BlueId, or {@code null}
     * @return this node
     */
    public Node blueId(String blueId) {
        this.blueId = blueId;
        return this;
    }

    /**
     * Sets the contracts metadata.
     *
     * @param contracts contracts node, or {@code null}
     * @return this node
     */
    public Node contracts(Node contracts) {
        this.contracts = contracts;
        return this;
    }

    /**
     * Sets the schema metadata.
     *
     * @param schema schema, or {@code null}
     * @return this node
     */
    public Node schema(Schema schema) {
        this.schema = schema;
        return this;
    }

    /**
     * Sets the list merge policy.
     *
     * @param mergePolicy merge policy, or {@code null}
     * @return this node
     */
    public Node mergePolicy(String mergePolicy) {
        this.mergePolicy = mergePolicy;
        return this;
    }

    /**
     * Sets the previous-list anchor BlueId.
     *
     * @param previousBlueId previous-list BlueId, or {@code null}
     * @return this node
     */
    public Node previousBlueId(String previousBlueId) {
        this.previousBlueId = previousBlueId;
        return this;
    }

    /**
     * Sets the list overlay position.
     *
     * @param position zero-based position, or {@code null}
     * @return this node
     */
    public Node position(Integer position) {
        this.position = position;
        return this;
    }

    /**
     * Sets the preprocessing directives.
     *
     * @param blue Blue directive node, or {@code null}
     * @return this node
     */
    public Node blue(Node blue) {
        this.blue = blue;
        return this;
    }
    
    /**
     * Marks whether this node originated from inline syntax sugar.
     *
     * @param inlineValue whether the node is inline
     * @return this node
     */
    public Node inlineValue(boolean inlineValue) {
        this.inlineValue = inlineValue;
        return this;
    }

    /**
     * Marks contextual preprocessing transformation configuration.
     *
     * <p>This flag is out-of-band parser state and is never serialized as a
     * Blue field.</p>
     *
     * @param transformationConfiguration whether the contextual grammar applies
     * @return this node
     */
    public Node preprocessingTransformationConfiguration(
            boolean transformationConfiguration) {
        this.preprocessingTransformationConfiguration =
                transformationConfiguration;
        return this;
    }

    /**
     * Replaces all state with a deep copy of {@code source}.
     *
     * @param source node whose state should be copied
     * @return this node
     * @throws IllegalArgumentException when {@code source} is null
     */
    public Node replaceWith(Node source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        Node stableSource = source == this ? copyGraph(source) : source;
        copyGraphInto(stableSource, this);
        return this;
    }

    /**
     * Copies the complete Node/Schema graph without consuming the VM call stack.
     * An active-path map terminates back-edges while still copying a shared acyclic
     * child independently at each edge, matching the historical clone behavior.
     */
    private static Node copyGraph(Node source) {
        Node root = source.shallowClone();
        copyGraphInto(source, root);
        return root;
    }

    private static void copyGraphInto(Node source, Node root) {
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

            to.name = from.name;
            to.description = from.description;
            to.value = copyValue(from.value, new IdentityHashMap<Object, Object>());
            to.blueId = from.blueId;
            to.mergePolicy = from.mergePolicy;
            to.previousBlueId = from.previousBlueId;
            to.position = from.position;
            to.inlineValue = from.inlineValue;
            to.preprocessingTransformationConfiguration =
                    from.preprocessingTransformationConfiguration;

            to.type = copyNodeReference(from.type, activeCopies, pending);
            to.itemType = copyNodeReference(from.itemType, activeCopies, pending);
            to.keyType = copyNodeReference(from.keyType, activeCopies, pending);
            to.valueType = copyNodeReference(from.valueType, activeCopies, pending);
            to.contracts = copyNodeReference(from.contracts, activeCopies, pending);
            to.blue = copyNodeReference(from.blue, activeCopies, pending);

            if (from.items != null) {
                to.items = new ArrayList<>(from.items.size());
                for (Node item : from.items) {
                    to.items.add(copyRequiredNodeReference(
                            item, activeCopies, pending));
                }
            } else {
                to.items = null;
            }
            if (from.properties != null) {
                to.properties = new LinkedHashMap<>();
                for (Map.Entry<String, Node> entry : from.properties.entrySet()) {
                    to.properties.put(entry.getKey(), copyRequiredNodeReference(
                            entry.getValue(), activeCopies, pending));
                }
            } else {
                to.properties = null;
            }
            to.schema = copySchemaReference(
                    from.schema, activeCopies, pending);
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
        Node target = source.shallowClone();
        pending.addLast(NodeCopy.enter(source, target));
        return target;
    }

    private static Node copyRequiredNodeReference(
            Node source,
            IdentityHashMap<Node, Node> activeCopies,
            Deque<NodeCopy> pending) {
        return copyNodeReference(
                Objects.requireNonNull(source, "Node child must not be null"),
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
        return source.copyWithNodeMapper(node -> copyRequiredNodeReference(
                node, activeCopies, pending));
    }

    private Node shallowClone() {
        try {
            return (Node) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Node must be cloneable", e);
        }
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

    /** Deep-copies JSON container values so a cloned Node owns its mutable payload graph. */
    private static Object copyValue(Object source, IdentityHashMap<Object, Object> copies) {
        if (source == null || source instanceof String || source instanceof Number
                || source instanceof Boolean || source instanceof Character
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
                copy.put(entry.getKey(), copyValue(entry.getValue(), copies));
            }
            return copy;
        }
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            Class<?> componentType = source.getClass().getComponentType();
            Class<?> copyComponentType = canRetainArrayComponentType(
                    source, componentType, new IdentityHashMap<Object, Boolean>())
                    ? componentType
                    : Object.class;
            Object copy = Array.newInstance(copyComponentType, length);
            copies.put(source, copy);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, copyValue(Array.get(source, index), copies));
            }
            return copy;
        }
        return source;
    }

    /**
     * A container is copied to an owned standard implementation. That copy is not
     * always assignable to a concrete array component such as a Jackson or JDK
     * implementation class. Predict the copied element types before allocating the
     * array so cycles point at the final array rather than an abandoned typed copy.
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
                if (copiedType != null && !componentType.isAssignableFrom(copiedType)) {
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
            return source instanceof LinkedList ? LinkedList.class : ArrayList.class;
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
            return canRetainArrayComponentType(source, componentType, visitingArrays)
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

    /**
     * Reads a value through the compatibility path accessor.
     *
     * @param path absolute pointer path
     * @return terminal scalar value or structural node
     */
    public Object get(String path) {
        return NodePathAccessor.get(this, path);
    }

    /**
     * Reads a value and lets the supplied function materialize link nodes
     * encountered by the compatibility path accessor.
     *
     * @param path absolute pointer path
     * @param linkingProvider reference materializer
     * @return terminal scalar value or structural node
     */
    public Object get(String path, Function<Node, Node> linkingProvider) {
        return NodePathAccessor.get(this, path, linkingProvider);
    }

    /**
     * Reads a path and casts the result to a node.
     *
     * @param path absolute pointer path
     * @return node at the path
     */
    public Node getAsNode(String path) {
        return (Node) get(path);
    }

    /**
     * Reads the mutable structural node at a path.
     *
     * @param path absolute pointer path
     * @return structural node at the path
     */
    public Node getNode(String path) {
        return NodePathAccessor.getNode(this, path);
    }

    /**
     * Reads a path and casts the result to text.
     *
     * @param path absolute pointer path
     * @return text value at the path
     */
    public String getAsText(String path) {
        return (String) get(path);
    }

    /**
     * Reads a path as an exact Integer value.
     *
     * @param path absolute pointer path
     * @return Integer value at the path
     */
    public Integer getAsInteger(String path) {
        Object value = get(path);
        if (value instanceof BigInteger) {
            return ((BigInteger) value).intValue();
        } else if (value instanceof BigDecimal) {
            BigDecimal bdValue = (BigDecimal) value;
            if (bdValue.scale() == 0) {
                return bdValue.intValueExact();
            } else {
                throw new IllegalArgumentException("Value at path " + path + " is not an integer: " + bdValue);
            }
        } else {
            throw new IllegalArgumentException("Value at path " + path + " is not a BigInteger or BigDecimal: " + value);
        }
    }

    /** Returns a deep mutable copy, including nested Node and JSON containers. */
    @Override
    public Node clone() {
        return copyGraph(this);
    }

    @Override
    public String toString() {
        return "Node{" +
               "name='" + name + '\'' +
               ", description='" + description + '\'' +
               ", type=" + type +
               ", itemType=" + itemType +
               ", keyType=" + keyType +
               ", valueType=" + valueType +
               ", value=" + value +
               ", items=" + items +
               ", properties=" + properties +
               ", contracts=" + contracts +
               ", blueId='" + blueId + '\'' +
               ", schema=" + schema +
               ", mergePolicy='" + mergePolicy + '\'' +
               ", previousBlueId='" + previousBlueId + '\'' +
               ", position=" + position +
               ", blue=" + blue +
               ", inlineValue=" + inlineValue +
               ", preprocessingTransformationConfiguration=" +
               preprocessingTransformationConfiguration +
               '}';
    }
}
