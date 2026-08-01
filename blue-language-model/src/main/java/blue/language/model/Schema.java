package blue.language.model;

import blue.language.model.value.ScalarValues;

import blue.language.model.wire.SchemaPropertyConstants;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static blue.language.model.value.ScalarValues.*;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_ENUM;

/**
 * Mutable representation of the closed Blue Language core schema vocabulary.
 *
 * <p>Keyword values remain Blue {@link Node} instances so exact type and
 * identity information is preserved. Typed convenience getters expose numeric
 * and boolean values. {@link #clone()} deep-copies keyword and enum nodes.</p>
 */
public class Schema implements Cloneable {

    private String blueId;
    private Node required;
    private Node minLength;
    private Node maxLength;
    private Node minimum;
    private Node maximum;
    private Node exclusiveMinimum;
    private Node exclusiveMaximum;
    private Node multipleOf;
    private Node minItems;
    private Node maxItems;
    private Node uniqueItems;
    private Node minFields;
    private Node maxFields;
    @JsonProperty(KEY_ENUM)
    private List<Node> enumValues;

    /** Creates an empty mutable schema. */
    public Schema() {
    }

    /**
     * Returns the exact schema identity when this object is a reference.
     *
     * @return referenced schema BlueId, or {@code null}
     */
    public String getBlueId() {
        return blueId;
    }

    /**
     * Sets the exact schema identity.
     *
     * @param blueId referenced schema BlueId, or {@code null}
     * @return this mutable schema
     */
    public Schema blueId(String blueId) {
        this.blueId = blueId;
        return this;
    }

    /**
     * Reports whether this schema contains only an exact {@code blueId}
     * reference.
     *
     * @return {@code true} when no inline keyword accompanies the identity
     */
    public boolean isReferenceOnly() {
        return blueId != null
                && required == null
                && minLength == null
                && maxLength == null
                && minimum == null
                && maximum == null
                && exclusiveMinimum == null
                && exclusiveMaximum == null
                && multipleOf == null
                && minItems == null
                && maxItems == null
                && uniqueItems == null
                && minFields == null
                && maxFields == null
                && enumValues == null;
    }

    /**
     * Returns the exact {@code required} keyword node.
     *
     * @return required keyword node, or {@code null}
     */
    public Node getRequired() {
        return required;
    }

    /**
     * Returns the exact {@code minLength} keyword node.
     *
     * @return minimum-length node, or {@code null}
     */
    public Node getMinLength() {
        return minLength;
    }

    /**
     * Returns the exact {@code maxLength} keyword node.
     *
     * @return maximum-length node, or {@code null}
     */
    public Node getMaxLength() {
        return maxLength;
    }

    /**
     * Returns the exact inclusive {@code minimum} keyword node.
     *
     * @return minimum node, or {@code null}
     */
    public Node getMinimum() {
        return minimum;
    }

    /**
     * Returns the exact inclusive {@code maximum} keyword node.
     *
     * @return maximum node, or {@code null}
     */
    public Node getMaximum() {
        return maximum;
    }

    /**
     * Returns the exact {@code exclusiveMinimum} keyword node.
     *
     * @return exclusive-minimum node, or {@code null}
     */
    public Node getExclusiveMinimum() {
        return exclusiveMinimum;
    }

    /**
     * Returns the exact {@code exclusiveMaximum} keyword node.
     *
     * @return exclusive-maximum node, or {@code null}
     */
    public Node getExclusiveMaximum() {
        return exclusiveMaximum;
    }

    /**
     * Returns the exact {@code multipleOf} keyword node.
     *
     * @return multiple-of node, or {@code null}
     */
    public Node getMultipleOf() {
        return multipleOf;
    }

    /**
     * Returns the exact {@code minItems} keyword node.
     *
     * @return minimum-items node, or {@code null}
     */
    public Node getMinItems() {
        return minItems;
    }

    /**
     * Returns the exact {@code maxItems} keyword node.
     *
     * @return maximum-items node, or {@code null}
     */
    public Node getMaxItems() {
        return maxItems;
    }

    /**
     * Returns the exact {@code uniqueItems} keyword node.
     *
     * @return unique-items node, or {@code null}
     */
    public Node getUniqueItems() {
        return uniqueItems;
    }

    /**
     * Reads the {@code required} keyword as a Boolean.
     *
     * @return required value, or {@code null}
     */
    public Boolean getRequiredValue() {
        return required == null ? null : getBooleanFromObject(required.getValue());
    }

    /**
     * Reads {@code minLength} without narrowing its integer range.
     *
     * @return exact minimum length, or {@code null}
     */
    public BigInteger getMinLengthExact() {
        return minLength == null ? null : getBigIntegerFromObject(minLength.getValue());
    }

    /**
     * Reads {@code maxLength} without narrowing its integer range.
     *
     * @return exact maximum length, or {@code null}
     */
    public BigInteger getMaxLengthExact() {
        return maxLength == null ? null : getBigIntegerFromObject(maxLength.getValue());
    }

    /**
     * Reads the inclusive numeric minimum.
     *
     * @return minimum value, or {@code null}
     */
    public BigDecimal getMinimumValue() {
        return minimum == null ? null : getBigDecimalFromObject(minimum.getValue());
    }

    /**
     * Reads the inclusive numeric maximum.
     *
     * @return maximum value, or {@code null}
     */
    public BigDecimal getMaximumValue() {
        return maximum == null ? null : getBigDecimalFromObject(maximum.getValue());
    }

    /**
     * Reads the exclusive numeric minimum.
     *
     * @return exclusive minimum, or {@code null}
     */
    public BigDecimal getExclusiveMinimumValue() {
        return exclusiveMinimum == null ? null : getBigDecimalFromObject(exclusiveMinimum.getValue());
    }

    /**
     * Reads the exclusive numeric maximum.
     *
     * @return exclusive maximum, or {@code null}
     */
    public BigDecimal getExclusiveMaximumValue() {
        return exclusiveMaximum == null ? null : getBigDecimalFromObject(exclusiveMaximum.getValue());
    }

    /**
     * Reads the exact numeric divisor.
     *
     * @return multiple-of value, or {@code null}
     */
    public BigDecimal getMultipleOfValue() {
        return multipleOf == null ? null : getBigDecimalFromObject(multipleOf.getValue());
    }

    /**
     * Reads {@code minItems} without narrowing its integer range.
     *
     * @return exact minimum item count, or {@code null}
     */
    public BigInteger getMinItemsExact() {
        return minItems == null ? null : getBigIntegerFromObject(minItems.getValue());
    }

    /**
     * Reads {@code maxItems} without narrowing its integer range.
     *
     * @return exact maximum item count, or {@code null}
     */
    public BigInteger getMaxItemsExact() {
        return maxItems == null ? null : getBigIntegerFromObject(maxItems.getValue());
    }

    /**
     * Reads the {@code uniqueItems} keyword as a Boolean.
     *
     * @return unique-items value, or {@code null}
     */
    public Boolean getUniqueItemsValue() {
        return uniqueItems == null ? null : getBooleanFromObject(uniqueItems.getValue());
    }

    /**
     * Returns the exact {@code minFields} keyword node.
     *
     * @return minimum-fields node, or {@code null}
     */
    public Node getMinFields() {
        return minFields;
    }

    /**
     * Returns the exact {@code maxFields} keyword node.
     *
     * @return maximum-fields node, or {@code null}
     */
    public Node getMaxFields() {
        return maxFields;
    }

    /**
     * Returns the live mutable enum node list.
     *
     * @return enum values, or {@code null} when absent
     */
    @JsonProperty(KEY_ENUM)
    public List<Node> getEnum() {
        return enumValues;
    }

    /**
     * Reads {@code minFields} without narrowing its integer range.
     *
     * @return exact minimum field count, or {@code null}
     */
    public BigInteger getMinFieldsExact() {
        return minFields == null ? null : getBigIntegerFromObject(minFields.getValue());
    }

    /**
     * Reads {@code maxFields} without narrowing its integer range.
     *
     * @return exact maximum field count, or {@code null}
     */
    public BigInteger getMaxFieldsExact() {
        return maxFields == null ? null : getBigIntegerFromObject(maxFields.getValue());
    }

    /**
     * Sets the exact {@code required} keyword node.
     *
     * @param required keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema required(Node required) {
        this.required = required;
        return this;
    }

    /**
     * Sets the exact {@code minLength} keyword node.
     *
     * @param minLength keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema minLength(Node minLength) {
        this.minLength = minLength;
        return this;
    }

    /**
     * Sets the exact {@code maxLength} keyword node.
     *
     * @param maxLength keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema maxLength(Node maxLength) {
        this.maxLength = maxLength;
        return this;
    }

    /**
     * Sets the exact inclusive {@code minimum} keyword node.
     *
     * @param minimum keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema minimum(Node minimum) {
        this.minimum = minimum;
        return this;
    }

    /**
     * Sets the exact inclusive {@code maximum} keyword node.
     *
     * @param maximum keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema maximum(Node maximum) {
        this.maximum = maximum;
        return this;
    }

    /**
     * Sets the exact {@code exclusiveMinimum} keyword node.
     *
     * @param exclusiveMinimum keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema exclusiveMinimum(Node exclusiveMinimum) {
        this.exclusiveMinimum = exclusiveMinimum;
        return this;
    }

    /**
     * Sets the exact {@code exclusiveMaximum} keyword node.
     *
     * @param exclusiveMaximum keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema exclusiveMaximum(Node exclusiveMaximum) {
        this.exclusiveMaximum = exclusiveMaximum;
        return this;
    }

    /**
     * Sets the exact {@code multipleOf} keyword node.
     *
     * @param multipleOf keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema multipleOf(Node multipleOf) {
        this.multipleOf = multipleOf;
        return this;
    }

    /**
     * Sets the exact {@code minItems} keyword node.
     *
     * @param minItems keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema minItems(Node minItems) {
        this.minItems = minItems;
        return this;
    }

    /**
     * Sets the exact {@code maxItems} keyword node.
     *
     * @param maxItems keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema maxItems(Node maxItems) {
        this.maxItems = maxItems;
        return this;
    }

    /**
     * Sets the exact {@code uniqueItems} keyword node.
     *
     * @param uniqueItems keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema uniqueItems(Node uniqueItems) {
        this.uniqueItems = uniqueItems;
        return this;
    }

    /**
     * Sets the exact {@code minFields} keyword node.
     *
     * @param minFields keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema minFields(Node minFields) {
        this.minFields = minFields;
        return this;
    }

    /**
     * Sets the exact {@code maxFields} keyword node.
     *
     * @param maxFields keyword node, or {@code null}
     * @return this mutable schema
     */
    public Schema maxFields(Node maxFields) {
        this.maxFields = maxFields;
        return this;
    }

    /**
     * Replaces the live enum-value list.
     *
     * @param enumValues exact enum nodes, or {@code null}
     * @return this mutable schema
     */
    public Schema enumValues(List<Node> enumValues) {
        this.enumValues = enumValues;
        return this;
    }

    /**
     * Sets {@code required} from a Boolean scalar.
     *
     * @param required required value
     * @return this mutable schema
     */
    public Schema required(Boolean required) {
        this.required = new Node().value(required);
        return this;
    }

    /**
     * Sets {@code minLength} from a Java integer.
     *
     * @param minLength minimum length
     * @return this mutable schema
     */
    public Schema minLength(Integer minLength) {
        this.minLength = new Node().value(BigInteger.valueOf(minLength));
        return this;
    }

    /**
     * Sets {@code minLength} without narrowing its integer range.
     *
     * @param minLength exact minimum length
     * @return this mutable schema
     */
    public Schema minLength(BigInteger minLength) {
        this.minLength = new Node().value(minLength);
        return this;
    }

    /**
     * Sets {@code maxLength} from a Java integer.
     *
     * @param maxLength maximum length
     * @return this mutable schema
     */
    public Schema maxLength(Integer maxLength) {
        this.maxLength = new Node().value(BigInteger.valueOf(maxLength));
        return this;
    }

    /**
     * Sets {@code maxLength} without narrowing its integer range.
     *
     * @param maxLength exact maximum length
     * @return this mutable schema
     */
    public Schema maxLength(BigInteger maxLength) {
        this.maxLength = new Node().value(maxLength);
        return this;
    }

    /**
     * Sets the inclusive numeric minimum.
     *
     * @param minimum minimum value
     * @return this mutable schema
     */
    public Schema minimum(BigDecimal minimum) {
        this.minimum = new Node().value(minimum);
        return this;
    }

    /**
     * Sets the inclusive numeric maximum.
     *
     * @param maximum maximum value
     * @return this mutable schema
     */
    public Schema maximum(BigDecimal maximum) {
        this.maximum = new Node().value(maximum);
        return this;
    }

    /**
     * Sets the exclusive numeric minimum.
     *
     * @param exclusiveMinimum exclusive minimum
     * @return this mutable schema
     */
    public Schema exclusiveMinimum(BigDecimal exclusiveMinimum) {
        this.exclusiveMinimum = new Node().value(exclusiveMinimum);
        return this;
    }

    /**
     * Sets the exclusive numeric maximum.
     *
     * @param exclusiveMaximum exclusive maximum
     * @return this mutable schema
     */
    public Schema exclusiveMaximum(BigDecimal exclusiveMaximum) {
        this.exclusiveMaximum = new Node().value(exclusiveMaximum);
        return this;
    }

    /**
     * Sets the exact numeric divisor.
     *
     * @param multipleOf multiple-of value
     * @return this mutable schema
     */
    public Schema multipleOf(BigDecimal multipleOf) {
        this.multipleOf = new Node().value(multipleOf);
        return this;
    }

    /**
     * Sets {@code minItems} from a Java integer.
     *
     * @param minItems minimum item count
     * @return this mutable schema
     */
    public Schema minItems(Integer minItems) {
        this.minItems = new Node().value(BigInteger.valueOf(minItems));
        return this;
    }

    /**
     * Sets {@code minItems} without narrowing its integer range.
     *
     * @param minItems exact minimum item count
     * @return this mutable schema
     */
    public Schema minItems(BigInteger minItems) {
        this.minItems = new Node().value(minItems);
        return this;
    }

    /**
     * Sets {@code maxItems} from a Java integer.
     *
     * @param maxItems maximum item count
     * @return this mutable schema
     */
    public Schema maxItems(Integer maxItems) {
        this.maxItems = new Node().value(BigInteger.valueOf(maxItems));
        return this;
    }

    /**
     * Sets {@code maxItems} without narrowing its integer range.
     *
     * @param maxItems exact maximum item count
     * @return this mutable schema
     */
    public Schema maxItems(BigInteger maxItems) {
        this.maxItems = new Node().value(maxItems);
        return this;
    }

    /**
     * Sets whether list items must be unique.
     *
     * @param uniqueItems uniqueness requirement
     * @return this mutable schema
     */
    public Schema uniqueItems(Boolean uniqueItems) {
        this.uniqueItems = new Node().value(uniqueItems);
        return this;
    }

    /**
     * Sets {@code minFields} from a Java integer.
     *
     * @param minFields minimum object-field count
     * @return this mutable schema
     */
    public Schema minFields(Integer minFields) {
        this.minFields = new Node().value(BigInteger.valueOf(minFields));
        return this;
    }

    /**
     * Sets {@code minFields} without narrowing its integer range.
     *
     * @param minFields exact minimum object-field count
     * @return this mutable schema
     */
    public Schema minFields(BigInteger minFields) {
        this.minFields = new Node().value(minFields);
        return this;
    }

    /**
     * Sets {@code maxFields} from a Java integer.
     *
     * @param maxFields maximum object-field count
     * @return this mutable schema
     */
    public Schema maxFields(Integer maxFields) {
        this.maxFields = new Node().value(BigInteger.valueOf(maxFields));
        return this;
    }

    /**
     * Sets {@code maxFields} without narrowing its integer range.
     *
     * @param maxFields exact maximum object-field count
     * @return this mutable schema
     */
    public Schema maxFields(BigInteger maxFields) {
        this.maxFields = new Node().value(maxFields);
        return this;
    }

    /**
     * Creates a subtype-preserving copy while delegating Node-edge ownership to
     * the caller. The package-private hook lets the iterative Node copier keep a
     * single traversal stack across Node and Schema boundaries.
     */
    final Schema copyWithNodeMapper(Function<Node, Node> nodeMapper) {
        Objects.requireNonNull(nodeMapper, "nodeMapper must not be null");
        Schema cloned = shallowClone();
        cloned.required = mapNullable(required, nodeMapper);
        cloned.minLength = mapNullable(minLength, nodeMapper);
        cloned.maxLength = mapNullable(maxLength, nodeMapper);
        cloned.minimum = mapNullable(minimum, nodeMapper);
        cloned.maximum = mapNullable(maximum, nodeMapper);
        cloned.exclusiveMinimum = mapNullable(exclusiveMinimum, nodeMapper);
        cloned.exclusiveMaximum = mapNullable(exclusiveMaximum, nodeMapper);
        cloned.multipleOf = mapNullable(multipleOf, nodeMapper);
        cloned.minItems = mapNullable(minItems, nodeMapper);
        cloned.maxItems = mapNullable(maxItems, nodeMapper);
        cloned.uniqueItems = mapNullable(uniqueItems, nodeMapper);
        cloned.minFields = mapNullable(minFields, nodeMapper);
        cloned.maxFields = mapNullable(maxFields, nodeMapper);
        cloned.enumValues = enumValues != null
                ? enumValues.stream()
                        .map(value -> nodeMapper.apply(Objects.requireNonNull(
                                value, "Schema enum value must not be null")))
                        .collect(Collectors.toList())
                : null;
        return cloned;
    }

    private static Node mapNullable(
            Node value,
            Function<Node, Node> nodeMapper) {
        return value != null ? nodeMapper.apply(value) : null;
    }

    private Schema shallowClone() {
        try {
            return (Schema) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Schema must be cloneable", e);
        }
    }

    /** Returns a deep mutable copy of every keyword node and enum value. */
    @Override
    public Schema clone() {
        return copyWithNodeMapper(Node::clone);
    }

    @Override
    public String toString() {
        return "Schema{" +
                "blueId=" + blueId +
                ", required=" + getRequiredValue() +
                ", minLength=" + getMinLengthExact() +
                ", maxLength=" + getMaxLengthExact() +
                ", minimum=" + getMinimumValue() +
                ", maximum=" + getMaximumValue() +
                ", exclusiveMinimum=" + getExclusiveMinimumValue() +
                ", exclusiveMaximum=" + getExclusiveMaximumValue() +
                ", multipleOf=" + getMultipleOfValue() +
                ", minItems=" + getMinItemsExact() +
                ", maxItems=" + getMaxItemsExact() +
                ", uniqueItems=" + getUniqueItemsValue() +
                ", minFields=" + getMinFieldsExact() +
                ", maxFields=" + getMaxFieldsExact() +
                ", enum=" + enumValues +
                '}';
    }

}
