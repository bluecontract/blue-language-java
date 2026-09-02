package blue.language.merge;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Immutable resolver-issued evidence for one completely resolved reference.
 *
 * <p>The constructor is package-private so arbitrary callers cannot fabricate
 * cache-admissible evidence.</p>
 */
public final class VerifiedReferenceResolution {

    private final String requestedBlueId;
    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final CanonicalTypeIdentityIndex.EvidenceSnapshot
            canonicalTypeIdentityEvidence;

    VerifiedReferenceResolution(
            String requestedBlueId,
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            CanonicalTypeIdentityIndex.EvidenceSnapshot
                    canonicalTypeIdentityEvidence) {
        this.requestedBlueId = Objects.requireNonNull(
                requestedBlueId, "requestedBlueId");
        this.canonicalRoot = Objects.requireNonNull(
                canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(
                resolvedRoot, "resolvedRoot");
        this.canonicalTypeIdentityEvidence = Objects.requireNonNull(
                canonicalTypeIdentityEvidence,
                "canonicalTypeIdentityEvidence");
    }

    /**
     * Returns the exact BlueId requested from the resolver.
     *
     * @return requested exact BlueId
     */
    public String requestedBlueId() {
        return requestedBlueId;
    }

    /**
     * Returns the strict canonical root covered by this evidence.
     *
     * @return immutable strict canonical root
     */
    public FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    /**
     * Returns the completed resolved root covered by this evidence.
     *
     * @return immutable completed resolved root
     */
    public FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    CanonicalTypeIdentityIndex.EvidenceSnapshot
    canonicalTypeIdentityEvidence() {
        return canonicalTypeIdentityEvidence;
    }

    /**
     * Returns immutable nested effective-type identity evidence transported
     * with this verified reference.
     *
     * @return fail-closed canonical type identity lookup
     */
    public CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return canonicalTypeIdentityEvidence;
    }
}
