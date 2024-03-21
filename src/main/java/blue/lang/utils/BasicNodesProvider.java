package blue.lang.utils;

import blue.lang.Node;
import blue.lang.NodeProvider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BasicNodesProvider implements NodeProvider {

    private Map<String, Node> blueIdToNodeMap;

    public BasicNodesProvider(Collection<Node> nodes) {
        this.blueIdToNodeMap = nodes.stream()
                .collect(Collectors.toMap(BlueIdCalculator::calculateBlueId, Function.identity()));
    }

    @Override
    public Node fetchByBlueId(String blueId) {
        Optional<Node> result = Optional.ofNullable(blueIdToNodeMap.get(blueId));
        return result.map(Node::clone).orElse(null);
    }

}
