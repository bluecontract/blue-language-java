package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.JsonPointer;
import blue.language.utils.ParsedJsonPointer;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Invocation-scoped mutation boundary over one transactional Root.
 *
 * <p>Admission, identity charging, planning, conformance, publication, and
 * rollback remain one deterministic transaction. The session retains no
 * state independent from its owning runtime.</p>
 */
final class ProcessingMutationSession {

    private final DocumentProcessingRuntime runtime;
    private final MutationGasCharger gasCharger;
    private final MutationCommit commit;

    ProcessingMutationSession(DocumentProcessingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.gasCharger = new MutationGasCharger(
                runtime.gasMeter(),
                runtime::identityChargeCanonicalRoot);
        this.commit = new MutationCommit(runtime);
    }

    WorkingDocument workingDocument(String originScopePath) {
        return runtime.workingDocument(originScopePath);
    }

    List<DocumentProcessingRuntime.DocumentUpdateData> apply(
            String originScopePath,
            List<JsonPatch> patches) {
        return applyPatches(
                originScopePath,
                patches,
                PatchSource.LEGACY_PUBLIC_API);
    }

    void writeProcessorState(String path, Node value) {
        validateMutationPathWithoutResolution(path);
        gasCharger.charge(
                PointerUtils.normalizePointer(path),
                value == null ? JsonPatch.Op.REMOVE : JsonPatch.Op.REPLACE,
                value,
                null,
                false);
        if (runtime.usesAuthoritativeSelectedSnapshot()) {
            commit.publishSelected(path, value);
            runtime.changedPaths.add(PointerUtils.normalizePointer(path));
            return;
        }
        if (runtime.snapshotManager != null && runtime.snapshot != null) {
            commit.publishSnapshot(path, value);
            runtime.changedPaths.add(PointerUtils.normalizePointer(path));
            return;
        }
        runtime.snapshotTransactionComponent()
                .publishFallbackDirectWrite(path, value);
    }

    DocumentProcessingRuntime.DocumentUpdateData applyPatch(
            String originScopePath,
            JsonPatch patch,
            PatchSource source) {
        if (patch == null) {
            return null;
        }
        List<DocumentProcessingRuntime.DocumentUpdateData> updates =
                applyPatches(
                        originScopePath,
                        Collections.singletonList(patch),
                        source);
        return updates.isEmpty() ? null : updates.get(0);
    }

    List<DocumentProcessingRuntime.DocumentUpdateData> applyPatches(
            String originScopePath,
            List<JsonPatch> patches,
            PatchSource source) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        return applyPatchInputs(
                originScopePath,
                PatchInput.mutableList(patches, source));
    }

    DocumentProcessingRuntime.DocumentUpdateData applyFrozenPatch(
            String originScopePath,
            FrozenJsonPatch patch) {
        if (patch == null) {
            return null;
        }
        List<DocumentProcessingRuntime.DocumentUpdateData> updates =
                applyFrozenPatches(
                        originScopePath,
                        Collections.singletonList(patch));
        return updates.isEmpty() ? null : updates.get(0);
    }

    List<DocumentProcessingRuntime.DocumentUpdateData> applyFrozenPatches(
            String originScopePath,
            List<FrozenJsonPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        return applyPatchInputs(
                originScopePath,
                PatchInput.frozenList(patches));
    }

    List<DocumentProcessingRuntime.DocumentUpdateData> applyPrecomputedPatch(
            String originScopePath,
            JsonPatch patch,
            WorkingDocument.PatchPreview preview) {
        if (patch == null) {
            return Collections.emptyList();
        }
        if (!canApplyPrecomputedPatch(originScopePath, patch, preview)) {
            return applyPatches(
                    originScopePath,
                    Collections.singletonList(patch),
                    PatchSource.LEGACY_PUBLIC_API);
        }
        Node selectedRollback = runtime.selectedDocumentBacked
                ? runtime.materializedView.copyRoot()
                : null;
        ResolvedSnapshot snapshotRollback = runtime.snapshot;
        runtime.batchPatchCalls++;
        runtime.batchPatchEntries++;
        try {
            chargeSemanticIdentityWork(Collections.singletonList(
                    PatchInput.mutable(patch)));
            long buildUpdatesStart = System.nanoTime();
            BatchPatchResult result;
            try {
                result = runtime.usesAuthoritativeSelectedSnapshot()
                        ? preview.result()
                        : preview.result().withMaterializationMetrics(
                                updateMaterializationMetrics());
            } finally {
                recordBuildUpdatesNanos(
                        System.nanoTime() - buildUpdatesStart);
            }
            List<DocumentProcessingRuntime.DocumentUpdateData> updates =
                    commitMeasured(result);
            recordChangedPaths(updates);
            return updates;
        } catch (RuntimeException failure) {
            rollback(selectedRollback, snapshotRollback);
            throw failure;
        }
    }

    void chargeSemanticIdentityWork(List<PatchInput> patches) {
        gasCharger.charge(patches);
    }

    void validateMutationPathWithoutResolution(PatchInput patch) {
        if (patch != null) {
            validateMutationPathWithoutResolution(patch.authoredPath());
        }
    }

    void validateProcessEmbeddedTraversalWithoutResolution(String path) {
        ImmutablePatchPlanner.forFrozen(
                runtime.canonicalRootWithoutResolution())
                .validateProcessEmbeddedTraversalPath(path);
    }

    void enforcePortableLimit(
            ProcessorErrorCategory category,
            String limitName,
            long observed) {
        gasCharger.enforcePortableLimit(category, limitName, observed);
    }

    DocumentProcessingRuntime.UpdateMaterializationMetrics
    updateMaterializationMetrics() {
        return new DocumentProcessingRuntime.UpdateMaterializationMetrics() {
            @Override
            public void recordBeforeNodeMaterialization() {
                runtime.documentUpdateBeforeNodeMaterializations++;
                runtime.observe(
                        ProcessingMetricId
                                .DOCUMENT_UPDATE_BEFORE_MATERIALIZATIONS,
                        1L);
            }

            @Override
            public void recordAfterNodeMaterialization() {
                runtime.documentUpdateAfterNodeMaterializations++;
                runtime.observe(
                        ProcessingMetricId
                                .DOCUMENT_UPDATE_AFTER_MATERIALIZATIONS,
                        1L);
            }
        };
    }

    private List<DocumentProcessingRuntime.DocumentUpdateData>
    applyPatchInputs(String originScopePath, List<PatchInput> patches) {
        Node selectedRollback = runtime.selectedDocumentBacked
                ? runtime.materializedView.copyRoot()
                : null;
        ResolvedSnapshot snapshotRollback = runtime.snapshot;
        runtime.batchPatchCalls++;
        runtime.batchPatchEntries += patches.size();
        if (patches.size() == 1) {
            runtime.singletonPatchTransactions++;
            runtime.observe(
                    ProcessingMetricId.SINGLETON_PATCH_TRANSACTIONS,
                    1L);
        }
        try {
            preflightPatchInputsWithoutResolution(patches);
            DocumentProcessingRuntime.PlanningContext planning =
                    runtime.planningContext(runtime.materializedView.root());
            chargeSemanticIdentityWork(patches);
            BatchPatchTransaction transaction =
                    BatchPatchTransaction.fromInputs(
                            originScopePath,
                            patches,
                            planning,
                            runtime.currentConformanceEngine(),
                            runtime.conformancePlannerOverride,
                            updateMaterializationMetrics(),
                            !runtime.usesAuthoritativeSelectedSnapshot(),
                            runtime.metrics);
            BatchPatchResult result = transaction.apply();
            recordPlanningMetrics(result);
            List<DocumentProcessingRuntime.DocumentUpdateData> updates =
                    commitMeasured(result);
            recordChangedPaths(updates);
            return updates;
        } catch (RuntimeException failure) {
            rollback(selectedRollback, snapshotRollback);
            throw failure;
        }
    }

    private void preflightPatchInputsWithoutResolution(
            List<PatchInput> patches) {
        FrozenNode workingCanonical =
                runtime.canonicalRootWithoutResolution();
        FrozenNode workingResolved =
                runtime.resolvedRootWithoutResolution();
        boolean exactReplacement = !runtime.selectedDocumentBacked;
        for (PatchInput input : patches) {
            if (input == null) {
                continue;
            }
            ImmutablePatchPlanner canonicalPlanner =
                    ImmutablePatchPlanner.forFrozen(workingCanonical);
            ImmutablePatchPlanner resolvedPlanner =
                    ImmutablePatchPlanner.forFrozen(workingResolved);
            ParsedJsonPointer path =
                    ParsedJsonPointer.parse(input.authoredPath());
            canonicalPlanner.validateMutationPath(path);
            if (!path.isRoot()
                    && resolvedPlanner.read(path.parent()) == null) {
                throw new IllegalStateException(
                        "Final parent does not exist for patch path: "
                                + path.pointer());
            }
            workingCanonical = canonicalPlanner.applyMutationPreflight(
                    input.op(),
                    path,
                    preflightValue(input, workingCanonical),
                    exactReplacement);
            workingResolved = resolvedPlanner.applyMutationPreflight(
                    input.op(),
                    path,
                    preflightValue(input, workingResolved),
                    exactReplacement);
        }
    }

    private FrozenNode preflightValue(
            PatchInput input,
            FrozenNode modeRoot) {
        if (input.op() == JsonPatch.Op.REMOVE) {
            return null;
        }
        FrozenNode frozen = input.frozenValue();
        if (frozen != null) {
            return FrozenNode.authoredValueInModeOf(frozen, modeRoot);
        }
        Node value = Objects.requireNonNull(
                input.mutableValue(), "patch value");
        if (!modeRoot.isStrictCanonical()) {
            return FrozenNode.fromResolvedNode(value);
        }
        return modeRoot.isStrictBlueIdValidation()
                ? FrozenNode.fromNode(value)
                : FrozenNode.fromUncheckedCanonicalNode(value);
    }

    private void validateMutationPathWithoutResolution(String path) {
        ImmutablePatchPlanner.forFrozen(
                runtime.canonicalRootWithoutResolution())
                .validateMutationPath(path);
    }

    private boolean canApplyPrecomputedPatch(
            String originScopePath,
            JsonPatch patch,
            WorkingDocument.PatchPreview preview) {
        if (preview == null
                || !PointerUtils.normalizeScope(originScopePath)
                        .equals(preview.originScope())
                || !preview.matches(patch)) {
            return false;
        }
        ResolvedSnapshot current = runtime.snapshot();
        return current != null
                && preview.isBasedOn(
                        current.frozenCanonicalRoot(),
                        current.frozenResolvedRoot(),
                        current.isResolutionComplete());
    }

    private List<DocumentProcessingRuntime.DocumentUpdateData> commitMeasured(
            BatchPatchResult result) {
        long commitStart = System.nanoTime();
        try {
            return runtime.commitBatchPatchResult(
                    result,
                    true,
                    runtime.currentSnapshotManager());
        } finally {
            long commitNanos = System.nanoTime() - commitStart;
            runtime.batchPatchCommitNanos += commitNanos;
            runtime.observe(
                    ProcessingMetricId.BATCH_PATCH_COMMIT_NANOS,
                    commitNanos);
            runtime.observe(
                    ProcessingMetricId.SNAPSHOT_COMMIT_NANOS,
                    commitNanos);
        }
    }

    private void recordPlanningMetrics(BatchPatchResult result) {
        runtime.batchPatchPlanningNanos += result.patchPlanningNanos();
        runtime.batchPatchConformanceNanos += result.conformanceNanos();
        runtime.batchPatchBuildUpdatesNanos += result.buildUpdatesNanos();
        runtime.observe(
                ProcessingMetricId.BATCH_PATCH_PLANNING_NANOS,
                result.patchPlanningNanos());
        runtime.observe(
                ProcessingMetricId.BATCH_PATCH_CONFORMANCE_NANOS,
                result.conformanceNanos());
        runtime.observe(
                ProcessingMetricId.BATCH_PATCH_BUILD_UPDATES_NANOS,
                result.buildUpdatesNanos());
    }

    private void recordBuildUpdatesNanos(long nanos) {
        runtime.batchPatchBuildUpdatesNanos += nanos;
        runtime.observe(
                ProcessingMetricId.BATCH_PATCH_BUILD_UPDATES_NANOS,
                nanos);
    }

    private void recordChangedPaths(
            List<DocumentProcessingRuntime.DocumentUpdateData> updates) {
        for (DocumentProcessingRuntime.DocumentUpdateData update : updates) {
            runtime.changedPaths.add(
                    PointerUtils.normalizePointer(update.path()));
        }
    }

    private void rollback(
            Node selectedRollback,
            ResolvedSnapshot snapshotRollback) {
        runtime.snapshot = snapshotRollback;
        if (selectedRollback != null) {
            runtime.materializedView.replaceWith(selectedRollback);
            runtime.materializedViewStale = false;
        } else if (snapshotRollback != null) {
            runtime.materializedView.replaceWithSnapshot(snapshotRollback);
            runtime.materializedViewStale = false;
        }
    }
}
