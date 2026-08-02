package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.ParsedJsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared immutable patch-planning core.
 *
 * <p>An atomic caller plans every raw patch before one conformance pass. A
 * sequential caller reuses this engine but finishes conformance after each
 * individual patch. Keeping both modes here prevents their patch,
 * generalization, and authoritative-resolution rules from drifting apart.</p>
 */
final class PatchPlanningEngine {

    private final String originScopePath;
    private final FrozenNode initialCanonicalRoot;
    private final FrozenNode initialResolvedRoot;
    private final boolean exactReplacement;
    private final ProcessingSnapshotManager authoritativeSnapshotManager;
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final UpdateMaterializationMetrics materializationMetrics;
    private final ImmutableJsonPatch.PreparationContext patchPreparation;
    private final ProcessingObserver metrics;
    private final PatchImpactAnalyzer impactAnalyzer;
    private final Set<String> openedScopePaths;
    private final Map<String, List<String>> executableBodyFieldsByType;
    private final boolean initialResolutionComplete;

    PatchPlanningEngine(String originScopePath,
                        PatchPlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        UpdateMaterializationMetrics materializationMetrics) {
        this(originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                NoOpProcessingObserver.INSTANCE,
                true);
    }

    PatchPlanningEngine(String originScopePath,
                        PatchPlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        UpdateMaterializationMetrics materializationMetrics,
                        ProcessingObserver metrics) {
        this(originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                metrics,
                true);
    }

    PatchPlanningEngine(String originScopePath,
                        PatchPlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        UpdateMaterializationMetrics materializationMetrics,
                        ProcessingObserver metrics,
                        boolean retainInitialRoots) {
        this.originScopePath = originScopePath;
        Objects.requireNonNull(planning, "planning");
        FrozenNode canonicalRoot = planning.baseSnapshot() != null
                ? planning.baseSnapshot().frozenCanonicalRoot()
                : planning.canonicalPlanner().root();
        FrozenNode resolvedRoot = planning.baseSnapshot() != null
                ? planning.baseSnapshot().frozenResolvedRoot()
                : planning.resolvedPlanner().root();
        this.initialCanonicalRoot = retainInitialRoots ? canonicalRoot : null;
        this.initialResolvedRoot = retainInitialRoots ? resolvedRoot : null;
        this.exactReplacement = planning.exactReplacement();
        this.authoritativeSnapshotManager = planning.authoritativeSnapshotManager();
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.materializationMetrics = materializationMetrics;
        this.metrics = metrics != null ? metrics : NoOpProcessingObserver.INSTANCE;
        this.patchPreparation = ImmutableJsonPatch.preparationContext(this.metrics);
        this.impactAnalyzer = new PatchImpactAnalyzer(conformanceEngine,
                conformancePlannerOverride,
                authoritativeSnapshotManager,
                this.metrics);
        this.openedScopePaths =
                new LinkedHashSet<>(planning.openedScopePaths());
        this.openedScopePaths.add(
                PointerUtils.normalizeScope(originScopePath));
        this.executableBodyFieldsByType =
                planning.executableBodyFieldsByType();
        this.initialResolutionComplete =
                planning.isResolutionComplete();
    }

    BatchPatchResult planAtomic(List<JsonPatch> patches, boolean buildUpdates) {
        if (initialCanonicalRoot == null || initialResolvedRoot == null) {
            throw new IllegalStateException("Atomic planning roots were not retained");
        }
        List<ImmutableJsonPatch> prepared = preparePatches(patches,
                initialCanonicalRoot,
                initialResolvedRoot);
        return plan(prepared,
                initialCanonicalRoot,
                initialResolvedRoot,
                initialResolutionComplete,
                buildUpdates);
    }

    BatchPatchResult planAtomicInputs(List<PatchInput> patches, boolean buildUpdates) {
        if (initialCanonicalRoot == null || initialResolvedRoot == null) {
            throw new IllegalStateException("Atomic planning roots were not retained");
        }
        List<ImmutableJsonPatch> prepared = preparePatchInputs(patches,
                initialCanonicalRoot,
                initialResolvedRoot);
        return plan(prepared,
                initialCanonicalRoot,
                initialResolvedRoot,
                initialResolutionComplete,
                buildUpdates);
    }

    BatchPatchResult planSequentialStep(FrozenNode canonicalRoot,
                                        FrozenNode resolvedRoot,
                                        JsonPatch patch) {
        FrozenNode checkedCanonical = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        FrozenNode checkedResolved = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        ImmutableJsonPatch prepared = preparePatch(patch, checkedCanonical, checkedResolved);
        return planSequentialStep(checkedCanonical, checkedResolved, prepared);
    }

    ImmutableJsonPatch preparePatch(JsonPatch patch,
                                    FrozenNode canonicalRoot,
                                    FrozenNode resolvedRoot) {
        return patchPreparation.prepare(Objects.requireNonNull(patch, "patch"),
                Objects.requireNonNull(canonicalRoot, "canonicalRoot"),
                Objects.requireNonNull(resolvedRoot, "resolvedRoot"));
    }

    ImmutableJsonPatch preparePatch(PatchInput patch,
                                    FrozenNode canonicalRoot,
                                    FrozenNode resolvedRoot) {
        return Objects.requireNonNull(patch, "patch").prepare(patchPreparation,
                Objects.requireNonNull(canonicalRoot, "canonicalRoot"),
                Objects.requireNonNull(resolvedRoot, "resolvedRoot"));
    }

    BatchPatchResult planSequentialStep(FrozenNode canonicalRoot,
                                        FrozenNode resolvedRoot,
                                        ImmutableJsonPatch patch) {
        return planSequentialStep(
                canonicalRoot,
                resolvedRoot,
                initialResolutionComplete,
                patch);
    }

    BatchPatchResult planSequentialStep(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean resolutionComplete,
            ImmutableJsonPatch patch) {
        return plan(Collections.singletonList(Objects.requireNonNull(patch, "patch")),
                Objects.requireNonNull(canonicalRoot, "canonicalRoot"),
                Objects.requireNonNull(resolvedRoot, "resolvedRoot"),
                resolutionComplete,
                false);
    }

    List<ImmutableJsonPatch> preparePatches(List<JsonPatch> patches,
                                            FrozenNode canonicalRoot,
                                            FrozenNode resolvedRoot) {
        Objects.requireNonNull(patches, "patches");
        List<ImmutableJsonPatch> prepared = new ArrayList<>(patches.size());
        for (JsonPatch patch : patches) {
            prepared.add(preparePatch(patch, canonicalRoot, resolvedRoot));
        }
        return Collections.unmodifiableList(prepared);
    }

    List<ImmutableJsonPatch> preparePatchInputs(List<PatchInput> patches,
                                                FrozenNode canonicalRoot,
                                                FrozenNode resolvedRoot) {
        Objects.requireNonNull(patches, "patches");
        List<ImmutableJsonPatch> prepared = new ArrayList<>(patches.size());
        for (PatchInput patch : patches) {
            prepared.add(preparePatch(patch, canonicalRoot, resolvedRoot));
        }
        return Collections.unmodifiableList(prepared);
    }

    private BatchPatchResult plan(List<ImmutableJsonPatch> patches,
                                  FrozenNode initialCanonical,
                                  FrozenNode initialResolved,
                                  boolean initialResolutionComplete,
                                  boolean buildUpdates) {
        Objects.requireNonNull(patches, "patches");
        long planningStart = System.nanoTime();
        FrozenNode workingCanonical = initialCanonical;
        FrozenNode workingResolved = initialResolved;
        PatchImpact.FallbackReason authoritativeFallbackReason = null;
        List<BatchPatchRecord> records = new ArrayList<>();
        List<ImmutableJsonPatch> preparedPatches = new ArrayList<>(patches.size());
        for (ImmutableJsonPatch prepared : patches) {
            Objects.requireNonNull(prepared, "patch");
            preparedPatches.add(prepared);
            ImmutablePatchPlanner canonicalPlanner = ImmutablePatchPlanner.forFrozen(workingCanonical);
            ImmutablePatchPlanner.PatchPlan canonicalPlan = exactReplacement
                    ? canonicalPlanner.planWithExactReplacement(originScopePath, prepared)
                    : canonicalPlanner.plan(originScopePath, prepared);
            ImmutableJsonPatch resolvedPatch = resolveProcessorManagedValue(
                    prepared, canonicalPlan);
            ImmutablePatchPlanner resolvedPlanner = ImmutablePatchPlanner.forFrozen(workingResolved);
            boolean objectMemberTarget = targetsObjectMember(
                    resolvedPlanner,
                    resolvedPatch.path());
            ImmutablePatchPlanner.PatchPlan resolvedPlan = exactReplacement
                    ? resolvedPlanner.planWithExactReplacement(originScopePath, resolvedPatch)
                    : resolvedPlanner.plan(originScopePath, resolvedPatch);
            PatchImpact impact = impactAnalyzer.analyze(exactReplacement,
                    workingCanonical,
                    workingResolved,
                    canonicalPlan,
                    resolvedPlan,
                    resolvedPatch);
            if (impact.resolvedScalarMetadataPreservationRequired()) {
                resolvedPlan = resolvedPlanner.planWithPreservedResolvedScalarMetadata(
                        originScopePath, prepared);
            }
            if (exactReplacement
                    && !impact.localResolutionProvenSafe()
                    && authoritativeFallbackReason == null) {
                authoritativeFallbackReason = impact.fallbackReason();
            }
            BatchPatchRecord record = new BatchPatchRecord(resolvedPatch,
                    canonicalPlan,
                    resolvedPlan,
                    objectMemberTarget,
                    impact,
                    isProcessorManagedConformanceBypass(canonicalPlan));
            records.add(record);
            workingCanonical = canonicalPlan.root();
            workingResolved = resolvedPlan.root();
        }
        long patchPlanningNanos = System.nanoTime() - planningStart;

        long conformanceStart = System.nanoTime();
        FrozenNode preConformanceResolved = workingResolved;
        ConformancePlan conformancePlan = planBatchConformance(workingCanonical, workingResolved, records);
        long conformanceNanos = System.nanoTime() - conformanceStart;
        FrozenNode finalCanonical = conformancePlan.canonicalRoot() != null
                ? conformancePlan.canonicalRoot()
                : workingCanonical;
        FrozenNode finalResolved = conformancePlan.root();
        boolean finalResolutionComplete =
                initialResolutionComplete;
        boolean fullSnapshotResolution = exactReplacement
                && (authoritativeFallbackReason != null || !conformancePlan.fullSnapshotRebuildAvoidable());
        if (fullSnapshotResolution) {
            if (authoritativeSnapshotManager == null) {
                throw new IllegalStateException("Authoritative snapshot resolution is unavailable");
            }
            PatchImpact.FallbackReason reason = authoritativeFallbackReason != null
                    ? authoritativeFallbackReason
                    : PatchImpact.FallbackReason.DEPENDENCY_INDEX_MISSING_OR_STALE;
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_SNAPSHOT_FALLBACKS, 1L);
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_SNAPSHOT_FALLBACK_REASON,
                    1L,
                    ProcessingObservationContext.of(
                            ProcessingObservationDimension.FALLBACK_REASON,
                            reason.name()));
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_CANONICAL_ROOT_MATERIALIZATIONS, 1L);
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_FROZEN_ROOT_TO_NODE_MATERIALIZATIONS, 1L);
            ResolvedSnapshot authoritative =
                    DocumentProcessingRuntime
                    .resolveCanonicalTransient(
                            authoritativeSnapshotManager,
                            finalCanonical,
                            openedScopePaths,
                            executableBodyFieldsByType);
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FULL_RESOLVED_ROOT_MATERIALIZATIONS, 1L);
            finalCanonical = authoritative.frozenCanonicalRoot();
            finalResolved = authoritative.frozenResolvedRoot();
            finalResolutionComplete =
                    authoritative.isResolutionComplete();
        } else if (exactReplacement) {
            for (BatchPatchRecord record : records) {
                if (record.impact().localResolutionProvenSafe()) {
                    ProcessingObservations.record(metrics,
                            ProcessingMetricId.INCREMENTAL_SNAPSHOT_RESOLUTIONS, 1L);
                    if (record.impact().kind() == PatchImpact.Kind.PROCESSOR_MANAGED_STATE) {
                        ProcessingObservations.record(metrics,
                                ProcessingMetricId.PROCESSOR_MANAGED_MARKER_INCREMENTAL_RESOLUTIONS,
                                1L);
                    }
                    ProcessingObservations.record(metrics,
                            ProcessingMetricId.INCREMENTAL_BOUNDARY_PATH_DEPTH,
                            record.impact().path().depth());
                    ProcessingObservations.record(metrics,
                            ProcessingMetricId.INCREMENTAL_BOUNDARY_NODE_COUNT, 1L);
                }
            }
        }
        if (containsApplicationPatch(records)) {
            ProtectedStateGuard.verifyUnchanged(
                    initialCanonical,
                    initialResolved,
                    finalCanonical,
                    finalResolved,
                    wholeEmbeddedChildApplicationPatches(
                            records,
                            initialResolved));
        }
        boolean includeGeneratedUpdates = conformancePlannerOverride != null && conformancePlannerOverride.applies();

        BatchPatchResult.UpdatePlan updatePlan = new BatchPatchResult.UpdatePlan(records,
                preConformanceResolved,
                finalResolved,
                conformancePlan.changedPaths(),
                includeGeneratedUpdates);
        List<BatchPatchResult.GeneralizationMetadataWrite> metadataWrites =
                generalizationMetadataWrites(finalCanonical, finalResolved, conformancePlan.changedPaths());
        long buildUpdatesNanos = 0L;
        List<DocumentUpdateData> updates = null;
        if (buildUpdates) {
            long buildUpdatesStart = System.nanoTime();
            updates = updatePlan.build(materializationMetrics);
            buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
        }
        return new BatchPatchResult(finalCanonical,
                finalResolved,
                updates,
                updatePlan,
                preparedPatches,
                metadataWrites,
                finalResolutionComplete,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    private boolean containsApplicationPatch(List<BatchPatchRecord> records) {
        for (BatchPatchRecord record : records) {
            if (!record.processorManagedConformanceBypass()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Captures the target container shape before the patch mutates it so
     * Document Update rendering can distinguish object-member upsert
     * semantics from positional list semantics.
     */
    private boolean targetsObjectMember(
            ImmutablePatchPlanner planner,
            ParsedJsonPointer path) {
        if (path.isRoot()) {
            return false;
        }
        FrozenNode parent = planner.read(path.parent());
        if (parent == null || !parent.hasItems()) {
            return parent != null;
        }
        String member = path.segments().get(
                path.segments().size() - 1);
        return BlueLanguageConstants.OBJECT_VALUE.equals(member)
                || ProcessorContractConstants.KEY_CONTRACTS.equals(member);
    }

    private Set<String> wholeEmbeddedChildApplicationPatches(
            List<BatchPatchRecord> records,
            FrozenNode entryResolvedRoot) {
        /*
         * Boundary validation already limits an ancestor to an exact
         * immediate-child-root operation. Re-derive that narrow set from the
         * entry Process Embedded snapshot for protected-state comparison.
         */
        Set<String> result = new LinkedHashSet<>();
        for (BatchPatchRecord record : records) {
            if (record.processorManagedConformanceBypass()) {
                continue;
            }
            FrozenNode scope = entryResolvedRoot != null
                    ? entryResolvedRoot.at(record.originScope())
                    : null;
            FrozenNode contracts =
                    scope != null ? scope.getContracts() : null;
            FrozenNode embedded = contracts != null
                    ? contracts.property(
                    ProcessorContractConstants.KEY_EMBEDDED)
                    : null;
            FrozenNode paths = embedded != null
                    ? embedded.property(
                    ProcessorContractConstants.KEY_PATHS)
                    : null;
            List<FrozenNode> items =
                    paths != null ? paths.getItems() : null;
            if (items == null) {
                continue;
            }
            String target =
                    PointerUtils.normalizePointer(record.path());
            for (FrozenNode item : items) {
                Object value =
                        item != null ? item.getValue() : null;
                if (!(value instanceof String)) {
                    continue;
                }
                String child;
                try {
                    child = PointerUtils.resolvePointer(
                            record.originScope(),
                            PointerUtils.assertValidRuntimePointer(
                                    (String) value));
                } catch (IllegalArgumentException malformedPath) {
                    continue;
                }
                if (target.equals(child)) {
                    result.add(child);
                    break;
                }
            }
        }
        return result;
    }

    private List<BatchPatchResult.GeneralizationMetadataWrite> generalizationMetadataWrites(
            FrozenNode finalCanonical,
            FrozenNode finalResolved,
            List<String> changedPaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> uniquePaths = new LinkedHashSet<>(changedPaths);
        List<BatchPatchResult.GeneralizationMetadataWrite> writes = new ArrayList<>();
        for (String path : uniquePaths) {
            if (!isGeneralizationMetadataPath(path)) {
                continue;
            }
            FrozenNode value = readGeneralizationMetadata(finalCanonical, path);
            if (value == null) {
                FrozenNode resolvedValue = readGeneralizationMetadata(finalResolved, path);
                if (resolvedValue != null && resolvedValue.getReferenceBlueId() != null) {
                    value = FrozenNode.fromResolvedNode(new Node().blueId(resolvedValue.getReferenceBlueId()));
                }
            }
            if (value != null) {
                writes.add(new BatchPatchResult.GeneralizationMetadataWrite(path, value));
            }
        }
        return writes;
    }

    private FrozenNode readGeneralizationMetadata(FrozenNode root, String path) {
        List<String> segments = JsonPointer.split(path);
        String field = segments.get(segments.size() - 1);
        String parentPath = JsonPointer.toPointer(segments.subList(0, segments.size() - 1));
        FrozenNode parent = ImmutablePatchPlanner.forFrozen(root).read(parentPath);
        if (parent == null) {
            return null;
        }
        if (BlueLanguageConstants.OBJECT_TYPE.equals(field)) {
            return parent.getType();
        }
        if (BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(field)) {
            return parent.getItemType();
        }
        if (BlueLanguageConstants.OBJECT_KEY_TYPE.equals(field)) {
            return parent.getKeyType();
        }
        if (BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(field)) {
            return parent.getValueType();
        }
        return null;
    }

    private boolean isGeneralizationMetadataPath(String path) {
        List<String> segments = JsonPointer.split(path);
        if (segments.isEmpty()) {
            return false;
        }
        String field = segments.get(segments.size() - 1);
        return BlueLanguageConstants.OBJECT_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_KEY_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(field);
    }

    private ConformancePlan planBatchConformance(FrozenNode canonicalRoot,
                                                 FrozenNode resolvedRoot,
                                                 List<BatchPatchRecord> records) {
        boolean hasOverride = conformancePlannerOverride != null && conformancePlannerOverride.applies();
        if (conformanceEngine == null && !hasOverride) {
            return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
        }
        List<String> changedPaths = new ArrayList<>();
        List<ConformanceChangedPath> changedPathRecords = new ArrayList<>();
        for (BatchPatchRecord record : records) {
            if (record.processorManagedConformanceBypass()) {
                continue;
            }
            /*
             * /contracts mutations are governed by changed-closure Contract
             * Recognition Resolution.  Running ordinary data-type
             * generalization first can misclassify an unsupported runtime
             * contract as a type-generalization failure.
             */
            if (isContractRecognitionChange(record)) {
                continue;
            }
            if (record.impact().localResolutionProvenSafe()) {
                continue;
            }
            if (hasTypedNodeBetweenOriginAndPath(resolvedRoot, record.originScope(), record.path())) {
                changedPaths.add(record.path());
                changedPathRecords.add(new ConformanceChangedPath(record.path(), record.originScope()));
            }
        }
        if (changedPaths.isEmpty()) {
            return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
        }
        ProcessingObservations.record(metrics,
                ProcessingMetricId.CONFORMANCE_PLANS, 1L);
        if (hasOverride) {
            ConformancePlan plan = conformancePlannerOverride.plan(canonicalRoot, resolvedRoot, changedPathRecords);
            String originScope = originScopeForGeneratedUpdate(records);
            TypeGeneralizationPolicyResolver.enforceScopeBoundary(originScope,
                    plan.changedPaths());
            TypeGeneralizationPolicyResolver.enforce(conformanceEngine, plan.root(), plan.changedPaths(), originScope);
            return plan;
        }
        try {
            Set<String> preservedBodies =
                    DocumentProcessingRuntime
                            .executableBodyPaths(
                                    /*
                                     * Reference-only contracts maps and contract
                                     * entries have no direct type header in the
                                     * canonical lane. The effective lane has
                                     * already resolved those headers while the
                                     * executable subtree remains deferred, so it
                                     * is the authoritative source for locating
                                     * paths that conformance must not demand.
                                     */
                                    resolvedRoot,
                                    openedScopePaths,
                                    executableBodyFieldsByType);
            ConformancePlan plan =
                    conformanceEngine
                            .planGeneralizationPreservingPaths(
                                    canonicalRoot,
                                    resolvedRoot,
                                    changedPaths,
                                    preservedBodies);
            String originScope = originScopeForGeneratedUpdate(records);
            TypeGeneralizationPolicyResolver.enforceScopeBoundary(originScope,
                    plan.changedPaths());
            TypeGeneralizationPolicyResolver.enforce(conformanceEngine, plan.root(), plan.changedPaths(), originScope);
            return plan;
        } catch (ProcessorFailureException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ProcessorFailureException(ProcessorErrorCategory.TypeGeneralizationFailure,
                    "GeneralizationNoValidType: " + ex.getMessage(),
                    ex);
        }
    }

    private boolean isContractRecognitionChange(BatchPatchRecord record) {
        String relative = PointerUtils.relativizePointer(
                record.originScope(), record.path());
        return PointerUtils.descendantOrEqual(
                relative,
                ProcessorPointerConstants.RELATIVE_CONTRACTS);
    }

    private boolean hasTypedNodeBetweenOriginAndPath(FrozenNode resolvedRoot, String originScope, String changedPath) {
        ImmutablePatchPlanner planner = ImmutablePatchPlanner.forFrozen(resolvedRoot);
        String normalizedOrigin = PointerUtils.normalizeScope(originScope);
        String current = PointerUtils.normalizePointer(changedPath);
        while (true) {
            FrozenNode node = planner.read(current);
            if (hasTypeMetadata(node)) {
                return true;
            }
            if (current.equals(normalizedOrigin)
                    || JsonPointer.ROOT.equals(current)) {
                return false;
            }
            current = parentPointer(current);
        }
    }

    private boolean hasTypeMetadata(FrozenNode node) {
        return node != null
                && (node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null);
    }

    private String parentPointer(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        if (segments.isEmpty()) {
            return JsonPointer.ROOT;
        }
        return JsonPointer.toPointer(segments.subList(0, segments.size() - 1));
    }

    private String originScopeForGeneratedUpdate(List<BatchPatchRecord> records) {
        return records.isEmpty()
                ? JsonPointer.ROOT
                : records.get(0).originScope();
    }

    private boolean isProcessorManagedConformanceBypass(ImmutablePatchPlanner.PatchPlan result) {
        String relativePath = PointerUtils.relativizePointer(result.originScope(), result.path());
        String initialized = ProcessorPointerConstants.RELATIVE_INITIALIZED;
        return PointerUtils.descendantOrEqual(relativePath, initialized);
    }

    private ImmutableJsonPatch resolveProcessorManagedValue(
            ImmutableJsonPatch patch,
            ImmutablePatchPlanner.PatchPlan canonicalPlan) {
        if (!exactReplacement
                || authoritativeSnapshotManager == null
                || patch.op() == JsonPatch.Op.REMOVE
                || !isProcessorManagedConformanceBypass(canonicalPlan)) {
            return patch;
        }
        ResolvedSnapshot resolvedValue = authoritativeSnapshotManager.fromDocumentTransient(
                patch.canonicalValue().toNode());
        return patch.withResolvedValue(resolvedValue.frozenResolvedRoot());
    }
}
