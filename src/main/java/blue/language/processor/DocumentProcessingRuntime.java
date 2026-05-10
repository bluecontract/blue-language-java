package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
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
    private ResolvedSnapshot snapshot;
    private boolean runTerminated;

    public DocumentProcessingRuntime(Node document) {
        this(document, null, null);
    }

    public DocumentProcessingRuntime(Node document, ConformanceEngine conformanceEngine) {
        this(document, conformanceEngine, null);
    }

    public DocumentProcessingRuntime(Node document,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager) {
        this.materializedView = new MaterializedDocumentView(Objects.requireNonNull(document, "document"));
        this.emissionRegistry = new EmissionRegistry();
        this.gasMeter = new GasMeter();
        this.conformanceEngine = conformanceEngine;
        this.snapshotManager = snapshotManager;
    }

    public DocumentProcessingRuntime(ResolvedSnapshot snapshot,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.materializedView = new MaterializedDocumentView(snapshot.canonicalRoot());
        this.emissionRegistry = new EmissionRegistry();
        this.gasMeter = new GasMeter();
        this.conformanceEngine = conformanceEngine;
        this.snapshotManager = snapshotManager;
        this.snapshot = snapshot;
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
        Node rollback = materializedView.copyRoot();
        ResolvedSnapshot snapshotRollback = snapshot;
        ImmutablePatchPlanner.PatchPlan result;
        Node before;
        try {
            PlanningContext planning = planningContext(rollback);
            result = planning.canonicalPlanner.plan(originScopePath, patch);
            ImmutablePatchPlanner.PatchPlan resolvedPlan = planning.resolvedPlanner.plan(originScopePath, patch);
            before = updateBefore(planning.baseSnapshot, result);
            ConformancePlan conformancePlan = planConformanceFromPatch(result, resolvedPlan.root());
            if (conformancePlan.generalized()) {
                commitGeneralization(conformancePlan);
            } else {
                SnapshotPatchPlan snapshotPatchPlan = prepareSnapshotPatch(planning.baseSnapshot, patch);
                commitSnapshotPatch(snapshotPatchPlan, conformancePlan.root());
            }
        } catch (RuntimeException ex) {
            materializedView.replaceWith(rollback);
            snapshot = snapshotRollback;
            throw ex;
        }
        Node after = result.op() == JsonPatch.Op.REMOVE
                ? null
                : updateAfter(result);
        return new DocumentUpdateData(result.path(),
                before,
                after,
                result.op(),
                result.originScope(),
                result.cascadeScopes());
    }

    private ConformancePlan planConformanceFromPatch(ImmutablePatchPlanner.PatchPlan result, FrozenNode resolvedRoot) {
        if (conformanceEngine == null) {
            return ConformancePlan.unchanged(result.root(), resolvedRoot);
        }
        if (isProcessorManagedConformanceBypass(result)) {
            return ConformancePlan.unchanged(result.root(), resolvedRoot);
        }
        FrozenNode originScope = ImmutablePatchPlanner.forFrozen(resolvedRoot).read(result.originScope());
        if (originScope == null || originScope.getType() == null) {
            return ConformancePlan.unchanged(result.root(), resolvedRoot);
        }
        return conformanceEngine.planGeneralization(result.root(), resolvedRoot, result.path());
    }

    private boolean isProcessorManagedConformanceBypass(ImmutablePatchPlanner.PatchPlan result) {
        String relativePath = PointerUtils.relativizePointer(result.originScope(), result.path());
        String initialized = ProcessorPointerConstants.RELATIVE_INITIALIZED;
        return relativePath.equals(initialized) || relativePath.startsWith(initialized + "/");
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

    private Node updateBefore(ResolvedSnapshot base, ImmutablePatchPlanner.PatchPlan plan) {
        if (base != null) {
            FrozenNode before = ImmutablePatchPlanner.readBefore(base, plan.path(), true);
            if (before != null) {
                return before.toNode();
            }
        }
        return plan.beforeNode();
    }

    private Node updateAfter(ImmutablePatchPlanner.PatchPlan plan) {
        if (snapshot != null) {
            FrozenNode after = ImmutablePatchPlanner.readAfter(snapshot, plan.path(), true);
            if (after != null) {
                return after.toNode();
            }
        }
        return materializedView.nodeAt(plan.path());
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

    private void commitGeneralization(ConformancePlan plan) {
        if (snapshotManager != null
                && plan.fullSnapshotRebuildAvoidable()
                && plan.canonicalRoot() != null) {
            snapshot = snapshotManager.cacheSnapshot(new ResolvedSnapshot(plan.canonicalRoot(),
                    plan.root(),
                    plan.canonicalRoot().blueId()));
            materializedView.replaceWithSnapshot(snapshot);
            return;
        }
        commitGeneralizedRoot(plan.root());
    }

    private void commitGeneralizedRoot(FrozenNode generalizedRoot) {
        if (snapshotManager == null) {
            materializedView.replaceWith(generalizedRoot.toNode());
            return;
        }
        snapshot = snapshotManager.fromDocument(generalizedRoot.toNode());
        materializedView.replaceWithSnapshot(snapshot);
    }

    static final class DocumentUpdateData {
        private final String path;
        private final Node before;
        private final Node after;
        private final JsonPatch.Op op;
        private final String originScope;
        private final List<String> cascadeScopes;

        DocumentUpdateData(String path,
                           Node before,
                           Node after,
                           JsonPatch.Op op,
                           String originScope,
                           List<String> cascadeScopes) {
            this.path = path;
            this.before = before;
            this.after = after;
            this.op = op;
            this.originScope = originScope;
            this.cascadeScopes = cascadeScopes;
        }

        String path() {
            return path;
        }

        Node before() {
            return before;
        }

        Node after() {
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

    private static final class PlanningContext {
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
    }

    private static final class SnapshotPatchPlan {
        private final ResolvedSnapshot next;

        private SnapshotPatchPlan(ResolvedSnapshot next) {
            this.next = next;
        }
    }
}
