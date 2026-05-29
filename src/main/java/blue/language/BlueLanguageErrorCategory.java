package blue.language;

public enum BlueLanguageErrorCategory {
    InvalidSyntax,
    DuplicateKey,
    InvalidReservedField,
    InvalidBlueId,
    InvalidReferenceShape,
    InvalidBlueIdInput,
    ProviderUnavailable,
    ProviderBlueIdMismatch,
    TypeCycle,
    FixedValueConflict,
    TypeCompatibilityViolation,
    SchemaVocabularyError,
    SchemaViolation,
    ListControlViolation,
    CanonicalizationError,
    CircularSetError,
    UnsupportedPreprocessingTransform
}
