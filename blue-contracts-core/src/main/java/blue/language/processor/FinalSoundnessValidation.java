package blue.language.processor;

/** Applies processor-owned cleanup and final transactional soundness checks. */
final class FinalSoundnessValidation {

    static final ProcessingPhaseContract CONTRACT =
            new ProcessingPhaseContract(
                    ProcessingPhaseState.Stage.SOUNDNESS_VALIDATED,
                    ProcessingPhaseContract.GasBehavior.CARRY_ADMITTED_PREFIX,
                    ProcessingPhaseContract.ProviderDemand.NONE,
                    ProcessorErrorCategory.RuntimeExecutionFailure,
                    true);

    ProcessingPhaseState execute(ProcessingPhaseState input) {
        input.session().validateFinalSoundness();
        return input.advance(
                ProcessingPhaseState.Stage.INTERNAL_OCCURRENCES_DRAINED,
                CONTRACT.stage());
    }
}
