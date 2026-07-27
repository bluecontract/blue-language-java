package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic kernel coverage for immutable logical-delivery routing. The fixture
 * deliberately uses no application-specific runtime type or contract name.
 */
final class LogicalDeliveryRoutingTest {

    private static final Node DEFAULT_CHANNEL_TYPE =
            new Node().name("Generic Default External Channel");
    private static final String DEFAULT_CHANNEL_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(
                    DEFAULT_CHANNEL_TYPE);
    private static final Node ROUTING_CHANNEL_TYPE =
            new Node().name("Generic Routing External Channel");
    private static final String ROUTING_CHANNEL_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(
                    ROUTING_CHANNEL_TYPE);
    private static final Node HANDLER_TYPE =
            new Node().name("Generic Logical Delivery Handler");
    private static final String HANDLER_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(HANDLER_TYPE);
    private static final Node HEADER_PROBE_TYPE =
            new Node().name("Generic Header Materialization Probe");
    private static final String HEADER_PROBE_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(
                    HEADER_PROBE_TYPE);
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(
                    Arrays.<Object>asList(
                            41, "logical-delivery", 1));

    @Test
    void defaultFunctionsPreserveRawSourceDispatchAndCheckpoint() {
        Node event = event("topic", "event-default");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(root(
                    defaultChannel(
                            "source",
                            0,
                            "topic",
                            "domain-source",
                            "default-payload"),
                    handler(
                            "handler",
                            "source",
                            fixture.selectedBodyBlueId)));
            PreparedRun prepared = fixture.prepare(
                    document, event, "source");

            ExternalChannelFunctionEvaluation evaluation =
                    fixture.evaluate(
                            document, event, "source");
            assertEquals(
                    "source",
                    evaluation.handlerChannelKey());
            assertEquals(
                    "source",
                    evaluation.logicalDeliveryKey());

            ProcessingDebugResult debug =
                    fixture.process(
                            document, event, prepared);

            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertEquals(
                    Collections.singletonList("source"),
                    fixture.handlers.matchedChannels());
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source"));
        }
    }

    @Test
    void twoFreshSourcesDispatchOnceAndAdvanceBothRawCheckpoints() {
        Node event = event("topic", "event-group");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            PreparedRun prepared = fixture.prepare(
                    document,
                    event,
                    "source-a",
                    "source-b");

            ProcessingDebugResult debug =
                    fixture.process(
                            document, event, prepared);

            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertEquals(
                    Collections.singletonList("target"),
                    fixture.handlers.matchedChannels());
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source-a"));
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source-b"));
            assertEquals(
                    Arrays.asList("source-a", "source-b"),
                    checkpointWrites(debug.trace()));
        }
    }

    @Test
    void staleMemberIsExcludedAndOnlyFreshSourceAdvances() {
        Node event = event("topic", "event-stale");
        try (Fixture fixture = new Fixture(event)) {
            Node initialized = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            ProcessingDebugResult seed = fixture.process(
                    initialized,
                    event,
                    fixture.prepare(
                            initialized,
                            event,
                            "source-b"));
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    seed.processResult().status());
            fixture.handlers.reset();
            Node withStaleSource =
                    seed.processResult().document();

            ProcessingDebugResult debug = fixture.process(
                    withStaleSource,
                    event,
                    fixture.prepare(
                            withStaleSource,
                            event,
                            "source-a",
                            "source-b"));

            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertEquals(
                    Collections.singletonList("source-a"),
                    checkpointWrites(debug.trace()));
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source-a"));
            assertTrue(hasCheckpoint(
                    debug.processResult().document(),
                    "source-b"));
        }
    }

    @Test
    void handlerFailureCommitsNoParticipatingCheckpoint() {
        Node event = event("topic", "event-failure");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            fixture.handlers.fail(true);

            ProcessingDebugResult debug = fixture.process(
                    document,
                    event,
                    fixture.prepare(
                            document,
                            event,
                            "source-a",
                            "source-b"));

            assertEquals(
                    ProcessorStatus.RUNTIME_FATAL,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertEquals(
                    BlueIdCalculator.calculateBlueId(document),
                    BlueIdCalculator.calculateBlueId(
                            debug.processResult().document()));
            assertFalse(hasCheckpoint(
                    debug.processResult().document(),
                    "source-a"));
            assertFalse(hasCheckpoint(
                    debug.processResult().document(),
                    "source-b"));
            assertTrue(checkpointWrites(
                    debug.trace()).isEmpty());
        }
    }

    @Test
    void handlerTargetIsNeitherEvaluatedNorCheckpointedAsSource() {
        Node event = event("topic", "event-target");
        try (Fixture fixture = new Fixture(event)) {
            Node document = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            fixture.routing.resetEventEvaluations();

            ProcessingDebugResult debug = fixture.process(
                    document,
                    event,
                    fixture.prepare(
                            document,
                            event,
                            "source-a",
                            "source-b"));

            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(
                    0,
                    fixture.routing
                            .eventEvaluations("target"));
            assertFalse(hasCheckpoint(
                    debug.processResult().document(),
                    "target"));
            assertEquals(
                    Collections.singletonList("target"),
                    fixture.handlers.matchedChannels());
        }
    }

    @Test
    void invalidRouteOrDisagreementFailsBeforeMutation() {
        Node event = event("topic", "event-invalid");
        assertInvalidBeforeMutation(
                event,
                routingChannel(
                        "source-a", 0, "topic", "domain-a",
                        "target-a", "logical", "payload"),
                routingChannel(
                        "source-b", 1, "topic", "domain-b",
                        "target-b", "logical", "payload"),
                routingChannel(
                        "target-a", 2, "other", "domain-ta",
                        "target-a", "target-a", "target-a"),
                routingChannel(
                        "target-b", 3, "other", "domain-tb",
                        "target-b", "target-b", "target-b"));
        assertInvalidBeforeMutation(
                event,
                routingChannel(
                        "source-a", 0, "topic", "domain-a",
                        "target-a", "logical", "payload-a"),
                routingChannel(
                        "source-b", 1, "topic", "domain-b",
                        "target-a", "logical", "payload-b"),
                routingChannel(
                        "target-a", 2, "other", "domain-ta",
                        "target-a", "target-a", "target-a"));
        assertInvalidBeforeMutation(
                event,
                routingChannel(
                        "source-a", 0, "topic", "domain-a",
                        "missing", "logical", "payload"),
                routingChannel(
                        "source-b", 1, "topic", "domain-b",
                        "missing", "logical", "payload"));
        assertInvalidKeyBeforeMutation(
                event,
                routingChannel(
                        "source-a", 0, "topic", "domain-a",
                        "target-a", "", "payload"),
                routingChannel(
                        "target-a", 1, "other", "domain-ta",
                        "target-a", "target-a", "target-a"));
    }

    @Test
    void exactFragmentEventHasSamePlanResultGasAndTraceAsInlineEvent() {
        Node inlineEvent =
                new Node()
                        .properties(
                                "subscriptionKeys",
                                new Node().items(
                                        Arrays.asList(
                                                new Node().value(
                                                        "topic"),
                                                new Node().value(
                                                        "other"))))
                        .properties(
                                "id",
                                new Node().value(
                                        "event-fragment"));
        ExactNodeGraphFragments eventFragments =
                new ExactNodeGraphFragments(
                        inlineEvent);
        Node fragmentEvent =
                eventFragments.roots().get(0)
                        .directFragment();
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        inlineEvent),
                BlueIdCalculator.calculateBlueId(
                        fragmentEvent));

        ProcessingDebugResult inlineDebug;
        ProcessingDebugResult fragmentDebug;
        List<String> inlinePlan;
        List<String> fragmentPlan;
        try (Fixture inline =
                     new Fixture(inlineEvent);
             Fixture fragmented =
                     new Fixture(inlineEvent)) {
            Node inlineDocument = inline.initialize(
                    routedDocument(
                            inline,
                            "shared-payload",
                            "shared-payload"));
            Node fragmentDocument =
                    fragmented.initialize(
                            routedDocument(
                                    fragmented,
                                    "shared-payload",
                                    "shared-payload"));
            assertEquals(
                    BlueIdCalculator.calculateBlueId(
                            inlineDocument),
                    BlueIdCalculator.calculateBlueId(
                            fragmentDocument));

            PreparedRun inlinePrepared =
                    inline.prepare(
                            inlineDocument,
                            inlineEvent,
                            "source-a",
                            "source-b");
            PreparedRun fragmentPrepared =
                    fragmented.prepare(
                            fragmentDocument,
                            fragmentEvent,
                            "source-a",
                            "source-b");
            inlinePlan = planProjection(
                    inlinePrepared.plan);
            fragmentPlan = planProjection(
                    fragmentPrepared.plan);
            inlineDebug = inline.process(
                    inlineDocument,
                    inlineEvent,
                    inlinePrepared);
            fragmentDebug = fragmented.process(
                    fragmentDocument,
                    fragmentEvent,
                    fragmentPrepared);
        }

        assertEquals(inlinePlan, fragmentPlan);
        assertEquals(
                inlineDebug.processResult().status(),
                fragmentDebug.processResult().status());
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        inlineDebug.processResult()
                                .document()),
                BlueIdCalculator.calculateBlueId(
                        fragmentDebug.processResult()
                                .document()));
        assertEquals(
                inlineDebug.processResult().totalGas(),
                fragmentDebug.processResult().totalGas());
        assertEquals(
                gasProjection(inlineDebug.trace()),
                gasProjection(fragmentDebug.trace()));
        assertEquals(
                traceProjection(inlineDebug.trace()),
                traceProjection(fragmentDebug.trace()));
    }

    @Test
    void unavailableEventFragmentSuspendsProcessAttempt() {
        Node inlineEvent = event(
                "topic", "event-suspension");
        Node keyFragment = new Node().value("topic");
        String keyBlueId =
                BlueIdCalculator.calculateBlueId(
                        keyFragment);
        Node fragmentedEvent =
                inlineEvent.clone()
                        .properties(
                                "subscriptionKey",
                                reference(keyBlueId));
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        inlineEvent),
                BlueIdCalculator.calculateBlueId(
                        fragmentedEvent));

        try (Fixture fixture = new Fixture(inlineEvent)) {
            Node document = fixture.initialize(
                    root(
                            defaultChannel(
                                    "source",
                                    0,
                                    "topic",
                                    "domain",
                                    "payload"),
                            handler(
                                    "handler",
                                    "source",
                                    fixture
                                            .selectedBodyBlueId)));
            PreparedRun prepared = fixture.prepare(
                    document,
                    inlineEvent,
                    "source");
            fixture.provider.unavailable(keyBlueId);

            ProcessAttemptResult attempt =
                    fixture.processAttempt(
                            document,
                            fragmentedEvent,
                            prepared);

            assertEquals(
                    ProcessAttemptResult.Kind
                            .NEEDS_RESOURCES,
                    attempt.kind(),
                    attempt.processResult() != null
                            ? attempt.processResult().status()
                            + "|"
                            + attempt.processResult()
                            .diagnostic().category()
                            + "|"
                            + attempt.processResult()
                            .diagnostic().message()
                            : "no completed result");
            assertEquals(
                    Collections.singletonList(
                            keyBlueId),
                    attempt.requiredExactBlueIds());
            assertEquals(0, fixture.handlers.executions());
        }
    }

    @Test
    void selectedHandlerBodyIsAdmittedLazilyAndUnselectedBodyIsNotDemanded() {
        Node event = event("topic", "event-body");
        try (Fixture fixture = new Fixture(event)) {
            fixture.provider.forbid(
                    fixture.missingBodyBlueId);
            Node document = fixture.initialize(root(
                    routingChannel(
                            "source-a", 0, "topic", "domain-a",
                            "target", "logical", "shared-payload"),
                    routingChannel(
                            "source-b", 1, "topic", "domain-b",
                            "target", "logical", "shared-payload"),
                    routingChannel(
                            "target", 2, "other", "domain-target",
                            "target", "target", "target"),
                    handler(
                            "selected-handler",
                            "target",
                            fixture.selectedBodyBlueId),
                    handler(
                            "unselected-handler",
                            "source-a",
                            fixture.missingBodyBlueId)));
            fixture.provider.reset();
            PreparedRun prepared = fixture.prepare(
                    document,
                    event,
                    "source-a",
                    "source-b");
            assertEquals(
                    0,
                    fixture.provider
                            .requests(
                                    fixture.missingBodyBlueId));

            ProcessingDebugResult debug =
                    fixture.process(
                            document, event, prepared);

            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status());
            assertEquals(1, fixture.handlers.executions());
            assertTrue(fixture.handlers.bodyMaterialized());
            assertFalse(
                    fixture.handlers
                            .bodyRequestedBeforeMatch());
            assertEquals(
                    0,
                    fixture.provider
                            .requests(
                                    fixture.missingBodyBlueId));
        }
    }

    @Test
    void exactMaterializationFailsDuringHeaderAndAfterEventSession() {
        Node event = event("topic", "event-context");
        try (Fixture fixture = new Fixture(event)) {
            Node document = root(
                    headerProbe("probe"));
            ResolvedSnapshot snapshot =
                    fixture.processor
                            .snapshotManager()
                            .fromDocumentTransient(
                                    document);
            ContractBundle bundle =
                    fixture.processor
                            .contractLoader()
                            .load(snapshot, "/");
            EffectiveContractSnapshot probe =
                    bundle.effectiveContractSnapshot(
                            "probe");

            IllegalStateException headerFailure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> new ExternalChannelFunctionResolver(
                                    fixture.processor.registry(),
                                    fixture.processor
                                            .contractConverter(),
                                    bundle)
                                    .header(probe));
            assertTrue(headerFailure.getMessage().contains(
                    "available only during event evaluation"));

            Node routed = fixture.initialize(
                    routedDocument(
                            fixture,
                            "shared-payload",
                            "shared-payload"));
            fixture.evaluate(
                    routed, event, "source-a");
            ExternalChannelFunctionContext retained =
                    fixture.routing.lastContext();
            assertNotNull(retained);
            Node exactReference =
                    new Node().blueId(
                            fixture.selectedBodyBlueId);
            IllegalStateException closedFailure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> retained
                                    .materializeExactReference(
                                            exactReference));
            assertTrue(closedFailure.getMessage().contains(
                    "no longer active"));
        }
    }

    private static void assertInvalidBeforeMutation(
            Node event,
            Node... contracts) {
        try (Fixture fixture = new Fixture(event)) {
            List<Node> all =
                    new ArrayList<>(
                            Arrays.asList(contracts));
            all.add(handler(
                    "handler",
                    "target-a",
                    fixture.selectedBodyBlueId));
            Node document = fixture.initialize(
                    root(all.toArray(
                            new Node[all.size()])));
            PreparedRun prepared = fixture.prepare(
                    document,
                    event,
                    "source-a",
                    contracts.length > 1
                            && "source-b".equals(
                            contracts[1].getName())
                            ? "source-b"
                            : "source-a");
            ProcessingDebugResult debug =
                    fixture.process(
                            document, event, prepared);

            assertEquals(
                    ProcessorStatus.RUNTIME_FATAL,
                    debug.processResult().status());
            assertEquals(0, fixture.handlers.executions());
            assertEquals(
                    BlueIdCalculator.calculateBlueId(
                            document),
                    BlueIdCalculator.calculateBlueId(
                            debug.processResult()
                                    .document()));
            assertTrue(checkpointWrites(
                    debug.trace()).isEmpty());
        }
    }

    private static void assertInvalidKeyBeforeMutation(
            Node event,
            Node... contracts) {
        try (Fixture fixture = new Fixture(event)) {
            List<Node> all =
                    new ArrayList<>(
                            Arrays.asList(contracts));
            all.add(handler(
                    "handler",
                    "target-a",
                    fixture.selectedBodyBlueId));
            Node document = fixture.initialize(
                    root(all.toArray(
                            new Node[all.size()])));

            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> fixture.prepare(
                                    document,
                                    event,
                                    "source-a"));
            assertTrue(failure.getMessage().contains(
                    "must be non-empty Text"));
            assertEquals(0, fixture.handlers.executions());
        }
    }

    private static Node routedDocument(
            Fixture fixture,
            String firstPayload,
            String secondPayload) {
        return root(
                routingChannel(
                        "source-a", 0, "topic", "domain-a",
                        "target", "logical", firstPayload),
                routingChannel(
                        "source-b", 1, "topic", "domain-b",
                        "target", "logical", secondPayload),
                routingChannel(
                        "target", 2, "other", "domain-target",
                        "target", "target", "target"),
                handler(
                        "handler",
                        "target",
                        fixture.selectedBodyBlueId));
    }

    private static Node event(
            String subscriptionKey,
            String id) {
        return new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                subscriptionKey))
                .properties(
                        "id",
                        new Node().value(id));
    }

    private static Node root(Node... contracts) {
        Node map = new Node();
        for (Node supplied : contracts) {
            String key = supplied.getName();
            Node contract = supplied.clone();
            contract.name(null);
            map.properties(key, contract);
        }
        return new Node().contracts(map);
    }

    private static Node defaultChannel(
            String key,
            int order,
            String subscriptionKey,
            String domain,
            String payload) {
        return new Node()
                .name(key)
                .type(reference(
                        DEFAULT_CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order",
                        new Node().value(order))
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                subscriptionKey))
                .properties(
                        "checkpointDomain",
                        new Node().value(domain))
                .properties(
                        "payload",
                        new Node().value(payload));
    }

    private static Node routingChannel(
            String key,
            int order,
            String subscriptionKey,
            String domain,
            String handlerChannelKey,
            String logicalDeliveryKey,
            String payload) {
        return new Node()
                .name(key)
                .type(reference(
                        ROUTING_CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order",
                        new Node().value(order))
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                subscriptionKey))
                .properties(
                        "checkpointDomain",
                        new Node().value(domain))
                .properties(
                        "handlerChannelKey",
                        new Node().value(
                                handlerChannelKey))
                .properties(
                        "logicalDeliveryKey",
                        new Node().value(
                                logicalDeliveryKey))
                .properties(
                        "payload",
                        new Node().value(payload));
    }

    private static Node handler(
            String key,
            String channelKey,
            String bodyBlueId) {
        return new Node()
                .name(key)
                .type(reference(HANDLER_TYPE_BLUE_ID))
                .properties(
                        "channel",
                        new Node().value(channelKey))
                .properties(
                        "body",
                        reference(bodyBlueId));
    }

    private static Node headerProbe(String key) {
        return new Node()
                .name(key)
                .type(reference(
                        HEADER_PROBE_TYPE_BLUE_ID));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static boolean hasCheckpoint(
            Node document,
            String rawChannelKey) {
        Node contracts =
                document != null
                        ? document.getContracts()
                        : null;
        Node checkpoint =
                property(contracts, "checkpoint");
        Node entries =
                property(checkpoint, "entries");
        return property(entries, rawChannelKey) != null;
    }

    private static Node property(
            Node owner,
            String key) {
        return owner != null
                && owner.getProperties() != null
                ? owner.getProperties().get(key)
                : null;
    }

    private static List<String> checkpointWrites(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record
                : trace.records(
                ProcessingTraceRecord.Kind
                        .CHECKPOINT_WRITE)) {
            result.add(record.contractKey());
        }
        return result;
    }

    private static List<String> planProjection(
            ExternalDeliveryPlan plan) {
        List<String> result = new ArrayList<>();
        result.add(plan.managedRootRevision()
                + "|" + plan.indexedRootRevision()
                + "|" + plan.eventOrderKey());
        for (ExternalDeliverySnapshot delivery
                : plan.deliveries()) {
            result.add(
                    delivery.scopePath()
                            + "|" + delivery.channelKey()
                            + "|" + delivery
                            .effectiveTypeBlueId()
                            + "|" + delivery.order()
                            + "|" + delivery
                            .sourceContributionNodeBlueIds()
                            + "|" + delivery.subscriptionKeys()
                            + "|" + delivery
                            .checkpointDomainBlueId()
                            + "|" + delivery
                            .checkpointSubjectBlueId());
        }
        return result;
    }

    private static List<String> gasProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (GasTraceEntry entry : trace.gas()) {
            result.add(
                    entry.sequence()
                            + "|" + entry.namespace()
                            + "|" + entry.counter()
                            + "|" + entry.quantity()
                            + "|" + entry.weight()
                            + "|" + entry.subtotal()
                            + "|" + entry.scopePath()
                            + "|" + entry.contractKey()
                            + "|" + entry.logicalPath()
                            + "|" + entry.reason());
        }
        return result;
    }

    private static List<String> traceProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record
                : trace.records()) {
            Node node = record.node();
            result.add(
                    record.sequence()
                            + "|" + record.kind()
                            + "|" + record.scopePath()
                            + "|" + record.contractKey()
                            + "|" + record.logicalPath()
                            + "|" + record.details()
                            + "|" + (node != null
                            ? BlueIdCalculator
                            .calculateBlueId(node)
                            : null));
        }
        return result;
    }

    public static final class DefaultExternalChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String checkpointDomain;
        private String payload;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(
                String checkpointDomain) {
            this.checkpointDomain = checkpointDomain;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    public static final class RoutingExternalChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String checkpointDomain;
        private String handlerChannelKey;
        private String logicalDeliveryKey;
        private String payload;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(
                String checkpointDomain) {
            this.checkpointDomain = checkpointDomain;
        }

        public String getHandlerChannelKey() {
            return handlerChannelKey;
        }

        public void setHandlerChannelKey(
                String handlerChannelKey) {
            this.handlerChannelKey =
                    handlerChannelKey;
        }

        public String getLogicalDeliveryKey() {
            return logicalDeliveryKey;
        }

        public void setLogicalDeliveryKey(
                String logicalDeliveryKey) {
            this.logicalDeliveryKey =
                    logicalDeliveryKey;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    public static final class LogicalHandler
            extends HandlerContract {
        private Node body;

        public Node getBody() {
            return body;
        }

        public void setBody(Node body) {
            this.body = body;
        }
    }

    public static final class HeaderProbeChannel
            extends ChannelContract {
    }

    private static final class DefaultProcessor
            implements ChannelProcessor<
            DefaultExternalChannel> {
        private final ExternalChannelSubscriptionFunctions<
                DefaultExternalChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        DefaultExternalChannel>() {
                    @Override
                    public List<String> channelKeys(
                            DefaultExternalChannel contract) {
                        return Collections.singletonList(
                                contract
                                        .getSubscriptionKey());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            DefaultExternalChannel contract) {
                        return contract
                                .getCheckpointDomain();
                    }

                    @Override
                    public Node payload(
                            DefaultExternalChannel contract,
                            Node exactEvent) {
                        return new Node().value(
                                contract.getPayload());
                    }
                };

        @Override
        public Class<DefaultExternalChannel>
        contractType() {
            return DefaultExternalChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                DefaultExternalChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class RoutingProcessor
            implements ChannelProcessor<
            RoutingExternalChannel> {
        private final Map<String, AtomicInteger>
                eventEvaluations =
                new LinkedHashMap<>();
        private ExternalChannelFunctionContext lastContext;
        private final ExternalChannelSubscriptionFunctions<
                RoutingExternalChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        RoutingExternalChannel>() {
                    @Override
                    public List<String> channelKeys(
                            RoutingExternalChannel contract) {
                        return Collections.singletonList(
                                contract
                                        .getSubscriptionKey());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            RoutingExternalChannel contract) {
                        return contract
                                .getCheckpointDomain();
                    }

                    @Override
                    public Node payload(
                            RoutingExternalChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        lastContext = context;
                        eventEvaluations
                                .computeIfAbsent(
                                        contract.getKey(),
                                        ignored ->
                                                new AtomicInteger())
                                .incrementAndGet();
                        return new Node().value(
                                contract.getPayload());
                    }

                    @Override
                    public String handlerChannelKey(
                            RoutingExternalChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return contract
                                .getHandlerChannelKey();
                    }

                    @Override
                    public String logicalDeliveryKey(
                            RoutingExternalChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return contract
                                .getLogicalDeliveryKey();
                    }
                };

        @Override
        public Class<RoutingExternalChannel>
        contractType() {
            return RoutingExternalChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                RoutingExternalChannel>
        externalSubscriptionFunctions() {
            return functions;
        }

        private int eventEvaluations(String key) {
            AtomicInteger count =
                    eventEvaluations.get(key);
            return count != null ? count.get() : 0;
        }

        private void resetEventEvaluations() {
            eventEvaluations.clear();
        }

        private ExternalChannelFunctionContext
        lastContext() {
            return lastContext;
        }
    }

    private static final class LogicalHandlerProcessor
            implements HandlerProcessor<LogicalHandler> {
        private final CountingProvider provider;
        private final String selectedBodyBlueId;
        private int executions;
        private boolean fail;
        private boolean bodyMaterialized;
        private boolean bodyRequestedBeforeMatch;
        private final List<String> matchedChannels =
                new ArrayList<>();

        private LogicalHandlerProcessor(
                CountingProvider provider,
                String selectedBodyBlueId) {
            this.provider = provider;
            this.selectedBodyBlueId =
                    selectedBodyBlueId;
        }

        @Override
        public Class<LogicalHandler> contractType() {
            return LogicalHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("body");
        }

        @Override
        public boolean matches(
                LogicalHandler contract,
                HandlerMatchContext context) {
            matchedChannels.add(
                    context.channelKey());
            bodyRequestedBeforeMatch =
                    bodyRequestedBeforeMatch
                            || provider.requests(
                            selectedBodyBlueId) > 0;
            return true;
        }

        @Override
        public void execute(
                LogicalHandler contract,
                ProcessorExecutionContext context) {
            executions++;
            bodyMaterialized =
                    contract.getBody() != null
                            && !contract.getBody()
                            .isReferenceOnly();
            if (fail) {
                context.throwFatal(
                        "generic routed handler failure");
            }
        }

        private int executions() {
            return executions;
        }

        private List<String> matchedChannels() {
            return Collections.unmodifiableList(
                    new ArrayList<>(
                            matchedChannels));
        }

        private void fail(boolean fail) {
            this.fail = fail;
        }

        private boolean bodyMaterialized() {
            return bodyMaterialized;
        }

        private boolean bodyRequestedBeforeMatch() {
            return bodyRequestedBeforeMatch;
        }

        private void reset() {
            executions = 0;
            fail = false;
            bodyMaterialized = false;
            bodyRequestedBeforeMatch = false;
            matchedChannels.clear();
        }
    }

    private static final class HeaderProbeProcessor
            implements ChannelProcessor<
            HeaderProbeChannel> {
        private final String exactReferenceBlueId;

        private HeaderProbeProcessor(
                String exactReferenceBlueId) {
            this.exactReferenceBlueId =
                    exactReferenceBlueId;
        }

        @Override
        public Class<HeaderProbeChannel>
        contractType() {
            return HeaderProbeChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                HeaderProbeChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<
                    HeaderProbeChannel>() {
                @Override
                public List<String> channelKeys(
                        HeaderProbeChannel contract,
                        ExternalChannelFunctionContext context) {
                    context.materializeExactReference(
                            reference(
                                    exactReferenceBlueId));
                    return Collections.singletonList(
                            "probe");
                }

                @Override
                public String checkpointDomainDiscriminator(
                        HeaderProbeChannel contract) {
                    return "probe-domain";
                }
            };
        }
    }

    private static final class PreparedRun {
        private final ExternalDeliveryPlan plan;
        private final VerifiedExecutionEvidence evidence;

        private PreparedRun(
                ExternalDeliveryPlan plan,
                VerifiedExecutionEvidence evidence) {
            this.plan = plan;
            this.evidence = evidence;
        }
    }

    private static final class Fixture
            implements AutoCloseable {
        private final Node selectedBody =
                new Node().value("selected-body");
        private final String selectedBodyBlueId =
                BlueIdCalculator.calculateBlueId(
                        selectedBody);
        private final String missingBodyBlueId =
                BlueIdCalculator.calculateBlueId(
                        new Node().value(
                                "missing-body"));
        private final CountingProvider provider;
        private final Blue language;
        private final DefaultProcessor defaults =
                new DefaultProcessor();
        private final RoutingProcessor routing =
                new RoutingProcessor();
        private final LogicalHandlerProcessor handlers;
        private final HeaderProbeProcessor headerProbe;
        private final DocumentProcessor processor;

        private Fixture(Node exactEvent) {
            ExactNodeGraphFragments fragments =
                    new ExactNodeGraphFragments(
                            Arrays.asList(
                                    selectedBody,
                                    exactEvent));
            this.provider = new CountingProvider(
                    fragments.fragments());
            this.language =
                    ProcessorTestSupport.blue(provider);
            this.handlers =
                    new LogicalHandlerProcessor(
                            provider,
                            selectedBodyBlueId);
            this.headerProbe =
                    new HeaderProbeProcessor(
                            selectedBodyBlueId);
            language.registerExternalContractType(
                    DEFAULT_CHANNEL_TYPE_BLUE_ID,
                    DEFAULT_CHANNEL_TYPE,
                    defaults);
            language.registerExternalContractType(
                    ROUTING_CHANNEL_TYPE_BLUE_ID,
                    ROUTING_CHANNEL_TYPE,
                    routing);
            language.registerExternalContractType(
                    HANDLER_TYPE_BLUE_ID,
                    HANDLER_TYPE,
                    handlers);
            language.registerExternalContractType(
                    HEADER_PROBE_TYPE_BLUE_ID,
                    HEADER_PROBE_TYPE,
                    headerProbe);
            this.processor =
                    DocumentProcessor.builder()
                            .registerContractProcessor(
                                    DEFAULT_CHANNEL_TYPE_BLUE_ID,
                                    DEFAULT_CHANNEL_TYPE,
                                    defaults)
                            .registerContractProcessor(
                                    ROUTING_CHANNEL_TYPE_BLUE_ID,
                                    ROUTING_CHANNEL_TYPE,
                                    routing)
                            .registerContractProcessor(
                                    HANDLER_TYPE_BLUE_ID,
                                    HANDLER_TYPE,
                                    handlers)
                            .registerContractProcessor(
                                    HEADER_PROBE_TYPE_BLUE_ID,
                                    HEADER_PROBE_TYPE,
                                    headerProbe)
                            .withMatchingService(
                                    new ContractMatchingService(
                                            language))
                            .withSnapshotManager(
                                    language
                                            .getDocumentProcessor()
                                            .snapshotManager())
                            .withExternalDeliveryEvidenceVerifier(
                                    (root, event, evidence) -> {
                                        // Exact binding is still revalidated
                                        // by VerifiedExecutionEvidence.
                                    })
                            .build();
        }

        private Node initialize(Node document) {
            DocumentProcessingResult result =
                    processor.initializeDocument(
                            document);
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    result.status());
            return result.document();
        }

        private ExternalChannelFunctionEvaluation evaluate(
                Node document,
                Node event,
                String sourceKey) {
            ResolvedSnapshot snapshot =
                    processor.snapshotManager()
                            .fromDocumentTransient(
                                    document);
            ContractBundle bundle =
                    processor.contractLoader()
                            .load(snapshot, "/");
            return ExternalChannelFunctionEvaluation
                    .evaluate(
                            processor.registry(),
                            processor.contractConverter(),
                            ExternalChannelFunctionEvaluation
                                    .verifiedMatcherSessions(
                                            processor
                                                    .snapshotManager()),
                            bundle,
                            bundle.effectiveContractSnapshot(
                                    sourceKey),
                            event);
        }

        private PreparedRun prepare(
                Node document,
                Node event,
                String... sourceKeys) {
            ResolvedSnapshot snapshot =
                    processor.snapshotManager()
                            .fromDocumentTransient(
                                    document);
            ContractBundle bundle =
                    processor.contractLoader()
                            .load(snapshot, "/");
            ExternalDeliveryPlan.Builder plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(7L, 7L)
                            .eventOrderKey(EVENT_ORDER)
                            .activeSubscriptionIntervals(
                                    Collections
                                            .<SubscriptionDelta.Entry>
                                                    emptyList())
                            .exactRuntimeState();
            for (String sourceKey : sourceKeys) {
                EffectiveContractSnapshot contract =
                        bundle.effectiveContractSnapshot(
                                sourceKey);
                ExternalChannelFunctionEvaluation
                        evaluation =
                        ExternalChannelFunctionEvaluation
                                .evaluate(
                                        processor.registry(),
                                        processor
                                                .contractConverter(),
                                        ExternalChannelFunctionEvaluation
                                                .verifiedMatcherSessions(
                                                        processor
                                                                .snapshotManager()),
                                        bundle,
                                        contract,
                                        event);
                plan.delivery(delivery(
                        contract, evaluation));
            }
            ExternalDeliveryPlan built = plan.build();
            return new PreparedRun(
                    built,
                    built.bind(
                            document,
                            event,
                            processor
                                    .runtimeRegistryIdentity()));
        }

        private ProcessingDebugResult process(
                Node document,
                Node event,
                PreparedRun prepared) {
            return processor.processDocumentWithTrace(
                    document,
                    event,
                    prepared.evidence);
        }

        private ProcessAttemptResult processAttempt(
                Node document,
                Node event,
                PreparedRun prepared) {
            return processor.processAttempt(
                    document,
                    event,
                    prepared.evidence);
        }

        @Override
        public void close() {
            processor.close();
            language.close();
        }
    }

    private static ExternalDeliverySnapshot delivery(
            EffectiveContractSnapshot snapshot,
            ExternalChannelFunctionEvaluation evaluation) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                snapshot.scopePath(),
                                snapshot.key())
                        .effectiveTypeBlueId(
                                snapshot
                                        .effectiveTypeBlueId())
                        .order(snapshot.order())
                        .checkpointDomainBlueId(
                                evaluation
                                        .checkpointDomainBlueId())
                        .checkpointSubjectBlueId(
                                evaluation
                                        .checkpointSubjectBlueId());
        for (String contribution
                : snapshot
                .sourceContributionNodeBlueIds()) {
            builder.sourceContribution(
                    contribution);
        }
        for (String subscriptionKey
                : evaluation.channelKeys()) {
            builder.subscriptionKey(
                    subscriptionKey);
        }
        return builder.build();
    }

    private static final class CountingProvider
            implements NodeProvider {
        private final Map<String, Node> exact =
                new LinkedHashMap<>();
        private final Map<String, AtomicInteger>
                requests = new LinkedHashMap<>();
        private final List<String> forbidden =
                new ArrayList<>();
        private final List<String> unavailable =
                new ArrayList<>();

        private CountingProvider(
                Map<String, Node> exact) {
            for (Map.Entry<String, Node> entry
                    : exact.entrySet()) {
                this.exact.put(
                        entry.getKey(),
                        entry.getValue().clone());
            }
        }

        @Override
        public synchronized List<Node> fetchByBlueId(
                String blueId) {
            if (forbidden.contains(blueId)) {
                throw new AssertionError(
                        "Forbidden exact body demand: "
                                + blueId);
            }
            if (unavailable.contains(blueId)) {
                throw new IllegalStateException(
                        "Provider unavailable for exact fragment "
                                + blueId);
            }
            requests.computeIfAbsent(
                            blueId,
                            ignored ->
                                    new AtomicInteger())
                    .incrementAndGet();
            Node node = exact.get(blueId);
            return node != null
                    ? Collections.singletonList(
                    node.clone())
                    : null;
        }

        private synchronized int requests(
                String blueId) {
            AtomicInteger count =
                    requests.get(blueId);
            return count != null ? count.get() : 0;
        }

        private synchronized void reset() {
            requests.clear();
        }

        private synchronized void forbid(
                String blueId) {
            forbidden.add(blueId);
        }

        private synchronized void unavailable(
                String blueId) {
            unavailable.add(blueId);
        }
    }
}
