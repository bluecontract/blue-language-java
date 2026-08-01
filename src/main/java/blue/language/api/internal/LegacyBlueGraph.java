package blue.language.api.internal;

import blue.language.Blue;
import blue.language.BlueOperationLimits;
import blue.language.BlueOperationResult;
import blue.language.graph.BlueGraph;
import blue.language.model.Node;

import java.util.Objects;

import static blue.language.utils.Properties.OBJECT_BLUE;

/** Focused graph adapter over the compatibility runtime. */
public final class LegacyBlueGraph implements BlueGraph {

    private final Blue blue;

    public LegacyBlueGraph(Blue blue) {
        this.blue = Objects.requireNonNull(blue, OBJECT_BLUE);
    }

    @Override
    public Node expand(Node source) {
        return blue.expand(source);
    }

    @Override
    public BlueOperationResult<Node> expandLimited(
            Node source, BlueOperationLimits limits) {
        return blue.expandLimited(source, limits);
    }

    @Override
    public Node collapse(Node exactInput) {
        return blue.collapse(exactInput);
    }

    @Override
    public Node specialize(Node type, Node overlay) {
        return blue.specialize(type, overlay);
    }
}
