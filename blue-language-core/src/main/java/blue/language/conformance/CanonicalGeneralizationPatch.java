package blue.language.conformance;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Immutable replacement of one canonical subtree produced while widening a
 * node to the nearest conforming type.
 *
 * <p>{@link #before()} may be {@code null} when the generalized path did not
 * previously exist in the canonical overlay. Mutable accessors return fresh
 * {@link Node} materializations.</p>
 */
public final class CanonicalGeneralizationPatch {

    private final String path;
    private final FrozenNode before;
    private final FrozenNode after;

    CanonicalGeneralizationPatch(String path, FrozenNode before, FrozenNode after) {
        this.path = Objects.requireNonNull(path, "path");
        this.before = before;
        this.after = Objects.requireNonNull(after, "after");
    }

    /**
     * Returns the generalized pointer.
     *
     * @return RFC 6901 path
     */
    public String path() {
        return path;
    }

    /**
     * Returns the prior canonical subtree.
     *
     * @return immutable prior subtree, or {@code null}
     */
    public FrozenNode before() {
        return before;
    }

    /**
     * Materializes the prior subtree.
     *
     * @return new mutable prior subtree, or {@code null}
     */
    public Node beforeNode() {
        return before != null ? before.toNode() : null;
    }

    /**
     * Returns the generalized canonical subtree.
     *
     * @return immutable replacement subtree
     */
    public FrozenNode after() {
        return after;
    }

    /**
     * Materializes the generalized subtree.
     *
     * @return new mutable replacement subtree
     */
    public Node afterNode() {
        return after.toNode();
    }
}
