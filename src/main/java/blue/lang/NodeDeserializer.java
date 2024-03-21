package blue.lang;

import blue.lang.utils.BlueId;
import blue.lang.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static blue.lang.utils.Properties.*;

public class NodeDeserializer extends StdDeserializer<Node> {

    protected NodeDeserializer() {
        super(Node.class);
    }

    @Override
    public Node deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        TreeNode treeNode = p.readValueAsTree();
        Node obj = new Node();
        Map<String, Node> properties = new LinkedHashMap<>();

        if (treeNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = ((JsonNode) treeNode).fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (OBJECT_NAME.equals(key))
                    obj.name(value.asText());
                else if (OBJECT_DESCRIPTION.equals(key))
                    obj.description(value.asText());
                else if (OBJECT_TYPE.equals(key))
                    obj.type(handleType(value));
                else if(OBJECT_VALUE.equals(key))
                    obj.value(handleValue(value));
                else if(OBJECT_BLUE_ID.equals(key))
                    obj.blueId(value.asText());
                else if(OBJECT_REF.equals(key))
                    obj.ref(value.asText());
                else if(OBJECT_ITEMS.equals(key))
                    obj.items(handleArray(value).getItems());
                else
                    properties.put(key, handleNode(value));
            }
        }

        obj.properties(properties);
        return obj;
    }

    private Node handleType(JsonNode value) {
        if (value.isTextual()) {
            if (BlueId.isPotentialBlueId(value.asText()))
                return new Node().blueId(value.asText());
            else
                return new Node().name(value.asText());
        }
        else
            return handleNode(value);
    }

    private Node handleNode(JsonNode node) {
        if (node.isTextual() && BlueId.isPotentialBlueId(node.asText()))
            return new Node().blueId(node.asText());
        else if (node.isObject())
            return UncheckedObjectMapper.YAML_MAPPER.treeToValue(node, Node.class);
        else if (node.isArray())
            return handleArray(node);
        else return new Node().value(handleValue(node));
    }

    private Object handleValue(JsonNode node) {
        if (node.isTextual())
            return node.asText();
        else if (node.isBigInteger() || node.isInt() || node.isLong())
            return node.bigIntegerValue();
        else if (node.isFloatingPointNumber())
            return node.decimalValue();
        else if (node.isBoolean())
            return node.asBoolean();
        else if (node.isNull())
            return null;
        throw new IllegalArgumentException("Can't handle node: " + node);
    }

    private Node handleArray(JsonNode value) {
        ArrayNode arrayNode = (ArrayNode) value;
        List<Node> result = StreamSupport.stream(arrayNode.spliterator(), false)
                .map(this::handleNode)
                .collect(Collectors.toList());
        return new Node().items(result);
    }

}