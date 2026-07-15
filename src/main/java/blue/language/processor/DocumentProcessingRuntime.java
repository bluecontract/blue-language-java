package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
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
            materializedFallback = true;
        }
        if (current != null) {
            return new WorkingDocument(normalizedScope,
                    current.frozenCanonicalRoot(),
                    current.frozenResolvedRoot(),
                    conformanceEngine,
                    conformancePlannerOverride,
                    snapshotManager,
                    current,
                    materializedFallback,
                    !selectedDocumentBacked);
        }

        Node root = materializedView.copyRoot();
        FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(new MergeReverser().reverse(root.clone()));
        FrozenNode resolved = FrozenNode.fromResolvedNode(root.clone());
        return new WorkingDocument(normalizedScope,
                canonical,
                resolved,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                null,
                true,
                false);
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
            ResolvedSnapshot cached = Objects.requireNonNull(snapshotManager.cacheSnapshot(authoritative),
                    "cachedSnapshot");
            materializedView.replaceWith(tentativeSelected);
            snapshot = cached;
            materializedViewStale = false;
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
            snapshot = snapshotManager.cacheSnapshot(next);
            commitMaterializedSnapshot(snapshot);
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
        Node selectedRollback = selectedDocumentBacked ? materializedView.copyRoot() : null;
        ResolvedSnapshot snapshotRollback = snapshot;
        batchPatchCalls++;
        batchPatchEntries += patches.size();
        try {
            PlanningContext planning = planningContext(materializedView.root());
            BatchPatchTransaction transaction = new BatchPatchTransaction(originScopePath,
                    patches,
                    planning,
                    conformanceEngine,
                    conformancePlannerOverride,
                    updateMaterializationMetrics(),
                    !usesAuthoritativeSelectedSnapshot());
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
                && current.frozenCanonicalRoot().blueId().equals(preview.baseCanonical().blueId())
                && current.frozenResolvedRoot().blueId().equals(preview.baseResolved().blueId());
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
        if (snapshotManager == null || canPlanFromSelectedWithoutSnapshot()) {
            ImmutablePatchPlanner planner = ImmutablePatchPlanner.forMaterialized(rollback);
            return new PlanningContext(null, planner, planner, false, null);
        }
        ResolvedSnapshot base = snapshot != null ? snapshot : snapshotFromDocument(rollback);
        return new PlanningContext(base,
                ImmutablePatchPlanner.forSnapshot(base),
                ImmutablePatchPlanner.forFrozen(base.frozenResolvedRoot()),
                !selectedDocumentBacked,
                !selectedDocumentBacked ? snapshotManager : null);
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
        if (snapshotManager == null || base == null) {
            return null;
        }
        try {
            return new SnapshotPatchPlan(snapshotManager.applyPatch(base, patch));
        } catch (RuntimeException ex) {
            return new SnapshotPatchPlan(null);
        }
    }

    private void commitSnapshotPatch(SnapshotPatchPlan plan, FrozenNode fallbackRoot) {
        if (snapshotManager == null || plan == null) {
            materializedView.replaceWith(fallbackRoot.toNode());
            materializedViewStale = false;
            return;
        }
        if (plan.next != null) {
            snapshot = plan.next;
            commitMaterializedSnapshot(snapshot);
        } else {
            snapshot = snapshotFromDocument(fallbackRoot.toNode());
            commitMaterializedSnapshot(snapshot);
        }
    }

    private List<DocumentUpdateData> commitBatchPatchResult(BatchPatchResult result) {
        if (snapshotManager == null) {
            Node next = result.resolvedRoot().toNode();
            materializedView.replaceWith(next);
            snapshot = null;
            materializedViewStale = false;
            return result.updates();
        }
        if (selectedDocumentBacked) {
            Node tentativeSelected = tentativeSelectedRoot(result);
            ResolvedSnapshot authoritative = snapshotFromDocument(tentativeSelected);
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
            ResolvedSnapshot cached = Objects.requireNonNull(snapshotManager.cacheSnapshot(authoritative),
                    "cachedSnapshot");
            materializedView.replaceWith(tentativeSelected);
            snapshot = cached;
            materializedViewStale = false;
            return updates;
        }
        ResolvedSnapshot next = new ResolvedSnapshot(result.canonicalRoot(),
                result.resolvedRoot(),
                result.canonicalRoot().blueId());
        ResolvedSnapshot cached = snapshotManager.cacheSnapshot(next);
        snapshot = cached;
        commitMaterializedSnapshot(cached);
        return result.updates();
    }

    private Node tentativeSelectedRoot(BatchPatchResult result) {
        FrozenNode tentative = FrozenNode.fromResolvedNode(materializedView.copyRoot());
        for (JsonPatch patch : result.requestedPatches()) {
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
        long start = System.nanoTime();
        try {
            return snapshotManager.fromDocument(document);
        } finally {
            metrics.incrementProcessingSnapshotFromDocumentBuilds();
            metrics.addProcessingSnapshotFromDocumentNanos(System.nanoTime() - start);
        }
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

        ResolvedSnapshot resolveCanonical(FrozenNode canonicalRoot) {
            if (!exactReplacement || authoritativeSnapshotManager == null) {
                throw new IllegalStateException("Authoritative snapshot resolution is unavailable");
            }
            return authoritativeSnapshotManager.fromDocument(canonicalRoot.toNode());
        }
    }

    private static final class SnapshotPatchPlan {
        private final ResolvedSnapshot next;

        private SnapshotPatchPlan(ResolvedSnapshot next) {
            this.next = next;
        }
    }
}
