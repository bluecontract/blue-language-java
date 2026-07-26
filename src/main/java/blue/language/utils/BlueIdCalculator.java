package blue.language.utils;

import blue.language.model.Node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

import static blue.language.utils.Properties.*;

public class BlueIdCalculator {

    private static final Base58Sha256Provider CANONICAL_HASH_PROVIDER = new Base58Sha256Provider();
    public static final BlueIdCalculator INSTANCE =
            new BlueIdCalculator(CANONICAL_HASH_PROVIDER::applyCanonicalValue);

    private Function<Object, String> hashProvider;

    public BlueIdCalculator(Function<Object, String> hashProvider) {
        this.hashProvider = hashProvider;
    }

    public static String calculateBlueId(Node node) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.get(node));
    }

    public static String calculateUncheckedBlueId(Node node) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToMapListOrValue.get(node));
    }

    public static String calculateBlueIdAllowingCyclicPlaceholders(Node node) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.getAllowingCyclicPlaceholders(node));
    }

    public static String calculateBlueId(List<Node> nodes) {
        List<Object> objects = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            objects.add(NodeToBlueIdInput.getListElement(nodes.get(i), i));
        }
        return BlueIdCalculator.INSTANCE.calculate(objects);
    }

    public static String calculateUncheckedBlueId(List<Node> nodes) {
        List<Object> objects = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            objects.add(NodeToMapListOrValue.get(node));
        }
        return BlueIdCalculator.INSTANCE.calculate(objects);
    }

    public static String calculateBlueIdAllowingCyclicPlaceholders(List<Node> nodes) {
        List<Object> objects = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            objects.add(NodeToBlueIdInput.getListElementAllowingCyclicPlaceholders(nodes.get(i), i));
        }
        return BlueIdCalculator.INSTANCE.calculate(objects);
    }

    public String calculate(Object object) {
        // we invoke calculateCleanedObject method only once (for root)
        Object cleaned = cleanRoot(object);
        return calculateCleanedObject(cleaned);
    }

    private String calculateCleanedObject(Object cleanedObject) {
        if (cleanedObject instanceof String || cleanedObject instanceof Number || cleanedObject instanceof Boolean) {
            // A bare scalar at any semantic child position is scalar-node
            // sugar. It has the same identity as the explicit typed scalar
            // node, never the identity of the raw JSON token.
            return calculateMap(typedScalarNode(cleanedObject));
        } else if (cleanedObject instanceof Map) {
            return calculateMap((Map<String, Object>) cleanedObject);
        } else if (cleanedObject instanceof List) {
            return calculateList((List<Object>) cleanedObject);
        }
        throw new IllegalArgumentException(
                "Object must be a String, Number, Boolean, List or Map - found " + cleanedObject.getClass());
    }

    private Map<String, Object> typedScalarNode(Object value) {
        String typeBlueId;
        Object canonicalValue = value;
        if (value instanceof String) {
            typeBlueId = TEXT_TYPE_BLUE_ID;
        } else if (value instanceof Boolean) {
            typeBlueId = BOOLEAN_TYPE_BLUE_ID;
        } else if (value instanceof BigDecimal
                || value instanceof Float
                || value instanceof Double) {
            typeBlueId = DOUBLE_TYPE_BLUE_ID;
            canonicalValue = BlueNumbers.toCanonicalDoubleValue(value);
        } else if (value instanceof Number) {
            typeBlueId = INTEGER_TYPE_BLUE_ID;
            BigInteger integer = value instanceof BigInteger
                    ? (BigInteger) value
                    : BigInteger.valueOf(((Number) value).longValue());
            BigInteger lowerBound = BigInteger.valueOf(-9007199254740991L);
            BigInteger upperBound = BigInteger.valueOf(9007199254740991L);
            canonicalValue = integer.compareTo(lowerBound) < 0
                    || integer.compareTo(upperBound) > 0
                    ? integer.toString()
                    : integer;
        } else {
            throw new IllegalArgumentException(
                    "Blue scalar must be Text, Integer, Double, or Boolean.");
        }

        Map<String, Object> type = new LinkedHashMap<>();
        type.put(OBJECT_BLUE_ID, typeBlueId);
        Map<String, Object> scalar = new LinkedHashMap<>();
        scalar.put(OBJECT_TYPE, type);
        scalar.put(OBJECT_VALUE, canonicalValue);
        return scalar;
    }

    private String calculateMap(Map<String, Object> map) {
        if (map.size() == 1 && map.containsKey(OBJECT_BLUE_ID)) {
            return (String) map.get(OBJECT_BLUE_ID);
        }

        Map<String, Object> hashes = new TreeMap<>(String::compareTo);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (OBJECT_NAME.equals(key) || OBJECT_VALUE.equals(key) || OBJECT_DESCRIPTION.equals(key)) {
                hashes.put(key, entry.getValue());
            } else {
                String blueId = calculateCleanedObject(entry.getValue());
                hashes.put(key, Collections.singletonMap("blueId", blueId));
            }
        }
        return hashProvider.apply(hashes);
    }

    private String calculateList(List<Object> list) {
        String accumulator = hashProvider.apply(Collections.singletonMap("$list", "empty"));
        int start = 0;
        if (!list.isEmpty() && isPreviousControl(list.get(0))) {
            accumulator = previousBlueId(list.get(0));
            start = 1;
        }
        for (int i = start; i < list.size(); i++) {
            Object element = list.get(i);
            // $empty is a list-control marker, not a Boolean scalar payload.
            // Its marker value therefore follows the raw map-value hash rule.
            String elementHash = isEmptyPlaceholder(element)
                    ? calculateEmptyPlaceholder()
                    : calculateCleanedObject(element);
            Map<String, Object> cons = new TreeMap<>(String::compareTo);
            cons.put("elem", Collections.singletonMap("blueId", elementHash));
            cons.put("prev", Collections.singletonMap("blueId", accumulator));
            accumulator = hashProvider.apply(Collections.singletonMap("$listCons", cons));
        }
        return accumulator;
    }

    private boolean isEmptyPlaceholder(Object element) {
        if (!(element instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) element;
        return map.size() == 1
                && Boolean.TRUE.equals(map.get(LIST_CONTROL_EMPTY));
    }

    private String calculateEmptyPlaceholder() {
        Map<String, Object> helper = new TreeMap<>(String::compareTo);
        helper.put(LIST_CONTROL_EMPTY,
                Collections.singletonMap("blueId", hashProvider.apply(Boolean.TRUE)));
        return hashProvider.apply(helper);
    }

    private Object cleanRoot(Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Root null is not valid BlueId input.");
        }
        if (obj instanceof Map) {
            return cleanMap((Map<String, Object>) obj, true);
        }
        if (obj instanceof List) {
            return cleanList((List<Object>) obj);
        }
        return obj;
    }

    private Object cleanObjectField(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Map) {
            Map<String, Object> cleaned = cleanMap((Map<String, Object>) obj, false);
            return ((Map<?, ?>) cleaned).isEmpty() ? null : cleaned;
        }
        if (obj instanceof List) {
            return cleanList((List<Object>) obj);
        }
        return obj;
    }

    private Object cleanListElement(Object obj, int index) {
        if (obj == null) {
            throw new IllegalArgumentException("Direct BlueId input must use { \"$empty\": true } for null list placeholders.");
        }
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            if (map.containsKey(LIST_CONTROL_EMPTY)) {
                validateEmptyPlaceholder(map);
            }
            if (map.isEmpty()) {
                throw new IllegalArgumentException("Direct BlueId input must use { \"$empty\": true } for empty object list placeholders.");
            }
            Object cleaned = cleanMap(map, false);
            if (((Map<?, ?>) cleaned).isEmpty()) {
                throw new IllegalArgumentException("Direct BlueId input must use { \"$empty\": true } for empty object list placeholders.");
            }
            return cleaned;
        }
        if (obj instanceof List) {
            return cleanList((List<Object>) obj);
        }
        return obj;
    }

    private Map<String, Object> cleanMap(Map<String, Object> map, boolean root) {
        if (map.containsKey(LIST_CONTROL_POS)) {
            throw new IllegalArgumentException("\"$pos\" overlays are not valid direct BlueId input.");
        }
        if (map.containsKey(LIST_CONTROL_REPLACE)) {
            throw new IllegalArgumentException("\"$replace\" overlays are not valid direct BlueId input.");
        }
        if (map.containsKey(LIST_CONTROL_PREVIOUS) && !isPreviousControl(map)) {
            throw new IllegalArgumentException("\"$previous\" must have shape { blueId: <PrevListBlueId> } and appear only as the first list item.");
        }
        Map<String, Object> cleanedMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object cleanedValue = cleanObjectField(entry.getValue());
            if (cleanedValue != null) {
                cleanedMap.put(entry.getKey(), cleanedValue);
            }
        }
        if (root || !cleanedMap.isEmpty()) {
            return cleanedMap;
        }
        return cleanedMap;
    }

    private Object cleanList(List<Object> list) {
        List<Object> cleanedList = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (i == 0 && isPreviousControl(item)) {
                cleanedList.add(item);
                continue;
            }
            if (hasInvalidPreviousControl(item) || isPreviousControl(item)) {
                throw new IllegalArgumentException("\"$previous\" must appear only as the first list item.");
            }
            cleanedList.add(cleanListElement(item, i));
        }
        return cleanedList;
    }

    private void validateEmptyPlaceholder(Map<String, Object> map) {
        if (map.size() == 1 && Boolean.TRUE.equals(map.get(LIST_CONTROL_EMPTY))) {
            return;
        }
        throw new IllegalArgumentException("\"$empty\" list placeholder must have exact shape { \"$empty\": true }.");
    }

    private boolean isPreviousControl(Object item) {
        if (!(item instanceof Map)) {
            return false;
        }
        Map<String, Object> map = (Map<String, Object>) item;
        return map.size() == 1
                && map.containsKey(LIST_CONTROL_PREVIOUS)
                && map.get(LIST_CONTROL_PREVIOUS) instanceof Map
                && ((Map<?, ?>) map.get(LIST_CONTROL_PREVIOUS)).size() == 1
                && ((Map<?, ?>) map.get(LIST_CONTROL_PREVIOUS)).containsKey(OBJECT_BLUE_ID)
                && ((Map<?, ?>) map.get(LIST_CONTROL_PREVIOUS)).get(OBJECT_BLUE_ID) instanceof String;
    }

    private boolean hasInvalidPreviousControl(Object item) {
        return item instanceof Map
                && ((Map<?, ?>) item).containsKey(LIST_CONTROL_PREVIOUS)
                && !isPreviousControl(item);
    }

    private String previousBlueId(Object item) {
        Map<String, Object> map = (Map<String, Object>) item;
        Map<String, Object> previous = (Map<String, Object>) map.get(LIST_CONTROL_PREVIOUS);
        return (String) previous.get(OBJECT_BLUE_ID);
    }

}
