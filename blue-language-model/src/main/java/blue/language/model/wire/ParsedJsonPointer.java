package blue.language.model.wire;

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

    /**
     * Parses and canonicalizes a JSON Pointer.
     *
     * @param pointer pointer to parse
     * @return immutable canonical parsed pointer
     */
    public static ParsedJsonPointer parse(String pointer) {
        List<String> decoded = JsonPointer.split(pointer);
        if (decoded.isEmpty()) {
            return ROOT;
        }
        List<String> immutable = Collections.unmodifiableList(new ArrayList<>(decoded));
        return new ParsedJsonPointer(JsonPointer.toPointer(immutable), immutable);
    }

    /**
     * Creates a canonical pointer from decoded path segments.
     *
     * @param segments decoded path segments
     * @return immutable canonical parsed pointer
     */
    public static ParsedJsonPointer ofSegments(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return ROOT;
        }
        List<String> copy = Collections.unmodifiableList(new ArrayList<>(segments));
        return new ParsedJsonPointer(JsonPointer.toPointer(copy), copy);
    }

    /**
     * Returns the canonical encoded pointer.
     *
     * @return canonical pointer text
     */
    public String pointer() {
        return pointer;
    }

    /**
     * Returns the decoded pointer segments.
     *
     * @return unmodifiable ordered segment list
     */
    public List<String> segments() {
        return segments;
    }

    /**
     * Returns the number of path segments.
     *
     * @return non-negative pointer depth
     */
    public int depth() {
        return segments.size();
    }

    /**
     * Reports whether this pointer denotes the root.
     *
     * @return {@code true} when the pointer has no segments
     */
    public boolean isRoot() {
        return segments.isEmpty();
    }

    /**
     * Returns the final decoded path segment.
     *
     * @return leaf segment, or {@code null} for the root
     */
    public String leaf() {
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    /**
     * Returns the canonical parent pointer.
     *
     * @return parent pointer, or this root pointer when already at the root
     */
    public ParsedJsonPointer parent() {
        return segments.isEmpty()
                ? this
                : ofSegments(segments.subList(0, segments.size() - 1));
    }

    /**
     * Appends one decoded segment.
     *
     * @param decodedSegment decoded segment to append
     * @return new canonical child pointer
     */
    public ParsedJsonPointer append(String decodedSegment) {
        List<String> next = new ArrayList<>(segments.size() + 1);
        next.addAll(segments);
        next.add(decodedSegment == null ? "" : decodedSegment);
        return ofSegments(next);
    }

    /**
     * Tests whether this pointer is equal to or an ancestor of a candidate.
     *
     * @param candidate candidate pointer
     * @return {@code true} when every segment of this pointer prefixes the candidate
     */
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

    /**
     * Tests whether either pointer is an ancestor of the other.
     *
     * @param other pointer to compare
     * @return {@code true} when the pointers overlap
     */
    public boolean overlaps(ParsedJsonPointer other) {
        Objects.requireNonNull(other, "other");
        return isAncestorOfOrEqual(other) || other.isAncestorOfOrEqual(this);
    }

    /**
     * Reports whether the leaf denotes an array index or append position.
     *
     * @return {@code true} when the leaf is numeric or {@code "-"}
     */
    public boolean hasArrayIndexLeaf() {
        String leaf = leaf();
        return leaf != null && JsonPointer.isArrayIndexSegment(leaf);
    }

    /**
     * Reports whether the leaf is the array append marker.
     *
     * @return {@code true} when the leaf is {@code "-"}
     */
    public boolean isAppend() {
        return JsonPointer.ARRAY_APPEND.equals(leaf());
    }

    /**
     * Returns the non-negative numeric leaf, or {@code -1} when the leaf is
     * root, append, non-numeric, negative, or outside the {@code int} range.
     *
     * @return non-negative array index, or {@code -1} when unavailable
     */
    public int arrayIndex() {
        String leaf = leaf();
        if (leaf == null
                || JsonPointer.ARRAY_APPEND.equals(leaf)) {
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
