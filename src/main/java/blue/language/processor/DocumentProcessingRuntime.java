package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.JsonPointer;
import blue.language.utils.MergeReverser;
import blue.language.utils.NodePathEditor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime state holder for a single document-processing invocation.
 */
public final class DocumentProcessingRuntime {

    private final MaterializedDocumentView materializedView;
    private final EmissionRegistry emissionRegistry;
    private final GasMeter gasMeter;
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final ProcessingSnapshotManager snapshotManager;
    private final ProcessingMetricsSink metrics;
    private final boolean lazyMaterializedCommits;
    private final boolean selectedDocumentBacked;
    private ResolvedSnapshot snapshot;
    private ProcessingSnapshotManager activeSequenceSnapshotManager;
    private boolean materializedViewStale;
    private boolean runTerminated;
    private long batchPatchCalls;
    private long batchPatchEntries;
    private long batchPatchPlanningNanos;
    private long batchPatchConformanceNanos;
    private long batchPatchBuildUpdatesNanos;
    private long batchPatchCommitNanos;
    private long batchPatchRollbackCopies;
    private long documentUpdateBeforeNodeMaterializations;
    private long documentUpdateAfterNodeMaterializations;
    private long stateVersion;
    private long sharedSnapshotVersion;
    private long patchSequencesPrepared;
    private long singletonPatchTransactions;
    private long sequenceIntermediateSnapshotAdvances;
    private long sequenceSharedSnapshotCacheInserts;
    private long sequenceFinalSnapshotCacheInserts;
    private long sequenceSuffixRebases;
    private long sequenceStalePreviewFallbacks;
    private long sequenceFallbackPatches;

    public DocumentProcessingRuntime(Node document) {
        this(document, null, null);
    }

    public DocumentProcessingRuntime(Node document, ConformanceEngine conformanceEngine) {
        this(document, conformanceEngine, null);
    }

    public DocumentProcessingRuntime(Node document,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager) {
        this(document, conformanceEngine, snapshotManager, null);
    }

    public DocumentProcessingRuntime(Node document,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager,
                                     ProcessingMetricsSink metrics) {
        this(document, conformanceEngine, null, snapshotManager, metrics);
    }

    public DocumentProcessingRuntime(Node document,
                                     ConformanceEngine conformanceEngine,
                                     ConformancePlannerOverride conformancePlannerOverride,
                                     ProcessingSnapshotManager snapshotManager,
                                     ProcessingMetricsSink metrics) {
        this.materializedView = new MaterializedDocumentView(Objects.requireNonNull(document, "document"));
        this.emissionRegistry = new EmissionRegistry();
        this.gasMeter = new GasMeter();
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
        this.lazyMaterializedCommits = false;
        this.selectedDocumentBacked = true;
    }

    public DocumentProcessingRuntime(ResolvedSnapshot snapshot,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager) {
        this(snapshot, conformanceEngine, snapshotManager, null);
    }

    public DocumentProcessingRuntime(ResolvedSnapshot snapshot,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager,
                                     ProcessingMetricsSink metrics) {
        this(snapshot, conformanceEngine, null, snapshotManager, metrics);
    }

    public DocumentProcessingRuntime(ResolvedSnapshot snapshot,
                                     ConformanceEngine conformanceEngine,
                                     ConformancePlannerOverride conformancePlannerOverride,
                                     ProcessingSnapshotManager snapshotManager,
                                     ProcessingMetricsSink metrics) {
        ResolvedSnapshot processorSnapshot = processorSnapshot(Objects.requireNonNull(snapshot, "snapshot"));
        this.materializedView = new MaterializedDocumentView(processorSnapshot.canonicalRoot());
        this.emissionRegistry = new EmissionRegistry();
        this.gasMeter = new GasMeter();
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.snapshot = processorSnapshot;
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
        this.lazyMaterializedCommits = true;
        this.selectedDocumentBacked = false;
    }

    private ResolvedSnapshot processorSnapshot(ResolvedSnapshot snapshot) {
        if (!snapshot.frozenCanonicalRoot().isStrictBlueIdValidation()) {
            return snapshot;
        }
        FrozenNode canonicalRoot = FrozenNode.fromUncheckedCanonicalNode(snapshot.canonicalRoot());
        return new ResolvedSnapshot(canonicalRoot, snapshot.frozenResolvedRoot(), canonicalRoot.blueId());
    }

    public Node document() {
        if (!selectedDocumentBacked && snapshot != null) {
            return snapshot.resolvedRoot();
        }
        syncMaterializedView();
        return materializedView.root();
    }

    Node selectedDocument() {
        return document();
    }

    void replaceDocument(Node document) {
        materializedView.replaceWith(Objects.requireNonNull(document, "document"));
        snapshot = null;
        materializedViewStale = false;
        markStateAdvanced(false);
    }

    public Map<String, ScopeRuntimeContext> scopes() {
        return emissionRegistry.scopes();
    }

    public ScopeRuntimeContext scope(String scopePath) {
        ScopeRuntimeContext context = emissionRegistry.scope(scopePath);
        if ("/".equals(PointerUtils.normalizeScope(scopePath))) {
            context.setEmbeddedDepth(0);
        }
        return context;
    }

    public ScopeRuntimeContext existingScope(String scopePath) {
        return emissionRegistry.existingScope(scopePath);
    }

    public List<Node> rootEmissions() {
        return emissionRegistry.rootEmissions();
    }

    public void recordRootEmission(Node emission) {
        emissionRegistry.recordRootEmission(emission);
    }

    public void addGas(long amount) {
        gasMeter.add(amount);
    }

    public long totalGas() {
        return gasMeter.totalGas();
    }

    public void chargeScopeEntry(String scopePath) {
        gasMeter.chargeScopeEntry(scope(scopePath).embeddedDepth());
    }

    public void setScopeEmbeddedDepth(String scopePath, int depth) {
        scope(scopePath).setEmbeddedDepth(depth);
    }

    public int scopeEmbeddedDepth(String scopePath) {
        return scope(scopePath).embeddedDepth();
    }

    public void chargeInitialization() {
        gasMeter.chargeInitialization();
    }

    public void chargeChannelMatchAttempt() {
        gasMeter.chargeChannelMatchAttempt();
    }

    public void chargeHandlerOverhead() {
        gasMeter.chargeHandlerOverhead();
    }

    public void chargeBoundaryCheck() {
        gasMeter.chargeBoundaryCheck();
    }

    public void chargePatchAddOrReplace(Node value) {
        gasMeter.chargePatchAddOrReplace(value);
    }

    public void chargeFrozenPatchAddOrReplace(FrozenNode value) {
        gasMeter.chargeFrozenPatchAddOrReplace(value);
    }

    public void chargeFrozenPatchAddOrReplace(long authoredCanonicalSizeBytes) {
        gasMeter.chargeFrozenPatchAddOrReplace(authoredCanonicalSizeBytes);
    }

    public void chargePatchRemove() {
        gasMeter.chargePatchRemove();
    }

    public void chargeCascadeRouting(int scopeCount) {
        gasMeter.chargeCascadeRouting(scopeCount);
    }

    public void chargeEmitEvent(Node event) {
        gasMeter.chargeEmitEvent(event);
    }

    public void chargeBridge(Node event) {
        gasMeter.chargeBridge(event);
    }

    public void chargeDrainEvent() {
        gasMeter.chargeDrainEvent();
    }

    public void chargeCheckpointUpdate() {
        gasMeter.chargeCheckpointUpdate();
    }

    public void chargeTerminationMarker() {
        gasMeter.chargeTerminationMarker();
    }

    public void chargeLifecycleDelivery() {
        gasMeter.chargeLifecycleDelivery();
    }

    public void chargeFatalTerminationOverhead() {
        gasMeter.chargeFatalTerminationOverhead();
    }

    public boolean isRunTerminated() {
        return runTerminated;
    }

    public void markRunTerminated() {
        runTerminated = true;
    }

    public boolean isScopeTerminated(String scopePath) {
        return emissionRegistry.isScopeTerminated(scopePath);
    }

    public ResolvedSnapshot snapshot() {
        if (snapshot == null && snapshotManager != null) {
            snapshot = snapshotFromDocument(materializedView.root());
            if (!selectedDocumentBacked) {
                materializedView.replaceWithSnapshot(snapshot);
            }
        }
        return snapshot;
    }

    public Node resolvedNodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.resolvedNodeAt(normalized);
        }
        return materializedView.nodeAt(normalized);
    }

    public FrozenNode resolvedFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.resolvedAt(normalized);
        }
        Node node = materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    FrozenNode selectedFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        if (!selectedDocumentBacked) {
            ResolvedSnapshot current = snapshot();
            if (current != null) {
                return current.resolvedAt(normalized);
            }
        }
        Node node = materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    public Node canonicalNodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.canonicalNodeAt(normalized);
        }
        return materializedView.nodeAt(normalized);
    }

    public FrozenNode canonicalFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.canonicalAt(normalized);
        }
        Node node = materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    public WorkingDocument workingDocument(String originScopePath) {
        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        ResolvedSnapshot current = snapshot;
        boolean materializedFallback = false;
        if (current == null && snapshotManager != null) {
            syncMaterializedView();
            current = snapshotFromDocument(materializedView.copyRoot());
            snapshot = current;
            sharedSnapshotVersion = stateVersion;
            materializedFallback = true;
        }
        if (current != null) {
            return new WorkingDocument(normalizedScope,
                    current.frozenCanonicalRoot(),
                    current.frozenResolvedRoot(),
                    conformanceEngine,
                    conformancePlannerOverride,
                    currentSnapshotManager(),
                    current,
                    materializedFallback,
                    !selectedDocumentBacked,
                    metrics);
        }

        Node root = materializedView.copyRoot();
        FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(new MergeReverser().reverse(root.clone()));
        FrozenNode resolved = FrozenNode.fromResolvedNode(root.clone());
        return new WorkingDocument(normalizedScope,
                canonical,
                resolved,
                conformanceEngine,
                conformancePlannerOverride,
                currentSnapshotManager(),
                null,
                true,
                false,
                metrics);
    }

    public Node nodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        if (snapshot != null) {
            return snapshot.resolvedNodeAt(normalized);
        }
        return materializedView.nodeAt(normalized);
    }

    public boolean contains(String path) {
        return nodeAt(path) != null;
    }

    public boolean hasInitializationMarker(String scopePath) {
        String pointer = PointerUtils.resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_INITIALIZED);
        Node marker = canonicalNodeAt(pointer);
        if (marker == null) {
            return false;
        }
        ProcessorEngine.validateInitializationMarker(marker, pointer);
        return true;
    }

    public ProcessorEngine.TerminationMarker terminationMarker(String scopePath) {
        String pointer = PointerUtils.resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_TERMINATED);
        Node marker = canonicalNodeAt(pointer);
        if (marker == null) {
            return null;
        }
        return ProcessorEngine.validateTerminationMarker(marker, pointer);
    }

    public boolean hasTerminationMarker(String scopePath) {
        return terminationMarker(scopePath) != null;
    }

    public void markScopeTerminatedFromMarker(String scopePath) {
        ProcessorEngine.TerminationMarker marker = terminationMarker(scopePath);
        if (marker == null) {
            return;
        }
        scope(scopePath).finalizeTermination(marker.kind, marker.reason);
    }

    public void directWrite(String path, Node value) {
        if (usesAuthoritativeSelectedSnapshot()) {
            directWriteSelected(path, value);
            return;
        }
        if (snapshotManager != null && snapshot != null) {
            directWriteSnapshot(path, value);
            return;
        }
        Node rollback = materializedView.copyRoot();
        ResolvedSnapshot snapshotRollback = snapshot;
        try {
            PlanningContext planning = planningContext(rollback);
            FrozenNode before = planning.canonicalPlanner.read(path);
            Node beforeNode = before != null ? before.toNode() : null;
            JsonPatch snapshotPatch = directWritePatch(path, beforeNode, value);
            if (snapshotPatch == null) {
                return;
            }
            planning.canonicalPlanner.plan("/", snapshotPatch);
            ImmutablePatchPlanner.PatchPlan resolvedPlan = planning.resolvedPlanner.plan("/", snapshotPatch);
            SnapshotPatchPlan snapshotPatchPlan = prepareSnapshotPatch(planning.baseSnapshot, snapshotPatch);
            commitSnapshotPatch(snapshotPatchPlan, resolvedPlan.root());
        } catch (RuntimeException ex) {
            materializedView.replaceWith(rollback);
            snapshot = snapshotRollback;
            materializedViewStale = false;
            throw ex;
        }
    }

    private void directWriteSelected(String path, Node value) {
        Node selectedRollback = materializedView.copyRoot();
        ResolvedSnapshot snapshotRollback = snapshot;
        try {
            Node before = ImmutablePatchPlanner.readNode(selectedRollback, path);
            JsonPatch patch = directWritePatch(path, before, value);
            if (patch == null) {
                return;
            }
            Node tentativeSelected = selectedRollback.clone();
            applyMaterializedDirectWrite(tentativeSelected, path, value);
            ResolvedSnapshot authoritative = snapshotFromDocument(tentativeSelected);
            ResolvedSnapshot cached = Objects.requireNonNull(
                    currentSnapshotManager().cacheSnapshot(authoritative),
                    "cachedSnapshot");
            materializedView.replaceWith(tentativeSelected);
            snapshot = cached;
            materializedViewStale = false;
            markStateAdvanced(true);
        } catch (RuntimeException ex) {
            materializedView.replaceWith(selectedRollback);
            snapshot = snapshotRollback;
            materializedViewStale = false;
            throw ex;
        }
    }

    private void directWriteSnapshot(String path, Node value) {
        ResolvedSnapshot snapshotRollback = snapshot;
        try {
            PlanningContext planning = planningContext(materializedView.root());
            FrozenNode before = planning.canonicalPlanner.read(path);
            Node beforeNode = before != null ? before.toNode() : null;
            JsonPatch snapshotPatch = directWritePatch(path, beforeNode, value);
            if (snapshotPatch == null) {
                return;
            }
            ImmutablePatchPlanner.PatchPlan canonicalPlan =
                    planning.canonicalPlanner.planWithExactReplacement("/", snapshotPatch);
            ResolvedSnapshot next = planning.resolveCanonical(canonicalPlan.root());
            snapshot = currentSnapshotManager().cacheSnapshot(next);
            commitMaterializedSnapshot(snapshot);
            markStateAdvanced(true);
        } catch (RuntimeException ex) {
            snapshot = snapshotRollback;
            throw ex;
        }
    }

    private void applyMaterializedDirectWrite(Node root, String path, Node value) {
        if (value == null) {
            removeMaterializedPath(root, path);
        } else {
            NodePathEditor.put(root, path, value.clone());
        }
    }

    private void removeMaterializedPath(Node root, String path) {
        List<String> segments = JsonPointer.split(path);
        if (segments.isEmpty()) {
            root.replaceWith(new Node());
            return;
        }
        List<String> parentSegments = new ArrayList<>(segments.subList(0, segments.size() - 1));
        Node parent = NodePathEditor.getOrNull(root, JsonPointer.toPointer(parentSegments));
        if (parent == null) {
            return;
        }
        String leaf = segments.get(segments.size() - 1);
        if ("type".equals(leaf)) {
            parent.type((Node) null);
        } else if ("itemType".equals(leaf)) {
            parent.itemType((Node) null);
        } else if ("keyType".equals(leaf)) {
            parent.keyType((Node) null);
        } else if ("valueType".equals(leaf)) {
            parent.valueType((Node) null);
        } else if ("blue".equals(leaf)) {
            parent.blue(null);
        } else if ("contracts".equals(leaf)) {
            parent.contracts(null);
        } else if (JsonPointer.isArrayIndexSegment(leaf) && parent.getItems() != null && !"-".equals(leaf)) {
            int index = Integer.parseInt(leaf);
            if (index >= 0 && index < parent.getItems().size()) {
                parent.getItems().remove(index);
            }
        } else if (parent.getProperties() != null) {
            parent.getProperties().remove(leaf);
        }
    }

    public DocumentUpdateData applyPatch(String originScopePath, JsonPatch patch) {
        if (patch == null) {
            return null;
        }
        List<DocumentUpdateData> updates = applyPatches(originScopePath, Collections.singletonList(patch));
        return updates.isEmpty() ? null : updates.get(0);
    }

    public List<DocumentUpdateData> applyPatches(String originScopePath, List<JsonPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        return applyPatchInputs(originScopePath, PatchInput.mutableList(patches));
    }

    public DocumentUpdateData applyFrozenPatch(String originScopePath, FrozenJsonPatch patch) {
        if (patch == null) {
            return null;
        }
        List<DocumentUpdateData> updates = applyFrozenPatches(
                originScopePath, Collections.singletonList(patch));
        return updates.isEmpty() ? null : updates.get(0);
    }

    /** Applies frozen patches as one rollback-all atomic transaction. */
    public List<DocumentUpdateData> applyFrozenPatches(String originScopePath,
                                                       List<FrozenJsonPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        return applyPatchInputs(originScopePath, PatchInput.frozenList(patches));
    }

    private List<DocumentUpdateData> applyPatchInputs(String originScopePath,
                                                      List<PatchInput> patches) {
        Node selectedRollback = selectedDocumentBacked ? materializedView.copyRoot() : null;
        ResolvedSnapshot snapshotRollback = snapshot;
        batchPatchCalls++;
        batchPatchEntries += patches.size();
        if (patches.size() == 1) {
            singletonPatchTransactions++;
            metrics.incrementSingletonPatchTransactions();
        }
        try {
            PlanningContext planning = planningContext(materializedView.root());
            BatchPatchTransaction transaction = BatchPatchTransaction.fromInputs(originScopePath,
                    patches,
                    planning,
                    currentConformanceEngine(),
                    conformancePlannerOverride,
                    updateMaterializationMetrics(),
                    !usesAuthoritativeSelectedSnapshot(),
                    metrics);
            BatchPatchResult result = transaction.apply();
            batchPatchPlanningNanos += result.patchPlanningNanos();
            batchPatchConformanceNanos += result.conformanceNanos();
            batchPatchBuildUpdatesNanos += result.buildUpdatesNanos();
            metrics.addBatchPatchPlanningNanos(result.patchPlanningNanos());
            metrics.addBatchPatchConformanceNanos(result.conformanceNanos());
            metrics.addBatchPatchBuildUpdatesNanos(result.buildUpdatesNanos());
            long commitStart = System.nanoTime();
            List<DocumentUpdateData> updates;
            try {
                updates = commitBatchPatchResult(result);
            } finally {
                long commitNanos = System.nanoTime() - commitStart;
                batchPatchCommitNanos += commitNanos;
                metrics.addBatchPatchCommitNanos(commitNanos);
                metrics.addSnapshotCommitNanos(commitNanos);
            }
            return updates;
        } catch (RuntimeException ex) {
            snapshot = snapshotRollback;
            if (selectedRollback != null) {
                materializedView.replaceWith(selectedRollback);
                materializedViewStale = false;
            } else if (snapshotRollback != null) {
                materializedView.replaceWithSnapshot(snapshotRollback);
                materializedViewStale = false;
            }
            throw ex;
        }
    }

    List<DocumentUpdateData> applyPrecomputedPatch(String originScopePath,
                                                   JsonPatch patch,
                                                   WorkingDocument.PatchPreview preview) {
        if (patch == null) {
            return Collections.emptyList();
        }
        if (!canApplyPrecomputedPatch(originScopePath, patch, preview)) {
            return applyPatches(originScopePath, Collections.singletonList(patch));
        }
        Node selectedRollback = selectedDocumentBacked ? materializedView.copyRoot() : null;
        ResolvedSnapshot snapshotRollback = snapshot;
        batchPatchCalls++;
        batchPatchEntries++;
        try {
            long buildUpdatesStart = System.nanoTime();
            BatchPatchResult result;
            try {
                result = usesAuthoritativeSelectedSnapshot()
                        ? preview.result()
                        : preview.result().withMaterializationMetrics(updateMaterializationMetrics());
            } finally {
                long buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
                batchPatchBuildUpdatesNanos += buildUpdatesNanos;
                metrics.addBatchPatchBuildUpdatesNanos(buildUpdatesNanos);
            }
            long commitStart = System.nanoTime();
            List<DocumentUpdateData> updates;
            try {
                updates = commitBatchPatchResult(result);
            } finally {
                long commitNanos = System.nanoTime() - commitStart;
                batchPatchCommitNanos += commitNanos;
                metrics.addBatchPatchCommitNanos(commitNanos);
                metrics.addSnapshotCommitNanos(commitNanos);
            }
            return updates;
        } catch (RuntimeException ex) {
            snapshot = snapshotRollback;
            if (selectedRollback != null) {
                materializedView.replaceWith(selectedRollback);
                materializedViewStale = false;
            } else if (snapshotRollback != null) {
                materializedView.replaceWithSnapshot(snapshotRollback);
                materializedViewStale = false;
            }
            throw ex;
        }
    }

    PreparedPatchSequence preparePatchSequence(String originScopePath,
                                               List<JsonPatch> patches,
                                               WorkingDocument.Preview preview) {
        return new PreparedPatchSequence(originScopePath, PatchInput.mutableList(patches), preview);
    }

    PreparedPatchSequence prepareFrozenPatchSequence(String originScopePath,
                                                     List<FrozenJsonPatch> patches,
                                                     WorkingDocument.Preview preview) {
        return new PreparedPatchSequence(originScopePath, PatchInput.frozenList(patches), preview);
    }

    PreparedPatchSequence preparePatchInputSequence(String originScopePath,
                                                    List<PatchInput> patches,
                                                    WorkingDocument.Preview preview) {
        return new PreparedPatchSequence(originScopePath, patches, preview);
    }

    private boolean canApplyPrecomputedPatch(String originScopePath,
                                             JsonPatch patch,
                                             WorkingDocument.PatchPreview preview) {
        if (preview == null
                || !PointerUtils.normalizeScope(originScopePath).equals(preview.originScope())
                || !preview.matches(patch)) {
            return false;
        }
        ResolvedSnapshot current = snapshot();
        return current != null
                && preview.isBasedOn(current.frozenCanonicalRoot(), current.frozenResolvedRoot());
    }

    private UpdateMaterializationMetrics updateMaterializationMetrics() {
        return new UpdateMaterializationMetrics() {
            @Override
            public void recordBeforeNodeMaterialization() {
                documentUpdateBeforeNodeMaterializations++;
                metrics.incrementDocumentUpdateBeforeMaterializations();
            }

            @Override
            public void recordAfterNodeMaterialization() {
                documentUpdateAfterNodeMaterializations++;
                metrics.incrementDocumentUpdateAfterMaterializations();
            }
        };
    }

    private JsonPatch directWritePatch(String path, Node before, Node value) {
        if (value == null) {
            return before == null ? null : JsonPatch.remove(path);
        }
        return before == null
                ? JsonPatch.add(path, value.clone())
                : JsonPatch.replace(path, value.clone());
    }

    private PlanningContext planningContext(Node rollback) {
        ProcessingSnapshotManager currentManager = currentSnapshotManager();
        if (currentManager == null || canPlanFromSelectedWithoutSnapshot()) {
            ImmutablePatchPlanner planner = ImmutablePatchPlanner.forMaterialized(rollback);
            return new PlanningContext(null, planner, planner, false, null);
        }
        ResolvedSnapshot base = snapshot != null ? snapshot : snapshotFromDocument(rollback);
        return new PlanningContext(base,
                ImmutablePatchPlanner.forSnapshot(base),
                ImmutablePatchPlanner.forFrozen(base.frozenResolvedRoot()),
                !selectedDocumentBacked,
                !selectedDocumentBacked ? currentManager : null);
    }

    private boolean canPlanFromSelectedWithoutSnapshot() {
        return usesAuthoritativeSelectedSnapshot()
                && snapshot == null
                && conformanceEngine == null
                && (conformancePlannerOverride == null || !conformancePlannerOverride.applies());
    }

    private boolean usesAuthoritativeSelectedSnapshot() {
        return selectedDocumentBacked && snapshotManager != null;
    }

    static PlanningContext workingPlanningContext(FrozenNode canonicalRoot,
                                                  FrozenNode resolvedRoot,
                                                  boolean exactReplacement,
                                                  ProcessingSnapshotManager snapshotManager) {
        return new PlanningContext(null,
                ImmutablePatchPlanner.forFrozen(canonicalRoot),
                ImmutablePatchPlanner.forFrozen(resolvedRoot),
                exactReplacement,
                exactReplacement ? snapshotManager : null);
    }

    private SnapshotPatchPlan prepareSnapshotPatch(ResolvedSnapshot base, JsonPatch patch) {
        ProcessingSnapshotManager currentManager = currentSnapshotManager();
        if (currentManager == null || base == null) {
            return null;
        }
        try {
            return new SnapshotPatchPlan(currentManager.applyPatch(base, patch));
        } catch (RuntimeException ex) {
            return new SnapshotPatchPlan(null);
        }
    }

    private void commitSnapshotPatch(SnapshotPatchPlan plan, FrozenNode fallbackRoot) {
        if (snapshotManager == null || plan == null) {
            materializedView.replaceWith(fallbackRoot.toNode());
            materializedViewStale = false;
            markStateAdvanced(false);
            return;
        }
        if (plan.next != null) {
            snapshot = plan.next;
            commitMaterializedSnapshot(snapshot);
            markStateAdvanced(false);
        } else {
            snapshot = snapshotFromDocument(fallbackRoot.toNode());
            commitMaterializedSnapshot(snapshot);
            markStateAdvanced(false);
        }
    }

    private List<DocumentUpdateData> commitBatchPatchResult(BatchPatchResult result) {
        return commitBatchPatchResult(result, true, currentSnapshotManager());
    }

    private List<DocumentUpdateData> commitBatchPatchResult(BatchPatchResult result,
                                                            boolean insertSharedSnapshot) {
        return commitBatchPatchResult(result, insertSharedSnapshot, snapshotManager);
    }

    private List<DocumentUpdateData> commitBatchPatchResult(BatchPatchResult result,
                                                            boolean insertSharedSnapshot,
                                                            ProcessingSnapshotManager commitSnapshotManager) {
        if (commitSnapshotManager == null) {
            Node next = result.resolvedRoot().toNode();
            materializedView.replaceWith(next);
            snapshot = null;
            materializedViewStale = false;
            markStateAdvanced(false);
            return result.updates();
        }
        if (selectedDocumentBacked) {
            Node tentativeSelected = tentativeSelectedRoot(result);
            ResolvedSnapshot authoritative = snapshotFromDocument(
                    tentativeSelected, true, commitSnapshotManager);
            long buildUpdatesStart = System.nanoTime();
            List<DocumentUpdateData> updates;
            try {
                updates = result.updatesAgainst(authoritative.frozenResolvedRoot(),
                        updateMaterializationMetrics());
            } finally {
                long buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
                batchPatchBuildUpdatesNanos += buildUpdatesNanos;
                metrics.addBatchPatchBuildUpdatesNanos(buildUpdatesNanos);
            }
            ResolvedSnapshot committed = insertSharedSnapshot
                    ? Objects.requireNonNull(commitSnapshotManager.cacheSnapshot(authoritative), "cachedSnapshot")
                    : authoritative;
            materializedView.replaceWith(tentativeSelected);
            snapshot = committed;
            materializedViewStale = false;
            markStateAdvanced(insertSharedSnapshot);
            return updates;
        }
        ResolvedSnapshot next = insertSharedSnapshot
                ? new ResolvedSnapshot(result.canonicalRoot(),
                result.resolvedRoot(),
                result.canonicalRoot().blueId())
                : new ResolvedSnapshot(result.canonicalRoot(), result.resolvedRoot());
        ResolvedSnapshot committed = insertSharedSnapshot
                ? commitSnapshotManager.cacheSnapshot(next)
                : next;
        snapshot = committed;
        commitMaterializedSnapshot(committed);
        markStateAdvanced(insertSharedSnapshot);
        return result.updates();
    }

    private Node tentativeSelectedRoot(BatchPatchResult result) {
        FrozenNode tentative = FrozenNode.fromResolvedNode(materializedView.copyRoot());
        for (ImmutableJsonPatch patch : result.requestedPatches()) {
            tentative = ImmutablePatchPlanner.forFrozen(tentative).plan("/", patch).root();
        }
        Node tentativeSelected = tentative.toNode();
        for (BatchPatchResult.GeneralizationMetadataWrite write : result.generalizationMetadataWrites()) {
            NodePathEditor.put(tentativeSelected, write.path(), write.value().toNode());
        }
        return tentativeSelected;
    }

    private void commitMaterializedSnapshot(ResolvedSnapshot committed) {
        if (lazyMaterializedCommits) {
            materializedViewStale = true;
            return;
        }
        materializedView.replaceWithSnapshot(committed);
        materializedViewStale = false;
    }

    private void syncMaterializedView() {
        if (materializedViewStale && snapshot != null) {
            materializedView.replaceWithSnapshot(snapshot);
            materializedViewStale = false;
        }
    }

    private ResolvedSnapshot snapshotFromDocument(Node document) {
        return snapshotFromDocument(document, false);
    }

    private ResolvedSnapshot snapshotFromDocumentTransient(Node document) {
        return snapshotFromDocument(document, true);
    }

    private ResolvedSnapshot snapshotFromDocument(Node document, boolean transientResolution) {
        return snapshotFromDocument(document, transientResolution, currentSnapshotManager());
    }

    private ProcessingSnapshotManager currentSnapshotManager() {
        return activeSequenceSnapshotManager != null
                ? activeSequenceSnapshotManager
                : snapshotManager;
    }

    private ConformanceEngine currentConformanceEngine() {
        return activeSequenceSnapshotManager != null
                ? activeSequenceSnapshotManager.transientConformanceEngine(conformanceEngine)
                : conformanceEngine;
    }

    private ResolvedSnapshot snapshotFromDocument(Node document,
                                                  boolean transientResolution,
                                                  ProcessingSnapshotManager manager) {
        long start = System.nanoTime();
        try {
            return transientResolution
                    ? manager.fromDocumentTransient(document)
                    : manager.fromDocument(document);
        } finally {
            metrics.incrementProcessingSnapshotFromDocumentBuilds();
            metrics.addProcessingSnapshotFromDocumentNanos(System.nanoTime() - start);
        }
    }

    private void markStateAdvanced(boolean sharedSnapshotInserted) {
        stateVersion++;
        if (sharedSnapshotInserted) {
            sharedSnapshotVersion = stateVersion;
        }
    }

    private void promoteCurrentSequenceSnapshot(ProcessingSnapshotManager manager) {
        if (manager == null || snapshot == null || sharedSnapshotVersion == stateVersion) {
            return;
        }
        long start = System.nanoTime();
        ResolvedSnapshot cached = Objects.requireNonNull(manager.cacheSnapshot(snapshot),
                "cachedSnapshot");
        snapshot = cached;
        sharedSnapshotVersion = stateVersion;
        if (!selectedDocumentBacked) {
            commitMaterializedSnapshot(cached);
        }
        sequenceSharedSnapshotCacheInserts++;
        sequenceFinalSnapshotCacheInserts++;
        metrics.incrementSequenceSharedSnapshotCacheInserts();
        metrics.incrementSequenceFinalSnapshotCacheInserts();
        metrics.addSequenceFinalCacheCommitNanos(System.nanoTime() - start);
    }

    long batchPatchCallsForTest() {
        return batchPatchCalls;
    }

    long batchPatchEntriesForTest() {
        return batchPatchEntries;
    }

    long batchPatchPlanningNanosForTest() {
        return batchPatchPlanningNanos;
    }

    long batchPatchConformanceNanosForTest() {
        return batchPatchConformanceNanos;
    }

    long batchPatchBuildUpdatesNanosForTest() {
        return batchPatchBuildUpdatesNanos;
    }

    long batchPatchCommitNanosForTest() {
        return batchPatchCommitNanos;
    }

    long batchPatchRollbackCopiesForTest() {
        return batchPatchRollbackCopies;
    }

    long documentUpdateBeforeNodeMaterializationsForTest() {
        return documentUpdateBeforeNodeMaterializations;
    }

    long documentUpdateAfterNodeMaterializationsForTest() {
        return documentUpdateAfterNodeMaterializations;
    }

    long patchSequencesPreparedForTest() {
        return patchSequencesPrepared;
    }

    long singletonPatchTransactionsForTest() {
        return singletonPatchTransactions;
    }

    long sequenceIntermediateSnapshotAdvancesForTest() {
        return sequenceIntermediateSnapshotAdvances;
    }

    long sequenceSharedSnapshotCacheInsertsForTest() {
        return sequenceSharedSnapshotCacheInserts;
    }

    long sequenceFinalSnapshotCacheInsertsForTest() {
        return sequenceFinalSnapshotCacheInserts;
    }

    long sequenceSuffixRebasesForTest() {
        return sequenceSuffixRebases;
    }

    long sequenceStalePreviewFallbacksForTest() {
        return sequenceStalePreviewFallbacks;
    }

    long sequenceFallbackPatchesForTest() {
        return sequenceFallbackPatches;
    }

    final class PreparedPatchSequence implements AutoCloseable {
        private final String originScope;
        private final int patchCount;
        private final WorkingDocument.Preview preview;
        private final List<PatchInput> patches;
        private ProcessingSnapshotManager sequenceSnapshotManager;
        private ProcessingSnapshotManager previousActiveSequenceSnapshotManager;
        private boolean sequenceSnapshotManagerActivated;
        private SequentialPatchPlanningSession planningSession;
        private FrozenNode observedCanonical;
        private FrozenNode observedResolved;
        private long observedVersion = Long.MIN_VALUE;
        private boolean advanced;
        private boolean closed;
        private boolean counted;

        private PreparedPatchSequence(String originScope,
                                      List<PatchInput> requestedPatches,
                                      WorkingDocument.Preview preview) {
            this.originScope = PointerUtils.normalizeScope(originScope);
            this.preview = preview;
            List<PatchInput> checkedPatches = Objects.requireNonNull(requestedPatches, "patches");
            this.patches = new ArrayList<>(checkedPatches);
            this.patchCount = this.patches.size();
        }

        int size() {
            return patchCount;
        }

        JsonPatch patchForValidation(int patchIndex) {
            return patchAt(patchIndex).legacyPatch();
        }

        PatchInput patchInputForValidation(int patchIndex) {
            return patchAt(patchIndex);
        }

        List<DocumentUpdateData> applyNext(int patchIndex) {
            if (closed) {
                throw new IllegalStateException("Patch sequence is already closed");
            }
            PatchInput authoredPatch = patchAt(patchIndex);
            if (!counted) {
                patchSequencesPrepared++;
                batchPatchCalls++;
                counted = true;
            }
            SequenceRoots actual = currentRoots();
            refreshInvalidSequenceSnapshotManager();
            if (planningSession == null) {
                planningSession = newPlanningSession(actual, patchIndex);
            }
            ImmutableJsonPatch patch = planningSession.preparePatch(
                    authoredPatch, actual.canonical, actual.resolved);
            WorkingDocument.PatchPreview prepared = preview != null ? preview.patch(patchIndex) : null;
            BatchPatchResult result = null;
            boolean plannedNow = false;
            if (prepared != null
                    && preview.isResolutionScopeCurrent()
                    && originScope.equals(prepared.originScope())
                    && prepared.matches(patch)
                    && prepared.isBasedOn(actual.canonical, actual.resolved)) {
                result = prepared.result();
            } else {
                if (preview != null) {
                    preview.discardFrom(patchIndex);
                    sequenceStalePreviewFallbacks++;
                    metrics.incrementSequenceStalePreviewFallbacks();
                }
                if (!planningSession.isBasedOn(actual.canonical, actual.resolved)) {
                    planningSession.rebase(actual.canonical, actual.resolved);
                    sequenceSuffixRebases++;
                    metrics.incrementSequenceSuffixRebases();
                }
                result = planningSession.planNext(patch).result();
                plannedNow = true;
            }
            if (preview != null) {
                preview.release(patchIndex);
            }

            if (plannedNow) {
                batchPatchPlanningNanos += result.patchPlanningNanos();
                batchPatchConformanceNanos += result.conformanceNanos();
            }
            batchPatchEntries++;

            long buildUpdatesStart = System.nanoTime();
            BatchPatchResult commitResult;
            try {
                commitResult = usesAuthoritativeSelectedSnapshot()
                        ? result
                        : result.withMaterializationMetrics(updateMaterializationMetrics());
            } finally {
                long buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
                batchPatchBuildUpdatesNanos += buildUpdatesNanos;
                metrics.addBatchPatchBuildUpdatesNanos(buildUpdatesNanos);
            }

            Node selectedRollback = selectedDocumentBacked ? materializedView.copyRoot() : null;
            ResolvedSnapshot snapshotRollback = snapshot;
            boolean staleRollback = materializedViewStale;
            long versionRollback = stateVersion;
            long sharedVersionRollback = sharedSnapshotVersion;
            boolean finalRequestedPatch = patchIndex == patchCount - 1;
            boolean insertSharedSnapshot = snapshotManager != null && finalRequestedPatch;
            long commitStart = System.nanoTime();
            try {
                List<DocumentUpdateData> updates =
                        commitBatchPatchResult(commitResult,
                                insertSharedSnapshot,
                                sequenceSnapshotManager());
                advanced = true;
                if (insertSharedSnapshot) {
                    sequenceSharedSnapshotCacheInserts++;
                    sequenceFinalSnapshotCacheInserts++;
                    metrics.incrementSequenceSharedSnapshotCacheInserts();
                    metrics.incrementSequenceFinalSnapshotCacheInserts();
                } else {
                    sequenceIntermediateSnapshotAdvances++;
                    metrics.incrementSequenceIntermediateSnapshotAdvances();
                }
                rememberCurrentRoots(commitResult);
                patches.set(patchIndex, null);
                return updates;
            } catch (RuntimeException ex) {
                snapshot = snapshotRollback;
                materializedViewStale = staleRollback;
                stateVersion = versionRollback;
                sharedSnapshotVersion = sharedVersionRollback;
                if (selectedRollback != null) {
                    materializedView.replaceWith(selectedRollback);
                    materializedViewStale = false;
                }
                throw ex;
            } finally {
                long commitNanos = System.nanoTime() - commitStart;
                batchPatchCommitNanos += commitNanos;
                metrics.addBatchPatchCommitNanos(commitNanos);
                metrics.addSequenceCommitNanos(commitNanos);
                metrics.addSnapshotCommitNanos(commitNanos);
                if (insertSharedSnapshot) {
                    metrics.addSequenceFinalCacheCommitNanos(commitNanos);
                }
            }
        }

        private PatchInput patchAt(int patchIndex) {
            if (patchIndex < 0 || patchIndex >= patchCount) {
                throw new IndexOutOfBoundsException("Patch index outside prepared sequence: " + patchIndex);
            }
            PatchInput patch = patches.get(patchIndex);
            if (patch == null) {
                throw new IllegalStateException("Patch was already consumed: " + patchIndex);
            }
            return patch;
        }

        private SequentialPatchPlanningSession newPlanningSession(SequenceRoots roots,
                                                                  int patchIndex) {
            ProcessingSnapshotManager sequenceManager = sequenceSnapshotManager(roots, patchIndex);
            ConformanceEngine sequenceConformanceEngine = sequenceManager != null
                    ? sequenceManager.transientConformanceEngine(conformanceEngine)
                    : conformanceEngine != null ? conformanceEngine.transientView() : null;
            DocumentProcessingRuntime.PlanningContext planning = workingPlanningContext(
                    roots.canonical,
                    roots.resolved,
                    !selectedDocumentBacked,
                    sequenceManager);
            return new SequentialPatchPlanningSession(originScope,
                    planning,
                    sequenceConformanceEngine,
                    conformancePlannerOverride,
                    updateMaterializationMetrics(),
                    metrics);
        }

        private ProcessingSnapshotManager sequenceSnapshotManager() {
            if (sequenceSnapshotManager == null && snapshotManager != null) {
                sequenceSnapshotManager = currentSnapshotManager().transientSequence();
                activateSequenceSnapshotManager();
            }
            return sequenceSnapshotManager;
        }

        private ProcessingSnapshotManager sequenceSnapshotManager(SequenceRoots roots,
                                                                  int patchIndex) {
            if (sequenceSnapshotManager != null || snapshotManager == null) {
                return sequenceSnapshotManager;
            }
            WorkingDocument.PatchPreview prepared = preview != null
                    ? preview.patch(patchIndex)
                    : null;
            if (prepared != null
                    && preview.isResolutionScopeCurrent()
                    && originScope.equals(prepared.originScope())
                    && prepared.matches(patchAt(patchIndex))
                    && prepared.isBasedOn(roots.canonical, roots.resolved)) {
                sequenceSnapshotManager = preview.takeSequenceSnapshotManager();
            }
            if (sequenceSnapshotManager == null) {
                sequenceSnapshotManager = currentSnapshotManager().transientSequence();
            }
            activateSequenceSnapshotManager();
            return sequenceSnapshotManager;
        }

        private void activateSequenceSnapshotManager() {
            if (sequenceSnapshotManager == null
                    || activeSequenceSnapshotManager == sequenceSnapshotManager) {
                return;
            }
            previousActiveSequenceSnapshotManager = activeSequenceSnapshotManager;
            activeSequenceSnapshotManager = sequenceSnapshotManager;
            sequenceSnapshotManagerActivated = true;
        }

        private void refreshInvalidSequenceSnapshotManager() {
            if (sequenceSnapshotManager == null
                    || sequenceSnapshotManager.isTransientStateCurrent()) {
                return;
            }
            ProcessingSnapshotManager invalid = sequenceSnapshotManager;
            deactivateSequenceSnapshotManager();
            closePlanningSession();
            sequenceSnapshotManager = null;
            invalid.releaseTransientState();
            sequenceSnapshotManager = snapshotManager != null
                    ? snapshotManager.transientSequence()
                    : null;
            planningSession = null;
            activateSequenceSnapshotManager();
        }

        private void deactivateSequenceSnapshotManager() {
            if (sequenceSnapshotManagerActivated
                    && activeSequenceSnapshotManager == sequenceSnapshotManager) {
                activeSequenceSnapshotManager = previousActiveSequenceSnapshotManager;
            }
            previousActiveSequenceSnapshotManager = null;
            sequenceSnapshotManagerActivated = false;
        }

        private SequenceRoots currentRoots() {
            if (observedVersion == stateVersion
                    && observedCanonical != null
                    && observedResolved != null) {
                return new SequenceRoots(observedCanonical, observedResolved);
            }
            ResolvedSnapshot current = snapshot;
            if (current != null) {
                observedCanonical = current.frozenCanonicalRoot();
                observedResolved = current.frozenResolvedRoot();
            } else {
                PlanningContext planning = planningContext(materializedView.root());
                observedCanonical = planning.canonicalPlanner().root();
                observedResolved = planning.resolvedPlanner().root();
            }
            observedVersion = stateVersion;
            return new SequenceRoots(observedCanonical, observedResolved);
        }

        private void rememberCurrentRoots(BatchPatchResult result) {
            if (snapshot != null) {
                observedCanonical = snapshot.frozenCanonicalRoot();
                observedResolved = snapshot.frozenResolvedRoot();
            } else {
                observedCanonical = result.canonicalRoot();
                observedResolved = result.resolvedRoot();
            }
            observedVersion = stateVersion;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (preview != null) {
                preview.discardFrom(0);
            }
            for (int index = 0; index < patches.size(); index++) {
                patches.set(index, null);
            }
            try {
                if (advanced) {
                    ProcessingSnapshotManager manager = sequenceSnapshotManager();
                    if (manager == null || manager.isTransientStateCurrent()) {
                        promoteCurrentSequenceSnapshot(manager);
                    }
                }
            } catch (RuntimeException | Error ex) {
                ProcessingSnapshotManager failedManager = sequenceSnapshotManager;
                deactivateSequenceSnapshotManager();
                sequenceSnapshotManager = null;
                try {
                    closePlanningSession();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (ex != cleanupFailure) {
                        ex.addSuppressed(cleanupFailure);
                    }
                }
                if (failedManager != null) {
                    try {
                        failedManager.releaseTransientState();
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (ex != cleanupFailure) {
                            ex.addSuppressed(cleanupFailure);
                        }
                    }
                }
                throw ex;
            }
            ProcessingSnapshotManager managerToRelease = sequenceSnapshotManager;
            deactivateSequenceSnapshotManager();
            closePlanningSession();
            sequenceSnapshotManager = null;
            observedCanonical = null;
            observedResolved = null;
            closed = true;
            if (managerToRelease != null) {
                managerToRelease.releaseTransientState();
            }
        }

        private void closePlanningSession() {
            if (planningSession != null) {
                planningSession.close();
                planningSession = null;
            }
        }
    }

    private static final class SequenceRoots {
        private final FrozenNode canonical;
        private final FrozenNode resolved;

        private SequenceRoots(FrozenNode canonical, FrozenNode resolved) {
            this.canonical = Objects.requireNonNull(canonical, "canonical");
            this.resolved = Objects.requireNonNull(resolved, "resolved");
        }
    }

    interface UpdateMaterializationMetrics {
        void recordBeforeNodeMaterialization();

        void recordAfterNodeMaterialization();
    }

    static final class DocumentUpdateData {
        private final String path;
        private final FrozenNode beforeFrozen;
        private final FrozenNode afterFrozen;
        private Node before;
        private Node after;
        private final JsonPatch.Op op;
        private final String originScope;
        private final List<String> cascadeScopes;
        private final UpdateMaterializationMetrics materializationMetrics;

        DocumentUpdateData(String path,
                           Node before,
                           Node after,
                           JsonPatch.Op op,
                           String originScope,
                           List<String> cascadeScopes) {
            this.path = path;
            this.beforeFrozen = null;
            this.afterFrozen = null;
            this.before = before;
            this.after = after;
            this.op = op;
            this.originScope = originScope;
            this.cascadeScopes = cascadeScopes;
            this.materializationMetrics = null;
        }

        DocumentUpdateData(String path,
                           FrozenNode beforeFrozen,
                           FrozenNode afterFrozen,
                           JsonPatch.Op op,
                           String originScope,
                           List<String> cascadeScopes,
                           UpdateMaterializationMetrics materializationMetrics) {
            this.path = path;
            this.beforeFrozen = beforeFrozen;
            this.afterFrozen = afterFrozen;
            this.op = op;
            this.originScope = originScope;
            this.cascadeScopes = cascadeScopes;
            this.materializationMetrics = materializationMetrics;
        }

        String path() {
            return path;
        }

        Node before() {
            if (before == null && beforeFrozen != null) {
                before = beforeFrozen.toNode();
                if (materializationMetrics != null) {
                    materializationMetrics.recordBeforeNodeMaterialization();
                }
            }
            return before;
        }

        Node after() {
            if (op == JsonPatch.Op.REMOVE) {
                return null;
            }
            if (after == null && afterFrozen != null) {
                after = afterFrozen.toNode();
                if (materializationMetrics != null) {
                    materializationMetrics.recordAfterNodeMaterialization();
                }
            }
            return after;
        }

        JsonPatch.Op op() {
            return op;
        }

        DocumentUpdateData withMaterializationMetrics(UpdateMaterializationMetrics materializationMetrics) {
            if (beforeFrozen != null || afterFrozen != null) {
                return new DocumentUpdateData(path,
                        beforeFrozen,
                        afterFrozen,
                        op,
                        originScope,
                        cascadeScopes,
                        materializationMetrics);
            }
            return new DocumentUpdateData(path,
                    before != null ? before.clone() : null,
                    after != null ? after.clone() : null,
                    op,
                    originScope,
                    cascadeScopes);
        }

        String originScope() {
            return originScope;
        }

        List<String> cascadeScopes() {
            return cascadeScopes;
        }
    }

    static final class PlanningContext {
        private final ResolvedSnapshot baseSnapshot;
        private final ImmutablePatchPlanner canonicalPlanner;
        private final ImmutablePatchPlanner resolvedPlanner;
        private final boolean exactReplacement;
        private final ProcessingSnapshotManager authoritativeSnapshotManager;

        private PlanningContext(ResolvedSnapshot baseSnapshot,
                                ImmutablePatchPlanner canonicalPlanner,
                                ImmutablePatchPlanner resolvedPlanner,
                                boolean exactReplacement,
                                ProcessingSnapshotManager authoritativeSnapshotManager) {
            this.baseSnapshot = baseSnapshot;
            this.canonicalPlanner = canonicalPlanner;
            this.resolvedPlanner = resolvedPlanner;
            this.exactReplacement = exactReplacement;
            this.authoritativeSnapshotManager = authoritativeSnapshotManager;
        }

        ResolvedSnapshot baseSnapshot() {
            return baseSnapshot;
        }

        ImmutablePatchPlanner canonicalPlanner() {
            return canonicalPlanner;
        }

        ImmutablePatchPlanner resolvedPlanner() {
            return resolvedPlanner;
        }

        boolean exactReplacement() {
            return exactReplacement;
        }

        ProcessingSnapshotManager authoritativeSnapshotManager() {
            return authoritativeSnapshotManager;
        }

        ResolvedSnapshot resolveCanonical(FrozenNode canonicalRoot) {
            if (!exactReplacement || authoritativeSnapshotManager == null) {
                throw new IllegalStateException("Authoritative snapshot resolution is unavailable");
            }
            return authoritativeSnapshotManager.fromDocumentTransient(canonicalRoot.toNode());
        }
    }

    private static final class SnapshotPatchPlan {
        private final ResolvedSnapshot next;

        private SnapshotPatchPlan(ResolvedSnapshot next) {
            this.next = next;
        }
    }
}
