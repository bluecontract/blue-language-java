package blue.language.processor.model;

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
            FrozenNode checked = Objects.requireNonNull(value, "value");
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

    public static FrozenJsonPatch add(String path, FrozenNode value) {
        FrozenNode checked = Objects.requireNonNull(value, "value");
        return new FrozenJsonPatch(JsonPatch.Op.ADD, path, checked,
                NodeCanonicalizer.canonicalFrozenSize(checked));
    }

    public static FrozenJsonPatch replace(String path, FrozenNode value) {
        FrozenNode checked = Objects.requireNonNull(value, "value");
        return new FrozenJsonPatch(JsonPatch.Op.REPLACE, path, checked,
                NodeCanonicalizer.canonicalFrozenSize(checked));
    }

    public static FrozenJsonPatch remove(String path) {
        return new FrozenJsonPatch(JsonPatch.Op.REMOVE, path, null, 0L);
    }

    /**
     * Takes an immutable canonical snapshot of a legacy mutable patch value.
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
        return FrozenNode.fromNode(Objects.requireNonNull(value, "value"));
    }

    private static FrozenJsonPatch freezeMutable(JsonPatch.Op op, String path, Node value) {
        Node authored = Objects.requireNonNull(value, "value").clone();
        return new FrozenJsonPatch(op,
                path,
                freeze(authored),
                NodeCanonicalizer.canonicalSize(authored));
    }

    public JsonPatch.Op getOp() {
        return op;
    }

    /** Returns the path exactly as authored by the caller. */
    public String getPath() {
        return authoredPath;
    }

    /** Returns the immutable authored value, or {@code null} for remove. */
    public FrozenNode getValue() {
        return value;
    }

    /** Exact legacy authored payload size retained for gas-equivalent handoff. */
    public long getAuthoredCanonicalSizeBytes() {
        return authoredCanonicalSizeBytes;
    }

    /**
     * Returns the immutable parsed path retained by this patch.
     * Its decoded segment list is unmodifiable.
     */
    public ParsedJsonPointer parsedPath() {
        return parsedPath;
    }

    public ParsedJsonPointer getParsedPath() {
        return parsedPath;
    }

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

    @Override
    public String toString() {
        return "FrozenJsonPatch{" + op + " " + authoredPath + '}';
    }
}
