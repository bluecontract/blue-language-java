package blue.language.processor;

import blue.language.model.Node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies one handler invocation's already-admitted effects in canonical order.
 *
 * <p>The executor owns no effects and performs no commit. It centralizes the
 * cut-off checks between patch batches, event occurrences, and termination so
 * the public execution context remains a small runtime-facing capability.</p>
 */
final class BufferedContractEffectExecutor {

    private final ProcessorInvocationState execution;
    private final ContractBundle bundle;
    private final String scopePath;
    private final String contractKey;
    private final boolean allowReservedMutation;
    private final ContractEffectBuffer effects;

    BufferedContractEffectExecutor(
            ProcessorInvocationState execution,
            ContractBundle bundle,
            String scopePath,
            String contractKey,
            boolean allowReservedMutation,
            ContractEffectBuffer effects) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.contractKey = contractKey;
        this.allowReservedMutation = allowReservedMutation;
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    /** Applies patches, then events, then the optional termination request. */
    void apply() {
        if (execution.shouldStopScopeWork(scopePath)) {
            recordCutOffDiscardedEffects(0, 0);
            return;
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
            ContractEffectBuffer.EventEmission emission =
                    effects.emittedEvents().get(eventIndex);
            if (!emitEvent(emission)) {
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
        ContractEffectBuffer.TerminationRequest termination =
                effects.terminationRequest();
        if (termination != null) {
            execution.enterGracefulTermination(scopePath,
                    bundle,
                    termination.cause(),
                    termination.reason());
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
            for (PatchInput patch : patchBatches.get(batchIndex).patches()) {
                recordDiscardedEffect(
                        ProcessingTraceConstants.EFFECT_PATCH,
                        patch.authoredPath(),
                        patch.authoredPath(),
                        null);
            }
        }
        List<ContractEffectBuffer.EventEmission> emissions =
                effects.emittedEvents();
        for (int index = Math.max(0, firstEventIndex);
             index < emissions.size();
             index++) {
            Node event = emissions.get(index).event();
            recordDiscardedEffect(
                    ProcessingTraceConstants.EFFECT_EVENT,
                    discardedEventLabel(event),
                    null,
                    event);
        }
        ContractEffectBuffer.TerminationRequest termination =
                effects.terminationRequest();
        if (termination != null) {
            recordDiscardedEffect(
                    ProcessingTraceConstants.EFFECT_TERMINATION,
                    ProcessingTraceConstants.LABEL_PREFIX_TERMINATION
                            + termination.cause(),
                    null,
                    null);
        }
    }

    private void recordDiscardedEffect(String effect,
                                       String label,
                                       String logicalPath,
                                       Node node) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(ProcessingTraceConstants.FIELD_EFFECT, effect);
        details.put(ProcessingTraceConstants.FIELD_REASON,
                ProcessingTraceConstants.REASON_SCOPE_CUT_OFF);
        details.put(ProcessingTraceConstants.FIELD_LABEL, label);
        runtime().recordTrace(ProcessingTraceRecord.Kind.DISCARDED_EFFECT,
                scopePath,
                contractKey,
                logicalPath,
                details,
                node);
    }

    private String discardedEventLabel(Node event) {
        Node id = event != null && event.getProperties() != null
                ? event.getProperties().get(
                        ProcessingTraceConstants.EVENT_LABEL_PROPERTY)
                : null;
        if (id != null && id.getValue() != null) {
            return String.valueOf(id.getValue());
        }
        if (event != null && event.getValue() != null) {
            return String.valueOf(event.getValue());
        }
        return ProcessingTraceConstants.DEFAULT_EVENT_LABEL;
    }

    private boolean emitEvent(ContractEffectBuffer.EventEmission emission) {
        Node event = emission.event();
        String eventBlueId;
        try {
            eventBlueId = emission.exactValue() != null
                    ? emission.exactValue().blueId()
                    : CheckpointIdentityCalculator.identity(
                            event, execution.blue());
        } catch (RuntimeException exception) {
            execution.abortRuntimeFailure(scopePath,
                    bundle,
                    ProcessorErrorCategory.InvalidPatch,
                    "Invalid emitted event: " + exception.getMessage());
            return false;
        }
        if (execution.shouldStopScopeWork(scopePath)) {
            return false;
        }
        execution.enqueueApplicationEvent(
                scopePath, contractKey, event, eventBlueId);
        return true;
    }

    private DocumentProcessingRuntime runtime() {
        return execution.runtime();
    }
}
