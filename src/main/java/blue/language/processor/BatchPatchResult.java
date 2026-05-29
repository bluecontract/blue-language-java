package blue.language.processor;

import blue.language.snapshot.FrozenNode;
import blue.language.processor.model.JsonPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class BatchPatchResult {

    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final List<DocumentProcessingRuntime.DocumentUpdateData> updates;
    private final UpdatePlan updatePlan;
    private final long patchPlanningNanos;
    private final long conformanceNanos;
    private final long buildUpdatesNanos;

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentProcessingRuntime.DocumentUpdateData> updates) {
        this(canonicalRoot, resolvedRoot, updates, 0L, 0L, 0L);
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentProcessingRuntime.DocumentUpdateData> updates,
                     long patchPlanningNanos,
                     long conformanceNanos,
                     long buildUpdatesNanos) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        this.updates = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(updates, "updates")));
        this.updatePlan = null;
        this.patchPlanningNanos = patchPlanningNanos;
        this.conformanceNanos = conformanceNanos;
        this.buildUpdatesNanos = buildUpdatesNanos;
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     UpdatePlan updatePlan,
                     long patchPlanningNanos,
                     long conformanceNanos,
                     long buildUpdatesNanos) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        this.updates = null;
        this.updatePlan = Objects.requireNonNull(updatePlan, "updatePlan");
        this.patchPlanningNanos = patchPlanningNanos;
        this.conformanceNanos = conformanceNanos;
        this.buildUpdatesNanos = buildUpdatesNanos;
    }

    FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    List<DocumentProcessingRuntime.DocumentUpdateData> updates() {
        return updates != null ? updates : updatePlan.build(null);
    }

    long patchPlanningNanos() {
        return patchPlanningNanos;
    }

    long conformanceNanos() {
        return conformanceNanos;
    }

    long buildUpdatesNanos() {
        return buildUpdatesNanos;
    }

    BatchPatchResult withMaterializationMetrics(DocumentProcessingRuntime.UpdateMaterializationMetrics metrics) {
        if (updatePlan != null) {
            return new BatchPatchResult(canonicalRoot,
                    resolvedRoot,
                    updatePlan.build(metrics),
                    patchPlanningNanos,
                    conformanceNanos,
                    buildUpdatesNanos);
        }
        List<DocumentProcessingRuntime.DocumentUpdateData> rebound = new ArrayList<>(updates.size());
        for (DocumentProcessingRuntime.DocumentUpdateData update : updates) {
            rebound.add(update.withMaterializationMetrics(metrics));
        }
        return new BatchPatchResult(canonicalRoot,
                resolvedRoot,
                rebound,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    static final class UpdatePlan {
        private final List<BatchPatchRecord> records;
        private final FrozenNode preConformanceResolvedRoot;
        private final FrozenNode finalResolvedRoot;
        private final List<String> generatedPaths;
        private final boolean includeGeneratedUpdates;

        UpdatePlan(List<BatchPatchRecord> records,
                   FrozenNode preConformanceResolvedRoot,
                   FrozenNode finalResolvedRoot,
                   List<String> generatedPaths,
                   boolean includeGeneratedUpdates) {
            this.records = Collections.unmodifiableList(new ArrayList<>(
                    Objects.requireNonNull(records, "records")));
            this.preConformanceResolvedRoot = Objects.requireNonNull(preConformanceResolvedRoot,
                    "preConformanceResolvedRoot");
            this.finalResolvedRoot = Objects.requireNonNull(finalResolvedRoot, "finalResolvedRoot");
            this.generatedPaths = generatedPaths == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(generatedPaths));
            this.includeGeneratedUpdates = includeGeneratedUpdates;
        }

        List<DocumentProcessingRuntime.DocumentUpdateData> build(
                DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics) {
            List<DocumentProcessingRuntime.DocumentUpdateData> built = new ArrayList<>();
            ImmutablePatchPlanner finalResolvedPlanner = ImmutablePatchPlanner.forFrozen(finalResolvedRoot);
            for (BatchPatchRecord record : records) {
                FrozenNode after = null;
                if (record.op() != JsonPatch.Op.REMOVE) {
                    after = hasLaterOverlappingPatch(record)
                            ? record.afterAtPatchTime()
                            : finalResolvedPlanner.read(record.path());
                }
                built.add(new DocumentProcessingRuntime.DocumentUpdateData(record.path(),
                        record.beforeAtPatchTime(),
                        after,
                        record.op(),
                        record.originScope(),
                        record.cascadeScopes(),
                        materializationMetrics));
            }
            if (includeGeneratedUpdates && !generatedPaths.isEmpty()) {
                ImmutablePatchPlanner preConformancePlanner =
                        ImmutablePatchPlanner.forFrozen(preConformanceResolvedRoot);
                for (String path : generatedPaths) {
                    FrozenNode before = preConformancePlanner.read(path);
                    FrozenNode after = finalResolvedPlanner.read(path);
                    built.add(new DocumentProcessingRuntime.DocumentUpdateData(path,
                            before,
                            after,
                            before == null ? JsonPatch.Op.ADD : JsonPatch.Op.REPLACE,
                            originScopeForGeneratedUpdate(),
                            Collections.singletonList("/"),
                            materializationMetrics));
                }
            }
            return Collections.unmodifiableList(built);
        }

        private String originScopeForGeneratedUpdate() {
            return records.isEmpty() ? "/" : records.get(0).originScope();
        }

        private boolean hasLaterOverlappingPatch(BatchPatchRecord current) {
            int currentIndex = records.indexOf(current);
            for (int i = currentIndex + 1; i < records.size(); i++) {
                if (pathsOverlap(current.path(), records.get(i).path())) {
                    return true;
                }
            }
            return false;
        }

        private boolean pathsOverlap(String first, String second) {
            return blue.language.processor.util.PointerUtils.descendantOrEqual(first, second)
                    || blue.language.processor.util.PointerUtils.descendantOrEqual(second, first);
        }
    }
}
