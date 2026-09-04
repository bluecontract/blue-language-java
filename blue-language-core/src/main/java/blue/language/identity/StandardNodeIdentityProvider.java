package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.NodeIdentityProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.FrozenNodeIdentity;

import java.util.ArrayList;
import java.util.List;

/** Normative Language implementation of the model identity SPI. */
public final class StandardNodeIdentityProvider
        implements NodeIdentityProvider {

    /** Creates the stateless normative model-identity provider. */
    public StandardNodeIdentityProvider() {
    }

    @Override
    public String calculate(Node node) {
        return FrozenNode.fromResolvedNode(node).blueId();
    }

    @Override
    public String calculate(List<Node> nodes) {
        if (nodes == null) {
            throw new IllegalArgumentException(
                    "Node identity input list must not be null.");
        }
        List<FrozenNode> frozen = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            frozen.add(FrozenNode.fromResolvedNode(node));
        }
        return FrozenNodeIdentity.INSTANCE.blueId(frozen);
    }
}
