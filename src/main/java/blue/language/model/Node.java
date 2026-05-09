package blue.language.model;

import blue.language.utils.NodePathAccessor;
import blue.language.utils.BlueNumbers;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

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
                return new BigInteger((String) this.value);
            } else if (DOUBLE_TYPE_BLUE_ID.equals(typeBlueId)) {
                return BlueNumbers.toCanonicalDoubleValue(this.value);
            } else if (BOOLEAN_TYPE_BLUE_ID.equals(typeBlueId) && this.value instanceof String) {
                return Boolean.parseBoolean((String) this.value);
            }
        }
        return value;
    }

    public List<Node> getItems() {
        return items;
    }

    public Map<String, Node> getProperties() {
        return properties;
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
        if (properties != null) {
            this.properties = new HashMap<>(properties);
        } else {
            this.properties = null;
        }
        return this;
    }

    public Node properties(String key1, Node value1) {
        if (this.properties == null) {
            this.properties = new HashMap<>();
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
        this.value = source.value;
        this.blueId = source.blueId;
        this.mergePolicy = source.mergePolicy;
        this.previousBlueId = source.previousBlueId;
        this.position = source.position;
        this.inlineValue = source.inlineValue;

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
                            HashMap::new
                    ))
                : null;
        this.schema = source.schema != null ? source.schema.clone() : null;
        this.blue = source.blue != null ? source.blue.clone() : null;
        return this;
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
