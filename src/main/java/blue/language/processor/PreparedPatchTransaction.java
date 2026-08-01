package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Single-use transaction cursor over one ordered patch sequence.
 *
 * <p>The cursor owns all transient planning/cache state. The enclosing
 * invocation runtime remains the authoritative state publisher and supplies
 * atomic commit/rollback operations.</p>
 */
class PreparedPatchTransaction implements AutoCloseable {

    private final DocumentProcessingRuntime runtime;
    private final String originScope;
    private final int patchCount;
    private final WorkingDocument.Preview preview;
    private final List<PatchInput> patches;
    private ProcessingSnapshotManager sequenceSnapshotManager;
    private ProcessingSnapshotManager previousActiveSequenceSnapshotManager;
    private boolean sequenceSnapshotManagerActivated;
    private SequentialPatchPlanningSession planningSession;
    private FrozenNode observedCanonical;
    private FrozenNode observedResolved;
    private boolean observedResolutionComplete = true;
    private long observedVersion = Long.MIN_VALUE;
    private boolean advanced;
    private boolean closed;
    private boolean counted;

    PreparedPatchTransaction(
            DocumentProcessingRuntime runtime,
            String originScope,
            List<PatchInput> requestedPatches,
            WorkingDocument.Preview preview) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.originScope = PointerUtils.normalizeScope(originScope);
        this.preview = preview;
        List<PatchInput> checkedPatches = Objects.requireNonNull(
                requestedPatches, "patches");
        this.patches = new ArrayList<>(checkedPatches);
        this.patchCount = this.patches.size();
    }

    int size() {
        return patchCount;
    }

    JsonPatch patchForValidation(int patchIndex) {
        return patchAt(patchIndex).legacyPatch();
    }

    PatchInput patchInputForValidation(int patchIndex) {
        return patchAt(patchIndex);
    }

    List<DocumentProcessingRuntime.DocumentUpdateData> applyNext(
            int patchIndex) {
        if (closed) {
            throw new IllegalStateException(
                    "Patch sequence is already closed");
        }
        PatchInput authoredPatch = patchAt(patchIndex);
        runtime.validateMutationPathWithoutResolution(authoredPatch);
        runtime.chargeSemanticIdentityWork(
                Collections.singletonList(authoredPatch));
        if (!counted) {
            runtime.patchSequencesPrepared++;
            runtime.batchPatchCalls++;
            counted = true;
        }
        SequenceRoots actual = currentRoots();
        refreshInvalidSequenceSnapshotManager();
        if (planningSession == null) {
            planningSession = newPlanningSession(actual, patchIndex);
        }
        ImmutableJsonPatch patch = planningSession.preparePatch(
                authoredPatch, actual.canonical, actual.resolved);
        WorkingDocument.PatchPreview prepared = preview != null
                ? preview.patch(patchIndex)
                : null;
        BatchPatchResult result;
        boolean plannedNow = false;
        if (prepared != null
                && preview.isResolutionScopeCurrent()
                && originScope.equals(prepared.originScope())
                && prepared.matches(patch)
                && prepared.isBasedOn(
                        actual.canonical,
                        actual.resolved,
                        actual.resolutionComplete)) {
            result = prepared.result();
        } else {
            if (preview != null) {
                preview.discardFrom(patchIndex);
                runtime.sequenceStalePreviewFallbacks++;
                runtime.observe(
                        ProcessingMetricId.SEQUENCE_STALE_PREVIEW_FALLBACKS,
                        1L);
            }
            if (!planningSession.isBasedOn(
                    actual.canonical,
                    actual.resolved,
                    actual.resolutionComplete)) {
                planningSession.rebase(
                        actual.canonical,
                        actual.resolved,
                        actual.resolutionComplete);
                runtime.sequenceSuffixRebases++;
                runtime.observe(
                        ProcessingMetricId.SEQUENCE_SUFFIX_REBASES,
                        1L);
            }
            result = planningSession.planNext(patch).result();
            plannedNow = true;
        }
        if (preview != null) {
            preview.release(patchIndex);
        }

        if (plannedNow) {
            runtime.batchPatchPlanningNanos += result.patchPlanningNanos();
            runtime.batchPatchConformanceNanos += result.conformanceNanos();
        }
        runtime.batchPatchEntries++;

        long buildUpdatesStart = System.nanoTime();
        BatchPatchResult commitResult;
        try {
            commitResult = runtime.usesAuthoritativeSelectedSnapshot()
                    ? result
                    : result.withMaterializationMetrics(
                            runtime.updateMaterializationMetrics());
        } finally {
            long buildUpdatesNanos =
                    System.nanoTime() - buildUpdatesStart;
            runtime.batchPatchBuildUpdatesNanos += buildUpdatesNanos;
            runtime.observe(
                    ProcessingMetricId.BATCH_PATCH_BUILD_UPDATES_NANOS,
                    buildUpdatesNanos);
        }

        Node selectedRollback = runtime.selectedDocumentBacked
                ? runtime.materializedView.copyRoot()
                : null;
        ResolvedSnapshot snapshotRollback = runtime.snapshot;
        boolean staleRollback = runtime.materializedViewStale;
        long versionRollback = runtime.stateVersion;
        long sharedVersionRollback = runtime.sharedSnapshotVersion;
        boolean finalRequestedPatch = patchIndex == patchCount - 1;
        boolean insertSharedSnapshot =
                runtime.snapshotManager != null && finalRequestedPatch;
        long commitStart = System.nanoTime();
        try {
            List<DocumentProcessingRuntime.DocumentUpdateData> updates =
                    runtime.commitBatchPatchResult(
                            commitResult,
                            insertSharedSnapshot,
                            sequenceSnapshotManager());
            advanced = true;
            boolean sharedSnapshotInserted =
                    insertSharedSnapshot
                            && runtime.sharedSnapshotVersion
                            == runtime.stateVersion;
            if (sharedSnapshotInserted) {
                runtime.sequenceSharedSnapshotCacheInserts++;
                runtime.sequenceFinalSnapshotCacheInserts++;
                runtime.observe(
                        ProcessingMetricId
                                .SEQUENCE_SHARED_SNAPSHOT_CACHE_INSERTS,
                        1L);
                runtime.observe(
                        ProcessingMetricId
                                .SEQUENCE_FINAL_SNAPSHOT_CACHE_INSERTS,
                        1L);
            } else {
                runtime.sequenceIntermediateSnapshotAdvances++;
                runtime.observe(
                        ProcessingMetricId
                                .SEQUENCE_INTERMEDIATE_SNAPSHOT_ADVANCES,
                        1L);
            }
            for (DocumentProcessingRuntime.DocumentUpdateData update
                    : updates) {
                runtime.changedPaths.add(
                        PointerUtils.normalizePointer(update.path()));
            }
            rememberCurrentRoots(commitResult);
            patches.set(patchIndex, null);
            return updates;
        } catch (RuntimeException failure) {
            runtime.snapshot = snapshotRollback;
            runtime.materializedViewStale = staleRollback;
            runtime.stateVersion = versionRollback;
            runtime.sharedSnapshotVersion = sharedVersionRollback;
            if (selectedRollback != null) {
                runtime.materializedView.replaceWith(selectedRollback);
                runtime.materializedViewStale = false;
            }
            throw failure;
        } finally {
            long commitNanos = System.nanoTime() - commitStart;
            runtime.batchPatchCommitNanos += commitNanos;
            runtime.observe(
                    ProcessingMetricId.BATCH_PATCH_COMMIT_NANOS,
                    commitNanos);
            runtime.observe(
                    ProcessingMetricId.SEQUENCE_COMMIT_NANOS,
                    commitNanos);
            runtime.observe(
                    ProcessingMetricId.SNAPSHOT_COMMIT_NANOS,
                    commitNanos);
            if (insertSharedSnapshot) {
                runtime.observe(
                        ProcessingMetricId.SEQUENCE_FINAL_CACHE_COMMIT_NANOS,
                        commitNanos);
            }
        }
    }

    private PatchInput patchAt(int patchIndex) {
        if (patchIndex < 0 || patchIndex >= patchCount) {
            throw new IndexOutOfBoundsException(
                    "Patch index outside prepared sequence: " + patchIndex);
        }
        PatchInput patch = patches.get(patchIndex);
        if (patch == null) {
            throw new IllegalStateException(
                    "Patch was already consumed: " + patchIndex);
        }
        return patch;
    }

    private SequentialPatchPlanningSession newPlanningSession(
            SequenceRoots roots,
            int patchIndex) {
        ProcessingSnapshotManager sequenceManager =
                sequenceSnapshotManager(roots, patchIndex);
        ConformanceEngine sequenceConformanceEngine = sequenceManager != null
                ? sequenceManager.transientConformanceEngine(
                        runtime.conformanceEngine)
                : runtime.conformanceEngine != null
                        ? runtime.conformanceEngine.transientView()
                        : null;
        DocumentProcessingRuntime.PlanningContext planning =
                DocumentProcessingRuntime.workingPlanningContext(
                        roots.canonical,
                        roots.resolved,
                        !runtime.selectedDocumentBacked,
                        sequenceManager,
                        runtime.scopes().keySet(),
                        runtime.executableBodyFieldsByType,
                        roots.resolutionComplete);
        return new SequentialPatchPlanningSession(
                originScope,
                planning,
                sequenceConformanceEngine,
                runtime.conformancePlannerOverride,
                runtime.updateMaterializationMetrics(),
                runtime.metrics);
    }

    private ProcessingSnapshotManager sequenceSnapshotManager() {
        if (sequenceSnapshotManager == null
                && runtime.snapshotManager != null) {
            sequenceSnapshotManager = runtime.currentSnapshotManager()
                    .transientSequence();
            activateSequenceSnapshotManager();
        }
        return sequenceSnapshotManager;
    }

    private ProcessingSnapshotManager sequenceSnapshotManager(
            SequenceRoots roots,
            int patchIndex) {
        if (sequenceSnapshotManager != null
                || runtime.snapshotManager == null) {
            return sequenceSnapshotManager;
        }
        WorkingDocument.PatchPreview prepared = preview != null
                ? preview.patch(patchIndex)
                : null;
        if (prepared != null
                && preview.isResolutionScopeCurrent()
                && originScope.equals(prepared.originScope())
                && prepared.matches(patchAt(patchIndex))
                && prepared.isBasedOn(
                        roots.canonical,
                        roots.resolved,
                        roots.resolutionComplete)) {
            sequenceSnapshotManager =
                    preview.takeSequenceSnapshotManager();
        }
        if (sequenceSnapshotManager == null) {
            sequenceSnapshotManager = runtime.currentSnapshotManager()
                    .transientSequence();
        }
        activateSequenceSnapshotManager();
        return sequenceSnapshotManager;
    }

    private void activateSequenceSnapshotManager() {
        if (sequenceSnapshotManager == null
                || runtime.activeSequenceSnapshotManager
                == sequenceSnapshotManager) {
            return;
        }
        previousActiveSequenceSnapshotManager =
                runtime.activeSequenceSnapshotManager;
        runtime.activeSequenceSnapshotManager = sequenceSnapshotManager;
        sequenceSnapshotManagerActivated = true;
    }

    private void refreshInvalidSequenceSnapshotManager() {
        if (sequenceSnapshotManager == null
                || sequenceSnapshotManager.isTransientStateCurrent()) {
            return;
        }
        ProcessingSnapshotManager invalid = sequenceSnapshotManager;
        deactivateSequenceSnapshotManager();
        closePlanningSession();
        sequenceSnapshotManager = null;
        invalid.releaseTransientState();
        sequenceSnapshotManager = runtime.snapshotManager != null
                ? runtime.snapshotManager.transientSequence()
                : null;
        planningSession = null;
        activateSequenceSnapshotManager();
    }

    private void deactivateSequenceSnapshotManager() {
        if (sequenceSnapshotManagerActivated
                && runtime.activeSequenceSnapshotManager
                == sequenceSnapshotManager) {
            runtime.activeSequenceSnapshotManager =
                    previousActiveSequenceSnapshotManager;
        }
        previousActiveSequenceSnapshotManager = null;
        sequenceSnapshotManagerActivated = false;
    }

    private SequenceRoots currentRoots() {
        if (observedVersion == runtime.stateVersion
                && observedCanonical != null
                && observedResolved != null) {
            return new SequenceRoots(
                    observedCanonical,
                    observedResolved,
                    observedResolutionComplete);
        }
        ResolvedSnapshot current = runtime.snapshot;
        if (current != null) {
            observedCanonical = current.frozenCanonicalRoot();
            observedResolved = current.frozenResolvedRoot();
            observedResolutionComplete = current.isResolutionComplete();
        } else {
            DocumentProcessingRuntime.PlanningContext planning =
                    runtime.planningContext(runtime.materializedView.root());
            observedCanonical = planning.canonicalPlanner().root();
            observedResolved = planning.resolvedPlanner().root();
            observedResolutionComplete = planning.isResolutionComplete();
        }
        observedVersion = runtime.stateVersion;
        return new SequenceRoots(
                observedCanonical,
                observedResolved,
                observedResolutionComplete);
    }

    private void rememberCurrentRoots(BatchPatchResult result) {
        if (runtime.snapshot != null) {
            observedCanonical = runtime.snapshot.frozenCanonicalRoot();
            observedResolved = runtime.snapshot.frozenResolvedRoot();
            observedResolutionComplete =
                    runtime.snapshot.isResolutionComplete();
        } else {
            observedCanonical = result.canonicalRoot();
            observedResolved = result.resolvedRoot();
            observedResolutionComplete = result.isResolutionComplete();
        }
        observedVersion = runtime.stateVersion;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (preview != null) {
            preview.discardFrom(0);
        }
        for (int index = 0; index < patches.size(); index++) {
            patches.set(index, null);
        }
        try {
            if (advanced) {
                ProcessingSnapshotManager manager =
                        sequenceSnapshotManager();
                if (manager == null
                        || manager.isTransientStateCurrent()) {
                    runtime.promoteCurrentSequenceSnapshot(manager);
                }
            }
        } catch (RuntimeException | Error failure) {
            ProcessingSnapshotManager failedManager =
                    sequenceSnapshotManager;
            deactivateSequenceSnapshotManager();
            sequenceSnapshotManager = null;
            try {
                closePlanningSession();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failedManager != null) {
                try {
                    failedManager.releaseTransientState();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            throw failure;
        }
        ProcessingSnapshotManager managerToRelease =
                sequenceSnapshotManager;
        deactivateSequenceSnapshotManager();
        closePlanningSession();
        sequenceSnapshotManager = null;
        observedCanonical = null;
        observedResolved = null;
        closed = true;
        if (managerToRelease != null) {
            managerToRelease.releaseTransientState();
        }
    }

    private void closePlanningSession() {
        if (planningSession != null) {
            planningSession.close();
            planningSession = null;
        }
    }

    private static final class SequenceRoots {
        private final FrozenNode canonical;
        private final FrozenNode resolved;
        private final boolean resolutionComplete;

        private SequenceRoots(
                FrozenNode canonical,
                FrozenNode resolved,
                boolean resolutionComplete) {
            this.canonical = Objects.requireNonNull(
                    canonical, "canonical");
            this.resolved = Objects.requireNonNull(
                    resolved, "resolved");
            this.resolutionComplete = resolutionComplete;
        }
    }
}
