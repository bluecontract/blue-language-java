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

    private JsonPointer() {
    }

    public static String normalize(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return "/";
        }
        return pointer.charAt(0) == '/' ? pointer : "/" + pointer;
    }

    public static String canonicalize(String pointer) {
        return toPointer(split(pointer));
    }

    public static List<String> split(String pointer) {
        String normalized = normalize(pointer);
        if ("/".equals(normalized)) {
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
            return "/";
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

    public static boolean isArrayIndexSegment(String segment) {
        return "-".equals(segment) || (!segment.isEmpty() && segment.chars().allMatch(Character::isDigit));
    }
}
