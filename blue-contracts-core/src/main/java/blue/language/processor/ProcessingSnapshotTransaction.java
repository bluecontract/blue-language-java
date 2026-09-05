package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePathEditor;

import java.util.Collections;
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
            ImmutablePatchPlanner.PatchPlan selectedPlan =
                    planning.canonicalPlanner().plan(
                            JsonPointer.ROOT, patch);
            ImmutablePatchPlanner.PatchPlan resolvedPlan =
                    planning.resolvedPlanner().plan(
                            JsonPointer.ROOT, patch);
            SnapshotPatchPlan snapshotPlan = prepareSnapshotPatch(
                    planning.baseSnapshot(), patch);
            commitSnapshotPatch(
                    snapshotPlan,
                    selectedPlan.root(),
                    resolvedPlan.root());
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
                    runtime.strictPlatformInvocation,
                    blue.language.identity.CanonicalTypeIdentityLookup
                            .incomplete(),
                    true);
        }
        ResolvedSnapshot base = runtime.snapshot != null
                ? runtime.snapshot
                : snapshotFromDocumentTransient(rollback);
        runtime.retainEntrySnapshot(base);
        FrozenNode selectedRoot = runtime.selectedDocumentBacked
                ? FrozenNode.fromResolvedNode(rollback)
                : base.frozenSourceRoot();
        return new PatchPlanningContext(
                base,
                ImmutablePatchPlanner.forFrozen(selectedRoot),
                ImmutablePatchPlanner.forFrozen(base.frozenResolvedRoot()),
                !runtime.selectedDocumentBacked,
                !runtime.selectedDocumentBacked ? manager : null,
                manager,
                runtime.scopes().keySet(),
                runtime.executableBodyFieldsByType,
                runtime.entryEmbeddedScopePlans(),
                base.isResolutionComplete(),
                runtime.strictPlatformInvocation,
                base.canonicalTypeIdentities(),
                runtime.selectedDocumentBacked
                        || base.isSourceBacked());
    }

    List<DocumentUpdateData> commitBatchPatchResult(
            BatchPatchResult result,
            boolean insertSharedSnapshot,
            ProcessingSnapshotManager commitManager) {
        BatchPatchResult exactResult = Objects.requireNonNull(
                result, "result");
        if (runtime.selectedDocumentBacked) {
            Node tentativeSelected = tentativeSelectedRoot(exactResult);
            ResolvedSnapshot authoritative = commitManager != null
                    ? snapshotFromDocument(
                            tentativeSelected, true, commitManager)
                    : null;
            long buildUpdatesStart = System.nanoTime();
            List<DocumentUpdateData> updates;
            try {
                updates = exactResult.updatesAgainst(
                        authoritative,
                        commitManager,
                        runtime.updateMaterializationMetrics());
            } finally {
                long nanos = System.nanoTime() - buildUpdatesStart;
                runtime.counters().recordBuildUpdatesNanos(nanos);
                runtime.observe(
                        ProcessingMetricId.BATCH_PATCH_BUILD_UPDATES_NANOS,
                        nanos);
            }
            boolean published = commitManager != null
                    && insertSharedSnapshot
                    && authoritative.isResolutionComplete();
            ResolvedSnapshot committed = authoritative == null
                    ? null
                    : insertSharedSnapshot
                            ? DocumentProcessingRuntime
                                    .cacheSnapshotIfComplete(
                                            commitManager,
                                            authoritative)
                            : authoritative;
            runtime.materializedView.replaceWith(tentativeSelected);
            runtime.snapshot = committed;
            runtime.materializedViewStale = false;
            markStateAdvanced(published);
            runtime.recordCommittedPatchEvidence(exactResult);
            return updates;
        }
        if (commitManager == null) {
            Node next = exactResult.resolvedRoot().toNode();
            runtime.materializedView.replaceWith(next);
            runtime.snapshot = null;
            runtime.materializedViewStale = false;
            markStateAdvanced(false);
            runtime.recordCommittedPatchEvidence(exactResult);
            return exactResult.updates();
        }
        ResolvedSnapshot next =
                DocumentProcessingRuntime.snapshotWithCompleteness(
                        exactResult.canonicalRoot(),
                        exactResult.resolvedRoot(),
                        exactResult.isResolutionComplete(),
                        exactResult.isSourceBacked(),
                        insertSharedSnapshot,
                        exactResult.canonicalTypeIdentities());
        boolean published = insertSharedSnapshot
                && next.isResolutionComplete();
        ResolvedSnapshot committed = insertSharedSnapshot
                ? DocumentProcessingRuntime.cacheSnapshotIfComplete(
                        commitManager, next)
                : next;
        runtime.snapshot = committed;
        commitMaterializedSnapshot(committed);
        markStateAdvanced(published);
        runtime.recordCommittedPatchEvidence(exactResult);
        return exactResult.updates();
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

    /**
     * Promotes a successful managed-step continuation rewrite into the same
     * invocation snapshot used to plan and charge the next authored patch.
     */
    void synchronizeSelectedDocumentAfterContinuation(
            FrozenNode selectedBeforeContinuation) {
        if (!runtime.selectedDocumentBacked) {
            return;
        }
        FrozenNode before = Objects.requireNonNull(
                selectedBeforeContinuation,
                "selectedBeforeContinuation");
        FrozenNode after = FrozenNode.fromResolvedNode(
                runtime.materializedView.copyRoot());
        if (before.sameResolvedStructure(after)) {
            return;
        }

        ProcessingSnapshotManager manager = currentManager();
        runtime.snapshot = manager != null
                ? snapshotFromDocument(
                        runtime.materializedView.copyRoot(), true, manager)
                : null;
        runtime.materializedViewStale = false;
        markStateAdvanced(false);
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
                        ExecutableBodyPathCatalog.fromNodeIncludingTypeContractFields(
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
            if (!preservedPaths.isEmpty()) {
                preservedPaths.addAll(
                        ExecutableBodyPathCatalog
                                .processorStateReferencePaths(
                                        document, openedScopePaths));
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

    ResolvedSnapshot resolveSelectedExecutableReference(
            FrozenNode reference) {
        ProcessingSnapshotManager manager = currentManager();
        FrozenNode exact = materializeSelectedExecutableReference(reference);
        Node exactBody = exact.toNode();
        Set<String> preservedPaths = new LinkedHashSet<>(
                ExecutableBodyPathCatalog.ordinaryReferencePaths(
                        exactBody));
        preservedPaths.addAll(
                ExecutableBodyPathCatalog.opaqueCyclicMemberPaths(
                        exactBody));
        ResolvedSnapshot resolved = Objects.requireNonNull(
                preservedPaths.isEmpty()
                        ? manager.fromDocumentTransient(exactBody)
                        : ExecutableBodyPathCatalog.forceDeferredResolution(
                                manager.fromDocumentTransientPreservingPaths(
                                        exactBody,
                                        preservedPaths)),
                "selectedExecutableBodySnapshot");
        CanonicalTypeIdentityLookup graphEvidence =
                CanonicalTypeIdentityEvidenceUnion
                        .establishForResolvedGraph(
                                resolved.resolvedRoot(),
                                Collections.singletonList(
                                        resolved.canonicalTypeIdentities()));
        if (!resolved.hasCanonicalIdentity()) {
            /*
             * materializeVerifiedExact established this provider body as the
             * exact canonical value named by the selected reference. A
             * preservation-limited resolver may omit whole-source identity,
             * but that must not discard the independently verified root.
             */
            resolved = ResolvedSnapshot.withDeferredResolution(
                    exact,
                    resolved.frozenResolvedRoot(),
                    graphEvidence);
        } else if (!resolved.canonicalTypeIdentities()
                .hasCompleteCoverage()) {
            resolved = resolved.withCanonicalIdentityEvidence(
                    graphEvidence);
        }
        if (!BlueIds.hasCyclicMemberSeparator(
                reference.getReferenceBlueId())
                && !reference.getReferenceBlueId().equals(
                        resolved.blueId())) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory.InvalidProcessingDocument,
                    "Selected executable body resolver identity mismatch: expected "
                            + reference.getReferenceBlueId()
                            + " but established " + resolved.blueId());
        }
        return resolved;
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
            FrozenNode fallbackSelectedRoot,
            FrozenNode fallbackResolvedRoot) {
        if (runtime.snapshotManager == null || plan == null) {
            runtime.materializedView.replaceWith(
                    fallbackResolvedRoot.toNode());
            runtime.materializedViewStale = false;
            markStateAdvanced(false);
            return;
        }
        runtime.snapshot = plan.next != null
                ? plan.next
                : snapshotFromDocument(
                        fallbackSelectedRoot.toNode());
        commitMaterializedSnapshot(runtime.snapshot);
        markStateAdvanced(false);
    }

    private Node tentativeSelectedRoot(BatchPatchResult result) {
        Node selected = result.selectedCanonicalRoot().toNode();
        List<BatchPatchResult.GeneralizationMetadataWrite> metadataWrites =
                result.generalizationMetadataWrites();
        for (BatchPatchResult.GeneralizationMetadataWrite write
                : metadataWrites) {
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
