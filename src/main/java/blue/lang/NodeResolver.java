package blue.lang;

import blue.lang.model.limits.Limits;
import blue.lang.model.limits.LimitsInterface;

public interface NodeResolver {
    Node resolve(Node node, LimitsInterface limits);

    default Node resolve(Node node) {
        return resolve(node, Limits.NO_LIMITS);
    }
}