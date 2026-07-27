package blue.language.processor;

/**
 * Stable Contracts 1.0 diagnostic categories.
 */
public enum ProcessorErrorCategory {
    InvalidProcessingDocument,
    InvalidProcessingEvent,
    InvalidRuntimePointer,
    InvalidPatch,
    PatchBoundaryViolation,
    ProtectedProcessorStateMutation,
    InvalidReservedRuntimeState,
    UnsupportedRuntimeType,
    UnsupportedRuntimeRole,
    InvalidContractKey,
    InvalidContractBinding,
    InvalidExternalChannelSnapshot,
    ExternalSubscriptionLawViolation,
    EmbeddedRouteNotFound,
    EmbeddedScopeNotObject,
    EmbeddedScopeCycle,
    ActiveScopeCutOff,
    CheckpointDomainError,
    CheckpointPolicyError,
    FixedValueConflict,
    TypeCompatibilityViolation,
    SchemaViolation,
    TypeGeneralizationFailure,
    CyclicSetMutationUnsupported,
    DirectNodeLimitExceeded,
    MatchingDeliveryLimitExceeded,
    ParticipatingScopeLimitExceeded,
    InternalEventLimitExceeded,
    PatchLimitExceeded,
    RuntimeLedgerLimitExceeded,
    SubscriptionSurfaceInvalid,
    RuntimeExecutionFailure,
    GasLimitExceeded
}
