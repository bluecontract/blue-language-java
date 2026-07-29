package blue.language.processor;

/**
 * Fixed-cardinality source attribution for mutable patch values that must be
 * frozen at the processor boundary.
 */
public enum PatchSource {
    /** Patch entered through the legacy mutable public API. */
    LEGACY_PUBLIC_API,
    /** Patch creates or updates processor initialization state. */
    PROCESSOR_INITIALIZATION_MARKER,
    /** Patch creates or updates processor termination state. */
    PROCESSOR_TERMINATION_MARKER,
    /** Patch creates or updates processor checkpoint state. */
    PROCESSOR_CHECKPOINT_MARKER,
    /** Patch was supplied by a closed conformance fixture. */
    CONFORMANCE_FIXTURE,
    /** Patch was emitted by a custom registered processor. */
    CUSTOM_PROCESSOR,
    /** Internal caller did not provide a more precise source. */
    UNKNOWN_INTERNAL
}
