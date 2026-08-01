package blue.language.processor;

/** Freezes and validates the complete accepted participating scope closure. */
final class ScopeInitialization {

    static final ProcessingPhaseContract CONTRACT =
            new ProcessingPhaseContract(
                    ProcessingPhaseState.Stage.SCOPES_INITIALIZED,
                    ProcessingPhaseContract.GasBehavior.CHARGE_BEFORE_WORK,
                    ProcessingPhaseContract.ProviderDemand.PARTICIPATING_CLOSURE_ONLY,
                    ProcessorErrorCategory.InvalidContractBinding,
                    true);

    ProcessingPhaseState execute(ProcessingPhaseState input) {
        input.session().preflightParticipatingClosure();
        return input.advance(
                ProcessingPhaseState.Stage.EXTERNAL_DELIVERIES_CLASSIFIED,
                CONTRACT.stage());
    }
}
