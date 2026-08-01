package blue.language.preprocess;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.Properties;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static blue.language.utils.SchemaPropertyConstants.KEY_ENUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_FIELDS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_ITEMS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_LENGTH;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAXIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_FIELDS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_ITEMS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_LENGTH;
import static blue.language.utils.SchemaPropertyConstants.KEY_MINIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MULTIPLE_OF;
import static blue.language.utils.SchemaPropertyConstants.KEY_REQUIRED;
import static blue.language.utils.SchemaPropertyConstants.KEY_UNIQUE_ITEMS;

/** Validates the reserved preprocessing directive independently of fetching. */
public final class DirectiveValidator {

    /** Validates graph bounds and proves that {@code blue} occurs only at root. */
    public void validateSource(Node source) {
        PreprocessingLimits.requireGraphWithinBounds(
                source, "Source Document");
        rejectNestedBlue(source);
    }

    /** Validates the portable shape of the resolved root directive. */
    public void validateDirective(Node directive) {
        rejectAnyBlue(directive, Properties.OBJECT_BLUE);
        if (directive.getBlueId() != null
                || directive.getValue() != null
                || directive.getItems() != null
                || directive.getItemType() != null
                || directive.getKeyType() != null
                || directive.getValueType() != null
                || directive.getSchema() != null
                || directive.getContracts() != null
                || directive.getMergePolicy() != null
                || directive.getPreviousBlueId() != null
                || directive.getPosition() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue\" directive has an invalid portable shape.");
        }
        if (directive.getType() != null
                && !directive.getType().isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Reserved \"blue.type\" metadata must be an exact pure reference.");
        }
        if (directive.getProperties() == null) {
            return;
        }
        for (String key : directive.getProperties().keySet()) {
            if (!Properties.BLUE_DIRECTIVE_IMPORTS.equals(key)
                    && !Properties.BLUE_DIRECTIVE_TRANSFORMATIONS
                    .equals(key)) {
                throw new IllegalArgumentException(
                        "Reserved \"blue\" directive field is unsupported: "
                                + key);
            }
        }
    }

    /** Validates that imports are an object containing only alias entries. */
    public void validateImportsObject(Node imports) {
        if (imports.getBlueId() != null
                || imports.getValue() != null
                || imports.getItems() != null
                || imports.getName() != null
                || imports.getDescription() != null
                || imports.getType() != null
                || imports.getItemType() != null
                || imports.getKeyType() != null
                || imports.getValueType() != null
                || imports.getSchema() != null
                || imports.getContracts() != null
                || imports.getMergePolicy() != null
                || imports.getPreviousBlueId() != null
                || imports.getPosition() != null
                || imports.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue.imports\" must be an object mapping aliases to pure references.");
        }
    }

    /** Validates the resolved transformations container before item preflight. */
    public void validateTransformationList(Node transformations) {
        if (transformations.getBlueId() != null
                || transformations.getValue() != null
                || transformations.getProperties() != null
                || transformations.getName() != null
                || transformations.getDescription() != null
                || transformations.getType() != null
                || transformations.getItemType() != null
                || transformations.getKeyType() != null
                || transformations.getValueType() != null
                || transformations.getSchema() != null
                || transformations.getContracts() != null
                || transformations.getMergePolicy() != null
                || transformations.getPreviousBlueId() != null
                || transformations.getPosition() != null
                || transformations.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue.transformations\" must be a list.");
        }
    }

    /** Rejects a reserved directive anywhere inside a resolved resource. */
    public void rejectAnyBlue(Node node, String path) {
        if (node == null) {
            return;
        }
        if (node.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue\" directive is not allowed inside "
                            + path + ".");
        }
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        rejectChildBlue(node, path, visited);
    }

    private void rejectNestedBlue(Node source) {
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        visited.add(source);
        rejectNodeChildren(source, "", visited);
    }

    private void rejectNodeChildren(
            Node node, String path, Set<Node> visited) {
        rejectChildBlue(node.getType(), path + "/type", visited);
        rejectChildBlue(node.getItemType(), path + "/itemType", visited);
        rejectChildBlue(node.getKeyType(), path + "/keyType", visited);
        rejectChildBlue(node.getValueType(), path + "/valueType", visited);
        rejectChildBlue(node.getContracts(), path + "/contracts", visited);
        rejectSchemaBlue(node.getSchema(), path + "/schema", visited);
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                if (Properties.OBJECT_BLUE.equals(entry.getKey())) {
                    throw nestedBlue(path + "/blue");
                }
                rejectChildBlue(entry.getValue(),
                        path + "/" + entry.getKey(), visited);
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                rejectChildBlue(node.getItems().get(index),
                        path + "/" + index, visited);
            }
        }
    }

    private void rejectChildBlue(
            Node node, String path, Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node.getBlue() != null) {
            throw nestedBlue(path + "/blue");
        }
        rejectNodeChildren(node, path, visited);
    }

    private void rejectSchemaBlue(
            Schema schema, String path, Set<Node> visited) {
        if (schema == null) {
            return;
        }
        rejectChildBlue(schema.getRequired(), path + "/" + KEY_REQUIRED, visited);
        rejectChildBlue(schema.getMinLength(), path + "/" + KEY_MIN_LENGTH, visited);
        rejectChildBlue(schema.getMaxLength(), path + "/" + KEY_MAX_LENGTH, visited);
        rejectChildBlue(schema.getMinimum(), path + "/" + KEY_MINIMUM, visited);
        rejectChildBlue(schema.getMaximum(), path + "/" + KEY_MAXIMUM, visited);
        rejectChildBlue(schema.getExclusiveMinimum(),
                path + "/" + KEY_EXCLUSIVE_MINIMUM, visited);
        rejectChildBlue(schema.getExclusiveMaximum(),
                path + "/" + KEY_EXCLUSIVE_MAXIMUM, visited);
        rejectChildBlue(schema.getMultipleOf(), path + "/" + KEY_MULTIPLE_OF, visited);
        rejectChildBlue(schema.getMinItems(), path + "/" + KEY_MIN_ITEMS, visited);
        rejectChildBlue(schema.getMaxItems(), path + "/" + KEY_MAX_ITEMS, visited);
        rejectChildBlue(schema.getUniqueItems(), path + "/" + KEY_UNIQUE_ITEMS, visited);
        rejectChildBlue(schema.getMinFields(), path + "/" + KEY_MIN_FIELDS, visited);
        rejectChildBlue(schema.getMaxFields(), path + "/" + KEY_MAX_FIELDS, visited);
        if (schema.getEnum() != null) {
            for (int index = 0; index < schema.getEnum().size(); index++) {
                rejectChildBlue(schema.getEnum().get(index),
                        path + "/" + KEY_ENUM + "/" + index, visited);
            }
        }
    }

    private IllegalArgumentException nestedBlue(String path) {
        return new IllegalArgumentException(
                "Reserved \"blue\" is valid only on the root Source Document. Path: "
                        + path);
    }
}
