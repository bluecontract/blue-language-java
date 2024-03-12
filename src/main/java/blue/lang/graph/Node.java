package blue.lang.graph;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Node implements Cloneable {

    private static int COUNTER = 1;

    private int id = COUNTER++;
    private String name;
    private String type;
    private Object value;
    private List<Node> items;
    private Map<String, Node> properties;
    private String ref;
    @JsonIgnore
    private List<Feature> features;

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
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

    public List<Feature> getFeatures() {
        return features;
    }

    public Node name(String name) {
        this.name = name;
        return this;
    }


    public Node type(String type) {
        this.type = type;
        return this;
    }

    public Node value(Object value) {
        this.value = value;
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

    public Node features(List<Feature> features) {
        this.features = features;
        return this;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s, %s, %s, %s", id, name, type, value == null ? null : value.toString(), features);
    }

}