package blue.language.processor;

/** Rejects opaque embedded boundaries before unrelated provider demand. */
final class ParticipatingClosurePreflight {

    static final ProcessingPhaseContract CONTRACT =
            new ProcessingPhaseContract(
                    ProcessingPhaseState.Stage.CLOSURE_PREFLIGHTED,
                    ProcessingPhaseContract.GasBehavior.NONE,
                    ProcessingPhaseContract.ProviderDemand.NONE,
                    ProcessorErrorCategory.CyclicSetEmbeddedBoundaryUnsupported,
                    false);

    ProcessingPhaseState execute(ProcessingPhaseState input) {
        input.session().preflightOpaqueEmbeddedBoundaries();
        return input.advance(
                ProcessingPhaseState.Stage.EVIDENCE_VERIFIED,
                CONTRACT.stage());
    }
}
