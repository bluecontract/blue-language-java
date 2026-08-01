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

    @Override
    public FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    @Override
    public FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    @Override
    public ResolutionProvenance provenance() {
        return provenance;
    }

    /** Returns verified reference evidence, or {@code null} when ineligible. */
    public VerifiedReferenceResolution verifiedReferenceResolution() {
        return provenance.verifiedReferenceResolution();
    }
}
