package blue.language.model.wire;

/** Model-owned wire keys for the closed core schema vocabulary. */
public class SchemaPropertyConstants {

    public static final String KEY_REQUIRED = "required";
    public static final String KEY_MIN_LENGTH = "minLength";
    public static final String KEY_MAX_LENGTH = "maxLength";
    public static final String KEY_MINIMUM = "minimum";
    public static final String KEY_MAXIMUM = "maximum";
    public static final String KEY_EXCLUSIVE_MINIMUM = "exclusiveMinimum";
    public static final String KEY_EXCLUSIVE_MAXIMUM = "exclusiveMaximum";
    public static final String KEY_MULTIPLE_OF = "multipleOf";
    public static final String KEY_MIN_ITEMS = "minItems";
    public static final String KEY_MAX_ITEMS = "maxItems";
    public static final String KEY_UNIQUE_ITEMS = "uniqueItems";
    public static final String KEY_MIN_FIELDS = "minFields";
    public static final String KEY_MAX_FIELDS = "maxFields";
    public static final String KEY_ENUM = "enum";

    /** Allows a compatibility facade to inherit the canonical constants. */
    protected SchemaPropertyConstants() {
    }
}
