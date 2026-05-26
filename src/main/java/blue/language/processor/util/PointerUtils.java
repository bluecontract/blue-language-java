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

    public static String abs(String scopePath, String pointer) {
        return resolvePointer(scopePath, pointer);
    }

    public static String relativize(String scopePath, String absolutePath) {
        return relativizePointer(scopePath, absolutePath);
    }

    public static boolean descendantOrEqual(String path, String ancestor) {
        List<String> pathSegments = JsonPointer.split(normalizePointer(path));
        List<String> ancestorSegments = JsonPointer.split(normalizePointer(ancestor));
        if (ancestorSegments.size() > pathSegments.size()) {
            return false;
        }
        for (int i = 0; i < ancestorSegments.size(); i++) {
            if (!ancestorSegments.get(i).equals(pathSegments.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean strictlyInside(String path, String ancestor) {
        return !normalizePointer(path).equals(normalizePointer(ancestor))
                && descendantOrEqual(path, ancestor);
    }

    public static String assertValidRuntimePointer(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            throw new IllegalArgumentException("Runtime pointer must not be empty");
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("Runtime pointer must be absolute: " + pointer);
        }
        if (pointer.length() > 1 && pointer.endsWith("/")) {
            throw new IllegalArgumentException("Runtime pointer must not have a trailing slash: " + pointer);
        }
        if ("/".equals(pointer)) {
            return "/";
        }
        String[] parts = pointer.substring(1).split("/", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Runtime pointer must not contain empty segments: " + pointer);
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) == '~') {
                    if (i + 1 >= part.length()) {
                        throw new IllegalArgumentException("Runtime pointer contains bad '~' escape: " + pointer);
                    }
                    char next = part.charAt(i + 1);
                    if (next != '0' && next != '1') {
                        throw new IllegalArgumentException("Runtime pointer contains bad '~' escape: " + pointer);
                    }
                    i++;
                }
            }
        }
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
