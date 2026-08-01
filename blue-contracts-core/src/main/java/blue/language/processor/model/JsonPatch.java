package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.BluePatchOperation;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.Objects;

/**
 * Validated RFC 6902-style patch entry supported by the Contracts processor.
 *
 * <p>Remove operations never carry a value; add and replace operations always
 * do. Factory methods enforce that invariant at construction time. Operation
 * and path fields are immutable, but add/replace values are retained and
 * returned by reference; use
 * {@link blue.language.processor.FrozenJsonPatch} when the value must be
 * isolated from caller mutation.</p>
 */
@TypeBlueId(RuntimeBlueIds.JSON_PATCH_ENTRY)
public class JsonPatch implements BluePatch {

    /** Supported patch operations. */
    public enum Op {
        /** Insert a value at the addressed location. */
        ADD,
        /** Replace the value at the addressed location. */
        REPLACE,
        /** Remove the value at the addressed location. */
        REMOVE;

        /** Returns the equivalent Language-owned patch operation. */
        public BluePatchOperation blueOperation() {
            switch (this) {
                case ADD:
                    return BluePatchOperation.ADD;
                case REPLACE:
                    return BluePatchOperation.REPLACE;
                case REMOVE:
                    return BluePatchOperation.REMOVE;
                default:
                    throw new IllegalStateException(
                            "Unsupported Contracts patch operation: " + this);
            }
        }

        /** Reconstructs the Contracts operation at the module boundary. */
        public static Op fromBlueOperation(
                BluePatchOperation operation) {
            switch (Objects.requireNonNull(operation, "operation")) {
                case ADD:
                    return ADD;
                case REPLACE:
                    return REPLACE;
                case REMOVE:
                    return REMOVE;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported Language patch operation: "
                                    + operation);
            }
        }
    }

    private final Op op;
    private final String path;
    private final Node val;

    private JsonPatch(Op op, String path, Node val) {
        this.op = Objects.requireNonNull(op, "op");
        this.path = Objects.requireNonNull(path, "path");
        if (op == Op.REMOVE) {
            this.val = null;
        } else {
            this.val = Objects.requireNonNull(val, "val");
        }
    }

    /**
     * Creates an add operation for {@code path}.
     *
     * @param path authored JSON Pointer path
     * @param val value retained by reference
     * @return validated add patch
     * @throws NullPointerException if {@code path} or {@code val} is
     *         {@code null}
     */
    public static JsonPatch add(String path, Node val) {
        return new JsonPatch(Op.ADD, path, val);
    }

    /**
     * Creates a replace operation for {@code path}.
     *
     * @param path authored JSON Pointer path
     * @param val value retained by reference
     * @return validated replace patch
     * @throws NullPointerException if {@code path} or {@code val} is
     *         {@code null}
     */
    public static JsonPatch replace(String path, Node val) {
        return new JsonPatch(Op.REPLACE, path, val);
    }

    /**
     * Creates a remove operation for {@code path}.
     *
     * @param path authored JSON Pointer path
     * @return validated remove patch with no value
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static JsonPatch remove(String path) {
        return new JsonPatch(Op.REMOVE, path, null);
    }

    /**
     * Returns the validated operation.
     *
     * @return non-null patch operation
     */
    public Op getOp() {
        return op;
    }

    /**
     * Returns the authored JSON Pointer path.
     *
     * @return non-null path exactly as supplied to the factory
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the operation value.
     *
     * @return retained mutable value reference, or {@code null} for remove
     */
    public Node getVal() {
        return val;
    }

    @Override
    public BluePatchOperation operation() {
        return op.blueOperation();
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Node value() {
        return val;
    }
}
