package blue.language.model;

import blue.language.utils.NodePathAccessor;
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
    private Constraints constraints;
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
            } else if (DOUBLE_TYPE_BLUE_ID.equals(typeBlueId) && this.value instanceof String) {
                BigDecimal parsed = new BigDecimal((String) this.value);
                double doubleValue = parsed.doubleValue();
                return new BigDecimal(Double.toString(doubleValue));
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

    public Constraints getConstraints() {
        return constraints;
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
            this.value = BigInteger.valueOf((Integer) value);
        } else if (value instanceof Float || value instanceof Double) {
            this.value = BigDecimal.valueOf((Double) value);
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

    public Node constraints(Constraints constraints) {
        this.constraints = constraints;
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

    public Node clone2() {
        try {
            Node cloned = (Node) super.clone();
            if (this.type != null) {
                cloned.type = this.type.clone();
            }
            if (this.itemType != null) {
                cloned.itemType = this.itemType.clone();
            }
            if (this.keyType != null) {
                cloned.keyType = this.keyType.clone();
            }
            if (this.valueType != null) {
                cloned.valueType = this.valueType.clone();
            }
            if (this.items != null) {
                cloned.items = this.items.stream()
                        .map(Node::clone)
                        .collect(Collectors.toCollection(ArrayList::new));
            }
            if (this.properties != null) {
                cloned.properties = this.properties.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().clone()
                        ));
            }
            if (this.blue != null) {
                cloned.blue = this.blue.clone();
            }
            cloned.inlineValue = this.inlineValue;
            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("BasicNode must be cloneable", e);
        }
    }

    @Override
    public Node clone() {
        try {
            Node cloned = (Node) super.clone();

            cloned.name = this.name;
            cloned.description = this.description;
            cloned.value = this.value;

            if (this.type != null) {
                cloned.type = this.type.clone();
            }

            if (this.itemType != null) {
                cloned.itemType = this.itemType.clone();
            }

            if (this.keyType != null) {
                cloned.keyType = this.keyType.clone();
            }

            if (this.valueType != null) {
                cloned.valueType = this.valueType.clone();
            }

            if (this.items != null) {
                cloned.items = this.items.stream()
                        .map(Node::clone)
                        .collect(Collectors.toCollection(ArrayList::new));
            }

            if (this.properties != null) {
                cloned.properties = this.properties.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().clone(),
                                (e1, e2) -> e1,
                                HashMap::new
                        ));
            }

            if (this.constraints != null) {
                cloned.constraints = this.constraints.clone();
            }

            if (this.blue != null) {
                cloned.blue = this.blue.clone();
            }

            return cloned;
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
               ", constraints=" + constraints +
               ", blue=" + blue +
               ", inlineValue=" + inlineValue +
               '}';
    }
}