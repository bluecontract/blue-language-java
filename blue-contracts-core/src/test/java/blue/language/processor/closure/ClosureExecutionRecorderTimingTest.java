package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Deterministic timing-accounting proof for implementation evidence. */
final class ClosureExecutionRecorderTimingTest {

    private static final String INVOCATION_IDENTITY =
            "sha256:1111111111111111111111111111111111111111111111111111111111111111";

    @Test
    void separatesNestedFinalizationFromInclusiveManagedStepTime() {
        ClosureExecutionRecorder recorder = new ClosureExecutionRecorder(
                INVOCATION_IDENTITY,
                new SequenceClock(
                        10L, 20L, 50L, 90L,
                        100L, 120L, 130L, 170L));

        long stepStarted = recorder.beginManagedDocumentStep();
        long nestedFinalizationStarted =
                recorder.beginComponentFinalizationProof();
        recorder.endComponentFinalizationProof(
                nestedFinalizationStarted);
        recorder.endManagedDocumentStep(stepStarted);

        long outsideFinalizationStarted =
                recorder.beginComponentFinalizationProof();
        recorder.endComponentFinalizationProof(
                outsideFinalizationStarted);

        long assemblyStarted =
                recorder.beginSuccessfulResultAssembly();
        recorder.endSuccessfulResultAssembly(assemblyStarted, true);

        ClosureImplementationEvidence evidence = recorder.snapshot(null);
        assertEquals(80L,
                evidence.managedDocumentStepInclusiveNanos());
        assertEquals(50L,
                evidence.managedDocumentStepExclusiveNanos());
        assertEquals(50L,
                evidence.componentFinalizationProofNanos());
        assertEquals(40L,
                evidence.successfulResultAssemblyNanos());
    }

    @Test
    void clampsRegressingClockAndDoesNotCountFailedAssembly() {
        ClosureExecutionRecorder recorder = new ClosureExecutionRecorder(
                INVOCATION_IDENTITY,
                new SequenceClock(100L, 90L, 80L, 70L, 60L, 50L));

        long stepStarted = recorder.beginManagedDocumentStep();
        long finalizationStarted =
                recorder.beginComponentFinalizationProof();
        recorder.endComponentFinalizationProof(finalizationStarted);
        recorder.endManagedDocumentStep(stepStarted);
        long assemblyStarted =
                recorder.beginSuccessfulResultAssembly();
        recorder.endSuccessfulResultAssembly(assemblyStarted, false);

        ClosureImplementationEvidence evidence = recorder.snapshot(null);
        assertEquals(0L,
                evidence.managedDocumentStepInclusiveNanos());
        assertEquals(0L,
                evidence.managedDocumentStepExclusiveNanos());
        assertEquals(0L,
                evidence.componentFinalizationProofNanos());
        assertEquals(0L,
                evidence.successfulResultAssemblyNanos());
    }

    private static final class SequenceClock implements LongSupplier {
        private final long[] values;
        private int index;

        private SequenceClock(long... values) {
            this.values = values.clone();
        }

        @Override
        public long getAsLong() {
            if (index >= values.length) {
                throw new AssertionError("Timing test exhausted its clock");
            }
            return values[index++];
        }
    }
}
