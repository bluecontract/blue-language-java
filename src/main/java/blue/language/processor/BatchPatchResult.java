package blue.language.processor;

import blue.language.utils.Properties;

import blue.language.snapshot.FrozenNode;
import blue.language.processor.model.JsonPatch;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable hand-off from patch planning to runtime commit.
 *
 * <p>The canonical and resolved roots form one atomic candidate state.
 * Optional update materialization and generalization metadata belong to that
 * same candidate and must never be applied independently.</p>
 */
final class BatchPatchResult {

    private final FrozenNode canonicalRoot;
    private final FrozenNode resolvedRoot;
    private final List<DocumentProcessingRuntime.DocumentUpdateData> updates;
    private final UpdatePlan updatePlan;
    private final List<ImmutableJsonPatch> requestedPatches;
    private final List<GeneralizationMetadataWrite> generalizationMetadataWrites;
    private final boolean resolutionComplete;
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
                Collections.<ImmutableJsonPatch>emptyList(),
                Collections.<GeneralizationMetadataWrite>emptyList(),
                true,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentProcessingRuntime.DocumentUpdateData> updates,
                     UpdatePlan updatePlan,
                     List<ImmutableJsonPatch> requestedPatches,
                     List<GeneralizationMetadataWrite> generalizationMetadataWrites,
                     long patchPlanningNanos,
                     long conformanceNanos,
                     long buildUpdatesNanos) {
        this(canonicalRoot,
                resolvedRoot,
                updates,
                updatePlan,
                requestedPatches,
                generalizationMetadataWrites,
                true,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentProcessingRuntime.DocumentUpdateData> updates,
                     UpdatePlan updatePlan,
                     List<ImmutableJsonPatch> requestedPatches,
                     List<GeneralizationMetadataWrite> generalizationMetadataWrites,
                     boolean resolutionComplete,
                     long patchPlanningNanos,
                     long conformanceNanos,
                     long buildUpdatesNanos) {
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        this.updates = updates == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(updates));
        this.updatePlan = updatePlan;
        this.requestedPatches = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(requestedPatches, "requestedPatches")));
        this.generalizationMetadataWrites = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(generalizationMetadataWrites, "generalizationMetadataWrites")));
        this.resolutionComplete = resolutionComplete;
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
        this.resolutionComplete = true;
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

    List<ImmutableJsonPatch> requestedPatches() {
        return requestedPatches;
    }

    List<GeneralizationMetadataWrite> generalizationMetadataWrites() {
        return generalizationMetadataWrites;
    }

    boolean isResolutionComplete() {
        return resolutionComplete;
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
                    resolutionComplete,
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
                resolutionComplete,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    static final class GeneralizationMetadataWrite {
        private final String path;
        private final FrozenNode value;

        GeneralizationMetadataWrite(String path, FrozenNode value) {
            this.path = Objects.requireNonNull(path, "path");
            this.value = Objects.requireNonNull(value, Properties.OBJECT_VALUE);
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
        private final boolean[] laterOverlaps;

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
            this.laterOverlaps = computeLaterOverlaps(this.records);
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
            for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
                BatchPatchRecord record = records.get(recordIndex);
                FrozenNode before = record.beforeAtPatchTime();
                FrozenNode after = null;
                if (record.op() != JsonPatch.Op.REMOVE) {
                    after = laterOverlaps[recordIndex]
                            ? record.afterAtPatchTime()
                            : finalResolvedPlanner.read(record.path());
                }
                built.add(new DocumentProcessingRuntime.DocumentUpdateData(record.path(),
                        before,
                        after,
                        semanticOperation(record, before),
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
                            Collections.singletonList(JsonPointer.ROOT),
                            materializationMetrics));
                }
            }
            return Collections.unmodifiableList(built);
        }

        /**
         * Renders object-member writes from their patch-time existence while
         * preserving authored positional list and root operations.
         */
        private JsonPatch.Op semanticOperation(BatchPatchRecord record,
                                               FrozenNode before) {
            JsonPatch.Op authored = record.op();
            if (authored == JsonPatch.Op.REMOVE
                    || !record.objectMemberTarget()) {
                return authored;
            }
            return before == null
                    ? JsonPatch.Op.ADD
                    : JsonPatch.Op.REPLACE;
        }

        private String originScopeForGeneratedUpdate() {
            return records.isEmpty()
                    ? JsonPointer.ROOT
                    : records.get(0).originScope();
        }

        private static boolean[] computeLaterOverlaps(List<BatchPatchRecord> records) {
            boolean[] overlaps = new boolean[records.size()];
            PathTrie later = new PathTrie();
            for (int index = records.size() - 1; index >= 0; index--) {
                List<String> segments = records.get(index).parsedPath().segments();
                overlaps[index] = later.overlaps(segments);
                later.add(segments);
            }
            return overlaps;
        }

        private static final class PathTrie {
            private final Map<String, PathTrie> children = new HashMap<>();
            private int terminalCount;
            private int subtreeCount;

            private void add(List<String> segments) {
                PathTrie current = this;
                current.subtreeCount++;
                for (String segment : segments) {
                    PathTrie child = current.children.get(segment);
                    if (child == null) {
                        child = new PathTrie();
                        current.children.put(segment, child);
                    }
                    current = child;
                    current.subtreeCount++;
                }
                current.terminalCount++;
            }

            private boolean overlaps(List<String> segments) {
                PathTrie current = this;
                if (current.terminalCount > 0) {
                    return true;
                }
                for (String segment : segments) {
                    current = current.children.get(segment);
                    if (current == null) {
                        return false;
                    }
                    if (current.terminalCount > 0) {
                        return true;
                    }
                }
                return current.subtreeCount > 0;
            }
        }
    }
}
