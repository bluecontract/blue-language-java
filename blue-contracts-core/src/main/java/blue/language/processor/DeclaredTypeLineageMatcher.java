package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.identity.BlueIds;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_IDS;

/**
 * Verifies same-or-descendant relationships from exact declared type edges.
 *
 * <p>No structural inference is permitted. Direct-parent facts are admitted
 * only from provider-verified content and held in a bounded weighted LRU;
 * unavailable or cyclic evidence never becomes a positive cache fact.</p>
 */
final class DeclaredTypeLineageMatcher {

    static final int CACHE_INITIAL_CAPACITY = 64;
    static final int CACHE_ENTRY_LIMIT = 4_096;

    private final NodeProvider provider;
    private final int maximumEntries;
    private final long maximumWeightBytes;
    private final long maximumEntryWeightBytes;
    private final Map<String, DirectParentFact> directParentByType =
            new LinkedHashMap<String, DirectParentFact>(CACHE_INITIAL_CAPACITY, 0.75f, true);
    private long currentWeightBytes;

    DeclaredTypeLineageMatcher(NodeProvider provider) {
        this(provider, BlueCachePolicy.boundedDefaults());
    }

    DeclaredTypeLineageMatcher(NodeProvider provider, BlueCachePolicy cachePolicy) {
        this.provider = provider;
        BlueCachePolicy policy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.maximumEntries = policy.conformancePlanMaxEntries();
        this.maximumWeightBytes = policy.conformancePlanMaxWeightBytes();
        this.maximumEntryWeightBytes = Math.min(
                policy.maximumDerivedEntryWeightBytes(), maximumWeightBytes);
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

        return hasExpectedInCompleteAncestry(candidateId, expectedId);
    }

    int cacheSize() {
        synchronized (directParentByType) {
            return directParentByType.size();
        }
    }

    long cacheWeightBytes() {
        synchronized (directParentByType) {
            return currentWeightBytes;
        }
    }

    void clearCaches() {
        synchronized (directParentByType) {
            directParentByType.clear();
            currentWeightBytes = 0L;
        }
    }

    private boolean hasExpectedInCompleteAncestry(String candidateId, String expectedId) {
        if (provider == null) {
            return false;
        }

        LinkedHashSet<String> ancestry = new LinkedHashSet<String>();
        String currentId = candidateId;
        while (true) {
            if (!ancestry.add(currentId)) {
                throw typeCycle(currentId, ancestry.size());
            }
            if (CORE_TYPE_BLUE_IDS.contains(currentId)) {
                return ancestry.contains(expectedId);
            }

            DirectParentFact fact = cachedFact(currentId);
            if (fact == null) {
                fact = fetchFact(currentId);
                if (fact == null) {
                    return false;
                }
                fact = cacheFact(currentId, fact);
            }
            if (fact.isTerminal()) {
                return ancestry.contains(expectedId);
            }
            currentId = fact.directParentId;
        }
    }

    private DirectParentFact fetchFact(String currentId) {
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
            return DirectParentFact.NO_DECLARED_PARENT;
        }
        String parentId = parent.getBlueId();
        if (parentId == null) {
            return DirectParentFact.ANONYMOUS_DECLARED_PARENT;
        }
        BlueIds.requireBlueIdOrCyclicMember(parentId, "declaredTypeAncestry.type.blueId");
        return DirectParentFact.parent(parentId);
    }

    private DirectParentFact cachedFact(String declaredTypeId) {
        synchronized (directParentByType) {
            return directParentByType.get(declaredTypeId);
        }
    }

    private DirectParentFact cacheFact(String declaredTypeId, DirectParentFact fact) {
        synchronized (directParentByType) {
            DirectParentFact existing = directParentByType.get(declaredTypeId);
            if (existing != null) {
                return existing;
            }
            long weight = factWeight(declaredTypeId, fact);
            if (weight > maximumEntryWeightBytes || weight > maximumWeightBytes) {
                return fact;
            }
            directParentByType.put(declaredTypeId, fact);
            currentWeightBytes += weight;
            evictToBounds();
            return fact;
        }
    }

    private void evictToBounds() {
        Iterator<Map.Entry<String, DirectParentFact>> iterator =
                directParentByType.entrySet().iterator();
        while ((directParentByType.size() > maximumEntries
                || currentWeightBytes > maximumWeightBytes) && iterator.hasNext()) {
            Map.Entry<String, DirectParentFact> eldest = iterator.next();
            currentWeightBytes -= factWeight(eldest.getKey(), eldest.getValue());
            iterator.remove();
        }
    }

    private long factWeight(String declaredTypeId, DirectParentFact fact) {
        long weight = 112L + retainedString(declaredTypeId);
        return weight + retainedString(fact.directParentId);
    }

    private long retainedString(String value) {
        return value != null ? 48L + 2L * value.length() : 0L;
    }

    private static IllegalStateException typeCycle(String repeatedId, int uniqueTypeCount) {
        return new IllegalStateException("Type cycle in declared type ancestry: "
                + repeatedId + " was revisited after " + uniqueTypeCount
                + " unique type declarations.");
    }

    private static final class DirectParentFact {
        private static final DirectParentFact NO_DECLARED_PARENT =
                new DirectParentFact(null, TerminalKind.NO_DECLARED_PARENT);
        private static final DirectParentFact ANONYMOUS_DECLARED_PARENT =
                new DirectParentFact(null, TerminalKind.ANONYMOUS_DECLARED_PARENT);

        private final String directParentId;
        private final TerminalKind terminalKind;

        private DirectParentFact(String directParentId, TerminalKind terminalKind) {
            this.directParentId = directParentId;
            this.terminalKind = terminalKind;
        }

        private static DirectParentFact parent(String directParentId) {
            return new DirectParentFact(directParentId, null);
        }

        private boolean isTerminal() {
            return terminalKind != null;
        }
    }

    private enum TerminalKind {
        NO_DECLARED_PARENT,
        ANONYMOUS_DECLARED_PARENT
    }
}
