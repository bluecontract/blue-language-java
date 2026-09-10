package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ManagedCheckpointCandidate;
import blue.language.processor.ManagedCheckpointSettlementEntry;
import blue.language.processor.ManagedDocumentStepRuntime;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checkpoint ownership is per actual Root work, not affected-cohort membership. */
final class ManagedCheckpointSettlementOwnershipTest {
    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final Node CHANNEL_TYPE = new Node().name("Checkpoint ownership source Channel");
    private static final String CHANNEL_ID = blueId(CHANNEL_TYPE);
    private static final Node HANDLER_TYPE = new Node().name("Checkpoint ownership test Handler");
    private static final String HANDLER_ID = blueId(HANDLER_TYPE);
    private static final String CHECKPOINT = "/contracts/checkpoint/entries/ownerChannel";

    @Test
    void numberedTailRetainsTwoActualPositionsBeforeActivation() { numberedTail(false); }

    @Test
    void numberedTailDoesNotSkipActualReturnToTheNumberedAnchor() { numberedTail(true); }

    private void numberedTail(boolean repeatedIdentity) {
        try (Fixture fixture = new Fixture(true)) {
            Node b = fixture.root("b", "attach", false);
            b.getContracts().properties("embedded", embedded("/peer"));
            Node a = fixture.root("a", "assign", false);
            a.properties("peer", new Node().blueId(blueId(b)));
            a.getContracts().properties("embedded", embedded("/peer"));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b),
                    Collections.singletonList(fixture.binding(A, "/peer", B, blueId(b))));
            List<Run> sourceHistory = new ArrayList<>();
            for (int value : repeatedIdentity ? new int[]{0, 1, 0, 2} : new int[]{0, 1, 2, 3}) {
                Run run = fixture.external(snapshot, A,
                        event("assign-" + value).properties("business", new Node().value(value)),
                        sourceHistory.size() + 1L, null);
                assertSuccess(run);
                sourceHistory.add(run);
                snapshot = fixture.after(run.result);
            }
            String saved = result(sourceHistory.get(0), A).afterBlueId();
            assertEquals(repeatedIdentity, saved.equals(result(sourceHistory.get(2), A).afterBlueId()));
            assertNotEquals(saved, result(sourceHistory.get(1), A).afterBlueId());
            long savedEpoch = result(sourceHistory.get(0), A).epoch();
            Run attached = fixture.external(snapshot, B,
                    event("attach-saved").properties("target", new Node().blueId(saved)), 5L, savedEpoch);
            assertSuccess(attached);
            snapshot = fixture.after(attached.result);
            long epochBeforeAnchor = snapshot.managedDocument(A).epoch();
            Run numberedAnchor = fixture.external(snapshot, A,
                    event("numbered-tail-anchor").properties("business", new Node().value(9)), 6L, null);
            assertSuccess(numberedAnchor);
            assertEquals(epochBeforeAnchor + 1L, result(numberedAnchor, A).epoch(),
                    "The carrier must start from a genuine numbered source commit");
            snapshot = fixture.after(numberedAnchor.result);
            String x = snapshot.managedDocument(A).blueId();
            long epoch = snapshot.managedDocument(A).epoch();
            ManagedDocumentTransitionReceipt anchor = receipt(numberedAnchor, A);
            String predecessor = anchor.transitionReceiptIdentity();
            List<ManagedRepresentationTransition> positions = new ArrayList<>();
            for (int index = 1; index <= 2; index++) {
                ManagedOccurrenceBinding pending = snapshot.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(B)).findFirst().get();
                Run historical = sourceHistory.get(index);
                ResultingDocument historicalA = result(historical, A);
                ManagedRevisionCause cause = ClosureEvidenceFactory.managedRevisionCause(
                        pending.occurrenceIdentity(), historicalA.epoch() - 1L, historicalA.epoch(),
                        historicalA.document(), receipt(historical, A), null);
                Run applied = fixture.process(snapshot, cause);
                assertSuccess(applied);
                assertEquals(epoch, result(applied, A).epoch());
                assertTrue(receipt(applied, A).emittedRootEvents().isEmpty());
                ManagedRepresentationTransition position = new ManagedRepresentationTransition(A, epoch,
                        anchor.transitionReceiptIdentity(), predecessor, applied.input, applied.result,
                        receipt(applied, A).transitionReceiptIdentity());
                positions.add(position);
                predecessor = position.positionIdentity();
                snapshot = fixture.after(applied.result);
            }
            assertNotEquals(x, positions.get(0).transitionReceipt().afterBlueId());
            assertEquals(repeatedIdentity, x.equals(positions.get(1).transitionReceipt().afterBlueId()),
                    "The repeated case must genuinely return to the numbered anchor's exact identity");
            String authoritative = snapshot.managedDocument(A).blueId();
            assertNotEquals(positions.get(0).positionIdentity(), positions.get(1).positionIdentity());
            assertEquals(positions.get(0).positionIdentity(), positions.get(1).predecessorPositionIdentity());
            DocumentId consumerId = new DocumentId("historical-consumer");
            Node consumer = fixture.root("consumer", "noop", false)
                    .properties("peer", new Node().blueId(anchor.beforeBlueId()))
                    .properties("observed", new Node().value(0));
            consumer.getContracts().properties("embedded", embedded("/peer"))
                    .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                            .properties("path", new Node().value("/peer")))
                    .properties("observe", handler("updates"));
            snapshot = fixture.withPendingConsumer(snapshot, consumerId, consumer, A, epoch - 1L, anchor.beforeBlueId());
            ManagedOccurrenceBinding occurrence = snapshot.occurrences().stream()
                    .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
            String targetPosition = positions.get(1).positionIdentity();
            ManagedRevisionCause originalNumbered = ClosureEvidenceFactory.managedRevisionCause(
                    occurrence.occurrenceIdentity(), epoch - 1L, epoch,
                    result(numberedAnchor, A).document(), anchor, null);
            assertFalse(originalNumbered.successorRepresentationCause().isPresent());
            ManagedRepresentationCause first = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                    positions.get(0), targetPosition, null, null);
            ManagedRevisionCause carrier = originalNumbered.withSuccessorRepresentationCause(first);
            assertNotEquals(originalNumbered.causeIdentity(), carrier.causeIdentity());
            assertEquals(ProcessingCause.Kind.MANAGED_REVISION, carrier.kind());
            assertEquals(originalNumbered.fromEpoch() + 1L, carrier.toEpoch());
            assertEquals(anchor.transitionReceiptIdentity(), carrier.sourceRevisionReceiptIdentity());
            assertEquals(anchor.originalCauseIdentity(), carrier.originalSourceCauseIdentity());
            assertEquals(first.causeIdentity(), carrier.successorRepresentationCause().get().causeIdentity());
            assertThrows(IllegalStateException.class, () -> carrier.withSuccessorRepresentationCause(first));
            final String occurrenceId = occurrence.occurrenceIdentity();
            assertThrows(IllegalArgumentException.class, () -> originalNumbered.withSuccessorRepresentationCause(
                    new ManagedRepresentationCause(occurrenceId, positions.get(1), targetPosition, null, null)));
            assertThrows(IllegalArgumentException.class, () -> originalNumbered.withSuccessorRepresentationCause(
                    new ManagedRepresentationCause(hash('f'), positions.get(0), targetPosition, null, null)));
            assertThrows(IllegalArgumentException.class, () -> originalNumbered.withSuccessorRepresentationCause(
                    new ManagedRepresentationCause(occurrenceId, positions.get(0), targetPosition, hash('f'), null)));
            final AffectedClosureSnapshot beforeNumbered = snapshot;
            try (Fixture legacy = new Fixture()) {
                assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.processClosure(
                        beforeNumbered, carrier, Collections.emptyList(), ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "numbered-tail-profile"), legacy.environment));
            }
            ClosureInvocationInput exhaustedNumbered = ClosureEvidenceFactory.processClosure(snapshot, carrier,
                    Collections.emptyList(), ClosureEvidenceFactory.executionPolicy(1L, Collections.emptyMap(),
                            "numbered-tail-rollback"), fixture.environment);
            try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                ClosureAttemptResult rollback = contracts.processClosure(exhaustedNumbered);
                assertTrue(rollback.isComplete());
                assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, rollback.processResult().status());
                assertEquals(snapshot.closureIdentity(), rollback.processResult().outputClosureIdentity());
            }
            int beforeNumberedCalls = fixture.probe.calls.size();
            Run numbered = fixture.process(snapshot, carrier);
            assertSuccess(numbered);
            assertTrue(numbered.result.totalGas() > 1L);
            assertTrue(numbered.result.publicEvents().isEmpty());
            assertTrue(numbered.evidence.workTrace().stream()
                    .noneMatch(work -> first.causeIdentity().equals(work.sourceOccurrenceIdentity())),
                    "The future cause must never execute inside the numbered invocation");
            assertEquals(Collections.singletonList("consumer:observe"),
                    fixture.probe.calls.subList(beforeNumberedCalls, fixture.probe.calls.size()));
            assertEquals(authoritative, result(numbered, A).afterBlueId());
            assertEquals(epoch, result(numbered, A).epoch());
            Run sameNumbered = fixture.process(snapshot, carrier);
            assertEquals(numbered.result.outputClosureIdentity(), sameNumbered.result.outputClosureIdentity());
            assertEquals(numbered.result.totalGas(), sameNumbered.result.totalGas());
            snapshot = fixture.after(numbered.result);
            occurrence = snapshot.occurrences().stream()
                    .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
            assertFalse(occurrence.active(), "Epoch and endpoint equality cannot skip a nonempty captured tail");
            assertEquals(Long.valueOf(epoch), occurrence.pendingHistoricalEpoch());
            assertEquals(anchor.afterBlueId(), occurrence.expectedTargetBlueId());
            assertEquals(new ManagedRepresentationCursor(anchor.transitionReceiptIdentity(), anchor.transitionReceiptIdentity(),
                    targetPosition, null), occurrence.pendingRepresentationCursor());
            final AffectedClosureSnapshot beforeTraversal = snapshot;
            ManagedRepresentationCause skipped = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                    positions.get(1), targetPosition, null, null);
            assertThrows(IllegalArgumentException.class, () -> fixture.process(beforeTraversal, skipped));
            int callsBefore = fixture.probe.calls.size();
            for (int index = 0; index < positions.size(); index++) {
                ManagedRepresentationCause cause = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                        positions.get(index), targetPosition, null, null);
                ClosureInvocationInput rollbackInput = ClosureEvidenceFactory.processClosure(snapshot, cause,
                        Collections.emptyList(), ClosureEvidenceFactory.executionPolicy(1L, Collections.emptyMap(),
                                "representation-return-rollback"), fixture.environment);
                try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                    ClosureAttemptResult rollback = contracts.processClosure(rollbackInput);
                    assertTrue(rollback.isComplete());
                    assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, rollback.processResult().status());
                    assertEquals(snapshot.closureIdentity(), rollback.processResult().outputClosureIdentity());
                }
                Run traversed = fixture.process(snapshot, cause);
                assertSuccess(traversed);
                assertTrue(traversed.result.totalGas() > 1L);
                assertTrue(traversed.result.publicEvents().isEmpty());
                assertEquals(authoritative, result(traversed, A).afterBlueId());
                assertEquals(epoch, result(traversed, A).epoch());
                assertEquals(java.math.BigInteger.valueOf(index + 2L),
                        result(traversed, consumerId).document().get("/observed"));
                snapshot = fixture.after(traversed.result);
                occurrence = snapshot.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
                assertEquals(index == positions.size() - 1, occurrence.active());
                final AffectedClosureSnapshot afterTraversal = snapshot;
                assertThrows(IllegalArgumentException.class, () -> fixture.process(afterTraversal, cause));
            }
            assertEquals(Arrays.asList("consumer:observe", "consumer:observe"),
                    fixture.probe.calls.subList(callsBefore, fixture.probe.calls.size()));
            System.out.println("GENUINE_NUMBERED_REPRESENTATION_TAIL epoch=" + epoch + " X=" + x
                    + " Y=" + positions.get(0).transitionReceipt().afterBlueId()
                    + " positions=" + positions.stream().map(ManagedRepresentationTransition::positionIdentity)
                            .collect(Collectors.toList()));
        }
    }

    @Test
    void historicalReconciliationRetainsGenuineRepeatedRepresentationPositions() {
        try (Fixture fixture = new Fixture()) {
            Node b = fixture.root("b", "attach", false);
            b.getContracts().properties("embedded", embedded("/peer"));
            Node a = fixture.root("a", "assign", false);
            a.properties("peer", new Node().blueId(blueId(b)));
            a.getContracts().properties("embedded", embedded("/peer"));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b),
                    Collections.singletonList(fixture.binding(A, "/peer", B, blueId(b))));
            List<Run> sourceHistory = new ArrayList<>();
            for (int value : new int[]{0, 1, 0, 2}) {
                Run run = fixture.external(snapshot, A,
                        event("assign-" + value).properties("business", new Node().value(value)),
                        sourceHistory.size() + 1L, null);
                assertSuccess(run);
                sourceHistory.add(run);
                snapshot = fixture.after(run.result);
            }
            String saved = result(sourceHistory.get(0), A).afterBlueId();
            assertEquals(saved, result(sourceHistory.get(2), A).afterBlueId(),
                    "Repeated external exact events are independently ordered; real state and checkpoint return exactly");
            assertNotEquals(saved, result(sourceHistory.get(1), A).afterBlueId());
            long savedEpoch = result(sourceHistory.get(0), A).epoch();
            Run attached = fixture.external(snapshot, B,
                    event("attach-saved").properties("target", new Node().blueId(saved)), 5L, savedEpoch);
            assertSuccess(attached);
            snapshot = fixture.after(attached.result);
            String x = snapshot.managedDocument(A).blueId();
            long epoch = snapshot.managedDocument(A).epoch();
            ManagedDocumentTransitionReceipt anchor = receipt(attached, A);
            String predecessor = anchor.transitionReceiptIdentity();
            List<ManagedRepresentationTransition> positions = new ArrayList<>();
            for (int index = 1; index <= 2; index++) {
                ManagedOccurrenceBinding pending = snapshot.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(B)).findFirst().get();
                Run historical = sourceHistory.get(index);
                ResultingDocument historicalA = result(historical, A);
                ManagedRevisionCause cause = ClosureEvidenceFactory.managedRevisionCause(
                        pending.occurrenceIdentity(), historicalA.epoch() - 1L, historicalA.epoch(),
                        historicalA.document(), receipt(historical, A), null);
                Run applied = fixture.process(snapshot, cause);
                assertSuccess(applied);
                assertEquals(epoch, result(applied, A).epoch());
                assertTrue(receipt(applied, A).emittedRootEvents().isEmpty());
                ManagedRepresentationTransition position = new ManagedRepresentationTransition(A, epoch,
                        anchor.transitionReceiptIdentity(), predecessor, applied.input, applied.result,
                        receipt(applied, A).transitionReceiptIdentity());
                positions.add(position);
                predecessor = position.positionIdentity();
                snapshot = fixture.after(applied.result);
            }
            assertNotEquals(x, positions.get(0).transitionReceipt().afterBlueId());
            assertEquals(x, positions.get(1).transitionReceipt().afterBlueId(),
                    "Two actual processor commits must establish X -> Y -> X at one source epoch");
            assertNotEquals(positions.get(0).positionIdentity(), positions.get(1).positionIdentity());
            assertEquals(positions.get(0).positionIdentity(), positions.get(1).predecessorPositionIdentity());
            DocumentId consumerId = new DocumentId("historical-consumer");
            Node consumer = fixture.root("consumer", "noop", false)
                    .properties("peer", new Node().blueId(x))
                    .properties("observed", new Node().value(0));
            consumer.getContracts().properties("embedded", embedded("/peer"))
                    .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                            .properties("path", new Node().value("/peer")))
                    .properties("observe", handler("updates"));
            snapshot = fixture.withPendingConsumer(snapshot, consumerId, consumer, A, epoch);
            ManagedOccurrenceBinding occurrence = snapshot.occurrences().stream()
                    .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
            String targetPosition = positions.get(1).positionIdentity();
            final AffectedClosureSnapshot beforeTraversal = snapshot;
            ManagedRepresentationCause skipped = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                    positions.get(1), targetPosition, null, null);
            assertThrows(IllegalArgumentException.class, () -> fixture.process(beforeTraversal, skipped));
            int callsBefore = fixture.probe.calls.size();
            for (int index = 0; index < positions.size(); index++) {
                ManagedRepresentationCause cause = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                        positions.get(index), targetPosition, null, null);
                ClosureInvocationInput rollbackInput = ClosureEvidenceFactory.processClosure(snapshot, cause,
                        Collections.emptyList(), ClosureEvidenceFactory.executionPolicy(1L, Collections.emptyMap(),
                                "representation-return-rollback"), fixture.environment);
                try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                    ClosureAttemptResult rollback = contracts.processClosure(rollbackInput);
                    assertTrue(rollback.isComplete());
                    assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, rollback.processResult().status());
                    assertEquals(snapshot.closureIdentity(), rollback.processResult().outputClosureIdentity());
                }
                Run traversed = fixture.process(snapshot, cause);
                assertSuccess(traversed);
                assertTrue(traversed.result.totalGas() > 1L);
                assertTrue(traversed.result.publicEvents().isEmpty());
                assertEquals(x, result(traversed, A).afterBlueId());
                assertEquals(epoch, result(traversed, A).epoch());
                assertEquals(java.math.BigInteger.valueOf(index + 1L),
                        result(traversed, consumerId).document().get("/observed"));
                snapshot = fixture.after(traversed.result);
                occurrence = snapshot.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
                assertEquals(index == positions.size() - 1, occurrence.active());
                final AffectedClosureSnapshot afterTraversal = snapshot;
                assertThrows(IllegalArgumentException.class, () -> fixture.process(afterTraversal, cause));
            }
            assertEquals(Arrays.asList("consumer:observe", "consumer:observe"),
                    fixture.probe.calls.subList(callsBefore, fixture.probe.calls.size()));
            System.out.println("GENUINE_REPRESENTATION_RETURN epoch=" + epoch + " X=" + x
                    + " Y=" + positions.get(0).transitionReceipt().afterBlueId()
                    + " positions=" + positions.stream().map(ManagedRepresentationTransition::positionIdentity)
                            .collect(Collectors.toList()));
        }
    }

    @Test
    void shouldRejectRepresentationPastFrozenTargetAndApplyNextNumberedRevision() {
        // given: retained processing creates three genuine same-epoch positions
        try (Fixture fixture = new Fixture()) {
            Node b = fixture.root("b", "attach", false);
            b.getContracts().properties("embedded", embedded("/peer"));
            Node a = fixture.root("a", "assign", false);
            a.properties("peer", new Node().blueId(blueId(b)));
            a.getContracts().properties("embedded", embedded("/peer"));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b),
                    Collections.singletonList(fixture.binding(A, "/peer", B, blueId(b))));
            List<Run> sourceHistory = new ArrayList<>();
            for (int value : new int[]{0, 1, 0, 2, 3}) {
                Run run = fixture.external(snapshot, A,
                        event("assign-" + value).properties("business", new Node().value(value)),
                        sourceHistory.size() + 1L, null);
                assertSuccess(run);
                sourceHistory.add(run);
                snapshot = fixture.after(run.result);
            }
            ResultingDocument saved = result(sourceHistory.get(0), A);
            Run attached = fixture.external(snapshot, B,
                    event("attach-saved").properties("target", new Node().blueId(saved.afterBlueId())),
                    6L, saved.epoch());
            assertSuccess(attached);
            snapshot = fixture.after(attached.result);
            long epoch = snapshot.managedDocument(A).epoch();
            ManagedDocumentTransitionReceipt anchor = receipt(attached, A);
            String predecessor = anchor.transitionReceiptIdentity();
            List<ManagedRepresentationTransition> positions = new ArrayList<>();
            AffectedClosureSnapshot frozenSource = null;
            for (int index = 1; index <= 3; index++) {
                ManagedOccurrenceBinding pending = snapshot.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(B)).findFirst().get();
                Run historical = sourceHistory.get(index);
                ResultingDocument historicalA = result(historical, A);
                Run applied = fixture.process(snapshot, ClosureEvidenceFactory.managedRevisionCause(
                        pending.occurrenceIdentity(), historicalA.epoch() - 1L, historicalA.epoch(),
                        historicalA.document(), receipt(historical, A), null));
                assertSuccess(applied);
                ManagedRepresentationTransition position = new ManagedRepresentationTransition(A, epoch,
                        anchor.transitionReceiptIdentity(), predecessor, applied.input, applied.result,
                        receipt(applied, A).transitionReceiptIdentity());
                positions.add(position);
                predecessor = position.positionIdentity();
                snapshot = fixture.after(applied.result);
                if (index == 2) {
                    frozenSource = snapshot;
                }
            }
            // The captured history continues with a numbered revision after position two.
            Run next = fixture.external(frozenSource, A,
                    event("next-numbered").properties("business", new Node().value(4)), 7L, null);
            assertSuccess(next);
            ManagedDocumentTransitionReceipt nextReceipt = receipt(next, A);
            String targetPosition = positions.get(1).positionIdentity();
            assertEquals(positions.get(2).predecessorPositionIdentity(), targetPosition);
            assertEquals(positions.get(1).transitionReceipt().afterBlueId(), nextReceipt.beforeBlueId());
            DocumentId consumerId = new DocumentId("historical-consumer");
            String initialBlueId = anchor.afterBlueId();
            Node consumer = fixture.root("consumer", "noop", false)
                    .properties("peer", new Node().blueId(initialBlueId))
                    .properties("observed", new Node().value(0));
            consumer.getContracts().properties("embedded", embedded("/peer"))
                    .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                            .properties("path", new Node().value("/peer")))
                    .properties("observe", handler("updates"));
            snapshot = fixture.withPendingConsumer(fixture.after(next.result), consumerId,
                    consumer, A, epoch, initialBlueId);
            ManagedOccurrenceBinding occurrence = snapshot.occurrences().stream()
                    .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();

            // when: both valid representation steps reach the frozen target
            for (int index = 0; index < 2; index++) {
                ManagedRepresentationCause cause = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                        positions.get(index), targetPosition, nextReceipt.transitionReceiptIdentity(), null);
                Run traversed = fixture.process(snapshot, cause);
                assertSuccess(traversed);
                assertEquals(java.math.BigInteger.valueOf(index + 1L),
                        result(traversed, consumerId).document().get("/observed"));
                snapshot = fixture.after(traversed.result);
                occurrence = snapshot.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
                assertFalse(occurrence.active(), "The numbered successor is still pending");
            }

            // then: an extra bridge is rejected, preserving the exact numbered predecessor
            assertEquals(targetPosition, occurrence.pendingRepresentationCursor().positionIdentity());
            ManagedRepresentationCause extra = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                    positions.get(2), targetPosition, nextReceipt.transitionReceiptIdentity(), null);
            final AffectedClosureSnapshot completedChain = snapshot;
            assertThrows(IllegalArgumentException.class,
                    () -> assertSuccess(fixture.process(completedChain, extra)));
            Run numbered = fixture.process(snapshot, ClosureEvidenceFactory.managedRevisionCause(
                    occurrence.occurrenceIdentity(), epoch, result(next, A).epoch(),
                    result(next, A).document(), nextReceipt, null));
            assertSuccess(numbered);
            ManagedOccurrenceBinding activated = numbered.result.occurrenceBindings().stream()
                    .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
            assertTrue(activated.active());
            assertNull(activated.pendingRepresentationCursor());
            assertEquals(nextReceipt.afterBlueId(), activated.expectedTargetBlueId());
            assertEquals(java.math.BigInteger.valueOf(3L),
                    result(numbered, consumerId).document().get("/observed"));
        }
    }

    private static ManagedDocumentTransitionReceipt receipt(Run run, DocumentId id) {
        return run.result.managedTransitionReceipts().stream()
                .filter(value -> value.documentId().equals(id)).findFirst().get();
    }

    @Test
    void untouchedRetainedSourceIsNotSettledOrCleaned() {
        try (Fixture fixture = new Fixture()) {
            Node b = fixture.staleCheckpointSource(false);
            Node a = fixture.root("a", "change", false);
            a.properties("peer", new Node().blueId(blueId(b)));
            a.getContracts().properties("embedded", embedded("/peer"));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b),
                    Collections.singletonList(fixture.binding(A, "/peer", B, blueId(b))));

            Run run = fixture.run(snapshot);

            assertSuccess(run);
            assertEquals(Collections.singletonList("a:change"), fixture.probe.calls);
            assertOnlyAWork(run);
            assertEquals(blueId(b), result(run, B).afterBlueId());
            assertEquals(3L, result(run, B).epoch());
            assertNodeEquals(b, result(run, B).document());
            assertTrue(writesFor(run, B).isEmpty());
            assertTrue(run.result.managedTransitionReceipts().stream()
                    .allMatch(receipt -> A.equals(receipt.documentId())));
        }
    }

    @Test
    void acceptedActualWorkSettlesDespiteEqualBusinessState() {
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.seedCheckpoint(fixture.root("a", "noop", false),
                    "ownerChannel", ignored -> { });
            AffectedClosureSnapshot snapshot = fixture.snapshot(
                    Collections.singletonMap(A, a), Collections.emptyList());

            Run run = fixture.run(snapshot);

            assertSuccess(run);
            assertEquals(Collections.singletonList("a:noop"), fixture.probe.calls);
            assertOnlyAWork(run);
            assertEquals("same", result(run, A).document().getAsText("/business"));
            assertEquals(4L, result(run, A).epoch(),
                    "Checkpoint-only settlement does not invent a business-work epoch");
            assertNotEquals(snapshot.managedDocument(A).blueId(), result(run, A).afterBlueId());
            assertEquals(1, run.result.documentTransitionEvidence().size());
            DocumentTransitionEvidence work = run.result.documentTransitionEvidence().get(0);
            assertEquals(work.beforeDocumentBlueId(), work.afterDocumentBlueId(),
                    "The accepted managed step is an exact no-op before settlement");
            assertEquals(1, writesFor(run, A).size());
            CheckpointWrite write = writesFor(run, A).get(0);
            assertTrue(write.beforePresent());
            assertTrue(write.afterPresent());
            assertEquals(write.beforeDomainBlueId(), write.afterDomainBlueId());
            assertNotEquals(write.beforeSubjectBlueId(), write.afterSubjectBlueId());
            assertEquals(((ExternalEventCause) run.input.cause()).eventBlueId(), write.afterSubjectBlueId());
        }
    }

    @Test
    void actualChannelRemovalLegitimatelyCleansItsCheckpoint() {
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.rootWithOtherCheckpoint("removeOther");
            AffectedClosureSnapshot snapshot = fixture.snapshot(
                    Collections.singletonMap(A, a), Collections.emptyList());

            Run run = fixture.run(snapshot);

            assertSuccess(run);
            assertOnlyAWork(run);
            assertEquals(Collections.singletonList("a:removeOther"), fixture.probe.calls);
            assertNull(NodePathEditor.getOrNull(result(run, A).document(), "/contracts/other"));
            assertNull(NodePathEditor.getOrNull(result(run, A).document(),
                    "/contracts/checkpoint/entries/other"));
            assertEquals(2, writesFor(run, A).size());
            CheckpointWrite cleanup = writesFor(run, A).stream()
                    .filter(write -> "other".equals(write.rawChannelKey())).findFirst().get();
            assertTrue(cleanup.beforePresent());
            assertFalse(cleanup.afterPresent());
            assertNotNull(NodePathEditor.getOrNull(result(run, A).document(), CHECKPOINT));
            assertEquals(5L, result(run, A).epoch());
            assertNodeEquals(a, snapshot.managedDocument(A).document());
        }
    }

    @Test
    void failedProcessingPublishesNoCheckpointChange() {
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.rootWithOtherCheckpoint("fail");
            AffectedClosureSnapshot snapshot = fixture.snapshot(
                    Collections.singletonMap(A, a), Collections.emptyList());

            Run run = fixture.run(snapshot);

            assertEquals(ProcessorStatus.RUNTIME_FATAL, run.result.status());
            assertFalse(run.result.commits());
            assertEquals(Collections.singletonList("a:fail"), fixture.probe.calls);
            assertOnlyAWork(run);
            assertEquals(snapshot.closureIdentity(), run.result.outputClosureIdentity());
            assertTrue(run.result.checkpointWrites().isEmpty());
            assertTrue(run.result.managedTransitionReceipts().isEmpty());
            assertEquals(snapshot.managedDocument(A).blueId(), result(run, A).afterBlueId());
            assertEquals(4L, result(run, A).epoch());
            assertNodeEquals(a, result(run, A).document());
            assertNotNull(NodePathEditor.getOrNull(result(run, A).document(),
                    "/contracts/checkpoint/entries/other"));
        }
    }

    @Test
    void representationOnlyCyclicRebindDoesNotInventCheckpointCleanup() {
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.root("a", "change", false);
            a.properties("peer", new Node().blueId("this#1"));
            a.getContracts().properties("embedded", embedded("/peer"));
            Node b = fixture.staleCheckpointSource(true);
            b.properties("parent", new Node().blueId("this#0"));
            CyclicSetFinalization cycle = new CircularSetIdentityCalculator()
                    .finalizeCyclicSet(Arrays.asList(a, b));
            String aId = cycle.membersInInputOrder().get(0).finalBlueId();
            String bId = cycle.membersInInputOrder().get(1).finalBlueId();
            a = cycle.membersInInputOrder().get(0).canonicalMemberBody();
            b = cycle.membersInInputOrder().get(1).canonicalMemberBody();
            NodePathEditor.put(a, "/peer", new Node().blueId(bId));
            NodePathEditor.put(b, "/parent", new Node().blueId(aId));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b), Arrays.asList(
                    fixture.binding(A, "/peer", B, bId),
                    fixture.binding(B, "/parent", A, aId)));
            assertEquals(1, snapshot.components().size());
            assertEquals(ComponentKind.CYCLIC, snapshot.components().get(0).kind());
            Node before = snapshot.managedDocument(B).document();
            assertNotNull(NodePathEditor.getOrNull(before, CHECKPOINT));

            Run run = fixture.run(snapshot);

            assertSuccess(run);
            assertOnlyAWork(run);
            assertEquals(Collections.singletonList("a:change"), fixture.probe.calls);
            assertNotEquals(snapshot.managedDocument(B).blueId(), result(run, B).afterBlueId(),
                    "The untouched peer must actually be re-encoded by the cycle finalizer");
            assertTrue(run.result.documentTransitionEvidence().stream()
                    .noneMatch(work -> B.equals(work.documentId())));
            System.out.println("CYCLIC_CHECKPOINT_OWNERSHIP before="
                    + snapshot.managedDocument(B).blueId() + " after=" + result(run, B).afterBlueId()
                    + " beforeEpoch=" + snapshot.managedDocument(B).epoch()
                    + " afterEpoch=" + result(run, B).epoch()
                    + " peerCheckpointWrites=" + writesFor(run, B).stream()
                            .map(write -> write.rawChannelKey() + ":" + write.beforePresent()
                                    + "->" + write.afterPresent()).collect(Collectors.toList()));
            assertTrue(writesFor(run, B).isEmpty(),
                    "Representation-only cyclic rebind does not own checkpoint cleanup");
            assertNodeEquals(NodePathEditor.getOrNull(before, CHECKPOINT),
                    NodePathEditor.getOrNull(result(run, B).document(), CHECKPOINT));
            Node ownedBefore = before.clone();
            Node ownedAfter = result(run, B).document();
            NodePathEditor.put(ownedBefore, "/parent", new Node().value("managed-reference-slot"));
            NodePathEditor.put(ownedAfter, "/parent", new Node().value("managed-reference-slot"));
            assertNodeEquals(ownedBefore, ownedAfter);
        }
    }

    @Test
    void shouldRejectUnrelatedSameEpochRebindWhenPendingReferenceIsUnchanged() {
        // given: a real numbered B revision and an inactive consumer of its exact head
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.root("a", "noop", false)
                    .properties("peer", new Node().blueId("this#1"));
            a.getContracts().properties("embedded", embedded("/peer"));
            Node b = fixture.root("b", "change", false)
                    .properties("parent", new Node().blueId("this#0"));
            b.getContracts().properties("embedded", embedded("/parent"));
            CyclicSetFinalization cycle = new CircularSetIdentityCalculator()
                    .finalizeCyclicSet(Arrays.asList(a, b));
            String aId = cycle.membersInInputOrder().get(0).finalBlueId();
            String bId = cycle.membersInInputOrder().get(1).finalBlueId();
            a = cycle.membersInInputOrder().get(0).canonicalMemberBody();
            b = cycle.membersInInputOrder().get(1).canonicalMemberBody();
            NodePathEditor.put(a, "/peer", new Node().blueId(bId));
            NodePathEditor.put(b, "/parent", new Node().blueId(aId));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b), Arrays.asList(
                    fixture.binding(A, "/peer", B, bId),
                    fixture.binding(B, "/parent", A, aId)));
            Run numbered = fixture.external(snapshot, B, event("numbered-b"), 1L, null);
            assertSuccess(numbered);
            ManagedDocumentTransitionReceipt anchor = receipt(numbered, B);
            assertEquals(snapshot.managedDocument(B).epoch() + 1L, result(numbered, B).epoch());
            snapshot = fixture.after(numbered.result);
            DocumentId consumerId = new DocumentId("pending-consumer");
            Node consumer = fixture.root("consumer", "noop", false)
                    .properties("peer", new Node().blueId(snapshot.managedDocument(B).blueId()));
            consumer.getContracts().properties("embedded", embedded("/peer"));
            snapshot = fixture.withPendingConsumer(snapshot, consumerId, consumer,
                    B, snapshot.managedDocument(B).epoch());
            ManagedDocumentSnapshot beforeB = snapshot.managedDocument(B);
            Node pendingBefore = NodePathEditor.getOrNull(snapshot.managedDocument(consumerId).document(), "/peer");

            // when: unrelated A checkpoint settlement re-encodes B, leaving the pending consumer alone
            Run unrelated = fixture.run(snapshot);

            // then: an unchanged cloned pending value is not evidence of reconciliation
            assertSuccess(unrelated);
            assertOnlyAWork(unrelated);
            assertEquals(beforeB.epoch(), result(unrelated, B).epoch());
            assertNotEquals(beforeB.blueId(), result(unrelated, B).afterBlueId());
            Node pendingAfter = NodePathEditor.getOrNull(result(unrelated, consumerId).document(), "/peer");
            assertNotNull(pendingBefore);
            assertNotNull(pendingAfter);
            assertNodeEquals(pendingBefore, pendingAfter);
            ManagedOccurrenceBinding pending = unrelated.result.occurrenceBindings().stream()
                    .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
            assertFalse(pending.active());
            assertEquals(beforeB.blueId(), pending.expectedTargetBlueId());
            assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationTransition(
                    B, beforeB.epoch(), anchor.transitionReceiptIdentity(), anchor.transitionReceiptIdentity(),
                    unrelated.input, unrelated.result, receipt(unrelated, B).transitionReceiptIdentity()));
        }
    }

    @Test
    void rootedCheckpointReferenceGraphAcceptsActualCompleteRebindsAndProcessorProof() {
        try (Fixture fixture = new Fixture(true)) {
            Run run = fixture.rootedCheckpointRun(fixture.rootedPair(true), 1L);
            assertActualCheckpointReference(run, 2);
            AffectedClosureSnapshot output = run.result.rootedProjection().resultingSnapshot();
            assertTrue(RootedCheckpointReferenceGraph.verifies(run.input.snapshot(), output, run.result.graphChanges()));
            for (AffectedClosureSnapshot boundary : run.result.rootedProjection().topologyBoundaries())
                assertTrue(RootedCheckpointReferenceGraph.sameTopology(run.input.snapshot(), boundary));
            assertEquals(run.input.snapshot().graphGeneration(), output.graphGeneration());
            AffectedClosureSnapshot wrongGeneration = new AffectedClosureSnapshot(output.closureIdentity(),
                    output.graphGeneration() + 1L, output.managedDocuments(), output.occurrences(),
                    output.occurrenceBindingSetIdentity(), output.components(), output.publicRootDocumentIds());
            assertFalse(RootedCheckpointReferenceGraph.verifies(run.input.snapshot(), wrongGeneration, run.result.graphChanges()));
            assertEquals(run.input.snapshot().managedDocument(A).epoch(), output.managedDocument(A).epoch());
            assertTrue(run.result.totalGas() > 0L);
        }
    }

    @Test
    void rootedCheckpointReferenceGraphRejectsMissingDuplicateDetachedAndForgedReceipts() {
        try (Fixture fixture = new Fixture(true)) {
            AffectedClosureSnapshot input = fixture.rootedPair(true);
            Run run = fixture.rootedCheckpointRun(input, 1L);
            assertActualCheckpointReference(run, 2);
            AffectedClosureSnapshot output = run.result.rootedProjection().resultingSnapshot();
            GraphChange first = run.result.graphChanges().get(0), second = run.result.graphChanges().get(1);
            assertFalse(RootedCheckpointReferenceGraph.verifies(input, output, Collections.emptyList()));
            assertFalse(RootedCheckpointReferenceGraph.verifies(input, output, Collections.singletonList(first)));
            assertFalse(RootedCheckpointReferenceGraph.verifies(input, output, Arrays.asList(first, first)));
            assertFalse(RootedCheckpointReferenceGraph.verifies(input, output, Arrays.asList(first, second, second)));
            assertFalse(RootedCheckpointReferenceGraph.verifies(input, output, Arrays.asList(second, first)));
            List<GraphChange> invalid = Arrays.asList(
                    new GraphChange(9L, GraphChange.Kind.REBIND, A, first.sourcePath(), first.before(), first.after()),
                    new GraphChange(0L, GraphChange.Kind.REBIND, B, first.sourcePath(), first.before(), first.after()),
                    new GraphChange(0L, GraphChange.Kind.REBIND, A, "/unknown", first.before(), first.after()),
                    new GraphChange(0L, GraphChange.Kind.ADD, A, first.sourcePath(), null, first.after()),
                    new GraphChange(0L, GraphChange.Kind.REMOVE, A, first.sourcePath(), first.before(), null),
                    new GraphChange(0L, GraphChange.Kind.REBIND, A, first.sourcePath(),
                            graphSide(first.before(), hash('e'), first.beforeTargetBlueId()), first.after()),
                    new GraphChange(0L, GraphChange.Kind.REBIND, A, first.sourcePath(), first.before(),
                            graphSide(first.after(), hash('f'), first.afterTargetBlueId())),
                    new GraphChange(0L, GraphChange.Kind.REBIND, A, first.sourcePath(), first.before(),
                            graphSide(first.after(), first.afterBindingIdentity(), first.beforeTargetBlueId())));
            for (GraphChange replacement : invalid)
                assertFalse(RootedCheckpointReferenceGraph.verifies(input, output, Arrays.asList(replacement, second)),
                        "Every receipt side, location, kind and ordinal must match the exact inventory");
            Run other = fixture.rootedCheckpointRun(input, 2L);
            assertActualCheckpointReference(other, 2);
            assertNotEquals(run.result.outputClosureIdentity(), other.result.outputClosureIdentity());
            assertFalse(RootedCheckpointReferenceGraph.verifies(input, output, other.result.graphChanges()),
                    "Another genuine checkpoint's graph evidence is not this publication's evidence");
        }
    }

    @Test
    void rootedCheckpointReferenceGraphRejectsInventoryOmissionDuplicationAndForgedBinding() {
        try (Fixture fixture = new Fixture(true)) {
            Run run = fixture.rootedCheckpointRun(fixture.rootedPair(true), 1L);
            assertActualCheckpointReference(run, 2);
            AffectedClosureSnapshot output = run.result.rootedProjection().resultingSnapshot();
            List<ManagedOccurrenceBinding> rows = output.occurrences();
            AffectedClosureSnapshot missing = fixture.snapshot(snapshotBodies(output), Collections.singletonList(rows.get(0)));
            assertFalse(RootedCheckpointReferenceGraph.verifies(run.input.snapshot(), missing, run.result.graphChanges()));
            assertThrows(IllegalArgumentException.class, () -> new AffectedClosureSnapshot(output.closureIdentity(),
                    output.graphGeneration(), output.managedDocuments(), Arrays.asList(rows.get(0), rows.get(0)),
                    output.occurrenceBindingSetIdentity(), output.components(), output.publicRootDocumentIds()));
            ManagedOccurrenceBinding old = rows.get(0);
            ManagedOccurrenceBinding forged = new ManagedOccurrenceBinding(old.occurrenceIdentity(), hash('f'),
                    old.bindingPolicyIdentity(), old.sourceDocumentId(), old.sourceAddress(), old.targetDocumentId(),
                    old.expectedTargetBlueId(), old.active(), old.pendingHistoricalEpoch());
            assertThrows(IllegalArgumentException.class, () -> fixture.snapshot(snapshotBodies(output),
                    Arrays.asList(forged, rows.get(1))));
        }
    }

    @Test
    void rootedCheckpointReferenceGraphRejectsStableMetadataAndCursorChanges() {
        try (Fixture fixture = new Fixture(true)) {
            Run run = fixture.rootedCheckpointRun(fixture.rootedPair(true), 1L);
            assertActualCheckpointReference(run, 2);
            AffectedClosureSnapshot output = run.result.rootedProjection().resultingSnapshot();
            ManagedOccurrenceBinding old = output.occurrences().get(0), other = output.occurrences().get(1);
            List<ManagedOccurrenceBinding> invalid = Arrays.asList(
                    ManagedOccurrenceBinding.derived(old.bindingPolicyIdentity(), A,
                            ScopeAddress.embedded("/wrongPath", old.activationGeneration()), B, old.expectedTargetBlueId(), true, null),
                    ManagedOccurrenceBinding.derived(old.bindingPolicyIdentity(), A,
                            ScopeAddress.embedded(old.sourcePath(), old.activationGeneration() + 1L), B, old.expectedTargetBlueId(), true, null),
                    ManagedOccurrenceBinding.derived(hash('e'), A, old.sourceAddress(), B, old.expectedTargetBlueId(), true, null),
                    ManagedOccurrenceBinding.derived(old.bindingPolicyIdentity(), A, old.sourceAddress(), B,
                            old.expectedTargetBlueId(), false, null),
                    ManagedOccurrenceBinding.derived(old.bindingPolicyIdentity(), A, old.sourceAddress(), B,
                            old.expectedTargetBlueId(), false, 0L),
                    ManagedOccurrenceBinding.derived(old.bindingPolicyIdentity(), A, old.sourceAddress(), B,
                            old.expectedTargetBlueId(), false, 0L).withRepresentationCursor(
                                    new ManagedRepresentationCursor(hash('a'), hash('b'), hash('c'), null)));
            for (ManagedOccurrenceBinding changed : invalid) {
                Map<DocumentId, Node> bodies = snapshotBodies(output);
                if ("/wrongPath".equals(changed.sourcePath())) {
                    Node value = NodePathEditor.getOrNull(bodies.get(A), old.sourcePath());
                    NodePathEditor.put(bodies.get(A), changed.sourcePath(), value);
                }
                AffectedClosureSnapshot mutation = fixture.snapshot(bodies, Arrays.asList(changed, other));
                assertFalse(RootedCheckpointReferenceGraph.sameTopology(output, mutation));
                assertFalse(RootedCheckpointReferenceGraph.verifies(run.input.snapshot(), mutation, run.result.graphChanges()));
            }
            // The constructor can establish a new legitimate self-edge/SCC; it
            // cannot make a target-lineage change a checkpoint reference rewrite.
            ManagedOccurrenceBinding retarget = ManagedOccurrenceBinding.derived(old.bindingPolicyIdentity(), A,
                    ScopeAddress.embedded(old.sourcePath(), old.activationGeneration() + 1L), A,
                    output.managedDocument(A).blueId(), true, null);
            AffectedClosureSnapshot retargeted = fixture.snapshot(snapshotBodies(output), Arrays.asList(retarget, other));
            assertFalse(RootedCheckpointReferenceGraph.sameTopology(output, retargeted));
            assertFalse(RootedCheckpointReferenceGraph.verifies(run.input.snapshot(), retargeted, run.result.graphChanges()));
        }
    }

    @Test
    void rootedCheckpointReferenceGraphComparesInactiveRepresentationCursorsExactly() {
        try (Fixture fixture = new Fixture(true)) {
            Run run = fixture.rootedCheckpointRun(fixture.rootedPair(true), 1L);
            assertActualCheckpointReference(run, 2);
            AffectedClosureSnapshot output = run.result.rootedProjection().resultingSnapshot();
            ManagedOccurrenceBinding active = output.occurrences().get(0), other = output.occurrences().get(1);
            ManagedOccurrenceBinding pending = ManagedOccurrenceBinding.derived(active.bindingPolicyIdentity(), A,
                    active.sourceAddress(), B, active.expectedTargetBlueId(), false, 0L);
            ManagedRepresentationCursor position = new ManagedRepresentationCursor(hash('a'), hash('b'), hash('c'), hash('d'));
            AffectedClosureSnapshot anchored = fixture.snapshot(snapshotBodies(output),
                    Arrays.asList(pending.withRepresentationCursor(position), other));
            for (ManagedRepresentationCursor changed : Arrays.asList(
                    new ManagedRepresentationCursor(hash('e'), hash('b'), hash('c'), hash('d')),
                    new ManagedRepresentationCursor(hash('a'), hash('e'), hash('c'), hash('d')),
                    new ManagedRepresentationCursor(hash('a'), hash('b'), hash('e'), hash('d')),
                    new ManagedRepresentationCursor(hash('a'), hash('b'), hash('c'), hash('e')))) {
                AffectedClosureSnapshot mutation = fixture.snapshot(snapshotBodies(output),
                        Arrays.asList(pending.withRepresentationCursor(changed), other));
                assertFalse(RootedCheckpointReferenceGraph.sameTopology(anchored, mutation));
            }
            AffectedClosureSnapshot cleared = fixture.snapshot(snapshotBodies(output), Arrays.asList(pending, other));
            assertFalse(RootedCheckpointReferenceGraph.sameTopology(anchored, cleared));
        }
    }

    @Test
    void rootedCheckpointReferenceProofRejectsTransientTopologyRestoredBeforePublication() {
        try (Fixture fixture = new Fixture(true)) {
            Run run = fixture.rootedCheckpointRun(fixture.rootedPair(true), 1L);
            assertActualCheckpointReference(run, 2);
            AffectedClosureSnapshot input = run.input.snapshot();
            // Drop only one of two parallel edges: graph adjacency/SCCs stay
            // unchanged, but the complete active occurrence inventory differs.
            ManagedOccurrenceBinding active = input.occurrences().get(0), other = input.occurrences().get(1);
            ManagedOccurrenceBinding retired = ManagedOccurrenceBinding.derived(active.bindingPolicyIdentity(), A,
                    active.sourceAddress(), B, active.expectedTargetBlueId(), false, null);
            AffectedClosureSnapshot transientState = fixture.snapshot(snapshotBodies(input), Arrays.asList(retired, other));
            assertEquals(input.graph().adjacency(), transientState.graph().adjacency());
            assertFalse(RootedCheckpointReferenceGraph.sameTopology(input, transientState));
            // Deliberately forged tracker is a negative only. Genuine positive
            // authority above came from actual BlueClosureContracts processing.
            RootedOwnershipTracker forged = new RootedOwnershipTracker(run.input.rootedBinding(), input);
            forged.finalized(transientState, TentativeFinalization.Boundary.Kind.WORK);
            forged.finalized(input, TentativeFinalization.Boundary.Kind.WORK);
            forged.finalized(run.result.rootedProjection().resultingSnapshot(),
                    TentativeFinalization.Boundary.Kind.CHECKPOINT_SETTLEMENT);
            RootedPublicationProjection rejected = new RootedPublicationProjection(run.result, forged.snapshot());
            assertFalse(rejected.checkpointReferenceProofIdentity(A).isPresent());
        }
    }

    private static GraphChange.Side graphSide(GraphChange.Side original, String binding, String blueId) {
        return new GraphChange.Side(original.activationGeneration(), original.occurrenceIdentity(), binding,
                original.targetDocumentId(), blueId);
    }

    private static Map<DocumentId, Node> snapshotBodies(AffectedClosureSnapshot snapshot) {
        Map<DocumentId, Node> result = new LinkedHashMap<>();
        snapshot.managedDocuments().forEach(document -> result.put(document.documentId(), document.document()));
        return result;
    }

    private static void assertActualCheckpointReference(Run run, int references) {
        assertSuccess(run);
        assertNotNull(run.result.rootedProjection());
        assertEquals(Collections.singletonList(A), run.result.rootedProjection().ownedDocumentIds());
        assertEquals(Collections.singletonList(B), run.input.directDeliveries().stream()
                .map(DirectLogicalDelivery::targetDocumentId).collect(Collectors.toList()));
        assertTrue(run.evidence.workTrace().stream().allMatch(work -> B.equals(work.targetDocumentId())));
        assertTrue(writesFor(run, A).isEmpty());
        assertFalse(writesFor(run, B).isEmpty());
        assertEquals(references, run.result.graphChanges().size());
        for (GraphChange graph : run.result.graphChanges()) {
            assertEquals(GraphChange.Kind.REBIND, graph.changeKind());
            assertEquals(A, graph.sourceDocumentId());
            assertEquals(B, graph.beforeTargetDocumentId());
            assertEquals(B, graph.afterTargetDocumentId());
            assertEquals(graph.beforeOccurrenceIdentity(), graph.afterOccurrenceIdentity());
            assertEquals(graph.beforeActivationGeneration(), graph.afterActivationGeneration());
            assertNotEquals(graph.beforeTargetBlueId(), graph.afterTargetBlueId());
        }
        assertTrue(receipt(run, A).emittedRootEvents().isEmpty());
        assertTrue(run.result.rootedProjection().checkpointReferenceProofIdentity(A).isPresent(),
                "Only the actual processor may supply the positive rooted checkpoint proof");
    }

    private static final class Fixture implements AutoCloseable {
        private final Map<String, Node> exact = new LinkedHashMap<>();
        private final ProbeProcessor probe = new ProbeProcessor();
        private final DocumentProcessor owner;
        private final ClosureEnvironment environment;

        private Fixture() { this(false); }

        private Fixture(boolean rooted) {
            exact.put(CHANNEL_ID, CHANNEL_TYPE.clone());
            exact.put(HANDLER_ID, HANDLER_TYPE.clone());
            NodeProvider runtime = BlueRuntimeTypeRegistry.getDefault().asProvider();
            NodeProvider provider = id -> exact.containsKey(id)
                    ? Collections.singletonList(exact.get(id).clone())
                    : runtime.fetchByBlueId(id);
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                    .register(CHANNEL_ID, CHANNEL_TYPE, new SourceProcessor())
                    .register(HANDLER_ID, HANDLER_TYPE, probe).build();
            owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider).build();
            environment = ClosureEvidenceFactory.environment(owner, hash('a'),
                    rooted ? RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY : hash('b'),
                    "checkpoint-ownership-document-v1", "checkpoint-ownership-binding-v1",
                    "checkpoint-ownership-provider-v1", "checkpoint-ownership-order-v1",
                    "checkpoint-ownership-limits-v1", GasSchedule.contracts10().portableLimits());
        }

        private AffectedClosureSnapshot rootedPair(boolean parallel) {
            Node b = root("b", "noop", false);
            Node a = root("a", "noop", false).properties("peer", new Node().blueId(blueId(b)));
            a.getContracts().properties("embedded", embedded("/peer"));
            List<ManagedOccurrenceBinding> rows = new ArrayList<>();
            rows.add(binding(A, "/peer", B, blueId(b)));
            if (parallel) {
                a.properties("otherPeer", new Node().blueId(blueId(b)));
                a.getContracts().getProperties().get("embedded").getProperties().put("paths",
                        new Node().items(new Node().value("/peer"), new Node().value("/otherPeer")));
                rows.add(binding(A, "/otherPeer", B, blueId(b)));
            }
            return snapshot(bodies(a, b), rows);
        }

        private Run rootedCheckpointRun(AffectedClosureSnapshot snapshot, long sequence) {
            Node event = event("rooted-checkpoint-" + sequence);
            exact.put(blueId(event), event.clone());
            snapshot.managedDocuments().forEach(document -> exact.put(document.blueId(), document.document()));
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, blueId(event),
                    ExternalOrderKey.of(Arrays.<Object>asList(sequence, "checkpoint-ownership")),
                    environment.externalOrderPolicyIdentity());
            ClosureInvocationInput base = ClosureEvidenceFactory.processClosure(snapshot, cause,
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(B),
                            "ownerChannel", "ownerChannel", 0L)),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "rooted-reference-graph"), environment);
            RootedProcessingContext context = RootedProcessingContext.derive(snapshot, A, Collections.singletonMap(A, hash('c')));
            String delivery;
            try (ManagedDocumentStepRuntime step = new ManagedDocumentStepRuntime(owner)) {
                blue.language.processor.ManagedRootChannelOccurrence channel = step.projectRootSubscriptionSurface(
                        snapshot.managedDocument(B).document()).channelOccurrences().stream()
                        .filter(row -> "ownerChannel".equals(row.rawChannelKey())).findFirst().get();
                ChannelOccurrence receiving = ChannelOccurrence.root(B, channel.rawChannelKey(),
                        channel.effectiveRuntimeContributionBlueId(), channel.subscriptionHeaderBlueId());
                delivery = context.deliveryBasisIdentity(cause.causeIdentity(), "LIVE", Collections.singletonList(receiving), null);
            }
            ClosureInvocationInput input = base.withRootedContext(context, delivery);
            Capture capture = new Capture();
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                ClosureAttemptResult attempt = contracts.processClosure(input);
                assertTrue(attempt.isComplete(), () -> "Actual checkpoint fixture suspended: " + attempt.resourceDemands());
                assertNotNull(capture.evidence);
                return new Run(input, attempt.processResult(), capture.evidence);
            }
        }

        private Node root(String label, String handlerKey, boolean catalog) {
            Node root = new Node().name("Checkpoint ownership " + label)
                    .properties("label", new Node().value(label))
                    .properties("business", new Node().value("same"))
                    .contracts(new Node().properties("ownerChannel", source(catalog))
                            .properties(handlerKey, handler("ownerChannel")));
            String authored = blueId(root);
            exact.put(authored, root.clone());
            root.getContracts().properties("initialized",
                    typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                            .properties("document", new Node().blueId(authored)));
            return root;
        }

        private Node staleCheckpointSource(boolean cyclic) {
            Node source = root("b", "catalogChange", true);
            if (cyclic) {
                source.getContracts().properties("embedded", embedded("/parent"));
            }
            Node frozen = seedCheckpoint(source, "ownerChannel",
                    after -> after.getContracts().properties("never", handler("ownerChannel")));
            try (ManagedDocumentStepRuntime step = new ManagedDocumentStepRuntime(owner)) {
                String currentDomain = step.projectRootSubscriptionSurface(frozen)
                        .externalSubscriptions().get(0).checkpointDomainBlueId();
                assertNotEquals(blueId(frozen.getAsNode(CHECKPOINT + "/domain")), currentDomain,
                        "The historical checkpoint deliberately retains its pre-change catalog domain");
            }
            return frozen;
        }

        private Node rootWithOtherCheckpoint(String handlerKey) {
            Node a = root("a", handlerKey, false);
            a.getContracts().properties("other", source(false))
                    .properties("oldOther", handler("other"));
            return seedCheckpoint(a, "other", ignored -> { });
        }

        private Node seedCheckpoint(Node before, String channel, Consumer<Node> change) {
            Node event = event("previous-" + before.getAsText("/label") + "-" + channel);
            exact.put(blueId(event), event.clone());
            GasChargeContext context = GasChargeContext.closure(before.getAsText("/label"),
                    "/", 0L, 1L, channel, null, null, "fixture.seed-checkpoint");
            try (ManagedDocumentStepRuntime step = new ManagedDocumentStepRuntime(owner)) {
                ManagedCheckpointCandidate candidate = step.classifyExternalDelivery(
                        before,
                        channel,
                        ExactEventIdentityEvidence.verify(
                                null,
                                event,
                                blueId(event),
                                null),
                        context).candidate();
                assertNotNull(candidate);
                exact.put(candidate.domain().blueId(), candidate.domain().exactValue());
                Node after = before.clone();
                change.accept(after);
                Node seeded = step.settleCheckpoints(after, Collections.singletonList(
                        new ManagedCheckpointSettlementEntry(candidate, 0L, context)),
                        (key, ordinal) -> context, context).resultingBody();
                String referencedId = blueId(seeded);
                // The pure fixture has no Language snapshot manager. Keep the
                // complete immutable descriptor inline without changing its BlueId.
                NodePathEditor.put(seeded, "/contracts/checkpoint/entries/" + channel + "/domain",
                        candidate.domain().exactValue());
                assertEquals(referencedId, blueId(seeded));
                return seeded;
            }
        }

        private ManagedOccurrenceBinding binding(DocumentId source, String path,
                DocumentId target, String expectedId) {
            return ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(),
                    source, ScopeAddress.embedded(path, 1L), target, expectedId, true, null);
        }

        private AffectedClosureSnapshot snapshot(Map<DocumentId, Node> bodies,
                List<ManagedOccurrenceBinding> bindings) {
            Map<DocumentId, Long> generations = new LinkedHashMap<>();
            bodies.keySet().forEach(id -> generations.put(id, 1L));
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                    new ComponentFinalizationInput(ManagedDocumentGraph.fromBindings(bodies.keySet(), bindings),
                            generations, bodies, bindings));
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            finalized.documents().forEach((id, value) -> documents.add(new ManagedDocumentSnapshot(
                    id, value.blueId(), value.document(), true, false, A.equals(id),
                    A.equals(id) ? 4L : 3L, value.componentGeneration())));
            return ClosureEvidenceFactory.affectedClosure(1L, documents,
                    finalized.finalizedGraph().bindings(), finalized.components().stream()
                            .map(FinalizedComponentEvidence::component).collect(Collectors.toList()),
                    Collections.singletonList(A));
        }

        private Run run(AffectedClosureSnapshot snapshot) {
            Node event = event("next-a");
            exact.put(blueId(event), event.clone());
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, blueId(event),
                    ExternalOrderKey.of(Arrays.<Object>asList(1L, "checkpoint-ownership")),
                    environment.externalOrderPolicyIdentity());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, cause,
                    Collections.singletonList(new DirectLogicalDelivery(
                            ManagedScopeKey.root(A), "ownerChannel", "ownerChannel", 0L)),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(),
                            "checkpoint-ownership-v1"), environment);
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }
            assertTrue(attempt.isComplete(), () -> "Fixture resource boundary: " + attempt.kind()
                    + " exact=" + attempt.requiredExactBlueIds());
            assertNotNull(capture.evidence);
            return new Run(input, attempt.processResult(), capture.evidence);
        }

        private Run external(AffectedClosureSnapshot snapshot, DocumentId target,
                Node event, long order, Long historicalEpoch) {
            exact.put(blueId(event), event.clone());
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, blueId(event),
                    ExternalOrderKey.of(Arrays.<Object>asList(order, "checkpoint-ownership")),
                    environment.externalOrderPolicyIdentity());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, cause,
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(target),
                            "ownerChannel", "ownerChannel", 0L)),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "representation-return-v1"), environment);
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
                if (historicalEpoch != null) {
                    assertFalse(attempt.isComplete(), "Saved history must require explicit managed occurrence evidence");
                    assertEquals(1, attempt.resourceDemands().size());
                    ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) attempt.resourceDemands().get(0);
                    List<ManagedOccurrenceBinding> rows = new ArrayList<>(snapshot.occurrences());
                    rows.add(ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(),
                            demand.sourceDocumentId(), ScopeAddress.embedded(demand.sourcePath(), 1L),
                            A, demand.suppliedValueBlueId(), false, historicalEpoch));
                    AffectedClosureSnapshot expanded = ClosureEvidenceFactory.affectedClosure(
                            snapshot.graphGeneration(), snapshot.managedDocuments(), rows,
                            snapshot.components(), snapshot.publicRootDocumentIds());
                    input = ClosureEvidenceFactory.processClosure(expanded, cause, input.directDeliveries(),
                            input.executionPolicy(), environment);
                    attempt = contracts.processClosure(input);
                }
            }
            assertTrue(attempt.isComplete(), "Exact fixture resources must be retained");
            return new Run(input, attempt.processResult(), capture.evidence);
        }

        private Run process(AffectedClosureSnapshot snapshot, ProcessingCause cause) {
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, cause, Collections.emptyList(),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "representation-return-v1"), environment);
            Capture capture = new Capture();
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                ClosureAttemptResult attempt = contracts.processClosure(input);
                assertTrue(attempt.isComplete());
                return new Run(input, attempt.processResult(), capture.evidence);
            }
        }

        private AffectedClosureSnapshot after(ClosureProcessResult result) {
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            for (ResultingDocument value : result.resultingDocuments()) {
                exact.put(value.afterBlueId(), value.document());
                documents.add(new ManagedDocumentSnapshot(value.documentId(), value.afterBlueId(), value.document(),
                        value.initialized(), value.terminated(), value.publicRoot(), value.epoch(), value.componentGeneration()));
            }
            return ClosureEvidenceFactory.affectedClosure(result.graphGeneration(), documents,
                    result.occurrenceBindings(), result.resultingComponents(), Collections.singletonList(A));
        }

        private AffectedClosureSnapshot withPendingConsumer(AffectedClosureSnapshot source,
                DocumentId consumerId, Node consumer, DocumentId target, long epoch) {
            return withPendingConsumer(source, consumerId, consumer, target, epoch,
                    source.managedDocument(target).blueId());
        }

        private AffectedClosureSnapshot withPendingConsumer(AffectedClosureSnapshot source,
                DocumentId consumerId, Node consumer, DocumentId target, long epoch, String expectedBlueId) {
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            Map<DocumentId, Long> generations = new LinkedHashMap<>();
            source.managedDocuments().forEach(value -> {
                bodies.put(value.documentId(), value.document());
                generations.put(value.documentId(), value.componentGeneration());
            });
            bodies.put(consumerId, consumer);
            generations.put(consumerId, 1L);
            List<ManagedOccurrenceBinding> rows = new ArrayList<>(source.occurrences());
            rows.add(ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), consumerId,
                    ScopeAddress.embedded("/peer", 1L), target, expectedBlueId, false, epoch));
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                    new ComponentFinalizationInput(ManagedDocumentGraph.fromBindings(bodies.keySet(), rows),
                            generations, bodies, rows));
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            finalized.documents().forEach((id, value) -> documents.add(new ManagedDocumentSnapshot(id,
                    value.blueId(), value.document(), true, false, A.equals(id),
                    id.equals(consumerId) ? 0L : source.managedDocument(id).epoch(), value.componentGeneration())));
            return ClosureEvidenceFactory.affectedClosure(source.graphGeneration(), documents,
                    finalized.finalizedGraph().bindings(), finalized.components().stream()
                            .map(FinalizedComponentEvidence::component).collect(Collectors.toList()), Collections.singletonList(A));
        }

        @Override
        public void close() {
            owner.close();
        }
    }

    /** Fixture-only Channel with an optional Timeline-shaped catalog dependency. */
    public static final class Source extends ChannelContract {
        private Boolean catalog;
        public Boolean getCatalog() { return catalog; }
        public void setCatalog(Boolean value) { catalog = value; }
    }

    private static final class SourceProcessor implements ChannelProcessor<Source> {
        @Override
        public Class<Source> contractType() { return Source.class; }

        @Override
        public ExternalChannelSubscriptionFunctions<Source> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<Source>() {
                @Override
                public List<String> channelKeys(Source channel) {
                    return Collections.singletonList("checkpoint-ownership");
                }
                @Override
                public boolean preselects(Source channel, Node event) { return true; }
                @Override
                public boolean accepts(Source channel, Node event) { return true; }
                @Override
                public Node payload(Source channel, Node event) { return event.clone(); }
                @Override
                public String checkpointDomainDiscriminator(Source channel,
                        ExternalChannelFunctionContext context) {
                    if (Boolean.TRUE.equals(channel.getCatalog())) {
                        context.dependOnSameScopeChannelCatalog();
                    }
                    return "checkpoint-ownership-source-v1";
                }
            };
        }
    }

    /** Fixture-only Handler; its exact raw contract key chooses the action. */
    public static final class Probe extends HandlerContract { }

    private static final class ProbeProcessor implements HandlerProcessor<Probe> {
        private final List<String> calls = new ArrayList<>();
        @Override
        public Class<Probe> contractType() { return Probe.class; }
        @Override
        public void execute(Probe contract, ProcessorExecutionContext context) {
            String key = context.contractKey();
            calls.add(context.documentAt("/label").getValue() + ":" + key);
            if ("change".equals(key)) {
                context.applyPatch(JsonPatch.replace("/business", new Node().value("changed")));
            } else if ("assign".equals(key)) {
                context.applyPatch(JsonPatch.replace("/business", NodePathEditor.getOrNull(context.event(), "/business")));
            } else if ("attach".equals(key)) {
                context.applyPatch(JsonPatch.add("/peer", NodePathEditor.getOrNull(context.event(), "/target")));
            } else if ("observe".equals(key)) {
                java.math.BigInteger count = (java.math.BigInteger) context.documentAt("/observed").getValue();
                context.applyPatch(JsonPatch.replace("/observed", new Node().value(count.add(java.math.BigInteger.ONE))));
            } else if ("removeOther".equals(key) || "fail".equals(key)) {
                context.applyPatch(JsonPatch.remove("/contracts/oldOther"));
                context.applyPatch(JsonPatch.remove("/contracts/other"));
                if ("fail".equals(key)) {
                    throw new IllegalStateException("checkpoint ownership fixture failure");
                }
            } else if ("catalogChange".equals(key)) {
                context.applyPatch(JsonPatch.add("/contracts/never", handler("ownerChannel")));
            } else if (!"noop".equals(key) && !"oldOther".equals(key)) {
                throw new AssertionError("Untouched retained source was processed: " + key);
            }
        }
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;
        @Override
        public void onExecutionEvidence(ClosureImplementationEvidence value) { evidence = value; }
    }

    private static final class Run {
        private final ClosureInvocationInput input;
        private final ClosureProcessResult result;
        private final ClosureImplementationEvidence evidence;
        private Run(ClosureInvocationInput input, ClosureProcessResult result,
                ClosureImplementationEvidence evidence) {
            this.input = input;
            this.result = result;
            this.evidence = evidence;
        }
    }

    private static Node source(boolean catalog) {
        return typed(CHANNEL_ID).properties("catalog", new Node().value(catalog));
    }
    private static Node handler(String channel) {
        return typed(HANDLER_ID).properties("channel", new Node().value(channel));
    }
    private static Node embedded(String path) {
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                .properties("paths", new Node().items(new Node().value(path)));
    }
    private static Node typed(String id) { return new Node().type(new Node().blueId(id)); }
    private static Node event(String label) {
        return new Node().name(label).properties("subscriptionKey", new Node().value("checkpoint-ownership"));
    }
    private static String blueId(Node value) { return DirectBlueIdCalculator.calculateBlueId(value); }
    private static String hash(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return "sha256:" + new String(chars);
    }
    private static Map<DocumentId, Node> bodies(Node a, Node b) {
        Map<DocumentId, Node> result = new LinkedHashMap<>();
        result.put(A, a);
        result.put(B, b);
        return result;
    }
    private static ResultingDocument result(Run run, DocumentId id) {
        return run.result.resultingDocuments().stream()
                .filter(document -> id.equals(document.documentId())).findFirst().get();
    }
    private static List<CheckpointWrite> writesFor(Run run, DocumentId id) {
        return run.result.checkpointWrites().stream()
                .filter(write -> id.equals(write.targetManagedScopeKey().documentId()))
                .collect(Collectors.toList());
    }
    private static void assertSuccess(Run run) {
            assertEquals(ProcessorStatus.SUCCESS, run.result.status(),
                () -> run.result.diagnostic() == null ? "No diagnostic" : run.result.diagnostic().message());
        assertTrue(run.result.commits());
    }
    private static void assertOnlyAWork(Run run) {
        assertFalse(run.evidence.workTrace().isEmpty());
        assertTrue(run.evidence.workTrace().stream().allMatch(work -> A.equals(work.targetDocumentId())));
        assertTrue(run.evidence.workTrace().stream().noneMatch(work -> work.kind() == WorkKind.INITIALIZATION));
    }
    private static void assertNodeEquals(Node expected, Node actual) {
        assertNotNull(actual);
        assertEquals(NodeWireForm.get(expected, NodeWireForm.Strategy.SIMPLE),
                NodeWireForm.get(actual, NodeWireForm.Strategy.SIMPLE));
    }
}
