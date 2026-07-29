package blue.language.utils;

/**
 * Authoritative wire keys for the closed Blue Language core schema
 * vocabulary.
 *
 * <p>These spellings participate in parsing, canonical serialization, path
 * reporting, and BlueId calculation. Consumers should use these constants so
 * every schema boundary refers to the same protocol vocabulary.</p>
 */
public final class SchemaPropertyConstants {

    /** Boolean keyword requiring a semantically present value. */
    public static final String KEY_REQUIRED = "required";

    /** Minimum Unicode code-point length keyword. */
    public static final String KEY_MIN_LENGTH = "minLength";

    /** Maximum Unicode code-point length keyword. */
    public static final String KEY_MAX_LENGTH = "maxLength";

    /** Inclusive numeric lower-bound keyword. */
    public static final String KEY_MINIMUM = "minimum";

    /** Inclusive numeric upper-bound keyword. */
    public static final String KEY_MAXIMUM = "maximum";

    /** Exclusive numeric lower-bound keyword. */
    public static final String KEY_EXCLUSIVE_MINIMUM = "exclusiveMinimum";

    /** Exclusive numeric upper-bound keyword. */
    public static final String KEY_EXCLUSIVE_MAXIMUM = "exclusiveMaximum";

    /** Exact numeric divisibility keyword. */
    public static final String KEY_MULTIPLE_OF = "multipleOf";

    /** Minimum list-item count keyword. */
    public static final String KEY_MIN_ITEMS = "minItems";

    /** Maximum list-item count keyword. */
    public static final String KEY_MAX_ITEMS = "maxItems";

    /** Boolean list uniqueness keyword. */
    public static final String KEY_UNIQUE_ITEMS = "uniqueItems";

    /** Minimum object-field count keyword. */
    public static final String KEY_MIN_FIELDS = "minFields";

    /** Maximum object-field count keyword. */
    public static final String KEY_MAX_FIELDS = "maxFields";

    /** Allowed scalar-value collection keyword. */
    public static final String KEY_ENUM = "enum";

    private SchemaPropertyConstants() {
    }
}
