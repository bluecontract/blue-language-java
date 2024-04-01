package blue.language.model;

import java.math.BigDecimal;
import java.util.List;

public class Constraints {

    private Boolean required;
    private Boolean allowMultiple;
    private Integer minLength;
    private Integer maxLength;
    private String pattern;
    private BigDecimal minimum;
    private BigDecimal maximum;
    private BigDecimal exclusiveMinimum;
    private BigDecimal exclusiveMaximum;
    private BigDecimal multipleOf;
    private Integer minItems;
    private Integer maxItems;
    private Boolean uniqueItems;
    private List<Node> options;

    public Boolean getRequired() {
        return required;
    }

    public Boolean getAllowMultiple() {
        return allowMultiple;
    }

    public Integer getMinLength() {
        return minLength;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public String getPattern() {
        return pattern;
    }

    public BigDecimal getMinimum() {
        return minimum;
    }

    public BigDecimal getMaximum() {
        return maximum;
    }

    public BigDecimal getExclusiveMinimum() {
        return exclusiveMinimum;
    }

    public BigDecimal getExclusiveMaximum() {
        return exclusiveMaximum;
    }

    public BigDecimal getMultipleOf() {
        return multipleOf;
    }

    public Integer getMinItems() {
        return minItems;
    }

    public Integer getMaxItems() {
        return maxItems;
    }

    public Boolean getUniqueItems() {
        return uniqueItems;
    }

    public List<Node> getOptions() {
        return options;
    }

    public Constraints required(Boolean required) {
        this.required = required;
        return this;
    }

    public Constraints allowMultiple(Boolean allowMultiple) {
        this.allowMultiple = allowMultiple;
        return this;
    }

    public Constraints minLength(Integer minLength) {
        this.minLength = minLength;
        return this;
    }

    public Constraints maxLength(Integer maxLength) {
        this.maxLength = maxLength;
        return this;
    }

    public Constraints pattern(String pattern) {
        this.pattern = pattern;
        return this;
    }

    public Constraints minimum(BigDecimal minimum) {
        this.minimum = minimum;
        return this;
    }

    public Constraints maximum(BigDecimal maximum) {
        this.maximum = maximum;
        return this;
    }

    public Constraints exclusiveMinimum(BigDecimal exclusiveMinimum) {
        this.exclusiveMinimum = exclusiveMinimum;
        return this;
    }

    public Constraints exclusiveMaximum(BigDecimal exclusiveMaximum) {
        this.exclusiveMaximum = exclusiveMaximum;
        return this;
    }

    public Constraints multipleOf(BigDecimal multipleOf) {
        this.multipleOf = multipleOf;
        return this;
    }

    public Constraints minItems(Integer minItems) {
        this.minItems = minItems;
        return this;
    }

    public Constraints maxItems(Integer maxItems) {
        this.maxItems = maxItems;
        return this;
    }

    public Constraints uniqueItems(Boolean uniqueItems) {
        this.uniqueItems = uniqueItems;
        return this;
    }

    public Constraints options(List<Node> options) {
        this.options = options;
        return this;
    }

    @Override
    public String toString() {
        return "Constraints{" +
                "required=" + required +
                ", allowMultiple=" + allowMultiple +
                ", minLength=" + minLength +
                ", maxLength=" + maxLength +
                ", pattern='" + pattern + '\'' +
                ", minimum=" + minimum +
                ", maximum=" + maximum +
                ", exclusiveMinimum=" + exclusiveMinimum +
                ", exclusiveMaximum=" + exclusiveMaximum +
                ", multipleOf=" + multipleOf +
                ", minItems=" + minItems +
                ", maxItems=" + maxItems +
                ", uniqueItems=" + uniqueItems +
                ", options=" + options +
                '}';
    }
}