package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;

import java.util.Objects;

/**
 * Lightweight wrapper passed to contract processors while executing.
 */
public final class ProcessorExecutionContext {

    private final ProcessorEngine.Execution execution;
    private final ContractBundle bundle;
    private final String scopePath;
    private final Node event;
    private final boolean allowTerminatedWork;
    private final boolean allowReservedMutation;

    ProcessorExecutionContext(ProcessorEngine.Execution execution,
                              ContractBundle bundle,
                              String scopePath,
                              Node event,
                              boolean allowTerminatedWork,
                              boolean allowReservedMutation) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.event = Objects.requireNonNull(event, "event");
        this.allowTerminatedWork = allowTerminatedWork;
        this.allowReservedMutation = allowReservedMutation;
    }

    public Node event() {
        return event;
    }

    public void applyPatch(JsonPatch patch) {
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return;
        }
        execution.handlePatch(scopePath, bundle, patch, allowReservedMutation);
    }

    public void emitEvent(Node emission) {
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return;
        }
        Objects.requireNonNull(emission, "emission");
        DocumentProcessingRuntime runtime = runtime();
        ScopeRuntimeContext scopeContext = runtime.scope(scopePath);
        runtime.chargeEmitEvent(emission);
        Node queued = emission.clone();
        scopeContext.enqueueTriggered(queued);
        scopeContext.recordBridgeable(queued.clone());
        if ("/".equals(scopeContext.scopePath())) {
            runtime.recordRootEmission(queued.clone());
        }
    }

    public void consumeGas(long units) {
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return;
        }
        runtime().addGas(units);
    }

    public void throwFatal(String reason) {
        throw new ProcessorFatalException(reason);
    }

    public String resolvePointer(String pointer) {
        return execution.resolvePointer(scopePath, pointer);
    }

    public Node documentAt(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return null;
        }
        Node node = ProcessorEngine.nodeAt(runtime().document(), ProcessorEngine.normalizePointer(absolutePointer));
        return node != null ? node.clone() : null;
    }

    public boolean documentContains(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return false;
        }
        Node node = ProcessorEngine.nodeAt(runtime().document(), ProcessorEngine.normalizePointer(absolutePointer));
        return node != null;
    }

    public void terminateGracefully(String reason) {
        execution.enterGracefulTermination(scopePath, bundle, reason);
    }

    public void terminateFatally(String reason) {
        execution.enterFatalTermination(scopePath, bundle, reason);
    }

    private DocumentProcessingRuntime runtime() {
        return execution.runtime();
    }
}
