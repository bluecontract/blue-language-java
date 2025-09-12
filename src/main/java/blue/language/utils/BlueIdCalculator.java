package blue.language.utils;

import blue.language.model.Node;

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
        return calculateCleanedObject(cleanStructure(object));
    }

    private String calculateCleanedObject(Object cleanedObject) {
        if (cleanedObject instanceof String || cleanedObject instanceof Number || cleanedObject instanceof Boolean) {
            return hashProvider.apply(cleanedObject.toString());
        } else if (cleanedObject instanceof Map) {
            return calculateMap((Map<String, Object>) cleanedObject);
        } else if (cleanedObject instanceof List) {
            return calculateList((List<Object>) cleanedObject);
        }
        throw new IllegalArgumentException(
                "Object must be a String, Number, Boolean, List or Map - found " + cleanedObject.getClass());
    }

    private String calculateMap(Map<String, Object> map) {
        if (map.containsKey(OBJECT_BLUE_ID)) {
            return (String) map.get(OBJECT_BLUE_ID);
        }

        Map<String, Object> hashes = new LinkedHashMap<>();
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
        if (list.size() == 1) {
            return calculateCleanedObject(list.get(0));
        }

        List<Object> subList = list.subList(0, list.size() - 1);
        String hashOfSubList = calculateList(subList);

        Object lastElement = list.get(list.size() - 1);
        String hashOfLastElement = calculateCleanedObject(lastElement);

        List<Map<String, String>> result = Arrays.asList(
                Collections.singletonMap("blueId", hashOfSubList),
                Collections.singletonMap("blueId", hashOfLastElement));
        return hashProvider.apply(result);
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
            return cleanedList.isEmpty() ? null : cleanedList;
        } else {
            return obj;
        }
    }

}