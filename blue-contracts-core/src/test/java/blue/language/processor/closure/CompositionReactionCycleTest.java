package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.util.*;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class CompositionReactionCycleTest {
    @Test
    void shouldFollowAnIndependentCountdownModelAcrossLongerCycles() {
        // given
        for (String domain : Arrays.asList("Agreement-Orders-Payments", "Experiment-Samples-Readings")) {
            for (int size : new int[]{3, 5, 8}) {
                try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
                    int remaining = size * 2 + 1;
                    ClosureInvocationInput input = fixture.admission(ring(fixture, domain, size, remaining), 100_000L);
                    List<String> expected = new ArrayList<>();
                    int receiver = size - 1;
                    for (int token = remaining; token >= 0; token--) {
                        expected.add(domain + receiver + ":" + token);
                        receiver = (receiver + size - 1) % size;
                    }
                    // when
                    ClosureProcessResult result = fixture.admit(input).processResult();
                    // then
                    assertNotNull(result);
                    assertTrue(result.commits(), diagnostic(result));
                    assertEquals(expected, fixture.reactions);
                    assertEquals(size, new HashSet<>(fixture.initialized).size());
                    assertEquals(size, fixture.initialized.size());
                    for (ResultingDocument document : result.resultingDocuments()) {
                        assertTrue(document.initialized());
                        String label = document.document().getAsText("/label");
                        long count = expected.stream().filter(value -> value.startsWith(label + ":")).count();
                        assertEquals(count, ((Number) document.document().getNode("/count").getValue()).longValue());
                    }
                    assertEquals(1 + (remaining / size), result.publicEvents().size());
                    assertEquals(size, result.managedTransitionReceipts().size());
                }
            }
        }
    }

    @Test
    void shouldRollbackEveryDocumentAndPublicEmissionAtDifferentFailureSteps() {
        // given
        for (int failAt : new int[]{1, 4, 9}) {
            try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
                fixture.failAt = failAt;
                ClosureInvocationInput input = fixture.admission(ring(fixture, "graph", 5, 12), 100_000L);
                // when
                ClosureProcessResult result = fixture.admit(input).processResult();
                // then
                assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), diagnostic(result));
                assertEquals(failAt, fixture.reactions.size());
                rollback(input, result);
            }
        }
    }

    @Test
    void shouldExhaustOneSharedGasBudgetForANonquiescentCycle() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            fixture.loop = true;
            ClosureInvocationInput input = fixture.admission(ring(fixture, "loop", 5, 1), 60_000L);
            // when
            ClosureProcessResult result = fixture.admit(input).processResult();
            // then
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, result.status(), diagnostic(result));
            assertTrue(fixture.reactions.size() > 5);
            assertTrue(result.totalGas() <= 60_000L);
            assertNotNull(result.rejectedCharge());
            rollback(input, result);
        }
    }

    @Test
    void shouldCommitAtTheExactGasBoundaryAndRollbackOneUnitBelowIt() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            AffectedClosureSnapshot graph = ring(fixture, "boundary", 5, 11);
            ClosureProcessResult measured = fixture.admit(fixture.admission(graph, 100_000L)).processResult();
            assertTrue(measured.commits(), diagnostic(measured));
            long required = measured.totalGas();
            // when
            fixture.initialized.clear(); fixture.reactions.clear();
            ClosureProcessResult exact = fixture.admit(fixture.admission(graph, required)).processResult();
            ClosureInvocationInput belowInput = fixture.admission(graph, required - 1L);
            fixture.initialized.clear(); fixture.reactions.clear();
            ClosureProcessResult below = fixture.admit(belowInput).processResult();
            // then: the measured total is a budget probe, not the transition oracle.
            assertTrue(exact.commits(), diagnostic(exact));
            assertEquals(required, exact.totalGas());
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, below.status(), diagnostic(below));
            assertNotNull(below.rejectedCharge());
            rollback(belowInput, below);
        }
    }

    static AffectedClosureSnapshot ring(CompositionCampaignFixture fixture, String domain, int size, int remaining) {
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
        // Deliberately reverse physical insertion order.
        for (int i = size - 1; i >= 0; i--) {
            Node body = document(domain + i).properties("next", new Node().blueId("placeholder"));
            body.getContracts().properties("embedded", process("paths", "/next"))
                    .properties("fromNext", embedded("/next"))
                    .properties("react", handler("fromNext"));
            if (i == 0) body.properties("seed", new Node().value(remaining));
            bodies.put(new DocumentId("d" + i), body);
        }
        for (int i = 0; i < size; i++) {
            DocumentId source = new DocumentId("d" + i);
            DocumentId target = new DocumentId("d" + ((i + 1) % size));
            // Finalization substitutes exact cyclic identities before admission.
            bindings.add(fixture.binding(source, "/next", target,
                    new Node().name("pre-finalization peer"), true));
        }
        return snapshot(bodies, bindings, new DocumentId("d0"));
    }

}
