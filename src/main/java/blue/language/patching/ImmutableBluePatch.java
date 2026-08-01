package blue.language.patching;

import blue.language.model.Node;

import java.util.Objects;

import static blue.language.utils.Properties.OBJECT_VALUE;

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

    /** Creates an add patch. */
    public static ImmutableBluePatch add(String path, Node value) {
        return new ImmutableBluePatch(BluePatchOperation.ADD, path, value);
    }

    /** Creates a replace patch. */
    public static ImmutableBluePatch replace(String path, Node value) {
        return new ImmutableBluePatch(
                BluePatchOperation.REPLACE, path, value);
    }

    /** Creates a remove patch. */
    public static ImmutableBluePatch remove(String path) {
        return new ImmutableBluePatch(BluePatchOperation.REMOVE, path, null);
    }

    @Override
    public BluePatchOperation operation() {
        return operation;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Node value() {
        return value == null ? null : value.clone();
    }
}
