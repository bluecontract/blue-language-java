package blue.lang.graph;

import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
public class BasicNodeManager implements NodeManager {

    private final Map<String, BasicNode> nodes;
    private final NodeProcessor nodeProcessor;

    public BasicNodeManager(List<BasicNode> nodeList, NodeProcessor nodeProcessor) {
        this.nodes = nodeList.stream()
                .collect(Collectors.toMap(
                        BasicNode::getName,
                        node -> node
                ));
        this.nodeProcessor = nodeProcessor;
    }

    @Override
    public Node getNode(String name) {
        return nodes.get(name);
    }

    @Override
    public Node copyNode(Node node) {
        return null;
    }

    @Override
    public Node newNode() {
        return new BasicNode();
    }

    @Override
    public NodeProcessor getNodeMergingProcessor() {
        return nodeProcessor;
    }

    @Override
    public NodeReferenceResolver getReferenceResolver() {
        return null;
    }

}