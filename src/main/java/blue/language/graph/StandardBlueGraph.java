package blue.language.graph;

import blue.language.BlueOperationLimits;
import blue.language.BlueOperationResult;
import blue.language.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeSpecializer;

import java.util.Objects;

/**
 * Default exact graph service over one selected provider and resolver.
 *
 * <p>The surrounding runtime remains responsible for operation admission,
 * configuration generations, caches, and close behavior. This service owns
 * only graph calculations and can therefore be shared by pure Language and
 * aggregate compatibility compositions.</p>
 */
public final class StandardBlueGraph implements BlueGraph {

    private final NodeExpansionEngine expansionEngine;
    private final NodeSpecializer specializer;

    /**
     * Creates a graph service for one runtime configuration.
     *
     * @param nodeProvider verified provider selected by the runtime
     * @param resolver complete resolver used to validate specialization
     */
    public StandardBlueGraph(
            NodeProvider nodeProvider, NodeResolver resolver) {
        this.expansionEngine = new NodeExpansionEngine(
                Objects.requireNonNull(nodeProvider, "nodeProvider"));
        this.specializer = new NodeSpecializer(
                Objects.requireNonNull(resolver, "resolver"));
    }

    @Override
    public Node expand(Node source) {
        return expansionEngine.expand(source);
    }

    @Override
    public BlueOperationResult<Node> expandLimited(
            Node source, BlueOperationLimits limits) {
        return expansionEngine.expandLimited(source, limits);
    }

    @Override
    public Node collapse(Node exactInput) {
        if (exactInput == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        return new Node().blueId(
                BlueIdCalculator.calculateBlueId(exactInput));
    }

    @Override
    public Node specialize(Node type, Node overlay) {
        return specializer.specialize(type, overlay);
    }
}
