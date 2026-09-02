package blue.language.matching;

import blue.language.api.BlueCachePolicy;
import blue.language.merge.TypeEvidenceResolution;
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
     * @throws NullPointerException if {@code source} is null
     * @throws IllegalStateException if the runtime is closed
     */
    Node preprocessForMatching(Node source);

    /**
     * Expands the demanded part of a mutable candidate in place.
     *
     * @param source mutable candidate to expand
     * @param limits target-driven expansion limits
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the candidate contains a malformed
     *         reference
     * @throws IllegalStateException if the runtime is closed
     */
    void expandForMatching(Node source, ResolutionLimits limits);

    /**
     * Resolves a candidate under the supplied target-driven limits while
     * retaining the canonical type identities issued by that same resolver
     * invocation.
     *
     * @param source candidate to resolve
     * @param limits target-driven resolution limits
     * @return immutable resolved candidate and invocation-local evidence
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the candidate contains invalid
     *         reference or type metadata
     * @throws IllegalStateException if the runtime is closed or exact type
     *         evidence cannot be established
     */
    TypeEvidenceResolution resolveTypeEvidenceForMatching(
            Node source,
            ResolutionLimits limits);

    /**
     * Materializes one pure type reference through a verified exact-content
     * boundary.
     *
     * @param reference pure reference whose identity must select the result
     * @return resolved reference content and invocation-local type identity
     *         evidence, or {@code null} when unavailable
     * @throws NullPointerException if {@code reference} is null
     * @throws IllegalArgumentException if {@code reference} is not a pure
     *         valid reference
     * @throws IllegalStateException if the runtime is closed or returned
     *         content cannot be verified exactly
     */
    TypeEvidenceResolution materializeTypeReferenceForMatching(
            FrozenNode reference);
}
