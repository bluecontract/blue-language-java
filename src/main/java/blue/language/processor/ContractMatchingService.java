package blue.language.processor;

import blue.language.Blue;
import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.FrozenTypeMatcher;

/**
 * Shared, bounded matcher facade for contract-level event patterns.
 *
 * <p>Structural matching and verified declared-type lineage use separate
 * caches under one {@link BlueCachePolicy}. A service without a {@link Blue}
 * context can match exact inline values but fails closed when provider-backed
 * ancestry is required.</p>
 */
public final class ContractMatchingService {

    private final Blue blue;
    private final BlueCachePolicy cachePolicy;
    private final FrozenTypeMatcher matcher;
    private final DeclaredTypeLineageMatcher declaredTypeLineageMatcher;

    /**
     * Creates a bounded matcher with no provider-backed type ancestry.
     */
    public ContractMatchingService() {
        this(null);
    }

    /**
     * Creates a bounded matcher using the supplied Blue resolution context.
     *
     * @param blue resolution context, or {@code null} to disable provider-backed ancestry
     */
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

    /**
     * Matches immutable values; a null pattern is the unconditional pattern.
     *
     * @param event frozen event value, possibly {@code null}
     * @param pattern frozen pattern, or {@code null} for an unconditional match
     * @return whether the event satisfies the pattern
     */
    public boolean matches(FrozenNode event, FrozenNode pattern) {
        if (pattern == null) {
            return true;
        }
        return matcher.matchesType(event, pattern);
    }

    /**
     * Defensively freezes mutable values before matching. A non-null pattern
     * never matches a null event.
     *
     * @param event mutable event value, possibly {@code null}
     * @param pattern mutable pattern, or {@code null} for an unconditional match
     * @return whether the event satisfies the pattern
     */
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
