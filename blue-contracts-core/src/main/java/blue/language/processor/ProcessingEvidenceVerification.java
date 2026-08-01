package blue.language.processor;

/** Admits the already bound feeder evidence and its exact gas/trace prefix. */
final class ProcessingEvidenceVerification {

    static final ProcessingPhaseContract CONTRACT =
            new ProcessingPhaseContract(
                    ProcessingPhaseState.Stage.EVIDENCE_VERIFIED,
                    ProcessingPhaseContract.GasBehavior.CHARGE_BEFORE_WORK,
                    ProcessingPhaseContract.ProviderDemand.EXACT_BOUND_INPUTS,
                    ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                    true);

    ProcessingPhaseState execute(ProcessingPhaseState input) {
        input.session().admitEvidence();
        return input.advance(
                ProcessingPhaseState.Stage.INPUT_ADMITTED,
                CONTRACT.stage());
    }
}
