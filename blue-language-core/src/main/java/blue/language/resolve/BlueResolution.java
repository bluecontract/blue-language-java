package blue.language.resolve;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.model.Node;

import java.util.Collection;

/** Establishes complete type-derived meaning and author-facing minimizations. */
public interface BlueResolution {

    /**
     * Resolves a Source Document completely.
     * Enforces completed-value presence and payload obligations regardless of
     * inline/reference spelling. Use {@link #resolveDefinition(Node)} when
     * preparing a declaration with obligations for future instances.
     *
     * @param source authored Source Document
     * @return completely resolved value
     */
    Node resolve(Node source);

    /**
     * Prepares a definition, checking fixed content and known constraints while
     * retaining obligations that require a future instance payload. The returned
     * graph is not a completed-value certificate. Neither this goal nor a sample
     * payload is added to content or identity. The caller's source is unchanged.
     *
     * @param source authored definition, including optional imports
     * @return resolved definition with retained schema obligations
     * @throws UnsupportedOperationException if an alternate implementation
     *         does not support definition preparation
     */
    default Node resolveDefinition(Node source) {
        throw new UnsupportedOperationException("Definition preparation is not supported");
    }

    /**
     * Resolves demanded content without conflating incomplete with absent.
     *
     * @param source authored Source Document
     * @param limits semantic-demand and reference-expansion limits
     * @return established resolved value or an explicit non-established outcome
     */
    BlueOperationResult<Node> resolveLimited(
            Node source, BlueOperationLimits limits);

    /**
     * Resolves while retaining authored content at the supplied pointers.
     *
     * @param source authored Source Document
     * @param preservedPaths RFC 6901 pointers whose authored content is retained
     * @return resolved value with the selected authored paths preserved
     */
    Node resolvePreservingPaths(
            Node source, Collection<String> preservedPaths);

    /**
     * Produces an ordinary smaller Source overlay with the same meaning.
     * Uses definition preparation; it is not a completed-value certificate.
     *
     * @param source authored Source Document
     * @return minimized Source overlay
     */
    Node minimize(Node source);

    /**
     * Tests the Language subtype relation after complete resolution.
     *
     * @param candidateType candidate subtype definition
     * @param superType prospective supertype definition
     * @return whether {@code candidateType} is a subtype of {@code superType}
     */
    boolean isSubtype(Node candidateType, Node superType);
}
