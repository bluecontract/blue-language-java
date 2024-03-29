package blue.lang;

import blue.lang.model.limits.Limits;

public interface NodeResolver {
    Node resolve(Node node, Limits limits);

    default Node resolve(Node node) {
        return resolve(node, Limits.NO_LIMITS);
    }
}