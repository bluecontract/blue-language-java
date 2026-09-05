package blue.language.runtime;

import blue.language.api.BlueCachePolicy;
import blue.language.matching.MatchingRuntime;
import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;
import blue.language.provider.NodeProvider;
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

    /**
     * Returns the runtime's verified provider graph.
     *
     * @return verified provider selected for the runtime
     */
    NodeProvider getNodeProvider();

    /**
     * Returns immutable bounds for runtime-owned derived caches.
     *
     * @return runtime cache policy
     */
    BlueCachePolicy cachePolicy();

    /**
     * Produces the canonical identity input for one authored Source value.
     *
     * @param source authored Source value
     * @return canonical identity input under the runtime's frozen environment
     */
    Node canonicalize(Node source);

    /**
     * Canonicalizes a Source declaration and retains the semantic graph from
     * that same resolution. This prepares definitions; it does not certify a
     * completed instance or discharge required-value obligations. Pure root
     * references remain opaque, including finalized cyclic member references.
     *
     * @param source authored Source declaration
     * @return canonical content and its resolver-owned semantic companion
     * @throws UnsupportedOperationException when this capability is unavailable
     */
    default ResolvedSnapshot canonicalizeWithEvidence(Node source) {
        throw new UnsupportedOperationException(
                "This Language runtime cannot retain canonicalization evidence");
    }

    /**
     * Calculates the Content BlueId of one authored Source document.
     *
     * @param source authored Source document
     * @return Content BlueId under the runtime's frozen environment
     */
    String calculateSourceDocumentBlueId(Node source);
}
