package blue.language.utils;

import blue.language.model.Node;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static blue.language.utils.Properties.*;

public class BlueIdCalculator {

    public static final BlueIdCalculator INSTANCE = new BlueIdCalculator(new Base58Sha256Provider());

    private Function<Object, String> hashProvider;

    public BlueIdCalculator(Function<Object, String> hashProvider) {
        this.hashProvider = hashProvider;
    }

    public static String calculateBlueId(Node node) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToMapListOrValue.get(node));
    }

    public static String calculateBlueId(List<Node> nodes) {
        List<Object> objects = nodes.stream()
                .map(NodeToMapListOrValue::get)
                .collect(Collectors.toList());
        return BlueIdCalculator.INSTANCE.calculate(objects);
    }

    public String calculate(Object object) {
        // we invoke calculateCleanedObject method only once (for root)
        Object cleaned = cleanStructure(object);
        if (cleaned == null) {
            return hashProvider.apply(Collections.emptyMap());
        }
        return calculateCleanedObject(cleaned);
    }

    private String calculateCleanedObject(Object cleanedObject) {
        if (cleanedObject instanceof String || cleanedObject instanceof Number || cleanedObject instanceof Boolean) {
            return hashProvider.apply(cleanedObject);
        } else if (cleanedObject instanceof Map) {
            return calculateMap((Map<String, Object>) cleanedObject);
        } else if (cleanedObject instanceof List) {
            return calculateList((List<Object>) cleanedObject);
        }
        throw new IllegalArgumentException(
                "Object must be a String, Number, Boolean, List or Map - found " + cleanedObject.getClass());
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
            String elementHash = calculateCleanedObject(element);
            Map<String, Object> cons = new TreeMap<>(String::compareTo);
            cons.put("elem", Collections.singletonMap("blueId", elementHash));
            cons.put("prev", Collections.singletonMap("blueId", accumulator));
            accumulator = hashProvider.apply(Collections.singletonMap("$listCons", cons));
        }
        return accumulator;
    }

    private Object cleanStructure(Object obj) {
        if (obj == null) {
            return null;
        } else if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            Map<String, Object> cleanedMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object cleanedValue = cleanStructure(entry.getValue());
                if (cleanedValue != null) {
                    cleanedMap.put(entry.getKey(), cleanedValue);
                }
            }
            return cleanedMap.isEmpty() ? null : cleanedMap;
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            List<Object> cleanedList = new ArrayList<>();
            for (Object item : list) {
                Object cleanedItem = cleanStructure(item);
                if (cleanedItem != null) {
                    cleanedList.add(cleanedItem);
                }
            }
            return normalizeListControls(cleanedList);
        } else {
            return obj;
        }
    }

    private Object normalizeListControls(List<Object> list) {
        if (list.isEmpty()) {
            return list;
        }

        List<Object> result = new ArrayList<>();
        int start = 0;
        if (isPreviousControl(list.get(0))) {
            result.add(list.get(0));
            start = 1;
        }

        Map<Integer, Object> positioned = new TreeMap<>();
        List<Object> appended = new ArrayList<>();
        boolean hasPositionControls = false;

        for (int i = start; i < list.size(); i++) {
            Object item = list.get(i);
            if (hasInvalidPreviousControl(item)) {
                throw new IllegalArgumentException("\"$previous\" must have shape { blueId: <PrevListBlueId> } and appear only as the first list item.");
            }
            if (isPreviousControl(item)) {
                throw new IllegalArgumentException("\"$previous\" must appear only as the first list item.");
            }
            if (isPositionControl(item)) {
                hasPositionControls = true;
                int position = positionValue(item);
                if (positioned.put(position, withoutPosition(item)) != null) {
                    throw new IllegalArgumentException("Duplicate \"$pos\" value in list: " + position);
                }
            } else {
                appended.add(item);
            }
        }

        if (!hasPositionControls) {
            result.addAll(list.subList(start, list.size()));
            return result;
        }

        for (Map.Entry<Integer, Object> entry : positioned.entrySet()) {
            result.add(entry.getValue());
        }
        result.addAll(appended);
        return result;
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

    private boolean isPositionControl(Object item) {
        return item instanceof Map && ((Map<?, ?>) item).containsKey(LIST_CONTROL_POS);
    }

    private int positionValue(Object item) {
        Object value = ((Map<?, ?>) item).get(LIST_CONTROL_POS);
        BigInteger position;
        if (value instanceof BigInteger) {
            position = (BigInteger) value;
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            position = BigInteger.valueOf(((Number) value).longValue());
        } else {
            throw new IllegalArgumentException("\"$pos\" must be a non-negative integer.");
        }
        if (position.signum() < 0 || position.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("\"$pos\" must be a non-negative integer.");
        }
        return position.intValue();
    }

    private Object withoutPosition(Object item) {
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) item);
        map.remove(LIST_CONTROL_POS);
        if (map.isEmpty()) {
            throw new IllegalArgumentException("\"$pos\" items must contain an overlay.");
        }
        return map;
    }

}
