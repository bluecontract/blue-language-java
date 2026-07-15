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

final class BatchPatchTransaction {

    private final String originScopePath;
    private final List<JsonPatch> patches;
    private final DocumentProcessingRuntime.PlanningContext planning;
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics;
    private final boolean buildUpdates;

    BatchPatchTransaction(String originScopePath,
                          List<JsonPatch> patches,
                          DocumentProcessingRuntime.PlanningContext planning,
                          ConformanceEngine conformanceEngine,
                          ConformancePlannerOverride conformancePlannerOverride,
                          DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics) {
        this(originScopePath, patches, planning, conformanceEngine, conformancePlannerOverride,
                materializationMetrics, true);
    }

    BatchPatchTransaction(String originScopePath,
                          List<JsonPatch> patches,
                          DocumentProcessingRuntime.PlanningContext planning,
                          ConformanceEngine conformanceEngine,
                          ConformancePlannerOverride conformancePlannerOverride,
                          DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics,
                          boolean buildUpdates) {
        this.originScopePath = originScopePath;
        this.patches = Collections.unmodifiableList(new ArrayList<>(patches));
        this.planning = planning;
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.materializationMetrics = materializationMetrics;
        this.buildUpdates = buildUpdates;
    }

    BatchPatchResult apply() {
        long planningStart = System.nanoTime();
        FrozenNode workingCanonical = planning.baseSnapshot() != null
                ? planning.baseSnapshot().frozenCanonicalRoot()
                : planning.canonicalPlanner().root();
        FrozenNode workingResolved = planning.baseSnapshot() != null
                ? planning.baseSnapshot().frozenResolvedRoot()
                : planning.resolvedPlanner().root();
        List<BatchPatchRecord> records = new ArrayList<>();
        for (JsonPatch patch : patches) {
            ImmutablePatchPlanner canonicalPlanner = ImmutablePatchPlanner.forFrozen(workingCanonical);
            ImmutablePatchPlanner.PatchPlan canonicalPlan = planning.exactReplacement()
                    ? canonicalPlanner.planWithExactReplacement(originScopePath, patch)
                    : canonicalPlanner.plan(originScopePath, patch);
            ImmutablePatchPlanner resolvedPlanner = ImmutablePatchPlanner.forFrozen(workingResolved);
            ImmutablePatchPlanner.PatchPlan resolvedPlan = planning.exactReplacement()
                    ? resolvedPlanner.planWithExactReplacement(originScopePath, patch)
                    : resolvedPlanner.plan(originScopePath, patch);
            BatchPatchRecord record = new BatchPatchRecord(patch,
                    canonicalPlan,
                    resolvedPlan,
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
        if (planning.exactReplacement()) {
            ResolvedSnapshot authoritative = planning.resolveCanonical(finalCanonical);
            finalCanonical = authoritative.frozenCanonicalRoot();
            finalResolved = authoritative.frozenResolvedRoot();
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
                patches,
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
            if (hasTypedNodeBetweenOriginAndPath(resolvedRoot, record.originScope(), record.path())) {
                changedPaths.add(record.path());
                changedPathRecords.add(new ConformanceChangedPath(record.path(), record.originScope()));
            }
        }
        if (changedPaths.isEmpty()) {
            return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
        }
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

    private List<DocumentProcessingRuntime.DocumentUpdateData> buildUpdates(List<BatchPatchRecord> records,
                                                                           FrozenNode preConformanceResolvedRoot,
                                                                           FrozenNode finalResolvedRoot,
                                                                           List<String> generatedPaths,
                                                                           boolean includeGeneratedUpdates) {
        List<DocumentProcessingRuntime.DocumentUpdateData> updates = new ArrayList<>();
        ImmutablePatchPlanner finalResolvedPlanner = ImmutablePatchPlanner.forFrozen(finalResolvedRoot);
        for (BatchPatchRecord record : records) {
            FrozenNode after = null;
            if (record.op() != JsonPatch.Op.REMOVE) {
                after = hasLaterOverlappingPatch(records, record)
                        ? record.afterAtPatchTime()
                        : finalResolvedPlanner.read(record.path());
            }
            updates.add(new DocumentProcessingRuntime.DocumentUpdateData(record.path(),
                    record.beforeAtPatchTime(),
                    after,
                    record.op(),
                    record.originScope(),
                    record.cascadeScopes(),
                    materializationMetrics));
        }
        if (includeGeneratedUpdates && generatedPaths != null && !generatedPaths.isEmpty()) {
            ImmutablePatchPlanner preConformancePlanner = ImmutablePatchPlanner.forFrozen(preConformanceResolvedRoot);
            for (String path : generatedPaths) {
                FrozenNode before = preConformancePlanner.read(path);
                FrozenNode after = finalResolvedPlanner.read(path);
                updates.add(new DocumentProcessingRuntime.DocumentUpdateData(path,
                        before,
                        after,
                        before == null ? JsonPatch.Op.ADD : JsonPatch.Op.REPLACE,
                        originScopeForGeneratedUpdate(records),
                        Collections.singletonList("/"),
                        materializationMetrics));
            }
        }
        return updates;
    }

    private String originScopeForGeneratedUpdate(List<BatchPatchRecord> records) {
        return records.isEmpty() ? "/" : records.get(0).originScope();
    }

    private boolean hasLaterOverlappingPatch(List<BatchPatchRecord> records, BatchPatchRecord current) {
        int currentIndex = records.indexOf(current);
        for (int i = currentIndex + 1; i < records.size(); i++) {
            if (pathsOverlap(current.path(), records.get(i).path())) {
                return true;
            }
        }
        return false;
    }

    private boolean pathsOverlap(String first, String second) {
        return PointerUtils.descendantOrEqual(first, second)
                || PointerUtils.descendantOrEqual(second, first);
    }

    private boolean isProcessorManagedConformanceBypass(ImmutablePatchPlanner.PatchPlan result) {
        String relativePath = PointerUtils.relativizePointer(result.originScope(), result.path());
        String initialized = ProcessorPointerConstants.RELATIVE_INITIALIZED;
        return PointerUtils.descendantOrEqual(relativePath, initialized);
    }
}
