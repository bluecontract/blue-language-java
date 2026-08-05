package blue.language.processor;

/** Read-only classification of every feeder-admitted source occurrence. */
final class ExternalDeliveryClassification {

    static final ProcessingPhaseContract CONTRACT =
            new ProcessingPhaseContract(
                    ProcessingPhaseState.Stage.EXTERNAL_DELIVERIES_CLASSIFIED,
                    ProcessingPhaseContract.GasBehavior.CHARGE_BEFORE_WORK,
                    ProcessingPhaseContract.ProviderDemand.PARTICIPATING_CLOSURE_ONLY,
                    ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                    true);

    ProcessingPhaseState execute(ProcessingPhaseState input) {
        input.session().classifyExternalDeliveries(input.event());
        return input.advance(
                ProcessingPhaseState.Stage.CLOSURE_PREFLIGHTED,
                CONTRACT.stage());
    }
}
