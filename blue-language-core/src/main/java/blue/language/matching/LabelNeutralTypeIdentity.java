package blue.language.matching;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;

/** Computes type compatibility identity after removing descriptive labels. */
final class LabelNeutralTypeIdentity {

    private LabelNeutralTypeIdentity() {
    }

    /**
     * Calculates the semantic identity used when differently labelled type
     * declarations are compared for compatibility.
     */
    public static String calculate(FrozenNode typeDefinition) {
        Node clone = typeDefinition.toNode();
        stripLabels(clone);
        return DirectBlueIdCalculator.calculateBlueId(clone);
    }

    private static void stripLabels(Node node) {
        if (node == null) {
            return;
        }
        node.name(null);
        node.description(null);
        if (node.getBlueId() != null && !node.isReferenceOnly()) {
            node.blueId(null);
        }
        stripLabels(node.getType());
        stripLabels(node.getItemType());
        stripLabels(node.getKeyType());
        stripLabels(node.getValueType());
        stripLabels(node.getBlue());
        stripLabels(node.getContracts());
        if (node.getItems() != null) {
            node.getItems().forEach(LabelNeutralTypeIdentity::stripLabels);
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(
                    LabelNeutralTypeIdentity::stripLabels);
        }
        stripSchemaLabels(node.getSchema());
    }

    private static void stripSchemaLabels(Schema schema) {
        if (schema == null) {
            return;
        }
        stripLabels(schema.getRequired());
        stripLabels(schema.getMinLength());
        stripLabels(schema.getMaxLength());
        stripLabels(schema.getMinimum());
        stripLabels(schema.getMaximum());
        stripLabels(schema.getExclusiveMinimum());
        stripLabels(schema.getExclusiveMaximum());
        stripLabels(schema.getMultipleOf());
        stripLabels(schema.getMinItems());
        stripLabels(schema.getMaxItems());
        stripLabels(schema.getUniqueItems());
        stripLabels(schema.getMinFields());
        stripLabels(schema.getMaxFields());
        if (schema.getEnum() != null) {
            schema.getEnum().forEach(LabelNeutralTypeIdentity::stripLabels);
        }
    }
}
