package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIds;

import java.util.List;
import java.util.Objects;

/**
 * Filters configured provider lookups to syntactically possible BlueIds while
 * preserving the delegate provider graph for provenance-aware traversal.
 */
public final class PotentialBlueIdNodeProvider implements NodeProvider {

    private final NodeProvider delegate;

    public PotentialBlueIdNodeProvider(NodeProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        return acceptsBlueId(blueId) ? delegate.fetchByBlueId(blueId) : null;
    }

    public boolean acceptsBlueId(String blueId) {
        return BlueIds.isPotentialBlueId(blueId);
    }

    public NodeProvider delegate() {
        return delegate;
    }
}
