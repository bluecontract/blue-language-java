package blue.lang.utils;

import blue.lang.graph.Node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.lang.utils.Properties.*;

public class NodeToObject {

    public static Object get(Node node) {

        boolean onlyValue = node.getValue() != null && node.getType() == null && node.getProperties() == null;
        if (onlyValue)
            return node.getValue();

        if (node.getItems() != null)
            return node.getItems().stream()
                    .map(NodeToObject::get)
                    .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        if (node.getName() != null)
            result.put(OBJECT_NAME, node.getName());
        if (node.getType() != null)
            result.put(OBJECT_TYPE, node.getType());
        if (node.getValue() != null)
            result.put(OBJECT_VALUE, node.getValue());
        if (node.getProperties() != null)
            node.getProperties().forEach((key, value) -> result.put(key, get(value)));
        return result;

    }

}
