package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.ParsedJsonPointer;

import java.util.ArrayList;
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

    List<DocumentUpdateData> apply(
            String originScopePath,
            List<JsonPatch> patches) {
        return applyPatches(
                originScopePath,
                patches,
                PatchSource.LEGACY_PUBLIC_API);
    }

    void writeProcessorState(String path, Node value) {
        validateMutationPathWithoutResolution(path);
        String normalizedPath = PointerUtils.normalizePointer(path);
        JsonPatch processorPatch = value == null
                ? JsonPatch.remove(normalizedPath)
                : JsonPatch.replace(normalizedPath, value);
        PatchInput input = PatchInput.mutable(processorPatch);
        MutationProjection projection =
                preflightProcessorWrite(input);
        gasCharger.charge(
                normalizedPath,
                value == null ? JsonPatch.Op.REMOVE : JsonPatch.Op.REPLACE,
                value,
                null,
                false,
                projection.priorCanonicalRoot,
                projection.resultingCanonicalRoot);
        if (runtime.usesAuthoritativeSelectedSnapshot()) {
            if (projection.materializedSelectedRoot != null) {
                commit.publishSelected(
                        path,
                        value,
                        projection.materializedSelectedRoot);
            } else {
                commit.publishSelected(path, value);
            }
            runtime.changedPaths.add(normalizedPath);
            return;
        }
        if (runtime.snapshotManager != null && runtime.snapshot != null) {
            commit.publishSnapshot(path, value);
            runtime.changedPaths.add(normalizedPath);
            return;
        }
        runtime.snapshotTransactionComponent()
                .publishFallbackDirectWrite(path, value);
    }

    DocumentUpdateData applyPatch(
            String originScopePath,
            JsonPatch patch,
            PatchSource source) {
        if (patch == null) {
            return null;
        }
        List<DocumentUpdateData> updates =
                applyPatches(
                        originScopePath,
                        Collections.singletonList(patch),
                        source);
        return authoredUpdate(updates, patch.getPath());
    }

    List<DocumentUpdateData> applyPatches(
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

    DocumentUpdateData applyFrozenPatch(
            String originScopePath,
            FrozenJsonPatch patch) {
        if (patch == null) {
            return null;
        }
        List<DocumentUpdateData> updates =
                applyFrozenPatches(
                        originScopePath,
                        Collections.singletonList(patch));
        return authoredUpdate(updates, patch.getPath());
    }

    List<DocumentUpdateData> applyFrozenPatches(
            String originScopePath,
            List<FrozenJsonPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        return applyPatchInputs(
                originScopePath,
                PatchInput.frozenList(patches));
    }

    private DocumentUpdateData authoredUpdate(
            List<DocumentUpdateData> updates,
            String authoredPath) {
        if (updates == null || updates.isEmpty()) {
            return null;
        }
        String normalizedPath = PointerUtils.normalizePointer(
                authoredPath);
        for (int index = updates.size() - 1; index >= 0; index--) {
            DocumentUpdateData update = updates.get(index);
            if (normalizedPath.equals(
                    PointerUtils.normalizePointer(update.path()))) {
                return update;
            }
        }
        throw new IllegalStateException(
                "Authored Document Update is missing for "
                        + normalizedPath);
    }

    List<DocumentUpdateData> applyPrecomputedPatch(
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
        runtime.counters().recordBatchPatch(1);
        try {
            chargeSemanticIdentityWork(
                    originScopePath,
                    Collections.singletonList(PatchInput.mutable(patch)));
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
            List<DocumentUpdateData> updates =
                    commitMeasured(result);
            recordChangedPaths(updates);
            return updates;
        } catch (RuntimeException failure) {
            rollback(selectedRollback, snapshotRollback);
            throw failure;
        }
    }

    void chargeSemanticIdentityWork(
            String originScopePath,
            List<PatchInput> patches) {
        MutationGasProjection projection = preflightCanonicalRoots(
                patches,
                !runtime.selectedDocumentBacked,
                originScopePath);
        gasCharger.charge(
                patches,
                projection.priorCanonicalRoots,
                projection.resultingCanonicalRoots);
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

    UpdateMaterializationMetrics
    updateMaterializationMetrics() {
        return new UpdateMaterializationMetrics() {
            @Override
            public void recordBeforeNodeMaterialization() {
                runtime.counters().recordBeforeNodeMaterialization();
                runtime.observe(
                        ProcessingMetricId
                                .DOCUMENT_UPDATE_BEFORE_MATERIALIZATIONS,
                        1L);
            }

            @Override
            public void recordAfterNodeMaterialization() {
                runtime.counters().recordAfterNodeMaterialization();
                runtime.observe(
                        ProcessingMetricId
                                .DOCUMENT_UPDATE_AFTER_MATERIALIZATIONS,
                        1L);
            }
        };
    }

    private List<DocumentUpdateData>
    applyPatchInputs(String originScopePath, List<PatchInput> patches) {
        Node selectedRollback = runtime.selectedDocumentBacked
                ? runtime.materializedView.copyRoot()
                : null;
        ResolvedSnapshot snapshotRollback = runtime.snapshot;
        runtime.counters().recordBatchPatch(patches.size());
        if (patches.size() == 1) {
            runtime.counters().recordSingletonPatchTransaction();
            runtime.observe(
                    ProcessingMetricId.SINGLETON_PATCH_TRANSACTIONS,
                    1L);
        }
        try {
            MutationGasProjection gasProjection =
                    preflightPatchInputsWithoutResolution(
                            originScopePath, patches);
            PatchPlanningContext planning =
                    runtime.planningContext(runtime.materializedView.root());
            gasCharger.charge(
                    patches,
                    gasProjection.priorCanonicalRoots,
                    gasProjection.resultingCanonicalRoots);
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
            List<DocumentUpdateData> updates =
                    commitMeasured(result);
            recordChangedPaths(updates);
            return updates;
        } catch (RuntimeException failure) {
            rollback(selectedRollback, snapshotRollback);
            throw failure;
        }
    }

    private MutationGasProjection preflightPatchInputsWithoutResolution(
            String originScopePath,
            List<PatchInput> patches) {
        ProcessingSnapshotManager evidenceManager =
                runtime.currentSnapshotManager();
        ReferenceTransparentPathAccess pathAccess =
                new ReferenceTransparentPathAccess(
                        evidenceManager,
                        runtime.strictPlatformInvocation,
                        runtime.executableBodyFieldsByType);
        FrozenNode workingCanonical =
                runtime.canonicalRootWithoutResolution();
        FrozenNode workingResolved =
                runtime.resolvedRootWithoutResolution();
        FrozenNode workingIdentityCanonical = null;
        boolean canProjectStrictIdentity = false;
        try {
            workingIdentityCanonical =
                    runtime.identityChargeCanonicalRoot();
            canProjectStrictIdentity =
                    !workingIdentityCanonical.containsCyclicSetReference();
        } catch (IllegalArgumentException unsupportedIdentityProjection) {
            /*
             * Intrinsic/preprocessing-only shapes are rejected later by the
             * normal mutation boundary with its precise processor category.
             * Gas projection must not replace that failure with a generic
             * strict-freeze diagnostic.
             */
        }
        boolean defaultExactReplacement = !runtime.selectedDocumentBacked;
        List<FrozenNode> priorCanonicalRoots =
                new ArrayList<FrozenNode>(patches.size());
        List<FrozenNode> resultingCanonicalRoots =
                new ArrayList<FrozenNode>(patches.size());
        for (PatchInput input : patches) {
            if (input == null) {
                FrozenNode unchanged = canProjectStrictIdentity
                        ? workingIdentityCanonical
                        : workingCanonical;
                priorCanonicalRoots.add(unchanged);
                resultingCanonicalRoots.add(unchanged);
                continue;
            }
            ImmutablePatchPlanner canonicalPlanner =
                    ImmutablePatchPlanner.forFrozen(workingCanonical);
            ParsedJsonPointer path =
                    ParsedJsonPointer.parse(input.authoredPath());
            boolean exactReplacement = defaultExactReplacement
                    || ProcessorOwnedContractsStatePreserver
                            .requiresExactReplacement(
                                    originScopePath,
                                    input.op(),
                                    path.pointer());
            canonicalPlanner.validateMutationPath(path);
            FrozenNode expandedCanonical = pathAccess
                    .materializePatchAncestors(
                            workingCanonical,
                            Collections.singletonList(path.pointer()));
            if (expandedCanonical != workingCanonical) {
                ResolvedSnapshot expanded = resolvePreflightCanonical(
                        evidenceManager, expandedCanonical);
                workingCanonical = expanded.frozenCanonicalRoot();
                workingResolved = expanded.frozenResolvedRoot();
                canonicalPlanner = ImmutablePatchPlanner.forFrozen(
                        workingCanonical);
            }
            ImmutablePatchPlanner resolvedPlanner =
                    ImmutablePatchPlanner.forFrozen(workingResolved);
            if (!path.isRoot()
                    && resolvedPlanner.read(path.parent()) == null) {
                throw new IllegalStateException(
                        "Final parent does not exist for patch path: "
                                + path.pointer());
            }
            if (canProjectStrictIdentity) {
                workingIdentityCanonical = pathAccess
                        .materializePatchAncestors(
                                workingIdentityCanonical,
                                Collections.singletonList(path.pointer()));
            }
            FrozenNode priorCanonical = canProjectStrictIdentity
                    ? workingIdentityCanonical
                    : workingCanonical;
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
            FrozenNode resultingCanonical;
            if (canProjectStrictIdentity) {
                ImmutablePatchPlanner identityPlanner =
                        ImmutablePatchPlanner.forFrozen(
                                workingIdentityCanonical);
                workingIdentityCanonical = identityPlanner.applyMutationPreflight(
                        input.op(),
                        path,
                        preflightValue(input, workingIdentityCanonical),
                        exactReplacement);
                resultingCanonical = workingIdentityCanonical;
            } else {
                resultingCanonical = workingCanonical;
            }
            priorCanonicalRoots.add(priorCanonical);
            resultingCanonicalRoots.add(resultingCanonical);
        }
        return new MutationGasProjection(
                priorCanonicalRoots,
                resultingCanonicalRoots);
    }

    private MutationGasProjection preflightCanonicalRoots(
            List<PatchInput> patches,
            boolean defaultExactReplacement,
            String originScopePath) {
        ReferenceTransparentPathAccess pathAccess =
                new ReferenceTransparentPathAccess(
                        runtime.currentSnapshotManager(),
                        runtime.strictPlatformInvocation,
                        runtime.executableBodyFieldsByType);
        FrozenNode workingCanonical =
                runtime.identityChargeCanonicalRoot();
        List<FrozenNode> priorCanonicalRoots =
                new ArrayList<FrozenNode>(patches.size());
        List<FrozenNode> resultingCanonicalRoots =
                new ArrayList<FrozenNode>(patches.size());
        for (PatchInput input : patches) {
            if (input == null) {
                priorCanonicalRoots.add(workingCanonical);
                resultingCanonicalRoots.add(workingCanonical);
                continue;
            }
            ImmutablePatchPlanner planner =
                    ImmutablePatchPlanner.forFrozen(workingCanonical);
            ParsedJsonPointer path =
                    ParsedJsonPointer.parse(input.authoredPath());
            boolean exactReplacement = defaultExactReplacement
                    || ProcessorOwnedContractsStatePreserver
                            .requiresExactReplacement(
                                    originScopePath,
                                    input.op(),
                                    path.pointer());
            planner.validateMutationPath(path);
            workingCanonical = pathAccess.materializePatchAncestors(
                    workingCanonical,
                    Collections.singletonList(path.pointer()));
            priorCanonicalRoots.add(workingCanonical);
            planner = ImmutablePatchPlanner.forFrozen(workingCanonical);
            workingCanonical = planner.applyMutationPreflight(
                    input.op(),
                    path,
                    preflightValue(input, workingCanonical),
                    exactReplacement);
            resultingCanonicalRoots.add(workingCanonical);
        }
        return new MutationGasProjection(
                priorCanonicalRoots,
                resultingCanonicalRoots);
    }

    private static final class MutationGasProjection {
        private final List<FrozenNode> priorCanonicalRoots;
        private final List<FrozenNode> resultingCanonicalRoots;

        private MutationGasProjection(
                List<FrozenNode> priorCanonicalRoots,
                List<FrozenNode> resultingCanonicalRoots) {
            this.priorCanonicalRoots = Collections.unmodifiableList(
                    new ArrayList<FrozenNode>(priorCanonicalRoots));
            this.resultingCanonicalRoots = Collections.unmodifiableList(
                    new ArrayList<FrozenNode>(resultingCanonicalRoots));
        }
    }

    private ResolvedSnapshot resolvePreflightCanonical(
            ProcessingSnapshotManager manager,
            FrozenNode canonicalRoot) {
        if (manager == null) {
            throw new IllegalStateException(
                    "Reference ancestor requires a processing snapshot manager");
        }
        return runtime.strictPlatformInvocation
                ? DocumentProcessingRuntime
                        .resolveCanonicalTransientIncludingTypeContracts(
                                manager,
                                canonicalRoot,
                                runtime.scopes().keySet(),
                                runtime.executableBodyFieldsByType)
                : DocumentProcessingRuntime.resolveCanonicalTransient(
                        manager,
                        canonicalRoot,
                        runtime.scopes().keySet(),
                        runtime.executableBodyFieldsByType);
    }

    private MutationProjection preflightProcessorWrite(PatchInput input) {
        ParsedJsonPointer path =
                ParsedJsonPointer.parse(input.authoredPath());
        FrozenNode strictCanonical =
                strictIdentityChargeCanonicalRootOrNull();
        if (strictCanonical != null) {
            FrozenNode strictValue =
                    preflightValue(input, strictCanonical);
            try {
                FrozenNode strictResult = ImmutablePatchPlanner
                        .forFrozen(strictCanonical)
                        .applyMutationPreflight(
                                input.op(),
                                path,
                                strictValue,
                                true);
                return new MutationProjection(
                        strictCanonical,
                        strictResult,
                        null);
            } catch (IllegalArgumentException unsupportedStrictProjection) {
                if (!hasReferenceOnlyAncestor(strictCanonical, path)) {
                    throw unsupportedStrictProjection;
                }
            }
        }
        if (runtime.usesAuthoritativeSelectedSnapshot()) {
            Node materializedSelectedRoot =
                    commit.materializedSelectedRoot(path.pointer());
            FrozenNode materializedCanonical =
                    FrozenNode.fromNode(materializedSelectedRoot);
            FrozenNode materializedResult = ImmutablePatchPlanner
                    .forFrozen(materializedCanonical)
                    .applyMutationPreflight(
                            input.op(),
                            path,
                            preflightValue(input, materializedCanonical),
                            true);
            return new MutationProjection(
                    materializedCanonical,
                    materializedResult,
                    materializedSelectedRoot);
        }
        FrozenNode resolvedCanonical =
                runtime.resolvedRootWithoutResolution();
        FrozenNode resolvedResult = ImmutablePatchPlanner
                .forFrozen(resolvedCanonical)
                .applyMutationPreflight(
                        input.op(),
                        path,
                        preflightValue(input, resolvedCanonical),
                        true);
        return new MutationProjection(
                resolvedCanonical,
                resolvedResult,
                null);
    }

    private FrozenNode strictIdentityChargeCanonicalRootOrNull() {
        try {
            FrozenNode strictIdentityCanonical =
                    runtime.identityChargeCanonicalRoot();
            return strictIdentityCanonical.containsCyclicSetReference()
                    ? null
                    : strictIdentityCanonical;
        } catch (IllegalArgumentException unsupportedIdentityProjection) {
            /*
             * Intrinsic/preprocessing-only shapes are rejected later by the
             * normal mutation boundary with its precise processor category.
             * Gas projection must not replace that failure with a generic
             * strict-freeze diagnostic.
             */
            return null;
        }
    }

    private boolean hasReferenceOnlyAncestor(
            FrozenNode root,
            ParsedJsonPointer path) {
        if (path.isRoot()) {
            return false;
        }
        if (root.isReferenceOnly()) {
            return true;
        }
        List<String> segments = path.segments();
        for (int count = 1; count < segments.size(); count++) {
            FrozenNode ancestor = root.at(
                    JsonPointer.toPointer(
                            segments.subList(0, count)));
            if (ancestor == null) {
                return false;
            }
            if (ancestor.isReferenceOnly()) {
                return true;
            }
        }
        return false;
    }

    private static final class MutationProjection {
        private final FrozenNode priorCanonicalRoot;
        private final FrozenNode resultingCanonicalRoot;
        private final Node materializedSelectedRoot;

        private MutationProjection(
                FrozenNode priorCanonicalRoot,
                FrozenNode resultingCanonicalRoot,
                Node materializedSelectedRoot) {
            this.priorCanonicalRoot = priorCanonicalRoot;
            this.resultingCanonicalRoot = resultingCanonicalRoot;
            this.materializedSelectedRoot = materializedSelectedRoot;
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

    private List<DocumentUpdateData> commitMeasured(
            BatchPatchResult result) {
        long commitStart = System.nanoTime();
        try {
            return runtime.commitBatchPatchResult(
                    result,
                    true,
                    runtime.currentSnapshotManager());
        } finally {
            long commitNanos = System.nanoTime() - commitStart;
            runtime.counters().recordCommitNanos(commitNanos);
            runtime.observe(
                    ProcessingMetricId.BATCH_PATCH_COMMIT_NANOS,
                    commitNanos);
            runtime.observe(
                    ProcessingMetricId.SNAPSHOT_COMMIT_NANOS,
                    commitNanos);
        }
    }

    private void recordPlanningMetrics(BatchPatchResult result) {
        runtime.counters().recordPatchPlanningNanos(
                result.patchPlanningNanos());
        runtime.counters().recordConformanceNanos(
                result.conformanceNanos());
        runtime.counters().recordBuildUpdatesNanos(
                result.buildUpdatesNanos());
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
        runtime.counters().recordBuildUpdatesNanos(nanos);
        runtime.observe(
                ProcessingMetricId.BATCH_PATCH_BUILD_UPDATES_NANOS,
                nanos);
    }

    private void recordChangedPaths(
            List<DocumentUpdateData> updates) {
        for (DocumentUpdateData update : updates) {
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
