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
    private final List<JsonPatch> requestedPatches;
    private final List<GeneralizationMetadataWrite> generalizationMetadataWrites;
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
        this(canonicalRoot,
                resolvedRoot,
                updates,
                null,
                Collections.<JsonPatch>emptyList(),
                Collections.<GeneralizationMetadataWrite>emptyList(),
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentProcessingRuntime.DocumentUpdateData> updates,
                     UpdatePlan updatePlan,
                     List<JsonPatch> requestedPatches,
                     List<GeneralizationMetadataWrite> generalizationMetadataWrites,
                     long patchPlanningNanos,
                     long conformanceNanos,
                     long buildUpdatesNanos) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        this.updates = updates == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(updates));
        this.updatePlan = updatePlan;
        this.requestedPatches = copyPatches(requestedPatches);
        this.generalizationMetadataWrites = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(generalizationMetadataWrites, "generalizationMetadataWrites")));
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
        this.requestedPatches = Collections.emptyList();
        this.generalizationMetadataWrites = Collections.emptyList();
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

    List<DocumentProcessingRuntime.DocumentUpdateData> updatesAgainst(
            FrozenNode authoritativeResolvedRoot,
            DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics) {
        if (updatePlan != null) {
            return updatePlan.build(materializationMetrics,
                    Objects.requireNonNull(authoritativeResolvedRoot, "authoritativeResolvedRoot"));
        }
        List<DocumentProcessingRuntime.DocumentUpdateData> rebound = new ArrayList<>(updates.size());
        for (DocumentProcessingRuntime.DocumentUpdateData update : updates) {
            rebound.add(update.withMaterializationMetrics(materializationMetrics));
        }
        return Collections.unmodifiableList(rebound);
    }

    List<JsonPatch> requestedPatches() {
        return requestedPatches;
    }

    List<GeneralizationMetadataWrite> generalizationMetadataWrites() {
        return generalizationMetadataWrites;
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
                    updatePlan,
                    requestedPatches,
                    generalizationMetadataWrites,
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
                null,
                requestedPatches,
                generalizationMetadataWrites,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    private static List<JsonPatch> copyPatches(List<JsonPatch> patches) {
        Objects.requireNonNull(patches, "requestedPatches");
        List<JsonPatch> copy = new ArrayList<>(patches.size());
        for (JsonPatch patch : patches) {
            Objects.requireNonNull(patch, "patch");
            switch (patch.getOp()) {
                case ADD:
                    copy.add(JsonPatch.add(patch.getPath(), patch.getVal().clone()));
                    break;
                case REPLACE:
                    copy.add(JsonPatch.replace(patch.getPath(), patch.getVal().clone()));
                    break;
                case REMOVE:
                    copy.add(JsonPatch.remove(patch.getPath()));
                    break;
                default:
                    throw new IllegalStateException("Unsupported patch op: " + patch.getOp());
            }
        }
        return Collections.unmodifiableList(copy);
    }

    static final class GeneralizationMetadataWrite {
        private final String path;
        private final FrozenNode value;

        GeneralizationMetadataWrite(String path, FrozenNode value) {
            this.path = Objects.requireNonNull(path, "path");
            this.value = Objects.requireNonNull(value, "value");
        }

        String path() {
            return path;
        }

        FrozenNode value() {
            return value;
        }
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
            return build(materializationMetrics, finalResolvedRoot);
        }

        List<DocumentProcessingRuntime.DocumentUpdateData> build(
                DocumentProcessingRuntime.UpdateMaterializationMetrics materializationMetrics,
                FrozenNode authoritativeResolvedRoot) {
            List<DocumentProcessingRuntime.DocumentUpdateData> built = new ArrayList<>();
            ImmutablePatchPlanner finalResolvedPlanner = ImmutablePatchPlanner.forFrozen(
                    Objects.requireNonNull(authoritativeResolvedRoot, "authoritativeResolvedRoot"));
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
