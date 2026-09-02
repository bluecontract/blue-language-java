package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.wire.BlueLanguageConstants;

import blue.language.snapshot.FrozenNode;
import blue.language.processor.model.JsonPatch;
import blue.language.model.wire.JsonPointer;

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
    private final List<DocumentUpdateData> updates;
    private final UpdatePlan updatePlan;
    private final List<ImmutableJsonPatch> requestedPatches;
    private final List<GeneralizationMetadataWrite> generalizationMetadataWrites;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
    private final boolean resolutionComplete;
    private final boolean sourceBacked;
    private final long patchPlanningNanos;
    private final long conformanceNanos;
    private final long buildUpdatesNanos;

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentUpdateData> updates) {
        this(canonicalRoot, resolvedRoot, updates, 0L, 0L, 0L);
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentUpdateData> updates,
                     long patchPlanningNanos,
                     long conformanceNanos,
                     long buildUpdatesNanos) {
        this(canonicalRoot,
                resolvedRoot,
                updates,
                null,
                Collections.<ImmutableJsonPatch>emptyList(),
                Collections.<GeneralizationMetadataWrite>emptyList(),
                CanonicalTypeIdentityLookup.incomplete(),
                true,
                false,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentUpdateData> updates,
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
                CanonicalTypeIdentityLookup.incomplete(),
                true,
                false,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    BatchPatchResult(FrozenNode canonicalRoot,
                     FrozenNode resolvedRoot,
                     List<DocumentUpdateData> updates,
                     UpdatePlan updatePlan,
                     List<ImmutableJsonPatch> requestedPatches,
                     List<GeneralizationMetadataWrite> generalizationMetadataWrites,
                     CanonicalTypeIdentityLookup canonicalTypeIdentities,
                     boolean resolutionComplete,
                     boolean sourceBacked,
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
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        this.resolutionComplete = resolutionComplete;
        this.sourceBacked = sourceBacked;
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
        this.canonicalTypeIdentities =
                CanonicalTypeIdentityLookup.incomplete();
        this.resolutionComplete = true;
        this.sourceBacked = false;
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

    /**
     * Returns the exact authored candidate after requested patches and before
     * conformance-owned metadata is applied. Selected-document publication
     * must start here: replaying the patches against an earlier collapsed
     * representation cannot patch through a pure-reference ancestor.
     */
    FrozenNode selectedCanonicalRoot() {
        return updatePlan != null
                ? updatePlan.preConformanceCanonicalRoot
                : canonicalRoot;
    }

    List<DocumentUpdateData> updates() {
        return updates != null ? updates : updatePlan.build(null);
    }

    List<DocumentUpdateData> updatesAgainst(
            FrozenNode authoritativeResolvedRoot,
            UpdateMaterializationMetrics materializationMetrics) {
        if (updatePlan != null) {
            return updatePlan.build(materializationMetrics,
                    Objects.requireNonNull(authoritativeResolvedRoot, "authoritativeResolvedRoot"));
        }
        List<DocumentUpdateData> rebound = new ArrayList<>(updates.size());
        for (DocumentUpdateData update : updates) {
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

    CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return canonicalTypeIdentities;
    }

    boolean isResolutionComplete() {
        return resolutionComplete;
    }

    boolean isSourceBacked() {
        return sourceBacked;
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

    BatchPatchResult withMaterializationMetrics(
            UpdateMaterializationMetrics metrics) {
        if (updatePlan != null) {
            return new BatchPatchResult(canonicalRoot,
                    resolvedRoot,
                    updatePlan.build(metrics),
                    updatePlan,
                    requestedPatches,
                    generalizationMetadataWrites,
                    canonicalTypeIdentities,
                    resolutionComplete,
                    sourceBacked,
                    patchPlanningNanos,
                    conformanceNanos,
                    buildUpdatesNanos);
        }
        List<DocumentUpdateData> rebound = new ArrayList<>(updates.size());
        for (DocumentUpdateData update : updates) {
            rebound.add(update.withMaterializationMetrics(metrics));
        }
        return new BatchPatchResult(canonicalRoot,
                resolvedRoot,
                rebound,
                null,
                requestedPatches,
                generalizationMetadataWrites,
                canonicalTypeIdentities,
                resolutionComplete,
                sourceBacked,
                patchPlanningNanos,
                conformanceNanos,
                buildUpdatesNanos);
    }

    static final class GeneralizationMetadataWrite {
        private final String path;
        private final FrozenNode value;
        private final int requiringPatchIndex;

        GeneralizationMetadataWrite(
                String path,
                FrozenNode value,
                int requiringPatchIndex) {
            this.path = Objects.requireNonNull(path, "path");
            this.value = Objects.requireNonNull(value, BlueLanguageConstants.OBJECT_VALUE);
            if (requiringPatchIndex < 0) {
                throw new IllegalArgumentException(
                        "requiringPatchIndex must be non-negative");
            }
            this.requiringPatchIndex = requiringPatchIndex;
        }

        String path() {
            return path;
        }

        FrozenNode value() {
            return value;
        }

        int requiringPatchIndex() {
            return requiringPatchIndex;
        }
    }

    static final class UpdatePlan {
        private final List<BatchPatchRecord> records;
        private final FrozenNode preConformanceCanonicalRoot;
        private final FrozenNode preConformanceResolvedRoot;
        private final FrozenNode finalResolvedRoot;
        private final List<GeneralizationMetadataWrite> generatedWrites;
        private final boolean includeGeneratedUpdates;
        private final boolean[] laterOverlaps;

        UpdatePlan(List<BatchPatchRecord> records,
                   FrozenNode preConformanceCanonicalRoot,
                   FrozenNode preConformanceResolvedRoot,
                   FrozenNode finalResolvedRoot,
                   List<GeneralizationMetadataWrite> generatedWrites,
                   boolean includeGeneratedUpdates) {
            this.records = Collections.unmodifiableList(new ArrayList<>(
                    Objects.requireNonNull(records, "records")));
            this.preConformanceCanonicalRoot = Objects.requireNonNull(
                    preConformanceCanonicalRoot,
                    "preConformanceCanonicalRoot");
            this.preConformanceResolvedRoot = Objects.requireNonNull(preConformanceResolvedRoot,
                    "preConformanceResolvedRoot");
            this.finalResolvedRoot = Objects.requireNonNull(finalResolvedRoot, "finalResolvedRoot");
            this.generatedWrites = generatedWrites == null
                    ? Collections.<GeneralizationMetadataWrite>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(generatedWrites));
            this.includeGeneratedUpdates = includeGeneratedUpdates;
            this.laterOverlaps = computeLaterOverlaps(this.records);
        }

        List<DocumentUpdateData> build(
                UpdateMaterializationMetrics materializationMetrics) {
            return build(materializationMetrics, finalResolvedRoot);
        }

        List<DocumentUpdateData> build(
                UpdateMaterializationMetrics materializationMetrics,
                FrozenNode authoritativeResolvedRoot) {
            List<DocumentUpdateData> built = new ArrayList<>();
            ImmutablePatchPlanner finalResolvedPlanner = ImmutablePatchPlanner.forFrozen(
                    Objects.requireNonNull(authoritativeResolvedRoot, "authoritativeResolvedRoot"));
            Map<Integer, List<GeneralizationMetadataWrite>> generatedBeforeUpdates =
                    generatedWritesByRequiringRecord();
            for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
                appendGeneratedUpdates(
                        built,
                        generatedBeforeUpdates.get(recordIndex),
                        materializationMetrics);
                BatchPatchRecord record = records.get(recordIndex);
                FrozenNode before = record.beforeAtPatchTime();
                FrozenNode after = null;
                if (record.op() != JsonPatch.Op.REMOVE) {
                    after = laterOverlaps[recordIndex]
                            ? record.afterAtPatchTime()
                            : finalResolvedPlanner.read(record.path());
                }
                built.add(new DocumentUpdateData(record.path(),
                        before,
                        after,
                        semanticOperation(record, before),
                        record.originScope(),
                        record.cascadeScopes(),
                        materializationMetrics));
            }
            appendGeneratedUpdates(
                    built,
                    generatedBeforeUpdates.get(records.size()),
                    materializationMetrics);
            return Collections.unmodifiableList(built);
        }

        private Map<Integer, List<GeneralizationMetadataWrite>>
        generatedWritesByRequiringRecord() {
            if (!includeGeneratedUpdates || generatedWrites.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<Integer, List<GeneralizationMetadataWrite>> grouped =
                    new HashMap<>();
            for (GeneralizationMetadataWrite write : generatedWrites) {
                int recordIndex = write.requiringPatchIndex();
                List<GeneralizationMetadataWrite> writes =
                        grouped.get(recordIndex);
                if (writes == null) {
                    writes = new ArrayList<>();
                    grouped.put(recordIndex, writes);
                }
                if (!containsPath(writes, write.path())) {
                    writes.add(write);
                }
            }
            return grouped;
        }

        private boolean containsPath(
                List<GeneralizationMetadataWrite> writes,
                String path) {
            for (GeneralizationMetadataWrite write : writes) {
                if (write.path().equals(path)) {
                    return true;
                }
            }
            return false;
        }

        private void appendGeneratedUpdates(
                List<DocumentUpdateData> target,
                List<GeneralizationMetadataWrite> writes,
                UpdateMaterializationMetrics materializationMetrics) {
            if (writes == null || writes.isEmpty()) {
                return;
            }
            for (GeneralizationMetadataWrite write : writes) {
                String path = write.path();
                FrozenNode before = readGeneralizationMetadata(
                        preConformanceCanonicalRoot,
                        path);
                if (before == null) {
                    before = readGeneralizationMetadata(
                            preConformanceResolvedRoot,
                            path);
                }
                target.add(new DocumentUpdateData(path,
                        before,
                        write.value(),
                        before == null
                                ? JsonPatch.Op.ADD
                                : JsonPatch.Op.REPLACE,
                        originScopeForGeneratedUpdate(),
                        Collections.singletonList(JsonPointer.ROOT),
                        materializationMetrics));
            }
        }

        private FrozenNode readGeneralizationMetadata(
                FrozenNode root,
                String path) {
            List<String> segments = JsonPointer.split(path);
            if (segments.isEmpty()) {
                return null;
            }
            String field = segments.get(segments.size() - 1);
            FrozenNode parent = root.at(
                    segments.subList(0, segments.size() - 1));
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
