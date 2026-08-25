package blue.language.processor;

import blue.language.Blue;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused proofs for complete capture on the effective traversal. */
final class ContractSurfaceProjectionIntegrationTest {

    @Test
    void shouldCaptureUnchangedEmbeddedScopesBeyondChangedPathPruning() {
        // given
        CountingExternalProcessor channelProcessor =
                new CountingExternalProcessor();
        try (Blue blue = ProcessorTestSupport.blue()) {
            blue.registerContractProcessor(channelProcessor);
            DocumentProcessor processor = blue.getDocumentProcessor();
            Node root = embeddedRoot("topic");
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(root);
            SubscriptionSurfaceValidationContext context =
                    context(processor, root, snapshot,
                            Collections.singleton("/unrelated"));
            SubscriptionSurfaceProjector projector = projector(processor);
            List<String> capturedScopes = new ArrayList<String>();

            // when
            SubscriptionDelta pruned = processor
                    .subscriptionSurfaceValidator()
                    .validate(context);
            projector.captureCompleteTentative(
                    root,
                    snapshot,
                    processor.gasSchedule(),
                    context,
                    (scopePath, bundle) ->
                            capturedScopes.add(scopePath));

            // then
            assertTrue(pruned.isEmpty());
            assertEquals(Arrays.asList("/", "/child"), capturedScopes);
            assertEquals(0, channelProcessor.evaluations(),
                    "changed-surface pruning and complete capture must not "
                            + "evaluate an unchanged descendant channel");
        }
    }

    @Test
    void shouldNotEvaluateExternalFunctionsAgainForCompleteCapture() {
        // given
        CountingExternalProcessor channelProcessor =
                new CountingExternalProcessor();
        try (Blue blue = ProcessorTestSupport.blue()) {
            blue.registerContractProcessor(channelProcessor);
            DocumentProcessor processor = blue.getDocumentProcessor();
            Node root = embeddedRoot("topic");
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(root);
            SubscriptionSurfaceValidationContext context =
                    context(
                            processor,
                            root,
                            snapshot,
                            Collections.singleton(
                                    "/child/contracts/incoming/eventType"));
            SubscriptionSurfaceProjector projector = projector(processor);

            // when
            projector.captureCompleteEntry(
                    root,
                    snapshot,
                    processor.gasSchedule(),
                    context,
                    (scopePath, bundle) -> { });
            projector.captureCompleteTentative(
                    root,
                    snapshot,
                    processor.gasSchedule(),
                    context,
                    (scopePath, bundle) -> { });
            int afterCapture = channelProcessor.evaluations();
            SubscriptionDelta delta = processor
                    .subscriptionSurfaceValidator()
                    .validate(context);

            // then
            assertEquals(0, afterCapture);
            assertEquals(4, channelProcessor.evaluations(),
                    "only before/after deterministic validation twins evaluate");
            assertTrue(delta.isEmpty());
        }
    }

    private static SubscriptionSurfaceProjector projector(
            DocumentProcessor processor) {
        return new SubscriptionSurfaceProjector(
                processor.contractLoader(),
                processor.snapshotManager(),
                processor.registry(),
                processor.contractConverter());
    }

    private static SubscriptionSurfaceValidationContext context(
            DocumentProcessor processor,
            Node root,
            ResolvedSnapshot snapshot,
            java.util.Set<String> changedPaths) {
        return SubscriptionSurfaceValidationContext.builder(
                        root,
                        root.clone(),
                        changedPaths,
                        processor.gasSchedule())
                .snapshots(snapshot, snapshot)
                .build();
    }

    private static Node embeddedRoot(String subscriptionKey) {
        Node channel = new Node()
                .type(reference(
                        ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL))
                .properties(
                        "eventType",
                        new Node().value(subscriptionKey));
        Node child = new Node().contracts(
                new Node().properties("incoming", channel));
        Node embedded = new Node()
                .type(reference(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(new Node().value("/child")));
        return new Node()
                .properties("child", child)
                .properties("unrelated", new Node().value("stable"))
                .contracts(new Node().properties("embedded", embedded));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class CountingExternalProcessor
            implements ChannelProcessor<TestEventChannel> {

        private final AtomicInteger evaluations = new AtomicInteger();
        private final ExternalChannelSubscriptionFunctions<TestEventChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<TestEventChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestEventChannel immutableContractSnapshot) {
                        evaluations.incrementAndGet();
                        return Collections.singletonList(
                                immutableContractSnapshot.getEventType());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            TestEventChannel immutableContractSnapshot) {
                        return "contract-surface-capture-test";
                    }
                };

        @Override
        public Class<TestEventChannel> contractType() {
            return TestEventChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<TestEventChannel>
        externalSubscriptionFunctions() {
            return functions;
        }

        private int evaluations() {
            return evaluations.get();
        }
    }
}
