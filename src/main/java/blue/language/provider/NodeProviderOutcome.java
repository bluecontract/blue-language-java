package blue.language.provider;

/** Exhaustive transport-neutral outcomes for one provider lookup. */
public enum NodeProviderOutcome {
    /** Exact candidate content is available. */
    FOUND,
    /** The provider definitively has no content for the identity. */
    NOT_FOUND,
    /** Evidence may exist but cannot currently be acquired. */
    UNAVAILABLE,
    /** Supplied content or proof failed identity verification. */
    INVALID_EVIDENCE
}
