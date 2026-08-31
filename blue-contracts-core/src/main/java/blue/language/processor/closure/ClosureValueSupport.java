package blue.language.processor.closure;

import blue.language.model.value.BlueNumbers;

import java.text.Normalizer;
import java.util.Objects;

/** Shared admission rules for the immutable closure evidence values. */
final class ClosureValueSupport {

    static final long MAX_SAFE_INTEGER =
            BlueNumbers.MAX_INTEROPERABLE_INTEGER.longValueExact();

    private ClosureValueSupport() {
    }

    static long requireSafeInteger(long value, String field) {
        if (value < 0L || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    field + " must be a non-negative safe integer");
        }
        return value;
    }

    static long requirePositiveSafeInteger(long value, String field) {
        requireSafeInteger(value, field);
        if (value == 0L) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    static long requireManagedEpochCursor(long value, String field) {
        if (value < -1L || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    field + " must be -1 or a non-negative safe integer");
        }
        return value;
    }

    static String requirePortableText(String value, String field) {
        String text = Objects.requireNonNull(value, field);
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(field + " must be NFC-normalized");
        }
        for (int index = 0; index < text.length();) {
            int codePoint = scalarAt(text, index, field);
            if (codePoint == 0) {
                throw new IllegalArgumentException(field + " must not contain U+0000");
            }
            index += Character.charCount(codePoint);
        }
        return text;
    }

    static String requireNonEmptyText(String value, String field) {
        String text = requirePortableText(value, field);
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return text;
    }

    static String requireSha256Identity(String value, String field) {
        String identity = Objects.requireNonNull(value, field);
        if (!identity.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase sha256 identity");
        }
        return identity;
    }

    static String requireBlueId(String value, String field) {
        return requireNonEmptyText(value, field);
    }

    static String requireAbsolutePointer(String value, String field) {
        String pointer = requireNonEmptyText(value, field);
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException(field + " must be absolute");
        }
        for (int index = 1; index < pointer.length(); index++) {
            if (pointer.charAt(index) == '~') {
                if (index + 1 >= pointer.length()
                        || (pointer.charAt(index + 1) != '0'
                        && pointer.charAt(index + 1) != '1')) {
                    throw new IllegalArgumentException(
                            field + " contains an invalid RFC 6901 escape");
                }
                index++;
            }
        }
        return pointer;
    }

    static int comparePortableText(String left, String right) {
        requirePortableText(left, "left ordering token");
        requirePortableText(right, "right ordering token");
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = scalarAt(left, leftIndex, "left ordering token");
            int rightCodePoint = scalarAt(right, rightIndex, "right ordering token");
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        if (leftIndex < left.length()) {
            return 1;
        }
        if (rightIndex < right.length()) {
            return -1;
        }
        return 0;
    }

    private static int scalarAt(String value, int index, String field) {
        char first = value.charAt(index);
        if (Character.isHighSurrogate(first)) {
            if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                throw new IllegalArgumentException(
                        field + " contains an unpaired Unicode surrogate");
            }
            return Character.toCodePoint(first, value.charAt(index + 1));
        }
        if (Character.isLowSurrogate(first)) {
            throw new IllegalArgumentException(
                    field + " contains an unpaired Unicode surrogate");
        }
        return first;
    }
}
