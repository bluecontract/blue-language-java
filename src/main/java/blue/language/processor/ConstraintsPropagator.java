package blue.language.processor;

import blue.language.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.NodeResolver;
import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.LCM;
import blue.language.utils.NodeToObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ConstraintsPropagator implements MergingProcessor {
    
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        Constraints sourceConstraints = source.getConstraints();
        if (sourceConstraints == null) {
            return;
        }

        Constraints targetConstraints = target.getConstraints();
        if (targetConstraints == null) {
            targetConstraints = new Constraints();
            target.constraints(targetConstraints);
        }

        propagateRequired(sourceConstraints, targetConstraints);
        propagateAllowMultiple(sourceConstraints, targetConstraints);
        propagateMinLength(sourceConstraints, targetConstraints);
        propagateMaxLength(sourceConstraints, targetConstraints);
        propagatePattern(sourceConstraints, targetConstraints);
        propagateMinimum(sourceConstraints, targetConstraints);
        propagateMaximum(sourceConstraints, targetConstraints);
        propagateExclusiveMinimum(sourceConstraints, targetConstraints);
        propagateExclusiveMaximum(sourceConstraints, targetConstraints);
        propagateMultipleOf(sourceConstraints, targetConstraints);
        propagateMinItems(sourceConstraints, targetConstraints);
        propagateMaxItems(sourceConstraints, targetConstraints);
        propagateUniqueItems(sourceConstraints, targetConstraints);
        propagateOptions(sourceConstraints, targetConstraints);
    }


    private void propagateMinLength(Constraints source, Constraints target) {
        propagateMinValue(source.getMinLengthValue(), target::getMinLengthValue, target::minLength);
    }

    private void propagateMaxLength(Constraints source, Constraints target) {
        propagateMaxValue(source.getMaxLengthValue(), target::getMaxLengthValue, target::maxLength);
    }

    private void propagatePattern(Constraints source, Constraints target) {
        List<String> sourcePattern = source.getPatternValue();
        if (sourcePattern != null) {
            target.pattern(sourcePattern);
        }
    }

    private void propagateMinimum(Constraints source, Constraints target) {
        propagateMinValue(source.getMinimumValue(), target::getMinimumValue, target::minimum);
    }

    private void propagateMaximum(Constraints source, Constraints target) {
        propagateMaxValue(source.getMaximumValue(), target::getMaximumValue, target::maximum);
    }

    private void propagateExclusiveMinimum(Constraints source, Constraints target) {
        propagateMinValue(source.getExclusiveMinimumValue(), target::getExclusiveMinimumValue, target::exclusiveMinimum);
    }

    private void propagateExclusiveMaximum(Constraints source, Constraints target) {
        propagateMaxValue(source.getExclusiveMaximumValue(), target::getExclusiveMaximumValue, target::exclusiveMaximum);
    }

    private void propagateRequired(Constraints source, Constraints target) {
        propagateBoolean(source.getRequiredValue(), target::getRequiredValue, target::required, true);
    }

    private void propagateAllowMultiple(Constraints source, Constraints target) {
        propagateBoolean(source.getAllowMultipleValue(), target::getAllowMultipleValue, target::allowMultiple, true);
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

    private void propagateMultipleOf(Constraints source, Constraints target) {
        BigDecimal sourceMultipleOf = source.getMultipleOfValue();
        BigDecimal targetMultipleOf = target.getMultipleOfValue();
        if (sourceMultipleOf != null && targetMultipleOf != null) {
            target.multipleOf(LCM.lcm(targetMultipleOf, sourceMultipleOf));
        } else if (sourceMultipleOf != null) {
            target.multipleOf(sourceMultipleOf);
        }
    }

    private void propagateMinItems(Constraints source, Constraints target) {
        propagateMinValue(source.getMinItemsValue(), target::getMinItemsValue, target::minItems);
    }

    private void propagateMaxItems(Constraints source, Constraints target) {
        propagateMaxValue(source.getMaxItemsValue(), target::getMaxItemsValue, target::maxItems);
    }

    private void propagateUniqueItems(Constraints source, Constraints target) {
        propagateBoolean(source.getUniqueItemsValue(), target::getUniqueItemsValue, target::uniqueItems, true);
    }

    private void propagateOptions(Constraints source, Constraints target) {
    }

}