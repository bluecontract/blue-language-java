package blue.language.processor.util;

import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility helpers for normalising and composing JSON Pointer / scope strings.
 */
public final class PointerUtils {

    private PointerUtils() {
    }

    public static String normalizeScope(String scopePath) {
        return JsonPointer.canonicalize(scopePath);
    }

    public static String normalizePointer(String pointer) {
        return JsonPointer.canonicalize(pointer);
    }

    public static String canonicalizePointer(String pointer) {
        return JsonPointer.canonicalize(pointer);
    }

    public static List<String> splitPointer(String pointer) {
        return JsonPointer.split(pointer);
    }

    public static String toPointer(List<String> segments) {
        return JsonPointer.toPointer(segments);
    }

    public static String appendPointer(String parent, String childSegment) {
        return JsonPointer.append(parent, childSegment);
    }

    public static String escapeSegment(String segment) {
        return JsonPointer.escape(segment);
    }

    public static String stripSlashes(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String stripped = value.trim();
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    public static String joinRelativePointers(String base, String tail) {
        List<String> segments = new ArrayList<>(JsonPointer.split(base));
        segments.addAll(JsonPointer.split(tail));
        return JsonPointer.toPointer(segments);
    }

    public static String resolvePointer(String scopePath, String relativePointer) {
        String normalizedScope = normalizeScope(scopePath);
        String normalizedPointer = normalizePointer(relativePointer);
        if ("/".equals(normalizedScope)) {
            return normalizedPointer;
        }
        if ("/".equals(normalizedPointer)) {
            return normalizedScope;
        }
        if (normalizedPointer.length() == 1) { // "/"
            return normalizedScope;
        }
        List<String> segments = new ArrayList<>(JsonPointer.split(normalizedScope));
        segments.addAll(JsonPointer.split(normalizedPointer));
        return JsonPointer.toPointer(segments);
    }

    public static String relativizePointer(String scopePath, String absolutePath) {
        List<String> scopeSegments = JsonPointer.split(normalizeScope(scopePath));
        List<String> absoluteSegments = JsonPointer.split(normalizePointer(absolutePath));
        if (scopeSegments.isEmpty()) {
            return JsonPointer.toPointer(absoluteSegments);
        }
        if (absoluteSegments.size() < scopeSegments.size()) {
            return JsonPointer.toPointer(absoluteSegments);
        }
        for (int i = 0; i < scopeSegments.size(); i++) {
            if (!scopeSegments.get(i).equals(absoluteSegments.get(i))) {
                return JsonPointer.toPointer(absoluteSegments);
            }
        }
        if (absoluteSegments.size() == scopeSegments.size()) {
            return "/";
        }
        return JsonPointer.toPointer(absoluteSegments.subList(scopeSegments.size(), absoluteSegments.size()));
    }
}
