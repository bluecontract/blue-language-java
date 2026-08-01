package blue.language.processor;

/** Drains the invocation-wide occurrence FIFO after external execution. */
final class InternalOccurrenceDrain {

    static final ProcessingPhaseContract CONTRACT =
            new ProcessingPhaseContract(
                    ProcessingPhaseState.Stage.INTERNAL_OCCURRENCES_DRAINED,
                    ProcessingPhaseContract.GasBehavior.CHARGE_BEFORE_WORK,
                    ProcessingPhaseContract.ProviderDemand.PARTICIPATING_CLOSURE_ONLY,
                    ProcessorErrorCategory.RuntimeExecutionFailure,
                    true);

    ProcessingPhaseState execute(ProcessingPhaseState input) {
        input.session().drainInternalOccurrences();
        return input.advance(
                ProcessingPhaseState.Stage.LOGICAL_DELIVERIES_EXECUTED,
                CONTRACT.stage());
    }
}
