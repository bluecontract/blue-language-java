package blue.language.processor;

/**
 * Stable diagnostic categories used by Blue Contracts conformance checks.
 */
public enum ProcessorErrorCategory {
    InvalidProcessingDocument,
    UnsupportedContract,
    InvalidReservedMarker,
    InvalidRuntimePointer,
    BoundaryViolation,
    ReservedKeyWrite,
    InvalidPatch,
    InvalidPatchValue,
    HandlerExecutionError,
    CheckpointError,
    TerminationError,
    GasError,
    GeneralizationRejected,
    GeneralizationNoValidType,
    TypeSoundnessViolation,
    InternalProcessorError
}
