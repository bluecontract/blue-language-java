package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.ConformancePlan;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class BatchPatchTransaction {

    private final String originScopePath;
    private final List<JsonPatch> patches;
    private final DocumentProcessingRuntime.PlanningContext planning;
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics;

    BatchPatchTransaction(String originScopePath,
                          List<JsonPatch> patches,
                          DocumentProcessingRuntime.PlanningContext planning,
                          ConformanceEngine conformanceEngine,
                          ConformancePlannerOverride conformancePlannerOverride,
                          DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics) {
        this.originScopePath = originScopePath;
        this.patches = Collections.unmodifiableList(new ArrayList<>(patches));
        this.planning = planning;
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.materializationMetrics = materializationMetrics;
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
            ImmutablePatchPlanner.PatchPlan canonicalPlan =
                    ImmutablePatchPlanner.forFrozen(workingCanonical).plan(originScopePath, patch);
            ImmutablePatchPlanner.PatchPlan resolvedPlan =
                    ImmutablePatchPlanner.forFrozen(workingResolved).plan(originScopePath, patch);
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
        boolean includeGeneratedUpdates = conformancePlannerOverride != null && conformancePlannerOverride.applies();

        long buildUpdatesStart = System.nanoTime();
        List<DocumentProcessingRuntime.DocumentUpdateData> updates = buildUpdates(records,
                preConformanceResolved,
                finalResolved,
                conformancePlan.changedPaths(),
                includeGeneratedUpdates);
        long buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
        return new BatchPatchResult(finalCanonical,
                finalResolved,
                updates,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
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
