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
            boolean hasValuePayload = false;
            boolean hasItemsPayload = false;
            boolean hasSchema = false;

            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                switch (key) {
                    case OBJECT_NAME:
                        obj.name(value.isNull() ? null : value.asText());
                        break;
                    case OBJECT_DESCRIPTION:
                        obj.description(value.isNull() ? null : value.asText());
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
                    case OBJECT_MERGE_POLICY:
                        obj.mergePolicy(value.isNull() ? null : value.asText());
                        break;
                    case OBJECT_VALUE:
                        hasValuePayload = true;
                        obj.value(handleValue(value));
                        break;
                    case OBJECT_BLUE_ID:
                        if (node.size() != 1) {
                            throw new IllegalArgumentException("\"blueId\" nodes must be reference-only and cannot contain sibling fields.");
                        }
                        obj.blueId(value.asText());
                        break;
                    case OBJECT_ITEMS:
                        hasItemsPayload = true;
                        obj.items(handleArray(value));
                        break;
                    case OBJECT_BLUE:
                        obj.blue(handleNode(value));
                        break;
                    case LIST_CONTROL_PREVIOUS:
                        if (node.size() != 1) {
                            throw new IllegalArgumentException("\"$previous\" list anchors must be single-key list items.");
                        }
                        obj.previousBlueId(handlePreviousBlueId(value));
                        break;
                    case LIST_CONTROL_POS:
                        obj.position(handlePosition(value));
                        break;
                    case OBJECT_SCHEMA:
                    case "constraints":
                        if (hasSchema) {
                            throw new IllegalArgumentException("A Blue node cannot contain both \"schema\" and legacy \"constraints\".");
                        }
                        hasSchema = true;
                        obj.schema(handleSchema(value));
                        break;
                    default:
                        if ("properties".equals(key)) {
                            throw new IllegalArgumentException("\"properties\" is an internal field and must not appear in Blue documents.");
                        }
                        properties.put(key, handleNode(value));
                        break;
                }
            }
            int payloadKinds = 0;
            if (hasValuePayload) payloadKinds++;
            if (hasItemsPayload) payloadKinds++;
            if (!properties.isEmpty()) payloadKinds++;
            if (payloadKinds > 1) {
                throw new IllegalArgumentException("A Blue node may contain only one payload kind: value, items, or object fields.");
            }
            if (obj.getPosition() != null && node.size() == 1) {
                throw new IllegalArgumentException("\"$pos\" items must contain an overlay.");
            }
            validateMergePolicy(obj.getMergePolicy());
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
            return node.bigIntegerValue();
        } else if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isNull()) {
            return null;
        }
        throw new IllegalArgumentException("Can't handle node: " + node);
    }

    private String handlePreviousBlueId(JsonNode node) {
        if (!node.isObject() || node.size() != 1 || !node.has(OBJECT_BLUE_ID)) {
            throw new IllegalArgumentException("\"$previous\" must have shape { blueId: <PrevListBlueId> }.");
        }
        JsonNode blueId = node.get(OBJECT_BLUE_ID);
        if (!blueId.isTextual()) {
            throw new IllegalArgumentException("\"$previous.blueId\" must be a string.");
        }
        return blueId.asText();
    }

    private Integer handlePosition(JsonNode node) {
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("\"$pos\" must be a non-negative integer.");
        }
        BigInteger position = node.bigIntegerValue();
        if (position.signum() < 0 || position.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("\"$pos\" must be a non-negative integer.");
        }
        return position.intValue();
    }

    private void validateMergePolicy(String mergePolicy) {
        if (mergePolicy == null) {
            return;
        }
        if (!LIST_MERGE_POLICY_POSITIONAL.equals(mergePolicy) && !LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
            throw new IllegalArgumentException("\"mergePolicy\" must be either \"positional\" or \"append-only\".");
        }
    }

    private List<Node> handleArray(JsonNode value) {
        if (value.isNull()) {
            return null;
        } else if (value.isObject()) {
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

    private Schema handleSchema(JsonNode schemaNode) {
        return UncheckedObjectMapper.YAML_MAPPER.convertValue(schemaNode, Schema.class);
    }
}
