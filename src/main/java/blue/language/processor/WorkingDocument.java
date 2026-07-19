package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Frozen preview state for processor-side read-your-writes workflows.
 *
 * <p>A working document applies the same immutable patch transaction used by
 * {@link DocumentProcessingRuntime}, including conformance planning, dynamic
 * type generalization, and Type Generalization Policy enforcement. It never
 * commits to the processor runtime, emits cascades, charges gas, or writes
 * processor-managed markers.</p>
 */
public final class WorkingDocument {

    private static final DocumentProcessingRuntime.UpdateMaterializationMetrics NOOP_MATERIALIZATION_METRICS =
            new DocumentProcessingRuntime.UpdateMaterializationMetrics() {
                @Override
                public void recordBeforeNodeMaterialization() {
                    // Working previews keep update metadata frozen and do not expose document-update materialization.
                }

                @Override
                public void recordAfterNodeMaterialization() {
                    // Working previews keep update metadata frozen and do not expose document-update materialization.
                }
            };

    private final String originScope;
    private FrozenNode canonicalRoot;
    private FrozenNode resolvedRoot;
    private final ProcessingSnapshotManager snapshotManager;
    private final boolean materializedFallback;
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final boolean exactReplacement;
    private final ProcessingMetricsSink metrics;
    private ProcessingSnapshotManager workingSequenceManager;
    private ResolvedSnapshot snapshot;

    WorkingDocument(String originScope,
                    FrozenNode canonicalRoot,
                    FrozenNode resolvedRoot,
                    ConformanceEngine conformanceEngine,
                    ConformancePlannerOverride conformancePlannerOverride,
                    ProcessingSnapshotManager snapshotManager,
                    ResolvedSnapshot snapshot,
                    boolean materializedFallback,
                    boolean exactReplacement,
                    ProcessingMetricsSink metrics) {
        this.originScope = PointerUtils.normalizeScope(originScope);
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        this.snapshotManager = snapshotManager;
        this.snapshot = snapshot;
        this.materializedFallback = materializedFallback;
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.exactReplacement = exactReplacement;
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
        this.workingSequenceManager = snapshotManager != null
                ? snapshotManager.transientSequence()
                : null;
    }

    public FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    public FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    public FrozenNode canonicalAt(String absolutePointer) {
        return ImmutablePatchPlanner.forFrozen(canonicalRoot)
                .read(PointerUtils.normalizePointer(absolutePointer));
    }

    public FrozenNode resolvedAt(String absolutePointer) {
        return ImmutablePatchPlanner.forFrozen(resolvedRoot)
                .read(PointerUtils.normalizePointer(absolutePointer));
    }

    public WorkingDocument applyPatch(JsonPatch patch) {
        if (patch == null) {
            return this;
        }
        return applyPatches(Collections.singletonList(patch));
    }

    public WorkingDocument applyPatches(List<JsonPatch> patches) {
        applyPatches(patches, false);
        return this;
    }

    public Preview previewAndApplyPatches(List<JsonPatch> patches) {
        return applyPatches(patches, true);
    }

    private Preview applyPatches(List<JsonPatch> patches, boolean createHandoff) {
        if (patches == null || patches.isEmpty()) {
            return Preview.empty(originScope);
        }
        List<JsonPatch> copiedPatches = new ArrayList<>(patches.size());
        for (JsonPatch patch : patches) {
            copiedPatches.add(ImmutableJsonPatch.copy(patch));
        }
        List<PatchPreview> previews = new ArrayList<>(copiedPatches.size());
        ProcessingSnapshotManager sequenceManager = workingSequenceManager();
        ConformanceEngine sequenceConformanceEngine = sequenceManager != null
                ? sequenceManager.transientConformanceEngine(conformanceEngine)
                : conformanceEngine != null ? conformanceEngine.transientView() : null;
        DocumentProcessingRuntime.PlanningContext planning =
                DocumentProcessingRuntime.workingPlanningContext(
                        canonicalRoot, resolvedRoot, exactReplacement, sequenceManager);
        SequentialPatchPlanningSession planningSession = new SequentialPatchPlanningSession(
                this.originScope,
                planning,
                sequenceConformanceEngine,
                conformancePlannerOverride,
                NOOP_MATERIALIZATION_METRICS,
                metrics);
        try {
            for (JsonPatch patch : copiedPatches) {
                SequentialPatchPlanningSession.PlannedStep step =
                        planningSession.planNext(patch);
                previews.add(PatchPreview.from(step));
            }
        } catch (RuntimeException ex) {
            if (sequenceManager != null) {
                sequenceManager.retainTransientState(canonicalRoot, resolvedRoot);
            }
            throw ex;
        }
        canonicalRoot = planningSession.canonicalRoot();
        resolvedRoot = planningSession.resolvedRoot();
        snapshot = null;
        ProcessingSnapshotManager handoff = createHandoff && sequenceManager != null
                ? sequenceManager.forkTransientSequence()
                : null;
        if (sequenceManager != null) {
            sequenceManager.retainTransientState(canonicalRoot, resolvedRoot);
        }
        return new Preview(originScope, previews, handoff);
    }

    private ProcessingSnapshotManager workingSequenceManager() {
        if (workingSequenceManager != null && !workingSequenceManager.isTransientStateCurrent()) {
            workingSequenceManager = null;
        }
        if (workingSequenceManager == null && snapshotManager != null) {
            workingSequenceManager = snapshotManager.transientSequence();
        }
        return workingSequenceManager;
    }

    public ResolvedSnapshot snapshot() {
        if (snapshot == null) {
            snapshot = new ResolvedSnapshot(canonicalRoot, resolvedRoot, canonicalRoot.blueId());
        }
        return snapshot;
    }

    public Node materializeCanonicalRoot() {
        return canonicalRoot.toNode();
    }

    public Node materializeResolvedRoot() {
        return resolvedRoot.toNode();
    }

    public Node commitToNode() {
        return materializeCanonicalRoot();
    }

    public ResolvedSnapshot commitSnapshot() {
        ResolvedSnapshot current = snapshot();
        if (snapshotManager == null) {
            snapshot = current;
            return snapshot;
        }
        boolean currentResolutionScope = workingSequenceManager == null
                || workingSequenceManager.isTransientStateCurrent();
        ProcessingSnapshotManager publicationManager = workingSequenceManager();
        ResolvedSnapshot authoritative = exactReplacement && currentResolutionScope
                ? current
                : publicationManager.fromDocumentTransient(
                        current.frozenCanonicalRoot().toNode());
        snapshot = publicationManager.cacheSnapshot(authoritative);
        canonicalRoot = snapshot.frozenCanonicalRoot();
        resolvedRoot = snapshot.frozenResolvedRoot();
        publicationManager.retainTransientState(canonicalRoot, resolvedRoot);
        return snapshot;
    }

    /**
     * Returns true when this preview had to freeze a materialized runtime tree
     * because no processor snapshot was available at creation time.
     */
    public boolean usedMaterializedFallback() {
        return materializedFallback;
    }

    public static final class Preview {
        private final String originScope;
        private final List<PatchPreview> patches;
        private final ProcessingSnapshotManager resolutionScope;
        private ProcessingSnapshotManager sequenceSnapshotManager;

        private Preview(String originScope,
                        List<PatchPreview> patches,
                        ProcessingSnapshotManager sequenceSnapshotManager) {
            this.originScope = PointerUtils.normalizeScope(originScope);
            this.patches = new ArrayList<>(patches);
            this.resolutionScope = sequenceSnapshotManager;
            this.sequenceSnapshotManager = sequenceSnapshotManager;
        }

        private static Preview empty(String originScope) {
            return new Preview(originScope, Collections.<PatchPreview>emptyList(), null);
        }

        String originScope() {
            return originScope;
        }

        int size() {
            return patches.size();
        }

        PatchPreview patch(int index) {
            return index >= 0 && index < patches.size() ? patches.get(index) : null;
        }

        void release(int index) {
            if (index >= 0 && index < patches.size()) {
                patches.set(index, null);
            }
        }

        void discardFrom(int index) {
            for (int current = Math.max(0, index); current < patches.size(); current++) {
                patches.set(current, null);
            }
            if (index <= 0) {
                sequenceSnapshotManager = null;
            }
        }

        ProcessingSnapshotManager takeSequenceSnapshotManager() {
            ProcessingSnapshotManager retained = sequenceSnapshotManager;
            sequenceSnapshotManager = null;
            return retained;
        }

        boolean isResolutionScopeCurrent() {
            return resolutionScope == null
                    || resolutionScope.isTransientStateCurrent();
        }
    }

    static final class PatchPreview {
        private final String originScope;
        private final ImmutableJsonPatch patch;
        private final FrozenNode baseCanonical;
        private final FrozenNode baseResolved;
        private final BatchPatchResult result;

        private PatchPreview(String originScope,
                             ImmutableJsonPatch patch,
                             FrozenNode baseCanonical,
                             FrozenNode baseResolved,
                             BatchPatchResult result) {
            this.originScope = PointerUtils.normalizeScope(originScope);
            this.patch = patch;
            this.baseCanonical = baseCanonical;
            this.baseResolved = baseResolved;
            this.result = result;
        }

        static PatchPreview from(SequentialPatchPlanningSession.PlannedStep step) {
            Objects.requireNonNull(step, "step");
            return new PatchPreview(step.originScope(),
                    step.patch(),
                    step.baseCanonical(),
                    step.baseResolved(),
                    step.result());
        }

        String originScope() {
            return originScope;
        }

        FrozenNode baseCanonical() {
            return baseCanonical;
        }

        FrozenNode baseResolved() {
            return baseResolved;
        }

        BatchPatchResult result() {
            return result;
        }

        ImmutableJsonPatch patch() {
            return patch;
        }

        boolean isBasedOn(FrozenNode actualCanonical, FrozenNode actualResolved) {
            return SequentialPatchPlanningSession.sameRoots(baseCanonical,
                    baseResolved,
                    actualCanonical,
                    actualResolved);
        }

        boolean matches(JsonPatch candidate) {
            return candidate != null && patch.matches(
                    ImmutableJsonPatch.from(candidate, baseCanonical, baseResolved));
        }

        boolean matches(ImmutableJsonPatch candidate) {
            return patch.matches(candidate);
        }
    }
}
