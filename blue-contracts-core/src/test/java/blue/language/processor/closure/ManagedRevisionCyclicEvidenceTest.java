package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict retained cyclic-member evidence at the managed-revision boundary. */
final class ManagedRevisionCyclicEvidenceTest {

    private static final DocumentId PARENT = new DocumentId("parent");
    private static final DocumentId A = new DocumentId("simple-a");
    private static final DocumentId B = new DocumentId("simple-b");

    @Test
    void typedReceiptCyclicCauseVerifiesAndRetainsProofDefensively() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            Fixture fixture = fixture(owner);
            ManagedRevisionCause cause = fixture.cause;

            ClosureInvocationVerifier.Verification verified =
                    ClosureInvocationVerifier.verify(fixture.input, null);

            assertEquals(fixture.input.invocationIdentity(),
                    verified.invocationIdentity());
            assertTrue(cause.sourceTransitionReceipt().isPresent());
            assertEquals(fixture.receipt.transitionReceiptIdentity(),
                    cause.sourceRevisionReceiptIdentity());
            assertTrue(cause.afterCyclicProof().isPresent());
            assertEquals(
                    ClosureIdentityService.INSTANCE
                            .managedRevisionCauseIdentity(
                                    cause.targetOccurrenceIdentity(),
                                    cause.childDocumentId(),
                                    cause.fromEpoch(),
                                    cause.toEpoch(),
                                    cause.beforeBlueId(),
                                    cause.afterBlueId(),
                                    cause.originalSourceCauseIdentity(),
                                    cause.sourceRevisionReceiptIdentity()),
                    cause.causeIdentity(),
                    "cyclic proof must not fork the established cause identity");

            CyclicSetProof first = cause.afterCyclicProof().get();
            CyclicSetProof second = cause.afterCyclicProof().get();
            assertNotSame(first, second);
            first.declaredPlaceholderSet().get(0)
                    .properties("tampered", new Node().value(Boolean.TRUE));
            ClosureInvocationVerifier.verify(fixture.input, null);
        }
    }

    @Test
    void rejectsTamperedProofBodyMasterAndSuffix() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            Fixture fixture = fixture(owner);
            CyclicSetProof exactProof = proof();
            Node exactBody = bodyA();

            List<Node> tamperedMembers = new ArrayList<Node>(
                    exactProof.declaredPlaceholderSet());
            tamperedMembers.get(0).properties(
                    "tampered", new Node().value(Boolean.TRUE));
            CyclicSetProof tamperedProof =
                    CyclicSetProof.fromDeclaredPlaceholderSet(tamperedMembers);
            assertVerifierRejects(fixture, cause(
                    fixture,
                    fixture.receipt,
                    exactBody,
                    tamperedProof));

            Node tamperedBody = exactBody.clone().properties(
                    "tampered", new Node().value(Boolean.TRUE));
            assertVerifierRejects(fixture, cause(
                    fixture,
                    fixture.receipt,
                    tamperedBody,
                    exactProof));

            String anotherMaster = DirectBlueIdCalculator.calculateBlueId(
                    new Node().name("another master"));
            String validAfter = fixture.receipt.afterBlueId();
            ManagedDocumentTransitionReceipt tamperedMaster = receipt(
                    fixture,
                    anotherMaster
                            + validAfter.substring(
                                    validAfter.lastIndexOf('#')));
            assertVerifierRejects(fixture, cause(
                    fixture,
                    tamperedMaster,
                    exactBody,
                    exactProof));

            ManagedDocumentTransitionReceipt tamperedSuffix = receipt(
                    fixture, cyclicMaster() + "#99");
            assertVerifierRejects(fixture, cause(
                    fixture,
                    tamperedSuffix,
                    exactBody,
                    exactProof));
        }
    }

    @Test
    void shouldRejectInvalidCyclicProofAtManagedInvocationAdmission() {
        // given
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            Fixture fixture = fixture(owner);
            List<Node> tamperedMembers = new ArrayList<Node>(
                    proof().declaredPlaceholderSet());
            tamperedMembers.get(0).properties(
                    "tampered", new Node().value(Boolean.TRUE));
            ManagedRevisionCause invalidCause = cause(
                    fixture,
                    fixture.receipt,
                    bodyA(),
                    CyclicSetProof.fromDeclaredPlaceholderSet(
                            tamperedMembers));
            ClosureInvocationInput invalidInput =
                    ClosureEvidenceFactory.processClosure(
                            fixture.input.snapshot(),
                            invalidCause,
                            fixture.input.directDeliveries(),
                            fixture.input.executionPolicy(),
                            fixture.input.environment());

            // when
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ClosureInvocationVerifier.verify(
                            invalidInput, null));

            // then
            assertTrue(failure.getMessage() != null
                    && !failure.getMessage().isEmpty());
        }
    }

    @Test
    void legacyAcyclicCauseRetainsEstablishedIdentityAndHasNoProof() {
        Node before = new Node().name("legacy before");
        Node after = new Node().name("legacy after");
        String beforeBlueId = DirectBlueIdCalculator.calculateBlueId(before);
        String afterBlueId = DirectBlueIdCalculator.calculateBlueId(after);
        ManagedRevisionCause legacy =
                ClosureEvidenceFactory.managedRevisionCause(
                        hash('5'),
                        A,
                        4L,
                        5L,
                        beforeBlueId,
                        afterBlueId,
                        after,
                        hash('6'));
        ManagedRevisionCause explicitNullProof =
                ClosureEvidenceFactory.managedRevisionCause(
                        hash('5'),
                        A,
                        4L,
                        5L,
                        beforeBlueId,
                        afterBlueId,
                        after,
                        hash('6'),
                        null);

        assertEquals(
                ClosureIdentityService.INSTANCE
                        .managedRevisionCauseIdentity(
                                legacy.targetOccurrenceIdentity(),
                                legacy.childDocumentId(),
                                legacy.fromEpoch(),
                                legacy.toEpoch(),
                                legacy.beforeBlueId(),
                                legacy.afterBlueId(),
                                legacy.originalSourceCauseIdentity(),
                                legacy.sourceRevisionReceiptIdentity()),
                legacy.causeIdentity());
        assertEquals(legacy.causeIdentity(),
                explicitNullProof.causeIdentity());
        assertFalse(legacy.afterCyclicProof().isPresent());
    }

    @Test
    void requiresProofExactlyForCyclicSuccessors() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            Fixture fixture = fixture(owner);
            assertThrows(IllegalArgumentException.class, () ->
                    ClosureEvidenceFactory.managedRevisionCause(
                            fixture.cause.targetOccurrenceIdentity(),
                            0L,
                            1L,
                            fixture.cause.afterDocument(),
                            fixture.receipt));

            Node plain = new Node().name("plain successor");
            String plainBlueId = DirectBlueIdCalculator.calculateBlueId(plain);
            ManagedDocumentTransitionReceipt plainReceipt =
                    ManagedDocumentTransitionReceipt.identified(
                            hash('7'),
                            0L,
                            A,
                            hash('8'),
                            fixture.cause.beforeBlueId(),
                            plainBlueId,
                            Collections.<ManagedRootEventOccurrence>emptyList(),
                            1L);
            assertThrows(IllegalArgumentException.class, () ->
                    ClosureEvidenceFactory.managedRevisionCause(
                            fixture.cause.targetOccurrenceIdentity(),
                            0L,
                            1L,
                            plain,
                            plainReceipt,
                            proof()));

            ManagedDocumentTransitionReceipt malformedReceipt =
                    ManagedDocumentTransitionReceipt.identified(
                            hash('7'),
                            0L,
                            A,
                            hash('8'),
                            fixture.cause.beforeBlueId(),
                            "not-a-master#0",
                            Collections
                                    .<ManagedRootEventOccurrence>emptyList(),
                            1L);
            assertThrows(IllegalArgumentException.class, () ->
                    ClosureEvidenceFactory.managedRevisionCause(
                            fixture.cause.targetOccurrenceIdentity(),
                            0L,
                            1L,
                            plain,
                            malformedReceipt,
                            proof()));
        }
    }

    private static Fixture fixture(DocumentProcessor owner) {
        ClosureEnvironment environment = ClosureEvidenceFactory.environment(
                owner,
                hash('a'),
                hash('b'),
                "cyclic-cause-document-lineage-v1",
                "cyclic-cause-binding-lineage-v1",
                "cyclic-cause-provider-domain-v1",
                "cyclic-cause-external-order-v1",
                "cyclic-cause-portable-limits-v1",
                GasSchedule.contracts10().portableLimits());
        String policy = environment.managedBindingPolicyIdentity();
        Node historical = new Node().name("historical child");
        String historicalBlueId = DirectBlueIdCalculator.calculateBlueId(
                historical);
        Node parent = markInitialized(new Node().properties(
                "child", new Node().blueId(historicalBlueId)));
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        bodies.put(PARENT, parent);
        bodies.put(A, bodyA());
        bodies.put(B, bodyB());
        ManagedOccurrenceBinding historicalBinding =
                ManagedOccurrenceBinding.derived(
                        policy,
                        PARENT,
                        ScopeAddress.embedded("/child", 1L),
                        A,
                        historicalBlueId,
                        false,
                        Long.valueOf(0L));
        List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                historicalBinding,
                ManagedOccurrenceBinding.derived(
                        policy,
                        A,
                        ScopeAddress.embedded("/b", 1L),
                        B,
                        cyclicBBlueId(),
                        true,
                        null),
                ManagedOccurrenceBinding.derived(
                        policy,
                        B,
                        ScopeAddress.embedded("/a", 1L),
                        A,
                        cyclicABlueId(),
                        true,
                        null));
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        generations.put(PARENT, Long.valueOf(1L));
        generations.put(A, Long.valueOf(1L));
        generations.put(B, Long.valueOf(1L));
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                ManagedDocumentGraph.fromBindings(
                                        bodies.keySet(), bindings),
                                generations,
                                bodies,
                                bindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId documentId : Arrays.asList(PARENT, A, B)) {
            FinalizedDocumentEvidence exact = finalized.document(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    documentId,
                    exact.blueId(),
                    exact.document(),
                    true,
                    false,
                    PARENT.equals(documentId),
                    PARENT.equals(documentId) ? 0L : 1L,
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component : finalized.components()) {
            components.add(component.component());
        }
        AffectedClosureSnapshot snapshot =
                ClosureEvidenceFactory.affectedClosure(
                        1L,
                        documents,
                        finalized.finalizedGraph().bindings(),
                        components,
                        Collections.singletonList(PARENT));
        assertEquals(cyclicABlueId(),
                snapshot.managedDocument(A).blueId());

        ManagedDocumentTransitionReceipt receipt =
                ManagedDocumentTransitionReceipt.identified(
                        hash('3'),
                        0L,
                        A,
                        hash('4'),
                        historicalBlueId,
                        cyclicABlueId(),
                        Collections.<ManagedRootEventOccurrence>emptyList(),
                        1L);
        ManagedRevisionCause cause = ClosureEvidenceFactory
                .managedRevisionCause(
                        historicalBinding.occurrenceIdentity(),
                        0L,
                        1L,
                        snapshot.managedDocument(A).document(),
                        receipt,
                        proof());
        ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(
                snapshot,
                cause,
                Collections.<DirectLogicalDelivery>emptyList(),
                ClosureEvidenceFactory.executionPolicy(
                        100_000L,
                        Collections.<DocumentId, Long>emptyMap(),
                        "cyclic-cause-gas-v1"),
                environment);
        return new Fixture(input, cause, receipt);
    }

    private static ManagedRevisionCause cause(
            Fixture fixture,
            ManagedDocumentTransitionReceipt receipt,
            Node afterDocument,
            CyclicSetProof afterCyclicProof) {
        return ClosureEvidenceFactory.managedRevisionCause(
                fixture.cause.targetOccurrenceIdentity(),
                fixture.cause.fromEpoch(),
                fixture.cause.toEpoch(),
                afterDocument,
                receipt,
                afterCyclicProof);
    }

    private static ManagedDocumentTransitionReceipt receipt(
            Fixture fixture,
            String afterBlueId) {
        return ManagedDocumentTransitionReceipt.identified(
                fixture.receipt.sourceInvocationIdentity(),
                fixture.receipt.transitionOrdinal(),
                fixture.receipt.documentId(),
                fixture.receipt.originalCauseIdentity(),
                fixture.receipt.beforeBlueId(),
                afterBlueId,
                fixture.receipt.emittedRootEvents(),
                fixture.receipt.admittedGas());
    }

    private static void assertVerifierRejects(
            Fixture fixture,
            ManagedRevisionCause cause) {
        ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(
                fixture.input.snapshot(),
                cause,
                fixture.input.directDeliveries(),
                fixture.input.executionPolicy(),
                fixture.input.environment());
        assertThrows(IllegalArgumentException.class,
                () -> ClosureInvocationVerifier.verify(input, null));
    }

    private static CyclicSetProof proof() {
        return CyclicSetProof.fromDeclaredPlaceholderSet(
                Arrays.asList(placeholderA(), placeholderB()));
    }

    private static CyclicSetFinalization cyclicFinalization() {
        return CircularSetIdentityCalculator
                .calculateCircularSetFinalization(
                        Arrays.asList(placeholderA(), placeholderB()));
    }

    private static CyclicSetFinalization authoredFinalization() {
        return CircularSetIdentityCalculator
                .calculateCircularSetFinalization(Arrays.asList(
                        authoredPlaceholderA(),
                        authoredPlaceholderB()));
    }

    private static String authoredABlueId() {
        return authoredFinalization().membersInInputOrder()
                .get(0).finalBlueId();
    }

    private static String authoredBBlueId() {
        return authoredFinalization().membersInInputOrder()
                .get(1).finalBlueId();
    }

    private static String cyclicMaster() {
        return cyclicFinalization().masterBlueId();
    }

    private static String cyclicABlueId() {
        return cyclicFinalization().membersInInputOrder()
                .get(0).finalBlueId();
    }

    private static String cyclicBBlueId() {
        return cyclicFinalization().membersInInputOrder()
                .get(1).finalBlueId();
    }

    private static Node placeholderA() {
        return new Node().properties(
                "documentId", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "memberIdentity", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "b", reference("this#1"),
                "contracts", initializedContracts(authoredABlueId()));
    }

    private static Node placeholderB() {
        return new Node().properties(
                "documentId", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "memberIdentity", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "a", reference("this#0"),
                "contracts", initializedContracts(authoredBBlueId()));
    }

    private static Node authoredPlaceholderA() {
        return new Node().properties(
                "documentId", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "memberIdentity", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "b", reference("this#1"),
                "contracts", reference(
                        "AKdg7JuRiCbPdRARLfWhCoSFQz4htgjc2pcDPWkNPfQJ"));
    }

    private static Node authoredPlaceholderB() {
        return new Node().properties(
                "documentId", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "memberIdentity", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "a", reference("this#0"),
                "contracts", reference(
                        "F4GdSvomgpBDpomh3VuFEg3L6yu2gLsBQeCEyGmYpeuz"));
    }

    private static Node bodyA() {
        Node body = placeholderA();
        body.getProperties().get("b").blueId(cyclicBBlueId());
        return body;
    }

    private static Node bodyB() {
        Node body = placeholderB();
        body.getProperties().get("a").blueId(cyclicABlueId());
        return body;
    }

    private static Node markInitialized(Node document) {
        Node before = document.clone();
        return document.contracts(initializedContracts(
                DirectBlueIdCalculator.calculateBlueId(before)));
    }

    private static Node initializedContracts(String authoredBlueId) {
        return new Node().properties(
                "initialized",
                new Node()
                        .type(reference(
                                RuntimeBlueIds
                                        .PROCESSING_INITIALIZED_MARKER))
                        .properties("document", reference(authoredBlueId)));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String hash(char digit) {
        StringBuilder value = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            value.append(digit);
        }
        return value.toString();
    }

    private static final class Fixture {
        private final ClosureInvocationInput input;
        private final ManagedRevisionCause cause;
        private final ManagedDocumentTransitionReceipt receipt;

        private Fixture(
                ClosureInvocationInput input,
                ManagedRevisionCause cause,
                ManagedDocumentTransitionReceipt receipt) {
            this.input = input;
            this.cause = cause;
            this.receipt = receipt;
        }
    }
}
