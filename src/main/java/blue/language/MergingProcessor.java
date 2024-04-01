package blue.language;

import blue.language.model.Node;

public interface MergingProcessor {
    void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver);
}