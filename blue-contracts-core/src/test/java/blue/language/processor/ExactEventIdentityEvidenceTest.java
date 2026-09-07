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

/** Exact event identity regressions at ordinary and cyclic boundaries. */
final class ExactEventIdentityEvidenceTest {

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
