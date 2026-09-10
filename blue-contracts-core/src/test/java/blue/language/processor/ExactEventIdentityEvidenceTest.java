package blue.language.processor;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Exact event identity regressions at ordinary and cyclic boundaries. */
final class ExactEventIdentityEvidenceTest {

    @Test
    void retainsCanonicalTypedListChildAtTheExistingExactInputAdmission() {
        Node child = new Node().name("inline source with an unavailable peer")
                .contracts(new Node().properties("embedded", new Node()
                        .type(new Node().blueId(blue.language.processor.registry.RuntimeBlueIds.PROCESS_EMBEDDED))
                        .properties("paths", new Node().items(Collections.singletonList(new Node()
                                .type(new Node().blueId(blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                                .value("/peer"))))))
                .properties("peer", new Node().blueId(DirectBlueIdCalculator.calculateBlueId(
                        new Node().properties("state", new Node().value("unavailable-here")))));
        Node event = new Node().properties("message", new Node().properties("request",
                new Node().properties("child", child)));
        Node original = event.clone();
        String rootId = DirectBlueIdCalculator.calculateBlueId(event);
        String childId = DirectBlueIdCalculator.calculateBlueId(child);
        String resolvedChildId = FrozenNode.fromResolvedNode(child).blueId();
        assertNotEquals(childId, resolvedChildId, "The distinct resolved list lane is preserved");

        ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(null, event, rootId, null);
        assertTrue(evidence.frozenEvent().isStrictCanonical());
        assertSame(evidence.frozenEvent(), evidence.frozenEvent());
        assertEquals(rootId, evidence.frozenEvent().blueId());
        assertEquals(childId, evidence.frozenEvent().at("/message/request/child").blueId());
        assertEquals(FrozenNode.fromResolvedNode(original).resolvedStructuralKey(),
                FrozenNode.fromResolvedNode(evidence.event()).resolvedStructuralKey(),
                "Compare complete observed Source bytes in the same representation mode");
        assertEquals(FrozenNode.fromResolvedNode(original).resolvedStructuralKey(),
                FrozenNode.fromResolvedNode(event).resolvedStructuralKey());
        assertThrows(IllegalArgumentException.class, () -> ExactEventIdentityEvidence.verify(
                null, child, resolvedChildId, null), "A resolved hash must not become canonical authority");
        event.properties("tampered", new Node().value(true));
        assertEquals(rootId, evidence.frozenEvent().blueId(), "The input capability remains immutable");
    }

    @Test
    void inlineNominalTypeSourceStillRequiresLanguageCanonicalization() {
        Node event = new Node().type(new Node().name("Inline exact event type"))
                .properties("payload", new Node().value("authored"));
        String directId = DirectBlueIdCalculator.calculateBlueId(new Node().value("unverified-source"));
        assertThrows(IllegalStateException.class, () -> ExactEventIdentityEvidence.verify(
                null, event, directId, null));
    }

    @Test
    void annotatedExpandedInputKeepsItsExistingIdentityAndObservedSource() {
        Node plain = new Node().properties("child", new Node().value("payload"));
        String expectedId = DirectBlueIdCalculator.calculateBlueId(plain);
        String annotation = DirectBlueIdCalculator.calculateBlueId(new Node().value("annotation"));
        Node annotated = plain.clone().blueId(annotation);
        annotated.getProperties().get("child").blueId(annotation);
        FrozenNode original = FrozenNode.fromResolvedNode(annotated);

        ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(
                null, annotated, expectedId, null);

        assertEquals(expectedId, evidence.eventBlueId());
        org.junit.jupiter.api.Assertions.assertFalse(evidence.frozenEvent().isStrictCanonical(),
                "The new strict-source path must not change existing annotated-input handling");
        assertEquals(original.resolvedStructuralKey(), evidence.frozenEvent().resolvedStructuralKey());
        assertEquals(annotation, evidence.event().getBlueId());
        assertEquals(annotation, evidence.event().getProperties().get("child").getBlueId());
        assertEquals(original.resolvedStructuralKey(), FrozenNode.fromResolvedNode(annotated).resolvedStructuralKey());
    }

    @Test
    void inlineNominalTypeStillUsesTheConfiguredLanguageAndRetainsItsSource() {
        Node event = new Node().type(new Node().name("Configured inline exact event type"))
                .properties("payload", new Node().value("authored"));
        FrozenNode original = FrozenNode.fromResolvedNode(event);
        try (blue.language.runtime.BlueLanguage language = blue.language.runtime.BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(language.processing()).build()) {
            ProcessorRuntimeAccess access = contracts.runtimeAccess();
            String expectedId = access.languageRuntime().calculateSourceDocumentBlueId(event.clone());
            ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(
                    access, event, expectedId, null);

            assertEquals(expectedId, evidence.eventBlueId());
            org.junit.jupiter.api.Assertions.assertFalse(evidence.frozenEvent().isStrictCanonical(),
                    "Inline type Source must stay on the existing Language-owned canonicalization path");
            assertEquals(original.resolvedStructuralKey(), evidence.frozenEvent().resolvedStructuralKey());
            assertEquals(original.resolvedStructuralKey(), FrozenNode.fromResolvedNode(event).resolvedStructuralKey());
        }
    }

    @Test
    void verifiesOrdinarySourceAndRejectsDetachedIdentity() {
        Node event = new Node().properties(
                "kind", new Node().value("ordinary"));
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);

        ExactEventIdentityEvidence evidence =
                ExactEventIdentityEvidence.verify(
                        null, event, eventBlueId, null);

        assertEquals(eventBlueId, evidence.eventBlueId());
        assertEquals("ordinary", evidence.event().getAsText("/kind"));
        assertThrows(IllegalArgumentException.class,
                () -> ExactEventIdentityEvidence.verify(
                        null,
                        new Node().properties(
                                "kind", new Node().value("tampered")),
                        eventBlueId,
                        null));
    }

    @Test
    void preservesPureCyclicReferenceWithoutStandaloneMemberProof() {
        CyclicFixture cycle = cyclicFixture();
        Node reference = new Node().blueId(cycle.memberBlueId);

        ExactEventIdentityEvidence evidence =
                ExactEventIdentityEvidence.verify(
                        null, reference, cycle.memberBlueId, null);

        assertEquals(cycle.memberBlueId, evidence.eventBlueId());
        assertEquals(cycle.memberBlueId, evidence.event().getBlueId());
        assertThrows(IllegalArgumentException.class,
                () -> ExactEventIdentityEvidence.verify(
                        null,
                        reference,
                        cycle.memberBlueId,
                        cycle.proof));
    }

    @Test
    void resolvedCyclicMemberRequiresItsCompleteProof() {
        CyclicFixture cycle = cyclicFixture();

        ExactEventIdentityEvidence evidence =
                ExactEventIdentityEvidence.verify(
                        null,
                        cycle.resolvedMember,
                        cycle.memberBlueId,
                        cycle.proof);

        assertEquals(cycle.memberBlueId, evidence.eventBlueId());
        assertEquals(cycle.memberBlueId,
                evidence.event().getProperties().get("self").getBlueId());
        assertNotEquals(
                cycle.memberBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        cycle.resolvedMember));
        assertThrows(IllegalArgumentException.class,
                () -> ExactEventIdentityEvidence.verify(
                        null,
                        cycle.resolvedMember,
                        cycle.memberBlueId,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> ExactEventIdentityEvidence.verify(
                        null,
                        cycle.resolvedMember.clone().properties(
                                "tampered", new Node().value(true)),
                        cycle.memberBlueId,
                        cycle.proof));
    }

    @Test
    void carriesInvocationAdmittedResolvedCyclicCursorWithoutRehashing() {
        CyclicFixture cycle = cyclicFixture();
        ExactBlueValue admitted = new ExactBlueValue(
                FrozenNode.fromResolvedNode(cycle.resolvedMember),
                cycle.memberBlueId);

        ExactEventIdentityEvidence evidence =
                ExactEventIdentityEvidence.fromAdmitted(admitted);

        assertEquals(cycle.memberBlueId, evidence.eventBlueId());
        assertEquals(
                FrozenNode.fromResolvedNode(cycle.resolvedMember)
                        .resolvedStructuralKey(),
                evidence.frozenEvent().resolvedStructuralKey());
    }

    private static CyclicFixture cyclicFixture() {
        Node placeholder = new Node().properties(
                "kind", new Node().value("self-cycle"),
                "self", new Node().blueId("this#0"));
        CyclicSetFinalization finalized = CircularSetIdentityCalculator
                .calculateCircularSetFinalization(
                        Collections.singletonList(placeholder));
        String memberBlueId = finalized.membersInInputOrder()
                .get(0).finalBlueId();
        Node resolved = placeholder.clone();
        resolved.getProperties().get("self").blueId(memberBlueId);
        return new CyclicFixture(
                memberBlueId,
                resolved,
                CyclicSetProof.fromDeclaredPlaceholderSet(
                        Collections.singletonList(placeholder)));
    }

    private static final class CyclicFixture {
        private final String memberBlueId;
        private final Node resolvedMember;
        private final CyclicSetProof proof;

        private CyclicFixture(
                String memberBlueId,
                Node resolvedMember,
                CyclicSetProof proof) {
            this.memberBlueId = memberBlueId;
            this.resolvedMember = resolvedMember;
            this.proof = proof;
        }
    }
}
