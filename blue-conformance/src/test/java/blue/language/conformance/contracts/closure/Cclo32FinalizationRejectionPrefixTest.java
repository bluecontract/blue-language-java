package blue.language.conformance.contracts.closure;

import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.WorkKind;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact executed-work prefix at the C-CLO-32 gas boundary. */
final class Cclo32FinalizationRejectionPrefixTest {

    private static final String ID =
            "c-clo-32-finalization-owned-gas-rejection";

    @Test
    void recordsOnlyWorkReachedBeforeFinalizationGasRejection() {
        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();
        ClosureFixtureInventory.Entry entry = entry(source);

        JsonNode executionFixture = source.executionFixture(entry);
        ClosureInvocationInput input = new ClosureFixtureParser()
                .parse(entry, executionFixture)
                .admit();
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(executionFixture);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor(), capture)) {
            attempt = contracts.admitClosure(input);
        }

        assertTrue(attempt.isComplete());
        assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                attempt.processResult().status());
        assertFalse(attempt.processResult().commits());
        assertEquals(1218L, attempt.processResult().totalGas());
        assertNotNull(capture.evidence);

        List<ClosureWorkOccurrence> work = capture.evidence.workTrace();
        List<DocumentStepEvidence> steps =
                capture.evidence.documentStepTrace();
        assertEquals(1, work.size());
        assertEquals(work.size(), steps.size());
        assertEquals(0L, work.get(0).ordinal());
        assertEquals(WorkKind.INITIALIZATION, work.get(0).kind());
        assertEquals("simple-a", work.get(0).targetDocumentId().value());
        assertEquals(0L, steps.get(0).stepOrdinal());
        assertEquals(work.get(0).ordinal(), steps.get(0).workOrdinal());
        assertEquals(work.get(0).targetDocumentId(),
                steps.get(0).targetDocumentId());
        assertEquals(work.get(0).targetDocumentId(),
                steps.get(0).executionRootDocumentId());
        assertTrue(capture.evidence.tentativeFinalizations().isEmpty());

        List<GasTraceEntry> trace = attempt.processResult().gasTrace();
        assertEquals(21, trace.size());
        assertEquals("scopeInitialization",
                trace.get(trace.size() - 1).counter());
        assertEquals(work.get(0).workIdentity(),
                trace.get(trace.size() - 1).workOccurrenceId());
        assertEquals(1L, count(trace, "closureWorkOccurrenceEnqueued"));
        assertEquals(1L, count(trace, "closureWorkOccurrenceDequeued"));
        assertEquals(0L, count(trace, "processorMarkerWritten"));

        RejectedCharge rejected = attempt.processResult().rejectedCharge();
        assertNotNull(rejected);
        assertEquals("tentativeComponentFinalization", rejected.counter());
        assertEquals(20L, rejected.subtotal());
        assertEquals(19L, rejected.remainingBeforeCharge());
        assertEquals(RejectedCharge.Owner.Kind.FINALIZATION,
                rejected.owner().kind());
        assertEquals(Long.valueOf(0L),
                rejected.owner().finalizationOrdinal());
        assertNull(rejected.owner().workOccurrenceIdentity());
    }

    private static ClosureFixtureInventory.Entry entry(
            ClosureFixtureCorpusSource source) {
        for (ClosureFixtureInventory.Entry entry : source.entries()) {
            if (ID.equals(entry.id())) {
                return entry;
            }
        }
        throw new AssertionError("Missing staged fixture " + ID);
    }

    private static long count(
            List<GasTraceEntry> trace,
            String counter) {
        long result = 0L;
        for (GasTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                result++;
            }
        }
        return result;
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            assertNull(evidence, "one observer callback per invocation");
            evidence = value;
        }
    }
}
