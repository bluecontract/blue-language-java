package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.FrozenTypeMatcher;
import blue.language.utils.Types;

/**
 * Shared matcher facade for contract-level event patterns.
 */
public final class ContractMatchingService {

    private static final NodeProvider NO_REFERENCES = blueId -> null;

    private final Blue blue;
    private final FrozenTypeMatcher matcher;

    public ContractMatchingService() {
        this(null);
    }

    public ContractMatchingService(Blue blue) {
        this.blue = blue;
        this.matcher = new FrozenTypeMatcher(blue);
    }

    Blue blue() {
        return blue;
    }

    boolean eventTypeIsSubtypeOf(Node eventType, Node expectedType) {
        if (eventType == null || expectedType == null) {
            return false;
        }
        return blue != null
                ? blue.isNodeSubtypeOf(eventType, expectedType)
                : Types.isSubtype(eventType, expectedType, NO_REFERENCES);
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
