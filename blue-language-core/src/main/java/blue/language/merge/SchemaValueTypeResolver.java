package blue.language.merge;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.resolve.ResolutionLimits;

import java.util.ArrayList;
import java.util.List;

/** Completes typed values embedded in the closed Schema vocabulary. */
final class SchemaValueTypeResolver {

    private final ResolutionEngine engine;

    SchemaValueTypeResolver(ResolutionEngine engine) {
        this.engine = engine;
    }

    void resolve(Schema schema, ResolutionLimits limits) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        schema.required(resolveValue(schema.getRequired(), limits));
        schema.minLength(resolveValue(schema.getMinLength(), limits));
        schema.maxLength(resolveValue(schema.getMaxLength(), limits));
        schema.minimum(resolveValue(schema.getMinimum(), limits));
        schema.maximum(resolveValue(schema.getMaximum(), limits));
        schema.exclusiveMinimum(resolveValue(
                schema.getExclusiveMinimum(), limits));
        schema.exclusiveMaximum(resolveValue(
                schema.getExclusiveMaximum(), limits));
        schema.multipleOf(resolveValue(schema.getMultipleOf(), limits));
        schema.minItems(resolveValue(schema.getMinItems(), limits));
        schema.maxItems(resolveValue(schema.getMaxItems(), limits));
        schema.uniqueItems(resolveValue(schema.getUniqueItems(), limits));
        schema.minFields(resolveValue(schema.getMinFields(), limits));
        schema.maxFields(resolveValue(schema.getMaxFields(), limits));
        if (schema.getEnum() != null) {
            List<Node> resolvedEnum = new ArrayList<>(schema.getEnum().size());
            for (Node value : schema.getEnum()) {
                if (!Nodes.isSchemaEnumValue(value)) {
                    throw new IllegalArgumentException(
                            "Schema enum entries must be scalar values, explicit type/value nodes, or pure references.");
                }
                Node completed = resolveValue(value, limits);
                // Type contributions may add schema and labels. They are
                // validated during completion, but are not enum value syntax.
                resolvedEnum.add(completed.isReferenceOnly() ? completed
                        : new Node().type(completed.getType())
                                .value(completed.getRawValue()));
            }
            schema.enumValues(resolvedEnum);
        }
    }

    private Node resolveValue(Node value, ResolutionLimits limits) {
        if (value == null || value.isReferenceOnly()
                || value.getType() == null
                && value.getItemType() == null
                && value.getKeyType() == null
                && value.getValueType() == null) {
            return value;
        }
        return engine.resolveWithContribution(
                value, limits, ResolutionEngine.Contribution.TYPE_METADATA);
    }
}
