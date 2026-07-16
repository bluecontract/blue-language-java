package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.FrozenTypeMatcher;
import blue.language.utils.Types;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared matcher facade for contract-level event patterns.
 */
public final class ContractMatchingService {

    private static final NodeProvider NO_REFERENCES = blueId -> null;
    private static final int EVENT_SUBTYPE_CACHE_LIMIT = 256;

    private final Blue blue;
    private final FrozenTypeMatcher matcher;
    private final Map<ReferenceSubtypeKey, Boolean> eventSubtypeCache =
            new LinkedHashMap<ReferenceSubtypeKey, Boolean>(EVENT_SUBTYPE_CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ReferenceSubtypeKey, Boolean> eldest) {
                    return size() > EVENT_SUBTYPE_CACHE_LIMIT;
                }
            };

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
        ReferenceSubtypeKey cacheKey = referenceSubtypeKey(eventType, expectedType);
        if (cacheKey != null) {
            synchronized (eventSubtypeCache) {
                Boolean cached = eventSubtypeCache.get(cacheKey);
                if (cached != null) {
                    return cached;
                }
            }
        }
        boolean result = blue != null
                ? blue.isNodeSubtypeOf(eventType, expectedType)
                : Types.isSubtype(eventType, expectedType, NO_REFERENCES);
        if (cacheKey != null) {
            synchronized (eventSubtypeCache) {
                eventSubtypeCache.put(cacheKey, result);
            }
        }
        return result;
    }

    private ReferenceSubtypeKey referenceSubtypeKey(Node eventType, Node expectedType) {
        if (!eventType.isReferenceOnly() || !expectedType.isReferenceOnly()) {
            return null;
        }
        String eventBlueId = eventType.getBlueId();
        String expectedBlueId = expectedType.getBlueId();
        if (eventBlueId == null || expectedBlueId == null || eventBlueId.equals(expectedBlueId)) {
            return null;
        }
        return new ReferenceSubtypeKey(eventBlueId, expectedBlueId);
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

    private static final class ReferenceSubtypeKey {
        private final String eventBlueId;
        private final String expectedBlueId;

        private ReferenceSubtypeKey(String eventBlueId, String expectedBlueId) {
            this.eventBlueId = eventBlueId;
            this.expectedBlueId = expectedBlueId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ReferenceSubtypeKey)) {
                return false;
            }
            ReferenceSubtypeKey that = (ReferenceSubtypeKey) other;
            return eventBlueId.equals(that.eventBlueId)
                    && expectedBlueId.equals(that.expectedBlueId);
        }

        @Override
        public int hashCode() {
            return 31 * eventBlueId.hashCode() + expectedBlueId.hashCode();
        }
    }
}
