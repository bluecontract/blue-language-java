package blue.language.provider;

import blue.language.api.NodeProviderOutcome;

import blue.language.model.Node;
import blue.language.provider.NodeProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ordered provider chain that stops at the first outcome other than
 * {@link NodeProviderOutcome#NOT_FOUND}.
 *
 * <p>Unavailable and invalid evidence are authoritative failures and are never
 * hidden by a later provider.</p>
 */
public class SequentialNodeProvider implements NodeProvider {
    private final List<NodeProvider> nodeProviders;

    /**
     * Creates an ordered provider chain.
     *
     * @param nodeProviders providers in lookup order
     */
    public SequentialNodeProvider(List<NodeProvider> nodeProviders) {
        Objects.requireNonNull(nodeProviders, "nodeProviders");
        List<NodeProvider> retained =
                new ArrayList<>(nodeProviders.size());
        for (NodeProvider provider : nodeProviders) {
            retained.add(Objects.requireNonNull(
                    provider, "nodeProvider"));
        }
        this.nodeProviders =
                Collections.unmodifiableList(retained);
    }

    /**
     * Creates an ordered provider chain.
     *
     * @param nodeProviders providers in lookup order
     */
    public SequentialNodeProvider(NodeProvider... nodeProviders) {
        this(Arrays.asList(
                Objects.requireNonNull(
                        nodeProviders, "nodeProviders")));
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        NodeProviderResult result = fetchResultByBlueId(blueId);
        if (result.outcome() == NodeProviderOutcome.FOUND) {
            return result.nodes();
        }
        if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw new IllegalArgumentException(result.diagnostic().orElse(
                    "Provider returned invalid evidence for " + blueId));
        }
        if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
            throw new ProviderUnavailableException(
                    blueId,
                    result.diagnostic().orElse(
                            "Provider unavailable for " + blueId));
        }
        return null;
    }

    @Override
    public NodeProviderResult fetchResultByBlueId(String blueId) {
        for (NodeProvider provider : nodeProviders) {
            NodeProviderResult result = provider.fetchResultByBlueId(blueId);
            if (result.outcome() != NodeProviderOutcome.NOT_FOUND) {
                return result;
            }
        }
        return NodeProviderResult.notFound();
    }

    /**
     * Returns the immutable configured provider snapshot retained by this
     * chain.
     *
     * @return unmodifiable providers in lookup order
     */
    public List<NodeProvider> getNodeProviders() {
        return nodeProviders;
    }
}
