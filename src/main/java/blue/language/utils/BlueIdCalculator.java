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

    public static String calculateBlueId(Node node, NodeToObject.Strategy strategy) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToObject.get(node, strategy));
    }
    public static String calculateBlueId(Node node) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToObject.get(node));
    }

    public static String calculateBlueId(List<Node> nodes) {
        List<Object> objects = nodes.stream()
                .map(NodeToObject::get)
                .collect(Collectors.toList());
        return BlueIdCalculator.INSTANCE.calculate(objects);
    }

    public String calculate(Object object) {
        if (object instanceof String || object instanceof Number || object instanceof Boolean) {
            return hashProvider.apply(object.toString());
        } else if (object instanceof Map) {
            return calculateMap((Map<String, Object>) object);
        } else if (object instanceof List) {
            return calculateList((List<Object>) object);
        }
        throw new IllegalArgumentException("Object must be a String, Number, Boolean, List or Map - found " + object.getClass());
    }

    private String calculateMap(Map<String, Object> map) {
        if (map.containsKey(OBJECT_BLUE_ID))
            return (String) map.get(OBJECT_BLUE_ID);

        Map<String, Object> hashes = map.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            String key = entry.getKey();
                            if (OBJECT_NAME.equals(key) || OBJECT_VALUE.equals(key) || OBJECT_REF.equals(key))
                                return entry.getValue();
                            return calculate(entry.getValue());
                        },
                        (oldValue, newValue) -> newValue,
                        LinkedHashMap::new
                ));
        return hashProvider.apply(hashes);
    }

    private String calculateList(List<Object> list) {
        if (list.isEmpty())
            throw new IllegalArgumentException("List must not be empty.");
        if (list.size() == 1)
            return calculate(list.get(0));

        List<Object> subList = list.subList(0, list.size() - 1);
        String hashOfSubList = calculateList(subList);

        Object lastElement = list.get(list.size() - 1);
        String hashOfLastElement = calculate(lastElement);

        return hashProvider.apply(Arrays.asList(hashOfSubList, hashOfLastElement));
    }

}