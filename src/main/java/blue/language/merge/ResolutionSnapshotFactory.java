package blue.language.merge;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.utils.CanonicalIdentityInputBuilder;
import blue.language.utils.limits.Limits;

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

    SnapshotResolution resolve(Node preprocessedSource, Limits limits) {
        Objects.requireNonNull(preprocessedSource, "preprocessedSource");
        Objects.requireNonNull(limits, "limits");
        Node resolved = engine.resolve(preprocessedSource.clone(), limits);
        Node canonical = new CanonicalIdentityInputBuilder().build(
                resolved.clone(), preprocessedSource);
        return snapshot(FrozenNode.fromNode(canonical), resolved, limits);
    }

    SnapshotResolution resolve(FrozenNode canonicalRoot, Limits limits) {
        Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        Objects.requireNonNull(limits, "limits");
        if (!canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException(
                    "Snapshot resolution requires a strict canonical root.");
        }
        Node resolved = engine.resolve(canonicalRoot.toNode(), limits);
        return snapshot(canonicalRoot, resolved, limits);
    }

    private SnapshotResolution snapshot(
            FrozenNode canonicalRoot, Node resolved, Limits limits) {
        FrozenNode frozenResolved = freezeResolved(resolved);
        VerifiedReferenceResolution verification = null;
        if (limits == Limits.NO_LIMITS
                && canonicalRoot.isStrictBlueIdValidation()
                && !canonicalRoot.isReferenceOnly()
                && !frozenResolved.isReferenceOnly()) {
            verification = new VerifiedReferenceResolution(
                    canonicalRoot.blueId(), canonicalRoot, frozenResolved);
        }
        return new SnapshotResolution(
                canonicalRoot,
                frozenResolved,
                verification != null
                        ? ResolutionProvenance.verified(verification)
                        : ResolutionProvenance.none());
    }

    private FrozenNode freezeResolved(Node resolved) {
        return resolvedReferenceCache != null
                ? resolvedReferenceCache.freezeResolved(resolved)
                : FrozenNode.fromResolvedNode(resolved);
    }
}
