package blue.lang;

import blue.lang.model.Limits;

public interface NodeResolver {
    Node resolve(Node node, Limits limits);
}