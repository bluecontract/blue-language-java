package blue.language.merge.processor;

import blue.language.merge.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.LeastCommonMultiple;
import blue.language.utils.NodeToBlueIdInput;
import blue.language.utils.UncheckedObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SchemaPropagator implements MergingProcessor {
    
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
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
        propagateEnum(sourceSchema, targetSchema);
    }


    private void propagateMinLength(Schema source, Schema target) {
        propagateMinValue(source.getMinLengthExact(), target::getMinLengthExact, target::minLength);
    }

    private void propagateMaxLength(Schema source, Schema target) {
        propagateMaxValue(source.getMaxLengthExact(), target::getMaxLengthExact, target::maxLength);
    }

    private void propagateMinimum(Schema source, Schema target) {
        propagateMinValue(source.getMinimumValue(), target::getMinimumValue, target::minimum);
    }

    private void propagateMaximum(Schema source, Schema target) {
        propagateMaxValue(source.getMaximumValue(), target::getMaximumValue, target::maximum);
    }

    private void propagateExclusiveMinimum(Schema source, Schema target) {
        propagateMinValue(source.getExclusiveMinimumValue(), target::getExclusiveMinimumValue, target::exclusiveMinimum);
    }

    private void propagateExclusiveMaximum(Schema source, Schema target) {
        propagateMaxValue(source.getExclusiveMaximumValue(), target::getExclusiveMaximumValue, target::exclusiveMaximum);
    }

    private void propagateRequired(Schema source, Schema target) {
        propagateBoolean(source.getRequiredValue(), target::getRequiredValue, target::required, true);
    }

    private <T extends Comparable<T>> void propagateMinValue(T sourceValue,
                                                             Supplier<T> targetValueGetter, Consumer<T> targetValueSetter) {
        if (sourceValue != null) {
            T targetValue = targetValueGetter.get();
            if (targetValue == null || sourceValue.compareTo(targetValue) > 0) {
                targetValueSetter.accept(sourceValue);
            }
        }
    }

    private <T extends Comparable<T>> void propagateMaxValue(T sourceValue,
                                                             Supplier<T> targetValueGetter, Consumer<T> targetValueSetter) {
        if (sourceValue != null) {
            T targetValue = targetValueGetter.get();
            if (targetValue == null || sourceValue.compareTo(targetValue) < 0) {
                targetValueSetter.accept(sourceValue);
            }
        }
    }

    private void propagateBoolean(Boolean sourceValue, Supplier<Boolean> targetValueGetter,
                                  Consumer<Boolean> targetValueSetter, boolean defaultValue) {
        if (sourceValue != null && sourceValue.equals(defaultValue)) {
            Boolean targetValue = targetValueGetter.get();
            if (targetValue == null || !targetValue.equals(defaultValue)) {
                targetValueSetter.accept(sourceValue);
            }
        }
    }

    private void propagateMultipleOf(Schema source, Schema target) {
        BigDecimal sourceMultipleOf = source.getMultipleOfValue();
        BigDecimal targetMultipleOf = target.getMultipleOfValue();
        if (sourceMultipleOf != null && targetMultipleOf != null) {
            target.multipleOf(LeastCommonMultiple.lcm(targetMultipleOf, sourceMultipleOf));
        } else if (sourceMultipleOf != null) {
            target.multipleOf(sourceMultipleOf);
        }
    }

    private void propagateMinItems(Schema source, Schema target) {
        propagateMinValue(source.getMinItemsExact(), target::getMinItemsExact, target::minItems);
    }

    private void propagateMaxItems(Schema source, Schema target) {
        propagateMaxValue(source.getMaxItemsExact(), target::getMaxItemsExact, target::maxItems);
    }

    private void propagateUniqueItems(Schema source, Schema target) {
        propagateBoolean(source.getUniqueItemsValue(), target::getUniqueItemsValue, target::uniqueItems, true);
    }

    private void propagateMinFields(Schema source, Schema target) {
        propagateMinValue(source.getMinFieldsExact(), target::getMinFieldsExact, target::minFields);
    }

    private void propagateMaxFields(Schema source, Schema target) {
        propagateMaxValue(source.getMaxFieldsExact(), target::getMaxFieldsExact, target::maxFields);
    }

    private void propagateEnum(Schema source, Schema target) {
        List<Node> sourceEnum = source.getEnum();
        if (sourceEnum == null) {
            return;
        }

        List<Node> targetEnum = target.getEnum();
        if (targetEnum == null) {
            target.enumValues(canonicalizeEnum(sourceEnum));
            return;
        }

        Map<String, Node> targetValuesByBlueId = targetEnum.stream()
                .collect(Collectors.toMap(this::enumComparableBlueId, Function.identity(), (left, right) -> left));
        List<Node> intersection = new ArrayList<>();
        for (Node sourceValue : sourceEnum) {
            Node targetValue = targetValuesByBlueId.get(enumComparableBlueId(sourceValue));
            if (targetValue != null) {
                intersection.add(targetValue.clone());
            }
        }
        target.enumValues(canonicalizeEnum(intersection));
    }

    private List<Node> cloneNodes(List<Node> nodes) {
        return nodes.stream()
                .map(Node::clone)
                .collect(Collectors.toList());
    }

    private String enumComparableBlueId(Node node) {
        Node comparable = node.clone();
        comparable.schema(null);
        return BlueIdCalculator.calculateBlueId(comparable);
    }

    private List<Node> canonicalizeEnum(List<Node> nodes) {
        Map<String, Node> uniqueByIdentity = new LinkedHashMap<>();
        for (Node node : nodes) {
            uniqueByIdentity.putIfAbsent(enumComparableBlueId(node), node.clone());
        }
        List<Node> result = new ArrayList<>(uniqueByIdentity.values());
        result.sort((left, right) -> enumCanonicalKey(left).compareTo(enumCanonicalKey(right)));
        return result;
    }

    private String enumCanonicalKey(Node node) {
        Node comparable = node.clone();
        comparable.schema(null);
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(NodeToBlueIdInput.get(comparable));
    }

}
