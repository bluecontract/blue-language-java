package blue.language.preprocess.processor;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.TransformationProcessor;
import blue.language.utils.Nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.utils.Properties.LIST_CONTROL_EMPTY;

public class NormalizeListPlaceholders implements TransformationProcessor {

    @Override
    public Node process(Node document) {
        return normalizeRoot(document);
    }

    private Node normalizeRoot(Node node) {
        if (node == null) {
            return null;
        }
        return normalizeNode(node, false, "/");
    }

    private Node normalizeObjectField(Node node, String path) {
        if (node == null) {
            return null;
        }
        Node normalized = normalizeNode(node, false, path);
        return Nodes.isEmptyNode(normalized) ? null : normalized;
    }

    private Node normalizeListElement(Node node, String path) {
        if (node == null || Nodes.isEmptyNode(node)) {
            return Nodes.emptyPlaceholder();
        }
        if (node.getProperties() != null && node.getProperties().containsKey(LIST_CONTROL_EMPTY)) {
            Nodes.validateEmptyPlaceholder(node, path);
            return node.clone();
        }
        Node normalized = normalizeNode(node, true, path);
        return Nodes.isEmptyNode(normalized) ? Nodes.emptyPlaceholder() : normalized;
    }

    private Node normalizeNode(Node node, boolean listElement, String path) {
        Node normalized = node.clone();

        if (listElement && normalized.getProperties() != null && normalized.getProperties().containsKey(LIST_CONTROL_EMPTY)) {
            Nodes.validateEmptyPlaceholder(normalized, path);
            return normalized;
        }

        if (normalized.getType() != null) {
            normalized.type(normalizeNode(normalized.getType(), false, append(path, "type")));
        }
        if (normalized.getItemType() != null) {
            normalized.itemType(normalizeNode(normalized.getItemType(), false, append(path, "itemType")));
        }
        if (normalized.getKeyType() != null) {
            normalized.keyType(normalizeNode(normalized.getKeyType(), false, append(path, "keyType")));
        }
        if (normalized.getValueType() != null) {
            normalized.valueType(normalizeNode(normalized.getValueType(), false, append(path, "valueType")));
        }
        if (normalized.getBlue() != null) {
            normalized.blue(normalizeNode(normalized.getBlue(), false, append(path, "blue")));
        }
        if (normalized.getContracts() != null) {
            normalized.contracts(normalizeNode(normalized.getContracts(), false, append(path, "contracts")));
        }
        if (normalized.getSchema() != null) {
            normalizeSchema(normalized.getSchema(), append(path, "schema"));
        }

        if (normalized.getItems() != null) {
            List<Node> items = new ArrayList<>(normalized.getItems().size());
            for (int i = 0; i < normalized.getItems().size(); i++) {
                items.add(normalizeListElement(normalized.getItems().get(i), append(path, "items", i)));
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
            normalized.properties(properties.isEmpty() ? null : properties);
        }

        return normalized;
    }

    private void normalizeSchema(Schema schema, String path) {
        schema.required(normalizeObjectField(schema.getRequired(), append(path, "required")));
        schema.minLength(normalizeObjectField(schema.getMinLength(), append(path, "minLength")));
        schema.maxLength(normalizeObjectField(schema.getMaxLength(), append(path, "maxLength")));
        schema.minimum(normalizeObjectField(schema.getMinimum(), append(path, "minimum")));
        schema.maximum(normalizeObjectField(schema.getMaximum(), append(path, "maximum")));
        schema.exclusiveMinimum(normalizeObjectField(schema.getExclusiveMinimum(), append(path, "exclusiveMinimum")));
        schema.exclusiveMaximum(normalizeObjectField(schema.getExclusiveMaximum(), append(path, "exclusiveMaximum")));
        schema.multipleOf(normalizeObjectField(schema.getMultipleOf(), append(path, "multipleOf")));
        schema.minItems(normalizeObjectField(schema.getMinItems(), append(path, "minItems")));
        schema.maxItems(normalizeObjectField(schema.getMaxItems(), append(path, "maxItems")));
        schema.uniqueItems(normalizeObjectField(schema.getUniqueItems(), append(path, "uniqueItems")));
        schema.minFields(normalizeObjectField(schema.getMinFields(), append(path, "minFields")));
        schema.maxFields(normalizeObjectField(schema.getMaxFields(), append(path, "maxFields")));
        if (schema.getEnum() != null) {
            List<Node> enumValues = new ArrayList<>(schema.getEnum().size());
            for (int i = 0; i < schema.getEnum().size(); i++) {
                String enumPath = append(path, "enum", i);
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
        String prefix = path == null || path.isEmpty() ? "/" : path;
        if ("/".equals(prefix)) {
            return "/" + escape(segment);
        }
        return prefix + "/" + escape(segment);
    }

    private static String append(String path, String segment, int index) {
        return append(append(path, segment), String.valueOf(index));
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
