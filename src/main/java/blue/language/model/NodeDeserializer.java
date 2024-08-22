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
                        obj.type(handleNode(value));
                        break;
                    case OBJECT_ITEM_TYPE:
                        obj.itemType(handleNode(value));
                        break;
                    case OBJECT_KEY_TYPE:
                        obj.keyType(handleNode(value));
                        break;
                    case OBJECT_VALUE_TYPE:
                        obj.valueType(handleNode(value));
                        break;
                    case OBJECT_VALUE:
                        obj.value(handleValue(value));
                        break;
                    case OBJECT_BLUE_ID:
                        obj.blueId(value.asText());
                        break;
                    case OBJECT_ITEMS:
                        obj.items(handleArray(value));
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
            if (!properties.isEmpty()) {
                obj.properties(properties);
            }
            return obj;
        } else if (node.isArray()) {
            return new Node().items(handleArray(node));
        } else {
            return new Node().value(handleValue(node)).inlineValue(true);
        }
    }

    private Object handleValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isBigInteger() || node.isInt() || node.isLong()) {
            BigInteger value = node.bigIntegerValue();
            BigInteger lowerBound = BigInteger.valueOf(-9007199254740991L);
            BigInteger upperBound = BigInteger.valueOf(9007199254740991L);

            if (value.compareTo(lowerBound) < 0) {
                return lowerBound;
            } else if (value.compareTo(upperBound) > 0) {
                return upperBound;
            } else {
                return value;
            }
        } else if (node.isFloatingPointNumber()) {
            double doubleValue = node.doubleValue();
            return new BigDecimal(Double.toString(doubleValue));
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isNull()) {
            return null;
        }
        throw new IllegalArgumentException("Can't handle node: " + node);
    }

    private List<Node> handleArray(JsonNode value) {
        if (value.isObject()) {
            List<Node> singleItemList = new ArrayList<>();
            singleItemList.add(handleNode(value));
            return singleItemList;
        } else if (value.isArray()) {
            ArrayNode arrayNode = (ArrayNode) value;
            return StreamSupport.stream(arrayNode.spliterator(), false)
                    .map(this::handleNode)
                    .collect(Collectors.toList());
        } else {
            throw new IllegalArgumentException("Expected an array node");
        }
    }

    private Constraints handleConstraints(JsonNode constraintsNode) {
        return UncheckedObjectMapper.YAML_MAPPER.convertValue(constraintsNode, Constraints.class);
    }
}