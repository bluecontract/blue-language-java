package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics;
    private final ImmutableJsonPatch.PreparationContext patchPreparation;
    private final ProcessingMetricsSink metrics;
    private final PatchImpactAnalyzer impactAnalyzer;

    PatchPlanningEngine(String originScopePath,
                        DocumentProcessingRuntime.PlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics) {
        this(originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                ProcessingMetricsSink.NOOP,
                true);
    }

    PatchPlanningEngine(String originScopePath,
                        DocumentProcessingRuntime.PlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics,
                        ProcessingMetricsSink metrics) {
        this(originScopePath,
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                materializationMetrics,
                metrics,
                true);
    }

    PatchPlanningEngine(String originScopePath,
                        DocumentProcessingRuntime.PlanningContext planning,
                        ConformanceEngine conformanceEngine,
                        ConformancePlannerOverride conformancePlannerOverride,
                        DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics,
                        ProcessingMetricsSink metrics,
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
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
        this.patchPreparation = ImmutableJsonPatch.preparationContext(this.metrics);
        this.impactAnalyzer = new PatchImpactAnalyzer(conformanceEngine,
                conformancePlannerOverride,
                authoritativeSnapshotManager,
                this.metrics);
    }

    BatchPatchResult planAtomic(List<JsonPatch> patches, boolean buildUpdates) {
        if (initialCanonicalRoot == null || initialResolvedRoot == null) {
            throw new IllegalStateException("Atomic planning roots were not retained");
        }
        List<ImmutableJsonPatch> prepared = preparePatches(patches,
                initialCanonicalRoot,
                initialResolvedRoot);
        return plan(prepared, initialCanonicalRoot, initialResolvedRoot, buildUpdates);
    }

    BatchPatchResult planAtomicInputs(List<PatchInput> patches, boolean buildUpdates) {
        if (initialCanonicalRoot == null || initialResolvedRoot == null) {
            throw new IllegalStateException("Atomic planning roots were not retained");
        }
        List<ImmutableJsonPatch> prepared = preparePatchInputs(patches,
                initialCanonicalRoot,
                initialResolvedRoot);
        return plan(prepared, initialCanonicalRoot, initialResolvedRoot, buildUpdates);
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
        return plan(Collections.singletonList(Objects.requireNonNull(patch, "patch")),
                Objects.requireNonNull(canonicalRoot, "canonicalRoot"),
                Objects.requireNonNull(resolvedRoot, "resolvedRoot"),
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
        boolean fullSnapshotResolution = exactReplacement
                && (authoritativeFallbackReason != null || !conformancePlan.fullSnapshotRebuildAvoidable());
        if (fullSnapshotResolution) {
            if (authoritativeSnapshotManager == null) {
                throw new IllegalStateException("Authoritative snapshot resolution is unavailable");
            }
            PatchImpact.FallbackReason reason = authoritativeFallbackReason != null
                    ? authoritativeFallbackReason
                    : PatchImpact.FallbackReason.DEPENDENCY_INDEX_MISSING_OR_STALE;
            metrics.incrementFullSnapshotFallback(reason.name());
            metrics.incrementFullCanonicalRootMaterializations();
            metrics.incrementFullFrozenRootToNodeMaterializations();
            ResolvedSnapshot authoritative =
                    authoritativeSnapshotManager.fromDocumentTransient(finalCanonical.toNode());
            metrics.incrementFullResolvedRootMaterializations();
            finalCanonical = authoritative.frozenCanonicalRoot();
            finalResolved = authoritative.frozenResolvedRoot();
        } else if (exactReplacement) {
            for (BatchPatchRecord record : records) {
                if (record.impact().localResolutionProvenSafe()) {
                    metrics.incrementIncrementalSnapshotResolutions();
                    if (record.impact().kind() == PatchImpact.Kind.PROCESSOR_MANAGED_STATE) {
                        metrics.incrementProcessorManagedMarkerIncrementalResolutions();
                    }
                    metrics.addIncrementalBoundaryPathDepth(record.impact().path().depth());
                    metrics.addIncrementalBoundaryNodeCount(1L);
                }
            }
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
        List<DocumentProcessingRuntime.DocumentUpdateData> updates = null;
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
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
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
        if ("type".equals(field)) {
            return parent.getType();
        }
        if ("itemType".equals(field)) {
            return parent.getItemType();
        }
        if ("keyType".equals(field)) {
            return parent.getKeyType();
        }
        if ("valueType".equals(field)) {
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
        return "type".equals(field)
                || "itemType".equals(field)
                || "keyType".equals(field)
                || "valueType".equals(field);
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
        metrics.incrementConformancePlans();
        if (hasOverride) {
            ConformancePlan plan = conformancePlannerOverride.plan(canonicalRoot, resolvedRoot, changedPathRecords);
            String originScope = originScopeForGeneratedUpdate(records);
            TypeGeneralizationPolicyResolver.enforceScopeBoundary(originScope,
                    plan.changedPaths());
            TypeGeneralizationPolicyResolver.enforce(conformanceEngine, plan.root(), plan.changedPaths(), originScope);
            return plan;
        }
        try {
            ConformancePlan plan = conformanceEngine.planGeneralization(canonicalRoot, resolvedRoot, changedPaths);
            String originScope = originScopeForGeneratedUpdate(records);
            TypeGeneralizationPolicyResolver.enforceScopeBoundary(originScope,
                    plan.changedPaths());
            TypeGeneralizationPolicyResolver.enforce(conformanceEngine, plan.root(), plan.changedPaths(), originScope);
            return plan;
        } catch (ProcessorFailureException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ProcessorFailureException(ProcessorErrorCategory.GeneralizationNoValidType,
                    "GeneralizationNoValidType: " + ex.getMessage(),
                    ex);
        }
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
            if (current.equals(normalizedOrigin) || "/".equals(current)) {
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
            return "/";
        }
        return JsonPointer.toPointer(segments.subList(0, segments.size() - 1));
    }

    private String originScopeForGeneratedUpdate(List<BatchPatchRecord> records) {
        return records.isEmpty() ? "/" : records.get(0).originScope();
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
