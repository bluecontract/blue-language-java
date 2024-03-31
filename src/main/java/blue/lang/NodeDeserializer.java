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
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static blue.lang.utils.Properties.*;
import static java.util.Collections.singletonList;

public class NodeDeserializer extends StdDeserializer<Node> {

    protected NodeDeserializer() {
        super(Node.class);
    }

    @Override
    public Node deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode treeNode = p.readValueAsTree();
        return handleNode(treeNode);
    }

    private Node handleNode(JsonNode node) {
        if (node.isTextual() && BlueId.isPotentialBlueId(node.asText()))
            return new Node().blueId(node.asText());
        else if (node.isObject()) {
            Node obj = new Node();
            Map<String, Node> properties = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                switch (key) {
                    case OBJECT_NAME:
                        obj.name(value.asText());
                        break;
                    case OBJECT_DESCRIPTION:
                        obj.description(value.asText());
                        break;
                    case OBJECT_TYPE:
                        obj.type(handleType(value));
                        break;
                    case OBJECT_VALUE:
                        obj.value(handleValue(value));
                        break;
                    case OBJECT_BLUE_ID:
                        obj.blueId(value.asText());
                        break;
                    case OBJECT_REF:
                        obj.ref(value.asText());
                        break;
                    case OBJECT_ITEMS:
                        obj.items(handleArray(value).getItems());
                        break;
                    default:
                        properties.put(key, handleNode(value));
                        break;
                }
            }
            obj.properties(properties);
            return obj;
        } else if (node.isArray())
            return handleArray(node);
        else
            return new Node().value(handleValue(node));
    }

    private Node handleType(JsonNode value) {
        if (value.isTextual()) {
            if (BlueId.isPotentialBlueId(value.asText()))
                return new Node().blueId(value.asText());
            else
                return new Node().name(value.asText());
        } else
            return handleNode(value);
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
        if (value.isTextual() && BlueId.isPotentialBlueId(value.asText())) {
            List<Node> singleItemList = new ArrayList<>();
            singleItemList.add(new Node().blueId(value.asText()));
            return new Node().items(singleItemList);
        } else if (value.isArray()) {
            ArrayNode arrayNode = (ArrayNode) value;
            List<Node> result = StreamSupport.stream(arrayNode.spliterator(), false)
                    .map(this::handleNode)
                    .collect(Collectors.toList());
            return new Node().items(result);
        } else
            throw new IllegalArgumentException("The 'items' field must be an array or a blueId.");
    }

}