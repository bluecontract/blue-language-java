package blue.language.processor;

/** Projects and validates the final Root subscription delta. */
final class SubscriptionDeltaValidation {

    static final ProcessingPhaseContract CONTRACT =
            new ProcessingPhaseContract(
                    ProcessingPhaseState.Stage.SUBSCRIPTION_DELTA_VALIDATED,
                    ProcessingPhaseContract.GasBehavior.CARRY_ADMITTED_PREFIX,
                    ProcessingPhaseContract.ProviderDemand.PARTICIPATING_CLOSURE_ONLY,
                    ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                    true);

    ProcessingPhaseState execute(ProcessingPhaseState input) {
        input.session().validateSubscriptionDelta();
        return input.advance(
                ProcessingPhaseState.Stage.SOUNDNESS_VALIDATED,
                CONTRACT.stage());
    }
}
