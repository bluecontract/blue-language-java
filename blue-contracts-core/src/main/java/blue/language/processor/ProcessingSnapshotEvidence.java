package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.ResolvedSnapshot;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;
import java.util.List;
import java.util.Map;

/** Constructs and retains snapshots without losing canonical type evidence. */
final class ProcessingSnapshotEvidence {

    private ProcessingSnapshotEvidence() {
    }

    static CanonicalTypeIdentityLookup identities(ResolvedSnapshot snapshot) {
        return snapshot != null
                ? snapshot.canonicalTypeIdentities()
                : CanonicalTypeIdentityLookup.incomplete();
    }

    static ResolvedSnapshot cacheIfComplete(
            ProcessingSnapshotManager manager,
            ResolvedSnapshot candidate) {
        Objects.requireNonNull(manager, "snapshotManager");
        ResolvedSnapshot checked = Objects.requireNonNull(
                candidate, "snapshot");
        return !checked.isResolutionComplete()
                ? checked
                : Objects.requireNonNull(
                        manager.cacheSnapshot(
                                checked.withoutVerifiedReferenceProvenance()),
                        "cachedSnapshot");
    }

    static ResolvedSnapshot create(
            FrozenNode selectedRoot,
            FrozenNode resolvedRoot,
            boolean resolutionComplete,
            boolean sourceBacked,
            boolean eagerIdentity,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        ResolvedSnapshot snapshot;
        if (sourceBacked) {
            snapshot = ResolvedSnapshot.withSource(
                    selectedRoot,
                    resolvedRoot,
                    canonicalTypeIdentities,
                    resolutionComplete);
        } else if (resolutionComplete) {
            snapshot = ResolvedSnapshot.withCanonicalTypeIdentities(
                    selectedRoot,
                    resolvedRoot,
                    canonicalTypeIdentities);
        } else {
            snapshot = ResolvedSnapshot.withDeferredResolution(
                    selectedRoot,
                    resolvedRoot,
                    canonicalTypeIdentities);
        }
        if (eagerIdentity && snapshot.hasCanonicalIdentity()) {
            snapshot.blueId();
        }
        return snapshot;
    }

    static PatchPlanningContext planningContext(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean exactReplacement,
            ProcessingSnapshotManager snapshotManager,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            Map<String, EmbeddedScopePlan> entryEmbeddedScopePlans,
            boolean resolutionComplete,
            boolean strictPlatformInvocation,
            CanonicalTypeIdentityLookup canonicalTypeIdentities,
            boolean sourceBacked) {
        return new PatchPlanningContext(
                null,
                ImmutablePatchPlanner.forFrozen(canonicalRoot),
                ImmutablePatchPlanner.forFrozen(resolvedRoot),
                exactReplacement,
                exactReplacement ? snapshotManager : null,
                snapshotManager,
                openedScopePaths,
                executableBodyFieldsByType,
                entryEmbeddedScopePlans,
                resolutionComplete,
                strictPlatformInvocation,
                canonicalTypeIdentities,
                sourceBacked);
    }

    static ResolvedSnapshot resolveTransient(
            ProcessingSnapshotManager manager,
            FrozenNode canonicalRoot,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean includeTypeContracts) {
        return includeTypeContracts
                ? ExecutableBodyPathCatalog
                        .resolveCanonicalTransientIncludingTypeContracts(
                                manager,
                                canonicalRoot,
                                openedScopePaths,
                                executableBodyFieldsByType)
                : ExecutableBodyPathCatalog.resolveCanonicalTransient(
                        manager,
                        canonicalRoot,
                        openedScopePaths,
                        executableBodyFieldsByType);
    }
}
