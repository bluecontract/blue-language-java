package blue.lang;

public interface NodeProcessor {
    void process(Node target, Node source, NodeProvider nodeProvider);
}