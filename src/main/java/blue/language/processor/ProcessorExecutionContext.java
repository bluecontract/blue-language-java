package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.conformance.ScriptedContractsRuntime;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.List;
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
    private final ContractEffectBuffer effects = new ContractEffectBuffer();
    private boolean effectsApplied;

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

    public String scopePath() {
        return scopePath;
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
        if (patch == null) {
            return;
        }
        applyPatches(Collections.singletonList(patch));
    }

    public void applyPatches(List<JsonPatch> patches) {
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        effects.addPatches(patches);
    }

    public void applyPreviewedPatches(List<JsonPatch> patches, WorkingDocument.Preview preview) {
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        effects.addPreviewedPatches(patches, preview);
    }

    public void emitEvent(Node emission) {
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return;
        }
        Objects.requireNonNull(emission, "emission");
        effects.emit(emission);
    }

    void applyBufferedEffects() {
        if (effectsApplied) {
            return;
        }
        effectsApplied = true;
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return;
        }
        if (effects.invalidGasReason() != null) {
            execution.enterFatalTermination(scopePath,
                    bundle,
                    ProcessorErrorCategory.GasError,
                    effects.invalidGasReason());
            return;
        }
        if (effects.gas() > 0L) {
            runtime().addGas(effects.gas());
        }
        for (ContractEffectBuffer.PatchBatch patchBatch : effects.patchBatches()) {
            execution.handlePatches(scopePath,
                    bundle,
                    patchBatch.patches(),
                    allowReservedMutation,
                    patchBatch.preview());
            if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
                return;
            }
        }
        for (Node emission : effects.emittedEvents()) {
            if (!emitEventNow(emission)) {
                return;
            }
            if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
                return;
            }
        }
        ContractEffectBuffer.TerminationRequest termination = effects.terminationRequest();
        if (termination != null) {
            ScriptedContractsRuntime scriptedRuntime = ScriptedContractsRuntime.active();
            if (scriptedRuntime != null) {
                scriptedRuntime.recordTermination(runtime(), termination.kind());
            }
            if (termination.kind() == ScopeRuntimeContext.TerminationKind.FATAL) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        ProcessorErrorCategory.InternalProcessorError,
                        termination.reason());
            } else {
                execution.enterGracefulTermination(scopePath, bundle, termination.reason());
            }
        }
    }

    public void consumeGas(long units) {
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return;
        }
        effects.addGas(units);
    }

    public void throwFatal(String reason) {
        applyBufferedEffects();
        throw new ProcessorFatalException(reason,
                execution.partialResult(),
                ProcessorErrorCategory.HandlerExecutionError);
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

    public FrozenNode canonicalFrozenAt(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return null;
        }
        return runtime().canonicalFrozenAt(absolutePointer);
    }

    public FrozenNode resolvedFrozenAt(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return null;
        }
        return runtime().resolvedFrozenAt(absolutePointer);
    }

    public WorkingDocument newWorkingDocument() {
        return runtime().workingDocument(scopePath);
    }

    public WorkingDocument newWorkingDocument(String originScope) {
        return runtime().workingDocument(originScope);
    }

    public boolean documentContains(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return false;
        }
        return runtime().contains(absolutePointer);
    }

    public void terminateGracefully(String reason) {
        effects.terminate(ScopeRuntimeContext.TerminationKind.GRACEFUL, reason);
    }

    public void terminateFatally(String reason) {
        effects.terminate(ScopeRuntimeContext.TerminationKind.FATAL, reason);
    }

    private boolean emitEventNow(Node emission) {
        try {
            CheckpointIdentityCalculator.identity(emission, execution.blue());
        } catch (RuntimeException ex) {
            execution.enterFatalTermination(scopePath,
                    bundle,
                    ProcessorErrorCategory.InvalidPatchValue,
                    "Invalid emitted event: " + ex.getMessage());
            return false;
        }
        if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
            return false;
        }
        DocumentProcessingRuntime runtime = runtime();
        ScopeRuntimeContext scopeContext = runtime.scope(scopePath);
        runtime.chargeEmitEvent(emission);
        Node queued = emission.clone();
        scopeContext.enqueueTriggered(queued);
        scopeContext.recordBridgeable(queued.clone());
        ScriptedContractsRuntime scriptedRuntime = ScriptedContractsRuntime.active();
        if (scriptedRuntime != null) {
            scriptedRuntime.recordTriggeredEvent(runtime, queued);
        }
        if ("/".equals(scopeContext.scopePath())) {
            runtime.recordRootEmission(queued.clone());
        }
        return true;
    }

    private DocumentProcessingRuntime runtime() {
        return execution.runtime();
    }
}
