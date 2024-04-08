package blue.language.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import javax.lang.model.type.NullType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

import static blue.language.utils.Types.isBasicType;

@JsonDeserialize(using = NodeDeserializer.class)
public class Node implements Cloneable {

    public static final Node INTEGER = new Node().name("Integer");

    private String name;
    private String description;
    private Node type;
    private Object value;
    private List<Node> items;
    private Map<String, Node> properties;
    private String ref;
    private String blueId;
    private Constraints constraints;
    @JsonIgnore
    private List<Feature> features;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Node getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public List<Node> getItems() {
        return items;
    }

    public Map<String, Node> getProperties() {
        return properties;
    }

    public String getRef() {
        return ref;
    }

    public String getBlueId() {
        return blueId;
    }

    public Constraints getConstraints() {
        return constraints;
    }

    public List<Feature> getFeatures() {
        return features;
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

    public Node eraseType() {
        this.type = null;
        return this;
    }

    public Node type(String type) {
        this.type = isBasicType(type) ? new Node().value(type) : new Node().blueId(type);
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
        this.properties = properties;
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

    public Node ref(String ref) {
        this.ref = ref;
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

    public Node features(List<Feature> features) {
        this.features = features;
        return this;
    }

    @Override
    public Node clone() {
        try {
            Node cloned = (Node) super.clone();
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
            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("BasicNode must be cloneable", e);
        }
    }

    @Override
    public String toString() {
        return "Node{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", type=" + type +
                ", value=" + value +
                ", items=" + items +
                ", properties=" + properties +
                ", ref='" + ref + '\'' +
                ", blueId='" + blueId + '\'' +
                ", constraints=" + constraints +
                '}';
    }
}