package blue.language;

import blue.language.matching.MatchingRuntime;
import blue.language.model.Node;
import blue.language.provider.SourceContentVerificationRuntime;

/**
 * Narrow Language runtime capability required by downstream semantic hosts.
 *
 * <p>The contract exposes only Language-owned provider, cache, identity, and
 * matching operations. It deliberately excludes mapping, Contracts,
 * conformance, and aggregate-facade lifecycle so lower modules can consume a
 * configured Language runtime without depending on an aggregate facade.</p>
 */
public interface LanguageRuntimeAccess extends MatchingRuntime,
        SourceContentVerificationRuntime {

    /** Returns the runtime's verified provider graph. */
    NodeProvider getNodeProvider();

    /** Returns immutable bounds for runtime-owned derived caches. */
    BlueCachePolicy cachePolicy();

    /** Produces the canonical identity input for one authored Source value. */
    Node canonicalize(Node source);

    /** Calculates the Content BlueId of one authored Source document. */
    String calculateSourceDocumentBlueId(Node source);
}
