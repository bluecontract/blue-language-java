package blue.language.processor;

/**
 * Stable Contracts 1.0 diagnostic categories exposed to hosts.
 *
 * <p>Names are protocol values rather than implementation details; callers may
 * persist or compare them across equivalent processor representations.</p>
 */
public enum ProcessorErrorCategory {
    /** The processing root is structurally or semantically invalid. */
    InvalidProcessingDocument,
    /** The supplied processing event is invalid. */
    InvalidProcessingEvent,
    /** A runtime pointer is malformed or escapes its permitted scope. */
    InvalidRuntimePointer,
    /** A requested patch is malformed or cannot be applied. */
    InvalidPatch,
    /** A patch crosses the processor's authorized boundary. */
    PatchBoundaryViolation,
    /** A patch attempts to mutate processor-owned state. */
    ProtectedProcessorStateMutation,
    /** Reserved runtime state does not satisfy its invariant. */
    InvalidReservedRuntimeState,
    /** A runtime type is outside the supported closed registry. */
    UnsupportedRuntimeType,
    /** A runtime value occupies an unsupported contract role. */
    UnsupportedRuntimeRole,
    /** A contract key violates the stable key rules. */
    InvalidContractKey,
    /** A contract cannot be bound deterministically to its declared role. */
    InvalidContractBinding,
    /** External-channel evidence is incomplete or inconsistent. */
    InvalidExternalChannelSnapshot,
    /** An external subscription violates a subscription law. */
    ExternalSubscriptionLawViolation,
    /** No deterministic embedded route exists for a delivery. */
    EmbeddedRouteNotFound,
    /** An embedded route selects a non-object scope. */
    EmbeddedScopeNotObject,
    /** Embedded-scope traversal encounters a cycle. */
    EmbeddedScopeCycle,
    /** An otherwise active scope has been terminated or cut off. */
    ActiveScopeCutOff,
    /** Checkpoint-domain identity cannot be established. */
    CheckpointDomainError,
    /** Checkpoint ordering or update policy is violated. */
    CheckpointPolicyError,
    /** Two fixed contributions require incompatible values. */
    FixedValueConflict,
    /** A value does not conform to its effective type. */
    TypeCompatibilityViolation,
    /** A value violates its effective schema. */
    SchemaViolation,
    /** Type generalization cannot produce a permitted effective type. */
    TypeGeneralizationFailure,
    /** A mutation would alter an immutable cyclic set. */
    CyclicSetMutationUnsupported,
    /** A cyclic-set member cannot be used as the processing root. */
    CyclicMemberProcessingRootUnsupported,
    /** A cyclic-set member cannot be used as the processing event. */
    CyclicMemberProcessingEventUnsupported,
    /** An embedded boundary crosses into a cyclic set. */
    CyclicSetEmbeddedBoundaryUnsupported,
    /** Duplicate delivery evidence disagrees for one logical delivery. */
    InconsistentLogicalDelivery,
    /** The portable direct-node limit was exceeded. */
    DirectNodeLimitExceeded,
    /** The portable matching-delivery limit was exceeded. */
    MatchingDeliveryLimitExceeded,
    /** The portable participating-scope limit was exceeded. */
    ParticipatingScopeLimitExceeded,
    /** The portable internal-event limit was exceeded. */
    InternalEventLimitExceeded,
    /** The portable patch-count limit was exceeded. */
    PatchLimitExceeded,
    /** The portable runtime-ledger limit was exceeded. */
    RuntimeLedgerLimitExceeded,
    /** The effective external subscription surface is invalid. */
    SubscriptionSurfaceInvalid,
    /** A registered runtime implementation failed deterministically. */
    RuntimeExecutionFailure,
    /** The admitted gas budget was exhausted. */
    GasLimitExceeded
}
