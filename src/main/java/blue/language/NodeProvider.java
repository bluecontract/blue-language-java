package blue.language;


import blue.language.model.Node;
import blue.language.provider.NodeProviderResult;

import java.util.List;

/**
 * Lookup boundary for canonical Blue content addressed by BlueId.
 *
 * <p>Implementations may return multiple nodes for compound provider formats.
 * A miss is represented by an empty result. Runtime code that requires
 * identity evidence wraps providers with verification rather than trusting a
 * returned node solely because it was stored under the requested key.</p>
 */
public interface NodeProvider {

    /**
     * Fetches canonical candidates for an exact BlueId.
     *
     * @param blueId exact content identity to look up
     * @return matching candidates, or null/an empty list when legacy content
     *         is absent
     */
    List<Node> fetchByBlueId(String blueId);

    /**
     * Adapts the legacy list result to an outcome that distinguishes a
     * definitive miss from successful content.
     *
     * @param blueId exact content identity to look up
     * @return transport-neutral lookup result
     */
    default NodeProviderResult fetchResultByBlueId(String blueId) {
        List<Node> nodes = fetchByBlueId(blueId);
        return nodes == null || nodes.isEmpty()
                ? NodeProviderResult.notFound()
                : NodeProviderResult.found(nodes);
    }

    /**
     * Returns the first candidate supplied for an identity.
     *
     * @param blueId exact content identity to look up
     * @return first matching candidate, or {@code null} when the provider
     *         misses
     */
    default Node fetchFirstByBlueId(String blueId) {
        List<Node> nodes = fetchByBlueId(blueId);
        if (nodes != null && !nodes.isEmpty()) {
            return nodes.get(0);
        }
        return null;
    }
}
