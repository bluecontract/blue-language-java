package blue.lang.model;

import blue.lang.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class BlueObject {
    private String ref;
    private Object value;
    private List<BlueObject> items;
    private Map<String, BlueObject> objectValue;
    private boolean inlineValue;

    public BlueObject(Object value) {
        this.value = value;
        this.inlineValue = true;
    }

    private BlueObject(List<BlueObject> value) {
        this.items = value;
    }

    private BlueObject handleNode(JsonNode node) {
        if (node.isTextual())
            return new BlueObject(node.asText());
        else if (node.isBigInteger() || node.isInt() || node.isLong())
            return new BlueObject(node.bigIntegerValue());
        else if (node.isFloatingPointNumber())
            return new BlueObject(node.decimalValue());
        else if (node.isBoolean())
            return new BlueObject(node.asBoolean());
        else if (node.isObject())
            return UncheckedObjectMapper.YAML_MAPPER.treeToValue(node, BlueObject.class);
        else if (node.isArray())
            return handleArray(node);
        else if (node.isNull())
            return null;
        throw new IllegalArgumentException("Can't handle node: " + node);
    }

    private BlueObject handleArray(JsonNode value) {
        ArrayNode arrayNode = (ArrayNode) value;
        List<BlueObject> result = StreamSupport.stream(arrayNode.spliterator(), false)
                .map(this::handleNode)
                .collect(Collectors.toList());
        return new BlueObject(result);
    }

    public BlueObject getObject(String key) {
        if (objectValue == null)
            return null;
        return objectValue.get(key);
    }
    public void setObject(String key, BlueObject object) {
        if (objectValue == null)
            objectValue = new LinkedHashMap<>();
        objectValue.put(key, object);
    }

    public String getStringValue(String key) {
        return getValue(key, String.class);
    }
    public Boolean getBooleanValue(String key) {
        return getValue(key, Boolean.class);
    }
    public Number getNumberValue(String key) {
        return getValue(key, Number.class);
    }
    public BigInteger getIntegerValue(String key) {
        return getValue(key, BigInteger.class);
    }

    private <T> T getValue(String key, Class<T> type) {
        if (objectValue == null || objectValue.get(key) == null)
            return null;
        return type.cast(objectValue.get(key).getValue());
    }

    @JsonAnySetter
    public void set(String name, JsonNode value) {
        if (objectValue == null)
            objectValue = new LinkedHashMap<>();
        BlueObject object = handleNode(value);
        objectValue.put(name, object);
    }

    public String getRef() {
        return ref;
    }

    public Object getValue() {
        return value;
    }

    public List<BlueObject> getItems() {
        return items;
    }

    public Map<String, BlueObject> getObjectValue() {
        return objectValue;
    }

    public boolean isInlineValue() {
        return inlineValue;
    }
}