package blue.language.processor;

import blue.language.model.Node;

/**
 * Specification-ordered orchestration of one already admitted PROCESS call.
 *
 * <p>Each phase accepts and returns an immutable hand-off value. Mutable
 * invocation state is reachable only through the invocation-owned execution
 * session, and every phase has a separately declared gas, provider-demand,
 * trace, and deterministic-failure contract.</p>
 */
final class ProcessingPhasePipeline {

    private final ProcessingEvidenceVerification evidence =
            new ProcessingEvidenceVerification();
    private final ParticipatingClosurePreflight closure =
            new ParticipatingClosurePreflight();
    private final ExternalDeliveryClassification classification =
            new ExternalDeliveryClassification();
    private final ScopeInitialization initialization =
            new ScopeInitialization();
    private final LogicalDeliveryExecution delivery =
            new LogicalDeliveryExecution();
    private final InternalOccurrenceDrain occurrenceDrain =
            new InternalOccurrenceDrain();
    private final FinalSoundnessValidation soundness =
            new FinalSoundnessValidation();
    private final SubscriptionDeltaValidation subscriptions =
            new SubscriptionDeltaValidation();
    private final ProcessResultAssembly resultAssembly =
            new ProcessResultAssembly();

    ProcessingDebugResult execute(
            ProcessorInvocationState execution,
            Node admittedEvent,
            Runnable evidenceVerifiedHook) {
        ProcessingSession session = new ProcessingSession(execution);
        ProcessingPhaseState state =
                ProcessingPhaseState.admitted(
                        session, admittedEvent);
        state = evidence.execute(state);
        if (evidenceVerifiedHook != null) {
            evidenceVerifiedHook.run();
        }
        if (!session.hasExecutionEvidence()) {
            throw new InvalidExecutionEvidenceException(
                    "PROCESS requires a complete external delivery plan");
        }
        state = closure.execute(state);
        state = classification.execute(state);
        state = initialization.execute(state);
        state = delivery.execute(state);
        state = occurrenceDrain.execute(state);
        state = soundness.execute(state);
        state = subscriptions.execute(state);
        return resultAssembly.execute(state);
    }
}
