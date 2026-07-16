package blue.language;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness boundary for hosts that intentionally process a materialized
 * Resolved View as the Selected Document.
 */
class ResolvedProcessingSelectionCorrectnessTest {

    @Test
    void resolvedSnapshotSelectsItsMaterializedInheritedWorkflow() {
        SyntheticWorkflowProcessingFixture fixture = new SyntheticWorkflowProcessingFixture();
        ResolvedSnapshot selected = fixture.blue.resolveToSnapshot(fixture.source.clone());

        DocumentProcessingResult result = assertDoesNotThrow(
                () -> fixture.blue.initializeDocument(selected));

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertEquals(1, fixture.handlerExecutions.get());
        assertEquals("after", result.document().getAsText("/probe"));
        assertTrue(hasContract(result.document(), "workflow"));
    }

    @Test
    void materializedResolvedNodeDoesNotReapplyItsTypeContribution() {
        SyntheticWorkflowProcessingFixture fixture = new SyntheticWorkflowProcessingFixture();
        Node selected = fixture.blue.resolveToSnapshot(fixture.source.clone()).resolvedRoot();

        DocumentProcessingResult result = assertDoesNotThrow(
                () -> fixture.blue.initializeDocument(selected));

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertEquals(1, fixture.handlerExecutions.get());
        assertEquals("after", result.document().getAsText("/probe"));
        assertTrue(hasContract(result.document(), "workflow"));
    }

    private static boolean hasContract(Node document, String key) {
        return document != null
                && document.getContracts() != null
                && document.getContracts().getProperties() != null
                && document.getContracts().getProperties().containsKey(key);
    }
}
