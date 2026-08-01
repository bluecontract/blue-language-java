package blue.language.processor;

/** Assembles the closed five-field result and its out-of-band debug companion. */
final class ProcessResultAssembly {

    ProcessingDebugResult execute(ProcessingPhaseState input) {
        if (input.stage()
                != ProcessingPhaseState.Stage.SUBSCRIPTION_DELTA_VALIDATED) {
            throw new IllegalStateException(
                    "PROCESS result assembled before final validation");
        }
        return input.session().assembleResult();
    }
}
