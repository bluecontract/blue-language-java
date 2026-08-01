package blue.language.snapshot;

import blue.language.model.Node;

/** Language-owned immutable view of one RFC 6902-style patch operation. */
public interface BluePatch {

    /** Returns the operation kind. */
    BluePatchOperation operation();

    /** Returns the authored RFC 6901 pointer. */
    String path();

    /** Returns the operation value, or {@code null} for removal. */
    Node value();
}
