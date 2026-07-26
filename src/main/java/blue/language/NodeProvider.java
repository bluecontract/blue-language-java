package blue.language;


import blue.language.model.Node;
import blue.language.provider.NodeProviderResult;

import java.util.List;

public interface NodeProvider {
    List<Node> fetchByBlueId(String blueId);

    default NodeProviderResult fetchResultByBlueId(String blueId) {
        List<Node> nodes = fetchByBlueId(blueId);
        return nodes == null || nodes.isEmpty()
                ? NodeProviderResult.notFound()
                : NodeProviderResult.found(nodes);
    }

    default Node fetchFirstByBlueId(String blueId) {
        List<Node> nodes = fetchByBlueId(blueId);
        if (nodes != null && !nodes.isEmpty()) {
            return nodes.get(0);
        }
        return null;
    }
}
