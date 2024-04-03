package blue.language.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static blue.language.utils.TypeUtils.*;

public class Constraints {

    private Node required;
    private Node allowMultiple;
    private Node minLength;
    private Node maxLength;
    private Node pattern;
    private Node minimum;
    private Node maximum;
    private Node exclusiveMinimum;
    private Node exclusiveMaximum;
    private Node multipleOf;
    private Node minItems;
    private Node maxItems;
    private Node uniqueItems;
    private List<Node> options;

    public Node getRequired() {
        return required;
    }

    public Node getAllowMultiple() {
        return allowMultiple;
    }

    public Node getMinLength() {
        return minLength;
    }

    public Node getMaxLength() {
        return maxLength;
    }

    public Node getPattern() {
        return pattern;
    }

    public Node getMinimum() {
        return minimum;
    }

    public Node getMaximum() {
        return maximum;
    }

    public Node getExclusiveMinimum() {
        return exclusiveMinimum;
    }

    public Node getExclusiveMaximum() {
        return exclusiveMaximum;
    }

    public Node getMultipleOf() {
        return multipleOf;
    }

    public Node getMinItems() {
        return minItems;
    }

    public Node getMaxItems() {
        return maxItems;
    }

    public Node getUniqueItems() {
        return uniqueItems;
    }

    public List<Node> getOptions() {
        return options;
    }

    public Boolean getRequiredValue() {
        return required == null ? null : getBooleanFromObject(required.getValue());
    }

    public Boolean getAllowMultipleValue() {
        return allowMultiple == null ? null : getBooleanFromObject(allowMultiple.getValue());
    }

    public Integer getMinLengthValue() {
        return minLength == null ? null : getIntegerFromObject(minLength.getValue());
    }

    public Integer getMaxLengthValue() {
        return maxLength == null ? null : getIntegerFromObject(maxLength.getValue());
    }

    public String getPatternValue() {
        return pattern == null ? null : (String) pattern.getValue();
    }

    public BigDecimal getMinimumValue() {
        return minimum == null ? null : getBigDecimalFromObject(minimum.getValue());
    }

    public BigDecimal getMaximumValue() {
        return maximum == null ? null : getBigDecimalFromObject(maximum.getValue());
    }

    public BigDecimal getExclusiveMinimumValue() {
        return exclusiveMinimum == null ? null : getBigDecimalFromObject(exclusiveMinimum.getValue());
    }

    public BigDecimal getExclusiveMaximumValue() {
        return exclusiveMaximum == null ? null : getBigDecimalFromObject(exclusiveMaximum.getValue());
    }

    public BigDecimal getMultipleOfValue() {
        return multipleOf == null ? null : getBigDecimalFromObject(multipleOf.getValue());
    }

    public Integer getMinItemsValue() {
        return minItems == null ? null : getIntegerFromObject(minItems.getValue());
    }

    public Integer getMaxItemsValue() {
        return maxItems == null ? null : getIntegerFromObject(maxItems.getValue());
    }

    public Boolean getUniqueItemsValue() {
        return uniqueItems == null ? null : getBooleanFromObject(uniqueItems.getValue());
    }

    public Constraints required(Node required) {
        this.required = required;
        return this;
    }

    public Constraints allowMultiple(Node allowMultiple) {
        this.allowMultiple = allowMultiple;
        return this;
    }

    public Constraints minLength(Node minLength) {
        this.minLength = minLength;
        return this;
    }

    public Constraints maxLength(Node maxLength) {
        this.maxLength = maxLength;
        return this;
    }

    public Constraints pattern(Node pattern) {
        this.pattern = pattern;
        return this;
    }

    public Constraints minimum(Node minimum) {
        this.minimum = minimum;
        return this;
    }

    public Constraints maximum(Node maximum) {
        this.maximum = maximum;
        return this;
    }

    public Constraints exclusiveMinimum(Node exclusiveMinimum) {
        this.exclusiveMinimum = exclusiveMinimum;
        return this;
    }

    public Constraints exclusiveMaximum(Node exclusiveMaximum) {
        this.exclusiveMaximum = exclusiveMaximum;
        return this;
    }

    public Constraints multipleOf(Node multipleOf) {
        this.multipleOf = multipleOf;
        return this;
    }

    public Constraints minItems(Node minItems) {
        this.minItems = minItems;
        return this;
    }

    public Constraints maxItems(Node maxItems) {
        this.maxItems = maxItems;
        return this;
    }

    public Constraints uniqueItems(Node uniqueItems) {
        this.uniqueItems = uniqueItems;
        return this;
    }

    public Constraints options(List<Node> options) {
        this.options = options;
        return this;
    }

    public Constraints required(Boolean required) {
        this.required = new Node().value(required);
        return this;
    }

    public Constraints allowMultiple(Boolean allowMultiple) {
        this.allowMultiple = new Node().value(allowMultiple);
        return this;
    }

    public Constraints minLength(Integer minLength) {
        this.minLength = new Node().value(BigInteger.valueOf(minLength));
        return this;
    }

    public Constraints maxLength(Integer maxLength) {
        this.maxLength = new Node().value(BigInteger.valueOf(maxLength));
        return this;
    }

    public Constraints pattern(String pattern) {
        this.pattern = new Node().value(pattern);
        return this;
    }

    public Constraints minimum(BigDecimal minimum) {
        this.minimum = new Node().value(minimum);
        return this;
    }

    public Constraints maximum(BigDecimal maximum) {
        this.maximum = new Node().value(maximum);
        return this;
    }

    public Constraints exclusiveMinimum(BigDecimal exclusiveMinimum) {
        this.exclusiveMinimum = new Node().value(exclusiveMinimum);
        return this;
    }

    public Constraints exclusiveMaximum(BigDecimal exclusiveMaximum) {
        this.exclusiveMaximum = new Node().value(exclusiveMaximum);
        return this;
    }

    public Constraints multipleOf(BigDecimal multipleOf) {
        this.multipleOf = new Node().value(multipleOf);
        return this;
    }

    public Constraints minItems(Integer minItems) {
        this.minItems = new Node().value(BigInteger.valueOf(minItems));
        return this;
    }

    public Constraints maxItems(Integer maxItems) {
        this.maxItems = new Node().value(BigInteger.valueOf(maxItems));
        return this;
    }

    public Constraints uniqueItems(Boolean uniqueItems) {
        this.uniqueItems = new Node().value(uniqueItems);
        return this;
    }

    @Override
    public String toString() {
        return "Constraints{" +
                "required=" + getRequiredValue() +
                ", allowMultiple=" + getAllowMultipleValue() +
                ", minLength=" + getMinLengthValue() +
                ", maxLength=" + getMaxLengthValue() +
                ", pattern='" + getPatternValue() + '\'' +
                ", minimum=" + getMinimumValue() +
                ", maximum=" + getMaximumValue() +
                ", exclusiveMinimum=" + getExclusiveMinimumValue() +
                ", exclusiveMaximum=" + getExclusiveMaximumValue() +
                ", multipleOf=" + getMultipleOfValue() +
                ", minItems=" + getMinItemsValue() +
                ", maxItems=" + getMaxItemsValue() +
                ", uniqueItems=" + getUniqueItemsValue() +
                ", options=" + options +
                '}';
    }

}