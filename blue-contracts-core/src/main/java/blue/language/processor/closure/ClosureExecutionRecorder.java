package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Invocation-local mutable recorder with one immutable publication point. */
final class ClosureExecutionRecorder {

    private final String invocationIdentity;
    private final LongSupplier nanoTime;
    private final List<ClosureWorkOccurrence> work =
            new ArrayList<ClosureWorkOccurrence>();
    private final List<DocumentStepEvidence> steps =
            new ArrayList<DocumentStepEvidence>();
    private final List<TentativeFinalization> finalizations =
            new ArrayList<TentativeFinalization>();
    private long managedDocumentStepInclusiveNanos;
    private long managedDocumentStepNestedFinalizationProofNanos;
    private long componentFinalizationProofNanos;
    private long successfulResultAssemblyNanos;
    private int activeManagedDocumentSteps;

    ClosureExecutionRecorder(String invocationIdentity) {
        this(invocationIdentity, System::nanoTime);
    }

    ClosureExecutionRecorder(
            String invocationIdentity,
            LongSupplier nanoTime) {
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
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

    long beginManagedDocumentStep() {
        if (activeManagedDocumentSteps != 0) {
            throw new IllegalStateException(
                    "Managed document-step timing cannot be nested");
        }
        long started = nanoTime.getAsLong();
        activeManagedDocumentSteps = 1;
        return started;
    }

    void endManagedDocumentStep(long started) {
        if (activeManagedDocumentSteps != 1) {
            throw new IllegalStateException(
                    "Managed document-step timing is not active");
        }
        try {
            managedDocumentStepInclusiveNanos = saturatedAdd(
                    managedDocumentStepInclusiveNanos,
                    elapsedSince(started));
        } finally {
            activeManagedDocumentSteps = 0;
        }
    }

    long beginComponentFinalizationProof() {
        return nanoTime.getAsLong();
    }

    void endComponentFinalizationProof(long started) {
        long elapsed = elapsedSince(started);
        componentFinalizationProofNanos = saturatedAdd(
                componentFinalizationProofNanos,
                elapsed);
        if (activeManagedDocumentSteps != 0) {
            managedDocumentStepNestedFinalizationProofNanos = saturatedAdd(
                    managedDocumentStepNestedFinalizationProofNanos,
                    elapsed);
        }
    }

    long beginSuccessfulResultAssembly() {
        return nanoTime.getAsLong();
    }

    void endSuccessfulResultAssembly(
            long started,
            boolean successful) {
        long elapsed = elapsedSince(started);
        if (successful) {
            successfulResultAssemblyNanos = saturatedAdd(
                    successfulResultAssemblyNanos,
                    elapsed);
        }
    }

    ClosureImplementationEvidence snapshot(String nonConformanceCode) {
        return new ClosureImplementationEvidence(
                invocationIdentity,
                work,
                steps,
                finalizations,
                managedDocumentStepInclusiveNanos,
                managedDocumentStepNestedFinalizationProofNanos,
                componentFinalizationProofNanos,
                successfulResultAssemblyNanos,
                nonConformanceCode);
    }

    private long elapsedSince(long started) {
        long elapsed = nanoTime.getAsLong() - started;
        return Math.max(0L, elapsed);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE
                : left + right;
    }
}
