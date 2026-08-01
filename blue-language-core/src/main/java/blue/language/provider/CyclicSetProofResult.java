package blue.language.provider;

import blue.language.api.NodeProviderOutcome;

import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral result of acquiring complete cyclic-set evidence.
 *
 * <p>The outcome is exhaustive: a proof is present only for
 * {@link NodeProviderOutcome#FOUND}; all other outcomes carry no proof and may
 * include a diagnostic. This keeps a temporary evidence-acquisition failure
 * distinct from a definitive miss or invalid evidence.</p>
 */
public final class CyclicSetProofResult {

    private final NodeProviderOutcome outcome;
    private final CyclicSetProof proof;
    private final String diagnostic;

    private CyclicSetProofResult(
            NodeProviderOutcome outcome,
            CyclicSetProof proof,
            String diagnostic) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.proof = proof;
        this.diagnostic = diagnostic;
        if (outcome == NodeProviderOutcome.FOUND && proof == null) {
            throw new IllegalArgumentException(
                    "Found cyclic-set proof results require proof.");
        }
        if (outcome != NodeProviderOutcome.FOUND && proof != null) {
            throw new IllegalArgumentException(
                    outcome + " cyclic-set proof results cannot carry proof.");
        }
    }

    /**
     * Creates a successful proof-acquisition result.
     *
     * @param proof complete candidate proof
     * @return found result
     */
    public static CyclicSetProofResult found(CyclicSetProof proof) {
        return new CyclicSetProofResult(
                NodeProviderOutcome.FOUND,
                Objects.requireNonNull(proof, "proof"),
                null);
    }

    /**
     * Creates a definitive proof miss.
     *
     * @return proof-miss result
     */
    public static CyclicSetProofResult notFound() {
        return new CyclicSetProofResult(
                NodeProviderOutcome.NOT_FOUND, null, null);
    }

    /**
     * Creates a temporary proof-acquisition failure.
     *
     * @param diagnostic optional provider diagnostic
     * @return unavailable result
     */
    public static CyclicSetProofResult unavailable(String diagnostic) {
        return new CyclicSetProofResult(
                NodeProviderOutcome.UNAVAILABLE, null, diagnostic);
    }

    /**
     * Creates an invalid-evidence result.
     *
     * @param diagnostic optional evidence diagnostic
     * @return invalid-evidence result
     */
    public static CyclicSetProofResult invalidEvidence(String diagnostic) {
        return new CyclicSetProofResult(
                NodeProviderOutcome.INVALID_EVIDENCE, null, diagnostic);
    }

    /**
     * Returns the provider's exhaustive proof-acquisition conclusion.
     *
     * @return proof outcome
     */
    public NodeProviderOutcome outcome() {
        return outcome;
    }

    /**
     * Returns the candidate proof when the outcome is found.
     *
     * @return optional complete proof
     */
    public Optional<CyclicSetProof> proof() {
        return Optional.ofNullable(proof);
    }

    /**
     * Returns the optional provider diagnostic.
     *
     * @return diagnostic, if supplied
     */
    public Optional<String> diagnostic() {
        return Optional.ofNullable(diagnostic);
    }
}
