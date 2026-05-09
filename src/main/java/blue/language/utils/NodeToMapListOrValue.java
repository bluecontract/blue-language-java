package blue.language.utils;

import blue.language.model.Node;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.NodeToMapListOrValue.Strategy.*;
import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class NodeToMapListOrValue {

    public enum Strategy {
        OFFICIAL,
        SIMPLE
    }

    public static Object get(Node node) {
        return get(node, OFFICIAL);
    }

    public static Object get(Node node, Strategy strategy) {
        validatePayloadKind(node);

        if (node.isReferenceOnly()) {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put(OBJECT_BLUE_ID, node.getBlueId());
            return reference;
        }

        Object value = node.getValue();

        if (value != null && strategy == SIMPLE)
            return value;

        List<Object> items = node.getItems() == null ? null :
                node.getItems().stream()
                        .map(item -> get(item, strategy))
                        .collect(Collectors.toList());
        if (items != null && strategy == SIMPLE)
            return items;

        Map<String, Object> result = new LinkedHashMap<>();
        if (node.getName() != null)
            result.put(OBJECT_NAME, node.getName());
        if (node.getDescription() != null)
            result.put(OBJECT_DESCRIPTION, node.getDescription());

        String valueTypeBlueId = null;
        if (strategy == OFFICIAL && value != null && node.getType() == null) {
            String inferredTypeBlueId = inferTypeBlueId(value);
            if (inferredTypeBlueId != null) {
                valueTypeBlueId = inferredTypeBlueId;
                Map<String, String> map = new HashMap<>();
                map.put(OBJECT_BLUE_ID, inferredTypeBlueId);
                result.put(OBJECT_TYPE, map);
            }
        } else if (node.getType() != null) {
            valueTypeBlueId = node.getType().getBlueId();
            result.put(OBJECT_TYPE, get(node.getType()));
        }

        if (node.getItemType() != null)
            result.put(OBJECT_ITEM_TYPE, get(node.getItemType()));
        if (node.getKeyType() != null)
            result.put(OBJECT_KEY_TYPE, get(node.getKeyType()));
        if (node.getValueType() != null)
            result.put(OBJECT_VALUE_TYPE, get(node.getValueType()));
        if (value != null)
            result.put(OBJECT_VALUE, handleValue(value, valueTypeBlueId));
        if (items != null)
            result.put(OBJECT_ITEMS, items);
        if (node.getSchema() != null)
            result.put(OBJECT_SCHEMA, YAML_MAPPER.convertValue(node.getSchema(), new TypeReference<Map<String, Object>>() {}));
        if (node.getBlue() != null)
            result.put(OBJECT_BLUE, node.getBlue());
        if (node.getProperties() != null)
            node.getProperties().forEach((key, propertyValue) -> result.put(key, get(propertyValue, strategy)));
        return result;
    }

    private static void validatePayloadKind(Node node) {
        int payloadKinds = 0;
        if (node.getValue() != null) payloadKinds++;
        if (node.getItems() != null) payloadKinds++;
        if (node.getProperties() != null && !node.getProperties().isEmpty()) payloadKinds++;
        if (payloadKinds > 1) {
            throw new IllegalArgumentException("A Blue node may contain only one payload kind: value, items, or object fields.");
        }
    }

    private static Object handleValue(Object value, String valueTypeBlueId) {
        if (DOUBLE_TYPE_BLUE_ID.equals(valueTypeBlueId)) {
            return BlueNumbers.toCanonicalDoubleValue(value);
        }
        if (value instanceof BigInteger) {
            BigInteger bigIntValue = (BigInteger) value;
            BigInteger lowerBound = BigInteger.valueOf(-9007199254740991L);
            BigInteger upperBound = BigInteger.valueOf(9007199254740991L);

            if (bigIntValue.compareTo(lowerBound) < 0 || bigIntValue.compareTo(upperBound) > 0) {
                return bigIntValue.toString();
            }
        }
        return value;
    }

    private static String inferTypeBlueId(Object value) {
        if (value instanceof String) {
            return TEXT_TYPE_BLUE_ID;
        } else if (value instanceof BigInteger) {
            return INTEGER_TYPE_BLUE_ID;
        } else if (value instanceof BigDecimal) {
            return DOUBLE_TYPE_BLUE_ID;
        } else if (value instanceof Boolean) {
            return BOOLEAN_TYPE_BLUE_ID;
        }
        return null;
    }

}
