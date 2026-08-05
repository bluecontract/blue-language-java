package blue.language.preprocess;

import blue.language.model.wire.SchemaPropertyConstants;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.InferBasicTypesForUntypedValues;
import blue.language.preprocess.NormalizeListPlaceholders;
import blue.language.preprocess.ReplaceInlineValuesForTypeAttributesWithImports;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.Nodes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static blue.language.model.wire.SchemaPropertyConstants.KEY_ENUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAX_FIELDS;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAX_ITEMS;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAX_LENGTH;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAXIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MIN_FIELDS;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MIN_ITEMS;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MIN_LENGTH;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MINIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MULTIPLE_OF;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_REQUIRED;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_UNIQUE_ITEMS;

/**
 * Mandatory Blue Language 1.0 preprocessing baseline.
 *
 * <p>The stage order is wrapper normalization, list-placeholder
 * normalization, type-alias substitution, primitive inference, and strict
 * Preprocessed Document validation. Parsed {@link Node} values already embody
 * wrapper normalization, so this pipeline begins with a defensive clone.</p>
 */
public final class StandardPreprocessingPipeline {

    /** Creates the stateless mandatory preprocessing pipeline. */
    public StandardPreprocessingPipeline() {
    }

    /**
     * Applies the mandatory baseline to transformed Source content.
     *
     * @param source transformed Source Document without a directive
     * @param effectiveImports complete exact alias map
     * @return validated Preprocessed Document
     */
    public Node apply(
            Node source,
            Map<String, String> effectiveImports) {
        Node wrapped = source.clone();
        Node placeholders = new NormalizeListPlaceholders().process(wrapped);
        Node aliases = new ReplaceInlineValuesForTypeAttributesWithImports(
                effectiveImports).process(placeholders);
        Node inferred = new InferBasicTypesForUntypedValues().process(aliases);
        validate(inferred);
        return inferred;
    }

    /**
     * Rejects Source-only directives and unresolved inline aliases from a
     * completed preprocessing result.
     *
     * @param node candidate Preprocessed Document
     */
    public void validate(Node node) {
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        validateNode(node, "", visited);
    }

    /**
     * Rejects a transformation result containing {@code blue} at any path
     * without requiring baseline alias substitution to have happened yet.
     *
     * @param node transformed Source Document
     */
    public void rejectBlueDirective(Node node) {
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        rejectBlue(node, "", visited);
    }

    private void validateNode(
            Node node,
            String path,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue\" directive is valid only while preprocessing the Source root. Path: "
                            + path);
        }
        validatePayloadShape(node, path);
        validateTypePosition(node.getType(), child(path, BlueLanguageConstants.OBJECT_TYPE));
        validateTypePosition(node.getItemType(), child(path, BlueLanguageConstants.OBJECT_ITEM_TYPE));
        validateTypePosition(node.getKeyType(), child(path, BlueLanguageConstants.OBJECT_KEY_TYPE));
        validateTypePosition(node.getValueType(), child(path, BlueLanguageConstants.OBJECT_VALUE_TYPE));
        validateNode(node.getType(), child(path, BlueLanguageConstants.OBJECT_TYPE), visited);
        validateNode(node.getItemType(), child(path, BlueLanguageConstants.OBJECT_ITEM_TYPE), visited);
        validateNode(node.getKeyType(), child(path, BlueLanguageConstants.OBJECT_KEY_TYPE), visited);
        validateNode(node.getValueType(), child(path, BlueLanguageConstants.OBJECT_VALUE_TYPE), visited);
        validateNode(node.getContracts(), child(path, BlueLanguageConstants.OBJECT_CONTRACTS), visited);
        validateSchema(node.getSchema(), child(path, BlueLanguageConstants.OBJECT_SCHEMA), visited);
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
                if (BlueLanguageConstants.OBJECT_BLUE.equals(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Reserved \"blue\" is valid only on the root Source Document. Path: "
                                    + child(path, entry.getKey()));
                }
                validateNode(entry.getValue(), child(path, entry.getKey()), visited);
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                validateNode(node.getItems().get(index),
                        child(path, String.valueOf(index)), visited);
            }
        }
    }

    private void validateTypePosition(Node type, String path) {
        if (type != null && type.isInlineValue()
                && type.getValue() instanceof String) {
            throw new IllegalArgumentException(
                    "Unresolved type alias at " + path + ": "
                            + type.getValue());
        }
    }

    private void validatePayloadShape(Node node, String path) {
        int payloadKinds = 0;
        if (node.getRawValue() != null) {
            payloadKinds++;
        }
        if (node.getItems() != null) {
            payloadKinds++;
        }
        if (node.getProperties() != null
                && !node.getProperties().isEmpty()) {
            payloadKinds++;
        }
        if (payloadKinds > 1) {
            throw new IllegalArgumentException(
                    "A Preprocessed Document node may contain only one payload kind: value, items, or object fields. Path: "
                            + path);
        }
        if (node.getBlueId() != null && !node.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "A Preprocessed Document blueId node must be a pure reference. Path: "
                            + path);
        }
        if (node.getProperties() != null
                && node.getProperties().containsKey(
                BlueLanguageConstants.LIST_CONTROL_EMPTY)) {
            Nodes.validateEmptyPlaceholder(node, path);
        }
    }

    private void validateSchema(
            Schema schema,
            String path,
            Set<Node> visited) {
        if (schema == null) {
            return;
        }
        validateNode(schema.getRequired(), child(path, KEY_REQUIRED), visited);
        validateNode(schema.getMinLength(), child(path, KEY_MIN_LENGTH), visited);
        validateNode(schema.getMaxLength(), child(path, KEY_MAX_LENGTH), visited);
        validateNode(schema.getMinimum(), child(path, KEY_MINIMUM), visited);
        validateNode(schema.getMaximum(), child(path, KEY_MAXIMUM), visited);
        validateNode(schema.getExclusiveMinimum(),
                child(path, KEY_EXCLUSIVE_MINIMUM), visited);
        validateNode(schema.getExclusiveMaximum(),
                child(path, KEY_EXCLUSIVE_MAXIMUM), visited);
        validateNode(schema.getMultipleOf(), child(path, KEY_MULTIPLE_OF), visited);
        validateNode(schema.getMinItems(), child(path, KEY_MIN_ITEMS), visited);
        validateNode(schema.getMaxItems(), child(path, KEY_MAX_ITEMS), visited);
        validateNode(schema.getUniqueItems(), child(path, KEY_UNIQUE_ITEMS), visited);
        validateNode(schema.getMinFields(), child(path, KEY_MIN_FIELDS), visited);
        validateNode(schema.getMaxFields(), child(path, KEY_MAX_FIELDS), visited);
        if (schema.getEnum() != null) {
            for (int index = 0; index < schema.getEnum().size(); index++) {
                validateNode(schema.getEnum().get(index),
                        child(child(path, KEY_ENUM), String.valueOf(index)),
                        visited);
            }
        }
    }

    private void rejectBlue(
            Node node,
            String path,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue\" directive was introduced by preprocessing at "
                            + path);
        }
        rejectBlue(node.getType(), child(path, BlueLanguageConstants.OBJECT_TYPE), visited);
        rejectBlue(node.getItemType(), child(path, BlueLanguageConstants.OBJECT_ITEM_TYPE), visited);
        rejectBlue(node.getKeyType(), child(path, BlueLanguageConstants.OBJECT_KEY_TYPE), visited);
        rejectBlue(node.getValueType(), child(path, BlueLanguageConstants.OBJECT_VALUE_TYPE), visited);
        rejectBlue(node.getContracts(), child(path, BlueLanguageConstants.OBJECT_CONTRACTS), visited);
        rejectBlueInSchema(node.getSchema(),
                child(path, BlueLanguageConstants.OBJECT_SCHEMA), visited);
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
                if (BlueLanguageConstants.OBJECT_BLUE.equals(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Reserved \"blue\" directive was introduced by preprocessing at "
                                    + child(path, entry.getKey()));
                }
                rejectBlue(entry.getValue(), child(path, entry.getKey()), visited);
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                rejectBlue(node.getItems().get(index),
                        child(path, String.valueOf(index)), visited);
            }
        }
    }

    private void rejectBlueInSchema(
            Schema schema,
            String path,
            Set<Node> visited) {
        if (schema == null) {
            return;
        }
        rejectBlue(schema.getRequired(), child(path, KEY_REQUIRED), visited);
        rejectBlue(schema.getMinLength(), child(path, KEY_MIN_LENGTH), visited);
        rejectBlue(schema.getMaxLength(), child(path, KEY_MAX_LENGTH), visited);
        rejectBlue(schema.getMinimum(), child(path, KEY_MINIMUM), visited);
        rejectBlue(schema.getMaximum(), child(path, KEY_MAXIMUM), visited);
        rejectBlue(schema.getExclusiveMinimum(),
                child(path, KEY_EXCLUSIVE_MINIMUM), visited);
        rejectBlue(schema.getExclusiveMaximum(),
                child(path, KEY_EXCLUSIVE_MAXIMUM), visited);
        rejectBlue(schema.getMultipleOf(), child(path, KEY_MULTIPLE_OF), visited);
        rejectBlue(schema.getMinItems(), child(path, KEY_MIN_ITEMS), visited);
        rejectBlue(schema.getMaxItems(), child(path, KEY_MAX_ITEMS), visited);
        rejectBlue(schema.getUniqueItems(), child(path, KEY_UNIQUE_ITEMS), visited);
        rejectBlue(schema.getMinFields(), child(path, KEY_MIN_FIELDS), visited);
        rejectBlue(schema.getMaxFields(), child(path, KEY_MAX_FIELDS), visited);
        if (schema.getEnum() != null) {
            for (int index = 0; index < schema.getEnum().size(); index++) {
                rejectBlue(schema.getEnum().get(index),
                        child(child(path, KEY_ENUM), String.valueOf(index)),
                        visited);
            }
        }
    }

    private String child(String path, String segment) {
        return path + "/" + segment.replace("~", "~0")
                .replace("/", "~1");
    }
}
