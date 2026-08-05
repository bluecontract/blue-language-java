package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePathEditor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Provides the invocation's current atomic canonical/resolved snapshot. */
final class ProcessingSnapshotTransaction {

    private final DocumentProcessingRuntime runtime;

    ProcessingSnapshotTransaction(DocumentProcessingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    ResolvedSnapshot current() {
        return runtime.snapshot();
    }

    void publishFallbackDirectWrite(String path, Node value) {
        Node rollback = runtime.materializedView.copyRoot();
        ResolvedSnapshot snapshotRollback = runtime.snapshot;
        try {
            PatchPlanningContext planning =
                    planningContext(rollback);
            FrozenNode before = planning.canonicalPlanner().read(path);
            Node beforeNode = before != null ? before.toNode() : null;
            JsonPatch patch = directWritePatch(path, beforeNode, value);
            if (patch == null) {
                return;
            }
            planning.canonicalPlanner().plan(JsonPointer.ROOT, patch);
            ImmutablePatchPlanner.PatchPlan resolvedPlan =
                    planning.resolvedPlanner().plan(
                            JsonPointer.ROOT, patch);
            SnapshotPatchPlan snapshotPlan = prepareSnapshotPatch(
                    planning.baseSnapshot(), patch);
            commitSnapshotPatch(snapshotPlan, resolvedPlan.root());
            runtime.changedPaths.add(PointerUtils.normalizePointer(path));
        } catch (RuntimeException failure) {
            runtime.materializedView.replaceWith(rollback);
            runtime.snapshot = snapshotRollback;
            runtime.materializedViewStale = false;
            throw failure;
        }
    }

    PatchPlanningContext planningContext(Node rollback) {
        ProcessingSnapshotManager manager = currentManager();
        if (manager == null || canPlanFromSelectedWithoutSnapshot()) {
            ImmutablePatchPlanner planner =
                    ImmutablePatchPlanner.forMaterialized(rollback);
            return new PatchPlanningContext(
                    null,
                    planner,
                    planner,
                    false,
                    null,
                    manager,
                    runtime.scopes().keySet(),
                    runtime.executableBodyFieldsByType,
                    runtime.entryEmbeddedScopePlans(),
                    true,
                    runtime.strictPlatformInvocation);
        }
        ResolvedSnapshot base = runtime.snapshot != null
                ? runtime.snapshot
                : snapshotFromDocument(rollback);
        return new PatchPlanningContext(
                base,
                ImmutablePatchPlanner.forSnapshot(base),
                ImmutablePatchPlanner.forFrozen(base.frozenResolvedRoot()),
                !runtime.selectedDocumentBacked,
                !runtime.selectedDocumentBacked ? manager : null,
                manager,
                runtime.scopes().keySet(),
                runtime.executableBodyFieldsByType,
                runtime.entryEmbeddedScopePlans(),
                base.isResolutionComplete(),
                runtime.strictPlatformInvocation);
    }

    List<DocumentUpdateData> commitBatchPatchResult(
            BatchPatchResult result,
            boolean insertSharedSnapshot,
            ProcessingSnapshotManager commitManager) {
        if (commitManager == null) {
            Node next = result.resolvedRoot().toNode();
            runtime.materializedView.replaceWith(next);
            runtime.snapshot = null;
            runtime.materializedViewStale = false;
            markStateAdvanced(false);
            return result.updates();
        }
        if (runtime.selectedDocumentBacked) {
            Node tentativeSelected = tentativeSelectedRoot(result);
            ResolvedSnapshot authoritative = snapshotFromDocument(
                    tentativeSelected, true, commitManager);
            long buildUpdatesStart = System.nanoTime();
            List<DocumentUpdateData> updates;
            try {
                updates = result.updatesAgainst(
                        authoritative.frozenResolvedRoot(),
                        runtime.updateMaterializationMetrics());
            } finally {
                long nanos = System.nanoTime() - buildUpdatesStart;
                runtime.counters().recordBuildUpdatesNanos(nanos);
                runtime.observe(
                        ProcessingMetricId.BATCH_PATCH_BUILD_UPDATES_NANOS,
                        nanos);
            }
            boolean published = insertSharedSnapshot
                    && authoritative.isResolutionComplete();
            ResolvedSnapshot committed = insertSharedSnapshot
                    ? DocumentProcessingRuntime.cacheSnapshotIfComplete(
                            commitManager, authoritative)
                    : authoritative;
            runtime.materializedView.replaceWith(tentativeSelected);
            runtime.snapshot = committed;
            runtime.materializedViewStale = false;
            markStateAdvanced(published);
            return updates;
        }
        ResolvedSnapshot next =
                DocumentProcessingRuntime.snapshotWithCompleteness(
                        result.canonicalRoot(),
                        result.resolvedRoot(),
                        result.isResolutionComplete(),
                        insertSharedSnapshot);
        boolean published = insertSharedSnapshot
                && next.isResolutionComplete();
        ResolvedSnapshot committed = insertSharedSnapshot
                ? DocumentProcessingRuntime.cacheSnapshotIfComplete(
                        commitManager, next)
                : next;
        runtime.snapshot = committed;
        commitMaterializedSnapshot(committed);
        markStateAdvanced(published);
        return result.updates();
    }

    void commitMaterializedSnapshot(ResolvedSnapshot committed) {
        if (runtime.lazyMaterializedCommits) {
            runtime.materializedViewStale = true;
            return;
        }
        runtime.materializedView.replaceWithSnapshot(committed);
        runtime.materializedViewStale = false;
    }

    void syncMaterializedView() {
        if (runtime.materializedViewStale && runtime.snapshot != null) {
            runtime.materializedView.replaceWithSnapshot(runtime.snapshot);
            runtime.materializedViewStale = false;
        }
    }

    ResolvedSnapshot snapshotFromDocument(Node document) {
        return snapshotFromDocument(document, false);
    }

    ResolvedSnapshot snapshotFromDocumentTransient(Node document) {
        return snapshotFromDocument(document, true);
    }

    ResolvedSnapshot snapshotFromDocument(
            Node document,
            boolean transientResolution,
            ProcessingSnapshotManager manager) {
        long start = System.nanoTime();
        try {
            Set<String> preservedPaths = new LinkedHashSet<>();
            Set<String> openedScopePaths = new LinkedHashSet<>(
                    runtime.scopes().keySet());
            if (runtime.selectedDocumentBacked
                    && !runtime.strictPlatformInvocation) {
                preservedPaths.addAll(
                        ExecutableBodyPathCatalog.fromNodeDirectContracts(
                                document,
                                openedScopePaths,
                                runtime.executableBodyFieldsByType,
                                manager));
            }
            if (runtime.strictPlatformInvocation) {
                preservedPaths.addAll(
                        ExecutableBodyPathCatalog
                                .fromNodeIncludingTypeContracts(
                                        document,
                                        runtime.evidenceScopePaths(),
                                        runtime.executableBodyFieldsByType,
                                        manager));
                preservedPaths.addAll(
                        ExecutableBodyPathCatalog
                                .ordinaryReferencePaths(
                                        document,
                                        runtime.evidenceScopePaths()));
            }
            preservedPaths.addAll(
                    ExecutableBodyPathCatalog
                            .opaqueCyclicMemberPaths(document));
            if (!preservedPaths.isEmpty()) {
                ResolvedSnapshot preserved = transientResolution
                        ? manager.fromDocumentTransientPreservingPaths(
                                document, preservedPaths)
                        : manager.fromDocumentPreservingPaths(
                                document, preservedPaths);
                return ExecutableBodyPathCatalog.forceDeferredResolution(
                        preserved);
            }
            return transientResolution
                    ? manager.fromDocumentTransient(document)
                    : manager.fromDocument(document);
        } finally {
            runtime.observe(
                    ProcessingMetricId
                            .PROCESSING_SNAPSHOT_FROM_DOCUMENT_BUILDS,
                    1L);
            runtime.observe(
                    ProcessingMetricId
                            .PROCESSING_SNAPSHOT_FROM_DOCUMENT_NANOS,
                    System.nanoTime() - start);
        }
    }

    ProcessingSnapshotManager currentManager() {
        return runtime.activeSequenceSnapshotManager != null
                ? runtime.activeSequenceSnapshotManager
                : runtime.snapshotManager;
    }

    ConformanceEngine currentConformanceEngine() {
        return runtime.activeSequenceSnapshotManager != null
                ? runtime.activeSequenceSnapshotManager
                        .transientConformanceEngine(runtime.conformanceEngine)
                : runtime.conformanceEngine;
    }

    ExternalChannelFunctionEvaluation.MatcherSessionFactory
    externalChannelMatcherSessions() {
        return ExternalChannelFunctionEvaluation.verifiedMatcherSessions(
                currentManager());
    }

    FrozenNode materializeSelectedExecutableReference(
            FrozenNode reference) {
        ProcessingSnapshotManager manager = currentManager();
        if (manager == null) {
            throw new IllegalStateException(
                    "Selected executable body materialization requires the "
                            + "active ProcessingSnapshotManager");
        }
        return ExecutableBodyPathCatalog.materializeVerifiedExact(
                manager, reference, "Selected executable body");
    }

    Supplier<Node> checkpointSubjectMaterializer(Node subjectReference) {
        final Node capturedReference = Objects.requireNonNull(
                subjectReference, "subjectReference").clone();
        final ProcessingSnapshotManager capturedManager = currentManager();
        return () -> {
            FrozenNode reference = FrozenNode.fromNode(capturedReference);
            if (!reference.isReferenceOnly()) {
                throw new ProcessorFailureException(
                        ProcessorErrorCategory.InvalidProcessingDocument,
                        "Checkpoint subject must be an exact pure reference");
            }
            if (capturedManager == null) {
                throw new IllegalStateException(
                        "Checkpoint subject materialization requires the "
                                + "active ProcessingSnapshotManager");
            }
            return ExecutableBodyPathCatalog.materializeVerifiedExact(
                    capturedManager,
                    reference,
                    "Checkpoint subject")
                    .toNode();
        };
    }

    void markStateAdvanced(boolean sharedSnapshotInserted) {
        runtime.stateVersion++;
        if (sharedSnapshotInserted) {
            runtime.sharedSnapshotVersion = runtime.stateVersion;
        }
    }

    void promoteCurrentSequenceSnapshot(ProcessingSnapshotManager manager) {
        if (manager == null
                || runtime.snapshot == null
                || !runtime.snapshot.isResolutionComplete()
                || runtime.sharedSnapshotVersion == runtime.stateVersion) {
            return;
        }
        long start = System.nanoTime();
        ResolvedSnapshot cached =
                DocumentProcessingRuntime.cacheSnapshotIfComplete(
                        manager, runtime.snapshot);
        runtime.snapshot = cached;
        runtime.sharedSnapshotVersion = runtime.stateVersion;
        if (!runtime.selectedDocumentBacked) {
            commitMaterializedSnapshot(cached);
        }
        runtime.counters().recordFinalSharedSnapshotCacheInsert();
        runtime.observe(
                ProcessingMetricId.SEQUENCE_SHARED_SNAPSHOT_CACHE_INSERTS,
                1L);
        runtime.observe(
                ProcessingMetricId.SEQUENCE_FINAL_SNAPSHOT_CACHE_INSERTS,
                1L);
        runtime.observe(
                ProcessingMetricId.SEQUENCE_FINAL_CACHE_COMMIT_NANOS,
                System.nanoTime() - start);
    }

    private boolean canPlanFromSelectedWithoutSnapshot() {
        return runtime.usesAuthoritativeSelectedSnapshot()
                && runtime.snapshot == null
                && runtime.conformanceEngine == null
                && (runtime.conformancePlannerOverride == null
                        || !runtime.conformancePlannerOverride.applies());
    }

    private SnapshotPatchPlan prepareSnapshotPatch(
            ResolvedSnapshot base,
            JsonPatch patch) {
        ProcessingSnapshotManager manager = currentManager();
        if (manager == null || base == null) {
            return null;
        }
        try {
            return new SnapshotPatchPlan(manager.applyPatch(base, patch));
        } catch (RuntimeException ignored) {
            return new SnapshotPatchPlan(null);
        }
    }

    private void commitSnapshotPatch(
            SnapshotPatchPlan plan,
            FrozenNode fallbackRoot) {
        if (runtime.snapshotManager == null || plan == null) {
            runtime.materializedView.replaceWith(fallbackRoot.toNode());
            runtime.materializedViewStale = false;
            markStateAdvanced(false);
            return;
        }
        runtime.snapshot = plan.next != null
                ? plan.next
                : snapshotFromDocument(fallbackRoot.toNode());
        commitMaterializedSnapshot(runtime.snapshot);
        markStateAdvanced(false);
    }

    private Node tentativeSelectedRoot(BatchPatchResult result) {
        FrozenNode tentative = FrozenNode.fromResolvedNode(
                runtime.materializedView.copyRoot());
        for (ImmutableJsonPatch patch : result.requestedPatches()) {
            tentative = ImmutablePatchPlanner.forFrozen(tentative)
                    .plan(JsonPointer.ROOT, patch)
                    .root();
        }
        Node selected = tentative.toNode();
        for (BatchPatchResult.GeneralizationMetadataWrite write
                : result.generalizationMetadataWrites()) {
            NodePathEditor.put(
                    selected, write.path(), write.value().toNode());
        }
        return selected;
    }

    private ResolvedSnapshot snapshotFromDocument(
            Node document,
            boolean transientResolution) {
        return snapshotFromDocument(
                document, transientResolution, currentManager());
    }

    private static JsonPatch directWritePatch(
            String path,
            Node before,
            Node value) {
        if (before == null && value == null) {
            return null;
        }
        if (value == null) {
            return JsonPatch.remove(path);
        }
        return before == null
                ? JsonPatch.add(path, value.clone())
                : JsonPatch.replace(path, value.clone());
    }

    private static final class SnapshotPatchPlan {
        private final ResolvedSnapshot next;

        private SnapshotPatchPlan(ResolvedSnapshot next) {
            this.next = next;
        }
    }
}
