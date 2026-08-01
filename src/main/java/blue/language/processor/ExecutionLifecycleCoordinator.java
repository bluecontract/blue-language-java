package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.utils.JsonPointer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns invocation cut-offs, scope termination, lifecycle delivery, and
 * internal event admission.
 */
final class ExecutionLifecycleCoordinator {

    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ScopeExecutor scopeExecutor;
    private final TerminationService terminationService;
    private final Set<String> cutOffScopes = new LinkedHashSet<>();

    ExecutionLifecycleCoordinator(
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            ScopeExecutor scopeExecutor,
            TerminationService terminationService) {
        this.execution = execution;
        this.runtime = runtime;
        this.scopeExecutor = scopeExecutor;
        this.terminationService = terminationService;
    }

    boolean shouldStopScopeWork(String scopePath) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        ScopeRuntimeContext context = runtime.existingScope(normalized);
        return execution.hasFailure()
                || isUnderCutOffScope(normalized)
                || context != null && context.isTerminated();
    }

    boolean isScopeActive(String scopePath) {
        ScopeRuntimeContext context = runtime.existingScope(
                ProcessorEngine.normalizeScope(scopePath));
        return (context == null || context.isActive())
                && !shouldStopScopeWork(scopePath);
    }

    boolean canDeliverOccurrenceLocally(ScopeRuntimeContext context) {
        return !execution.hasFailure()
                && context != null
                && context.isActive()
                && !context.isCutOff();
    }

    boolean canCompleteTermination(String scopePath) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        ScopeRuntimeContext context = runtime.existingScope(normalized);
        return !execution.hasFailure()
                && !isUnderCutOffScope(normalized)
                && context != null
                && context.isTerminating();
    }

    void enterGracefulTermination(
            String scopePath,
            ContractBundle bundle,
            String cause,
            String reason) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        ScopeRuntimeContext context = runtime.scope(normalized);
        if (!context.beginTermination()) {
            return;
        }
        runtime.chargeTerminationRequest();
        terminationService.terminateScope(
                execution,
                scopePath,
                bundle,
                cause,
                reason);
    }

    void abortRuntimeFailure(
            String scopePath,
            ProcessorErrorCategory errorCategory,
            String reason) {
        ProcessorErrorCategory category = errorCategory != null
                ? errorCategory
                : ProcessorErrorCategory.RuntimeExecutionFailure;
        execution.fail(
                ProcessorStatus.RUNTIME_FATAL,
                ProcessorDiagnostic.builder(category)
                        .message(reason)
                        .detail(
                                ProcessorDiagnosticConstants.FIELD_SCOPE_PATH,
                                ProcessorEngine.normalizeScope(scopePath))
                        .build());
        throw new RunTerminationException(reason);
    }

    void markCutOff(String scopePath) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        if (JsonPointer.ROOT.equals(normalized)) {
            return;
        }
        if (cutOffScopes.add(normalized)) {
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.SCOPE_CUT_OFF,
                    normalized,
                    null,
                    normalized);
            for (Map.Entry<String, ScopeRuntimeContext> entry
                    : runtime.scopes().entrySet()) {
                if (PointerUtils.descendantOrEqual(
                        entry.getKey(),
                        normalized)) {
                    entry.getValue().markCutOff();
                }
            }
        }
    }

    void deliverLifecycle(
            String scopePath,
            ContractBundle bundle,
            Node event,
            boolean finalizeAfter) {
        scopeExecutor.deliverLifecycle(
                scopePath,
                bundle,
                event,
                finalizeAfter);
    }

    void deliverTerminationLifecycle(
            String scopePath,
            ContractBundle bundle,
            Node event) {
        scopeExecutor.deliverTerminationLifecycle(
                scopePath,
                bundle,
                event);
    }

    void enqueueApplicationEvent(
            String scopePath,
            String contractKey,
            Node event,
            String eventBlueId) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        ScopeRuntimeContext source = runtime.scope(normalized);
        EventOccurrence occurrence = new EventOccurrence(
                event,
                eventBlueId,
                source,
                source.freezeAncestorChain(),
                EventOccurrence.SourceMode.TRIGGERED,
                contractKey);
        runtime.chargeEmitEvent(event);
        runtime.enqueueEventOccurrence(occurrence);
        runtime.recordTrace(
                ProcessingTraceRecord.Kind.EVENT_ENQUEUED,
                normalized,
                contractKey,
                null,
                Collections.emptyMap(),
                event);
        if (JsonPointer.ROOT.equals(normalized)) {
            runtime.chargeRootEventRecorded();
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.ROOT_EVENT,
                    normalized,
                    contractKey,
                    null,
                    Collections.emptyMap(),
                    event);
            runtime.recordRootEmission(event.clone());
        }
    }

    void drainInternalEvents() {
        scopeExecutor.drainInternalEvents();
    }

    void requestInternalEventDrain() {
        scopeExecutor.requestInternalEventDrain();
    }

    void completePendingTerminations() {
        terminationService.completePendingTerminations(execution);
    }

    private boolean isUnderCutOffScope(String scopePath) {
        for (String cutOff : cutOffScopes) {
            if (PointerUtils.descendantOrEqual(scopePath, cutOff)) {
                return true;
            }
        }
        return false;
    }
}
