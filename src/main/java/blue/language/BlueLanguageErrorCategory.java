package blue.language;

/**
 * Stable semantic failure categories emitted by the Language conformance
 * harness independently of implementation exception types.
 */
public enum BlueLanguageErrorCategory {
    /** Source syntax is invalid. */
    InvalidSyntax,
    /** An object contains a duplicate key. */
    DuplicateKey,
    /** A reserved field is invalid. */
    InvalidReservedField,
    /** A BlueId is malformed or noncanonical. */
    InvalidBlueId,
    /** A reference has an invalid structural shape. */
    InvalidReferenceShape,
    /** Canonical BlueId input is invalid. */
    InvalidBlueIdInput,
    /** Required provider evidence is unavailable. */
    ProviderUnavailable,
    /** Provider content does not match its requested identity. */
    ProviderBlueIdMismatch,
    /** Type ancestry contains a cycle. */
    TypeCycle,
    /** A fixed value conflicts with supplied content. */
    FixedValueConflict,
    /** Type constraints are incompatible. */
    TypeCompatibilityViolation,
    /** Schema vocabulary is invalid. */
    SchemaVocabularyError,
    /** A value violates its schema. */
    SchemaViolation,
    /** List control fields are inconsistent. */
    ListControlViolation,
    /** Canonicalization cannot produce a valid result. */
    CanonicalizationError,
    /** A circular-set definition is invalid. */
    CircularSetError,
    /** A preprocessing transform is unsupported. */
    UnsupportedPreprocessingTransform
}
