package blue.language.patching;

/** Patch operations supported by the immutable canonical overlay engine. */
public enum BluePatchOperation {
    /** Inserts a value at a path. */
    ADD,
    /** Replaces the value at a path. */
    REPLACE,
    /** Removes the value at a path. */
    REMOVE
}
