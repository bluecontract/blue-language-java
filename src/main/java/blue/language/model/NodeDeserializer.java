package blue.language.model;

import blue.language.utils.UncheckedObjectMapper;
import blue.language.utils.BlueNumbers;
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

    private static final Set<String> ALLOWED_SCHEMA_KEYS = new HashSet<>(Arrays.asList(
            "blueId",
            "required",
            "minLength",
            "maxLength",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minItems",
            "maxItems",
            "uniqueItems",
            "minFields",
            "maxFields",
            "enum"
    ));

    protected NodeDeserializer() {
        super(Node.class);
    }

    @Override
    public Node deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode treeNode = p.readValueAsTree();
        return handleNode(treeNode, "/", true);
    }

    private Node handleNode(JsonNode node, String path, boolean root) {
        if (node == null || node.isNull()) {
            if (root) {
                throw new IllegalArgumentException("Root null is not a valid Blue document.");
            }
            return new Node().value(null).inlineValue(true);
        }
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
                        rejectNullReserved(value, key, appendPath(path, key));
                        obj.name(requireString(value, key, appendPath(path, key)));
                        break;
                    case OBJECT_DESCRIPTION:
                        rejectNullReserved(value, key, appendPath(path, key));
                        obj.description(requireString(value, key, appendPath(path, key)));
                        break;
                    case OBJECT_TYPE:
                        rejectNullReserved(value, key, appendPath(path, key));
                        obj.type(handleNode(value, appendPath(path, key), false));
                        break;
                    case OBJECT_ITEM_TYPE:
                        rejectNullReserved(value, key, appendPath(path, key));
                        obj.itemType(handleNode(value, appendPath(path, key), false));
                        break;
                    case OBJECT_KEY_TYPE:
                        rejectNullReserved(value, key, appendPath(path, key));
                        obj.keyType(handleNode(value, appendPath(path, key), false));
                        break;
                    case OBJECT_VALUE_TYPE:
                        rejectNullReserved(value, key, appendPath(path, key));
                        obj.valueType(handleNode(value, appendPath(path, key), false));
                        break;
                    case OBJECT_MERGE_POLICY:
                        rejectNullReserved(value, key, appendPath(path, key));
                        obj.mergePolicy(requireString(value, key, appendPath(path, key)));
                        break;
                    case OBJECT_VALUE:
                        rejectNullReserved(value, key, appendPath(path, key));
                        hasValuePayload = true;
                        obj.value(handleValue(value));
                        break;
                    case OBJECT_BLUE_ID:
                        if (node.size() != 1) {
                            throw new IllegalArgumentException("\"blueId\" nodes must be reference-only and cannot contain sibling fields.");
                        }
                        obj.blueId(requireString(value, key, appendPath(path, key)));
                        break;
                    case OBJECT_ITEMS:
                        rejectNullReserved(value, key, appendPath(path, key));
                        hasItemsPayload = true;
                        obj.items(handleArray(value, appendPath(path, key)));
                        break;
                    case OBJECT_BLUE:
                        rejectNullReserved(value, key, appendPath(path, key));
                        if (!root) {
                            throw new IllegalArgumentException("\"blue\" is valid only on the root Source Document. Path: " + appendPath(path, key));
                        }
                        if (value.isArray()) {
                            throw new IllegalArgumentException("\"blue\" must be a string or object directive. Path: " + appendPath(path, key));
                        }
                        obj.blue(handleNode(value, appendPath(path, key), false));
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
                    case LIST_CONTROL_REPLACE:
                        properties.put(key, handleNode(value, appendPath(path, key), false));
                        break;
                    case OBJECT_SCHEMA:
                        rejectNullReserved(value, key, appendPath(path, key));
                        if (hasSchema) {
                            throw new IllegalArgumentException("A Blue node cannot contain more than one \"schema\" field.");
                        }
                        hasSchema = true;
                        obj.schema(handleSchema(value, appendPath(path, key)));
                        break;
                    case OBJECT_CONTRACTS:
                        if (!value.isObject()) {
                            throw new IllegalArgumentException("\"contracts\" must be an object. Path: " + appendPath(path, key));
                        }
                        obj.contracts(handleNode(value, appendPath(path, key), false));
                        break;
                    case "constraints":
                        throw new IllegalArgumentException("\"constraints\" is not part of the Blue Language 1.0 top-level vocabulary.");
                    default:
                        if ("properties".equals(key)) {
                            throw new IllegalArgumentException("\"properties\" is an internal field and must not appear in Blue documents.");
                        }
                        properties.put(key, handleNode(value, appendPath(path, key), false));
                        break;
                }
            }
            int payloadKinds = 0;
            if (hasValuePayload) payloadKinds++;
            if (hasItemsPayload) payloadKinds++;
            if (properties.keySet().stream().anyMatch(key -> !isBlueImportsDirective(path, key))) {
                payloadKinds++;
            }
            if (payloadKinds > 1) {
                throw new IllegalArgumentException("A Blue node may contain only one payload kind: value, items, or object fields.");
            }
            if (obj.getPosition() != null && node.size() == 1) {
                throw new IllegalArgumentException("\"$pos\" items must contain an overlay.");
            }
            if (properties.containsKey(LIST_CONTROL_REPLACE)) {
                if (obj.getPosition() == null) {
                    throw new IllegalArgumentException("\"$replace\" is valid only inside a \"$pos\" list overlay. Path: " + appendPath(path, LIST_CONTROL_REPLACE));
                }
                if (node.size() != 2) {
                    throw new IllegalArgumentException("\"$replace\" cannot be combined with sibling overlay fields other than \"$pos\". Path: " + path);
                }
            }
            validateMergePolicy(obj.getMergePolicy());
            if (!properties.isEmpty()) {
                obj.properties(properties);
            }
            return obj;
        } else if (node.isArray()) {
            return new Node().items(handleArray(node, path));
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
            if (value.compareTo(lowerBound) < 0 || value.compareTo(upperBound) > 0) {
                throw new IllegalArgumentException("Unquoted integers outside [-9007199254740991, 9007199254740991] must be quoted and explicitly typed as Integer.");
            }
            return value;
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

    private List<Node> handleArray(JsonNode value, String path) {
        if (value.isArray()) {
            ArrayNode arrayNode = (ArrayNode) value;
            List<Node> items = new ArrayList<>();
            for (int i = 0; i < arrayNode.size(); i++) {
                items.add(handleNode(arrayNode.get(i), appendPath(path, i), false));
            }
            return items;
        } else {
            throw new IllegalArgumentException("\"items\" must be a list. Path: " + path);
        }
    }

    private Schema handleSchema(JsonNode schemaNode, String path) {
        if (schemaNode == null || schemaNode.isNull()) {
            return null;
        }
        if (!schemaNode.isObject()) {
            throw new IllegalArgumentException("\"schema\" must be an object. Path: " + path);
        }
        if (schemaNode.has(OBJECT_BLUE_ID)) {
            if (schemaNode.size() != 1) {
                throw new IllegalArgumentException("\"schema.blueId\" must be a pure reference without sibling keywords. Path: " + path);
            }
            JsonNode blueId = schemaNode.get(OBJECT_BLUE_ID);
            if (!blueId.isTextual()) {
                throw new IllegalArgumentException("\"schema.blueId\" must be a string. Path: "
                        + appendPath(path, OBJECT_BLUE_ID));
            }
            return new Schema().blueId(blueId.asText());
        }
        for (Iterator<String> it = schemaNode.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!ALLOWED_SCHEMA_KEYS.contains(key)) {
                throw new IllegalArgumentException("\"schema." + key + "\" is not part of the Blue language core.");
            }
        }
        validateSchemaValueShapes(schemaNode, path);
        return UncheckedObjectMapper.YAML_MAPPER.convertValue(schemaNode, Schema.class);
    }

    public static Schema parseSchema(JsonNode schemaNode, String path) {
        return new NodeDeserializer().handleSchema(schemaNode, path);
    }

    private void validateSchemaValueShapes(JsonNode schemaNode, String path) {
        requireBooleanKeyword(schemaNode, "required", path);
        requireBooleanKeyword(schemaNode, "uniqueItems", path);

        requireNonNegativeIntegerKeyword(schemaNode, "minLength", path);
        requireNonNegativeIntegerKeyword(schemaNode, "maxLength", path);
        requireNonNegativeIntegerKeyword(schemaNode, "minItems", path);
        requireNonNegativeIntegerKeyword(schemaNode, "maxItems", path);
        requireNonNegativeIntegerKeyword(schemaNode, "minFields", path);
        requireNonNegativeIntegerKeyword(schemaNode, "maxFields", path);

        requireNumericKeyword(schemaNode, "minimum", path);
        requireNumericKeyword(schemaNode, "maximum", path);
        requireNumericKeyword(schemaNode, "exclusiveMinimum", path);
        requireNumericKeyword(schemaNode, "exclusiveMaximum", path);
        requireNumericKeyword(schemaNode, "multipleOf", path);

        JsonNode enumNode = schemaNode.get("enum");
        if (enumNode != null) {
            if (!enumNode.isArray()) {
                throw new IllegalArgumentException("\"schema.enum\" must be a list. Path: " + appendPath(path, "enum"));
            }
            for (int i = 0; i < enumNode.size(); i++) {
                requireEnumEntry(enumNode.get(i), appendPath(appendPath(path, "enum"), i));
            }
        }
    }

    private void requireBooleanKeyword(JsonNode schemaNode, String keyword, String path) {
        JsonNode value = schemaNode.get(keyword);
        if (value == null || value.isBoolean()) {
            return;
        }
        throw new IllegalArgumentException("\"schema." + keyword + "\" must be a boolean. Path: " + appendPath(path, keyword));
    }

    private void requireEnumEntry(JsonNode value, String path) {
        if (value == null || value.isNull() || value.isArray()) {
            throw new IllegalArgumentException("\"schema.enum\" entries must be scalar values or explicit scalar nodes. Path: " + path);
        }
        if (!value.isObject()) {
            return;
        }
        if (value.size() == 0 || value.has(LIST_CONTROL_EMPTY)) {
            throw new IllegalArgumentException("\"schema.enum\" entries must be scalar values or explicit scalar nodes. Path: " + path);
        }
        Node enumNode = handleNode(value, path, false);
        if (!isExplicitSchemaScalar(enumNode, true)) {
            throw new IllegalArgumentException("\"schema.enum\" entries must be scalar values or explicit scalar nodes. Path: " + path);
        }
    }

    private void requireNonNegativeIntegerKeyword(JsonNode schemaNode, String keyword, String path) {
        JsonNode value = schemaNode.get(keyword);
        if (value == null) {
            return;
        }
        BigInteger integer = null;
        if (value.isIntegralNumber()) {
            integer = value.bigIntegerValue();
        } else if (value.isObject()) {
            Node integerNode = handleNode(value, appendPath(path, keyword), false);
            if (isExplicitSchemaScalar(integerNode, true)
                    && integerNode.getValue() instanceof BigInteger
                    && (integerNode.getType() == null
                    || isIntegerType(integerNode.getType()))) {
                integer = (BigInteger) integerNode.getValue();
            }
        }
        if (integer == null) {
            throw new IllegalArgumentException("\"schema." + keyword + "\" must be a non-negative integer. Path: " + appendPath(path, keyword));
        }
        if (integer.signum() < 0
                || integer.compareTo(BigInteger.valueOf(9007199254740991L)) > 0) {
            throw new IllegalArgumentException("\"schema." + keyword + "\" must be a non-negative integer in the interoperable range. Path: " + appendPath(path, keyword));
        }
    }

    private void requireNumericKeyword(JsonNode schemaNode, String keyword, String path) {
        JsonNode value = schemaNode.get(keyword);
        if (value == null) {
            return;
        }
        if (value.isNumber()) {
            if (value.isIntegralNumber()) {
                BigInteger integer = value.bigIntegerValue();
                BigInteger lowerBound = BigInteger.valueOf(-9007199254740991L);
                BigInteger upperBound = BigInteger.valueOf(9007199254740991L);
                if (integer.compareTo(lowerBound) < 0 || integer.compareTo(upperBound) > 0) {
                    throw new IllegalArgumentException("\"schema." + keyword + "\" unquoted integer is outside the interoperable range. Path: " + appendPath(path, keyword));
                }
            }
            return;
        }
        if (value.isObject()) {
            Node numericNode = handleNode(value, appendPath(path, keyword), false);
            if (isExplicitNumericValue(numericNode)) {
                return;
            }
        }
        throw new IllegalArgumentException("\"schema." + keyword + "\" must be numeric or an explicit numeric scalar node. Path: " + appendPath(path, keyword));
    }

    private boolean isExplicitNumericValue(Node node) {
        if (!isExplicitSchemaScalar(node, true)) {
            return false;
        }
        if (node.getValue() instanceof Number) {
            return node.getType() == null || isNumericType(node.getType());
        }
        if (!(node.getRawValue() instanceof String)) {
            return false;
        }
        String value = (String) node.getRawValue();
        if (isIntegerType(node.getType())) {
            return parseExplicitInteger(value) != null;
        }
        if (isDoubleType(node.getType())) {
            BlueNumbers.toCanonicalDoubleValue(value);
            return true;
        }
        return false;
    }

    private boolean isExplicitSchemaScalar(Node node, boolean allowType) {
        if (node == null || node.getValue() == null) {
            return false;
        }
        if ((!allowType && node.getType() != null)
                || node.getName() != null
                || node.getDescription() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getItems() != null
                || node.getProperties() != null
                || node.getContracts() != null
                || node.getBlueId() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null) {
            return false;
        }
        return node.getType() == null || isScalarType(node.getType());
    }

    private boolean isScalarType(Node type) {
        return isCoreType(type, TEXT_TYPE_BLUE_ID, "Text")
                || isCoreType(type, INTEGER_TYPE_BLUE_ID, "Integer")
                || isCoreType(type, DOUBLE_TYPE_BLUE_ID, "Double")
                || isCoreType(type, BOOLEAN_TYPE_BLUE_ID, "Boolean");
    }

    private boolean isNumericType(Node type) {
        return isIntegerType(type) || isDoubleType(type);
    }

    private BigInteger parseExplicitInteger(String value) {
        if (!isCanonicalDecimalInteger(value)) {
            throw new IllegalArgumentException("Explicit Integer scalar values must be canonical decimal strings.");
        }
        return new BigInteger(value);
    }

    private boolean isCanonicalDecimalInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int index = value.charAt(0) == '-' ? 1 : 0;
        if (index == value.length()) {
            return false;
        }
        char firstDigit = value.charAt(index);
        if (firstDigit == '0') {
            return index + 1 == value.length();
        }
        if (firstDigit < '1' || firstDigit > '9') {
            return false;
        }
        for (index++; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return true;
    }

    private boolean isIntegerType(Node type) {
        return isCoreType(type, INTEGER_TYPE_BLUE_ID, "Integer");
    }

    private boolean isDoubleType(Node type) {
        return isCoreType(type, DOUBLE_TYPE_BLUE_ID, "Double");
    }

    private boolean isCoreType(Node type, String blueId, String alias) {
        if (type == null) {
            return false;
        }
        if (blueId.equals(type.getBlueId())) {
            return true;
        }
        return type.isInlineValue() && alias.equals(type.getValue());
    }

    private void rejectNullReserved(JsonNode node, String field, String path) {
        if (node.isNull()) {
            throw new IllegalArgumentException("\"" + field + "\" must not be null; omit the field instead. Path: " + path);
        }
    }

    private String requireString(JsonNode node, String field, String path) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException("\"" + field + "\" must be a string. Path: " + path);
        }
        return node.asText();
    }

    private String appendPath(String path, String segment) {
        String prefix = path == null || path.isEmpty() ? "/" : path;
        if ("/".equals(prefix)) {
            return "/" + escapePathSegment(segment);
        }
        return prefix + "/" + escapePathSegment(segment);
    }

    private String appendPath(String path, int index) {
        return appendPath(path, String.valueOf(index));
    }

    private boolean isBlueImportsDirective(String path, String key) {
        return "/blue".equals(path) && "imports".equals(key);
    }

    private String escapePathSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
