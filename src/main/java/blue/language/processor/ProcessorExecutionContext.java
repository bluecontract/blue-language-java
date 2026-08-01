package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.ArrayList;
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

    private static final String PATCH_LIMIT =
            GasScheduleConstants.PortableLimit.PATCHES_PER_CONTRACT_RESULT;
    private static final String EVENT_LIMIT =
            GasScheduleConstants.PortableLimit.EVENTS_PER_CONTRACT_RESULT;

    private final ProcessorInvocationState execution;
    private final ContractBundle bundle;
    private final String scopePath;
    private final String contractKey;
    private final FrozenNode contractNode;
    private final Node event;
    private final Node occurrenceEvent;
    private final boolean allowReservedMutation;
    private final ContractEffectBuffer effects = new ContractEffectBuffer();
    private final RuntimeWorkSession runtimeWorkSession;
    private final Map<String, SelectedExecutableBody>
            selectedExecutableBodies =
            new LinkedHashMap<>();
    private long acceptedPatchCount;
    private long acceptedEventCount;
    private boolean effectsApplied;
    private boolean closed;

    ProcessorExecutionContext(ProcessorInvocationState execution,
                              ContractBundle bundle,
                              String scopePath,
                              String contractKey,
                              FrozenNode contractNode,
                              Node event,
                              Node occurrenceEvent,
                              boolean allowReservedMutation) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.contractKey = contractKey;
        this.contractNode = contractNode;
        this.event = Objects.requireNonNull(event, "event");
        this.occurrenceEvent = Objects.requireNonNull(
                occurrenceEvent,
                "occurrenceEvent");
        this.allowReservedMutation = allowReservedMutation;
        this.runtimeWorkSession =
                execution.runtime().newRuntimeWorkSession(
                        execution.blue());
    }

    /**
     * Returns the contract key selected for this invocation.
     *
     * @return contract key, or {@code null} for processor-managed work
     */
    public String contractKey() {
        return contractKey;
    }

    /**
     * Returns the absolute scope in which this invocation executes.
     *
     * @return normalized scope path
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Materializes a detached mutable copy of the effective contract.
     *
     * @return contract copy, or {@code null} when no contract is bound
     */
    public Node contractNode() {
        return contractNode != null ? contractNode.toNode() : null;
    }

    /**
     * Returns the immutable effective contract without materialization.
     *
     * @return frozen contract, or {@code null} when no contract is bound
     */
    public FrozenNode frozenContractNode() {
        return contractNode;
    }

    /**
     * Returns this handler's current channelized event payload.
     *
     * <p>This is not the Processing Event. Triggered, bridged, and adapted
     * deliveries may each have a different current event.</p>
     *
     * @return current channelized event payload
     */
    public Node event() {
        return event;
    }

    /**
     * Returns the semantic event occurrence offered to this handler.
     *
     * <p>Ordinary deliveries return the same value as {@link #event()}.
     * Adapter Channels may keep their wire payload in {@code event()} while
     * retaining the exact originating occurrence here.</p>
     *
     * @return current semantic occurrence event
     */
    public Node occurrenceEvent() {
        return occurrenceEvent;
    }

    /**
     * Returns whether this execution was started by {@code PROCESS(document, event)}.
     *
     * <p>This is a constant-time presence check and never constructs the immutable
     * Processing Event snapshot. Explicit {@code INITIALIZE} executions return
     * {@code false}.</p>
     *
     * @return {@code true} for a PROCESS invocation
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
     *
     * @return immutable original Processing Event, or {@code null} during
     *         explicit initialization
     */
    public FrozenNode frozenProcessEvent() {
        return execution.frozenProcessEvent();
    }

    /**
     * Buffers one mutable patch for this handler invocation.
     *
     * <p>The patch is defensively captured with the surrounding batch and is
     * applied only after the handler returns successfully. {@code null} is a
     * no-op; a stopped scope accepts no further effects.</p>
     *
     * @param patch mutable authored patch, or {@code null}
     * @throws IllegalStateException if this invocation context is closed
     */
    public void applyPatch(JsonPatch patch) {
        ensureOpen();
        if (patch == null) {
            return;
        }
        applyPatches(Collections.singletonList(patch));
    }

    /**
     * Buffers an ordered atomic patch batch, enforcing the per-result
     * portable patch limit before ownership is transferred.
     *
     * @param patches ordered mutable patches; {@code null} and empty lists are
     *         no-ops
     * @throws PortableLimitExceededException if the result patch bound would
     *         be exceeded
     * @throws IllegalStateException if this invocation context is closed
     */
    public void applyPatches(List<JsonPatch> patches) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        long observedPatchCount = requireEffectCapacity(
                ProcessorErrorCategory.PatchLimitExceeded,
                PATCH_LIMIT,
                acceptedPatchCount,
                patches.size());
        effects.addPatches(patches);
        acceptedPatchCount = observedPatchCount;
    }

    /**
     * Buffers patches with a precomputed preview.
     *
     * <p>When this context accepts a non-empty patch list, it owns the preview
     * and releases it after the buffered effects are consumed or abandoned.
     * If execution has already stopped or the list is empty, ownership remains
     * with the caller.</p>
     *
     * @param patches ordered mutable patches
     * @param preview matching working-document preview
     * @throws PortableLimitExceededException if the result patch bound would
     *         be exceeded
     * @throws IllegalStateException if this invocation context is closed
     */
    public void applyPreviewedPatches(List<JsonPatch> patches, WorkingDocument.Preview preview) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        long observedPatchCount = requireEffectCapacity(
                ProcessorErrorCategory.PatchLimitExceeded,
                PATCH_LIMIT,
                acceptedPatchCount,
                patches.size());
        effects.addPreviewedPatches(patches, preview);
        acceptedPatchCount = observedPatchCount;
    }

    /**
     * Buffers one already-frozen patch without reopening caller-owned mutable
     * value state.
     *
     * @param patch immutable patch, or {@code null}
     * @throws IllegalStateException if this invocation context is closed
     */
    public void applyFrozenPatch(FrozenJsonPatch patch) {
        ensureOpen();
        if (patch == null) {
            return;
        }
        applyFrozenPatches(Collections.singletonList(patch));
    }

    /**
     * Buffers an ordered atomic frozen-patch batch under the same portable
     * result limit as mutable patches.
     *
     * @param patches ordered immutable patches; {@code null} and empty lists
     *         are no-ops
     * @throws PortableLimitExceededException if the result patch bound would
     *         be exceeded
     * @throws IllegalStateException if this invocation context is closed
     */
    public void applyFrozenPatches(List<FrozenJsonPatch> patches) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        List<FrozenJsonPatch> admittedPatches =
                admitExactPatchValues(
                        patches);
        long observedPatchCount = requireEffectCapacity(
                ProcessorErrorCategory.PatchLimitExceeded,
                PATCH_LIMIT,
                acceptedPatchCount,
                admittedPatches.size());
        effects.addFrozenPatches(
                admittedPatches);
        acceptedPatchCount = observedPatchCount;
    }

    /**
     * Frozen-patch counterpart of {@link #applyPreviewedPatches(List, WorkingDocument.Preview)}.
     * Accepting a non-empty patch list transfers preview ownership to this context.
     *
     * @param patches ordered immutable patches
     * @param preview matching working-document preview
     * @throws PortableLimitExceededException if the result patch bound would
     *         be exceeded
     * @throws IllegalStateException if this invocation context is closed
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
        List<FrozenJsonPatch> admittedPatches =
                admitExactPatchValues(
                        patches);
        long observedPatchCount = requireEffectCapacity(
                ProcessorErrorCategory.PatchLimitExceeded,
                PATCH_LIMIT,
                acceptedPatchCount,
                admittedPatches.size());
        effects.addPreviewedFrozenPatches(
                admittedPatches,
                preview);
        acceptedPatchCount = observedPatchCount;
    }

    /**
     * Buffers one application event for FIFO delivery after successful
     * handler completion.
     *
     * <p>The event is cloned by the effect buffer. The portable event limit is
     * checked before admission, and no event is accepted after scope cut-off.</p>
     *
     * @param emission application event to buffer
     * @throws PortableLimitExceededException if the result event bound would
     *         be exceeded
     * @throws IllegalStateException if this invocation context is closed
     */
    public void emitEvent(Node emission) {
        ensureOpen();
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        Objects.requireNonNull(emission, "emission");
        long observedEventCount = requireEffectCapacity(
                ProcessorErrorCategory.InternalEventLimitExceeded,
                EVENT_LIMIT,
                acceptedEventCount,
                1L);
        effects.emit(emission);
        acceptedEventCount = observedEventCount;
    }

    /**
     * Buffers one event already admitted by a semantic output boundary.
     *
     * <p>The handle is re-admitted at this invocation boundary. Same-run
     * capabilities therefore avoid a second identity charge, while handles
     * from another invocation cannot replay ambient trust.</p>
     *
     * @param emission processor-issued exact event
     */
    public void emitEvent(
            ExactBlueValue emission) {
        ensureOpen();
        if (execution.shouldStopScopeWork(
                scopePath)) {
            return;
        }
        ExactBlueValue admitted =
                semanticOutputBoundary()
                        .admit(
                                Objects.requireNonNull(
                                        emission,
                                        "emission"));
        long observedEventCount = requireEffectCapacity(
                ProcessorErrorCategory.InternalEventLimitExceeded,
                EVENT_LIMIT,
                acceptedEventCount,
                1L);
        effects.emit(admitted);
        acceptedEventCount = observedEventCount;
    }

    void applyBufferedEffects() {
        if (effectsApplied) {
            return;
        }
        /*
         * Runtime work is portable run state, not an application effect.
         * Commit its submitted named traces before applying buffered patches
         * and events so a later deterministic effect failure retains the
         * admitted runtime prefix while the Root transition still rolls back.
         */
        runtimeWorkSession.complete();
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
        new BufferedContractEffectExecutor(
                execution,
                bundle,
                scopePath,
                contractKey,
                allowReservedMutation,
                effects).apply();
    }

    /**
     * Discards buffered work and releases every transferred preview.
     *
     * <p>Closing is idempotent. A context must not be used after this call.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        effectsApplied = true;
        Throwable failure = null;
        try {
            runtimeWorkSession.close();
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                effects.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure != null) {
                    if (failure != cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                } else {
                    throw cleanupFailure;
                }
            }
        }
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
     * Creates a live-bounded, named runtime child ledger using the exact
     * currently remaining shared budget.
     *
     * @param namespace stable hosted-runtime namespace
     * @param counterWeights immutable counter-name to unit-weight catalog
     * @return live child ledger owned by this invocation
     */
    public GasMeter.ChildGasLedger newRuntimeGasLedger(
            String namespace,
            Map<String, Long> counterWeights) {
        ensureOpen();
        return runtimeWorkSession.openLedger(
                namespace, counterWeights);
    }

    /**
     * Submits the completed named runtime ledger to the invocation meter.
     *
     * <p>The processor-owned work session stages the ledger against a live
     * parent reservation and merges submitted ledgers once, in canonical
     * namespace order, when this execution unit completes. Several
     * independently specified runtime namespaces may participate.</p>
     *
     * @param ledger live child ledger created by this context
     */
    public void submitRuntimeGasLedger(GasMeter.ChildGasLedger ledger) {
        ensureOpen();
        GasMeter.ChildGasLedger exactLedger =
                Objects.requireNonNull(ledger, "ledger");
        runtimeWorkSession.submit(exactLedger);
    }

    /** Returns the raw work session to processor-internal collaborators. */
    RuntimeWorkSession runtimeWorkSession() {
        ensureOpen();
        return runtimeWorkSession;
    }

    /**
     * Returns the single semantic output admission boundary owned by this
     * invocation.
     *
     * @return live invocation-owned semantic boundary
     */
    public SemanticOutputBoundary semanticOutputBoundary() {
        ensureOpen();
        return runtimeWorkSession.semanticOutputBoundary();
    }

    /**
     * Returns one invocation-bound selected executable-body capability.
     *
     * @param field direct executable-body field name
     * @return selected capability, or {@code null} when the field was absent
     */
    public SelectedExecutableBody selectedExecutableBody(
            String field) {
        ensureOpen();
        return selectedExecutableBodies.get(field);
    }

    /** Returns selected capabilities to processor-internal orchestration. */
    Map<String, SelectedExecutableBody>
    selectedExecutableBodies() {
        ensureOpen();
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        selectedExecutableBodies));
    }

    void bindSelectedExecutableBodies(
            List<String> fields,
            Map<String, String> bodyBlueIds) {
        ensureOpen();
        if (!selectedExecutableBodies.isEmpty()) {
            throw new IllegalStateException(
                    "Selected executable bodies were already bound");
        }
        if (fields == null || fields.isEmpty()) {
            return;
        }
        if (contractNode == null) {
            throw new IllegalStateException(
                    "Selected executable bodies require an exact contract snapshot");
        }
        Map<String, FrozenNode> properties =
                contractNode.getProperties();
        for (String field : new ArrayList<>(fields)) {
            FrozenNode body =
                    properties != null
                            ? properties.get(field)
                            : null;
            if (body == null) {
                continue;
            }
            String bodyBlueId =
                    bodyBlueIds != null
                            ? bodyBlueIds.get(field)
                            : null;
            if (bodyBlueId == null) {
                bodyBlueId =
                        body.isReferenceOnly()
                                ? body.getReferenceBlueId()
                                : body.blueId();
            }
            selectedExecutableBodies.put(
                    field,
                    new SelectedExecutableBody(
                            field,
                            bodyBlueId,
                            body,
                            runtime()
                                    ::materializeSelectedExecutableReference,
                            () -> !closed,
                            runtime().gasMeter()
                                    .schedule()));
        }
    }

    /**
     * Aborts the whole invocation as a deterministic runtime failure.
     *
     * <p>Admitted runtime work is retained, buffered application effects are
     * abandoned, and the thrown exception carries the current partial
     * result.</p>
     *
     * @param reason deterministic runtime-failure explanation
     * @throws ProcessorFatalException always
     */
    public void throwFatal(String reason) {
        ensureOpen();
        /*
         * A deterministic runtime failure aborts the entire invocation.  In
         * particular, effects buffered by this call must not become visible
         * before the abort is observed.
         */
        runtimeWorkSession.failDeterministically();
        close();
        throw new ProcessorFatalException(reason,
                execution.partialResult(),
                ProcessorErrorCategory.RuntimeExecutionFailure);
    }

    void suspendRuntimeWork() {
        runtimeWorkSession.suspend();
    }

    /**
     * Resolves a runtime pointer relative to this handler's scope.
     *
     * @param pointer relative or absolute JSON Pointer
     * @return normalized absolute pointer
     */
    public String resolvePointer(String pointer) {
        return execution.resolvePointer(scopePath, pointer);
    }

    /**
     * Returns a defensive mutable view of the current runtime node, or
     * {@code null} for an empty/absent absolute pointer.
     *
     * @param absolutePointer absolute JSON Pointer
     * @return detached node, or {@code null}
     */
    public Node documentAt(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return null;
        }
        return runtime().nodeAt(absolutePointer);
    }

    /**
     * Returns the exact canonical node at an absolute pointer, if present.
     *
     * @param absolutePointer absolute JSON Pointer
     * @return immutable canonical node, or {@code null}
     */
    public FrozenNode canonicalFrozenAt(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return null;
        }
        return runtime().canonicalFrozenAt(absolutePointer);
    }

    /**
     * Returns the effective resolved node at an absolute pointer, if present.
     *
     * @param absolutePointer absolute JSON Pointer
     * @return immutable resolved node, or {@code null}
     */
    public FrozenNode resolvedFrozenAt(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return null;
        }
        return runtime().resolvedFrozenAt(absolutePointer);
    }

    /**
     * Opens an invocation-owned working document rooted at this handler's
     * scope. The caller must close it or transfer a preview back to this
     * context.
     *
     * @return invocation-owned working document
     */
    public WorkingDocument newWorkingDocument() {
        ensureOpen();
        return runtime().workingDocument(scopePath, PatchSource.CUSTOM_PROCESSOR);
    }

    /** Opens processor-internal working state for an explicit origin scope. */
    WorkingDocument newWorkingDocument(String originScope) {
        ensureOpen();
        return runtime().workingDocument(originScope, PatchSource.CUSTOM_PROCESSOR);
    }

    /**
     * Tests the current runtime document without materializing missing data.
     *
     * @param absolutePointer absolute JSON Pointer
     * @return {@code true} when the runtime contains the pointer
     */
    public boolean documentContains(String absolutePointer) {
        if (absolutePointer == null || absolutePointer.isEmpty()) {
            return false;
        }
        return runtime().contains(absolutePointer);
    }

    /**
     * Buffers successful graceful termination after earlier buffered effects.
     *
     * @param reason optional application explanation
     */
    public void terminateGracefully(String reason) {
        ensureOpen();
        terminate("graceful", reason);
    }

    /**
     * Requests successful application termination with an application-defined
     * cause and optional explanatory reason.
     *
     * @param cause non-empty stable application cause
     * @param reason optional application explanation
     * @throws IllegalArgumentException if {@code cause} is empty
     */
    public void terminate(String cause, String reason) {
        ensureOpen();
        if (cause == null || cause.isEmpty()) {
            throw new IllegalArgumentException("Termination cause must not be empty");
        }
        effects.terminate(cause, reason);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Processor execution context is closed");
        }
    }

    private long requireEffectCapacity(
            ProcessorErrorCategory category,
            String limitName,
            long accepted,
            long additional) {
        long limit = runtime().gasMeter().schedule()
                .portableLimit(limitName);
        long observed = accepted > Long.MAX_VALUE - additional
                ? Long.MAX_VALUE
                : accepted + additional;
        if (observed > limit) {
            throw new PortableLimitExceededException(
                    category,
                    limitName,
                    observed,
                    limit);
        }
        return observed;
    }

    private List<FrozenJsonPatch> admitExactPatchValues(
            List<FrozenJsonPatch> patches) {
        List<FrozenJsonPatch> admitted =
                new ArrayList<>(
                        patches.size());
        for (FrozenJsonPatch patch : patches) {
            FrozenJsonPatch checked =
                    Objects.requireNonNull(
                            patch,
                            "patch");
            ExactBlueValue exact =
                    checked.getExactValue();
            admitted.add(
                    exact == null
                            ? checked
                            : checked.withExactValue(
                                    semanticOutputBoundary()
                                            .admit(
                                                    exact)));
        }
        return Collections.unmodifiableList(
                admitted);
    }

    private DocumentProcessingRuntime runtime() {
        return execution.runtime();
    }
}
