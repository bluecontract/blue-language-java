package blue.language.processor.model;

import blue.language.utils.Properties;

import blue.language.model.Node;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.ParsedJsonPointer;

import java.util.Objects;

/**
 * Immutable authored JSON patch whose value is already in canonical frozen form.
 *
 * <p>This type is the allocation-free handoff for callers that already own a
 * {@link FrozenNode}. Values must be canonical authored values; resolved document
 * views are deliberately rejected because their inherited fields are ambiguous
 * at a patch boundary. The original path spelling is retained for diagnostics,
 * while its immutable parsed form is constructed exactly once.</p>
 */
public final class FrozenJsonPatch {

    private final JsonPatch.Op op;
    private final String authoredPath;
    private final ParsedJsonPointer parsedPath;
    private final FrozenNode value;
    private final long authoredCanonicalSizeBytes;
    private volatile String valueBlueId;
    private volatile FrozenNode.ResolvedStructuralKey valueStructuralKey;

    private FrozenJsonPatch(JsonPatch.Op op,
                            String path,
                            FrozenNode value,
                            long authoredCanonicalSizeBytes) {
        this.op = Objects.requireNonNull(op, "op");
        this.authoredPath = Objects.requireNonNull(path, "path");
        this.parsedPath = ParsedJsonPointer.parse(path);
        if (op == JsonPatch.Op.REMOVE) {
            this.value = null;
            this.authoredCanonicalSizeBytes = 0L;
        } else {
            FrozenNode checked = Objects.requireNonNull(value, Properties.OBJECT_VALUE);
            if (!checked.isStrictCanonical()) {
                throw new IllegalArgumentException(
                        "Frozen patch values must be authored canonical values, not resolved document views");
            }
            this.value = checked;
            if (authoredCanonicalSizeBytes < 0L) {
                throw new IllegalArgumentException(
                        "authoredCanonicalSizeBytes must be non-negative");
            }
            this.authoredCanonicalSizeBytes = authoredCanonicalSizeBytes;
        }
    }

    /**
     * Creates an immutable add patch and records its canonical authored size.
     *
     * <p>The immutable value is retained directly without another allocation.</p>
     *
     * @param path authored JSON Pointer path
     * @param value strict canonical authored value
     * @return immutable add patch
     * @throws NullPointerException if {@code path} or {@code value} is
     *         {@code null}
     * @throws IllegalArgumentException if {@code value} is a resolved document
     *         view rather than a strict canonical authored value
     */
    public static FrozenJsonPatch add(String path, FrozenNode value) {
        FrozenNode checked = Objects.requireNonNull(value, Properties.OBJECT_VALUE);
        return new FrozenJsonPatch(JsonPatch.Op.ADD, path, checked,
                NodeCanonicalizer.canonicalFrozenSize(checked));
    }

    /**
     * Creates an immutable replace patch and records its canonical authored
     * size.
     *
     * <p>The immutable value is retained directly without another allocation.</p>
     *
     * @param path authored JSON Pointer path
     * @param value strict canonical authored value
     * @return immutable replace patch
     * @throws NullPointerException if {@code path} or {@code value} is
     *         {@code null}
     * @throws IllegalArgumentException if {@code value} is a resolved document
     *         view rather than a strict canonical authored value
     */
    public static FrozenJsonPatch replace(String path, FrozenNode value) {
        FrozenNode checked = Objects.requireNonNull(value, Properties.OBJECT_VALUE);
        return new FrozenJsonPatch(JsonPatch.Op.REPLACE, path, checked,
                NodeCanonicalizer.canonicalFrozenSize(checked));
    }

    /**
     * Creates an immutable remove patch with no value payload.
     *
     * @param path authored JSON Pointer path
     * @return immutable remove patch
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static FrozenJsonPatch remove(String path) {
        return new FrozenJsonPatch(JsonPatch.Op.REMOVE, path, null, 0L);
    }

    /**
     * Takes an immutable canonical snapshot of a legacy mutable patch value.
     *
     * <p>Add and replace values are cloned before freezing, so later mutation
     * of the source patch value cannot affect the result.</p>
     *
     * @param patch mutable patch to snapshot
     * @return immutable patch with the same operation and authored path
     * @throws NullPointerException if {@code patch} is {@code null}
     * @throws IllegalArgumentException if an add/replace value cannot be
     *         represented as a canonical authored value
     */
    public static FrozenJsonPatch from(JsonPatch patch) {
        JsonPatch checked = Objects.requireNonNull(patch, "patch");
        switch (checked.getOp()) {
            case ADD:
                return freezeMutable(JsonPatch.Op.ADD, checked.getPath(), checked.getVal());
            case REPLACE:
                return freezeMutable(JsonPatch.Op.REPLACE, checked.getPath(), checked.getVal());
            case REMOVE:
                return remove(checked.getPath());
            default:
                throw new IllegalStateException("Unsupported patch op: " + checked.getOp());
        }
    }

    private static FrozenNode freeze(Node value) {
        return FrozenNode.fromNode(Objects.requireNonNull(value, Properties.OBJECT_VALUE));
    }

    private static FrozenJsonPatch freezeMutable(JsonPatch.Op op, String path, Node value) {
        Node authored = Objects.requireNonNull(value, Properties.OBJECT_VALUE).clone();
        return new FrozenJsonPatch(op,
                path,
                freeze(authored),
                NodeCanonicalizer.canonicalSize(authored));
    }

    /**
     * Returns the validated patch operation.
     *
     * @return non-null patch operation
     */
    public JsonPatch.Op getOp() {
        return op;
    }

    /**
     * Returns the path exactly as authored by the caller.
     *
     * @return non-null authored path without normalization
     */
    public String getPath() {
        return authoredPath;
    }

    /**
     * Returns the immutable authored value.
     *
     * @return retained immutable value, or {@code null} for remove
     */
    public FrozenNode getValue() {
        return value;
    }

    /**
     * Returns the exact legacy authored payload size retained for
     * gas-equivalent handoff.
     *
     * @return non-negative canonical byte size, or zero for remove
     */
    public long getAuthoredCanonicalSizeBytes() {
        return authoredCanonicalSizeBytes;
    }

    /**
     * Returns the immutable parsed path retained by this patch.
     * Its decoded segment list is unmodifiable.
     *
     * @return parsed canonical pointer retained by this patch
     */
    public ParsedJsonPointer parsedPath() {
        return parsedPath;
    }

    /**
     * JavaBean alias for {@link #parsedPath()}.
     *
     * @return parsed canonical pointer retained by this patch
     */
    public ParsedJsonPointer getParsedPath() {
        return parsedPath;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FrozenJsonPatch)) {
            return false;
        }
        FrozenJsonPatch that = (FrozenJsonPatch) other;
        return op == that.op
                && authoredPath.equals(that.authoredPath)
                && authoredCanonicalSizeBytes == that.authoredCanonicalSizeBytes
                && (value == that.value
                || Objects.equals(semanticValueBlueId(), that.semanticValueBlueId())
                && Objects.equals(exactValueKey(), that.exactValueKey()));
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(op, authoredPath, authoredCanonicalSizeBytes,
                semanticValueBlueId(), exactValueKey());
    }

    private String semanticValueBlueId() {
        if (value == null) {
            return null;
        }
        String identity = valueBlueId;
        if (identity == null) {
            synchronized (this) {
                identity = valueBlueId;
                if (identity == null) {
                    identity = value.blueId();
                    valueBlueId = identity;
                }
            }
        }
        return identity;
    }

    private FrozenNode.ResolvedStructuralKey exactValueKey() {
        if (value == null) {
            return null;
        }
        FrozenNode.ResolvedStructuralKey key = valueStructuralKey;
        if (key == null) {
            synchronized (this) {
                key = valueStructuralKey;
                if (key == null) {
                    key = value.resolvedStructuralKey();
                    valueStructuralKey = key;
                }
            }
        }
        return key;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "FrozenJsonPatch{" + op + " " + authoredPath + '}';
    }
}
