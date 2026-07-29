package blue.language;

/**
 * Semantic conclusion of a demand-limited Language operation.
 */
public enum BlueOperationOutcome {
    /** A value was fully established. */
    ESTABLISHED,
    /** Semantic absence was fully established. */
    ABSENT,
    /** Additional evidence or budget is required. */
    INCOMPLETE,
    /** Input or evidence is terminally invalid. */
    INVALID
}
