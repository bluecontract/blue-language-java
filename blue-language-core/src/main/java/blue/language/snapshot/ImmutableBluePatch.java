package blue.language.snapshot;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;

import java.util.Objects;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE;

/** Immutable, defensively copied patch value for Language API callers. */
public final class ImmutableBluePatch implements BluePatch {

    private final BluePatchOperation operation;
    private final String path;
    private final Node value;

    private ImmutableBluePatch(
            BluePatchOperation operation, String path, Node value) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.path = Objects.requireNonNull(path, "path");
        if (operation == BluePatchOperation.REMOVE) {
            this.value = null;
        } else {
            this.value = Objects.requireNonNull(value, OBJECT_VALUE).clone();
        }
    }

    /**
     * Creates an immutable add patch with a defensive value copy.
     *
     * @param path authored target pointer
     * @param value value to add
     * @return a new immutable add patch
     * @throws NullPointerException when {@code path} or {@code value} is
     *         {@code null}
     */
    public static ImmutableBluePatch add(String path, Node value) {
        return new ImmutableBluePatch(BluePatchOperation.ADD, path, value);
    }

    /**
     * Creates an immutable replace patch with a defensive value copy.
     *
     * @param path authored target pointer
     * @param value replacement value
     * @return a new immutable replace patch
     * @throws NullPointerException when {@code path} or {@code value} is
     *         {@code null}
     */
    public static ImmutableBluePatch replace(String path, Node value) {
        return new ImmutableBluePatch(
                BluePatchOperation.REPLACE, path, value);
    }

    /**
     * Creates an immutable remove patch.
     *
     * @param path authored target pointer
     * @return a new immutable remove patch
     * @throws NullPointerException when {@code path} is {@code null}
     */
    public static ImmutableBluePatch remove(String path) {
        return new ImmutableBluePatch(BluePatchOperation.REMOVE, path, null);
    }

    /**
     * Returns this patch's operation kind.
     *
     * @return patch operation kind
     */
    @Override
    public BluePatchOperation operation() {
        return operation;
    }

    /**
     * Returns the authored target pointer.
     *
     * @return target pointer
     */
    @Override
    public String path() {
        return path;
    }

    /**
     * Returns a defensive copy of this patch's operation value.
     *
     * @return caller-owned value copy, or {@code null} for removal
     */
    @Override
    public Node value() {
        return value == null ? null : value.clone();
    }
}
