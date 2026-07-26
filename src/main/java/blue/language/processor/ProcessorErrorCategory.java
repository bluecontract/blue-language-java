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
    GasLimitExceeded,

    /*
     * Source-compatible names from the pre-1.0 API. They remain readable by
     * existing integrations, but every public diagnostic is normalized to the
     * corresponding Contracts 1.0 category.
     */
    @Deprecated UnsupportedContract,
    @Deprecated InvalidReservedMarker,
    @Deprecated ProviderUnavailable,
    @Deprecated ProviderBlueIdMismatch,
    @Deprecated BoundaryViolation,
    @Deprecated ReservedKeyWrite,
    @Deprecated InvalidPatchValue,
    @Deprecated HandlerExecutionError,
    @Deprecated CheckpointError,
    @Deprecated TerminationError,
    @Deprecated GasError,
    @Deprecated GeneralizationRejected,
    @Deprecated GeneralizationNoValidType,
    @Deprecated TypeSoundnessViolation,
    @Deprecated InternalProcessorError;

    /**
     * Maps compatibility categories to the normative Contracts 1.0 vocabulary.
     */
    public ProcessorErrorCategory normative() {
        switch (this) {
            case UnsupportedContract:
                return UnsupportedRuntimeType;
            case InvalidReservedMarker:
                return InvalidReservedRuntimeState;
            case ProviderUnavailable:
                // A conforming host normally turns this into NeedsResources
                // before a completed result exists.
                return RuntimeExecutionFailure;
            case ProviderBlueIdMismatch:
                return InvalidProcessingDocument;
            case BoundaryViolation:
                return PatchBoundaryViolation;
            case ReservedKeyWrite:
                return ProtectedProcessorStateMutation;
            case InvalidPatchValue:
                return InvalidPatch;
            case HandlerExecutionError:
            case TerminationError:
            case InternalProcessorError:
                return RuntimeExecutionFailure;
            case CheckpointError:
                return CheckpointPolicyError;
            case GasError:
                return RuntimeLedgerLimitExceeded;
            case GeneralizationRejected:
            case GeneralizationNoValidType:
                return TypeGeneralizationFailure;
            case TypeSoundnessViolation:
                return TypeCompatibilityViolation;
            default:
                return this;
        }
    }
}
