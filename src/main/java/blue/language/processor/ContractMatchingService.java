package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.FrozenTypeMatcher;

/**
 * Shared matcher facade for contract-level event patterns.
 */
public final class ContractMatchingService {

    private final FrozenTypeMatcher matcher;

    public ContractMatchingService() {
        this(null);
    }

    public ContractMatchingService(Blue blue) {
        this.matcher = new FrozenTypeMatcher(blue);
    }

    public boolean matches(FrozenNode event, FrozenNode pattern) {
        if (pattern == null) {
            return true;
        }
        return matcher.matchesType(event, pattern);
    }

    public boolean matches(Node event, Node pattern) {
        if (pattern == null) {
            return true;
        }
        if (event == null) {
            return false;
        }
        return matches(FrozenNode.fromResolvedNode(event), FrozenNode.fromResolvedNode(pattern));
    }
}
