package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the progress-only host companion for a terminal no-match. */
final class RevisionBoundNoMatchProgressTest {

    @Test
    void shouldBindNoMatchProgressToTheExactUnchangedRootRevision() {
        // given
        Node root = new Node().properties(
                "name",
                new Node().value("No Match Root"));
        Node event = new Node().properties(
                "kind",
                new Node().value("unmatched"));
        long rootRevision = 37L;
        ExternalOrderKey eventOrder = ExternalOrderKey.of(
                Arrays.<Object>asList(
                        rootRevision,
                        "no-match"));
        ExternalDeliveryPlan plan = ExternalDeliveryPlan.builder()
                .revisions(rootRevision, rootRevision)
                .eventOrderKey(eventOrder)
                .activeSubscriptionIntervals(
                        Collections
                                .<SubscriptionDelta.Entry>emptyList())
                .exactRuntimeState()
                .build();
        VerifiedExecutionEvidence evidence = plan.bind(
                root,
                event,
                RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY);
        DocumentProcessor processor = DocumentProcessor.builder()
                .withExternalDeliveryPlanDeriver(
                        (ignoredRoot, ignoredEvent) -> plan)
                .build();

        // when
        PlatformProcessingResult handOff;
        try {
            handOff = processor.processDocumentForPlatformCommit(
                    root,
                    event,
                    evidence);
        } finally {
            processor.close();
        }

        // then
        DocumentProcessingResult result = handOff.processResult();
        PlatformCommitCompanion companion = handOff.commitCompanion();
        assertEquals(ProcessorStatus.NO_MATCH, result.status());
        assertFalse(result.commits());
        assertEquals(
                BlueIdCalculator.calculateBlueId(root),
                BlueIdCalculator.calculateBlueId(
                        result.document()));
        assertTrue(result.events().isEmpty());
        assertFalse(companion.commitsRootAndOutbox());
        assertEquals(
                BlueIdCalculator.calculateBlueId(root),
                companion.expectedRootBlueId());
        assertEquals(
                BlueIdCalculator.calculateBlueId(event),
                companion.eventBlueId());
        assertEquals(rootRevision, companion.expectedRootRevision());
        assertEquals(rootRevision, companion.resultingRootRevision());
        assertEquals(eventOrder, companion.eventOrderKey());
        assertTrue(companion.subscriptionDelta().isEmpty());
    }
}
