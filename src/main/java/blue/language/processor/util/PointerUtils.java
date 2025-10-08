package blue.language.processor.util;

/**
 * Utility helpers for normalising and composing JSON Pointer / scope strings.
 */
public final class PointerUtils {

    private PointerUtils() {
    }

    public static String normalizeScope(String scopePath) {
        if (scopePath == null || scopePath.isEmpty()) {
            return "/";
        }
        String result = scopePath;
        if (result.charAt(0) != '/') {
            result = "/" + result;
        }
        return result;
    }

    public static String normalizePointer(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return "/";
        }
        String result = pointer;
        if (result.charAt(0) != '/') {
            result = "/" + result;
        }
        return result;
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
        String basePart = stripSlashes(base);
        String tailPart = stripSlashes(tail);
        if (basePart.isEmpty() && tailPart.isEmpty()) {
            return "/";
        }
        if (basePart.isEmpty()) {
            return "/" + tailPart;
        }
        if (tailPart.isEmpty()) {
            return "/" + basePart;
        }
        return "/" + basePart + "/" + tailPart;
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
        return normalizedScope + normalizedPointer;
    }

    public static String relativizePointer(String scopePath, String absolutePath) {
        String normalizedScope = normalizeScope(scopePath);
        String normalizedAbsolute = normalizePointer(absolutePath);
        if ("/".equals(normalizedScope)) {
            return normalizedAbsolute;
        }
        if (!normalizedAbsolute.startsWith(normalizedScope)) {
            return normalizedAbsolute;
        }
        if (normalizedAbsolute.length() == normalizedScope.length()) {
            return "/";
        }
        String remainder = normalizedAbsolute.substring(normalizedScope.length());
        if (remainder.isEmpty()) {
            return "/";
        }
        return remainder.startsWith("/") ? remainder : "/" + remainder;
    }
}
