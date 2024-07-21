package blue.language;


import blue.language.model.Node;

import java.util.List;

public interface NodeProvider {
    List<Node> fetchByBlueId(String blueId);

    default Node fetchFirstByBlueId(String blueId) {
        List<Node> nodes = fetchByBlueId(blueId);
        if (nodes != null && !nodes.isEmpty()) {
            return nodes.get(0);
        }
        return null;
    }
}