package blue.language.processor;

import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable canonical/resolved inputs for one patch-planning revision. */
class PatchPlanningContext {

    private final ResolvedSnapshot baseSnapshot;
    private final ImmutablePatchPlanner canonicalPlanner;
    private final ImmutablePatchPlanner resolvedPlanner;
    private final boolean exactReplacement;
    private final ProcessingSnapshotManager authoritativeSnapshotManager;
    private final Set<String> openedScopePaths;
    private final Map<String, List<String>> executableBodyFieldsByType;
    private final boolean resolutionComplete;

    PatchPlanningContext(
            ResolvedSnapshot baseSnapshot,
            ImmutablePatchPlanner canonicalPlanner,
            ImmutablePatchPlanner resolvedPlanner,
            boolean exactReplacement,
            ProcessingSnapshotManager authoritativeSnapshotManager,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean resolutionComplete) {
        this.baseSnapshot = baseSnapshot;
        this.canonicalPlanner = canonicalPlanner;
        this.resolvedPlanner = resolvedPlanner;
        this.exactReplacement = exactReplacement;
        this.authoritativeSnapshotManager = authoritativeSnapshotManager;
        this.openedScopePaths = Collections.unmodifiableSet(
                ExecutableBodyPathCatalog.openedScopes(openedScopePaths));
        this.executableBodyFieldsByType = ProcessingSnapshotBootstrap
                .immutableExecutableBodyFields(executableBodyFieldsByType);
        this.resolutionComplete = resolutionComplete;
    }

    ResolvedSnapshot baseSnapshot() {
        return baseSnapshot;
    }

    ImmutablePatchPlanner canonicalPlanner() {
        return canonicalPlanner;
    }

    ImmutablePatchPlanner resolvedPlanner() {
        return resolvedPlanner;
    }

    boolean exactReplacement() {
        return exactReplacement;
    }

    ProcessingSnapshotManager authoritativeSnapshotManager() {
        return authoritativeSnapshotManager;
    }

    Set<String> openedScopePaths() {
        return openedScopePaths;
    }

    Map<String, List<String>> executableBodyFieldsByType() {
        return executableBodyFieldsByType;
    }

    boolean isResolutionComplete() {
        return resolutionComplete;
    }

    ResolvedSnapshot resolveCanonical(FrozenNode canonicalRoot) {
        if (!exactReplacement || authoritativeSnapshotManager == null) {
            throw new IllegalStateException(
                    "Authoritative snapshot resolution is unavailable");
        }
        return DocumentProcessingRuntime.resolveCanonicalTransient(
                authoritativeSnapshotManager,
                canonicalRoot,
                openedScopePaths,
                executableBodyFieldsByType);
    }
}
