package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Lightweight wrapper passed to contract processors while executing.
 */
public final class ProcessorExecutionContext {

    private final ProcessorEngine.Execution execution;
    private final ContractBundle bundle;
    private final String scopePath;
    private final String contractKey;
    private final FrozenNode contractNode;
    private final Node event;
    private final boolean allowTerminatedWork;
    private final boolean allowReservedMutation;

    ProcessorExecutionContext(ProcessorEngine.Execution execution,
                              ContractBundle bundle,
                              String scopePath,
                              String contractKey,
                              FrozenNode contractNode,
                              Node event,
                              boolean allowTerminatedWork,
                              boolean allowReservedMutation) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.contractKey = contractKey;
        this.contractNode = contractNode;
        this.event = Objects.requireNonNull(event, "event");
        this.allowTerminatedWork = allowTerminatedWork;
        this.allowReservedMutation = allowReservedMutation;
    }

    public String contractKey() {
        return contractKey;
    }

    public Node contractNode() {
        return contractNode != null ? contractNode.toNode() : null;
    }

    public FrozenNode frozenContractNode() {
        return contractNode;
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
        throw new ProcessorFatalException(reason, execution.partialResult());
    }

    public String resolvePointer(String pointer) {
        return execution.resolvePointer(scopePath, pointer);
    }

    public Node documentAt(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return null;
        }
        return runtime().nodeAt(absolutePointer);
    }

    public boolean documentContains(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return false;
        }
        return runtime().contains(absolutePointer);
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
