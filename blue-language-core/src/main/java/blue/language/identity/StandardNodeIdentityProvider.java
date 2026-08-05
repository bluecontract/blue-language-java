package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.NodeIdentityProvider;
import blue.language.identity.NodeToBlueIdInput;

import java.util.List;

/** Normative Language implementation of the model identity SPI. */
public final class StandardNodeIdentityProvider
        implements NodeIdentityProvider {

    /** Creates the stateless normative model-identity provider. */
    public StandardNodeIdentityProvider() {
    }

    @Override
    public String calculate(Node node) {
        return DirectBlueIdCalculator.INSTANCE
                .directBlueIdFromCanonicalInput(
                        NodeToBlueIdInput
                                .getWithResolvedBlueIdMetadata(node));
    }

    @Override
    public String calculate(List<Node> nodes) {
        return DirectBlueIdCalculator.calculateBlueId(nodes);
    }
}
