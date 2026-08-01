package blue.language.processor;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EffectiveSubscriptionSurfaceValidatorTest {

    private static final String TEST_CHANNEL_TYPE =
            ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;

    @Test
    void shouldVerifyInheritedReferencedCustomChannelUsesOrderedSourceAndAttemptInterval() {
        // given
        EffectiveTypes types = effectiveTypes("old-topic", "new-topic");
        try (Blue blue = blue(types, new PortableExternalProcessor())) {
            Node before = new Node().type(reference(types.beforeTypeBlueId));
            Node after = new Node().type(reference(types.afterTypeBlueId));
            ResolvedSnapshot beforeSnapshot =
                    blue.resolveToSnapshot(before);
            ResolvedSnapshot afterSnapshot =
                    blue.resolveToSnapshot(after);
            ExternalOrderKey order = ExternalOrderKey.of(
                    Arrays.asList(2000, "timeline", 7));

            // when
            SubscriptionDelta delta =
                    blue.getDocumentProcessor()
                            .subscriptionSurfaceValidator()
                            .validate(
                                    SubscriptionSurfaceValidationContext
                                            .builder(
                                                    before,
                                                    after,
                                                    Collections.singleton(
                                                            "/type"),
                                                    GasSchedule.contracts10())
                                            .snapshots(
                                                    beforeSnapshot,
                                                    afterSnapshot)
                                            .committingInterval(order, 9L)
                                            .build());
            SubscriptionDelta.Entry removed =
                    delta.removed().get(0);
            SubscriptionDelta.Entry added =
                    delta.added().get(0);

            // then
            assertEquals(1, delta.removed().size());
            assertEquals(1, delta.added().size());
            assertEquals(
                    Collections.singletonList(
                            types.beforeChannelBlueId),
                    removed.sourceContributionNodeBlueIds());
            assertEquals(
                    Collections.singletonList(
                            types.afterChannelBlueId),
                    added.sourceContributionNodeBlueIds());
            assertEquals(
                    Collections.singletonList("old-topic"),
                    removed.subscriptionKeys());
            assertEquals(
                    Collections.singletonList("new-topic"),
                    added.subscriptionKeys());
            assertEquals(Long.valueOf(9L),
                    removed.endAtRootRevision());
            assertNull(removed.activationRootRevision());
            assertEquals(Long.valueOf(9L),
                    added.activationRootRevision());
            assertEquals(order,
                    added.startAfterExternalOrderKey());
            assertNull(added.endAtRootRevision());
        }
    }

    @Test
    void shouldVerifyChangedCustomExternalTypeWithoutSurfaceFunctionsFailsClosed() {
        // given
        EffectiveTypes types = effectiveTypes("old-topic", "new-topic");

        // when
        SubscriptionSurfaceInvalidException failure;
        try (Blue blue = blue(types, new UnindexableExternalProcessor())) {
            Node before = new Node().type(reference(types.beforeTypeBlueId));
            Node after = new Node().type(reference(types.afterTypeBlueId));

            failure = captureFailure(
                    () -> blue.getDocumentProcessor()
                            .subscriptionSurfaceValidator()
                            .validate(
                                    SubscriptionSurfaceValidationContext
                                            .builder(
                                                    before,
                                                    after,
                                                    Collections.singleton(
                                                            "/type"),
                                                    GasSchedule.contracts10())
                                            .snapshots(
                                                    blue.resolveToSnapshot(
                                                            before),
                                                    blue.resolveToSnapshot(
                                                            after))
                                            .build()));

        }

        // then
        assertEquals(SubscriptionSurfaceInvalidException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "does not expose supported immutable subscription functions"));
    }

    @Test
    void shouldVerifyAddingDirectTerminationRetiresPreviouslyActiveSurface() {
        // given
        Node channel = new Node()
                .type(reference(
                        RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL))
                .properties("subscriptionKey",
                        new Node().value("topic"))
                .properties("checkpointDomain",
                        new Node().value("domain"));
        Node before = new Node().contracts(
                new Node().properties("incoming", channel));
        Node after = before.clone();
        after.getContracts().properties(
                "terminated",
                new Node().type(reference(
                        RuntimeBlueIds.PROCESSING_TERMINATED_MARKER)));
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.asList(10, "timeline", 2));

        // when
        SubscriptionDelta delta =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext
                                .builder(
                                        before,
                                        after,
                                        Collections.singleton(
                                                "/contracts/terminated"),
                                        GasSchedule.contracts10())
                                .committingInterval(order, 4L)
                                .build());

        // then
        assertEquals(1, delta.removed().size());
        assertTrue(delta.added().isEmpty());
        assertEquals(Long.valueOf(4L),
                delta.removed().get(0).endAtRootRevision());
    }

    @Test
    void shouldVerifyRetainedIntervalIdentityIsClosedExactlyAndReplacementStartsAfterEvent() {
        // given
        Node beforeChannel = scriptedChannel("old-topic");
        Node afterChannel = scriptedChannel("new-topic");
        Node before = new Node().contracts(
                new Node().properties(
                        "incoming", beforeChannel));
        Node after = new Node().contracts(
                new Node().properties(
                        "incoming", afterChannel));
        ExternalOrderKey originalStart =
                ExternalOrderKey.of(
                        Arrays.asList(3, "timeline", 1));
        ExternalOrderKey current =
                ExternalOrderKey.of(
                        Arrays.asList(9, "timeline", 4));
        SubscriptionDelta.Entry retained =
                descriptor(
                        beforeChannel,
                        "old-topic",
                        2L,
                        originalStart);

        // when
        SubscriptionDelta delta =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext
                                .builder(
                                        before,
                                        after,
                                        Collections.singleton(
                                                "/contracts/incoming"),
                                        GasSchedule.contracts10())
                                .activeSubscriptionIntervals(
                                        Collections.singleton(
                                                retained))
                                .committingInterval(current, 7L)
                                .build());
        SubscriptionDelta.Entry retired =
                delta.removed().get(0);
        SubscriptionDelta.Entry activated =
                delta.added().get(0);

        // then
        assertEquals(1, delta.removed().size());
        assertEquals(1, delta.added().size());
        assertEquals(Long.valueOf(2L),
                retired.activationRootRevision());
        assertEquals(originalStart,
                retired.startAfterExternalOrderKey());
        assertEquals(Long.valueOf(7L),
                retired.endAtRootRevision());
        assertEquals(Long.valueOf(7L),
                activated.activationRootRevision());
        assertEquals(current,
                activated.startAfterExternalOrderKey());
        assertNull(activated.endAtRootRevision());
    }

    @Test
    void shouldVerifyExactRetainedIntervalIsRetiredWhenOccurrenceIsRemoved() {
        // given
        Node channel = scriptedChannel("topic");
        Node before = new Node().contracts(
                new Node().properties("incoming", channel));
        Node after = new Node().contracts(new Node());
        ExternalOrderKey originalStart =
                ExternalOrderKey.of(
                        Arrays.asList(1, "timeline", 0));
        SubscriptionDelta.Entry retained =
                descriptor(channel, "topic", 1L, originalStart);

        // when
        SubscriptionDelta delta =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext
                                .builder(
                                        before,
                                        after,
                                        Collections.singleton(
                                                "/contracts/incoming"),
                                        GasSchedule.contracts10())
                                .activeSubscriptionIntervals(
                                        Collections.singleton(
                                                retained))
                                .committingInterval(
                                        ExternalOrderKey.of(
                                                Arrays.asList(
                                                        5,
                                                        "timeline",
                                                        2)),
                                        6L)
                                .build());

        // then
        assertTrue(delta.added().isEmpty());
        assertEquals(1, delta.removed().size());
        assertEquals(Long.valueOf(1L),
                delta.removed().get(0)
                        .activationRootRevision());
        assertEquals(originalStart,
                delta.removed().get(0)
                        .startAfterExternalOrderKey());
        assertEquals(Long.valueOf(6L),
                delta.removed().get(0)
                        .endAtRootRevision());
    }

    @Test
    void shouldVerifyRemovingEmbeddedDeclarationRetiresRetainedDescendantWithoutOldScan() {
        // given
        Node channel = scriptedChannel("topic");
        Node embedded = new Node()
                .type(reference(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(
                                new Node().value("/child")));
        Node child = new Node().contracts(
                new Node().properties("incoming", channel));
        Node before = new Node()
                .properties("child", child)
                .contracts(new Node().properties(
                        "embedded", embedded));
        Node after = before.clone();
        after.getContracts().getProperties().remove("embedded");
        String contribution =
                DirectBlueIdCalculator.calculateBlueId(channel);
        SubscriptionDelta.Entry retained =
                new SubscriptionDelta.Entry(
                        "/child",
                        "incoming",
                        RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                        Collections.singletonList(contribution),
                        0,
                        Collections.singletonList("topic"),
                        CheckpointDomain.derive(
                                RuntimeBlueIds
                                        .SCRIPTED_EXTERNAL_CHANNEL,
                                Collections.singletonList(
                                        contribution),
                                "domain"),
                        1L,
                        ExternalOrderKey.of(
                                Arrays.asList(
                                        1, "timeline", 0)),
                        null);

        // when
        SubscriptionDelta delta =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext
                                .builder(
                                        before,
                                        after,
                                        Collections.singleton(
                                                "/contracts/embedded"),
                                        GasSchedule.contracts10())
                                .activeSubscriptionIntervals(
                                        Collections.singleton(
                                                retained))
                                .committingInterval(
                                        ExternalOrderKey.of(
                                                Arrays.asList(
                                                        2,
                                                        "timeline",
                                                        0)),
                                        2L)
                                .build());

        // then
        assertTrue(delta.added().isEmpty());
        assertEquals(1, delta.removed().size());
        assertEquals("/child",
                delta.removed().get(0).scopePath());
        assertEquals(Long.valueOf(2L),
                delta.removed().get(0)
                        .endAtRootRevision());
    }

    @Test
    void shouldVerifyUnrelatedDeepBranchIsNeitherTraversedNorDemanded() {
        // given
        Node beforeChannel = scriptedChannel("old-topic");
        Node afterChannel = scriptedChannel("new-topic");
        Node before = new Node()
                .properties("unrelated", new ExplodingDeepNode())
                .contracts(new Node().properties(
                        "incoming", beforeChannel));
        Node after = new Node()
                .properties("unrelated", new ExplodingDeepNode())
                .contracts(new Node().properties(
                        "incoming", afterChannel));
        ExternalOrderKey priorOrder =
                ExternalOrderKey.of(
                        Arrays.asList(1, "timeline", 0));

        // when
        SubscriptionDelta delta =
                DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext
                                .builder(
                                        before,
                                        after,
                                        Collections.singleton(
                                                "/contracts/incoming/"
                                                        + "subscriptionKey"),
                                        GasSchedule.contracts10())
                                .activeSubscriptionIntervals(
                                        Collections.singleton(
                                                descriptor(
                                                        beforeChannel,
                                                        "old-topic",
                                                        1L,
                                                        priorOrder)))
                                .committingInterval(
                                        ExternalOrderKey.of(
                                                Arrays.asList(
                                                        2,
                                                        "timeline",
                                                        0)),
                                        2L)
                                .build());

        // then
        assertFalse(delta.isEmpty());
    }

    @Test
    void shouldVerifyExactScopeIdentityCannotRecurInEmbeddedAncestry() {
        // given
        Node child = new Node()
                .blueId("same-exact-scope")
                .contracts(new Node());
        Node root = new Node()
                .blueId("same-exact-scope")
                .properties("child", child)
                .contracts(new Node().properties(
                        "embedded",
                        new Node()
                                .type(reference(
                                        RuntimeBlueIds.PROCESS_EMBEDDED))
                                .properties(
                                        "paths",
                                        new Node().items(
                                                new Node().value(
                                                        "/child")))));

        // when
        SubscriptionSurfaceInvalidException failure = captureFailure(
                () -> DirectSubscriptionSurfaceValidator.INSTANCE.validate(
                        SubscriptionSurfaceValidationContext.builder(
                                        root,
                                        root.clone(),
                                        Collections.singleton(
                                                "/contracts/embedded/paths"),
                                        GasSchedule.contracts10())
                                .build()));

        // then
        assertEquals(SubscriptionSurfaceInvalidException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "revisits exact node same-exact-scope"));
    }

    private Blue blue(EffectiveTypes types,
                      ChannelProcessor<TestEventChannel> processor) {
        NodeProvider provider = blueId -> {
            Node node = types.nodes.get(blueId);
            return node != null
                    ? Collections.singletonList(node.clone())
                    : null;
        };
        Blue blue = ProcessorTestSupport.blue(provider);
        blue.registerContractProcessor(processor);
        return blue;
    }

    private EffectiveTypes effectiveTypes(
            String beforeKey,
            String afterKey) {
        Node beforeChannel = externalChannel(beforeKey);
        Node afterChannel = externalChannel(afterKey);
        String beforeChannelBlueId =
                DirectBlueIdCalculator.calculateBlueId(beforeChannel);
        String afterChannelBlueId =
                DirectBlueIdCalculator.calculateBlueId(afterChannel);
        Node beforeType = new Node().contracts(
                new Node().properties(
                        "incoming",
                        reference(beforeChannelBlueId)));
        Node afterType = new Node().contracts(
                new Node().properties(
                        "incoming",
                        reference(afterChannelBlueId)));
        String beforeTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(beforeType);
        String afterTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(afterType);
        Map<String, Node> nodes = new LinkedHashMap<>();
        nodes.put(beforeChannelBlueId, beforeChannel);
        nodes.put(afterChannelBlueId, afterChannel);
        nodes.put(beforeTypeBlueId, beforeType);
        nodes.put(afterTypeBlueId, afterType);
        return new EffectiveTypes(
                nodes,
                beforeChannelBlueId,
                afterChannelBlueId,
                beforeTypeBlueId,
                afterTypeBlueId);
    }

    private Node externalChannel(String subscriptionKey) {
        return new Node()
                .type(reference(TEST_CHANNEL_TYPE))
                .properties(
                        "eventType",
                        new Node().value(subscriptionKey));
    }

    private Node scriptedChannel(String subscriptionKey) {
        return new Node()
                .type(reference(
                        RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL))
                .properties(
                        "subscriptionKey",
                        new Node().value(subscriptionKey))
                .properties(
                        "checkpointDomain",
                        new Node().value("domain"));
    }

    private SubscriptionDelta.Entry descriptor(
            Node channel,
            String subscriptionKey,
            long activationRevision,
            ExternalOrderKey start) {
        String contribution =
                DirectBlueIdCalculator.calculateBlueId(channel);
        return new SubscriptionDelta.Entry(
                "/",
                "incoming",
                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                Collections.singletonList(contribution),
                0,
                Collections.singletonList(subscriptionKey),
                CheckpointDomain.derive(
                        RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                        Collections.singletonList(contribution),
                        "domain"),
                activationRevision,
                start,
                null);
    }

    private static final class ExplodingDeepNode extends Node {
        @Override
        public Map<String, Node> getProperties() {
            throw new AssertionError(
                    "unchanged deep branch was traversed");
        }

        @Override
        public Node getContracts() {
            throw new AssertionError(
                    "unchanged deep branch was inspected");
        }
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class PortableExternalProcessor
            implements ChannelProcessor<TestEventChannel> {

        private final ExternalChannelSubscriptionFunctions<TestEventChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<TestEventChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestEventChannel immutableContractSnapshot) {
                        String key =
                                immutableContractSnapshot.getEventType();
                        return key != null
                                ? Collections.singletonList(key)
                                : Collections.<String>emptyList();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            TestEventChannel immutableContractSnapshot) {
                        return "test-event-channel-v1";
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
    }

    private static final class UnindexableExternalProcessor
            implements ChannelProcessor<TestEventChannel> {

        @Override
        public Class<TestEventChannel> contractType() {
            return TestEventChannel.class;
        }
    }

    private static final class EffectiveTypes {
        private final Map<String, Node> nodes;
        private final String beforeChannelBlueId;
        private final String afterChannelBlueId;
        private final String beforeTypeBlueId;
        private final String afterTypeBlueId;

        private EffectiveTypes(
                Map<String, Node> nodes,
                String beforeChannelBlueId,
                String afterChannelBlueId,
                String beforeTypeBlueId,
                String afterTypeBlueId) {
            this.nodes = nodes;
            this.beforeChannelBlueId = beforeChannelBlueId;
            this.afterChannelBlueId = afterChannelBlueId;
            this.beforeTypeBlueId = beforeTypeBlueId;
            this.afterTypeBlueId = afterTypeBlueId;
        }
    }
}
