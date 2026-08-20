package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ProcessorErrorCategory;

import java.util.List;
import java.util.Objects;

/** Executes the released Phase-A semantic-rejection admission lane. */
final class ClosureAdmissionRejectionProcessor {

    private static final ManagedDocumentStepContinuation NO_EFFECTS =
            new ManagedDocumentStepContinuation() {
                @Override
                public void afterPatch(
                        String scopePath,
                        Node currentDocument,
                        FrozenJsonPatch patch,
                        List<DocumentUpdateOccurrence> updates) {
                    throw new IllegalStateException(
                            "Admission verification cannot apply patches");
                }

                @Override
                public void onApplicationEvent(
                        String scopePath,
                        String originContractKey,
                        Node event,
                        String eventBlueId) {
                    throw new IllegalStateException(
                            "Admission verification cannot emit events");
                }

                @Override
                public void onTerminationRequested(
                        String scopePath,
                        String cause,
                        String reason) {
                    throw new IllegalStateException(
                            "Admission verification cannot terminate documents");
                }
            };

    private ClosureAdmissionRejectionProcessor() {
    }

    static boolean supports(
            ClosureInvocationInput input,
            ClosureInvocationVerifier.Verification verification) {
        requireRejectedAdmission(input, verification);
        return new AdmissionCandidateGasVerifier()
                .supportsReleasedRejection(input);
    }

    static Rejection reject(
            DocumentProcessor owner,
            ClosureInvocationInput input,
            ClosureInvocationVerifier.Verification verification) {
        requireRejectedAdmission(input, verification);
        AdmissionCandidateGasVerifier verifier =
                new AdmissionCandidateGasVerifier();
        if (!verifier.supportsReleasedRejection(input)) {
            throw new IllegalArgumentException(
                    "Admission candidate is outside the released semantic-rejection lane");
        }
        try (ManagedDocumentStepProcessor meter =
                     new ManagedDocumentStepProcessor(
                             Objects.requireNonNull(owner, "owner"),
                             input.executionPolicy(),
                             NO_EFFECTS)) {
            ProcessorErrorCategory category =
                    verifier.verifyReleasedRejection(input, meter);
            ClosureAttemptResult attempt =
                    ClosureAdmissionRollbackAssembler.reject(
                            input,
                            meter.processorGasTrace(),
                            category,
                            message(category));
            return new Rejection(attempt, category.name());
        }
    }

    private static void requireRejectedAdmission(
            ClosureInvocationInput input,
            ClosureInvocationVerifier.Verification verification) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(verification, "verification");
        if (input.operation()
                    != ClosureInvocationInput.Operation.ADMIT_CLOSURE
                || input.admissionCandidate() == null
                || verification.candidateDisposition()
                    != ClosureInvocationVerifier.CandidateDisposition
                            .SEMANTICALLY_INVALID
                || verification.candidateKind()
                    != input.admissionCandidate().kind()) {
            throw new IllegalArgumentException(
                    "Released rejection requires one verified invalid admission candidate");
        }
    }

    private static String message(ProcessorErrorCategory category) {
        switch (category) {
            case CyclicSetProofInvalid:
                return "Candidate cyclic-set proof does not match the exact component";
            case CyclicPreliminaryMemberAmbiguous:
                return "Candidate cyclic members have indistinguishable preliminary identity input";
            case ManagedOccurrenceBindingMissing:
                return "Candidate managed occurrence is absent at its exact source path";
            default:
                throw new AssertionError(
                        "Unexpected admission diagnostic " + category);
        }
    }

    /** Exact completed rejection plus its stable evidence diagnostic. */
    static final class Rejection {
        private final ClosureAttemptResult attempt;
        private final String diagnosticCode;

        private Rejection(
                ClosureAttemptResult attempt,
                String diagnosticCode) {
            this.attempt = Objects.requireNonNull(attempt, "attempt");
            this.diagnosticCode = Objects.requireNonNull(
                    diagnosticCode, "diagnosticCode");
        }

        ClosureAttemptResult attempt() {
            return attempt;
        }

        String diagnosticCode() {
            return diagnosticCode;
        }
    }
}
