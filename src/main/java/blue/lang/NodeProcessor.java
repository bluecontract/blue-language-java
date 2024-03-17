package blue.lang;

import blue.lang.Resolver;

public interface NodeProcessor {
    void process(Node target, Node source, Resolver resolver);
}