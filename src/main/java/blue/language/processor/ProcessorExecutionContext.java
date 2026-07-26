package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            recordCutOffDiscardedEffects(0, 0);
            return;
        }
        if (effects.runtimeLedger() != null) {
            runtime().mergeRuntimeGasLedger(effects.runtimeLedger());
        }
        for (int batchIndex = 0;
             batchIndex < effects.patchBatches().size();
             batchIndex++) {
            ContractEffectBuffer.PatchBatch patchBatch =
                    effects.patchBatches().get(batchIndex);
            execution.handlePatchInputs(scopePath,
                    bundle,
                    patchBatch.patches(),
                    allowReservedMutation,
                    patchBatch.preview());
            if (execution.shouldStopScopeWork(scopePath)) {
                recordCutOffDiscardedEffects(batchIndex + 1, 0);
                return;
            }
        }
        for (int eventIndex = 0;
             eventIndex < effects.emittedEvents().size();
             eventIndex++) {
            Node emission = effects.emittedEvents().get(eventIndex);
            if (!emitEventNow(emission)) {
                recordCutOffDiscardedEffects(
                        effects.patchBatches().size(), eventIndex);
                return;
            }
            if (execution.shouldStopScopeWork(scopePath)) {
                recordCutOffDiscardedEffects(
                        effects.patchBatches().size(), eventIndex + 1);
                return;
            }
        }
        ContractEffectBuffer.TerminationRequest termination = effects.terminationRequest();
        if (termination != null) {
            execution.enterGracefulTermination(
                    scopePath, bundle, termination.cause(), termination.reason());
        }
    }

    private void recordCutOffDiscardedEffects(int firstPatchBatchIndex,
                                              int firstEventIndex) {
        ScopeRuntimeContext scope = runtime().existingScope(
                execution.normalizeScope(scopePath));
        if (scope == null || !scope.isCutOff()) {
            return;
        }
        List<ContractEffectBuffer.PatchBatch> patchBatches =
                effects.patchBatches();
        for (int batchIndex = Math.max(0, firstPatchBatchIndex);
             batchIndex < patchBatches.size();
             batchIndex++) {
            for (PatchInput patch :
                    patchBatches.get(batchIndex).patches()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("effect", "patch");
                details.put("reason", "scope-cut-off");
                details.put("label", patch.authoredPath());
                runtime().recordTrace(
                        ProcessingTraceRecord.Kind.DISCARDED_EFFECT,
                        scopePath,
                        contractKey,
                        patch.authoredPath(),
                        details,
                        null);
            }
        }
        List<Node> emissions = effects.emittedEvents();
        for (int index = Math.max(0, firstEventIndex);
             index < emissions.size();
             index++) {
            Node emission = emissions.get(index);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("effect", "event");
            details.put("reason", "scope-cut-off");
            details.put("label", discardedEventLabel(emission));
            runtime().recordTrace(
                    ProcessingTraceRecord.Kind.DISCARDED_EFFECT,
                    scopePath,
                    contractKey,
                    null,
                    details,
                    emission);
        }
        ContractEffectBuffer.TerminationRequest termination =
                effects.terminationRequest();
        if (termination != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("effect", "termination");
            details.put("reason", "scope-cut-off");
            details.put("label", "termination:" + termination.cause());
            runtime().recordTrace(
                    ProcessingTraceRecord.Kind.DISCARDED_EFFECT,
                    scopePath,
                    contractKey,
                    null,
                    details,
                    null);
        }
    }

    private String discardedEventLabel(Node event) {
        Node id = event != null && event.getProperties() != null
                ? event.getProperties().get("id")
                : null;
        if (id != null && id.getValue() != null) {
            return String.valueOf(id.getValue());
        }
        if (event != null && event.getValue() != null) {
            return String.valueOf(event.getValue());
        }
        return "event";
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

    /**
     * @deprecated Contracts 1.0 requires named, weighted runtime counters.
     * Create a child ledger with {@link #newRuntimeGasLedger(String, Map)}
     * and submit it with {@link #submitRuntimeGasLedger(GasMeter.ChildGasLedger)}.
     */
    @Deprecated
    public void consumeGas(long units) {
        ensureOpen();
        throw new UnsupportedOperationException(
                "Anonymous runtime gas is not supported by Contracts 1.0; "
                        + "use a named runtime child ledger");
    }

    /**
     * Creates a live-bounded, named runtime child ledger using the exact
     * currently remaining shared budget.
     */
    public GasMeter.ChildGasLedger newRuntimeGasLedger(
            String namespace,
            Map<String, Long> counterWeights) {
        ensureOpen();
        return runtime().newRuntimeGasLedger(namespace, counterWeights);
    }

    /**
     * Attaches the completed named runtime ledger to this result.  It is
     * validated and merged exactly once before any patch, event, or
     * termination effect.
     */
    public void submitRuntimeGasLedger(GasMeter.ChildGasLedger ledger) {
        ensureOpen();
        effects.runtimeLedger(Objects.requireNonNull(ledger, "ledger"));
    }

    public void throwFatal(String reason) {
        ensureOpen();
        /*
         * A deterministic runtime failure aborts the entire invocation.  In
         * particular, effects buffered by this call must not become visible
         * before the abort is observed.
         */
        close();
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
        return runtime().workingDocument(scopePath, PatchSource.CUSTOM_PROCESSOR);
    }

    public WorkingDocument newWorkingDocument(String originScope) {
        ensureOpen();
        return runtime().workingDocument(originScope, PatchSource.CUSTOM_PROCESSOR);
    }

    public boolean documentContains(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return false;
        }
        return runtime().contains(absolutePointer);
    }

    public void terminateGracefully(String reason) {
        ensureOpen();
        terminate("graceful", reason);
    }

    /**
     * Requests successful application termination with an application-defined
     * cause and optional explanatory reason.
     */
    public void terminate(String cause, String reason) {
        ensureOpen();
        if (cause == null || cause.isEmpty()) {
            throw new IllegalArgumentException("Termination cause must not be empty");
        }
        effects.terminate(cause, reason);
    }

    /**
     * @deprecated Contracts 1.0 has no committing fatal termination mode.
     * Calling this method aborts atomically as a deterministic runtime failure.
     */
    @Deprecated
    public void terminateFatally(String reason) {
        throwFatal(reason != null ? reason : "Runtime requested fatal termination");
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Processor execution context is closed");
        }
    }

    private boolean emitEventNow(Node emission) {
        String eventBlueId;
        try {
            eventBlueId = CheckpointIdentityCalculator.identity(
                    emission, execution.blue());
        } catch (RuntimeException ex) {
            execution.abortRuntimeFailure(scopePath,
                    bundle,
                    ProcessorErrorCategory.InvalidPatchValue,
                    "Invalid emitted event: " + ex.getMessage());
            return false;
        }
        if (execution.shouldStopScopeWork(scopePath)) {
            return false;
        }
        execution.enqueueApplicationEvent(
                scopePath,
                contractKey,
                emission,
                eventBlueId);
        return true;
    }

    private DocumentProcessingRuntime runtime() {
        return execution.runtime();
    }
}
