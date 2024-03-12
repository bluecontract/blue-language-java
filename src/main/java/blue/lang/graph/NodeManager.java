package blue.lang.graph;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NodeManager {

    private final Map<String, Node> nodes;
    private final NodeProcessor nodeProcessor;

    public NodeManager(List<Node> nodeList, NodeProcessor nodeProcessor) {
        this.nodes = nodeList.stream()
                .collect(Collectors.toMap(
                        Node::getName,
                        node -> node
                ));
        this.nodeProcessor = nodeProcessor;
    }

    public Node getNode(String name) {
        return nodes.get(name);
    }

    public Node copyNode(Node node) {
        return null;
    }

    public Node newNode() {
        return new Node();
    }

    public NodeProcessor getNodeMergingProcessor() {
        return nodeProcessor;
    }

    public NodeReferenceResolver getReferenceResolver() {
        return null;
    }

}