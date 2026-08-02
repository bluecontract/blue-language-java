package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;

import java.util.List;
import java.util.Objects;

/**
 * Reusable cursor for exact sequential patch planning.
 *
 * <p>Unlike an atomic batch, every call completes conformance before the next
 * patch is planned. The session is mutable but never advances when planning a
 * step fails, which lets callers safely retain an already-planned prefix or
 * rebase an unconsumed suffix onto an observed runtime state.</p>
 */
final class SequentialPatchPlanningSession implements AutoCloseable {

    private final String originScope;
    private final PatchPlanningEngine planningEngine;
    private final ProcessingObserver metrics;
    private final ConformanceEngine conformanceEngine;
    private FrozenNode canonicalRoot;
    private FrozenNode resolvedRoot;
    private boolean resolutionComplete;
    private boolean metricsStarted;

    SequentialPatchPlanningSession(String originScope,
                                   PatchPlanningContext planning,
                                   ConformanceEngine conformanceEngine,
                                   ConformancePlannerOverride conformancePlannerOverride,
                                   UpdateMaterializationMetrics materializationMetrics) {
        this(originScope,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                NoOpProcessingObserver.INSTANCE);
    }

    SequentialPatchPlanningSession(String originScope,
                                   PatchPlanningContext planning,
                                   ConformanceEngine conformanceEngine,
                                   ConformancePlannerOverride conformancePlannerOverride,
                                   UpdateMaterializationMetrics materializationMetrics,
                                   ProcessingObserver metrics) {
        this.originScope = PointerUtils.normalizeScope(Objects.requireNonNull(originScope, "originScope"));
        Objects.requireNonNull(planning, "planning");
        this.metrics = metrics != null ? metrics : NoOpProcessingObserver.INSTANCE;
        this.conformanceEngine = conformanceEngine;
        this.canonicalRoot = planning.baseSnapshot() != null
                ? planning.baseSnapshot().frozenCanonicalRoot()
                : planning.canonicalPlanner().root();
        this.resolvedRoot = planning.baseSnapshot() != null
                ? planning.baseSnapshot().frozenResolvedRoot()
                : planning.resolvedPlanner().root();
        this.resolutionComplete =
                planning.isResolutionComplete();
        this.planningEngine = new PatchPlanningEngine(originScope,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                this.metrics,
                false);
    }

    @Override
    public void close() {
        if (conformanceEngine != null) {
            conformanceEngine.close();
        }
    }

    List<ImmutableJsonPatch> preparePatches(List<JsonPatch> patches) {
        return planningEngine.preparePatches(patches, canonicalRoot, resolvedRoot);
    }

    ImmutableJsonPatch preparePatch(JsonPatch patch) {
        return planningEngine.preparePatch(patch, canonicalRoot, resolvedRoot);
    }

    ImmutableJsonPatch preparePatch(JsonPatch patch,
                                    FrozenNode actualCanonicalRoot,
                                    FrozenNode actualResolvedRoot) {
        return planningEngine.preparePatch(patch, actualCanonicalRoot, actualResolvedRoot);
    }

    ImmutableJsonPatch preparePatch(PatchInput patch,
                                    FrozenNode actualCanonicalRoot,
                                    FrozenNode actualResolvedRoot) {
        return planningEngine.preparePatch(patch, actualCanonicalRoot, actualResolvedRoot);
    }

    PlannedStep planNext(PatchInput patch) {
        return planNext(planningEngine.preparePatch(
                Objects.requireNonNull(patch, "patch"), canonicalRoot, resolvedRoot));
    }

    PlannedStep planNext(JsonPatch patch) {
        return planNext(preparePatch(Objects.requireNonNull(patch, "patch")));
    }

    PlannedStep planNext(ImmutableJsonPatch patch) {
        if (!metricsStarted) {
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.PATCH_SEQUENCES_PREPARED, 1L);
            metricsStarted = true;
        }
        FrozenNode baseCanonical = canonicalRoot;
        FrozenNode baseResolved = resolvedRoot;
        boolean baseResolutionComplete = resolutionComplete;
        BatchPatchResult result = planningEngine.planSequentialStep(baseCanonical,
                baseResolved,
                baseResolutionComplete,
                Objects.requireNonNull(patch, "patch"));
        ProcessingObservations.record(metrics,
                ProcessingMetricId.PATCHES_PREPARED, 1L);
        ProcessingObservations.record(metrics,
                ProcessingMetricId.SEQUENCE_PLANNING_NANOS,
                result.patchPlanningNanos());
        ProcessingObservations.record(metrics,
                ProcessingMetricId.SEQUENCE_CONFORMANCE_NANOS,
                result.conformanceNanos());
        canonicalRoot = result.canonicalRoot();
        resolvedRoot = result.resolvedRoot();
        resolutionComplete =
                result.isResolutionComplete();
        return new PlannedStep(originScope,
                result.requestedPatches().get(0),
                baseCanonical,
                baseResolved,
                baseResolutionComplete,
                result);
    }

    void rebase(FrozenNode actualCanonicalRoot, FrozenNode actualResolvedRoot) {
        rebase(actualCanonicalRoot, actualResolvedRoot, resolutionComplete);
    }

    void rebase(FrozenNode actualCanonicalRoot,
                FrozenNode actualResolvedRoot,
                boolean actualResolutionComplete) {
        canonicalRoot = Objects.requireNonNull(actualCanonicalRoot, "actualCanonicalRoot");
        resolvedRoot = Objects.requireNonNull(actualResolvedRoot, "actualResolvedRoot");
        resolutionComplete = actualResolutionComplete;
    }

    FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    boolean isResolutionComplete() {
        return resolutionComplete;
    }

    boolean isBasedOn(FrozenNode actualCanonicalRoot, FrozenNode actualResolvedRoot) {
        return sameRoots(canonicalRoot, resolvedRoot, actualCanonicalRoot, actualResolvedRoot);
    }

    boolean isBasedOn(FrozenNode actualCanonicalRoot,
                      FrozenNode actualResolvedRoot,
                      boolean actualResolutionComplete) {
        return resolutionComplete == actualResolutionComplete
                && isBasedOn(actualCanonicalRoot, actualResolvedRoot);
    }

    static boolean sameRoots(FrozenNode expectedCanonicalRoot,
                             FrozenNode expectedResolvedRoot,
                             FrozenNode actualCanonicalRoot,
                             FrozenNode actualResolvedRoot) {
        if (expectedCanonicalRoot == null
                || expectedResolvedRoot == null
                || actualCanonicalRoot == null
                || actualResolvedRoot == null) {
            return false;
        }
        if (expectedCanonicalRoot == actualCanonicalRoot
                && expectedResolvedRoot == actualResolvedRoot) {
            return true;
        }
        return sameFreezeMode(expectedCanonicalRoot, actualCanonicalRoot)
                && sameFreezeMode(expectedResolvedRoot, actualResolvedRoot)
                && expectedCanonicalRoot.blueId().equals(actualCanonicalRoot.blueId())
                && expectedCanonicalRoot.sameResolvedStructure(actualCanonicalRoot)
                && expectedResolvedRoot.blueId().equals(actualResolvedRoot.blueId())
                && expectedResolvedRoot.sameResolvedStructure(actualResolvedRoot);
    }

    private static boolean sameFreezeMode(FrozenNode left, FrozenNode right) {
        return left.isStrictCanonical() == right.isStrictCanonical()
                && left.isStrictBlueIdValidation() == right.isStrictBlueIdValidation();
    }

    static final class PlannedStep {
        private final String originScope;
        private final ImmutableJsonPatch patch;
        private final FrozenNode baseCanonical;
        private final FrozenNode baseResolved;
        private final boolean baseResolutionComplete;
        private final BatchPatchResult result;

        private PlannedStep(String originScope,
                            ImmutableJsonPatch patch,
                            FrozenNode baseCanonical,
                            FrozenNode baseResolved,
                            boolean baseResolutionComplete,
                            BatchPatchResult result) {
            this.originScope = originScope;
            this.patch = patch;
            this.baseCanonical = baseCanonical;
            this.baseResolved = baseResolved;
            this.baseResolutionComplete = baseResolutionComplete;
            this.result = result;
        }

        String originScope() {
            return originScope;
        }

        ImmutableJsonPatch patch() {
            return patch;
        }

        FrozenNode baseCanonical() {
            return baseCanonical;
        }

        FrozenNode baseResolved() {
            return baseResolved;
        }

        boolean isBaseResolutionComplete() {
            return baseResolutionComplete;
        }

        BatchPatchResult result() {
            return result;
        }
    }
}
