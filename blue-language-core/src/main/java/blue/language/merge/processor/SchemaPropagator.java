package blue.language.merge.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.merge.MergingProcessor;
import blue.language.provider.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.SchemaEnumCanonicalizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;

/**
 * Intersects inherited and authored schema constraints into the effective
 * schema of the merge target.
 *
 * <p>Minimum constraints become stricter maxima, maximum constraints become
 * stricter minima, enum values are intersected by canonical scalar identity,
 * and numeric {@code multipleOf} constraints are combined exactly.</p>
 */
public class SchemaPropagator implements MergingProcessor {

    /**
     * Creates a stateless schema propagation stage.
     */
    public SchemaPropagator() {
    }
    
    @Override
    public void process(
            Node target,
            Node source,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        Schema sourceSchema = source.getSchema();
        if (sourceSchema == null) {
            return;
        }

        Schema targetSchema = target.getSchema();
        if (targetSchema == null) {
            targetSchema = new Schema();
            target.schema(targetSchema);
        }

        propagateRequired(sourceSchema, targetSchema);
        propagateMinLength(sourceSchema, targetSchema);
        propagateMaxLength(sourceSchema, targetSchema);
        propagateMinimum(sourceSchema, targetSchema);
        propagateMaximum(sourceSchema, targetSchema);
        propagateExclusiveMinimum(sourceSchema, targetSchema);
        propagateExclusiveMaximum(sourceSchema, targetSchema);
        propagateMultipleOf(sourceSchema, targetSchema);
        propagateMinItems(sourceSchema, targetSchema);
        propagateMaxItems(sourceSchema, targetSchema);
        propagateUniqueItems(sourceSchema, targetSchema);
        propagateMinFields(sourceSchema, targetSchema);
        propagateMaxFields(sourceSchema, targetSchema);
        propagateEnum(
                sourceSchema,
                targetSchema,
                nodeResolver,
                typeIdentities);
    }


    private void propagateMinLength(Schema source, Schema target) {
        propagateMinValue(source.getMinLength(), source.getMinLengthExact(),
                target.getMinLengthExact(),
                node -> target.minLength(node));
    }

    private void propagateMaxLength(Schema source, Schema target) {
        propagateMaxValue(source.getMaxLength(), source.getMaxLengthExact(),
                target.getMaxLengthExact(),
                node -> target.maxLength(node));
    }

    private void propagateMinimum(Schema source, Schema target) {
        propagateMinValue(source.getMinimum(), source.getMinimumValue(),
                target.getMinimumValue(),
                node -> target.minimum(node));
    }

    private void propagateMaximum(Schema source, Schema target) {
        propagateMaxValue(source.getMaximum(), source.getMaximumValue(),
                target.getMaximumValue(),
                node -> target.maximum(node));
    }

    private void propagateExclusiveMinimum(Schema source, Schema target) {
        propagateMinValue(source.getExclusiveMinimum(),
                source.getExclusiveMinimumValue(),
                target.getExclusiveMinimumValue(),
                node -> target.exclusiveMinimum(node));
    }

    private void propagateExclusiveMaximum(Schema source, Schema target) {
        propagateMaxValue(source.getExclusiveMaximum(),
                source.getExclusiveMaximumValue(),
                target.getExclusiveMaximumValue(),
                node -> target.exclusiveMaximum(node));
    }

    private void propagateRequired(Schema source, Schema target) {
        propagateBoolean(source.getRequired(), source.getRequiredValue(),
                target.getRequiredValue(),
                node -> target.required(node), true);
    }

    private <T extends Comparable<T>> void propagateMinValue(
            Node sourceNode, T sourceValue,
            T targetValue,
            Consumer<Node> targetNodeSetter) {
        if (sourceValue != null) {
            if (targetValue == null || sourceValue.compareTo(targetValue) > 0) {
                targetNodeSetter.accept(sourceNode.clone());
            }
        }
    }

    private <T extends Comparable<T>> void propagateMaxValue(
            Node sourceNode, T sourceValue,
            T targetValue,
            Consumer<Node> targetNodeSetter) {
        if (sourceValue != null) {
            if (targetValue == null || sourceValue.compareTo(targetValue) < 0) {
                targetNodeSetter.accept(sourceNode.clone());
            }
        }
    }

    private void propagateBoolean(Node sourceNode, Boolean sourceValue,
                                  Boolean targetValue,
                                  Consumer<Node> targetNodeSetter,
                                  boolean defaultValue) {
        if (sourceValue != null && sourceValue.equals(defaultValue)) {
            if (targetValue == null || !targetValue.equals(defaultValue)) {
                targetNodeSetter.accept(sourceNode.clone());
            }
        }
    }

    private void propagateMultipleOf(Schema source, Schema target) {
        Node sourceNode = source.getMultipleOf();
        Node targetNode = target.getMultipleOf();
        BigDecimal sourceMultipleOf = source.getMultipleOfValue();
        BigDecimal targetMultipleOf = target.getMultipleOfValue();
        if (sourceMultipleOf != null && targetMultipleOf != null) {
            if (sourceNode.getValue() instanceof BigInteger
                    && targetNode.getValue() instanceof BigInteger) {
                BigInteger left = ((BigInteger) targetNode.getValue()).abs();
                BigInteger right = ((BigInteger) sourceNode.getValue()).abs();
                BigInteger lcm = left.signum() == 0 || right.signum() == 0
                        ? BigInteger.ZERO
                        : left.divide(left.gcd(right)).multiply(right);
                target.multipleOf(typedMergedNumber(
                        lcm, INTEGER_TYPE_BLUE_ID, targetNode, sourceNode));
            } else {
                target.multipleOf(typedMergedNumber(
                        LeastCommonMultiple.lcm(
                                targetMultipleOf, sourceMultipleOf),
                        DOUBLE_TYPE_BLUE_ID, targetNode, sourceNode));
            }
        } else if (sourceMultipleOf != null) {
            target.multipleOf(sourceNode.clone());
        }
    }

    private Node typedMergedNumber(Object value,
                                   String fallbackTypeBlueId,
                                   Node targetNode,
                                   Node sourceNode) {
        Node type = typeWithBlueId(targetNode, fallbackTypeBlueId);
        if (type == null) {
            type = typeWithBlueId(sourceNode, fallbackTypeBlueId);
        }
        if (type == null) {
            type = new Node().blueId(fallbackTypeBlueId);
        }
        return new Node().type(type).value(value);
    }

    private Node typeWithBlueId(Node node, String blueId) {
        Node type = node != null ? node.getType() : null;
        if (type == null) {
            return null;
        }
        if (type.isReferenceOnly()
                && blueId.equals(type.getBlueId())) {
            return type.clone();
        }
        return null;
    }

    private void propagateMinItems(Schema source, Schema target) {
        propagateMinValue(source.getMinItems(), source.getMinItemsExact(),
                target.getMinItemsExact(),
                node -> target.minItems(node));
    }

    private void propagateMaxItems(Schema source, Schema target) {
        propagateMaxValue(source.getMaxItems(), source.getMaxItemsExact(),
                target.getMaxItemsExact(),
                node -> target.maxItems(node));
    }

    private void propagateUniqueItems(Schema source, Schema target) {
        propagateBoolean(source.getUniqueItems(), source.getUniqueItemsValue(),
                target.getUniqueItemsValue(),
                node -> target.uniqueItems(node), true);
    }

    private void propagateMinFields(Schema source, Schema target) {
        propagateMinValue(source.getMinFields(), source.getMinFieldsExact(),
                target.getMinFieldsExact(),
                node -> target.minFields(node));
    }

    private void propagateMaxFields(Schema source, Schema target) {
        propagateMaxValue(source.getMaxFields(), source.getMaxFieldsExact(),
                target.getMaxFieldsExact(),
                node -> target.maxFields(node));
    }

    private void propagateEnum(
            Schema source,
            Schema target,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        List<Node> sourceEnum = source.getEnum();
        if (sourceEnum == null) {
            return;
        }

        List<Node> canonicalSource = canonicalizeEnum(
                sourceEnum, nodeResolver, typeIdentities);

        List<Node> targetEnum = target.getEnum();
        if (targetEnum == null) {
            target.enumValues(canonicalSource);
            return;
        }

        List<Node> canonicalTarget = canonicalizeEnum(
                targetEnum, nodeResolver, typeIdentities);
        Map<String, Node> targetValuesByBlueId = canonicalTarget.stream()
                .collect(Collectors.toMap(
                        SchemaEnumCanonicalizer::canonicalKey,
                        Function.identity(),
                        (left, right) -> left));
        List<Node> intersection = new ArrayList<>();
        for (Node sourceValue : canonicalSource) {
            Node targetValue = targetValuesByBlueId.get(
                    SchemaEnumCanonicalizer.canonicalKey(sourceValue));
            if (targetValue != null) {
                intersection.add(targetValue.clone());
            }
        }
        target.enumValues(SchemaEnumCanonicalizer.canonicalize(intersection));
    }

    private List<Node> canonicalizeEnum(
            List<Node> nodes,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (!requiresEffectiveTypeEvidence(nodes)) {
            return SchemaEnumCanonicalizer.canonicalize(nodes);
        }
        if (nodeResolver == null) {
            throw new IllegalStateException(
                    "Schema enum contains a completed inline type but no "
                            + "resolver-issued canonical type identity evidence");
        }
        return SchemaEnumCanonicalizer.canonicalizeResolved(
                nodes,
                java.util.Objects.requireNonNull(
                        typeIdentities,
                        "typeIdentities"));
    }

    private boolean requiresEffectiveTypeEvidence(List<Node> nodes) {
        for (Node node : nodes) {
            if (node != null
                    && !node.isReferenceOnly()
                    && node.getType() != null
                    && !node.getType().isReferenceOnly()) {
                return true;
            }
        }
        return false;
    }

}
