package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.conformance.ScriptedContractsRuntime;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Lightweight wrapper passed to contract processors while executing.
 *
 * <p>The context is valid only for its handler invocation. The processor
 * runtime closes it after applying or abandoning buffered effects; later
 * effect mutation and working-document creation are rejected.</p>
 */
public final class ProcessorExecutionContext implements AutoCloseable {

    private final ProcessorEngine.Execution execution;
    private final ContractBundle bundle;
    private final String scopePath;
    private final String contractKey;
    private final FrozenNode contractNode;
    private final Node event;
    private final boolean allowReservedMutation;
    private final ContractEffectBuffer effects = new ContractEffectBuffer();
    private boolean effectsApplied;
    private boolean closed;

    ProcessorExecutionContext(ProcessorEngine.Execution execution,
                              ContractBundle bundle,
                              String scopePath,
                              String contractKey,
                              FrozenNode contractNode,
                              Node event,
                              boolean allowReservedMutation) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.contractKey = contractKey;
        this.contractNode = contractNode;
        this.event = Objects.requireNonNull(event, "event");
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

    /**
     * Returns this handler's current channelized event payload.
     *
     * <p>This is not the Processing Event. Triggered, bridged, and adapted
     * deliveries may each have a different current event.</p>
     */
    public Node event() {
        return event;
    }

    /**
     * Returns whether this execution was started by {@code PROCESS(document, event)}.
     *
     * <p>This is a constant-time presence check and never constructs the immutable
     * Processing Event snapshot. Explicit {@code INITIALIZE} executions return
     * {@code false}.</p>
     */
    public boolean hasProcessEvent() {
        return execution.hasProcessEvent();
    }

    /**
     * Returns the immutable snapshot of the original Processing Event for this run.
     *
     * <p>The snapshot is constructed lazily on first access and then shared by all
     * handler contexts in the same execution. Explicit {@code INITIALIZE}
     * executions return {@code null}. Unlike {@link #event()}, this value is never
     * replaced by triggered, bridged, or adapted channel payloads.</p>
     */
    public FrozenNode frozenProcessEvent() {
        return execution.frozenProcessEvent();
    }

    public void applyPatch(JsonPatch patch) {
        ensureOpen();
        if (patch == null) {
            return;
        }
        applyPatches(Collections.singletonList(patch));
    }

    public void applyPatches(List<JsonPatch> patches) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        effects.addPatches(patches);
    }

    /**
     * Buffers patches with a precomputed preview.
     *
     * <p>When this context accepts a non-empty patch list, it owns the preview
     * and releases it after the buffered effects are consumed or abandoned.
     * If execution has already stopped or the list is empty, ownership remains
     * with the caller.</p>
     */
    public void applyPreviewedPatches(List<JsonPatch> patches, WorkingDocument.Preview preview) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        effects.addPreviewedPatches(patches, preview);
    }

    public void applyFrozenPatch(FrozenJsonPatch patch) {
        ensureOpen();
        if (patch == null) {
            return;
        }
        applyFrozenPatches(Collections.singletonList(patch));
    }

    public void applyFrozenPatches(List<FrozenJsonPatch> patches) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        effects.addFrozenPatches(patches);
    }

    /**
     * Frozen-patch counterpart of {@link #applyPreviewedPatches(List, WorkingDocument.Preview)}.
     * Accepting a non-empty patch list transfers preview ownership to this context.
     */
    public void applyPreviewedFrozenPatches(List<FrozenJsonPatch> patches,
                                            WorkingDocument.Preview preview) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        effects.addPreviewedFrozenPatches(patches, preview);
    }

    public void emitEvent(Node emission) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
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
        Throwable failure = null;
        try {
            applyBufferedEffectsNow();
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            closeEffects(failure);
        }
    }

    private void applyBufferedEffectsNow() {
        if (execution.shouldStopScopeWork(scopePath)) {
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
            execution.handlePatchInputs(scopePath,
                    bundle,
                    patchBatch.patches(),
                    allowReservedMutation,
                    patchBatch.preview());
            if (execution.shouldStopScopeWork(scopePath)) {
                return;
            }
        }
        for (Node emission : effects.emittedEvents()) {
            if (!emitEventNow(emission)) {
                return;
            }
            if (execution.shouldStopScopeWork(scopePath)) {
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
                execution.enterRequestedFatalTermination(scopePath, bundle, termination.reason());
            } else {
                execution.enterGracefulTermination(scopePath, bundle, termination.reason());
            }
        }
    }

    /** Discards buffered work and releases every transferred preview. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        effectsApplied = true;
        effects.close();
    }

    private void closeEffects(Throwable primaryFailure) {
        try {
            close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure != null) {
                if (primaryFailure != cleanupFailure) {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
            } else {
                throw cleanupFailure;
            }
        }
    }

    public void consumeGas(long units) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        effects.addGas(units);
    }

    public void throwFatal(String reason) {
        ensureOpen();
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
        ensureOpen();
        return runtime().workingDocument(scopePath);
    }

    public WorkingDocument newWorkingDocument(String originScope) {
        ensureOpen();
        return runtime().workingDocument(originScope);
    }

    public boolean documentContains(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return false;
        }
        return runtime().contains(absolutePointer);
    }

    public void terminateGracefully(String reason) {
        ensureOpen();
        effects.terminate(ScopeRuntimeContext.TerminationKind.GRACEFUL, reason);
    }

    public void terminateFatally(String reason) {
        ensureOpen();
        effects.terminate(ScopeRuntimeContext.TerminationKind.FATAL, reason);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Processor execution context is closed");
        }
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
        if (execution.shouldStopScopeWork(scopePath)) {
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
