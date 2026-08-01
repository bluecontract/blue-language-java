package blue.language.matching;

import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.limits.Limits;

/**
 * Minimal Language runtime surface required by mutable and immutable matching.
 *
 * <p>The matching implementation depends on this capability instead of the
 * aggregate {@code Blue} facade. Implementations retain responsibility for
 * provider verification and for applying their configured global limits.</p>
 */
public interface MatchingRuntime {

    /** Returns the bounds used by matcher-owned derived caches. */
    BlueCachePolicy matchingCachePolicy();

    /** Applies the runtime's configured preprocessing rules to a source graph. */
    Node preprocessForMatching(Node source);

    /** Expands the demanded part of a mutable candidate in place. */
    void expandForMatching(Node source, Limits limits);

    /** Resolves a candidate under the supplied target-driven limits. */
    Node resolveForMatching(Node source, Limits limits);

    /**
     * Materializes one pure type reference through a verified exact-content
     * boundary.
     *
     * @param reference pure reference whose identity must select the result
     * @return resolved type definition, or {@code null} when unavailable
     */
    FrozenNode materializeTypeReferenceForMatching(FrozenNode reference);
}
