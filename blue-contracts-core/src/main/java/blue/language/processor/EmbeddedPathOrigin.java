package blue.language.processor;

/**
 * Identifies how one concrete embedded-scope path entered an effective plan.
 */
enum EmbeddedPathOrigin {

    /** The path was authored directly in {@code ProcessEmbedded.paths}. */
    EXPLICIT,

    /** The path was generated from a direct collection member. */
    COLLECTION_MEMBER
}
