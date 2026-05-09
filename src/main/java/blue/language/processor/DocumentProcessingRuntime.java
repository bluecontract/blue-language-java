package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime state holder for a single document-processing invocation.
 */
public final class DocumentProcessingRuntime {

    private final Node document;
    private final PatchEngine patchEngine;
    private final EmissionRegistry emissionRegistry;
    private final GasMeter gasMeter;
    private final ConformanceEngine conformanceEngine;
    private boolean runTerminated;

    public DocumentProcessingRuntime(Node document) {
        this(document, null);
    }

    public DocumentProcessingRuntime(Node document, ConformanceEngine conformanceEngine) {
        this.document = Objects.requireNonNull(document, "document");
        this.patchEngine = new PatchEngine(this.document);
        this.emissionRegistry = new EmissionRegistry();
        this.gasMeter = new GasMeter();
        this.conformanceEngine = conformanceEngine;
    }

    public Node document() {
        return document;
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

    public void directWrite(String path, Node value) {
        patchEngine.directWrite(path, value);
    }

    public DocumentUpdateData applyPatch(String originScopePath, JsonPatch patch) {
        Node rollback = document.clone();
        PatchEngine.PatchResult result;
        try {
            result = patchEngine.applyPatch(originScopePath, patch);
            if (conformanceEngine != null && document.getType() != null) {
                conformanceEngine.generalizeChangedPath(document, result.path());
            }
        } catch (RuntimeException ex) {
            document.replaceWith(rollback);
            throw ex;
        }
        Node after = result.op() == JsonPatch.Op.REMOVE ? null : cloneNode(patchEngine.read(result.path()));
        return new DocumentUpdateData(result.path(),
                result.before(),
                after,
                result.op(),
                result.originScope(),
                result.cascadeScopes());
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
}
