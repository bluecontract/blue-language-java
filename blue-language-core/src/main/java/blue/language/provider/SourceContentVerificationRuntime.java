package blue.language.provider;

import blue.language.model.Node;

import java.util.Map;

/**
 * Narrow host boundary required to verify environment-bound Source content.
 *
 * <p>The provider layer depends on this contract instead of an aggregate
 * facade. Implementations must freeze their preprocessing configuration and
 * apply the released Language source canonicalization strategy independently
 * of caller-selected traversal limits or merge customizations.</p>
 */
public interface SourceContentVerificationRuntime {

    /** Returns the exact Language version implemented by this runtime. */
    String languageVersion();

    /** Returns an immutable snapshot of explicit preprocessing aliases. */
    Map<String, String> preprocessingAliases();

    /** Canonicalizes one authored source under the released identity strategy. */
    Node canonicalizeSourceContent(Node source);

    /**
     * Returns the exact canonical core-registry identity bound to this
     * runtime.
     *
     * <p>The fail-closed default preserves binary compatibility for existing
     * implementations while preventing them from silently accepting Source
     * evidence without an explicit registry binding.</p>
     *
     * @return canonical registry package identity
     */
    default String canonicalRegistryIdentity() {
        throw new UnsupportedOperationException(
                "Source-content verification requires an explicit canonical registry identity.");
    }

}
