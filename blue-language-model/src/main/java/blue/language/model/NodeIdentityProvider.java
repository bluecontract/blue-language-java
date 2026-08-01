package blue.language.model;

import java.util.List;

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

    /**
     * Calculates the identity of an ordered sequence using the Language list
     * fold rather than wrapping the sequence in an object node.
     *
     * @param nodes ordered nodes to identify
     * @return deterministic list BlueId
     */
    default String calculate(List<Node> nodes) {
        return calculate(new Node().items(nodes));
    }
}
