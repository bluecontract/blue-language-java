package blue.language.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, canonical JSON Pointer with decoded segments.
 *
 * <p>Parsing and escape handling are performed once. The representation keeps
 * the project's historical {@code "/"} root spelling while using RFC 6901
 * escaping for non-root pointers.</p>
 */
public final class ParsedJsonPointer implements Comparable<ParsedJsonPointer> {

    private static final ParsedJsonPointer ROOT =
            new ParsedJsonPointer("/", Collections.<String>emptyList());

    private final String pointer;
    private final List<String> segments;
    private final int hashCode;

    private ParsedJsonPointer(String pointer, List<String> segments) {
        this.pointer = pointer;
        this.segments = segments;
        this.hashCode = pointer.hashCode();
    }

    public static ParsedJsonPointer parse(String pointer) {
        List<String> decoded = JsonPointer.split(pointer);
        if (decoded.isEmpty()) {
            return ROOT;
        }
        List<String> immutable = Collections.unmodifiableList(new ArrayList<>(decoded));
        return new ParsedJsonPointer(JsonPointer.toPointer(immutable), immutable);
    }

    public static ParsedJsonPointer ofSegments(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return ROOT;
        }
        List<String> copy = Collections.unmodifiableList(new ArrayList<>(segments));
        return new ParsedJsonPointer(JsonPointer.toPointer(copy), copy);
    }

    public String pointer() {
        return pointer;
    }

    /** Returns an unmodifiable list of decoded pointer segments. */
    public List<String> segments() {
        return segments;
    }

    public int depth() {
        return segments.size();
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public String leaf() {
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    public ParsedJsonPointer parent() {
        return segments.isEmpty()
                ? this
                : ofSegments(segments.subList(0, segments.size() - 1));
    }

    public ParsedJsonPointer append(String decodedSegment) {
        List<String> next = new ArrayList<>(segments.size() + 1);
        next.addAll(segments);
        next.add(decodedSegment == null ? "" : decodedSegment);
        return ofSegments(next);
    }

    public boolean isAncestorOfOrEqual(ParsedJsonPointer candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (segments.size() > candidate.segments.size()) {
            return false;
        }
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).equals(candidate.segments.get(i))) {
                return false;
            }
        }
        return true;
    }

    public boolean overlaps(ParsedJsonPointer other) {
        Objects.requireNonNull(other, "other");
        return isAncestorOfOrEqual(other) || other.isAncestorOfOrEqual(this);
    }

    public boolean hasArrayIndexLeaf() {
        String leaf = leaf();
        return leaf != null && JsonPointer.isArrayIndexSegment(leaf);
    }

    public boolean isAppend() {
        return "-".equals(leaf());
    }

    /**
     * Returns the non-negative numeric leaf, or {@code -1} when the leaf is
     * root, append, non-numeric, negative, or outside the {@code int} range.
     */
    public int arrayIndex() {
        String leaf = leaf();
        if (leaf == null || "-".equals(leaf)) {
            return -1;
        }
        try {
            int value = Integer.parseInt(leaf);
            return value >= 0 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override
    public int compareTo(ParsedJsonPointer other) {
        return pointer.compareTo(Objects.requireNonNull(other, "other").pointer);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ParsedJsonPointer
                && pointer.equals(((ParsedJsonPointer) other).pointer);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return pointer;
    }
}
