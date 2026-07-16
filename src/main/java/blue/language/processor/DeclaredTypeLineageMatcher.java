package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static blue.language.utils.Properties.CORE_TYPE_BLUE_IDS;

/**
 * Matches declared type identity and explicit declared ancestry only.
 */
final class DeclaredTypeLineageMatcher {

    private static final int CACHE_ENTRY_LIMIT = 256;
    private static final int CACHED_ANCESTRY_LIMIT = 256;

    private final NodeProvider provider;
    private final Map<String, Set<String>> ancestryByCandidate =
            new LinkedHashMap<String, Set<String>>(CACHE_ENTRY_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                    return size() > CACHE_ENTRY_LIMIT;
                }
            };

    DeclaredTypeLineageMatcher(NodeProvider provider) {
        this.provider = provider;
    }

    boolean isSameOrDescendant(Node candidateType, Node expectedType) {
        if (candidateType == null || expectedType == null) {
            return false;
        }
        String candidateId = candidateType.getBlueId();
        String expectedId = expectedType.getBlueId();
        if (candidateId == null || expectedId == null) {
            return false;
        }

        BlueIds.requireBlueIdOrCyclicMember(candidateId, "event.type.blueId");
        BlueIds.requireBlueIdOrCyclicMember(expectedId, "expectedType.blueId");
        if (candidateId.equals(expectedId)) {
            return true;
        }

        Set<String> ancestry = cachedAncestry(candidateId);
        if (ancestry == null) {
            ancestry = buildCompleteAncestry(candidateId);
            if (ancestry == null) {
                return false;
            }
            cacheIfBounded(candidateId, ancestry);
        }
        return ancestry.contains(expectedId);
    }

    int cacheSize() {
        synchronized (ancestryByCandidate) {
            return ancestryByCandidate.size();
        }
    }

    private Set<String> cachedAncestry(String candidateId) {
        synchronized (ancestryByCandidate) {
            return ancestryByCandidate.get(candidateId);
        }
    }

    private Set<String> buildCompleteAncestry(String candidateId) {
        if (provider == null) {
            return null;
        }

        LinkedHashSet<String> ancestry = new LinkedHashSet<String>();
        String currentId = candidateId;
        while (true) {
            if (!ancestry.add(currentId)) {
                throw typeCycle(currentId, ancestry.size());
            }
            if (CORE_TYPE_BLUE_IDS.contains(currentId)) {
                return immutable(ancestry);
            }

            List<Node> definitions = provider.fetchByBlueId(currentId);
            if (definitions == null || definitions.isEmpty()) {
                return null;
            }
            if (definitions.size() != 1) {
                throw new IllegalStateException(String.format(
                        "Expected a single node for declared type with blueId '%s', but found multiple.",
                        currentId));
            }

            Node definition = definitions.get(0);
            if (definition == null || definition.isReferenceOnly()) {
                return null;
            }
            Node parent = definition.getType();
            if (parent == null) {
                return immutable(ancestry);
            }
            String parentId = parent.getBlueId();
            if (parentId == null) {
                return null;
            }
            BlueIds.requireBlueIdOrCyclicMember(parentId, "declaredTypeAncestry.type.blueId");
            currentId = parentId;
        }
    }

    private void cacheIfBounded(String candidateId, Set<String> ancestry) {
        if (ancestry.size() > CACHED_ANCESTRY_LIMIT) {
            return;
        }
        synchronized (ancestryByCandidate) {
            Set<String> existing = ancestryByCandidate.get(candidateId);
            if (existing == null) {
                ancestryByCandidate.put(candidateId, ancestry);
            }
        }
    }

    private static Set<String> immutable(LinkedHashSet<String> ancestry) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(ancestry));
    }

    private static IllegalStateException typeCycle(String repeatedId, int uniqueTypeCount) {
        return new IllegalStateException("Type cycle in declared type ancestry: "
                + repeatedId + " was revisited after " + uniqueTypeCount
                + " unique type declarations.");
    }
}
