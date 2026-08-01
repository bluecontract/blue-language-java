package blue.language.model.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Model-owned RFC 6901 path operations using Blue's {@code "/"} root. */
public class JsonPointer {

    /** Blue's canonical pointer spelling for the selected root node. */
    public static final String ROOT = "/";
    /** RFC 6902 array-append path segment. */
    public static final String ARRAY_APPEND = "-";

    /** Allows a compatibility facade to inherit the pure path operations. */
    protected JsonPointer() {
    }

    /**
     * Normalizes a pointer to Blue's rooted spelling.
     *
     * @param pointer authored pointer, or {@code null}
     * @return {@link #ROOT} for a null or empty input, otherwise the input
     *         with a leading slash
     */
    public static String normalize(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return ROOT;
        }
        return pointer.charAt(0) == '/' ? pointer : ROOT + pointer;
    }

    /**
     * Canonicalizes a pointer by decoding and re-encoding every segment.
     *
     * @param pointer authored pointer, or {@code null}
     * @return canonical rooted pointer spelling
     */
    public static String canonicalize(String pointer) {
        return toPointer(split(pointer));
    }

    /**
     * Splits a pointer into decoded RFC 6901 path segments.
     *
     * @param pointer authored pointer, or {@code null}
     * @return decoded segments in path order; root yields an empty list
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
     * Encodes decoded path segments as a rooted RFC 6901 pointer.
     *
     * @param segments decoded segments, or {@code null} for root
     * @return encoded rooted pointer, with an empty list mapped to
     *         {@link #ROOT}
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
     * @param parent parent pointer, or {@code null} for root
     * @param childSegment decoded child segment; {@code null} denotes an empty
     *        segment
     * @return canonical pointer to the appended child
     */
    public static String append(String parent, String childSegment) {
        List<String> segments = new ArrayList<>(split(parent));
        segments.add(childSegment);
        return toPointer(segments);
    }

    /**
     * Escapes one decoded segment using RFC 6901 substitutions.
     *
     * @param segment decoded segment, or {@code null}
     * @return escaped segment, or an empty string for {@code null}
     */
    public static String escape(String segment) {
        if (segment == null) {
            return "";
        }
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Decodes the recognized RFC 6901 substitutions in one segment.
     * Unrecognized tilde sequences remain literal.
     *
     * @param segment encoded segment, or {@code null}
     * @return decoded segment, or an empty string for {@code null}
     */
    public static String unescape(String segment) {
        if (segment == null || segment.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(segment.length());
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (character == '~' && index + 1 < segment.length()) {
                char next = segment.charAt(index + 1);
                if (next == '0') {
                    builder.append('~');
                    index++;
                    continue;
                }
                if (next == '1') {
                    builder.append('/');
                    index++;
                    continue;
                }
            }
            builder.append(character);
        }
        return builder.toString();
    }

    /**
     * Reports whether a segment denotes array append or a decimal index.
     *
     * @param segment decoded path segment, or {@code null}
     * @return {@code true} for {@link #ARRAY_APPEND} or a non-empty sequence
     *         of decimal digit characters
     */
    public static boolean isArrayIndexSegment(String segment) {
        return ARRAY_APPEND.equals(segment)
                || (segment != null && !segment.isEmpty()
                && segment.chars().allMatch(Character::isDigit));
    }
}
