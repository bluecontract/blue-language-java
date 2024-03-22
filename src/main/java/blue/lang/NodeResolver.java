package blue.lang;

import blue.lang.model.limits.LimitsInterface;

public interface NodeResolver {
    Node resolve(Node node, LimitsInterface limits);
}