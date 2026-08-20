package blue.language.conformance.contracts.closure;

import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.closure.ResultingDocument;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedRevisionClosureFixtureTest {

    @Test
    void executesEachReleasedReceiptAsOneIndependentDocumentStep() {
        List<ClosureFixtureInventory.Entry> entries =
                ClosureFixtureInventory.managedRevisionProcessFixtures();
        assertEquals(5, entries.size());

        for (ClosureFixtureInventory.Entry entry : entries) {
            ClosureFixtureParser.ParsedFixture parsed =
                    new ClosureFixtureParser().parse(entry);
            ClosureInvocationInput invocation = parsed.admit();
            assertTrue(invocation.cause() instanceof ManagedRevisionCause,
                    entry.id());
            ManagedRevisionCause cause =
                    (ManagedRevisionCause) invocation.cause();
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            JsonNode fixture = ClosureFixtureInventory.readFixture(entry);
            try (ClosureFixtureRuntime runtime =
                         ClosureFixtureRuntime.fromFixture(fixture);
                 BlueClosureContracts contracts = new BlueClosureContracts(
                         runtime.processor(), capture)) {
                attempt = contracts.processClosure(invocation);
            }

            assertTrue(attempt.isComplete(), entry.id());
            ClosureProcessResult actual = attempt.processResult();
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    actual.status(),
                    entry.id() + ": " + diagnostic(actual));
            assertTrue(actual.commits(), entry.id());
            assertNotNull(capture.evidence, entry.id());
            assertTrue(capture.evidence.complete(), entry.id());
            assertEquals(1, capture.evidence.workTrace().size(), entry.id());
            assertEquals(1,
                    capture.evidence.documentStepTrace().size(), entry.id());
            DocumentStepEvidence step =
                    capture.evidence.documentStepTrace().get(0);
            assertEquals(step.targetDocumentId(),
                    step.executionRootDocumentId(), entry.id());
            assertEquals("/", step.scopePath(), entry.id());
            assertTrue(step.ambientContainingDocumentIds().isEmpty(),
                    entry.id());

            // Expected output is revealed only after the processor has
            // returned its independently assembled attempt.
            JsonNode expected = ClosureFixtureInventory.requiredObject(
                    fixture, "expected");
            assertEquals(
                    ClosureFixtureInventory.requiredText(
                            expected, "outputClosureIdentity"),
                    actual.outputClosureIdentity(), entry.id());
            assertEquals(
                    ClosureFixtureInventory.requiredLong(
                            expected, "graphGeneration"),
                    actual.graphGeneration(), entry.id());
            assertEquals(
                    ClosureFixtureInventory.requiredText(
                            expected, "graphChangesIdentity"),
                    actual.graphChangesIdentity(), entry.id());
            assertResultingDocuments(entry.id(), expected, actual);
            assertOccurrenceCursor(entry.id(), fixture, expected, actual);
            assertPerDocumentEpochs(
                    entry.id(), parsed, cause, actual);
            assertOrderedManagedRevisionTrace(
                    entry.id(), cause,
                    inputEpoch(parsed, cause.childDocumentId()),
                    actual.gasTrace());
            assertEquals(
                    entry.id().equals("c-clo-23-05-a9-to-a10")
                            ? 473L : 342L,
                    actual.totalGas(),
                    entry.id() + " gas " + traceSummary(actual));
        }
    }

    private static void assertPerDocumentEpochs(
            String fixtureId,
            ClosureFixtureParser.ParsedFixture parsed,
            ManagedRevisionCause cause,
            ClosureProcessResult actual) {
        long inputChildEpoch = inputEpoch(
                parsed, cause.childDocumentId());
        assertEquals(inputChildEpoch,
                resultingDocument(actual, cause.childDocumentId()).epoch(),
                fixtureId + " authoritative child epoch");

        ManagedOccurrenceBinding target = binding(
                actual, cause.targetOccurrenceIdentity());
        long inputSourceEpoch = inputEpoch(
                parsed, target.sourceDocumentId());
        assertEquals(inputSourceEpoch + 1L,
                resultingDocument(actual, target.sourceDocumentId()).epoch(),
                fixtureId + " source work-boundary epoch");
    }

    private static long inputEpoch(
            ClosureFixtureParser.ParsedFixture parsed,
            DocumentId documentId) {
        for (blue.language.processor.closure.ManagedDocumentSnapshot document
                : parsed.documents()) {
            if (document.documentId().equals(documentId)) {
                return document.epoch();
            }
        }
        throw new AssertionError("Missing input document: " + documentId);
    }

    private static void assertOrderedManagedRevisionTrace(
            String fixtureId,
            ManagedRevisionCause cause,
            long authoritativeChildEpoch,
            List<GasTraceEntry> trace) {
        int scope = indexOf(trace, "scopeOpened", "participating-scope");
        int patch = indexOf(
                trace, "patchAddOrReplace", "application-patch");
        int receiptReference = indexOf(
                trace,
                "containingReferenceUpdated",
                "managed-revision.receipt-reference");
        int receiptBinding = indexOf(
                trace,
                "managedOccurrenceBindingVerified",
                "managed-revision.receipt-binding");
        List<Integer> acyclic = indexesOf(
                trace,
                "tentativeComponentFinalization",
                "managed-revision.acyclic-finalization");
        assertEquals(2, acyclic.size(), fixtureId);
        int ancestorIdentity = indexOf(
                trace,
                "nodeIdentityEstablished",
                "work.0.acyclic-finalization.node-established");
        int ancestorReference = indexWithReasonPrefix(
                trace,
                "containingReferenceUpdated",
                "managed-revision.ancestor-reference.");
        assertStrictlyIncreasing(
                fixtureId,
                scope,
                patch,
                receiptReference,
                receiptBinding,
                acyclic.get(0).intValue(),
                acyclic.get(1).intValue(),
                ancestorIdentity,
                ancestorReference);

        int duplicateSourceIdentity = 0;
        for (int index = receiptBinding + 1;
                index < ancestorReference;
                index++) {
            GasTraceEntry entry = trace.get(index);
            if ("nodeIdentityEstablished".equals(entry.counter())
                    && entry.documentId() != null
                    && "history-b".equals(entry.documentId().value())) {
                duplicateSourceIdentity++;
            }
        }
        assertEquals(0, duplicateSourceIdentity,
                fixtureId + " duplicate source finalization identity");

        boolean catchesUp = cause.toEpoch() == authoritativeChildEpoch;
        if (!catchesUp) {
            assertEquals(-1, indexOfOptional(
                    trace,
                    "containingReferenceUpdated",
                    "managed-revision.authoritative-reconciliation"),
                    fixtureId);
            assertEquals(0, indexesOf(
                    trace,
                    "tentativeComponentFinalization",
                    "work.0.finalization.finalization-boundary").size(),
                    fixtureId);
            return;
        }

        int authoritativeReference = indexOf(
                trace,
                "containingReferenceUpdated",
                "managed-revision.authoritative-reconciliation");
        int activatedEdge = indexOf(
                trace,
                "processEmbeddedEdgeExamined",
                "managed-revision.topology-change");
        int activatedBinding = indexOf(
                trace,
                "managedOccurrenceBindingVerified",
                "managed-revision.topology-change");
        int partition = indexOf(
                trace,
                "componentPartitionChanged",
                "managed-revision.topology-change");
        int members = indexOf(
                trace,
                "componentMemberPartitioned",
                "managed-revision.topology-change");
        int edges = indexOf(
                trace,
                "componentEdgePartitioned",
                "managed-revision.topology-change");
        int cyclic = indexOf(
                trace,
                "tentativeComponentFinalization",
                "work.0.finalization.finalization-boundary");
        assertStrictlyIncreasing(
                fixtureId,
                ancestorReference,
                authoritativeReference,
                activatedEdge,
                activatedBinding,
                partition,
                members,
                edges,
                cyclic);
    }

    private static int indexOf(
            List<GasTraceEntry> trace,
            String counter,
            String reason) {
        int index = indexOfOptional(trace, counter, reason);
        assertTrue(index >= 0, counter + "/" + reason);
        return index;
    }

    private static int indexOfOptional(
            List<GasTraceEntry> trace,
            String counter,
            String reason) {
        for (int index = 0; index < trace.size(); index++) {
            GasTraceEntry entry = trace.get(index);
            if (counter.equals(entry.counter())
                    && reason.equals(entry.reason())) {
                return index;
            }
        }
        return -1;
    }

    private static int indexWithReasonPrefix(
            List<GasTraceEntry> trace,
            String counter,
            String prefix) {
        for (int index = 0; index < trace.size(); index++) {
            GasTraceEntry entry = trace.get(index);
            if (counter.equals(entry.counter())
                    && entry.reason() != null
                    && entry.reason().startsWith(prefix)) {
                return index;
            }
        }
        assertTrue(false, counter + "/" + prefix);
        return -1;
    }

    private static List<Integer> indexesOf(
            List<GasTraceEntry> trace,
            String counter,
            String reason) {
        ArrayList<Integer> result = new ArrayList<Integer>();
        for (int index = 0; index < trace.size(); index++) {
            GasTraceEntry entry = trace.get(index);
            if (counter.equals(entry.counter())
                    && reason.equals(entry.reason())) {
                result.add(Integer.valueOf(index));
            }
        }
        return result;
    }

    private static void assertStrictlyIncreasing(
            String fixtureId,
            int... indexes) {
        for (int index = 1; index < indexes.length; index++) {
            assertTrue(indexes[index - 1] < indexes[index],
                    fixtureId + " trace order at " + index);
        }
    }

    private static void assertResultingDocuments(
            String fixtureId,
            JsonNode expected,
            ClosureProcessResult actual) {
        JsonNode documents = ClosureFixtureInventory.requiredArray(
                expected, "resultingDocuments");
        assertEquals(documents.size(), actual.resultingDocuments().size(),
                fixtureId);
        for (JsonNode expectedDocument : documents) {
            DocumentId documentId = new DocumentId(
                    ClosureFixtureInventory.requiredText(
                            expectedDocument, "documentId"));
            ResultingDocument actualDocument = null;
            for (ResultingDocument candidate : actual.resultingDocuments()) {
                if (candidate.documentId().equals(documentId)) {
                    actualDocument = candidate;
                    break;
                }
            }
            assertNotNull(actualDocument, fixtureId + ": " + documentId);
            assertEquals(
                    ClosureFixtureInventory.requiredText(
                            expectedDocument, "afterBlueId"),
                    actualDocument.afterBlueId(), fixtureId);
            assertEquals(
                    ClosureFixtureInventory.requiredLong(
                            expectedDocument, "epoch"),
                    actualDocument.epoch(), fixtureId);
        }
    }

    private static void assertOccurrenceCursor(
            String fixtureId,
            JsonNode fixture,
            JsonNode expected,
            ClosureProcessResult actual) {
        JsonNode expectedCause = ClosureFixtureInventory.requiredObject(
                ClosureFixtureInventory.requiredObject(
                        fixture,
                        "input"),
                "cause");
        String occurrenceIdentity = ClosureFixtureInventory.requiredText(
                expectedCause, "targetOccurrenceIdentity");
        ManagedOccurrenceBinding actualBinding = null;
        for (ManagedOccurrenceBinding binding : actual.occurrenceBindings()) {
            if (binding.occurrenceIdentity().equals(occurrenceIdentity)) {
                actualBinding = binding;
                break;
            }
        }
        assertNotNull(actualBinding, fixtureId);
        JsonNode expectedBinding = null;
        for (JsonNode binding : ClosureFixtureInventory.requiredArray(
                expected, "occurrenceBindings")) {
            if (occurrenceIdentity.equals(
                    ClosureFixtureInventory.requiredText(
                            binding, "occurrenceIdentity"))) {
                expectedBinding = binding;
                break;
            }
        }
        assertNotNull(expectedBinding, fixtureId);
        assertEquals(
                ClosureFixtureInventory.requiredText(
                        expectedBinding, "bindingIdentity"),
                actualBinding.bindingIdentity(), fixtureId);
        assertEquals(
                ClosureFixtureInventory.requiredText(
                        expectedBinding, "expectedTargetBlueId"),
                actualBinding.expectedTargetBlueId(), fixtureId);
        assertEquals(
                ClosureFixtureInventory.requiredText(
                        expectedBinding, "sourceDocumentId"),
                actualBinding.sourceDocumentId().value(), fixtureId);
        assertEquals(
                ClosureFixtureInventory.requiredText(
                        expectedBinding, "sourcePath"),
                actualBinding.sourcePath(), fixtureId);
        assertEquals(
                ClosureFixtureInventory.requiredText(
                        expectedBinding, "targetDocumentId"),
                actualBinding.targetDocumentId().value(), fixtureId);
        assertEquals(
                ClosureFixtureInventory.requiredLong(
                        expectedBinding, "activationGeneration"),
                actualBinding.activationGeneration(), fixtureId);
        assertEquals(
                ClosureFixtureInventory.requiredBoolean(
                        expectedBinding, "active"),
                actualBinding.active(), fixtureId);
        JsonNode pending = expectedBinding.get("pendingHistoricalEpoch");
        if (pending == null || pending.isNull()) {
            assertFalse(actualBinding.pendingHistoricalEpoch() != null,
                    fixtureId);
        } else {
            assertEquals(Long.valueOf(pending.longValue()),
                    actualBinding.pendingHistoricalEpoch(), fixtureId);
        }
    }

    private static ManagedOccurrenceBinding binding(
            ClosureProcessResult actual,
            String occurrenceIdentity) {
        for (ManagedOccurrenceBinding candidate
                : actual.occurrenceBindings()) {
            if (candidate.occurrenceIdentity().equals(
                    occurrenceIdentity)) {
                return candidate;
            }
        }
        throw new AssertionError(
                "Missing occurrence binding: " + occurrenceIdentity);
    }

    private static ResultingDocument resultingDocument(
            ClosureProcessResult actual,
            DocumentId documentId) {
        for (ResultingDocument candidate : actual.resultingDocuments()) {
            if (candidate.documentId().equals(documentId)) {
                return candidate;
            }
        }
        throw new AssertionError(
                "Missing resulting document: " + documentId);
    }

    private static String diagnostic(ClosureProcessResult result) {
        return result.diagnostic() == null
                ? "no diagnostic"
                : result.diagnostic().category() + ": "
                        + result.diagnostic().message();
    }

    private static String traceSummary(ClosureProcessResult result) {
        StringBuilder value = new StringBuilder();
        for (blue.language.processor.closure.GasTraceEntry entry
                : result.gasTrace()) {
            if (value.length() > 0) {
                value.append(", ");
            }
            value.append(entry.sequence())
                    .append(':')
                    .append(entry.namespace().wireValue())
                    .append(':')
                    .append(entry.counter())
                    .append('x')
                    .append(entry.quantity())
                    .append('=')
                    .append(entry.subtotal())
                    .append('[')
                    .append(entry.documentId())
                    .append('|')
                    .append(entry.scopePath())
                    .append('|')
                    .append(entry.activationGeneration())
                    .append('|')
                    .append(entry.componentGeneration())
                    .append('|')
                    .append(entry.contractKey())
                    .append('|')
                    .append(entry.logicalPath())
                    .append('|')
                    .append(entry.workOccurrenceId())
                    .append('|')
                    .append(entry.reason())
                    .append(']');
        }
        return value.toString();
    }

    private static final class Capture
            implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence evidence) {
            this.evidence = evidence;
        }
    }
}
