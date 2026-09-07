package blue.language.conformance.contracts.closure;

import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.closure.ResultingDocument;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normative C-CLO-23 sequence and causal epoch classification. */
final class Cclo23HistoricalEpochMatrixTest {

    private static final DocumentId A = new DocumentId("history-a");
    private static final DocumentId B = new DocumentId("history-b");
    private static final String FINAL_MASTER =
            "AqxN3nEKymbTyHrfjFhHcEH35YRyz3Ggcoch3dEzMkH5";

    private static final List<Row> MATRIX = Arrays.asList(
            new Row("c-clo-23-00-attach-a5-retry", null, null,
                    10L, 10L, 0L, 1L, 5L, false),
            new Row("c-clo-23-01-a5-to-a6", 5L, 6L,
                    10L, 10L, 1L, 2L, 6L, false),
            new Row("c-clo-23-02-a6-to-a7", 6L, 7L,
                    10L, 10L, 2L, 3L, 7L, false),
            new Row("c-clo-23-03-a7-to-a8", 7L, 8L,
                    10L, 10L, 3L, 4L, 8L, false),
            new Row("c-clo-23-04-a8-to-a9", 8L, 9L,
                    10L, 10L, 4L, 5L, 9L, false),
            new Row("c-clo-23-05-a9-to-a10", 9L, 10L,
                    10L, 10L, 5L, 6L, null, true));

    @Test
    void executesContiguousSequenceWithCausalEpochClassification() {
        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();
        ClosureProcessResult previous = null;

        for (Row row : MATRIX) {
            ClosureFixtureInventory.Entry entry = requireEntry(
                    source, row.fixtureId);
            JsonNode executionFixture = source.executionFixture(entry);
            ClosureInvocationInput input = new ClosureFixtureParser()
                    .parse(entry, executionFixture)
                    .admit();
            if (previous != null) {
                assertEquals(previous.outputClosureIdentity(),
                        input.snapshot().closureIdentity(),
                        row.fixtureId + " contiguous input closure");
                assertPreviousDocuments(previous, input, row.fixtureId);
            }

            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (ClosureFixtureRuntime runtime =
                         ClosureFixtureRuntime.fromFixture(executionFixture);
                 BlueClosureContracts contracts = new BlueClosureContracts(
                         runtime.processor(), capture)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete(), row.fixtureId);
            ClosureProcessResult result = attempt.processResult();
            assertNotNull(result, row.fixtureId);
            assertEquals(ProcessorStatus.SUCCESS, result.status(),
                    row.fixtureId + ": " + diagnostic(result));
            assertTrue(result.commits(), row.fixtureId);
            assertNotNull(capture.evidence, row.fixtureId);
            assertEquals(1, capture.evidence.workTrace().size(),
                    row.fixtureId);
            assertEquals(B, capture.evidence.workTrace().get(0)
                    .targetDocumentId(), row.fixtureId);

            ManagedDocumentSnapshot beforeA =
                    input.snapshot().managedDocument(A);
            ManagedDocumentSnapshot beforeB =
                    input.snapshot().managedDocument(B);
            ResultingDocument afterA = resultingDocument(result, A);
            ResultingDocument afterB = resultingDocument(result, B);
            assertEquals(row.inputAEpoch, beforeA.epoch(), row.fixtureId);
            assertEquals(row.outputAEpoch, afterA.epoch(),
                    row.fixtureId + " finalizer-only A re-encoding");
            assertEquals(row.inputBEpoch, beforeB.epoch(), row.fixtureId);
            assertEquals(row.outputBEpoch, afterB.epoch(),
                    row.fixtureId + " locally staged B change");

            // A changes exactly because its active /b reference is rebuilt
            // after B changes.  No independently authored A field changes.
            assertOnlyPropertyChanged(
                    beforeA.document(), afterA.document(), "b", row.fixtureId);
            assertNotEquals(beforeB.blueId(), afterB.afterBlueId(),
                    row.fixtureId + " B exact local body");

            ManagedOccurrenceBinding historical = bindingAt(
                    result, B, "/a");
            assertEquals(row.pendingEpoch,
                    historical.pendingHistoricalEpoch(), row.fixtureId);
            assertEquals(row.active, historical.active(), row.fixtureId);
            assertEquals(row.active ? 2L : 1L,
                    result.graphGeneration(), row.fixtureId);
            if (row.active) {
                assertEquals(1, result.resultingComponents().size(),
                        row.fixtureId);
                assertEquals(ComponentKind.CYCLIC,
                        result.resultingComponents().get(0).kind(),
                        row.fixtureId);
                assertEquals(FINAL_MASTER,
                        result.resultingComponents().get(0).masterBlueId(),
                        row.fixtureId);
                assertEquals(FINAL_MASTER + "#0", afterA.afterBlueId(),
                        row.fixtureId);
                assertEquals(FINAL_MASTER + "#1", afterB.afterBlueId(),
                        row.fixtureId);
            } else {
                assertEquals(2, result.resultingComponents().size(),
                        row.fixtureId);
                assertEquals(ComponentKind.ACYCLIC,
                        result.resultingComponents().get(0).kind(),
                        row.fixtureId);
                assertEquals(ComponentKind.ACYCLIC,
                        result.resultingComponents().get(1).kind(),
                        row.fixtureId);
            }

            if (row.fromEpoch == null) {
                assertFalse(input.cause() instanceof ManagedRevisionCause,
                        row.fixtureId);
                assertNull(NodePathEditor.getOrNull(
                        beforeB.document(), "/a"), row.fixtureId);
                assertNotNull(NodePathEditor.getOrNull(
                        afterB.document(), "/a"), row.fixtureId);
            } else {
                assertTrue(input.cause() instanceof ManagedRevisionCause,
                        row.fixtureId);
                ManagedRevisionCause cause =
                        (ManagedRevisionCause) input.cause();
                assertEquals(row.fromEpoch.longValue(), cause.fromEpoch(),
                        row.fixtureId);
                assertEquals(row.toEpoch.longValue(), cause.toEpoch(),
                        row.fixtureId);
                assertEquals(A, cause.childDocumentId(), row.fixtureId);
                assertEquals(B, historical.sourceDocumentId(), row.fixtureId);
                assertEquals(row.active,
                        cause.toEpoch() == row.inputAEpoch, row.fixtureId);
            }
            previous = result;
        }
    }

    @Test
    void shouldAdvanceOrdinaryFinalizerReencodingOutsideHistoricalCatchUp() {
        // given
        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();
        ClosureFixtureInventory.Entry entry = requireEntry(
                source, "c-clo-09-merge-two-cycles");
        JsonNode executionFixture = source.executionFixture(entry);
        ClosureInvocationInput input = new ClosureFixtureParser()
                .parse(entry, executionFixture)
                .admit();

        // when
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(executionFixture);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor(), capture)) {
            attempt = contracts.processClosure(input);
        }

        // then
        assertTrue(attempt.isComplete(), entry.id());
        ClosureProcessResult result = attempt.processResult();
        assertNotNull(result, entry.id());
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                entry.id() + ": " + diagnostic(result));
        assertNotNull(capture.evidence, entry.id());
        assertEquals(1, capture.evidence.workTrace().size(), entry.id());
        assertEquals(new DocumentId("ma"), capture.evidence.workTrace()
                .get(0).targetDocumentId(), entry.id());

        for (String value : Arrays.asList("ma", "mb", "mc", "md")) {
            DocumentId documentId = new DocumentId(value);
            ManagedDocumentSnapshot before = input.snapshot()
                    .managedDocument(documentId);
            ResultingDocument after = resultingDocument(result, documentId);
            assertNotEquals(NodeWireForm.get(before.document()),
                    NodeWireForm.get(after.document()),
                    entry.id() + " changed body " + value);
            assertEquals(before.epoch() + 1L, after.epoch(),
                    entry.id() + " changed epoch " + value);
        }
    }

    private static void assertPreviousDocuments(
            ClosureProcessResult previous,
            ClosureInvocationInput input,
            String fixtureId) {
        for (DocumentId documentId : Arrays.asList(A, B)) {
            ResultingDocument prior = resultingDocument(previous, documentId);
            ManagedDocumentSnapshot current = input.snapshot()
                    .managedDocument(documentId);
            assertEquals(prior.afterBlueId(), current.blueId(), fixtureId);
            assertEquals(NodeWireForm.get(prior.document()),
                    NodeWireForm.get(current.document()), fixtureId);
            assertEquals(prior.epoch(), current.epoch(), fixtureId);
        }
    }

    private static void assertOnlyPropertyChanged(
            Node before,
            Node after,
            String property,
            String fixtureId) {
        assertNotEquals(NodeWireForm.get(before), NodeWireForm.get(after),
                fixtureId + " exact body must change");
        Node beforeWithout = before.clone();
        Node afterWithout = after.clone();
        assertNotNull(beforeWithout.getProperties(), fixtureId);
        assertNotNull(afterWithout.getProperties(), fixtureId);
        assertNotNull(beforeWithout.getProperties().remove(property), fixtureId);
        assertNotNull(afterWithout.getProperties().remove(property), fixtureId);
        assertEquals(NodeWireForm.get(beforeWithout),
                NodeWireForm.get(afterWithout),
                fixtureId + " only /" + property + " may change");
    }

    private static ClosureFixtureInventory.Entry requireEntry(
            ClosureFixtureCorpusSource source,
            String fixtureId) {
        for (ClosureFixtureInventory.Entry entry : source.entries()) {
            if (fixtureId.equals(entry.id())) {
                return entry;
            }
        }
        throw new AssertionError("Missing fixture: " + fixtureId);
    }

    private static ResultingDocument resultingDocument(
            ClosureProcessResult result,
            DocumentId documentId) {
        for (ResultingDocument document : result.resultingDocuments()) {
            if (document.documentId().equals(documentId)) {
                return document;
            }
        }
        throw new AssertionError("Missing resulting document: " + documentId);
    }

    private static ManagedOccurrenceBinding bindingAt(
            ClosureProcessResult result,
            DocumentId source,
            String path) {
        for (ManagedOccurrenceBinding binding : result.occurrenceBindings()) {
            if (binding.sourceDocumentId().equals(source)
                    && binding.sourcePath().equals(path)) {
                return binding;
            }
        }
        throw new AssertionError("Missing binding: " + source + ':' + path);
    }

    private static String diagnostic(ClosureProcessResult result) {
        return result.diagnostic() == null
                ? "no diagnostic"
                : result.diagnostic().category() + ": "
                        + result.diagnostic().message();
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            evidence = value;
        }
    }

    private static final class Row {
        private final String fixtureId;
        private final Long fromEpoch;
        private final Long toEpoch;
        private final long inputAEpoch;
        private final long outputAEpoch;
        private final long inputBEpoch;
        private final long outputBEpoch;
        private final Long pendingEpoch;
        private final boolean active;

        private Row(
                String fixtureId,
                Long fromEpoch,
                Long toEpoch,
                long inputAEpoch,
                long outputAEpoch,
                long inputBEpoch,
                long outputBEpoch,
                Long pendingEpoch,
                boolean active) {
            this.fixtureId = fixtureId;
            this.fromEpoch = fromEpoch;
            this.toEpoch = toEpoch;
            this.inputAEpoch = inputAEpoch;
            this.outputAEpoch = outputAEpoch;
            this.inputBEpoch = inputBEpoch;
            this.outputBEpoch = outputBEpoch;
            this.pendingEpoch = pendingEpoch;
            this.active = active;
        }
    }
}
