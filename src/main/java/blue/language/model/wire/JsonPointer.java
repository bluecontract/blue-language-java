package blue.language.model.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Model-owned RFC 6901 path operations using Blue's {@code "/"} root. */
public class JsonPointer {

    public static final String ROOT = "/";
    public static final String ARRAY_APPEND = "-";

    /** Allows a compatibility facade to inherit the pure path operations. */
    protected JsonPointer() {
    }

    public static String normalize(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return ROOT;
        }
        return pointer.charAt(0) == '/' ? pointer : ROOT + pointer;
    }

    public static String canonicalize(String pointer) {
        return toPointer(split(pointer));
    }

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

    public static String append(String parent, String childSegment) {
        List<String> segments = new ArrayList<>(split(parent));
        segments.add(childSegment);
        return toPointer(segments);
    }

    public static String escape(String segment) {
        if (segment == null) {
            return "";
        }
        return segment.replace("~", "~0").replace("/", "~1");
    }

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

    public static boolean isArrayIndexSegment(String segment) {
        return ARRAY_APPEND.equals(segment)
                || (segment != null && !segment.isEmpty()
                && segment.chars().allMatch(Character::isDigit));
    }
}
