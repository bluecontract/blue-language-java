package blue.language.merge;

import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Standalone immutable canonical/resolved pair produced by one invocation.
 */
public final class SnapshotResolution implements ResolutionSnapshot {

    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final ResolutionProvenance provenance;

    SnapshotResolution(FrozenNode canonicalRoot,
                       FrozenNode resolvedRoot,
                       ResolutionProvenance provenance) {
        this.canonicalRoot = Objects.requireNonNull(
                canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(
                resolvedRoot, "resolvedRoot");
        this.provenance = Objects.requireNonNull(
                provenance, "provenance");
    }

    /**
     * Returns the strict canonical root captured by this resolution.
     *
     * @return immutable strict canonical root
     */
    @Override
    public FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    /**
     * Returns the completed root produced by this resolution.
     *
     * @return immutable completed resolved root
     */
    @Override
    public FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    /**
     * Returns provenance captured by this resolver invocation.
     *
     * @return immutable resolution provenance
     */
    @Override
    public ResolutionProvenance provenance() {
        return provenance;
    }

    /**
     * Returns resolver-issued evidence for an eligible reference resolution.
     *
     * @return verified reference evidence, or {@code null} when the resolution
     *         is not eligible for verified-reference caching
     */
    public VerifiedReferenceResolution verifiedReferenceResolution() {
        return provenance.verifiedReferenceResolution();
    }
}
