package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The private assembler allowance never weakens public strict snapshot transition validation. */
class ClosureProcessedEpochAuthorityTest {
    private static final DocumentId A = new DocumentId("processed"), B = new DocumentId("not-in-input");

    @Test
    void sameHeadEpochRequiresExactSuccessfulInterpreterOwnership() {
        AffectedClosureSnapshot input = snapshot(7, true), output = snapshot(8, true);
        List<ResultingDocument> results = results(input, output);
        assertThrows(IllegalArgumentException.class, () -> verify(input, output, results, Collections.emptySet()));
        assertDoesNotThrow(() -> verify(input, output, results, Collections.singleton(A)));
        assertThrows(IllegalArgumentException.class, () -> verify(input, output, results, Collections.singleton(B)));
        assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceVerifier.verifyTransition(input, output, results,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null));
    }

    @Test
    void authorityCannotAdvanceMoreThanOncePreserveTheEpochOrInitializeAnEpoch() {
        AffectedClosureSnapshot input = snapshot(7, true);
        for (long epoch : Arrays.asList(6L, 7L, 9L)) {
            AffectedClosureSnapshot output = snapshot(epoch, true);
            assertThrows(IllegalArgumentException.class, () -> verify(input, output, results(input, output), Collections.singleton(A)));
        }
        AffectedClosureSnapshot authored = snapshot(0, false), output = snapshot(1, false);
        assertThrows(IllegalArgumentException.class, () -> verify(authored, output, results(authored, output), Collections.singleton(A)));
    }

    private static void verify(AffectedClosureSnapshot input, AffectedClosureSnapshot output,
                               List<ResultingDocument> results, Set<DocumentId> processed) {
        ClosureEvidenceVerifier.verifyTransition(input, output, results, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, Collections.emptySet(),
                Collections.emptyList(), processed);
    }
    private static AffectedClosureSnapshot snapshot(long epoch, boolean initialized) {
        Node document = new Node().name("unchanged exact head").contracts(new Node());
        if (initialized) {
            String authored = DirectBlueIdCalculator.calculateBlueId(document);
            document.getContracts().properties("initialized", new Node().type(new Node().blueId(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                    .properties("document", new Node().blueId(authored)));
        }
        ManagedDocumentSnapshot state = new ManagedDocumentSnapshot(A, DirectBlueIdCalculator.calculateBlueId(document), document,
                initialized, false, true, epoch, 0);
        return ClosureEvidenceFactory.affectedClosure(0, Collections.singletonList(state), Collections.emptyList(),
                Collections.singletonList(ClosureEvidenceFactory.acyclicComponent(state)), Collections.singletonList(A));
    }
    private static List<ResultingDocument> results(AffectedClosureSnapshot input, AffectedClosureSnapshot output) {
        ManagedDocumentSnapshot state = output.managedDocument(A); ComponentSnapshot component = output.components().get(0);
        return Collections.singletonList(new ResultingDocument(A, input.managedDocument(A).blueId(), state.blueId(), state.document(),
                state.initialized(), state.terminated(), state.publicRoot(), state.epoch(), state.componentGeneration(),
                component.componentIdentity(), component.componentStateIdentity(), null));
    }
}
