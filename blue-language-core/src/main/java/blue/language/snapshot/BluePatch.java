package blue.language.snapshot;

import blue.language.model.Node;

/** Language-owned immutable view of one RFC 6902-style patch operation. */
public interface BluePatch {

    /**
     * Returns the operation kind.
     *
     * @return patch operation kind
     */
    BluePatchOperation operation();

    /**
     * Returns the authored RFC 6901 pointer.
     *
     * @return target pointer
     */
    String path();

    /**
     * Returns the operation value, or {@code null} for removal.
     *
     * @return operation value, or {@code null} when the operation removes a value
     */
    Node value();
}
