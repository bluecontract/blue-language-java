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
import java.util.stream.Collectors;

import static blue.language.utils.Properties.*;

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

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Node getType() {
        return type;
    }

    public Node getItemType() {
        return itemType;
    }
    
    public Node getKeyType() {
        return keyType;
    }

    public Node getValueType() {
        return valueType;
    }

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
                if ("true".equals(this.value)) {
                    return true;
                }
                if ("false".equals(this.value)) {
                    return false;
                }
                throw new IllegalArgumentException("Explicit Boolean scalar values must be \"true\" or \"false\".");
            }
        }
        return value;
    }

    public Object getRawValue() {
        return value;
    }

    public List<Node> getItems() {
        return items;
    }

    public Map<String, Node> getProperties() {
        return properties;
    }

    public Node getContracts() {
        return contracts;
    }

    public String getBlueId() {
        return blueId;
    }

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

    public Schema getSchema() {
        return schema;
    }

    public String getMergePolicy() {
        return mergePolicy;
    }

    public String getPreviousBlueId() {
        return previousBlueId;
    }

    public Integer getPosition() {
        return position;
    }

    public Node getBlue() {
        return blue;
    }
    
    public boolean isInlineValue() {
        return inlineValue;
    }

    public Node name(String name) {
        this.name = name;
        return this;
    }

    public Node description(String description) {
        this.description = description;
        return this;
    }

    public Node type(Node type) {
        this.type = type;
        return this;
    }

    public Node type(String type) {
        this.type = new Node().value(type).inlineValue(true);
        return this;
    }

    public Node itemType(Node itemType) {
        this.itemType = itemType;
        return this;
    }

    public Node itemType(String itemType) {
        this.itemType = new Node().value(itemType).inlineValue(true);
        return this;
    }

    public Node keyType(Node keyType) {
        this.keyType = keyType;
        return this;
    }

    public Node keyType(String keyType) {
        this.keyType = new Node().value(keyType).inlineValue(true);
        return this;
    }

    public Node valueType(Node valueType) {
        this.valueType = valueType;
        return this;
    }

    public Node valueType(String valueType) {
        this.valueType = new Node().value(valueType).inlineValue(true);
        return this;
    }

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

    public Node value(long value) {
        this.value = BigInteger.valueOf(value);
        return this;
    }

    public Node value(double value) {
        this.value = BigDecimal.valueOf(value);
        return this;
    }

    public Node items(List<Node> items) {
        this.items = items;
        return this;
    }

    public Node items(Node... items) {
        this.items = Arrays.asList(items);
        return this;
    }

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

    public Node properties(String key1, Node value1, String key2, Node value2) {
        properties(key1, value1);
        properties(key2, value2);
        return this;
    }

    public Node properties(String key1, Node value1, String key2, Node value2, String key3, Node value3) {
        properties(key1, value1, key2, value2);
        properties(key3, value3);
        return this;
    }

    public Node properties(String key1, Node value1, String key2, Node value2, String key3, Node value3, String key4, Node value4) {
        properties(key1, value1, key2, value2, key3, value3);
        properties(key4, value4);
        return this;
    }

    public Node blueId(String blueId) {
        this.blueId = blueId;
        return this;
    }

    public Node contracts(Node contracts) {
        this.contracts = contracts;
        return this;
    }

    public Node schema(Schema schema) {
        this.schema = schema;
        return this;
    }

    public Node mergePolicy(String mergePolicy) {
        this.mergePolicy = mergePolicy;
        return this;
    }

    public Node previousBlueId(String previousBlueId) {
        this.previousBlueId = previousBlueId;
        return this;
    }

    public Node position(Integer position) {
        this.position = position;
        return this;
    }

    public Node blue(Node blue) {
        this.blue = blue;
        return this;
    }
    
    public Node inlineValue(boolean inlineValue) {
        this.inlineValue = inlineValue;
        return this;
    }

    public Node replaceWith(Node source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        this.name = source.name;
        this.description = source.description;
        this.value = copyValue(source.value, new IdentityHashMap<Object, Object>());
        this.blueId = source.blueId;
        this.mergePolicy = source.mergePolicy;
        this.previousBlueId = source.previousBlueId;
        this.position = source.position;
        this.inlineValue = source.inlineValue;
        this.contracts = source.contracts != null ? source.contracts.clone() : null;

        this.type = source.type != null ? source.type.clone() : null;
        this.itemType = source.itemType != null ? source.itemType.clone() : null;
        this.keyType = source.keyType != null ? source.keyType.clone() : null;
        this.valueType = source.valueType != null ? source.valueType.clone() : null;
        this.items = source.items != null
                ? source.items.stream().map(Node::clone).collect(Collectors.toCollection(ArrayList::new))
                : null;
        this.properties = source.properties != null
                ? source.properties.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().clone(),
                            (e1, e2) -> e1,
                            LinkedHashMap::new
                    ))
                : null;
        this.schema = source.schema != null ? source.schema.clone() : null;
        this.blue = source.blue != null ? source.blue.clone() : null;
        return this;
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

    public Object get(String path) {
        return NodePathAccessor.get(this, path);
    }

    public Object get(String path, Function<Node, Node> linkingProvider) {
        return NodePathAccessor.get(this, path, linkingProvider);
    }

    public Node getAsNode(String path) {
        return (Node) get(path);
    }

    public Node getNode(String path) {
        return NodePathAccessor.getNode(this, path);
    }

    public String getAsText(String path) {
        return (String) get(path);
    }

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

    @Override
    public Node clone() {
        try {
            Node cloned = (Node) super.clone();

            return cloned.replaceWith(this);
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Node must be cloneable", e);
        }
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
               '}';
    }
}
