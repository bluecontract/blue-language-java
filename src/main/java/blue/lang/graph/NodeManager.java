package blue.lang.graph;

public interface NodeManager {
    Node getNode(String name);
    Node copyNode(Node node);
    Node newNode();
    NodeProcessor getNodeMergingProcessor();
    NodeReferenceResolver getReferenceResolver();
}