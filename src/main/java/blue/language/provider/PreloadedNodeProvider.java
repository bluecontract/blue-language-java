package blue.language.provider;

import blue.language.model.Node;

import java.util.*;

/**
 * Base for eager providers that additionally index stored identities by
 * human-readable node name.
 */
public abstract class PreloadedNodeProvider extends AbstractNodeProvider {

    /** Creates an empty name-indexed provider for subclass loading. */
    public PreloadedNodeProvider() {
    }

    /** Mutable insertion index maintained by subclasses during loading. */
    protected Map<String, List<String>> nameToBlueIdsMap = new HashMap<>();

    /**
     * Returns the uniquely named node.
     *
     * @param name indexed node name
     * @return unique node, or empty when the name is absent
     * @throws IllegalStateException when more than one identity has that name
     */
    public Optional<Node> findNodeByName(String name) {
        List<String> blueIds = nameToBlueIdsMap.get(name);
        if (blueIds == null) {
            return Optional.empty();
        }
        if (blueIds.size() > 1) {
            throw new IllegalStateException("Multiple nodes found with name: " + name);
        }
        List<Node> nodes = fetchByBlueId(blueIds.get(0));
        return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.get(0));
    }

    /**
     * Returns all nodes registered under a name.
     *
     * @param name indexed node name
     * @return matching nodes, or an empty list
     */
    public List<Node> findAllNodesByName(String name) {
        List<String> blueIds = nameToBlueIdsMap.get(name);
        if (blueIds == null) {
            return Collections.emptyList();
        }
        List<Node> result = new ArrayList<>();
        for (String blueId : blueIds) {
            result.addAll(fetchByBlueId(blueId));
        }
        return result;
    }

    /**
     * Adds an identity to the mutable name index.
     *
     * @param name node name
     * @param blueId stored identity
     */
    protected void addToNameMap(String name, String blueId) {
        nameToBlueIdsMap.computeIfAbsent(name, k -> new ArrayList<>()).add(blueId);
    }
}
