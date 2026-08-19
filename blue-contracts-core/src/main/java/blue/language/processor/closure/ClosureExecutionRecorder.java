package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Invocation-local mutable recorder with one immutable publication point. */
final class ClosureExecutionRecorder {

    private final String invocationIdentity;
    private final List<ClosureWorkOccurrence> work =
            new ArrayList<ClosureWorkOccurrence>();
    private final List<DocumentStepEvidence> steps =
            new ArrayList<DocumentStepEvidence>();
    private final List<TentativeFinalization> finalizations =
            new ArrayList<TentativeFinalization>();

    ClosureExecutionRecorder(String invocationIdentity) {
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
    }

    void accepted(ClosureWorkOccurrence occurrence) {
        ClosureWorkOccurrence selected = Objects.requireNonNull(
                occurrence, "occurrence");
        if (selected.ordinal() != work.size()) {
            throw new IllegalArgumentException(
                    "Accepted work occurrence ordinal is not contiguous");
        }
        work.add(selected);
    }

    void step(DocumentStepInput input) {
        DocumentStepEvidence evidence = new DocumentStepEvidence(input);
        if (evidence.stepOrdinal() != steps.size()) {
            throw new IllegalArgumentException(
                    "Document-step ordinal is not contiguous");
        }
        steps.add(evidence);
    }

    void finalization(TentativeFinalization finalization) {
        TentativeFinalization selected = Objects.requireNonNull(
                finalization, "finalization");
        if (selected.ordinal() != finalizations.size()) {
            throw new IllegalArgumentException(
                    "Tentative-finalization ordinal is not contiguous");
        }
        finalizations.add(selected);
    }

    long finalizationCount() {
        return finalizations.size();
    }

    ClosureImplementationEvidence snapshot(String nonConformanceCode) {
        return new ClosureImplementationEvidence(
                invocationIdentity,
                work,
                steps,
                finalizations,
                nonConformanceCode);
    }
}
