package blue.language.processor.util;

import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.ParsedJsonPointer;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility helpers for normalising and composing JSON Pointer / scope strings.
 */
public final class PointerUtils {

    private PointerUtils() {
    }

    /**
     * Canonicalizes a scope path using the runtime's root spelling.
     *
     * @param scopePath scope path
     * @return canonical absolute scope path
     */
    public static String normalizeScope(String scopePath) {
        return JsonPointer.canonicalize(scopePath);
    }

    /**
     * Canonicalizes a JSON Pointer using the runtime's root spelling.
     *
     * @param pointer pointer to canonicalize
     * @return canonical pointer
     */
    public static String normalizePointer(String pointer) {
        return JsonPointer.canonicalize(pointer);
    }

    /**
     * Compatibility alias for {@link #resolvePointer(String, String)}.
     *
     * @param scopePath absolute scope path
     * @param pointer relative pointer
     * @return resolved absolute pointer
     */
    public static String abs(String scopePath, String pointer) {
        return resolvePointer(scopePath, pointer);
    }

    /**
     * Compatibility alias for {@link #relativizePointer(String, String)}.
     *
     * @param scopePath absolute scope path
     * @param absolutePath path to relativize
     * @return relative pointer when inside the scope
     */
    public static String relativize(String scopePath, String absolutePath) {
        return relativizePointer(scopePath, absolutePath);
    }

    /**
     * Tests segment-aware ancestry after canonicalizing both pointer strings.
     *
     * @param path candidate descendant
     * @param ancestor candidate ancestor
     * @return whether {@code path} equals or descends from {@code ancestor}
     */
    public static boolean descendantOrEqual(String path, String ancestor) {
        return descendantOrEqual(ParsedJsonPointer.parse(path), ParsedJsonPointer.parse(ancestor));
    }

    /**
     * Tests segment-aware ancestry for already parsed pointers.
     *
     * @param path candidate descendant
     * @param ancestor candidate ancestor
     * @return whether {@code path} equals or descends from {@code ancestor}
     */
    public static boolean descendantOrEqual(ParsedJsonPointer path, ParsedJsonPointer ancestor) {
        return ancestor.isAncestorOfOrEqual(path);
    }

    /**
     * Returns whether {@code path} is a proper descendant of {@code ancestor}.
     *
     * @param path candidate descendant
     * @param ancestor candidate ancestor
     * @return whether the path is strictly below the ancestor
     */
    public static boolean strictlyInside(String path, String ancestor) {
        return !normalizePointer(path).equals(normalizePointer(ancestor))
                && descendantOrEqual(path, ancestor);
    }

    /**
     * Validates the stricter processor pointer form and returns it canonicalized.
     *
     * <p>Unlike general Blue paths, runtime pointers must be absolute, may not
     * contain empty segments, and may not have a trailing slash.</p>
     *
     * @param pointer runtime pointer
     * @return canonical validated pointer
     * @throws IllegalArgumentException when the pointer violates runtime syntax
     */
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

    /**
     * Delegates canonical pointer normalization to the shared JSON Pointer utility.
     *
     * @param pointer pointer to canonicalize
     * @return canonical pointer
     */
    public static String canonicalizePointer(String pointer) {
        return JsonPointer.canonicalize(pointer);
    }

    /**
     * Returns decoded pointer segments.
     *
     * @param pointer canonical or equivalent pointer
     * @return decoded immutable-or-owned segment list from the shared utility
     */
    public static List<String> splitPointer(String pointer) {
        return JsonPointer.split(pointer);
    }

    /**
     * Encodes decoded segments as a canonical pointer.
     *
     * @param segments decoded segments
     * @return canonical pointer
     */
    public static String toPointer(List<String> segments) {
        return JsonPointer.toPointer(segments);
    }

    /**
     * Appends one decoded child segment to a pointer.
     *
     * @param parent parent pointer
     * @param childSegment decoded child segment
     * @return canonical child pointer
     */
    public static String appendPointer(String parent, String childSegment) {
        return JsonPointer.append(parent, childSegment);
    }

    /**
     * Escapes one decoded segment according to RFC 6901.
     *
     * @param segment decoded segment
     * @return escaped segment
     */
    public static String escapeSegment(String segment) {
        return JsonPointer.escape(segment);
    }

    /**
     * Trims whitespace and leading/trailing slashes without decoding segments.
     *
     * @param value pointer fragment, or {@code null}
     * @return stripped fragment, never {@code null}
     */
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

    /**
     * Joins two relative pointer fragments by decoded segment.
     *
     * @param base first pointer fragment
     * @param tail second pointer fragment
     * @return canonical joined pointer
     */
    public static String joinRelativePointers(String base, String tail) {
        List<String> segments = new ArrayList<>(JsonPointer.split(base));
        segments.addAll(JsonPointer.split(tail));
        return JsonPointer.toPointer(segments);
    }

    /**
     * Resolves a pointer relative to a processing scope.
     *
     * <p>The root pointer selects the scope itself; otherwise decoded segments
     * are appended so escaped keys are never double-encoded.</p>
     *
     * @param scopePath absolute processing scope
     * @param relativePointer pointer relative to the scope
     * @return canonical absolute pointer
     */
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

    /**
     * Relativizes an absolute path when it is inside {@code scopePath}.
     *
     * <p>Paths outside the scope are returned in canonical absolute form rather
     * than being rejected.</p>
     *
     * @param scopePath absolute processing scope
     * @param absolutePath absolute candidate path
     * @return relative pointer when contained, otherwise canonical absolute path
     */
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
