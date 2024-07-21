package blue.language.provider;

import blue.language.model.Node;

import java.util.*;

public abstract class PreloadedNodeProvider extends AbstractNodeProvider {
    protected Map<String, List<String>> nameToBlueIdsMap = new HashMap<>();

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

    protected void addToNameMap(String name, String blueId) {
        nameToBlueIdsMap.computeIfAbsent(name, k -> new ArrayList<>()).add(blueId);
    }
}