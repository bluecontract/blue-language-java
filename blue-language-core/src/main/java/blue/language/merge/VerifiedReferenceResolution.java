package blue.language.merge;

import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Immutable resolver-issued evidence for one completely resolved reference.
 *
 * <p>The constructor is package-private so arbitrary callers cannot fabricate
 * cache-admissible evidence. The historical nested Merger value delegates to
 * this standalone representation.</p>
 */
public final class VerifiedReferenceResolution {

    private final String requestedBlueId;
    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;

    VerifiedReferenceResolution(String requestedBlueId,
                                FrozenNode canonicalRoot,
                                FrozenNode resolvedRoot) {
        this.requestedBlueId = Objects.requireNonNull(
                requestedBlueId, "requestedBlueId");
        this.canonicalRoot = Objects.requireNonNull(
                canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(
                resolvedRoot, "resolvedRoot");
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
}
