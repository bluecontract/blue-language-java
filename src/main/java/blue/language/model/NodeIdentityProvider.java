package blue.language.model;

/**
 * Downward dependency-inversion point for deriving an identity from a model
 * node.
 *
 * <p>The model owns the contract while the Language identity layer supplies
 * the normative implementation through {@link java.util.ServiceLoader}.</p>
 */
public interface NodeIdentityProvider {

    /**
     * Calculates the identity exposed by the compatibility {@code /blueId}
     * node path.
     *
     * @param node node whose expanded identity is required
     * @return deterministic BlueId
     */
    String calculate(Node node);
}
