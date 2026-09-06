package blue.language.merge;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.identity.CanonicalIdentityInputBuilder;
import blue.language.resolve.ResolutionLimits;

import java.util.Objects;

/** Creates immutable canonical/resolved pairs from one active invocation. */
final class ResolutionSnapshotFactory {

    private final ResolutionEngine engine;
    private final ResolvedReferenceCache resolvedReferenceCache;

    ResolutionSnapshotFactory(
            ResolutionEngine engine,
            ResolvedReferenceCache resolvedReferenceCache) {
        this.engine = engine;
        this.resolvedReferenceCache = resolvedReferenceCache;
    }

    SnapshotResolution resolve(Node preprocessedSource, ResolutionLimits limits) {
        Objects.requireNonNull(preprocessedSource, "preprocessedSource");
        Objects.requireNonNull(limits, "limits");
        InlineTypeCycleValidator.validate(preprocessedSource);
        Node resolved = engine.resolve(preprocessedSource.clone(), limits);
        CanonicalTypeIdentityIndex.EvidenceSnapshot typeIdentityEvidence =
                engine.completedTypeIdentityEvidence();
        Node canonical = new CanonicalIdentityInputBuilder().build(
                resolved.clone(),
                preprocessedSource,
                typeIdentityEvidence);
        return snapshot(
                FrozenNode.fromNode(canonical),
                resolved,
                limits,
                typeIdentityEvidence);
    }

    SnapshotResolution resolve(FrozenNode canonicalRoot, ResolutionLimits limits) {
        Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        Objects.requireNonNull(limits, "limits");
        if (!canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException(
                    "Snapshot resolution requires a strict canonical root.");
        }
        Node resolved = engine.resolveCanonical(canonicalRoot.toNode(), limits);
        return snapshot(
                canonicalRoot,
                resolved,
                limits,
                engine.completedTypeIdentityEvidence());
    }

    private SnapshotResolution snapshot(
            FrozenNode canonicalRoot,
            Node resolved,
            ResolutionLimits limits,
            CanonicalTypeIdentityIndex.EvidenceSnapshot
                    typeIdentityEvidence) {
        FrozenNode frozenResolved = freezeResolved(resolved);
        VerifiedReferenceResolution verification = null;
        if (limits == ResolutionLimits.NO_LIMITS
                && canonicalRoot.isStrictBlueIdValidation()
                && !canonicalRoot.isReferenceOnly()
                && !frozenResolved.isReferenceOnly()) {
            verification = new VerifiedReferenceResolution(
                    canonicalRoot.blueId(),
                    canonicalRoot,
                    frozenResolved,
                    typeIdentityEvidence);
        }
        return new SnapshotResolution(
                canonicalRoot,
                frozenResolved,
                verification != null
                        ? ResolutionProvenance.verified(verification)
                        : ResolutionProvenance.none(),
                typeIdentityEvidence,
                limits == ResolutionLimits.NO_LIMITS);
    }

    private FrozenNode freezeResolved(Node resolved) {
        return resolvedReferenceCache != null
                ? resolvedReferenceCache.freezeResolved(resolved)
                : FrozenNode.fromResolvedNode(resolved);
    }
}
