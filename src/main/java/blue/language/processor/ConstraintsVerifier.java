package blue.language.processor;

import blue.language.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.NodeResolver;
import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToObject;
import blue.language.utils.Nodes;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static java.lang.Boolean.TRUE;

public class ConstraintsVerifier implements MergingProcessor {

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        // do nothing
    }

    @Override
    public void postProcess(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        Constraints constraints = target.getConstraints();
        if (constraints == null)
            return;

        verifyRequired(constraints.getRequiredValue(), target.getValue());
        verifyAllowMultiple(constraints.getAllowMultipleValue(), target.getItems());
        verifyMinLength(constraints.getMinLengthValue(), target.getValue());
        verifyMaxLength(constraints.getMaxLengthValue(), target.getValue());
        verifyPattern(constraints.getPatternValue(), target.getValue());
        verifyMinimum(constraints.getMinimumValue(), target.getValue());
        verifyMaximum(constraints.getMaximumValue(), target.getValue());
        verifyExclusiveMinimum(constraints.getExclusiveMinimumValue(), target.getValue());
        verifyExclusiveMaximum(constraints.getExclusiveMaximumValue(), target.getValue());
        verifyMultipleOf(constraints.getMultipleOfValue(), target.getValue());
        verifyMinItems(constraints.getMinItemsValue(), target.getItems());
        verifyMaxItems(constraints.getMaxItemsValue(), target.getItems());
        verifyUniqueItems(constraints.getUniqueItemsValue(), target.getItems());
        verifyOptions(constraints.getOptions(), target, nodeProvider);
    }

    private void verifyRequired(Boolean required, Object value) {
        if (TRUE.equals(required) && value == null)
            throw new IllegalArgumentException("Value is required but is null.");
    }

    private void verifyAllowMultiple(Boolean allowMultiple, List<Node> items) {
        if ((allowMultiple == null || Boolean.FALSE.equals(allowMultiple)) && items != null && items.size() > 1)
            throw new IllegalArgumentException("Multiple items are not allowed.");
    }

    private void verifyMinLength(Integer minLength, Object value) {
        if (minLength != null && value instanceof String && ((String) value).length() < minLength)
            throw new IllegalArgumentException("Value is shorter than the minimum length of " + minLength + ".");
    }

    private void verifyMaxLength(Integer maxLength, Object value) {
        if (maxLength != null && value instanceof String && ((String) value).length() > maxLength) {
            throw new IllegalArgumentException("Value is longer than the maximum length of " + maxLength + ".");
        }
    }

    private void verifyPattern(List<String> pattern, Object value) {
        if (pattern != null && value instanceof String) {
            for (String p : pattern) {
                verifyPattern(p, value);
            }
        }
    }
    private void verifyPattern(String pattern, Object value) {
        if (pattern != null && value instanceof String) {
            if (!Pattern.matches(pattern, (String) value)) {
                throw new IllegalArgumentException("Value does not match the required pattern.");
            }
        }
    }

    private void verifyMinimum(BigDecimal minimum, Object value) {
        if (minimum != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            if (valueDecimal.compareTo(minimum) < 0) {
                throw new IllegalArgumentException("Value is less than the minimum value of " + minimum + ".");
            }
        }
    }

    private void verifyMaximum(BigDecimal maximum, Object value) {
        if (maximum != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            if (valueDecimal.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("Value is greater than the maximum value of " + maximum + ".");
            }
        }
    }

    private void verifyExclusiveMinimum(BigDecimal exclusiveMinimum, Object value) {
        if (exclusiveMinimum != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            if (valueDecimal.compareTo(exclusiveMinimum) <= 0) {
                throw new IllegalArgumentException("Value is less than or equal to the exclusive minimum value of " + exclusiveMinimum + ".");
            }
        }
    }

    private void verifyExclusiveMaximum(BigDecimal exclusiveMaximum, Object value) {
        if (exclusiveMaximum != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            if (valueDecimal.compareTo(exclusiveMaximum) >= 0) {
                throw new IllegalArgumentException("Value is greater than or equal to the exclusive maximum value of " + exclusiveMaximum + ".");
            }
        }
    }

    private void verifyMultipleOf(BigDecimal multipleOf, Object value) {
        if (multipleOf != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            BigDecimal remainder = valueDecimal.remainder(multipleOf);
            if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("Value is not a multiple of " + multipleOf + ".");
            }
        }
    }

    private void verifyMinItems(Integer minItems, List<Node> items) {
        if (minItems != null && (items == null || items.size() < minItems)) {
            throw new IllegalArgumentException("Number of items is less than the minimum required items of " + minItems + ".");
        }
    }

    private void verifyMaxItems(Integer maxItems, List<Node> items) {
        if (maxItems != null && items != null && items.size() > maxItems) {
            throw new IllegalArgumentException("Number of items is greater than the maximum allowed items of " + maxItems + ".");
        }
    }

    private void verifyUniqueItems(Boolean uniqueItems, List<Node> items) {
        if (Boolean.TRUE.equals(uniqueItems) && items != null) {
            int uniqueItemsCount = items.stream()
                    .map(NodeToObject::get)
                    .map(doc -> YAML_MAPPER.convertValue(doc, Node.class))
                    .map(BlueIdCalculator::calculateBlueId)
                    .collect(Collectors.toSet())
                    .size();
            if (items.size() != uniqueItemsCount)
                throw new IllegalArgumentException("Unique items are required, but some items are identical.");
        }
    }

    private void verifyOptions(List<Node> options, Node node, NodeProvider nodeProvider) {
        if (options == null || node == null || Nodes.isValueLess(node)) {
            return;
        }

        if (Nodes.isSingleValueNode(node)) {
            if (!options.stream()
                    .map(Node::getValue)
                    .collect(Collectors.toSet())
                    .contains(node.getValue())) {
                throw new IllegalArgumentException("Value is not one of the allowed options.");
            }
        }

        String blueId = BlueIdCalculator.calculateBlueId(node, NodeToObject.Strategy.VALUE_MAPPING);
        Node nodeOptions = options.stream()
                .map(e -> {
                    if (e.getBlueId() != null) {
                        // we have to fetch, because we calculate only VALUE_MAPPING
                        List<Node> nodes = nodeProvider.fetchByBlueId(e.getBlueId());
                        if (nodes != null) {
                            return nodes.stream().findFirst().orElse(e);
                        }
                    }
                    return e;
                })
                .filter(n -> BlueIdCalculator.calculateBlueId(n, NodeToObject.Strategy.VALUE_MAPPING).equals(blueId))
                .findFirst()
                .orElse(null);
        if (nodeOptions == null) {
            throw new IllegalArgumentException("Value is not one of the allowed options.");
        }
    }

}
