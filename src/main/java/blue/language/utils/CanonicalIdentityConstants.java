package blue.language.utils;

/**
 * Wire tokens used by the recursive canonical identity representation of a
 * Blue list.
 *
 * <p>These values are part of the BlueId protocol. Changing any value changes
 * the identity of every list, so callers should refer to the named constants
 * instead of repeating their serialized spelling.</p>
 */
public final class CanonicalIdentityConstants {

    /** Field wrapping the seed value for an empty canonical list. */
    public static final String LIST_SEED_KEY = "$list";

    /** Seed value representing an empty canonical list. */
    public static final String LIST_SEED_VALUE = "empty";

    /** Field wrapping one recursive canonical list-cons record. */
    public static final String LIST_CONS_KEY = "$listCons";

    /** Field holding the current element reference in a list-cons record. */
    public static final String LIST_CONS_ELEMENT_KEY = "elem";

    /** Field holding the preceding accumulator reference in a list-cons record. */
    public static final String LIST_CONS_PREVIOUS_KEY = "prev";

    private CanonicalIdentityConstants() {
    }
}
