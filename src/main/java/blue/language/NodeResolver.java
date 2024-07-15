package blue.language;

import blue.language.model.Node;
import blue.language.utils.limits.Limits;

public interface NodeResolver {
    Node resolve(Node node, Limits limits);

    default Node resolve(Node node) {
        return resolve(node, Limits.NO_LIMITS);
    }
}