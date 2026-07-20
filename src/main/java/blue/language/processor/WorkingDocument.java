package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
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
 *
 * <p>A working document owns transient snapshot-planning state and must be
 * closed when the read-your-writes session is finished. A preview returned by
 * this object has an independent handoff lease and remains valid after the
 * working document itself is closed.</p>
 */
public final class WorkingDocument implements AutoCloseable {

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
    private boolean closed;

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
        applyPatchInputs(PatchInput.mutableList(patches), false);
        return this;
    }

    public Preview previewAndApplyPatches(List<JsonPatch> patches) {
        return applyPatchInputs(PatchInput.mutableList(patches), true);
    }

    public WorkingDocument applyFrozenPatch(FrozenJsonPatch patch) {
        if (patch == null) {
            return this;
        }
        return applyFrozenPatches(Collections.singletonList(patch));
    }

    public WorkingDocument applyFrozenPatches(List<FrozenJsonPatch> patches) {
        applyPatchInputs(PatchInput.frozenList(patches), false);
        return this;
    }

    public Preview previewAndApplyFrozenPatches(List<FrozenJsonPatch> patches) {
        return applyPatchInputs(PatchInput.frozenList(patches), true);
    }

    private Preview applyPatchInputs(List<PatchInput> patches, boolean createHandoff) {
        ensureOpen();
        if (patches == null || patches.isEmpty()) {
            return Preview.empty(originScope);
        }
        List<PatchPreview> previews = new ArrayList<>(patches.size());
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
            for (PatchInput patch : patches) {
                SequentialPatchPlanningSession.PlannedStep step =
                        planningSession.planNext(patch);
                previews.add(PatchPreview.from(step));
            }
        } catch (RuntimeException ex) {
            if (sequenceManager != null) {
                sequenceManager.retainTransientState(canonicalRoot, resolvedRoot);
            }
            throw ex;
        } finally {
            planningSession.close();
        }
        canonicalRoot = planningSession.canonicalRoot();
        resolvedRoot = planningSession.resolvedRoot();
        snapshot = null;
        ProcessingSnapshotManager handoff = null;
        try {
            handoff = createHandoff && sequenceManager != null
                    ? sequenceManager.forkTransientSequence()
                    : null;
            if (sequenceManager != null) {
                sequenceManager.retainTransientState(canonicalRoot, resolvedRoot);
            }
            return new Preview(originScope, previews, handoff);
        } catch (RuntimeException | Error ex) {
            releaseAfterFailedHandoff(handoff, ex);
            throw ex;
        }
    }

    private static void releaseAfterFailedHandoff(ProcessingSnapshotManager handoff,
                                                  Throwable primaryFailure) {
        if (handoff == null) {
            return;
        }
        try {
            handoff.releaseTransientState();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure != cleanupFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private ProcessingSnapshotManager workingSequenceManager() {
        ensureOpen();
        if (workingSequenceManager != null && !workingSequenceManager.isTransientStateCurrent()) {
            workingSequenceManager.releaseTransientState();
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
        ensureOpen();
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

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ProcessingSnapshotManager manager = workingSequenceManager;
        workingSequenceManager = null;
        if (manager != null) {
            manager.releaseTransientState();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Working document is closed");
        }
    }

    /**
     * Returns true when this preview had to freeze a materialized runtime tree
     * because no processor snapshot was available at creation time.
     */
    public boolean usedMaterializedFallback() {
        return materializedFallback;
    }

    public static final class Preview implements AutoCloseable {
        private final String originScope;
        private final List<PatchPreview> patches;
        private ProcessingSnapshotManager resolutionScope;
        private ProcessingSnapshotManager sequenceSnapshotManager;
        private boolean closed;

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
                ProcessingSnapshotManager manager = sequenceSnapshotManager;
                sequenceSnapshotManager = null;
                resolutionScope = null;
                closed = true;
                if (manager != null) {
                    manager.releaseTransientState();
                }
            }
        }

        ProcessingSnapshotManager takeSequenceSnapshotManager() {
            if (closed) {
                return null;
            }
            ProcessingSnapshotManager retained = sequenceSnapshotManager;
            sequenceSnapshotManager = null;
            return retained;
        }

        boolean isResolutionScopeCurrent() {
            return resolutionScope == null
                    || resolutionScope.isTransientStateCurrent();
        }

        @Override
        public void close() {
            discardFrom(0);
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

        boolean matches(PatchInput candidate) {
            if (candidate == null) {
                return false;
            }
            ImmutableJsonPatch.PreparationContext preparation =
                    ImmutableJsonPatch.preparationContext(ProcessingMetricsSink.NOOP);
            return patch.matches(candidate.prepare(preparation, baseCanonical, baseResolved));
        }

        boolean matches(ImmutableJsonPatch candidate) {
            return patch.matches(candidate);
        }
    }
}
