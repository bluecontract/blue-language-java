package blue.language.matching;

import blue.language.api.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.resolve.ResolutionLimits;

/**
 * Minimal Language runtime surface required by mutable and immutable matching.
 *
 * <p>The matching implementation depends on this capability instead of the
 * aggregate {@code Blue} facade. Implementations retain responsibility for
 * provider verification and for applying their configured global limits.</p>
 */
public interface MatchingRuntime {

    /**
     * Returns the bounds used by matcher-owned derived caches.
     *
     * @return immutable cache policy for matching-derived state
     */
    BlueCachePolicy matchingCachePolicy();

    /**
     * Applies the runtime's configured preprocessing rules to a source graph.
     *
     * @param source authored source graph
     * @return preprocessed graph used for matching
     */
    Node preprocessForMatching(Node source);

    /**
     * Expands the demanded part of a mutable candidate in place.
     *
     * @param source mutable candidate to expand
     * @param limits target-driven expansion limits
     */
    void expandForMatching(Node source, ResolutionLimits limits);

    /**
     * Resolves a candidate under the supplied target-driven limits.
     *
     * @param source candidate to resolve
     * @param limits target-driven resolution limits
     * @return resolved candidate
     */
    Node resolveForMatching(Node source, ResolutionLimits limits);

    /**
     * Materializes one pure type reference through a verified exact-content
     * boundary.
     *
     * @param reference pure reference whose identity must select the result
     * @return resolved type definition, or {@code null} when unavailable
     */
    FrozenNode materializeTypeReferenceForMatching(FrozenNode reference);
}
