package blue.language.processor;

/**
 * Fixed-cardinality source attribution for mutable patch values that must be
 * frozen at the processor boundary.
 */
public enum PatchSource {
    LEGACY_PUBLIC_API,
    PROCESSOR_INITIALIZATION_MARKER,
    PROCESSOR_TERMINATION_MARKER,
    PROCESSOR_CHECKPOINT_MARKER,
    CONFORMANCE_FIXTURE,
    CUSTOM_PROCESSOR,
    UNKNOWN_INTERNAL
}
