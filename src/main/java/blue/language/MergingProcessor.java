package blue.language;

public interface MergingProcessor {
    void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver);
}