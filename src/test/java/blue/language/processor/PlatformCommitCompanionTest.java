package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlatformCommitCompanionTest {

    @Test
    void shouldVerifyAtomicHandOffRetainsTheExactValidatorDeltaInstance() {
        // given
        Node root = new Node().properties(
                "value", new Node().value(1));
        Node event = new Node().value("event");
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.asList(12, "timeline", 3));
        VerifiedExecutionEvidence evidence = evidence(
                root, event, order, 12L);
        SubscriptionDelta.Entry added =
                new SubscriptionDelta.Entry(
                        "/",
                        "new",
                        "type-id",
                        Collections.singletonList(
                                "contribution-id"),
                        0,
                        Collections.singletonList("topic"),
                        "checkpoint-domain-id",
                        13L,
                        order,
                        null);
        SubscriptionDelta delta = new SubscriptionDelta(
                Collections.singletonList(added),
                Collections.<SubscriptionDelta.Entry>emptyList());
        DocumentProcessingResult semantic =
                DocumentProcessingResult.of(
                        root,
                        Collections.<Node>emptyList(),
                        5L);

        // when
        PlatformCommitCompanion companion =
                PlatformCommitCompanion.of(
                        evidence, semantic, delta);
        PlatformProcessingResult handOff =
                new PlatformProcessingResult(
                        semantic, companion);

        // then
        assertSame(semantic, handOff.processResult());
        assertSame(companion, handOff.commitCompanion());
        assertSame(delta, companion.subscriptionDelta());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(root),
                companion.expectedRootBlueId());
        assertEquals(12L,
                companion.expectedRootRevision());
        assertEquals(13L,
                companion.resultingRootRevision());
        assertEquals(order, companion.eventOrderKey());
        assertTrue(companion.commitsRootAndOutbox());
    }

    @Test
    void shouldVerifyDirectTerminationProducesProgressCompanionWithoutDeliveryVerification() {
        // given
        Node root = terminatedRoot();
        Node event = new Node().value("event");
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.asList(7, "timeline", 1));
        VerifiedExecutionEvidence evidence = evidence(
                root, event, order, 7L);
        DocumentProcessor processor =
                DocumentProcessor.builder()
                        .evidenceVerifier(
                                (document, processingEvent, ignored) -> {
                                    throw new AssertionError(
                                            "direct termination must not "
                                                    + "verify deliveries");
                                })
                        .build();

        // when
        PlatformProcessingResult handOff =
                processor.processDocumentForPlatformCommit(
                        root, event, evidence);

        // then
        assertEquals(
                ProcessorStatus.TERMINATED,
                handOff.processResult().status());
        assertFalse(
                handOff.commitCompanion()
                        .commitsRootAndOutbox());
        assertEquals(7L,
                handOff.commitCompanion()
                        .expectedRootRevision());
        assertEquals(7L,
                handOff.commitCompanion()
                        .resultingRootRevision());
        assertTrue(
                handOff.commitCompanion()
                        .subscriptionDelta().isEmpty());
    }

    private static VerifiedExecutionEvidence evidence(
            Node root,
            Node event,
            ExternalOrderKey order,
            long revision) {
        return VerifiedExecutionEvidence.builder(
                        DirectBlueIdCalculator.calculateBlueId(root),
                        DirectBlueIdCalculator.calculateBlueId(event))
                .revisions(revision, revision)
                .runtimeRegistryIdentity(
                        RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY)
                .eventOrderKey(order)
                .activeSubscriptionIntervals(
                        Collections
                                .<SubscriptionDelta.Entry>emptyList())
                .build();
    }

    private static Node terminatedRoot() {
        return new Node().contracts(
                new Node().properties(
                        "terminated",
                        new Node()
                                .type(new Node().blueId(
                                        RuntimeBlueIds
                                                .PROCESSING_TERMINATED_MARKER))
                                .properties(
                                        "cause",
                                        new Node().value("business"))
                                .properties(
                                        "reason",
                                        new Node().value("complete"))));
    }
}
