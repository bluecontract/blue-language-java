package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact tests for the isolated managed-document step boundary. */
final class DocumentStepBoundaryTest {

    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");

    @Test
    void shouldDeriveFreshRootContextAndRejectStaleTargetState() {
        AffectedClosureSnapshot state = acyclicState();
        ClosureInvocationInput invocation = invocation(state);
        TentativeResolutionContext context =
                TentativeResolutionContext.from(invocation, state, A);
        ClosureWorkOccurrence work = work(A, 0L);
        Node payload = new Node().name("event");

        DocumentStepInput input = new DocumentStepInput(
                0L, work, state.managedDocument(A), payload, context);
        payload.name("caller-mutation");

        assertEquals("event", input.exactPayload().getName());
        assertEquals("/", input.executionScope().path());
        assertEquals(0L, input.executionScope().activationGeneration());
        assertEquals(Collections.emptyList(),
                input.ambientContainingDocumentIds());
        assertNull(context.cyclicProofIdentity());
        assertThrows(UnsupportedOperationException.class,
                () -> context.currentBlueIds().clear());

        ManagedDocumentSnapshot stale = new ManagedDocumentSnapshot(
                A, "different-blue", new Node().name("a"),
                true, false, true, 0L, 1L);
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentStepInput(
                        0L, work, stale, new Node().name("event"), context));
    }

    @Test
    void shouldKeepLocalResultPreFinalizationAndDefensive() {
        Node body = new Node().name("result");
        Node event = new Node().name("emitted");
        LocalDocumentStepResult result = new LocalDocumentStepResult(
                A, hash('9'), "before-blue", body,
                Collections.singletonList(event), 10L, 15L, true);
        body.name("caller-body-mutation");
        event.name("caller-event-mutation");

        assertEquals("result", result.resultingBody().getName());
        assertEquals("emitted", result.emittedEvents().get(0).getName());
        result.resultingBody().name("returned-body-mutation");
        result.emittedEvents().get(0).name("returned-event-mutation");
        assertEquals("result", result.resultingBody().getName());
        assertEquals("emitted", result.emittedEvents().get(0).getName());
        assertEquals(5L, result.gasAfter() - result.gasBefore());

        for (Method method : LocalDocumentStepResult.class.getMethods()) {
            assertFalse(method.getName().equals("afterBlueId"),
                    "a local result must not claim a finalized after BlueId");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new LocalDocumentStepResult(
                        A, hash('9'), "before-blue", new Node(),
                        Collections.<Node>emptyList(), 16L, 15L, false));
    }

    @Test
    void shouldUseOneProcessorFunctionForAcyclicAndCyclicTargets() {
        AffectedClosureSnapshot acyclic = acyclicState();
        AffectedClosureSnapshot cyclic = cyclicState();
        DocumentStepInput acyclicInput = input(acyclic, invocation(acyclic));
        DocumentStepInput cyclicInput = input(cyclic, invocation(cyclic));
        final int[] calls = new int[1];

        DocumentStepProcessor processor = input -> {
            calls[0]++;
            ManagedDocumentSnapshot target = input.targetDocument();
            return new LocalDocumentStepResult(
                    target.documentId(), input.work().workIdentity(),
                    target.blueId(), target.document(),
                    Collections.<Node>emptyList(), calls[0] - 1L,
                    calls[0], false);
        };

        processor.process(acyclicInput);
        processor.process(cyclicInput);

        assertEquals(2, calls[0]);
        assertNull(acyclicInput.resolutionContext().cyclicProofIdentity());
        assertEquals(hash('5'),
                cyclicInput.resolutionContext().cyclicProofIdentity());
        DocumentStepEvidence evidence = new DocumentStepEvidence(cyclicInput);
        assertEquals(A, evidence.executionRootDocumentId());
        assertEquals("ISOLATED_DOCUMENT", evidence.executionMode());
        assertEquals(Collections.emptyList(),
                evidence.ambientContainingDocumentIds());
    }

    private static DocumentStepInput input(
            AffectedClosureSnapshot state,
            ClosureInvocationInput invocation) {
        return new DocumentStepInput(
                0L,
                work(A, 0L),
                state.managedDocument(A),
                new Node().name("event"),
                TentativeResolutionContext.from(invocation, state, A));
    }

    private static AffectedClosureSnapshot acyclicState() {
        ManagedDocumentSnapshot document = managed(
                A, "blue-a", true);
        ComponentSnapshot component = new ComponentSnapshot(
                hash('1'), hash('2'), 1L, ComponentKind.ACYCLIC,
                Collections.singletonList(A),
                Collections.singletonList("blue-a"),
                null, null, null);
        return new AffectedClosureSnapshot(
                hash('0'), 1L, Collections.singletonList(document),
                Collections.<ManagedOccurrenceBinding>emptyList(), hash('e'),
                Collections.singletonList(component),
                Collections.singletonList(A));
    }

    private static AffectedClosureSnapshot cyclicState() {
        CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                Arrays.asList(
                        new Node().name("a-proof"),
                        new Node().name("b-proof")));
        ComponentSnapshot component = new ComponentSnapshot(
                hash('3'), hash('4'), 2L, ComponentKind.CYCLIC,
                Arrays.asList(A, B),
                Arrays.asList("master#0", "master#1"),
                "master", proof, hash('5'));
        return new AffectedClosureSnapshot(
                hash('6'), 2L,
                Arrays.asList(
                        managed(A, "master#0", true),
                        managed(B, "master#1", false)),
                Arrays.asList(
                        new ManagedOccurrenceBinding(
                                hash('1'), hash('2'), hash('3'),
                                A, ScopeAddress.embedded("/b", 1L),
                                B, "master#1", true, null),
                        new ManagedOccurrenceBinding(
                                hash('4'), hash('5'), hash('6'),
                                B, ScopeAddress.embedded("/a", 1L),
                                A, "master#0", true, null)),
                hash('f'),
                Collections.singletonList(component),
                Collections.singletonList(A));
    }

    private static ManagedDocumentSnapshot managed(
            DocumentId documentId,
            String blueId,
            boolean publicRoot) {
        return new ManagedDocumentSnapshot(
                documentId, blueId, new Node().name(documentId.value()),
                true, false, publicRoot, 0L,
                blueId.startsWith("master#") ? 2L : 1L);
    }

    private static ClosureInvocationInput invocation(
            AffectedClosureSnapshot state) {
        String orderPolicy = hash('a');
        ExternalEventCause cause = new ExternalEventCause(
                hash('b'), new Node().name("event"), "event-blue",
                ExternalOrderKey.of(Arrays.asList(1L, "timeline", 1L)),
                orderPolicy);
        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(A), "channel", "logical", 0L);
        return ClosureInvocationInput.processClosure(
                hash('c'), state, cause, Collections.singletonList(delivery),
                hash('d'),
                new ExecutionPolicy(
                        hash('1'), 1000L,
                        new LinkedHashMap<DocumentId, Long>(), "policy"),
                new ClosureEnvironment(
                        hash('2'), hash('3'), hash('4'), hash('5'),
                        labeled(hash('6'), "document-policy"),
                        labeled(hash('7'), "binding-policy"),
                        labeled(hash('8'), "provider-domain"),
                        labeled(orderPolicy, "external-order-policy"),
                        portable(hash('9')),
                        hash('0'), hash('1')));
    }

    private static ClosureEnvironment.LabeledIdentityEvidence labeled(
            String identity, String label) {
        return new ClosureEnvironment.LabeledIdentityEvidence(identity, label);
    }

    private static ClosureEnvironment.PortableLimitPolicyEvidence portable(
            String identity) {
        return new ClosureEnvironment.PortableLimitPolicyEvidence(
                identity,
                "portable-limits",
                Collections.<String, Long>emptyMap());
    }

    private static ClosureWorkOccurrence work(
            DocumentId target,
            long ordinal) {
        return new ClosureWorkOccurrence(
                ordinal, WorkKind.EXTERNAL_DELIVERY, target,
                "channel", "event-blue", Long.valueOf(0L),
                hash('7'), hash('8'), hash('9'));
    }

    private static String hash(char digit) {
        StringBuilder value = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            value.append(digit);
        }
        return value.toString();
    }
}
