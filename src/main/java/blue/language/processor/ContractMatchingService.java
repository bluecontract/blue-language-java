package blue.language.processor;

import blue.language.Blue;
import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.FrozenTypeMatcher;

/**
 * Shared matcher facade for contract-level event patterns.
 */
public final class ContractMatchingService {

    private final Blue blue;
    private final BlueCachePolicy cachePolicy;
    private final FrozenTypeMatcher matcher;
    private final DeclaredTypeLineageMatcher declaredTypeLineageMatcher;

    public ContractMatchingService() {
        this(null);
    }

    public ContractMatchingService(Blue blue) {
        this.blue = blue;
        this.cachePolicy = blue != null
                ? blue.cachePolicy()
                : BlueCachePolicy.boundedDefaults();
        this.matcher = new FrozenTypeMatcher(blue);
        this.declaredTypeLineageMatcher = new DeclaredTypeLineageMatcher(
                blue != null ? blue.getNodeProvider() : null,
                cachePolicy);
    }

    Blue blue() {
        return blue;
    }

    boolean eventDeclaredTypeIsSameOrDescendantOf(Node eventType, Node expectedType) {
        return declaredTypeLineageMatcher.isSameOrDescendant(eventType, expectedType);
    }

    int declaredTypeLineageCacheSize() {
        return declaredTypeLineageMatcher.cacheSize();
    }

    BlueCachePolicy cachePolicy() {
        return cachePolicy;
    }

    int matcherCacheSize() {
        return matcher.cacheEntryCount();
    }

    int cacheEntryCount() {
        return matcher.cacheEntryCount() + declaredTypeLineageMatcher.cacheSize();
    }

    long cacheWeightBytes() {
        long matcherWeight = matcher.cacheWeightBytes();
        long lineageWeight = declaredTypeLineageMatcher.cacheWeightBytes();
        return Long.MAX_VALUE - matcherWeight < lineageWeight
                ? Long.MAX_VALUE
                : matcherWeight + lineageWeight;
    }

    /** Releases matching, reference-resolution, and declared-lineage caches. */
    public void clearCaches() {
        matcher.clearCaches();
        declaredTypeLineageMatcher.clearCaches();
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
