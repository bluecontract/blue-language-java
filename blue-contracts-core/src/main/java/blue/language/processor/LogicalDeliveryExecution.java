package blue.language.processor;

/** Groups equivalent sources and executes each logical delivery once. */
final class LogicalDeliveryExecution {

    static final ProcessingPhaseContract CONTRACT =
            new ProcessingPhaseContract(
                    ProcessingPhaseState.Stage.LOGICAL_DELIVERIES_EXECUTED,
                    ProcessingPhaseContract.GasBehavior.CARRY_ADMITTED_PREFIX,
                    ProcessingPhaseContract.ProviderDemand.PARTICIPATING_CLOSURE_ONLY,
                    ProcessorErrorCategory.InconsistentLogicalDelivery,
                    true);

    ProcessingPhaseState execute(ProcessingPhaseState input) {
        input.session().executeLogicalDeliveries();
        return input.advance(
                ProcessingPhaseState.Stage.SCOPES_INITIALIZED,
                CONTRACT.stage());
    }
}
