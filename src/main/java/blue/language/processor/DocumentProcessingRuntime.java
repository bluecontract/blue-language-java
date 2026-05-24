package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
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
    private final ProcessingSnapshotManager snapshotManager;
    private final ProcessingMetricsSink metrics;
    private ResolvedSnapshot snapshot;
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
        this.materializedView = new MaterializedDocumentView(Objects.requireNonNull(document, "document"));
        this.emissionRegistry = new EmissionRegistry();
        this.gasMeter = new GasMeter();
        this.conformanceEngine = conformanceEngine;
        this.snapshotManager = snapshotManager;
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
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
        ResolvedSnapshot processorSnapshot = processorSnapshot(Objects.requireNonNull(snapshot, "snapshot"));
        this.materializedView = new MaterializedDocumentView(processorSnapshot.canonicalRoot());
        this.emissionRegistry = new EmissionRegistry();
        this.gasMeter = new GasMeter();
        this.conformanceEngine = conformanceEngine;
        this.snapshotManager = snapshotManager;
        this.snapshot = processorSnapshot;
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
    }

    private ResolvedSnapshot processorSnapshot(ResolvedSnapshot snapshot) {
        if (!snapshot.frozenCanonicalRoot().isStrictBlueIdValidation()) {
            return snapshot;
        }
        FrozenNode canonicalRoot = FrozenNode.fromUncheckedCanonicalNode(snapshot.canonicalRoot());
        return new ResolvedSnapshot(canonicalRoot, snapshot.frozenResolvedRoot(), canonicalRoot.blueId());
    }

    public Node document() {
        return materializedView.root();
    }

    public Map<String, ScopeRuntimeContext> scopes() {
        return emissionRegistry.scopes();
    }

    public ScopeRuntimeContext scope(String scopePath) {
        return emissionRegistry.scope(scopePath);
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
        gasMeter.chargeScopeEntry(scopePath);
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
            snapshot = snapshotManager.fromDocument(materializedView.root());
            materializedView.replaceWithSnapshot(snapshot);
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

    public Node nodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        if (snapshot != null) {
            Node resolved = snapshot.resolvedNodeAt(normalized);
            if (resolved != null) {
                return resolved;
            }
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

    public void directWrite(String path, Node value) {
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
            throw ex;
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
        ResolvedSnapshot snapshotRollback = snapshot;
        batchPatchCalls++;
        batchPatchEntries += patches.size();
        try {
            PlanningContext planning = planningContext(materializedView.root());
            BatchPatchTransaction transaction = new BatchPatchTransaction(originScopePath,
                    patches,
                    planning,
                    conformanceEngine,
                    new UpdateMaterializationMetrics() {
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
                    });
            BatchPatchResult result = transaction.apply();
            batchPatchPlanningNanos += result.patchPlanningNanos();
            batchPatchConformanceNanos += result.conformanceNanos();
            batchPatchBuildUpdatesNanos += result.buildUpdatesNanos();
            metrics.addBatchPatchPlanningNanos(result.patchPlanningNanos());
            metrics.addBatchPatchConformanceNanos(result.conformanceNanos());
            metrics.addBatchPatchBuildUpdatesNanos(result.buildUpdatesNanos());
            long commitStart = System.nanoTime();
            try {
                commitBatchPatchResult(result);
            } finally {
                long commitNanos = System.nanoTime() - commitStart;
                batchPatchCommitNanos += commitNanos;
                metrics.addBatchPatchCommitNanos(commitNanos);
                metrics.addSnapshotCommitNanos(commitNanos);
            }
            return result.updates();
        } catch (RuntimeException ex) {
            snapshot = snapshotRollback;
            if (snapshotRollback != null) {
                materializedView.replaceWithSnapshot(snapshotRollback);
            }
            throw ex;
        }
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
        if (snapshotManager == null) {
            ImmutablePatchPlanner planner = ImmutablePatchPlanner.forMaterialized(rollback);
            return new PlanningContext(null, planner, planner);
        }
        ResolvedSnapshot base = snapshot != null ? snapshot : snapshotManager.fromDocument(rollback);
        return new PlanningContext(base,
                ImmutablePatchPlanner.forSnapshot(base),
                ImmutablePatchPlanner.forFrozen(base.frozenResolvedRoot()));
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
            return;
        }
        if (plan.next != null) {
            snapshot = plan.next;
            materializedView.replaceWithSnapshot(snapshot);
        } else {
            snapshot = snapshotManager.fromDocument(fallbackRoot.toNode());
            materializedView.replaceWithSnapshot(snapshot);
        }
    }

    private void commitBatchPatchResult(BatchPatchResult result) {
        if (snapshotManager == null) {
            Node next = result.resolvedRoot().toNode();
            materializedView.replaceWith(next);
            snapshot = null;
            return;
        }
        ResolvedSnapshot next = new ResolvedSnapshot(result.canonicalRoot(),
                result.resolvedRoot(),
                result.canonicalRoot().blueId());
        ResolvedSnapshot cached = snapshotManager.cacheSnapshot(next);
        materializedView.replaceWithSnapshot(cached);
        snapshot = cached;
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

        private PlanningContext(ResolvedSnapshot baseSnapshot,
                                ImmutablePatchPlanner canonicalPlanner,
                                ImmutablePatchPlanner resolvedPlanner) {
            this.baseSnapshot = baseSnapshot;
            this.canonicalPlanner = canonicalPlanner;
            this.resolvedPlanner = resolvedPlanner;
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
    }

    private static final class SnapshotPatchPlan {
        private final ResolvedSnapshot next;

        private SnapshotPatchPlan(ResolvedSnapshot next) {
            this.next = next;
        }
    }
}
