package blue.language.utils;

import blue.language.model.TypeBlueId;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Syntax and canonicality checks for plain and cyclic-member BlueIds.
 *
 * <p>A plain BlueId is the canonical Base58 encoding of exactly 32 digest
 * bytes. Cyclic members append a zero-based {@code #index}; temporary
 * {@code this} placeholders are accepted only by explicitly cyclic APIs.</p>
 */
public class BlueIds {

    /** Placeholder for the current document in a single-document cycle. */
    public static final String THIS_PLACEHOLDER = "this";
    /** Separator between a cyclic-set master BlueId and its member index. */
    public static final String CYCLIC_MEMBER_SEPARATOR = "#";
    /** Prefix for an indexed member placeholder in a cyclic document set. */
    public static final String THIS_MEMBER_PREFIX =
            THIS_PLACEHOLDER + CYCLIC_MEMBER_SEPARATOR;

    private static final Pattern PLAIN_BLUE_ID_PATTERN = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]+$");
    private static final Pattern CYCLIC_MEMBER_PATTERN = Pattern.compile(
            "^([1-9A-HJ-NP-Za-km-z]+)"
                    + Pattern.quote(CYCLIC_MEMBER_SEPARATOR)
                    + "(0|[1-9]\\d*)$");
    private static final Pattern THIS_MEMBER_PATTERN = Pattern.compile(
            "^" + THIS_MEMBER_PREFIX + "(0|[1-9]\\d*)$");
    private static final Pattern ZERO_PLACEHOLDER_PATTERN = Pattern.compile("^0{44}$");

    /** Creates a compatibility facade over static identity checks. */
    public BlueIds() {
    }

    /**
     * Tests whether a value is a canonical plain or cyclic-member BlueId.
     *
     * @param value candidate identity
     * @return whether the value is a potential BlueId
     */
    public static boolean isPotentialBlueId(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        try {
            requireBlueIdOrCyclicMember(value, Properties.OBJECT_BLUE_ID);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validates and returns a canonical plain BlueId.
     *
     * @param value candidate identity
     * @param path diagnostic location included in validation failures
     * @return validated identity
     * @throws IllegalArgumentException when the identity is not canonical
     */
    public static String requirePlainBlueId(String value, String path) {
        if (value == null || value.isEmpty() || !PLAIN_BLUE_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Expected canonical Base58 SHA-256 BlueId at " + path + ".");
        }
        byte[] decoded;
        try {
            decoded = Base58.decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Expected canonical Base58 SHA-256 BlueId at " + path + ".", e);
        }
        if (decoded.length != 32 || !Base58.encode(decoded).equals(value)) {
            throw new IllegalArgumentException("Expected canonical Base58 SHA-256 BlueId at " + path + ".");
        }
        return value;
    }

    /**
     * Validates a plain BlueId or canonical cyclic member.
     *
     * @param value candidate identity
     * @param path diagnostic location
     * @return validated identity
     * @throws IllegalArgumentException when the identity is not canonical
     */
    public static String requireBlueIdOrCyclicMember(String value, String path) {
        if (value == null) {
            throw new IllegalArgumentException("Expected BlueId at " + path + ".");
        }
        java.util.regex.Matcher cyclic = CYCLIC_MEMBER_PATTERN.matcher(value);
        if (cyclic.matches()) {
            requirePlainBlueId(cyclic.group(1), path);
            return value;
        }
        if (hasCyclicMemberSeparator(value)) {
            throw new IllegalArgumentException("Invalid cyclic BlueId member syntax at " + path + ".");
        }
        return requirePlainBlueId(value, path);
    }

    /**
     * Rejects invocation-local placeholders at ordinary API boundaries.
     *
     * @param value candidate identity
     * @param path diagnostic location
     * @return unchanged value
     * @throws IllegalArgumentException when the value is a {@code this}
     *                                  placeholder
     */
    public static String requireNoThisPlaceholderOutsideCyclicApi(String value, String path) {
        if (value != null
                && (THIS_PLACEHOLDER.equals(value)
                || THIS_MEMBER_PATTERN.matcher(value).matches())) {
            throw new IllegalArgumentException("\"this\" BlueId placeholders are valid only inside cyclic BlueId calculation APIs. Path: " + path);
        }
        return value;
    }

    /**
     * Tests for an internal cyclic-calculation placeholder.
     *
     * @param value candidate identity
     * @return whether the value is a calculation placeholder
     */
    public static boolean isCyclicCalculationPlaceholder(String value) {
        return value != null && (THIS_PLACEHOLDER.equals(value)
                || THIS_MEMBER_PATTERN.matcher(value).matches()
                || ZERO_PLACEHOLDER_PATTERN.matcher(value).matches());
    }

    /**
     * Formats an indexed placeholder for a cyclic document-set member.
     *
     * @param index non-negative member index
     * @return canonical {@code this#index} placeholder
     * @throws IllegalArgumentException when {@code index} is negative
     */
    public static String indexedThisPlaceholder(int index) {
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Cyclic placeholder index must be non-negative.");
        }
        return THIS_MEMBER_PREFIX + index;
    }

    /**
     * Tests whether a value contains the cyclic-member separator.
     *
     * <p>This is a structural check only; callers that accept external input
     * must still use {@link #requireBlueIdOrCyclicMember(String, String)}.</p>
     *
     * @param value candidate identity
     * @return whether the separator occurs in the value
     */
    public static boolean hasCyclicMemberSeparator(String value) {
        return cyclicMemberSeparatorIndex(value) >= 0;
    }

    /**
     * Locates the first cyclic-member separator without validating the value.
     *
     * @param value candidate identity
     * @return zero-based separator position, or {@code -1}
     */
    public static int cyclicMemberSeparatorIndex(String value) {
        return value == null
                ? -1
                : value.indexOf(CYCLIC_MEMBER_SEPARATOR);
    }

    /**
     * Removes an optional cyclic-member suffix.
     *
     * @param blueId plain or member-qualified identity
     * @return the master identity, or {@code null} when {@code blueId} is null
     */
    public static String cyclicSetMasterBlueId(String blueId) {
        int separator = cyclicMemberSeparatorIndex(blueId);
        return separator < 0 ? blueId : blueId.substring(0, separator);
    }

    /**
     * Formats a member identity from its master BlueId and ordered index.
     *
     * <p>The formatter deliberately does not validate either component so
     * internal calculators retain their existing validation order.</p>
     *
     * @param masterBlueId cyclic-set master identity
     * @param index member index
     * @return {@code masterBlueId#index}
     */
    public static String indexedCyclicMemberBlueId(
            String masterBlueId,
            int index) {
        return masterBlueId + CYCLIC_MEMBER_SEPARATOR + index;
    }

    /**
     * Resolves the preferred BlueId declared for a Java type.
     *
     * @param clazz Java class to inspect
     * @return preferred identity, if declared
     */
    public static Optional<String> getBlueId(Class<?> clazz) {
        return Optional.ofNullable(BlueIdResolver.resolveBlueId(clazz));
    }

}
