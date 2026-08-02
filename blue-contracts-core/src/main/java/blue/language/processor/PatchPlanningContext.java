package blue.language.processor;

import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.processor.util.PointerUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable canonical/resolved inputs for one patch-planning revision. */
class PatchPlanningContext {

    private final ResolvedSnapshot baseSnapshot;
    private final ImmutablePatchPlanner canonicalPlanner;
    private final ImmutablePatchPlanner resolvedPlanner;
    private final boolean exactReplacement;
    private final ProcessingSnapshotManager authoritativeSnapshotManager;
    private final ProcessingSnapshotManager invocationEvidenceSnapshotManager;
    private final Set<String> openedScopePaths;
    private final Map<String, List<String>> executableBodyFieldsByType;
    private final Map<String, EmbeddedScopePlan> entryEmbeddedScopePlans;
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
        this(
                baseSnapshot,
                canonicalPlanner,
                resolvedPlanner,
                exactReplacement,
                authoritativeSnapshotManager,
                authoritativeSnapshotManager,
                openedScopePaths,
                executableBodyFieldsByType,
                Collections.<String, EmbeddedScopePlan>emptyMap(),
                resolutionComplete);
    }

    PatchPlanningContext(
            ResolvedSnapshot baseSnapshot,
            ImmutablePatchPlanner canonicalPlanner,
            ImmutablePatchPlanner resolvedPlanner,
            boolean exactReplacement,
            ProcessingSnapshotManager authoritativeSnapshotManager,
            ProcessingSnapshotManager invocationEvidenceSnapshotManager,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            Map<String, EmbeddedScopePlan> entryEmbeddedScopePlans,
            boolean resolutionComplete) {
        this.baseSnapshot = baseSnapshot;
        this.canonicalPlanner = canonicalPlanner;
        this.resolvedPlanner = resolvedPlanner;
        this.exactReplacement = exactReplacement;
        this.authoritativeSnapshotManager = authoritativeSnapshotManager;
        this.invocationEvidenceSnapshotManager =
                invocationEvidenceSnapshotManager;
        this.openedScopePaths = Collections.unmodifiableSet(
                ExecutableBodyPathCatalog.openedScopes(openedScopePaths));
        this.executableBodyFieldsByType = ProcessingSnapshotBootstrap
                .immutableExecutableBodyFields(executableBodyFieldsByType);
        this.entryEmbeddedScopePlans = immutableEntryEmbeddedScopePlans(
                entryEmbeddedScopePlans);
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

    ProcessingSnapshotManager invocationEvidenceSnapshotManager() {
        return invocationEvidenceSnapshotManager;
    }

    Set<String> openedScopePaths() {
        return openedScopePaths;
    }

    Map<String, List<String>> executableBodyFieldsByType() {
        return executableBodyFieldsByType;
    }

    EmbeddedScopePlan entryEmbeddedScopePlan(String scopePath) {
        return entryEmbeddedScopePlans.get(
                PointerUtils.normalizeScope(scopePath));
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

    private static Map<String, EmbeddedScopePlan>
    immutableEntryEmbeddedScopePlans(
            Map<String, EmbeddedScopePlan> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, EmbeddedScopePlan> result = new LinkedHashMap<>();
        for (Map.Entry<String, EmbeddedScopePlan> entry
                : source.entrySet()) {
            String scopePath = PointerUtils.normalizeScope(
                    Objects.requireNonNull(
                            entry.getKey(), "entry embedded scope path"));
            EmbeddedScopePlan plan = Objects.requireNonNull(
                    entry.getValue(), "entry embedded scope plan");
            if (!scopePath.equals(plan.scopePath())) {
                throw new IllegalArgumentException(
                        "Entry embedded scope plan belongs to another scope: "
                                + plan.scopePath());
            }
            if (result.put(scopePath, plan) != null) {
                throw new IllegalArgumentException(
                        "Duplicate entry embedded scope plan: " + scopePath);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
