package blue.language.merge.processor;

import blue.language.model.wire.SchemaPropertyConstants;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.merge.MergingProcessor;
import blue.language.provider.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.value.BlueNumbers;
import blue.language.model.NodeWireForm;
import blue.language.utils.ScalarNodeIdentity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE;
import static blue.language.model.wire.SchemaPropertyConstants.*;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static java.lang.Boolean.TRUE;

/**
 * Validates schema vocabulary during merging and validates payload-dependent
 * constraints against the completed resolved value.
 *
 * <p>Completed validation is deferred so inherited and authored contributions
 * are judged as one semantic value rather than as partial intermediates.</p>
 */
public class SchemaVerifier implements MergingProcessor {

    /**
     * Creates a stateless schema validation stage.
     */
    public SchemaVerifier() {
    }

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
    }

    @Override
    public boolean hasCompletedValidation(Node node) {
        return node != null && node.getSchema() != null;
    }

    @Override
    public boolean requiresReferenceMaterialization(Node node) {
        Schema schema = node != null ? node.getSchema() : null;
        return schema != null && hasPayloadDependentKeyword(schema);
    }

    @Override
    public void validateCompleted(Node node, boolean semanticallyPresent, String path) {
        Schema schema = node.getSchema();
        if (schema == null) {
            return;
        }
        try {
            verifyWellFormed(schema);
            onCompletedValidation(node, path);
            verifyValue(schema, node, semanticallyPresent);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Schema validation failed at path " + path + ": "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * Test seam for counting completed semantic validations without shared mutable state.
     *
     * @param node completed resolved value
     * @param path RFC 6901 path of the value
     */
    protected void onCompletedValidation(Node node, String path) {
        // default implementation
    }

    private void verifyValue(Schema schema, Node target, boolean semanticallyPresent) {

        verifyRequired(schema.getRequiredValue(), semanticallyPresent);
        if (!semanticallyPresent) {
            return;
        }
        verifyMinLength(schema.getMinLengthExact(), target);
        verifyMaxLength(schema.getMaxLengthExact(), target);
        verifyMinimum(schema.getMinimumValue(), target);
        verifyMaximum(schema.getMaximumValue(), target);
        verifyExclusiveMinimum(schema.getExclusiveMinimumValue(), target);
        verifyExclusiveMaximum(schema.getExclusiveMaximumValue(), target);
        verifyMultipleOf(schema.getMultipleOfValue(), target);
        verifyMinItems(schema.getMinItemsExact(), target);
        verifyMaxItems(schema.getMaxItemsExact(), target);
        verifyUniqueItems(schema.getUniqueItemsValue(), target);
        verifyMinFields(schema.getMinFieldsExact(), target);
        verifyMaxFields(schema.getMaxFieldsExact(), target);
        verifyEnum(schema.getEnum(), target);
    }

    private boolean hasPayloadDependentKeyword(Schema schema) {
        return schema.getMinLengthExact() != null
                || schema.getMaxLengthExact() != null
                || schema.getMinimumValue() != null
                || schema.getMaximumValue() != null
                || schema.getExclusiveMinimumValue() != null
                || schema.getExclusiveMaximumValue() != null
                || schema.getMultipleOfValue() != null
                || schema.getMinItemsExact() != null
                || schema.getMaxItemsExact() != null
                || Boolean.TRUE.equals(schema.getUniqueItemsValue())
                || schema.getMinFieldsExact() != null
                || schema.getMaxFieldsExact() != null
                || schema.getEnum() != null;
    }

    private void verifyWellFormed(Schema schema) {
        verifyNonNegative(KEY_MIN_LENGTH, schema.getMinLengthExact());
        verifyNonNegative(KEY_MAX_LENGTH, schema.getMaxLengthExact());
        verifyMinLessThanOrEqualMax(
                KEY_MIN_LENGTH,
                schema.getMinLengthExact(),
                KEY_MAX_LENGTH,
                schema.getMaxLengthExact());

        verifyNonNegative(KEY_MIN_ITEMS, schema.getMinItemsExact());
        verifyNonNegative(KEY_MAX_ITEMS, schema.getMaxItemsExact());
        verifyMinLessThanOrEqualMax(
                KEY_MIN_ITEMS,
                schema.getMinItemsExact(),
                KEY_MAX_ITEMS,
                schema.getMaxItemsExact());

        verifyNonNegative(KEY_MIN_FIELDS, schema.getMinFieldsExact());
        verifyNonNegative(KEY_MAX_FIELDS, schema.getMaxFieldsExact());
        verifyMinLessThanOrEqualMax(
                KEY_MIN_FIELDS,
                schema.getMinFieldsExact(),
                KEY_MAX_FIELDS,
                schema.getMaxFieldsExact());

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

    private void verifyRequired(Boolean required, boolean semanticallyPresent) {
        if (TRUE.equals(required) && !semanticallyPresent)
            throw new IllegalArgumentException("Required node has no value, items, or object fields.");
    }

    private void verifyMinLength(BigInteger minLength, Node node) {
        if (minLength == null) {
            return;
        }
        Object value = requireScalarPayload(KEY_MIN_LENGTH, node, String.class, "Text scalar");
        if (value == null) {
            return;
        }
        if (BigInteger.valueOf(codePointLength((String) value)).compareTo(minLength) < 0) {
            throw new IllegalArgumentException("Value \"" + value + "\" is shorter than the minimum length of " + minLength + ".");
        }
    }

    private void verifyMaxLength(BigInteger maxLength, Node node) {
        if (maxLength == null) {
            return;
        }
        Object value = requireScalarPayload(KEY_MAX_LENGTH, node, String.class, "Text scalar");
        if (value == null) {
            return;
        }
        if (BigInteger.valueOf(codePointLength((String) value)).compareTo(maxLength) > 0) {
            throw new IllegalArgumentException("Value \"" + value + "\" is longer than the maximum length of " + maxLength + ".");
        }
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private void verifyMinimum(BigDecimal minimum, Node node) {
        if (minimum == null) {
            return;
        }
        Object value = requireScalarPayload(KEY_MINIMUM, node, Number.class, "numeric scalar");
        if (value == null) {
            return;
        }
        BigDecimal valueDecimal = new BigDecimal(value.toString());
        if (valueDecimal.compareTo(minimum) < 0) {
            throw new IllegalArgumentException("Value " + value + " is less than the minimum value of " + minimum + ".");
        }
    }

    private void verifyMaximum(BigDecimal maximum, Node node) {
        if (maximum == null) {
            return;
        }
        Object value = requireScalarPayload(KEY_MAXIMUM, node, Number.class, "numeric scalar");
        if (value == null) {
            return;
        }
        BigDecimal valueDecimal = new BigDecimal(value.toString());
        if (valueDecimal.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Value " + value + " is greater than the maximum value of " + maximum + ".");
        }
    }

    private void verifyExclusiveMinimum(BigDecimal exclusiveMinimum, Node node) {
        if (exclusiveMinimum == null) {
            return;
        }
        Object value = requireScalarPayload(
                KEY_EXCLUSIVE_MINIMUM, node, Number.class, "numeric scalar");
        if (value == null) {
            return;
        }
        BigDecimal valueDecimal = new BigDecimal(value.toString());
        if (valueDecimal.compareTo(exclusiveMinimum) <= 0) {
            throw new IllegalArgumentException("Value " + value + " is less than or equal to the exclusive minimum value of " + exclusiveMinimum + ".");
        }
    }

    private void verifyExclusiveMaximum(BigDecimal exclusiveMaximum, Node node) {
        if (exclusiveMaximum == null) {
            return;
        }
        Object value = requireScalarPayload(
                KEY_EXCLUSIVE_MAXIMUM, node, Number.class, "numeric scalar");
        if (value == null) {
            return;
        }
        BigDecimal valueDecimal = new BigDecimal(value.toString());
        if (valueDecimal.compareTo(exclusiveMaximum) >= 0) {
            throw new IllegalArgumentException("Value " + value + " is greater than or equal to the exclusive maximum value of " + exclusiveMaximum + ".");
        }
    }

    private void verifyMultipleOf(BigDecimal multipleOf, Node node) {
        if (multipleOf == null) {
            return;
        }
        Object value = requireScalarPayload(KEY_MULTIPLE_OF, node, Number.class, "numeric scalar");
        if (value == null) {
            return;
        }
        if (!BlueNumbers.isExactBinary64Multiple(value, multipleOf)) {
            throw new IllegalArgumentException("Value " + value + " is not a multiple of " + multipleOf + ".");
        }
    }

    private void verifyMinItems(BigInteger minItems, Node node) {
        if (minItems == null) {
            return;
        }
        requireListPayload(KEY_MIN_ITEMS, node);
        List<Node> items = node.getItems();
        int size = items != null ? items.size() : 0;
        if (BigInteger.valueOf(size).compareTo(minItems) < 0) {
            throw new IllegalArgumentException("Number of items " + (items != null ? items.size() : 0) + " is less than the minimum required items of " + minItems + ".");
        }
    }

    private void verifyMaxItems(BigInteger maxItems, Node node) {
        if (maxItems == null) {
            return;
        }
        requireListPayload(KEY_MAX_ITEMS, node);
        List<Node> items = node.getItems();
        if (items != null && BigInteger.valueOf(items.size()).compareTo(maxItems) > 0) {
            throw new IllegalArgumentException("Number of items " + items.size() + " is greater than the maximum allowed items of " + maxItems + ".");
        }
    }

    private void verifyUniqueItems(Boolean uniqueItems, Node node) {
        if (!Boolean.TRUE.equals(uniqueItems)) {
            return;
        }
        requireListPayload(KEY_UNIQUE_ITEMS, node);
        List<Node> items = node.getItems();
        if (items != null) {
            int uniqueItemsCount = items.stream()
                    .map(NodeWireForm::get)
                    .map(doc -> YAML_MAPPER.convertValue(doc, Node.class))
                    .map(DirectBlueIdCalculator::calculateBlueId)
                    .collect(Collectors.toSet())
                    .size();
            if (items.size() != uniqueItemsCount)
                throw new IllegalArgumentException("Unique items are required, but some items are identical. Found items: " + items);
        }
    }

    private void verifyMinFields(BigInteger minFields, Node node) {
        if (minFields == null) {
            return;
        }
        requireObjectPayload(KEY_MIN_FIELDS, node);
        Map<String, Node> properties = node.getProperties();
        int fieldCount = properties == null ? 0 : properties.size();
        if (BigInteger.valueOf(fieldCount).compareTo(minFields) < 0) {
            throw new IllegalArgumentException("Number of fields " + fieldCount + " is less than the minimum required fields of " + minFields + ".");
        }
    }

    private void verifyMaxFields(BigInteger maxFields, Node node) {
        if (maxFields == null) {
            return;
        }
        requireObjectPayload(KEY_MAX_FIELDS, node);
        Map<String, Node> properties = node.getProperties();
        int fieldCount = properties == null ? 0 : properties.size();
        if (BigInteger.valueOf(fieldCount).compareTo(maxFields) > 0) {
            throw new IllegalArgumentException("Number of fields " + fieldCount + " is greater than the maximum allowed fields of " + maxFields + ".");
        }
    }

    private void verifyEnum(List<Node> enumValues, Node node) {
        if (enumValues == null) {
            return;
        }
        if (node.getValue() == null) {
            throw wrongKind(KEY_ENUM, "scalar", node);
        }

        String nodeBlueId = ScalarNodeIdentity.blueId(node);
        boolean matched = enumValues.stream()
                .map(ScalarNodeIdentity::blueId)
                .anyMatch(nodeBlueId::equals);
        if (!matched) {
            throw new IllegalArgumentException("Node value is not one of the allowed enum values.");
        }
    }

    private Object requireScalarPayload(String keyword, Node node, Class<?> expectedClass, String expected) {
        Object value = node.getValue();
        if (!expectedClass.isInstance(value)) {
            throw wrongKind(keyword, expected, node);
        }
        return value;
    }

    private void requireListPayload(String keyword, Node node) {
        if (node.getItems() == null) {
            throw wrongKind(keyword, "List payload", node);
        }
    }

    private void requireObjectPayload(String keyword, Node node) {
        if (!hasEffectiveObjectKind(node)) {
            throw wrongKind(keyword, "Dictionary/object payload", node);
        }
    }

    private boolean hasEffectiveObjectKind(Node node) {
        if (node.getProperties() != null && !node.getProperties().isEmpty()) {
            return true;
        }
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        Node type = node.getType();
        while (type != null && visited.add(type)) {
            if (DICTIONARY_TYPE_BLUE_ID.equals(type.getBlueId()) || isBareDictionaryAlias(type)) {
                return true;
            }
            type = type.getType();
        }
        return false;
    }

    private boolean isBareDictionaryAlias(Node type) {
        if (type.isInlineValue() && DICTIONARY_TYPE.equals(type.getValue())) {
            return true;
        }
        return DICTIONARY_TYPE.equals(type.getName())
                && type.getDescription() == null
                && type.getType() == null
                && type.getItemType() == null
                && type.getKeyType() == null
                && type.getValueType() == null
                && type.getValue() == null
                && type.getItems() == null
                && (type.getProperties() == null || type.getProperties().isEmpty())
                && type.getContracts() == null
                && type.getBlueId() == null
                && type.getSchema() == null
                && type.getMergePolicy() == null
                && type.getPreviousBlueId() == null
                && type.getPosition() == null
                && type.getBlue() == null;
    }

    private IllegalArgumentException wrongKind(String keyword, String expected, Node node) {
        return new IllegalArgumentException("Schema keyword \"" + keyword + "\" applies to wrong kind; expected " + expected + ".");
    }
}
