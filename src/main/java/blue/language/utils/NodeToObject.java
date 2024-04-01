package blue.language.utils;

import blue.language.Node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.NodeToObject.Strategy.DOMAIN_MAPPING;
import static blue.language.utils.NodeToObject.Strategy.STANDARD;
import static blue.language.utils.Properties.*;

public class NodeToObject {

    public enum Strategy {
        STANDARD,
        DOMAIN_MAPPING
    }

    public static Object get(Node node) {
        return get(node, STANDARD);
    }

    public static Object get(Node node, Strategy strategy) {

        if (node.getValue() != null && strategy == DOMAIN_MAPPING)
            return node.getValue();

        List<Object> items = node.getItems() == null ? null :
                node.getItems().stream()
                        .map(item -> get(item, strategy))
                        .collect(Collectors.toList());
        if (items != null && strategy == DOMAIN_MAPPING)
            return items;

        Map<String, Object> result = new LinkedHashMap<>();
        if (node.getName() != null)
            result.put(OBJECT_NAME, node.getName());
        if (node.getDescription() != null)
            result.put(OBJECT_DESCRIPTION, node.getDescription());
        if (node.getType() != null)
            result.put(OBJECT_TYPE, get(node.getType()));
        if (node.getValue() != null)
            result.put(OBJECT_VALUE, node.getValue());
        if (items != null)
            result.put(OBJECT_ITEMS, items);
        if (node.getRef() != null)
            result.put(OBJECT_REF, node.getRef());
        if (node.getBlueId() != null)
            result.put(OBJECT_BLUE_ID, node.getBlueId());
        if (node.getProperties() != null)
            node.getProperties().forEach((key, value) -> result.put(key, get(value, strategy)));
        return result;

    }

}
