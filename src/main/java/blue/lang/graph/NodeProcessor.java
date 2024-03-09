package blue.lang.graph;

public interface NodeProcessor {
    void process(Node target, Node source, NodeManager nodeManager);
}