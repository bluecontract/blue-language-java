package blue.lang.utils;

import blue.lang.Node;
import blue.lang.NodeProvider;

import java.util.List;
import java.util.Objects;

public class SequentialNodeProvider implements NodeProvider {
    private List<NodeProvider> nodeProviders;

    public SequentialNodeProvider(List<NodeProvider> nodeProviders) {
        this.nodeProviders = nodeProviders;
    }

    @Override
    public Node fetchByBlueId(String blueId) {
        return nodeProviders.stream()
                .map(provider -> provider.fetchByBlueId(blueId))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}