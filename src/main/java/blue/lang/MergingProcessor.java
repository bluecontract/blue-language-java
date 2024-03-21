package blue.lang;

public interface MergingProcessor {
    void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver);
}