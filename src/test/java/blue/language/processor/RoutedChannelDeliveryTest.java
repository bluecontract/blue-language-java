package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEventChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance-style coverage for source acceptance, checkpoint ownership, and same-run route
 * deduplication. The fixture invokes {@link ChannelRunner} directly so a test can control each
 * eligible source candidate without a target channel becoming an independent external candidate.
 */
final class RoutedChannelDeliveryTest {

    @Test
    void ordinaryDeliveryUsesAcceptingChannelForHandlers() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, null, null));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("source", "source", "target");

        fixture.run("/", bundle, "source", event("event-1"));

        assertEquals(Collections.singletonList("source"), handler.matchedChannels);
        assertEquals(1, handler.executions);
        assertCheckpoint(bundle, "source", true);
        assertCheckpoint(bundle, "target", false);
        assertGas(fixture, 75L);
    }

    @Test
    void ordinaryDeliveryKeepsExistingCheckpointKey() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", "custom-checkpoint", null, null));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("source", "source", "target");

        fixture.run("/", bundle, "source", event("event-1"));

        assertCheckpoint(bundle, "custom-checkpoint", true);
        assertCheckpoint(bundle, "source", false);
    }

    @Test
    void routesToSameScopeHandlerChannel() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("selected", null, "target", null));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "source", "target");

        fixture.run("/", bundle, "source", event("event-1"));

        assertEquals(Collections.singletonList("target"), handler.matchedChannels);
        assertEquals(Collections.singletonList("selected"), handler.payloads);
        assertEquals(1, handler.executions);
        assertGas(fixture, 75L);
    }

    @Test
    void processorManagedHandlerChannelIsSupported() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("selected", null, "target", null));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        LifecycleChannel target = new LifecycleChannel();
        target.setKey("target");
        SetProperty targetHandler = new SetProperty();
        targetHandler.setChannelKey("target");
        ContractBundle bundle = ContractBundle.builder()
                .addChannel("source", channel("source"))
                .addChannel("target", target)
                .addHandler("target-handler", targetHandler)
                .build();

        fixture.run("/", bundle, "source", event("event-1"));

        assertEquals(1, handler.executions);
        assertEquals(Collections.singletonList("target"), handler.matchedChannels);
    }

    @Test
    void handlerContextReportsEffectiveChannel() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("delivery-payload", null, "target", null));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "source", "target");

        fixture.run("/", bundle, "source", event("original-event"));

        assertEquals("target", handler.matchedChannels.get(0));
        assertEquals("delivery-payload", handler.payloads.get(0));
    }

    @Test
    void sourceChannelOwnsCheckpoint() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, "target", null));
        Fixture fixture = fixture(channels, new HandlerProbe(HandlerOutcome.SUCCESS), document());
        ContractBundle bundle = bundle("target", "source", "target");

        fixture.run("/", bundle, "source", event("event-1"));

        assertCheckpoint(bundle, "source", true);
        assertCheckpoint(bundle, "target", false);
    }

    @Test
    void explicitCheckpointKeyStillWins() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", "composite-source", "target", null));
        Fixture fixture = fixture(channels, new HandlerProbe(HandlerOutcome.SUCCESS), document());
        ContractBundle bundle = bundle("target", "source", "target");

        fixture.run("/", bundle, "source", event("event-1"));

        assertCheckpoint(bundle, "composite-source", true);
        assertCheckpoint(bundle, "source", false);
        assertCheckpoint(bundle, "target", false);
    }

    @Test
    void unknownHandlerChannelTerminatesDeterministically() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, "missing", "route-1"));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "source", "target");

        assertThrows(RunTerminationException.class,
                () -> fixture.run("/", bundle, "source", event("event-1")));

        assertFatalUnsupportedRoute(fixture);
        assertEquals(0, handler.executions);
        assertCheckpoint(bundle, "source", false);
        assertGas(fixture, 155L);
    }

    @Test
    void nonChannelTargetTerminatesDeterministically() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, "handler-only", "route-1"));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundleWithNonChannelTarget("source", "target", "handler-only");

        assertThrows(RunTerminationException.class,
                () -> fixture.run("/", bundle, "source", event("event-1")));

        assertFatalUnsupportedRoute(fixture);
        assertEquals(0, handler.executions);
        assertCheckpoint(bundle, "source", false);
    }

    @Test
    void targetCannotEscapeCurrentScope() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, "root-target", "route-1"));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, childDocument());
        ContractBundle childBundle = bundle("target", "source", "target");

        fixture.run("/child", childBundle, "source", event("event-1"));

        assertFatalUnsupportedRoute(fixture);
        assertEquals(0, handler.executions);
        assertCheckpoint(childBundle, "source", false);
    }

    @Test
    void targetChannelIsNotReevaluatedAsSource() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, "target", "route-1"));
        Fixture fixture = fixture(channels, new HandlerProbe(HandlerOutcome.SUCCESS), document());
        ContractBundle bundle = bundle("target", "source", "target");

        fixture.run("/", bundle, "source", event("event-1"));

        assertEquals(1, channels.evaluations("source"));
        assertEquals(0, channels.evaluations("target"));
        assertCheckpoint(bundle, "target", false);
    }

    @Test
    void multipleSourcesInvokeLogicalRouteOnce() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        ChannelDelivery route = delivery("payload", null, "target", "operation-1");
        channels.deliver("source-one", route);
        channels.deliver("source-two", route);
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "source-one", "source-two", "target");
        Node event = event("event-1");

        fixture.run("/", bundle, "source-one", event);
        fixture.run("/", bundle, "source-two", event);

        assertEquals(1, handler.executions);
        assertCheckpoint(bundle, "source-one", true);
        assertCheckpoint(bundle, "source-two", true);
        assertEquals(1, fixture.metrics.routedDeliveries);
        assertEquals(1, fixture.metrics.deduplicatedDeliveries);
        assertGas(fixture, 100L);
    }

    @Test
    void staleRoutedDeliveryCostsOnlyCandidateAttempt() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("stale-source", delivery("payload", null, "target", "operation-1"));
        channels.markStale("stale-source");
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "stale-source", "target");

        fixture.run("/", bundle, "stale-source", event("event-1"));

        assertEquals(0, handler.executions);
        assertCheckpoint(bundle, "stale-source", false);
        assertGas(fixture, 5L);
    }

    @Test
    void staleDuplicateSourceDoesNotAdvance() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        ChannelDelivery route = delivery("payload", null, "target", "operation-1");
        channels.deliver("fresh-source", route);
        channels.deliver("stale-source", route);
        channels.markStale("stale-source");
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "fresh-source", "stale-source", "target");
        Node event = event("event-1");

        fixture.run("/", bundle, "fresh-source", event);
        fixture.run("/", bundle, "stale-source", event);

        assertEquals(1, handler.executions);
        assertCheckpoint(bundle, "fresh-source", true);
        assertCheckpoint(bundle, "stale-source", false);
        assertEquals(0, fixture.metrics.deduplicatedDeliveries);
        assertGas(fixture, 80L);
    }

    @Test
    void differentLogicalKeysDoNotDeduplicate() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source-one", delivery("payload", null, "target", "operation-1"));
        channels.deliver("source-two", delivery("payload", null, "target", "operation-2"));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "source-one", "source-two", "target");
        Node event = event("event-1");

        fixture.run("/", bundle, "source-one", event);
        fixture.run("/", bundle, "source-two", event);

        assertEquals(2, handler.executions);
        assertEquals(2, fixture.metrics.routedDeliveries);
        assertEquals(0, fixture.metrics.deduplicatedDeliveries);
    }

    @Test
    void missingLogicalKeyPreservesLegacyMultipleDelivery() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        ChannelDelivery route = delivery("payload", null, "target", null);
        channels.deliver("source-one", route);
        channels.deliver("source-two", route);
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "source-one", "source-two", "target");
        Node event = event("event-1");

        fixture.run("/", bundle, "source-one", event);
        fixture.run("/", bundle, "source-two", event);

        assertEquals(2, handler.executions);
        assertEquals(0, fixture.metrics.deduplicatedDeliveries);
    }

    @Test
    void differentScopesDoNotDeduplicate() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, "target", "operation-1"));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Fixture fixture = fixture(channels, handler, childDocument());
        ContractBundle rootBundle = bundle("target", "source", "target");
        ContractBundle childBundle = bundle("target", "source", "target");
        Node event = event("event-1");

        fixture.run("/", rootBundle, "source", event);
        fixture.run("/child", childBundle, "source", event);

        assertEquals(2, handler.executions);
        assertCheckpoint(rootBundle, "source", true);
        assertCheckpoint(childBundle, "source", true);
    }

    @Test
    void handlerFailureMarksNoLogicalSuccess() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, "target", "operation-1"));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.FAIL);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "source", "target");

        assertThrows(RunTerminationException.class,
                () -> fixture.run("/", bundle, "source", event("event-1")));

        assertEquals(1, handler.executions);
        assertEquals(0, fixture.metrics.deduplicatedDeliveries);
        assertCheckpoint(bundle, "source", false);
        assertEquals(ProcessorStatus.RUNTIME_FATAL, fixture.execution.result().status());
        assertGas(fixture, 205L);
    }

    @Test
    void gracefulTerminationMarksNoLogicalSuccess() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source", delivery("payload", null, "target", "operation-1"));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.GRACEFUL_TERMINATION);
        Fixture fixture = fixture(channels, handler, document());
        ContractBundle bundle = bundle("target", "source", "target");

        assertThrows(RunTerminationException.class,
                () -> fixture.run("/", bundle, "source", event("event-1")));

        assertEquals(1, handler.executions);
        assertCheckpoint(bundle, "source", false);
        assertEquals(ProcessorStatus.SUCCESS, fixture.execution.result().status());
        assertGas(fixture, 105L);
    }

    @Test
    void replayAfterCommittedCheckpointsRunsNothing() {
        RoutingChannelProcessor channels = new RoutingChannelProcessor();
        channels.deliver("source-one", delivery("payload", null, "target", "operation-1"));
        channels.deliver("source-two", delivery("payload", null, "target", "operation-1"));
        HandlerProbe handler = new HandlerProbe(HandlerOutcome.SUCCESS);
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(channels);
        blue.registerContractProcessor(handler);
        Node document = blue.yamlToNode("contracts:\n"
                + "  source-one:\n"
                + "    type:\n"
                + "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n"
                + "  source-two:\n"
                + "    type:\n"
                + "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n"
                + "  target:\n"
                + "    type:\n"
                + "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n"
                + "  target-handler:\n"
                + "    channel: target\n"
                + "    type:\n"
                + "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n");
        Node initialized = blue.initializeDocument(document).document();
        Node processingEvent = event("event-1");

        DocumentProcessingResult first = blue.processDocument(initialized, processingEvent);
        DocumentProcessingResult replay = blue.processDocument(first.document(), processingEvent);

        assertEquals(1, handler.executions);
        assertFalse(first.capabilityFailure(), first.failureReason());
        assertFalse(replay.capabilityFailure(), replay.failureReason());
        assertTrue(replay.totalGas() < first.totalGas(),
                "replay must avoid all handler delivery and checkpoint persistence work");
    }

    private static ChannelDelivery delivery(String payload,
                                            String checkpointKey,
                                            String handlerChannelKey,
                                            String logicalDeliveryKey) {
        return ChannelDelivery.of(new Node().properties("payload", new Node().value(payload)),
                null,
                checkpointKey,
                null,
                handlerChannelKey,
                logicalDeliveryKey);
    }

    private static Node event(String eventId) {
        return new Node().properties("eventId", new Node().value(eventId));
    }

    private static Node document() {
        return new Node().contracts(new Node());
    }

    private static Node childDocument() {
        return new Node().contracts(new Node()).properties("child", new Node().contracts(new Node()));
    }

    private static ContractBundle bundle(String handlerChannel, String... channelKeys) {
        ContractBundle.Builder builder = ContractBundle.builder();
        for (String channelKey : channelKeys) {
            builder.addChannel(channelKey, channel(channelKey));
        }
        SetProperty handler = new SetProperty();
        handler.setChannelKey(handlerChannel);
        builder.addHandler("target-handler", handler);
        return builder.build();
    }

    private static ContractBundle bundleWithNonChannelTarget(String sourceChannel,
                                                              String handlerChannel,
                                                              String nonChannelKey) {
        ContractBundle.Builder builder = ContractBundle.builder()
                .addChannel(sourceChannel, channel(sourceChannel))
                .addChannel(handlerChannel, channel(handlerChannel));
        SetProperty handler = new SetProperty();
        handler.setChannelKey(handlerChannel);
        builder.addHandler("target-handler", handler);
        SetProperty nonChannel = new SetProperty();
        nonChannel.setChannelKey(nonChannelKey);
        builder.addHandler(nonChannelKey, nonChannel);
        return builder.build();
    }

    private static TestEventChannel channel(String key) {
        TestEventChannel channel = new TestEventChannel();
        channel.setKey(key);
        return channel;
    }

    private static Fixture fixture(RoutingChannelProcessor channels, HandlerProbe handler, Node document) {
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(channels)
                .register(handler)
                .build();
        DocumentProcessor owner = new DocumentProcessor(registry);
        RecordingMetrics metrics = new RecordingMetrics();
        owner.processingMetricsSink(metrics);
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document);
        ChannelRunner runner = new ChannelRunner(owner,
                execution,
                execution.runtime(),
                new CheckpointManager(execution.runtime()));
        return new Fixture(execution, runner, metrics);
    }

    private static void assertCheckpoint(ContractBundle bundle, String key, boolean expected) {
        ChannelEventCheckpoint checkpoint = (ChannelEventCheckpoint) bundle.marker("checkpoint");
        assertNotNull(checkpoint, "checkpoint marker must be created for evaluated source deliveries");
        if (expected) {
            assertNotNull(checkpoint.lastEvent(key), "expected checkpoint for " + key);
        } else {
            assertNull(checkpoint.lastEvent(key), "unexpected checkpoint for " + key);
        }
    }

    private static void assertGas(Fixture fixture, long expected) {
        assertEquals(expected, fixture.execution.runtime().totalGas());
    }

    private static void assertFatalUnsupportedRoute(Fixture fixture) {
        DocumentProcessingResult result = fixture.execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertEquals(ProcessorErrorCategory.UnsupportedContract, result.errorCategory());
        assertTrue(result.failureReason().contains("same-scope Channel"));
    }

    private static final class Fixture {
        private final ProcessorEngine.Execution execution;
        private final ChannelRunner runner;
        private final RecordingMetrics metrics;

        private Fixture(ProcessorEngine.Execution execution, ChannelRunner runner, RecordingMetrics metrics) {
            this.execution = execution;
            this.runner = runner;
            this.metrics = metrics;
        }

        private void run(String scopePath, ContractBundle bundle, String sourceChannelKey, Node event) {
            runner.runExternalChannel(scopePath, bundle, bundle.channelBinding(sourceChannelKey), event);
        }
    }

    private enum HandlerOutcome {
        SUCCESS,
        FAIL,
        GRACEFUL_TERMINATION
    }

    private static final class RoutingChannelProcessor implements ChannelProcessor<TestEventChannel> {
        private final Map<String, List<ChannelDelivery>> deliveriesByChannel = new LinkedHashMap<>();
        private final Map<String, Integer> evaluationCounts = new LinkedHashMap<>();
        private final Set<String> staleChannels = new LinkedHashSet<>();

        @Override
        public Class<TestEventChannel> contractType() {
            return TestEventChannel.class;
        }

        @Override
        public ChannelEvaluation evaluate(TestEventChannel contract, ChannelEvaluationContext context) {
            String channelKey = context.bindingKey();
            evaluationCounts.put(channelKey, evaluations(channelKey) + 1);
            List<ChannelDelivery> deliveries = deliveriesByChannel.get(channelKey);
            return deliveries != null ? ChannelEvaluation.matchDeliveries(deliveries) : ChannelEvaluation.noMatch();
        }

        @Override
        public boolean isNewerEvent(TestEventChannel contract, ChannelCheckpointContext context) {
            return !staleChannels.contains(context.channelKey());
        }

        private void deliver(String channelKey, ChannelDelivery... deliveries) {
            deliveriesByChannel.put(channelKey, Arrays.asList(deliveries));
        }

        private void markStale(String channelKey) {
            staleChannels.add(channelKey);
        }

        private int evaluations(String channelKey) {
            Integer count = evaluationCounts.get(channelKey);
            return count != null ? count : 0;
        }
    }

    private static final class HandlerProbe implements HandlerProcessor<SetProperty> {
        private final HandlerOutcome outcome;
        private final List<String> matchedChannels = new ArrayList<>();
        private final List<String> payloads = new ArrayList<>();
        private int executions;

        private HandlerProbe(HandlerOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public boolean matches(SetProperty contract, HandlerMatchContext context) {
            matchedChannels.add(context.channelKey());
            payloads.add(context.event().getAsText("/payload"));
            return true;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            executions++;
            if (outcome == HandlerOutcome.FAIL) {
                throw new IllegalStateException("handler failed");
            }
            if (outcome == HandlerOutcome.GRACEFUL_TERMINATION) {
                context.terminateGracefully("complete");
            }
        }
    }

    private static final class RecordingMetrics implements ProcessingMetricsSink {
        private int routedDeliveries;
        private int deduplicatedDeliveries;

        @Override
        public void incrementRoutedChannelDeliveries() {
            routedDeliveries++;
        }

        @Override
        public void incrementDeduplicatedChannelDeliveries() {
            deduplicatedDeliveries++;
        }
    }
}
