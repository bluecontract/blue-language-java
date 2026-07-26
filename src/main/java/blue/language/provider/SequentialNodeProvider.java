package blue.language.provider;

import blue.language.model.Node;
import blue.language.NodeProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class SequentialNodeProvider implements NodeProvider {
    private List<NodeProvider> nodeProviders;

    public SequentialNodeProvider(List<NodeProvider> nodeProviders) {
        this.nodeProviders = nodeProviders;
    }

    public SequentialNodeProvider(NodeProvider... nodeProviders) {
        this.nodeProviders = Arrays.asList(nodeProviders);
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
            throw new IllegalStateException(result.diagnostic().orElse(
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

    public List<NodeProvider> getNodeProviders() {
        return nodeProviders;
    }
}
