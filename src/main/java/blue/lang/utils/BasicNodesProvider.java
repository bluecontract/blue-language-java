package blue.lang.utils;

import blue.lang.Node;
import blue.lang.NodeProvider;

import java.util.*;
import java.util.stream.Collectors;

import static blue.lang.utils.BlueIdCalculator.calculateBlueId;

public class BasicNodesProvider implements NodeProvider {

    private Map<String, List<Node>> blueIdToNodeMap;

    public BasicNodesProvider(Node... nodes) {
        this(Arrays.asList(nodes));
    }

    public BasicNodesProvider(Collection<Node> nodes) {
        this.blueIdToNodeMap = nodes.stream()
                .collect(Collectors.toMap(BlueIdCalculator::calculateBlueId, Collections::singletonList));
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        List<Node> nodes = blueIdToNodeMap.get(blueId);
        if (nodes == null)
            return null;

        return nodes.stream()
                .map(Node::clone)
                .collect(Collectors.toList());
    }

    public void addSingleNodes(Node... nodes) {
        Arrays.stream(nodes).forEach(node -> blueIdToNodeMap.put(calculateBlueId(node), Collections.singletonList(node)));
    }

    public void addNodesList(List<Node> list) {
        blueIdToNodeMap.put(calculateBlueId(list), list);
    }

}