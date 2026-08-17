package blue.contracts.closure;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Objects;
public final class CanonicalOrders {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private CanonicalOrders() {}
    public static long requireSafeInteger(long value,String field){if(value<0||value>MAX_SAFE_INTEGER)throw new IllegalArgumentException(field);return value;}
    /** Rejects non-NFC or non-scalar text before it enters portable ordering. */
    public static String requireNfc(String value, String field) {
        String text = Objects.requireNonNull(value, field);
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(field + " must be NFC-normalized");
        }
        for (int index = 0; index < text.length();) {
            int codePoint = scalarAt(text, index);
            index += Character.charCount(codePoint);
        }
        return text;
    }
    /** Normalized absolute RFC 6901 Runtime Pointer; Contracts Root is '/'. */
    public static String requireRuntimePointer(String value, String field) {
        String pointer = requireNfc(value, field);
        if (pointer.isEmpty() || pointer.charAt(0) != '/') {
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
    /** Portable text order: NFC Unicode scalar values, never UTF-16 code units. */
    static int compareUnicodeScalars(String left, String right) {
        left = requireNfc(left, "left ordering token");
        right = requireNfc(right, "right ordering token");
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = scalarAt(left, leftIndex);
            int rightCodePoint = scalarAt(right, rightIndex);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        if (leftIndex < left.length()) return 1;
        if (rightIndex < right.length()) return -1;
        return 0;
    }
    private static int scalarAt(String value, int index) {
        char first = value.charAt(index);
        if (Character.isHighSurrogate(first)) {
            if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                throw new IllegalArgumentException("unpaired Unicode surrogate");
            }
            return Character.toCodePoint(first, value.charAt(index + 1));
        }
        if (Character.isLowSurrogate(first)) {
            throw new IllegalArgumentException("unpaired Unicode surrogate");
        }
        return first;
    }
    public static final Comparator<DocumentId> DOCUMENT_ID = new Comparator<DocumentId>() { public int compare(DocumentId a,DocumentId b){return a.compareTo(b);} };
    public static final Comparator<DirectLogicalDelivery> DIRECT_DELIVERY = new Comparator<DirectLogicalDelivery>() { public int compare(DirectLogicalDelivery a,DirectLogicalDelivery b){return a.compareTo(b);} };
    public static final Comparator<WorkOccurrence> WORK_ORDINAL = new Comparator<WorkOccurrence>() { public int compare(WorkOccurrence a,WorkOccurrence b){return a.compareTo(b);} };
}
