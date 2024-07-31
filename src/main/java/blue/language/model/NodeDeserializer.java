package blue.language.model;

import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static blue.language.utils.Properties.*;

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
        if (node.isObject()) {
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
                        obj.type(handleTypeNode(value));
                        break;
                    case OBJECT_ITEM_TYPE:
                        obj.itemType(handleTypeNode(value));
                        break;
                    case OBJECT_KEY_TYPE:
                        obj.keyType(handleTypeNode(value));
                        break;
                    case OBJECT_VALUE_TYPE:
                        obj.valueType(handleTypeNode(value));
                        break;
                    case OBJECT_VALUE:
                        obj.value(handleValueWithType(value, obj.getType()));
                        break;
                    case OBJECT_BLUE_ID:
                        obj.blueId(value.asText());
                        break;
                    case OBJECT_ITEMS:
                        obj.items(handleArray(value).getItems());
                        break;
                    case OBJECT_BLUE:
                        obj.blue(handleNode(value));
                        break;
                    case OBJECT_CONSTRAINTS:
                        obj.constraints(handleConstraints(value));
                        break;
                    default:
                        properties.put(key, handleNode(value));
                        break;
                }
            }
            obj.properties(properties);
            obj.inlineValue(false);
            return obj;
        } else if (node.isArray()) {
            return handleArray(node);
        } else {
            return new Node().value(handleValue(node)).inlineValue(true);
        }
    }

    private Node handleTypeNode(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            String typeValue = typeNode.asText();
            if (CORE_TYPES.contains(typeValue)) {
                return new Node().blueId(CORE_TYPE_NAME_TO_BLUE_ID_MAP.get(typeValue));
            } else {
                return new Node().value(typeValue).inlineValue(true);
            }
        } else {
            return handleNode(typeNode);
        }
    }

    private Object handleValueWithType(JsonNode valueNode, Node typeNode) {
        if (typeNode == null || typeNode.getBlueId() == null) {
            return handleValue(valueNode);
        }

        String typeBlueId = typeNode.getBlueId();
        if (TEXT_TYPE_BLUE_ID.equals(typeBlueId)) {
            return valueNode.asText();
        } else if (INTEGER_TYPE_BLUE_ID.equals(typeBlueId)) {
            return valueNode.isTextual() ? new BigInteger(valueNode.asText()) : valueNode.bigIntegerValue();
        } else if (NUMBER_TYPE_BLUE_ID.equals(typeBlueId)) {
            return valueNode.isTextual() ? new BigDecimal(valueNode.asText()) : valueNode.decimalValue();
        } else if (BOOLEAN_TYPE_BLUE_ID.equals(typeBlueId)) {
            return valueNode.isTextual() ? Boolean.parseBoolean(valueNode.asText()) : valueNode.booleanValue();
        } else {
            return handleValue(valueNode);
        }
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
        if (value.isTextual()) {
            List<Node> singleItemList = new ArrayList<>();
            singleItemList.add(new Node().value(value.asText()).inlineValue(true));
            return new Node().items(singleItemList).inlineValue(false);
        } else if (value.isArray()) {
            ArrayNode arrayNode = (ArrayNode) value;
            List<Node> result = StreamSupport.stream(arrayNode.spliterator(), false)
                    .map(this::handleNode)
                    .collect(Collectors.toList());
            return new Node().items(result).inlineValue(false);
        } else
            throw new IllegalArgumentException("The 'items' field must be an array or a blueId.");
    }

    private Constraints handleConstraints(JsonNode constraintsNode) {
        return UncheckedObjectMapper.YAML_MAPPER.convertValue(constraintsNode, Constraints.class);
    }
}