package blue.language.merge.processor;

import blue.language.merge.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
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
        verifyMinLength(schema.getMinLengthExact(), target.getValue());
        verifyMaxLength(schema.getMaxLengthExact(), target.getValue());
        verifyMinimum(schema.getMinimumValue(), target.getValue());
        verifyMaximum(schema.getMaximumValue(), target.getValue());
        verifyExclusiveMinimum(schema.getExclusiveMinimumValue(), target.getValue());
        verifyExclusiveMaximum(schema.getExclusiveMaximumValue(), target.getValue());
        verifyMultipleOf(schema.getMultipleOfValue(), target.getValue());
        verifyMinItems(schema.getMinItemsExact(), target.getItems());
        verifyMaxItems(schema.getMaxItemsExact(), target.getItems());
        verifyUniqueItems(schema.getUniqueItemsValue(), target.getItems());
        verifyMinFields(schema.getMinFieldsExact(), target.getProperties());
        verifyMaxFields(schema.getMaxFieldsExact(), target.getProperties());
        verifyEnum(schema.getEnum(), target);
    }

    private void verifyWellFormed(Schema schema) {
        verifyNonNegative("minLength", schema.getMinLengthExact());
        verifyNonNegative("maxLength", schema.getMaxLengthExact());
        verifyMinLessThanOrEqualMax("minLength", schema.getMinLengthExact(), "maxLength", schema.getMaxLengthExact());

        verifyNonNegative("minItems", schema.getMinItemsExact());
        verifyNonNegative("maxItems", schema.getMaxItemsExact());
        verifyMinLessThanOrEqualMax("minItems", schema.getMinItemsExact(), "maxItems", schema.getMaxItemsExact());

        verifyNonNegative("minFields", schema.getMinFieldsExact());
        verifyNonNegative("maxFields", schema.getMaxFieldsExact());
        verifyMinLessThanOrEqualMax("minFields", schema.getMinFieldsExact(), "maxFields", schema.getMaxFieldsExact());

        verifyMinimumLessThanOrEqualMaximum(schema.getMinimumValue(), schema.getMaximumValue());
        verifyExclusiveMinimumLessThanExclusiveMaximum(schema.getExclusiveMinimumValue(), schema.getExclusiveMaximumValue());
        verifyMultipleOfKeyword(schema.getMultipleOfValue());
    }

    private void verifyNonNegative(String keyword, BigInteger value) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException("Schema keyword \"" + keyword + "\" must be non-negative.");
        }
    }

    private void verifyMinLessThanOrEqualMax(String minKeyword, BigInteger minValue, String maxKeyword, BigInteger maxValue) {
        if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
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

    private void verifyMinLength(BigInteger minLength, Object value) {
        if (minLength != null
                && value instanceof String
                && BigInteger.valueOf(codePointLength((String) value)).compareTo(minLength) < 0) {
            throw new IllegalArgumentException("Value \"" + value + "\" is shorter than the minimum length of " + minLength + ".");
        }
    }

    private void verifyMaxLength(BigInteger maxLength, Object value) {
        if (maxLength != null
                && value instanceof String
                && BigInteger.valueOf(codePointLength((String) value)).compareTo(maxLength) > 0) {
            throw new IllegalArgumentException("Value \"" + value + "\" is longer than the maximum length of " + maxLength + ".");
        }
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
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

    private void verifyMinItems(BigInteger minItems, List<Node> items) {
        int size = items != null ? items.size() : 0;
        if (minItems != null && BigInteger.valueOf(size).compareTo(minItems) < 0) {
            throw new IllegalArgumentException("Number of items " + (items != null ? items.size() : 0) + " is less than the minimum required items of " + minItems + ".");
        }
    }

    private void verifyMaxItems(BigInteger maxItems, List<Node> items) {
        if (maxItems != null && items != null && BigInteger.valueOf(items.size()).compareTo(maxItems) > 0) {
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

    private void verifyMinFields(BigInteger minFields, Map<String, Node> properties) {
        int fieldCount = properties == null ? 0 : properties.size();
        if (minFields != null && BigInteger.valueOf(fieldCount).compareTo(minFields) < 0) {
            throw new IllegalArgumentException("Number of fields " + fieldCount + " is less than the minimum required fields of " + minFields + ".");
        }
    }

    private void verifyMaxFields(BigInteger maxFields, Map<String, Node> properties) {
        int fieldCount = properties == null ? 0 : properties.size();
        if (maxFields != null && BigInteger.valueOf(fieldCount).compareTo(maxFields) > 0) {
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
