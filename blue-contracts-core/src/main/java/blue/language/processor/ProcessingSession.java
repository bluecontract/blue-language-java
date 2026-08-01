package blue.language.processor;

import blue.language.model.Node;

import java.util.Objects;

/**
 * Composition root for exactly one PROCESS invocation.
 *
 * <p>The session is never shared between invocations. It exposes named state
 * owners to the phase pipeline while the legacy execution adapter remains an
 * implementation detail during API migration.</p>
 */
final class ProcessingSession {

    private final ProcessorInvocationState execution;
    private final ProcessingDocumentView documentView;
    private final ProcessingMutationSession mutationSession;
    private final ProcessingEventQueue eventQueue;
    private final ProcessingLifecycleState lifecycleState;
    private final ProcessingCheckpointTransaction checkpointTransaction;
    private final ProcessingGasContext gasContext;
    private final ProcessingScopeRegistry scopeRegistry;
    private final ProcessingOutputCollector outputCollector;
    private final ProcessingCutoffTracker cutoffTracker;
    private final ProcessingSnapshotTransaction snapshotTransaction;

    ProcessingSession(ProcessorInvocationState execution) {
        this.execution = Objects.requireNonNull(execution, "execution");
        DocumentProcessingRuntime runtime = execution.runtime();
        this.documentView = runtime.documentViewComponent();
        this.mutationSession = runtime.mutationSessionComponent();
        this.eventQueue = runtime.eventQueueComponent();
        this.lifecycleState = runtime.lifecycleStateComponent();
        this.checkpointTransaction = execution.checkpointTransaction();
        this.gasContext = runtime.gasContextComponent();
        this.scopeRegistry = runtime.scopeRegistryComponent();
        this.outputCollector = runtime.outputCollectorComponent();
        this.cutoffTracker = new ProcessingCutoffTracker(execution);
        this.snapshotTransaction = runtime.snapshotTransactionComponent();
    }

    void admitEvidence() {
        execution.admitEvidence();
    }

    boolean hasExecutionEvidence() {
        return execution.hasExecutionEvidence();
    }

    void preflightOpaqueEmbeddedBoundaries() {
        execution.preflightOpaqueProcessEmbeddedBoundaries();
    }

    void classifyExternalDeliveries(Node event) {
        execution.classifyExternalDeliveries(event);
    }

    void preflightParticipatingClosure() {
        execution.preflightParticipatingClosure();
    }

    void executeLogicalDeliveries() {
        execution.prepareLogicalDeliveries();
        execution.executeLogicalDeliveries();
    }

    void drainInternalOccurrences() {
        execution.drainInternalEvents();
    }

    void validateFinalSoundness() {
        execution.performFinalSoundnessValidation();
    }

    void validateSubscriptionDelta() {
        execution.validateSubscriptionDelta();
    }

    ProcessingDebugResult assembleResult() {
        return execution.debugResult();
    }

    ProcessingDocumentView documentView() {
        return documentView;
    }

    ProcessingMutationSession mutationSession() {
        return mutationSession;
    }

    ProcessingEventQueue eventQueue() {
        return eventQueue;
    }

    ProcessingLifecycleState lifecycleState() {
        return lifecycleState;
    }

    ProcessingGasContext gasContext() {
        return gasContext;
    }

    ProcessingScopeRegistry scopeRegistry() {
        return scopeRegistry;
    }

    ProcessingOutputCollector outputCollector() {
        return outputCollector;
    }

    ProcessingCutoffTracker cutoffTracker() {
        return cutoffTracker;
    }

    ProcessingSnapshotTransaction snapshotTransaction() {
        return snapshotTransaction;
    }
}
