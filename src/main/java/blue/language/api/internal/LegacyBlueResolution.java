package blue.language.api.internal;

import blue.language.Blue;
import blue.language.BlueOperationLimits;
import blue.language.BlueOperationResult;
import blue.language.model.Node;
import blue.language.resolve.BlueResolution;

import java.util.Collection;
import java.util.Objects;

import static blue.language.utils.Properties.OBJECT_BLUE;

/** Focused resolution adapter over the compatibility runtime. */
public final class LegacyBlueResolution implements BlueResolution {

    private final Blue blue;

    public LegacyBlueResolution(Blue blue) {
        this.blue = Objects.requireNonNull(blue, OBJECT_BLUE);
    }

    @Override
    public Node resolve(Node source) {
        return blue.resolve(blue.preprocess(source));
    }

    @Override
    public BlueOperationResult<Node> resolveLimited(
            Node source, BlueOperationLimits limits) {
        return blue.resolveLimited(source, limits);
    }

    @Override
    public Node resolvePreservingPaths(
            Node source, Collection<String> preservedPaths) {
        return blue.resolvePreservingPaths(
                blue.preprocess(source), preservedPaths);
    }

    @Override
    public Node minimize(Node source) {
        return blue.minimize(source);
    }

    @Override
    public boolean isSubtype(Node candidateType, Node superType) {
        return blue.isNodeSubtypeOf(candidateType, superType);
    }
}
