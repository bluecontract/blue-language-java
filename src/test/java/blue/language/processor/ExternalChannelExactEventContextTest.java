package blue.language.processor;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ExternalChannelExactEventContextTest {
    @Test
    void strictInputIdentitySurvivesCapturedContextAndCallerMutation() {
        Node event = new Node().properties("paths", new Node().items(Collections.singletonList(
                new Node().value("/peer"))));
        FrozenNode strict = FrozenNode.fromNode(event);
        GasMeter gas = new GasMeter();
        RuntimeWorkSession session = new RuntimeWorkSession(gas, RuntimeWorkSession.Mode.ADMISSION);
        try {
            session.carryExactInput(strict, strict.blueId());
            ExternalChannelFunctionContext context = context(session, event);
            event.properties("tamperedAfterCapture", new Node().value(true));
            assertEquals(strict.blueId(), context.exactEventBlueId());
            assertEquals(strict.blueId(), context.exactEventBlueId());
            assertEquals(0L, gas.totalGas());
            assertTrue(session.stagedTrace().isEmpty());
            session.suspend();
            IllegalStateException closed = assertThrows(IllegalStateException.class, context::exactEventBlueId);
            assertEquals("External Channel exact event identity is no longer active", closed.getMessage());
        } finally {
            if (session.isOpen()) session.suspend();
            session.close();
        }
    }

    @Test
    void missingConflictingAndHeaderOnlyEvidenceKeepTheirExactFailures() {
        Node event = new Node().properties("kind", new Node().value("source"));
        FrozenNode strict = FrozenNode.fromNode(event);
        RuntimeWorkSession session = new RuntimeWorkSession(new GasMeter(), RuntimeWorkSession.Mode.ADMISSION);
        try {
            ExternalChannelFunctionContext context = context(session, event);
            assertEquals("External Channel exact event identity was not admitted",
                    assertThrows(IllegalStateException.class, context::exactEventBlueId).getMessage());
            assertEquals("External Channel exact event identity is available only during event evaluation",
                    assertThrows(IllegalStateException.class, context(session, null)::exactEventBlueId).getMessage());
            session.carryExactInput(strict, strict.blueId());
            assertEquals(strict.blueId(), context.exactEventBlueId());
            // This internal seam deliberately injects a conflicting carried capability;
            // it does not claim that public Source admission would accept the false ID.
            session.carryExactInput(FrozenNode.fromResolvedNode(event),
                    DirectBlueIdCalculator.calculateBlueId(new Node().name("unrelated admitted value")));
            IllegalStateException ambiguous = assertThrows(IllegalStateException.class, context::exactEventBlueId);
            assertEquals("External Channel exact event identity is ambiguous", ambiguous.getMessage());
            assertTrue(ambiguous.getCause() instanceof InvalidExecutionEvidenceException);
        } finally {
            session.suspend();
            session.close();
        }
    }

    @Test
    void completeCyclicEvidenceAndPureMemberReferencesKeepTheirAdmittedIdentity() {
        Node placeholder = new Node().properties("kind", new Node().value("cycle"))
                .properties("self", new Node().blueId("this#0"));
        String member = CircularSetIdentityCalculator.calculateCircularSetFinalization(
                Collections.singletonList(placeholder)).membersInInputOrder().get(0).finalBlueId();
        Node resolved = placeholder.clone();
        resolved.getProperties().get("self").blueId(member);
        ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(null, resolved, member,
                CyclicSetProof.fromDeclaredPlaceholderSet(Collections.singletonList(placeholder)));
        assertNotEquals(member, DirectBlueIdCalculator.calculateBlueId(resolved));
        RuntimeWorkSession session = new RuntimeWorkSession(new GasMeter(), RuntimeWorkSession.Mode.ADMISSION);
        try {
            session.carryExactInput(evidence.frozenEvent(), member);
            assertEquals(member, context(session, resolved).exactEventBlueId());
            // A materialized member capability alone does not admit its reference
            // representation at the function-context boundary.
            assertEquals("External Channel exact event identity was not admitted",
                    assertThrows(IllegalStateException.class,
                            context(session, new Node().blueId(member))::exactEventBlueId).getMessage());
            assertEquals("External Channel exact event identity was not admitted",
                    assertThrows(IllegalStateException.class, context(session,
                            resolved.clone().properties("tampered", new Node().value(true)))::exactEventBlueId)
                            .getMessage());
            assertTrue(session.stagedTrace().isEmpty());
        } finally {
            session.suspend();
            session.close();
        }
    }

    @Test
    void pureReferenceRequiresItsExactCarriedReferenceRepresentation() {
        String id = DirectBlueIdCalculator.calculateBlueId(new Node().name("reference target"));
        Node reference = new Node().blueId(id);
        RuntimeWorkSession session = new RuntimeWorkSession(new GasMeter(), RuntimeWorkSession.Mode.ADMISSION);
        try {
            session.carryExactInput(FrozenNode.fromResolvedNode(reference), id);
            assertEquals(id, context(session, reference).exactEventBlueId());
            assertTrue(session.stagedTrace().isEmpty());
        } finally {
            session.suspend();
            session.close();
        }
    }

    private static ExternalChannelFunctionContext context(RuntimeWorkSession session, Node event) {
        return new ExternalChannelFunctionContext("/", "source", UNUSED_ACCESS, session, event);
    }

    private static final ExternalChannelFunctionContext.Access UNUSED_ACCESS =
            new ExternalChannelFunctionContext.Access() {
        private AssertionError unexpected() { return new AssertionError("Identity lookup must not consult dependencies"); }
        @Override public ExternalChannelMemberSnapshot member(String key) { throw unexpected(); }
        @Override public List<ExternalChannelMemberSnapshot> members() { throw unexpected(); }
        @Override public List<ExternalChannelMemberSnapshot> membersByEffectiveType(String id) { throw unexpected(); }
        @Override public List<ExternalChannelMemberSnapshot> membersAssignableToType(String id) { throw unexpected(); }
        @Override public ChannelMemberSnapshot dependOnSameScopeChannel(String key) { throw unexpected(); }
        @Override public void dependOnSameScopeChannelCatalog() { throw unexpected(); }
        @Override public ChannelLookupResult lookupChannel(String key) { throw unexpected(); }
        @Override public boolean matchesPattern(FrozenNode candidate, FrozenNode pattern) { throw unexpected(); }
        @Override public FrozenNode materializeExactReference(FrozenNode reference) { throw unexpected(); }
    };
}
