package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
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

    public Node canonicalNodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.canonicalNodeAt(normalized);
        }
        return materializedView.nodeAt(normalized);
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
            Node before = cloneNode(new PatchEngine(rollback.clone()).read(path));
            JsonPatch snapshotPatch = directWritePatch(path, before, value);
            SnapshotPatchPlan snapshotPatchPlan = snapshotPatch != null
                    ? prepareSnapshotPatch(rollback, snapshotPatch)
                    : null;
            Node compatibilityRoot = rollback.clone();
            new PatchEngine(compatibilityRoot).directWrite(path, value);
            materializeCommittedPatch(snapshotPatchPlan, compatibilityRoot);
            if (snapshotPatch != null) {
                commitSnapshotPatch(snapshotPatchPlan, false);
            }
        } catch (RuntimeException ex) {
            materializedView.replaceWith(rollback);
            snapshot = snapshotRollback;
            throw ex;
        }
    }

    public DocumentUpdateData applyPatch(String originScopePath, JsonPatch patch) {
        Node rollback = materializedView.copyRoot();
        ResolvedSnapshot snapshotRollback = snapshot;
        PatchEngine.PatchResult result;
        try {
            SnapshotPatchPlan snapshotPatchPlan = prepareSnapshotPatch(rollback, patch);
            Node compatibilityRoot = rollback.clone();
            result = new PatchEngine(compatibilityRoot).applyPatch(originScopePath, patch);
            materializedView.replaceWith(compatibilityRoot);
            boolean generalized = enforceConformanceFromPatchScope(result);
            commitSnapshotPatch(snapshotPatchPlan, generalized);
        } catch (RuntimeException ex) {
            materializedView.replaceWith(rollback);
            snapshot = snapshotRollback;
            throw ex;
        }
        Node after = result.op() == JsonPatch.Op.REMOVE
                ? null
                : materializedView.nodeAt(result.path());
        return new DocumentUpdateData(result.path(),
                result.before(),
                after,
                result.op(),
                result.originScope(),
                result.cascadeScopes());
    }

    private boolean enforceConformanceFromPatchScope(PatchEngine.PatchResult result) {
        if (conformanceEngine == null) {
            return false;
        }
        Node scopeNode = new PatchEngine(materializedView.root()).read(result.originScope());
        if (scopeNode == null || scopeNode.getType() == null) {
            return false;
        }
        String relativePath = PointerUtils.relativizePointer(result.originScope(), result.path());
        return conformanceEngine.generalizeChangedPath(scopeNode, relativePath);
    }

    private JsonPatch directWritePatch(String path, Node before, Node value) {
        if (value == null) {
            return before == null ? null : JsonPatch.remove(path);
        }
        return before == null
                ? JsonPatch.add(path, value.clone())
                : JsonPatch.replace(path, value.clone());
    }

    private SnapshotPatchPlan prepareSnapshotPatch(Node rollback, JsonPatch patch) {
        if (snapshotManager == null) {
            return null;
        }
        ResolvedSnapshot base = snapshot != null ? snapshot : snapshotManager.fromDocument(rollback);
        try {
            return new SnapshotPatchPlan(snapshotManager.applyPatch(base, patch));
        } catch (RuntimeException ex) {
            return new SnapshotPatchPlan(null);
        }
    }

    private void materializeCommittedPatch(SnapshotPatchPlan plan, Node compatibilityRoot) {
        if (snapshotManager != null && plan != null && plan.next != null) {
            materializedView.replaceWithSnapshot(plan.next);
            return;
        }
        materializedView.replaceWith(compatibilityRoot);
    }

    private void commitSnapshotPatch(SnapshotPatchPlan plan, boolean generalized) {
        if (snapshotManager == null || plan == null) {
            return;
        }
        if (generalized) {
            snapshot = snapshotManager.fromDocument(materializedView.root());
            materializedView.replaceWithSnapshot(snapshot);
            return;
        }

        if (plan.next != null) {
            snapshot = plan.next;
            materializedView.replaceWithSnapshot(snapshot);
        } else {
            snapshot = snapshotManager.fromDocument(materializedView.root());
            materializedView.replaceWithSnapshot(snapshot);
        }
    }

    private Node cloneNode(Node node) {
        return node != null ? node.clone() : null;
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

    private static final class SnapshotPatchPlan {
        private final ResolvedSnapshot next;

        private SnapshotPatchPlan(ResolvedSnapshot next) {
            this.next = next;
        }
    }
}
