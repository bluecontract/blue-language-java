package blue.language.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Project JSON Pointer helper.
 *
 * <p>The language historically uses {@code "/"} as the root pointer. Within
 * non-root pointers this class follows RFC 6901 escaping: {@code ~1} decodes to
 * {@code /} and {@code ~0} decodes to {@code ~}.</p>
 */
public final class JsonPointer {

    /** Project representation of the root pointer. */
    public static final String ROOT = "/";
    /** RFC 6902 array-append path segment. */
    public static final String ARRAY_APPEND = "-";

    private JsonPointer() {
    }

    /**
     * Normalizes a pointer to the project's slash-prefixed root convention.
     *
     * @param pointer pointer to normalize, or {@code null}
     * @return normalized slash-prefixed pointer
     */
    public static String normalize(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return ROOT;
        }
        return pointer.charAt(0) == '/'
                ? pointer
                : ROOT + pointer;
    }

    /**
     * Returns the canonical escaped form of a pointer.
     *
     * @param pointer pointer to canonicalize
     * @return canonical pointer using RFC 6901 escaping
     */
    public static String canonicalize(String pointer) {
        return toPointer(split(pointer));
    }

    /**
     * Decodes a pointer into its ordered path segments.
     *
     * @param pointer pointer to split
     * @return mutable list of decoded segments
     */
    public static List<String> split(String pointer) {
        String normalized = normalize(pointer);
        if (ROOT.equals(normalized)) {
            return Collections.emptyList();
        }
        String raw = normalized.substring(1);
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = raw.split("/", -1);
        List<String> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            segments.add(unescape(part));
        }
        return segments;
    }

    /**
     * Encodes decoded path segments as a canonical pointer.
     *
     * @param segments decoded path segments
     * @return canonical pointer, or {@code "/"} for no segments
     */
    public static String toPointer(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return ROOT;
        }
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            builder.append('/').append(escape(segment));
        }
        return builder.toString();
    }

    /**
     * Appends one decoded child segment to a parent pointer.
     *
     * @param parent parent pointer
     * @param childSegment decoded child segment
     * @return canonical pointer to the child
     */
    public static String append(String parent, String childSegment) {
        List<String> segments = new ArrayList<>(split(parent));
        segments.add(childSegment);
        return toPointer(segments);
    }

    /**
     * Escapes one decoded path segment according to RFC 6901.
     *
     * @param segment decoded segment
     * @return escaped segment
     */
    public static String escape(String segment) {
        if (segment == null) {
            return "";
        }
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Decodes RFC 6901 escape sequences in one path segment.
     *
     * @param segment escaped segment
     * @return decoded segment
     */
    public static String unescape(String segment) {
        if (segment == null || segment.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '~' && i + 1 < segment.length()) {
                char next = segment.charAt(i + 1);
                if (next == '0') {
                    builder.append('~');
                    i++;
                    continue;
                }
                if (next == '1') {
                    builder.append('/');
                    i++;
                    continue;
                }
            }
            builder.append(c);
        }
        return builder.toString();
    }

    /**
     * Tests whether a segment denotes an array index or append position.
     *
     * @param segment decoded pointer segment
     * @return {@code true} for decimal digits or {@code "-"}
     */
    public static boolean isArrayIndexSegment(String segment) {
        return ARRAY_APPEND.equals(segment)
                || (!segment.isEmpty()
                && segment.chars().allMatch(Character::isDigit));
    }
}
