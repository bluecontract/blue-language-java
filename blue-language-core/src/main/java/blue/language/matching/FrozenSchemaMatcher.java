package blue.language.matching;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import blue.language.model.value.BlueNumbers;
import blue.language.utils.ScalarNodeIdentity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Evaluates the released schema keywords against an immutable candidate. */
final class FrozenSchemaMatcher {

    /**
     * Evaluates every populated keyword, failing closed for malformed schemas
     * and wrong-kind candidate payloads.
     */
    public boolean matches(FrozenNode node, Schema schema) {
        if (schema == null) {
            return true;
        }
        try {
            verifyWellFormed(schema);
            return verifyRequired(schema, node)
                    && verifyMinLength(schema, node)
                    && verifyMaxLength(schema, node)
                    && verifyMinimum(schema, node)
                    && verifyMaximum(schema, node)
                    && verifyExclusiveMinimum(schema, node)
                    && verifyExclusiveMaximum(schema, node)
                    && verifyMultipleOf(schema, node)
                    && verifyMinItems(schema, node)
                    && verifyMaxItems(schema, node)
                    && verifyUniqueItems(schema, node)
                    && verifyMinFields(schema, node)
                    && verifyMaxFields(schema, node)
                    && verifyEnum(schema, node);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void verifyWellFormed(Schema schema) {
        verifyNonNegative(schema.getMinLengthExact());
        verifyNonNegative(schema.getMaxLengthExact());
        verifyMinLessThanOrEqualMax(
                schema.getMinLengthExact(), schema.getMaxLengthExact());
        verifyNonNegative(schema.getMinItemsExact());
        verifyNonNegative(schema.getMaxItemsExact());
        verifyMinLessThanOrEqualMax(
                schema.getMinItemsExact(), schema.getMaxItemsExact());
        verifyNonNegative(schema.getMinFieldsExact());
        verifyNonNegative(schema.getMaxFieldsExact());
        verifyMinLessThanOrEqualMax(
                schema.getMinFieldsExact(), schema.getMaxFieldsExact());
        if (schema.getMinimumValue() != null
                && schema.getMaximumValue() != null
                && schema.getMinimumValue().compareTo(schema.getMaximumValue()) > 0) {
            throw new IllegalArgumentException("minimum must be <= maximum");
        }
        if (schema.getExclusiveMinimumValue() != null
                && schema.getExclusiveMaximumValue() != null
                && schema.getExclusiveMinimumValue()
                .compareTo(schema.getExclusiveMaximumValue()) >= 0) {
            throw new IllegalArgumentException(
                    "exclusiveMinimum must be < exclusiveMaximum");
        }
        if (schema.getMultipleOfValue() != null
                && schema.getMultipleOfValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("multipleOf must be > 0");
        }
    }

    private void verifyNonNegative(BigInteger value) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(
                    "schema value must be non-negative");
        }
    }

    private void verifyMinLessThanOrEqualMax(BigInteger min, BigInteger max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("schema min must be <= max");
        }
    }

    private boolean verifyRequired(Schema schema, FrozenNode node) {
        return !Boolean.TRUE.equals(schema.getRequiredValue()) || hasPayload(node);
    }

    private boolean verifyMinLength(Schema schema, FrozenNode node) {
        BigInteger minimumLength = schema.getMinLengthExact();
        Object value = node.getValue();
        if (minimumLength == null || !hasPayload(node)) {
            return true;
        }
        return value instanceof String
                && codePointLength((String) value).compareTo(minimumLength) >= 0;
    }

    private boolean verifyMaxLength(Schema schema, FrozenNode node) {
        BigInteger maximumLength = schema.getMaxLengthExact();
        Object value = node.getValue();
        if (maximumLength == null || !hasPayload(node)) {
            return true;
        }
        return value instanceof String
                && codePointLength((String) value).compareTo(maximumLength) <= 0;
    }

    private BigInteger codePointLength(String value) {
        return BigInteger.valueOf(value.codePointCount(0, value.length()));
    }

    private boolean verifyMinimum(Schema schema, FrozenNode node) {
        return compareNumber(node, schema.getMinimumValue()) >= 0;
    }

    private boolean verifyMaximum(Schema schema, FrozenNode node) {
        return compareNumber(node, schema.getMaximumValue()) <= 0;
    }

    private boolean verifyExclusiveMinimum(Schema schema, FrozenNode node) {
        return schema.getExclusiveMinimumValue() == null
                || compareNumber(node, schema.getExclusiveMinimumValue()) > 0;
    }

    private boolean verifyExclusiveMaximum(Schema schema, FrozenNode node) {
        return schema.getExclusiveMaximumValue() == null
                || compareNumber(node, schema.getExclusiveMaximumValue()) < 0;
    }

    private boolean verifyMultipleOf(Schema schema, FrozenNode node) {
        BigDecimal multipleOf = schema.getMultipleOfValue();
        Object value = node.getValue();
        if (multipleOf == null || !hasPayload(node)) {
            return true;
        }
        return value instanceof Number
                && BlueNumbers.isExactBinary64Multiple(value, multipleOf);
    }

    private int compareNumber(FrozenNode node, BigDecimal bound) {
        Object value = node.getValue();
        if (bound == null || !hasPayload(node)) {
            return 0;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(
                    "numeric schema keyword applies to wrong kind");
        }
        return numberValue(value).compareTo(bound);
    }

    private BigDecimal numberValue(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        return new BigDecimal(value.toString());
    }

    private boolean verifyMinItems(Schema schema, FrozenNode node) {
        BigInteger minimumItems = schema.getMinItemsExact();
        if (minimumItems == null || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null
                || node.getProperties() != null && !node.getProperties().isEmpty()) {
            return false;
        }
        int size = node.getItems() != null ? node.getItems().size() : 0;
        return BigInteger.valueOf(size).compareTo(minimumItems) >= 0;
    }

    private boolean verifyMaxItems(Schema schema, FrozenNode node) {
        BigInteger maximumItems = schema.getMaxItemsExact();
        if (maximumItems == null || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null
                || node.getProperties() != null && !node.getProperties().isEmpty()) {
            return false;
        }
        int size = node.getItems() != null ? node.getItems().size() : 0;
        return BigInteger.valueOf(size).compareTo(maximumItems) <= 0;
    }

    private boolean verifyUniqueItems(Schema schema, FrozenNode node) {
        if (!Boolean.TRUE.equals(schema.getUniqueItemsValue()) || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null
                || node.getProperties() != null && !node.getProperties().isEmpty()) {
            return false;
        }
        if (node.getItems() == null) {
            return true;
        }
        Set<String> itemIds = new HashSet<>();
        for (FrozenNode item : node.getItems()) {
            if (!itemIds.add(item.blueId())) {
                return false;
            }
        }
        return true;
    }

    private boolean verifyMinFields(Schema schema, FrozenNode node) {
        BigInteger minimumFields = schema.getMinFieldsExact();
        if (minimumFields == null || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null || node.getItems() != null) {
            return false;
        }
        int size = node.getProperties() != null ? node.getProperties().size() : 0;
        return BigInteger.valueOf(size).compareTo(minimumFields) >= 0;
    }

    private boolean verifyMaxFields(Schema schema, FrozenNode node) {
        BigInteger maximumFields = schema.getMaxFieldsExact();
        if (maximumFields == null || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null || node.getItems() != null) {
            return false;
        }
        int size = node.getProperties() != null ? node.getProperties().size() : 0;
        return BigInteger.valueOf(size).compareTo(maximumFields) <= 0;
    }

    private boolean verifyEnum(Schema schema, FrozenNode node) {
        List<Node> enumValues = schema.getEnum();
        if (enumValues == null) {
            return true;
        }
        if (node.getValue() == null) {
            return !hasPayload(node);
        }
        String nodeBlueId = ScalarNodeIdentity.blueId(node.toNode());
        for (Node enumValue : enumValues) {
            if (nodeBlueId.equals(ScalarNodeIdentity.blueId(enumValue))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPayload(FrozenNode node) {
        return node.isReferenceOnly()
                || node.getValue() != null
                || node.getItems() != null
                || node.getProperties() != null && !node.getProperties().isEmpty();
    }
}
