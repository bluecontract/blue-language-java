package blue.language.resolve;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.model.Node;

import java.util.Collection;

/** Establishes complete type-derived meaning and author-facing minimizations. */
public interface BlueResolution {

    /** Resolves a Source Document completely. */
    Node resolve(Node source);

    /** Resolves demanded content without conflating incomplete with absent. */
    BlueOperationResult<Node> resolveLimited(
            Node source, BlueOperationLimits limits);

    /** Resolves while retaining authored content at the supplied pointers. */
    Node resolvePreservingPaths(
            Node source, Collection<String> preservedPaths);

    /** Produces an ordinary smaller Source overlay with the same meaning. */
    Node minimize(Node source);

    /** Tests the Language subtype relation after complete resolution. */
    boolean isSubtype(Node candidateType, Node superType);
}
