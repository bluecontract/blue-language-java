package blue.language.matching;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Computes a local compatibility fingerprint for completed type semantics. */
final class LabelNeutralTypeIdentity {

    private static final String COMPATIBILITY_PREFIX = "semantic-type:";

    private LabelNeutralTypeIdentity() {
    }

    /**
     * Calculates an opaque, matcher-local compatibility fingerprint.
     *
     * <p>This value is deliberately prefixed and must never be published as a
     * canonical type BlueId. Pure references are materialized through the
     * supplied verified boundary before labels are removed, so an inline type
     * and the equivalent verified-reference form converge on the same
     * completed semantics.</p>
     */
    static String calculateCompatibilityFingerprint(
            FrozenNode typeDefinition,
            Function<FrozenNode, FrozenNode> verifiedMaterializer) {
        Objects.requireNonNull(typeDefinition, "typeDefinition");
        Objects.requireNonNull(verifiedMaterializer, "verifiedMaterializer");
        Node normalized = normalize(
                typeDefinition,
                verifiedMaterializer,
                new HashSet<String>());
        return COMPATIBILITY_PREFIX
                + DirectBlueIdCalculator.calculateBlueId(normalized);
    }

    private static Node normalize(
            FrozenNode node,
            Function<FrozenNode, FrozenNode> verifiedMaterializer,
            Set<String> expandingReferences) {
        if (node == null) {
            return null;
        }
        if (node.isReferenceOnly()) {
            String blueId = node.getReferenceBlueId();
            if (!expandingReferences.add(blueId)) {
                return new Node().blueId(blueId);
            }
            try {
                FrozenNode materialized = verifiedMaterializer.apply(node);
                if (materialized == null) {
                    throw new IllegalStateException(
                            "Verified type materialization is unavailable for "
                                    + blueId);
                }
                if (materialized.isReferenceOnly()) {
                    if (blueId.equals(materialized.getReferenceBlueId())) {
                        return new Node().blueId(blueId);
                    }
                    throw new IllegalStateException(
                            "Verified type materialization retained a different "
                                    + "pure reference for " + blueId);
                }
                return normalize(
                        materialized,
                        verifiedMaterializer,
                        expandingReferences);
            } finally {
                expandingReferences.remove(blueId);
            }
        }

        Node normalized = node.toNode();
        normalized.name(null);
        normalized.description(null);
        if (normalized.getBlueId() != null) {
            normalized.blueId(null);
        }
        normalized.type(normalize(
                node.getType(), verifiedMaterializer, expandingReferences));
        normalized.itemType(normalize(
                node.getItemType(), verifiedMaterializer, expandingReferences));
        normalized.keyType(normalize(
                node.getKeyType(), verifiedMaterializer, expandingReferences));
        normalized.valueType(normalize(
                node.getValueType(), verifiedMaterializer, expandingReferences));
        normalized.blue(normalize(
                node.getBlue(), verifiedMaterializer, expandingReferences));
        normalized.contracts(normalize(
                node.getContracts(), verifiedMaterializer, expandingReferences));
        normalized.items(normalizeItems(
                node.getItems(), verifiedMaterializer, expandingReferences));
        normalized.properties(normalizeProperties(
                node.getProperties(), verifiedMaterializer, expandingReferences));
        normalized.schema(normalizeSchema(
                node.getSchema(), verifiedMaterializer, expandingReferences));
        return normalized;
    }

    private static List<Node> normalizeItems(
            List<FrozenNode> items,
            Function<FrozenNode, FrozenNode> verifiedMaterializer,
            Set<String> expandingReferences) {
        if (items == null) {
            return null;
        }
        List<Node> normalized = new ArrayList<>(items.size());
        for (FrozenNode item : items) {
            normalized.add(normalize(
                    item, verifiedMaterializer, expandingReferences));
        }
        return normalized;
    }

    private static Map<String, Node> normalizeProperties(
            Map<String, FrozenNode> properties,
            Function<FrozenNode, FrozenNode> verifiedMaterializer,
            Set<String> expandingReferences) {
        if (properties == null) {
            return null;
        }
        Map<String, Node> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, FrozenNode> entry : properties.entrySet()) {
            normalized.put(entry.getKey(), normalize(
                    entry.getValue(),
                    verifiedMaterializer,
                    expandingReferences));
        }
        return normalized;
    }

    private static Schema normalizeSchema(
            Schema schema,
            Function<FrozenNode, FrozenNode> verifiedMaterializer,
            Set<String> expandingReferences) {
        if (schema == null) {
            return null;
        }
        Schema normalized = schema.clone();
        normalized.required(normalizeSchemaNode(
                schema.getRequired(), verifiedMaterializer, expandingReferences));
        normalized.minLength(normalizeSchemaNode(
                schema.getMinLength(), verifiedMaterializer, expandingReferences));
        normalized.maxLength(normalizeSchemaNode(
                schema.getMaxLength(), verifiedMaterializer, expandingReferences));
        normalized.minimum(normalizeSchemaNode(
                schema.getMinimum(), verifiedMaterializer, expandingReferences));
        normalized.maximum(normalizeSchemaNode(
                schema.getMaximum(), verifiedMaterializer, expandingReferences));
        normalized.exclusiveMinimum(normalizeSchemaNode(
                schema.getExclusiveMinimum(), verifiedMaterializer, expandingReferences));
        normalized.exclusiveMaximum(normalizeSchemaNode(
                schema.getExclusiveMaximum(), verifiedMaterializer, expandingReferences));
        normalized.multipleOf(normalizeSchemaNode(
                schema.getMultipleOf(), verifiedMaterializer, expandingReferences));
        normalized.minItems(normalizeSchemaNode(
                schema.getMinItems(), verifiedMaterializer, expandingReferences));
        normalized.maxItems(normalizeSchemaNode(
                schema.getMaxItems(), verifiedMaterializer, expandingReferences));
        normalized.uniqueItems(normalizeSchemaNode(
                schema.getUniqueItems(), verifiedMaterializer, expandingReferences));
        normalized.minFields(normalizeSchemaNode(
                schema.getMinFields(), verifiedMaterializer, expandingReferences));
        normalized.maxFields(normalizeSchemaNode(
                schema.getMaxFields(), verifiedMaterializer, expandingReferences));
        if (schema.getEnum() != null) {
            List<Node> values = new ArrayList<>(schema.getEnum().size());
            for (Node value : schema.getEnum()) {
                values.add(normalize(
                        FrozenNode.fromResolvedNode(value),
                        verifiedMaterializer,
                        expandingReferences));
            }
            normalized.enumValues(values);
        }
        return normalized;
    }

    private static Node normalizeSchemaNode(
            Node value,
            Function<FrozenNode, FrozenNode> verifiedMaterializer,
            Set<String> expandingReferences) {
        return value == null
                ? null
                : normalize(
                        FrozenNode.fromResolvedNode(value),
                        verifiedMaterializer,
                        expandingReferences);
    }
}
