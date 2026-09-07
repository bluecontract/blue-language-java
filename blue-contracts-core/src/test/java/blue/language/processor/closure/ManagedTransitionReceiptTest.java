package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused identity, duplicate, and compatibility proofs for receipts. */
final class ManagedTransitionReceiptTest {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final DocumentId SOURCE = new DocumentId("source");
    private static final String INVOCATION = hash('a');
    private static final String ORIGINAL_CAUSE = hash('b');

    @Test
    void eventOnlyReceiptPreservesTwoEqualOccurrences() {
        Node event = new Node().properties("kind", new Node().value("same"));
        String eventBlueId = blueId(event);
        ManagedRootEventOccurrence first = event(
                0L, 3L, eventBlueId, event, false);
        ManagedRootEventOccurrence second = event(
                1L, 4L, eventBlueId, event, false);
        String unchanged = blueId(new Node().name("unchanged"));

        ManagedDocumentTransitionReceipt receipt =
                ManagedDocumentTransitionReceipt.identified(
                        INVOCATION,
                        0L,
                        SOURCE,
                        ORIGINAL_CAUSE,
                        unchanged,
                        unchanged,
                        Arrays.asList(first, second),
                        17L);

        assertEquals(unchanged, receipt.beforeBlueId());
        assertEquals(unchanged, receipt.afterBlueId());
        assertEquals(2, receipt.emittedRootEvents().size());
        assertEquals(eventBlueId,
                receipt.emittedRootEvents().get(0).eventBlueId());
        assertEquals(eventBlueId,
                receipt.emittedRootEvents().get(1).eventBlueId());
        assertNotEquals(
                receipt.emittedRootEvents().get(0).occurrenceIdentity(),
                receipt.emittedRootEvents().get(1).occurrenceIdentity());
        assertFalse(receipt.emittedRootEvents().get(0).publicAtSource());
        assertEquals(17L, receipt.admittedGas());
    }

    @Test
    void stateOnlyReceiptIsValidAndEmptyNoOpIsRejected() {
        String before = blueId(new Node().name("before"));
        String after = blueId(new Node().name("after"));

        ManagedDocumentTransitionReceipt receipt =
                ManagedDocumentTransitionReceipt.identified(
                        INVOCATION,
                        0L,
                        SOURCE,
                        ORIGINAL_CAUSE,
                        before,
                        after,
                        Collections.<ManagedRootEventOccurrence>emptyList(),
                        5L);

        assertTrue(receipt.emittedRootEvents().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocumentTransitionReceipt.identified(
                        INVOCATION,
                        0L,
                        SOURCE,
                        ORIGINAL_CAUSE,
                        before,
                        before,
                        Collections.<ManagedRootEventOccurrence>emptyList(),
                        0L));
    }

    @Test
    void eventBodyOccurrenceAndReceiptTamperingFailClosed() {
        Node event = new Node().properties("kind", new Node().value("exact"));
        String eventBlueId = blueId(event);
        String before = blueId(new Node().name("before"));
        String after = blueId(new Node().name("after"));
        ManagedRootEventOccurrence exact = event(
                0L, 0L, eventBlueId, event, true);
        ManagedDocumentTransitionReceipt receipt =
                ManagedDocumentTransitionReceipt.identified(
                        INVOCATION,
                        0L,
                        SOURCE,
                        ORIGINAL_CAUSE,
                        before,
                        after,
                        Collections.singletonList(exact),
                        1L);

        assertThrows(IllegalArgumentException.class,
                () -> ExactEventIdentityEvidence.verify(
                        null,
                        new Node().name("tampered"),
                        eventBlueId,
                        null));

        ManagedRootEventOccurrence wrongOccurrence =
                new ManagedRootEventOccurrence(
                        0L,
                        0L,
                        SOURCE,
                        hash('c'),
                        exactEvent(event, eventBlueId),
                        true);
        assertThrows(IllegalArgumentException.class,
                () -> ManagedDocumentTransitionReceipt.identified(
                        INVOCATION,
                        0L,
                        SOURCE,
                        ORIGINAL_CAUSE,
                        before,
                        after,
                        Collections.singletonList(wrongOccurrence),
                        1L));

        assertThrows(IllegalArgumentException.class,
                () -> new ManagedDocumentTransitionReceipt(
                        hash('d'),
                        receipt.sourceInvocationIdentity(),
                        receipt.transitionOrdinal(),
                        receipt.transitionOccurrenceIdentity(),
                        receipt.documentId(),
                        receipt.originalCauseIdentity(),
                        receipt.beforeBlueId(),
                        receipt.afterBlueId(),
                        receipt.emittedRootEvents(),
                        receipt.emittedRootEventsIdentity(),
                        receipt.admittedGas()));
    }

    @Test
    void aggregateAndManagedRevisionCauseBindCompleteReceipt() {
        Node event = new Node().properties("kind", new Node().value("exact"));
        String eventBlueId = blueId(event);
        String before = blueId(new Node().name("before"));
        Node afterDocument = new Node().name("after");
        String after = blueId(afterDocument);
        ManagedDocumentTransitionReceipt one =
                ManagedDocumentTransitionReceipt.identified(
                        INVOCATION,
                        0L,
                        SOURCE,
                        ORIGINAL_CAUSE,
                        before,
                        after,
                        Collections.singletonList(event(
                                0L, 0L, eventBlueId, event, false)),
                        3L);
        ManagedDocumentTransitionReceipt duplicate =
                ManagedDocumentTransitionReceipt.identified(
                        INVOCATION,
                        0L,
                        SOURCE,
                        ORIGINAL_CAUSE,
                        before,
                        after,
                        Arrays.asList(
                                event(0L, 0L, eventBlueId, event, false),
                                event(1L, 1L, eventBlueId, event, false)),
                        3L);

        assertNotEquals(
                IDENTITIES.managedTransitionReceiptsIdentity(
                        Collections.singletonList(one)),
                IDENTITIES.managedTransitionReceiptsIdentity(
                        Collections.singletonList(duplicate)));

        ManagedRevisionCause cause = ClosureEvidenceFactory
                .managedRevisionCause(
                        hash('e'), 6L, 7L, afterDocument, duplicate);
        assertTrue(cause.sourceTransitionReceipt().isPresent());
        assertEquals(duplicate.transitionReceiptIdentity(),
                cause.sourceRevisionReceiptIdentity());
        assertEquals(duplicate,
                cause.sourceTransitionReceipt().get());

        ManagedRevisionCause epochZero = ClosureEvidenceFactory
                .managedRevisionCause(
                        hash('e'), -1L, 0L, afterDocument, duplicate);
        assertEquals(-1L, epochZero.fromEpoch());
        assertEquals(0L, epochZero.toEpoch());
        assertEquals(duplicate.transitionReceiptIdentity(),
                epochZero.sourceRevisionReceiptIdentity());
        assertThrows(IllegalArgumentException.class,
                () -> ClosureEvidenceFactory.managedRevisionCause(
                        hash('e'), -2L, -1L,
                        afterDocument, duplicate));
    }

    @Test
    void managedAndPublicOccurrencesPreserveVerifiedCyclicMemberIdentity() {
        Node placeholder = new Node().properties(
                "kind", new Node().value("cyclic-event"),
                "self", new Node().blueId("this#0"));
        CyclicSetFinalization finalized = CircularSetIdentityCalculator
                .calculateCircularSetFinalization(
                        Collections.singletonList(placeholder));
        String eventBlueId = finalized.membersInInputOrder()
                .get(0).finalBlueId();
        Node resolved = placeholder.clone();
        resolved.getProperties().get("self").blueId(eventBlueId);
        ExactEventIdentityEvidence evidence =
                ExactEventIdentityEvidence.verify(
                        null,
                        resolved,
                        eventBlueId,
                        CyclicSetProof.fromDeclaredPlaceholderSet(
                                Collections.singletonList(placeholder)));
        String occurrenceIdentity = IDENTITIES.eventOccurrenceIdentity(
                INVOCATION, 0L, eventBlueId);

        ManagedRootEventOccurrence managed =
                new ManagedRootEventOccurrence(
                        0L,
                        0L,
                        SOURCE,
                        occurrenceIdentity,
                        evidence,
                        true);
        PublicEventOccurrence publicEvent = new PublicEventOccurrence(
                0L,
                0L,
                SOURCE,
                occurrenceIdentity,
                evidence);

        assertEquals(eventBlueId, managed.eventBlueId());
        assertEquals(eventBlueId, publicEvent.eventBlueId());
        FrozenNode.ResolvedStructuralKey expected =
                FrozenNode.fromResolvedNode(resolved)
                        .resolvedStructuralKey();
        assertEquals(expected,
                FrozenNode.fromResolvedNode(managed.exactEvent())
                        .resolvedStructuralKey());
        assertEquals(expected,
                FrozenNode.fromResolvedNode(publicEvent.event())
                        .resolvedStructuralKey());
    }

    private static ManagedRootEventOccurrence event(
            long ordinal,
            long occurrenceOrdinal,
            String eventBlueId,
            Node event,
            boolean publicAtSource) {
        return new ManagedRootEventOccurrence(
                ordinal,
                occurrenceOrdinal,
                SOURCE,
                IDENTITIES.eventOccurrenceIdentity(
                        INVOCATION, occurrenceOrdinal, eventBlueId),
                exactEvent(event, eventBlueId),
                publicAtSource);
    }

    private static ExactEventIdentityEvidence exactEvent(
            Node event,
            String eventBlueId) {
        return ExactEventIdentityEvidence.verify(
                null, event, eventBlueId, null);
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static String hash(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
