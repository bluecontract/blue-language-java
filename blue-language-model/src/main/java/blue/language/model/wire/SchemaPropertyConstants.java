package blue.language.model.wire;

/** Model-owned wire keys for the closed core schema vocabulary. */
public class SchemaPropertyConstants {

    /** Schema key declaring whether a value or field is required. */
    public static final String KEY_REQUIRED = "required";
    /** Schema key declaring the inclusive minimum text length. */
    public static final String KEY_MIN_LENGTH = "minLength";
    /** Schema key declaring the inclusive maximum text length. */
    public static final String KEY_MAX_LENGTH = "maxLength";
    /** Schema key declaring the inclusive numeric minimum. */
    public static final String KEY_MINIMUM = "minimum";
    /** Schema key declaring the inclusive numeric maximum. */
    public static final String KEY_MAXIMUM = "maximum";
    /** Schema key declaring the exclusive numeric minimum. */
    public static final String KEY_EXCLUSIVE_MINIMUM = "exclusiveMinimum";
    /** Schema key declaring the exclusive numeric maximum. */
    public static final String KEY_EXCLUSIVE_MAXIMUM = "exclusiveMaximum";
    /** Schema key declaring the required numeric divisor. */
    public static final String KEY_MULTIPLE_OF = "multipleOf";
    /** Schema key declaring the inclusive minimum list size. */
    public static final String KEY_MIN_ITEMS = "minItems";
    /** Schema key declaring the inclusive maximum list size. */
    public static final String KEY_MAX_ITEMS = "maxItems";
    /** Schema key requiring pairwise-distinct list items. */
    public static final String KEY_UNIQUE_ITEMS = "uniqueItems";
    /** Schema key declaring the inclusive minimum object field count. */
    public static final String KEY_MIN_FIELDS = "minFields";
    /** Schema key declaring the inclusive maximum object field count. */
    public static final String KEY_MAX_FIELDS = "maxFields";
    /** Schema key declaring the closed set of allowed values. */
    public static final String KEY_ENUM = "enum";

    /** Allows a compatibility facade to inherit the canonical constants. */
    protected SchemaPropertyConstants() {
    }
}
