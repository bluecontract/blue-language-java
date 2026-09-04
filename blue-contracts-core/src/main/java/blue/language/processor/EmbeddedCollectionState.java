package blue.language.processor;

/**
 * Successful projection state for one Process Embedded collection declaration.
 *
 * <p>Unavailable and invalid collections never produce a plan. They remain
 * typed retryable or invalid outcomes at the planner boundary.</p>
 */
enum EmbeddedCollectionState {
    /** The declared path is absent and therefore contributes zero occurrences. */
    ABSENT_ZERO_OCCURRENCES,
    /** The declared path selects a present, verified object collection. */
    PRESENT_COLLECTION
}
