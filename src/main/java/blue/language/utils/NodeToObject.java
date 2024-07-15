package blue.language.utils;

import blue.language.model.Node;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.NodeToObject.Strategy.*;
import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class NodeToObject {

    public enum Strategy {
        OFFICIAL,
        SIMPLE,
        SIMPLE_NO_TYPE
    }

    public static Object get(Node node) {
        return get(node, OFFICIAL);
    }

    public static Object get(Node node, Strategy strategy) {

        if (node.getValue() != null && isStrategySimple(strategy))
            return node.getValue();

        List<Object> items = node.getItems() == null ? null :
                node.getItems().stream()
                        .map(item -> get(item, strategy))
                        .collect(Collectors.toList());
        if (items != null && isStrategySimple(strategy))
            return items;

        Map<String, Object> result = new LinkedHashMap<>();
        if (node.getName() != null)
            result.put(OBJECT_NAME, node.getName());
        if (node.getDescription() != null)
            result.put(OBJECT_DESCRIPTION, node.getDescription());
        if (node.getType() != null && strategy != SIMPLE_NO_TYPE)
            result.put(OBJECT_TYPE, get(node.getType()));
        if (node.getValue() != null)
            result.put(OBJECT_VALUE, node.getValue());
        if (items != null)
            result.put(OBJECT_ITEMS, items);
        if (node.getBlueId() != null)
            result.put(OBJECT_BLUE_ID, node.getBlueId());
        if (node.getConstraints() != null)
            result.put(OBJECT_CONSTRAINTS, YAML_MAPPER.convertValue(node.getConstraints(), new TypeReference<Map<String, Object>>() {}));
        if (node.getProperties() != null)
            node.getProperties().forEach((key, value) -> result.put(key, get(value, strategy)));
        return result;

    }

    private static boolean isStrategySimple(Strategy strategy) {
        return strategy == SIMPLE || strategy == SIMPLE_NO_TYPE;
    }

}