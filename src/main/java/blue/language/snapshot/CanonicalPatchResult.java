package blue.language.snapshot;

import blue.language.processor.model.JsonPatch;

/**
 * Immutable evidence produced by one canonical overlay patch.
 *
 * <p>{@link #before()} is null for a newly added path and {@link #after()} is
 * null for removal. {@link #root()} is the new structurally shared root.</p>
 */
public final class CanonicalPatchResult {

    private final FrozenNode root;
    private final FrozenNode before;
    private final FrozenNode after;
    private final JsonPatch.Op op;
    private final String path;

    CanonicalPatchResult(FrozenNode root, FrozenNode before, FrozenNode after, JsonPatch.Op op, String path) {
        this.root = root;
        this.before = before;
        this.after = after;
        this.op = op;
        this.path = path;
    }

    /** Returns the patched root.
     * @return immutable patched root */
    public FrozenNode root() {
        return root;
    }

    /** Returns the prior path value.
     * @return prior value, or {@code null} */
    public FrozenNode before() {
        return before;
    }

    /** Returns the resulting path value.
     * @return resulting value, or {@code null} */
    public FrozenNode after() {
        return after;
    }

    /** Returns the applied operation.
     * @return patch operation */
    public JsonPatch.Op op() {
        return op;
    }

    /** Returns the patched pointer.
     * @return RFC 6901 path */
    public String path() {
        return path;
    }

    /** Returns the patched root identity.
     * @return root BlueId */
    public String blueId() {
        return root.blueId();
    }
}
