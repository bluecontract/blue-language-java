package blue.language.preprocess;

import blue.language.model.wire.SchemaPropertyConstants;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.wire.JsonPointer;
import blue.language.model.Nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_EMPTY;
import static blue.language.model.wire.SchemaPropertyConstants.*;

/**
 * Consumes Source nulls: object members are omitted and list elements become
 * explicit {@code $empty: true} placeholders. Exact empty objects are retained.
 *
 * <p>The transformation operates on a deep clone and applies the same rules to
 * schema values and nested metadata.</p>
 */
public class NormalizeListPlaceholders implements TransformationProcessor {

    /**
     * Creates a stateless list-placeholder normalization transformation.
     */
    public NormalizeListPlaceholders() {
    }

    @Override
    public Node process(Node document) {
        return normalizeRoot(document);
    }

    private Node normalizeRoot(Node node) {
        if (node == null) {
            return null;
        }
        if (Nodes.isSourceNullLiteral(node)) {
            throw new IllegalArgumentException(
                    "Root null is not a valid Blue document.");
        }
        return normalizeNode(node, false, JsonPointer.ROOT);
    }

    private Node normalizeObjectField(Node node, String path) {
        if (node == null || Nodes.isSourceNullLiteral(node)) {
            return null;
        }
        return normalizeNode(node, false, path);
    }

    private Node normalizeListElement(Node node, String path) {
        if (node == null || Nodes.isSourceNullLiteral(node)) {
            return Nodes.emptyPlaceholder();
        }
        if (node.getProperties() != null && node.getProperties().containsKey(LIST_CONTROL_EMPTY)) {
            Nodes.validateEmptyPlaceholder(node, path);
            return node.clone();
        }
        return normalizeNode(node, true, path);
    }

    private Node normalizeNode(Node node, boolean listElement, String path) {
        Node normalized = node.clone();

        if (listElement && normalized.getProperties() != null && normalized.getProperties().containsKey(LIST_CONTROL_EMPTY)) {
            Nodes.validateEmptyPlaceholder(normalized, path);
            return normalized;
        }

        if (Nodes.isSourceNullLiteral(normalized.getType())) {
            normalized.type((Node) null);
        } else if (normalized.getType() != null) {
            normalized.type(normalizeNode(normalized.getType(), false, append(path, BlueLanguageConstants.OBJECT_TYPE)));
        }
        if (Nodes.isSourceNullLiteral(normalized.getItemType())) {
            normalized.itemType((Node) null);
        } else if (normalized.getItemType() != null) {
            normalized.itemType(normalizeNode(normalized.getItemType(), false, append(path, BlueLanguageConstants.OBJECT_ITEM_TYPE)));
        }
        if (Nodes.isSourceNullLiteral(normalized.getKeyType())) {
            normalized.keyType((Node) null);
        } else if (normalized.getKeyType() != null) {
            normalized.keyType(normalizeNode(normalized.getKeyType(), false, append(path, BlueLanguageConstants.OBJECT_KEY_TYPE)));
        }
        if (Nodes.isSourceNullLiteral(normalized.getValueType())) {
            normalized.valueType((Node) null);
        } else if (normalized.getValueType() != null) {
            normalized.valueType(normalizeNode(normalized.getValueType(), false, append(path, BlueLanguageConstants.OBJECT_VALUE_TYPE)));
        }
        if (Nodes.isSourceNullLiteral(normalized.getBlue())) {
            normalized.blue(null);
        } else if (normalized.getBlue() != null) {
            normalized.blue(normalizeNode(normalized.getBlue(), false, append(path, BlueLanguageConstants.OBJECT_BLUE)));
        }
        if (Nodes.isSourceNullLiteral(normalized.getContracts())) {
            normalized.contracts(null);
        } else if (normalized.getContracts() != null) {
            normalized.contracts(normalizeNode(normalized.getContracts(), false, append(path, BlueLanguageConstants.OBJECT_CONTRACTS)));
        }
        if (normalized.getSchema() != null) {
            normalizeSchema(normalized.getSchema(), append(path, BlueLanguageConstants.OBJECT_SCHEMA));
        }

        if (normalized.getItems() != null) {
            List<Node> items = new ArrayList<>(normalized.getItems().size());
            for (int i = 0; i < normalized.getItems().size(); i++) {
                items.add(normalizeListElement(normalized.getItems().get(i), append(path, BlueLanguageConstants.OBJECT_ITEMS, i)));
            }
            normalized.items(items);
        }

        if (normalized.getProperties() != null) {
            Map<String, Node> properties = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry : normalized.getProperties().entrySet()) {
                Node child = normalizeObjectField(entry.getValue(), append(path, entry.getKey()));
                if (child != null) {
                    properties.put(entry.getKey(), child);
                }
            }
            if (properties.isEmpty()) {
                normalized.properties((Map<String, Node>) null);
                if (Nodes.isEmptyNode(normalized)) {
                    normalized.properties(properties);
                }
            } else {
                normalized.properties(properties);
            }
        }

        return normalized;
    }

    private void normalizeSchema(Schema schema, String path) {
        schema.required(normalizeObjectField(schema.getRequired(), append(path, KEY_REQUIRED)));
        schema.minLength(normalizeObjectField(schema.getMinLength(), append(path, KEY_MIN_LENGTH)));
        schema.maxLength(normalizeObjectField(schema.getMaxLength(), append(path, KEY_MAX_LENGTH)));
        schema.minimum(normalizeObjectField(schema.getMinimum(), append(path, KEY_MINIMUM)));
        schema.maximum(normalizeObjectField(schema.getMaximum(), append(path, KEY_MAXIMUM)));
        schema.exclusiveMinimum(normalizeObjectField(
                schema.getExclusiveMinimum(), append(path, KEY_EXCLUSIVE_MINIMUM)));
        schema.exclusiveMaximum(normalizeObjectField(
                schema.getExclusiveMaximum(), append(path, KEY_EXCLUSIVE_MAXIMUM)));
        schema.multipleOf(normalizeObjectField(schema.getMultipleOf(), append(path, KEY_MULTIPLE_OF)));
        schema.minItems(normalizeObjectField(schema.getMinItems(), append(path, KEY_MIN_ITEMS)));
        schema.maxItems(normalizeObjectField(schema.getMaxItems(), append(path, KEY_MAX_ITEMS)));
        schema.uniqueItems(normalizeObjectField(
                schema.getUniqueItems(), append(path, KEY_UNIQUE_ITEMS)));
        schema.minFields(normalizeObjectField(schema.getMinFields(), append(path, KEY_MIN_FIELDS)));
        schema.maxFields(normalizeObjectField(schema.getMaxFields(), append(path, KEY_MAX_FIELDS)));
        if (schema.getEnum() != null) {
            List<Node> enumValues = new ArrayList<>(schema.getEnum().size());
            for (int i = 0; i < schema.getEnum().size(); i++) {
                String enumPath = append(path, KEY_ENUM, i);
                Node enumValue = normalizeObjectField(schema.getEnum().get(i), enumPath);
                if (enumValue == null
                        || Nodes.isEmptyPlaceholder(enumValue)
                        || (enumValue.getProperties() != null && enumValue.getProperties().containsKey(LIST_CONTROL_EMPTY))) {
                    throw new IllegalArgumentException("schema.enum entries must be scalar values or explicit scalar nodes. Path: " + enumPath);
                }
                enumValues.add(enumValue);
            }
            schema.enumValues(enumValues);
        }
    }

    private static String append(String path, String segment) {
        return JsonPointer.append(path, segment);
    }

    private static String append(String path, String segment, int index) {
        return append(append(path, segment), String.valueOf(index));
    }
}
