package blue.language.merge.processor;

import blue.language.merge.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static java.lang.Boolean.TRUE;

public class SchemaVerifier implements MergingProcessor {

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        // do nothing
    }

    @Override
    public void postProcess(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        Schema schema = target.getSchema();
        if (schema == null)
            return;

        verifyWellFormed(schema);

        verifyRequired(schema.getRequiredValue(), target);
        verifyAllowMultiple(schema.getAllowMultipleValue(), target.getItems());
        verifyMinLength(schema.getMinLengthValue(), target.getValue());
        verifyMaxLength(schema.getMaxLengthValue(), target.getValue());
        verifyPattern(schema.getPatternValue(), target.getValue());
        verifyMinimum(schema.getMinimumValue(), target.getValue());
        verifyMaximum(schema.getMaximumValue(), target.getValue());
        verifyExclusiveMinimum(schema.getExclusiveMinimumValue(), target.getValue());
        verifyExclusiveMaximum(schema.getExclusiveMaximumValue(), target.getValue());
        verifyMultipleOf(schema.getMultipleOfValue(), target.getValue());
        verifyMinItems(schema.getMinItemsValue(), target.getItems());
        verifyMaxItems(schema.getMaxItemsValue(), target.getItems());
        verifyUniqueItems(schema.getUniqueItemsValue(), target.getItems());
        verifyMinFields(schema.getMinFieldsValue(), target.getProperties());
        verifyMaxFields(schema.getMaxFieldsValue(), target.getProperties());
        verifyEnum(schema.getEnum(), target);
    }

    private void verifyWellFormed(Schema schema) {
        verifyNonNegative("minLength", schema.getMinLengthValue());
        verifyNonNegative("maxLength", schema.getMaxLengthValue());
        verifyMinLessThanOrEqualMax("minLength", schema.getMinLengthValue(), "maxLength", schema.getMaxLengthValue());

        verifyNonNegative("minItems", schema.getMinItemsValue());
        verifyNonNegative("maxItems", schema.getMaxItemsValue());
        verifyMinLessThanOrEqualMax("minItems", schema.getMinItemsValue(), "maxItems", schema.getMaxItemsValue());

        verifyNonNegative("minFields", schema.getMinFieldsValue());
        verifyNonNegative("maxFields", schema.getMaxFieldsValue());
        verifyMinLessThanOrEqualMax("minFields", schema.getMinFieldsValue(), "maxFields", schema.getMaxFieldsValue());

        verifyMinimumLessThanOrEqualMaximum(schema.getMinimumValue(), schema.getMaximumValue());
        verifyExclusiveMinimumLessThanExclusiveMaximum(schema.getExclusiveMinimumValue(), schema.getExclusiveMaximumValue());
        verifyMultipleOfKeyword(schema.getMultipleOfValue());
    }

    private void verifyNonNegative(String keyword, Integer value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Schema keyword \"" + keyword + "\" must be non-negative.");
        }
    }

    private void verifyMinLessThanOrEqualMax(String minKeyword, Integer minValue, String maxKeyword, Integer maxValue) {
        if (minValue != null && maxValue != null && minValue > maxValue) {
            throw new IllegalArgumentException("Schema keyword \"" + minKeyword + "\" must be less than or equal to \"" + maxKeyword + "\".");
        }
    }

    private void verifyMinimumLessThanOrEqualMaximum(BigDecimal minimum, BigDecimal maximum) {
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Schema keyword \"minimum\" must be less than or equal to \"maximum\".");
        }
    }

    private void verifyExclusiveMinimumLessThanExclusiveMaximum(BigDecimal exclusiveMinimum, BigDecimal exclusiveMaximum) {
        if (exclusiveMinimum != null && exclusiveMaximum != null && exclusiveMinimum.compareTo(exclusiveMaximum) >= 0) {
            throw new IllegalArgumentException("Schema keyword \"exclusiveMinimum\" must be less than \"exclusiveMaximum\".");
        }
    }

    private void verifyMultipleOfKeyword(BigDecimal multipleOf) {
        if (multipleOf != null && multipleOf.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Schema keyword \"multipleOf\" must be greater than zero.");
        }
    }

    private void verifyRequired(Boolean required, Node node) {
        if (TRUE.equals(required) && !hasPayload(node))
            throw new IllegalArgumentException("Required node has no value, items, or object fields.");
    }

    private boolean hasPayload(Node node) {
        return node.getValue() != null
                || node.getItems() != null
                || (node.getProperties() != null && !node.getProperties().isEmpty());
    }

    private void verifyAllowMultiple(Boolean allowMultiple, List<Node> items) {
        if ((allowMultiple == null || Boolean.FALSE.equals(allowMultiple)) && items != null && items.size() > 1)
            throw new IllegalArgumentException("Multiple items are not allowed. Found items: " + items);
    }

    private void verifyMinLength(Integer minLength, Object value) {
        if (minLength != null && value instanceof String && codePointLength((String) value) < minLength)
            throw new IllegalArgumentException("Value \"" + value + "\" is shorter than the minimum length of " + minLength + ".");
    }

    private void verifyMaxLength(Integer maxLength, Object value) {
        if (maxLength != null && value instanceof String && codePointLength((String) value) > maxLength) {
            throw new IllegalArgumentException("Value \"" + value + "\" is longer than the maximum length of " + maxLength + ".");
        }
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
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
                throw new IllegalArgumentException("Value \"" + value + "\" does not match the required pattern \"" + pattern + "\".");
            }
        }
    }

    private void verifyMinimum(BigDecimal minimum, Object value) {
        if (minimum != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            if (valueDecimal.compareTo(minimum) < 0) {
                throw new IllegalArgumentException("Value " + value + " is less than the minimum value of " + minimum + ".");
            }
        }
    }

    private void verifyMaximum(BigDecimal maximum, Object value) {
        if (maximum != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            if (valueDecimal.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("Value " + value + " is greater than the maximum value of " + maximum + ".");
            }
        }
    }

    private void verifyExclusiveMinimum(BigDecimal exclusiveMinimum, Object value) {
        if (exclusiveMinimum != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            if (valueDecimal.compareTo(exclusiveMinimum) <= 0) {
                throw new IllegalArgumentException("Value " + value + " is less than or equal to the exclusive minimum value of " + exclusiveMinimum + ".");
            }
        }
    }

    private void verifyExclusiveMaximum(BigDecimal exclusiveMaximum, Object value) {
        if (exclusiveMaximum != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            if (valueDecimal.compareTo(exclusiveMaximum) >= 0) {
                throw new IllegalArgumentException("Value " + value + " is greater than or equal to the exclusive maximum value of " + exclusiveMaximum + ".");
            }
        }
    }

    private void verifyMultipleOf(BigDecimal multipleOf, Object value) {
        if (multipleOf != null && value instanceof Number) {
            BigDecimal valueDecimal = new BigDecimal(value.toString());
            BigDecimal remainder = valueDecimal.remainder(multipleOf);
            if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("Value " + value + " is not a multiple of " + multipleOf + ".");
            }
        }
    }

    private void verifyMinItems(Integer minItems, List<Node> items) {
        if (minItems != null && (items == null || items.size() < minItems)) {
            throw new IllegalArgumentException("Number of items " + (items != null ? items.size() : 0) + " is less than the minimum required items of " + minItems + ".");
        }
    }

    private void verifyMaxItems(Integer maxItems, List<Node> items) {
        if (maxItems != null && items != null && items.size() > maxItems) {
            throw new IllegalArgumentException("Number of items " + items.size() + " is greater than the maximum allowed items of " + maxItems + ".");
        }
    }

    private void verifyUniqueItems(Boolean uniqueItems, List<Node> items) {
        if (Boolean.TRUE.equals(uniqueItems) && items != null) {
            int uniqueItemsCount = items.stream()
                    .map(NodeToMapListOrValue::get)
                    .map(doc -> YAML_MAPPER.convertValue(doc, Node.class))
                    .map(BlueIdCalculator::calculateBlueId)
                    .collect(Collectors.toSet())
                    .size();
            if (items.size() != uniqueItemsCount)
                throw new IllegalArgumentException("Unique items are required, but some items are identical. Found items: " + items);
        }
    }

    private void verifyMinFields(Integer minFields, Map<String, Node> properties) {
        int fieldCount = properties == null ? 0 : properties.size();
        if (minFields != null && fieldCount < minFields) {
            throw new IllegalArgumentException("Number of fields " + fieldCount + " is less than the minimum required fields of " + minFields + ".");
        }
    }

    private void verifyMaxFields(Integer maxFields, Map<String, Node> properties) {
        int fieldCount = properties == null ? 0 : properties.size();
        if (maxFields != null && fieldCount > maxFields) {
            throw new IllegalArgumentException("Number of fields " + fieldCount + " is greater than the maximum allowed fields of " + maxFields + ".");
        }
    }

    private void verifyEnum(List<Node> enumValues, Node node) {
        if (enumValues == null) {
            return;
        }

        String nodeBlueId = comparableBlueId(node);
        boolean matched = enumValues.stream()
                .map(this::comparableBlueId)
                .anyMatch(nodeBlueId::equals);
        if (!matched) {
            throw new IllegalArgumentException("Node value is not one of the allowed enum values.");
        }
    }

    private String comparableBlueId(Node node) {
        Node comparable = node.clone();
        comparable.schema(null);
        return BlueIdCalculator.calculateBlueId(comparable);
    }
}
