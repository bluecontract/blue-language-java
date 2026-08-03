package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.provider.NodeProvider;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SubscriptionSurfaceProjectionTest {

    private static final String CHANNEL_KEY = "incoming";
    private static final String EVENT_TYPE_KEY = "eventType";
    private static final String TEST_CHANNEL_TYPE =
            ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;
    private static final String CHILD_KEY = "child";
    private static final String CHILD_SCOPE = "/child";
    private static final String EMBEDDED_CONTRACT_POINTER =
            "/contracts/" + ProcessorContractConstants.KEY_EMBEDDED;
    private static final String ROOT_TYPE_POINTER = "/type";
    private static final String UNRELATED_CONTRACT_KEY = "metadata";
    private static final String UNRELATED_CONTRACT_POINTER =
            "/contracts/" + UNRELATED_CONTRACT_KEY;

    @Test
    void shouldProjectInitialSurfaceWithActivationBounds() {
        // given
        Node exactRoot = rootWithSubscription("initial-topic");
        String retainedCallerRoot = exactRoot.toString();
        ExternalOrderKey activationOrder = order(3);

        // when
        SubscriptionDelta delta;
        try (Blue blue = ProcessorTestSupport.blue(
                subscriptionProvider("initial-topic"))) {
            blue.registerContractProcessor(
                    new PortableExternalProcessor());
            SubscriptionSurfaceProjection projection =
                    new SubscriptionSurfaceProjection(
                            blue.getDocumentProcessor(),
                            lifecycle(new TrackingResources()));
            delta = projection.projectInitial(
                    exactRoot,
                    4L,
                    activationOrder);
        }

        // then
        assertEquals(retainedCallerRoot, exactRoot.toString());
        assertEquals(1, delta.added().size());
        assertTrue(delta.removed().isEmpty());
        SubscriptionDelta.Entry activated = delta.added().get(0);
        assertEquals("/", activated.scopePath());
        assertEquals(CHANNEL_KEY, activated.channelKey());
        assertEquals(Collections.singletonList("initial-topic"),
                activated.subscriptionKeys());
        assertEquals(Long.valueOf(4L),
                activated.activationRootRevision());
        assertEquals(activationOrder,
                activated.startAfterExternalOrderKey());
        assertNull(activated.endAtRootRevision());
        assertThrows(UnsupportedOperationException.class,
                () -> delta.added().clear());
    }

    @Test
    void shouldProjectUpdateAgainstPriorActiveIntervals() {
        // given
        Node initialRoot = rootWithSubscription("old-topic");
        Node updatedRoot = rootWithSubscription("new-topic");
        ExternalOrderKey originalOrder = order(5);
        ExternalOrderKey transitionOrder = order(8);

        // when
        SubscriptionDelta initial;
        SubscriptionDelta update;
        try (Blue blue = ProcessorTestSupport.blue(
                subscriptionProvider(
                        "old-topic",
                        "new-topic"))) {
            blue.registerContractProcessor(
                    new PortableExternalProcessor());
            SubscriptionSurfaceProjection projection =
                    new SubscriptionSurfaceProjection(
                            blue.getDocumentProcessor(),
                            lifecycle(new TrackingResources()));
            initial = projection.projectInitial(
                    initialRoot,
                    6L,
                    originalOrder);
            update = projection.projectUpdate(
                    updatedRoot,
                    initial.added(),
                    Collections.singleton(
                            "/contracts/incoming/eventType"),
                    9L,
                    transitionOrder);
        }

        // then
        assertEquals(1, update.removed().size());
        assertEquals(1, update.added().size());
        SubscriptionDelta.Entry retired = update.removed().get(0);
        SubscriptionDelta.Entry activated = update.added().get(0);
        assertEquals(Collections.singletonList("old-topic"),
                retired.subscriptionKeys());
        assertEquals(Long.valueOf(6L),
                retired.activationRootRevision());
        assertEquals(originalOrder,
                retired.startAfterExternalOrderKey());
        assertEquals(Long.valueOf(9L),
                retired.endAtRootRevision());
        assertEquals(Collections.singletonList("new-topic"),
                activated.subscriptionKeys());
        assertEquals(Long.valueOf(9L),
                activated.activationRootRevision());
        assertEquals(transitionOrder,
                activated.startAfterExternalOrderKey());
        assertNull(activated.endAtRootRevision());
    }

    @Test
    void shouldRetireDescendantWhenAncestorEmbeddedRouteIsRemoved() {
        // given
        Node initialRoot = rootWithEmbeddedSubscription("descendant-topic");
        Node resultingRoot = initialRoot.clone();
        resultingRoot.getContracts().getProperties().remove(
                ProcessorContractConstants.KEY_EMBEDDED);
        ExternalOrderKey activationOrder = order(13);
        ExternalOrderKey transitionOrder = order(14);

        // when
        SubscriptionDelta initial;
        SubscriptionDelta update;
        try (Blue blue = ProcessorTestSupport.blue(
                subscriptionProvider("descendant-topic"))) {
            blue.registerContractProcessor(
                    new PortableExternalProcessor());
            SubscriptionSurfaceProjection projection =
                    new SubscriptionSurfaceProjection(
                            blue.getDocumentProcessor(),
                            lifecycle(new TrackingResources()));
            initial = projection.projectInitial(
                    initialRoot,
                    20L,
                    activationOrder);
            update = projection.projectUpdate(
                    resultingRoot,
                    initial.added(),
                    Collections.singleton(
                            EMBEDDED_CONTRACT_POINTER),
                    21L,
                    transitionOrder);
        }

        // then
        assertEquals(1, initial.added().size());
        assertEquals(CHILD_SCOPE,
                initial.added().get(0).scopePath());
        assertTrue(update.added().isEmpty());
        assertEquals(1, update.removed().size());
        SubscriptionDelta.Entry retired = update.removed().get(0);
        assertEquals(CHILD_SCOPE, retired.scopePath());
        assertEquals(Long.valueOf(20L),
                retired.activationRootRevision());
        assertEquals(activationOrder,
                retired.startAfterExternalOrderKey());
        assertEquals(Long.valueOf(21L),
                retired.endAtRootRevision());
    }

    @Test
    void shouldKeepDescendantWhenUnrelatedAncestorContractIsRemoved() {
        // given
        Node initialRoot = rootWithEmbeddedSubscription("stable-topic");
        initialRoot.getContracts().properties(
                UNRELATED_CONTRACT_KEY,
                new Node().type(new Node().blueId(
                        RuntimeBlueIds.TYPE_GENERALIZATION_POLICY)));
        Node resultingRoot = initialRoot.clone();
        resultingRoot.getContracts().getProperties().remove(
                UNRELATED_CONTRACT_KEY);
        Set<String> changedPointers = new LinkedHashSet<>(
                Collections.singleton(
                        UNRELATED_CONTRACT_POINTER));

        // when
        SubscriptionDelta update;
        try (Blue blue = ProcessorTestSupport.blue(
                subscriptionProvider("stable-topic"))) {
            blue.registerContractProcessor(
                    new PortableExternalProcessor());
            SubscriptionSurfaceProjection projection =
                    new SubscriptionSurfaceProjection(
                            blue.getDocumentProcessor(),
                            lifecycle(new TrackingResources()));
            SubscriptionDelta initial = projection.projectInitial(
                    initialRoot,
                    25L,
                    order(17));
            update = projection.projectUpdate(
                    resultingRoot,
                    initial.added(),
                    changedPointers,
                    26L,
                    order(18));
        }

        // then
        assertTrue(update.isEmpty());
        assertEquals(
                Collections.singleton(
                        UNRELATED_CONTRACT_POINTER),
                changedPointers);
    }

    @Test
    void shouldRetireDescendantWhenAncestorEmbeddedRouteIsRetyped() {
        // given
        Node channel = subscriptionChannel("descendant-topic");
        Node embeddedParentType = new Node()
                .name("Parent type with embedded child")
                .contracts(new Node().properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        processEmbeddedChildContract()));
        Node detachedParentType = new Node()
                .name("Parent type without embedded child");
        Node initialRoot = referencedParentWithChild(
                embeddedParentType, channel);
        Node resultingRoot = referencedParentWithChild(
                detachedParentType, channel);
        ExternalOrderKey activationOrder = order(15);

        // when
        SubscriptionDelta update;
        try (Blue blue = ProcessorTestSupport.blue(
                nodeProvider(
                        channel,
                        embeddedParentType,
                        detachedParentType))) {
            blue.registerContractProcessor(
                    new PortableExternalProcessor());
            SubscriptionSurfaceProjection projection =
                    new SubscriptionSurfaceProjection(
                            blue.getDocumentProcessor(),
                            lifecycle(new TrackingResources()));
            SubscriptionDelta initial = projection.projectInitial(
                    initialRoot,
                    30L,
                    activationOrder);
            update = projection.projectUpdate(
                    resultingRoot,
                    initial.added(),
                    Collections.singleton(
                            ROOT_TYPE_POINTER),
                    31L,
                    order(16));
        }

        // then
        assertEquals(1, update.removed().size());
        assertEquals(CHILD_SCOPE,
                update.removed().get(0).scopePath());
        assertEquals(Long.valueOf(31L),
                update.removed().get(0).endAtRootRevision());
        assertTrue(update.added().isEmpty());
    }

    @Test
    void shouldPreserveMutableInputsAndUseConfiguredValidatorAndRuntimeSession() {
        // given
        AtomicReference<SubscriptionSurfaceValidationContext> captured =
                new AtomicReference<>();
        AtomicBoolean semanticOutputAvailable = new AtomicBoolean();
        SubscriptionDelta expected = SubscriptionDelta.empty();
        Node exactRoot = rootWithSubscription("topic");
        String retainedCallerRoot = exactRoot.toString();
        SubscriptionDelta.Entry prior = priorInterval();
        List<SubscriptionDelta.Entry> priorIntervals =
                new ArrayList<>(Collections.singletonList(prior));
        Set<String> changedPointers = new LinkedHashSet<>(
                Collections.singleton(
                        "/contracts/incoming/eventType"));

        // when
        SubscriptionDelta actual;
        String callerRootAfterProjection;
        try (Blue blue = ProcessorTestSupport.blue(
                subscriptionProvider("topic"))) {
            blue.registerContractProcessor(
                    new PortableExternalProcessor());
            DocumentProcessor processor = DocumentProcessor.Builder
                    .from(blue.getDocumentProcessor())
                    .subscriptionSurfaceValidator(context -> {
                        captured.set(context);
                        RuntimeWorkSession session =
                                context.newRuntimeWorkSession();
                        semanticOutputAvailable.set(
                                session.hasSemanticOutputBoundary());
                        session.close();
                        context.inputRoot().properties(
                                "validatorInputMutation",
                                new Node().value(true));
                        context.tentativeRoot().properties(
                                "validatorTentativeMutation",
                                new Node().value(true));
                        return expected;
                    })
                    .build();
            try {
                SubscriptionSurfaceProjection projection =
                        new SubscriptionSurfaceProjection(
                                processor,
                                lifecycle(new TrackingResources()));
                actual = projection.projectUpdate(
                        exactRoot,
                        priorIntervals,
                        changedPointers,
                        12L,
                        order(11));
            } finally {
                processor.close();
            }
        }
        callerRootAfterProjection = exactRoot.toString();
        priorIntervals.clear();
        changedPointers.clear();
        exactRoot.properties("callerMutation", new Node().value(true));

        // then
        SubscriptionSurfaceValidationContext context = captured.get();
        assertSame(expected, actual);
        assertNotNull(context);
        assertTrue(context.hasActiveSubscriptionIntervals());
        assertEquals(Collections.singletonList(prior),
                context.activeSubscriptionIntervals());
        assertEquals(Collections.singleton(
                        "/contracts/incoming/eventType"),
                context.changedPaths());
        assertNotSame(exactRoot, context.inputRoot());
        assertNotSame(exactRoot, context.tentativeRoot());
        assertNotSame(context.inputRoot(), context.tentativeRoot());
        assertNotNull(context.inputSnapshot());
        assertSame(context.inputSnapshot(),
                context.tentativeSnapshot());
        assertEquals(retainedCallerRoot,
                context.inputSnapshot().canonicalRoot().toString());
        assertEquals(retainedCallerRoot,
                context.tentativeSnapshot().canonicalRoot().toString());
        assertEquals(retainedCallerRoot,
                callerRootAfterProjection);
        assertTrue(semanticOutputAvailable.get());
    }

    @Test
    void shouldHoldLifecycleReadScopeUntilProjectionCompletes() {
        // given
        TrackingResources resources = new TrackingResources();
        DocumentProcessorLifecycle lifecycle = lifecycle(resources);
        AtomicBoolean detachedInsideValidator = new AtomicBoolean(true);
        DocumentProcessor processor = DocumentProcessor.builder()
                .snapshotStore(new PassthroughSnapshotManager())
                .subscriptionSurfaceValidator(context -> {
                    lifecycle.close();
                    detachedInsideValidator.set(resources.detached.get());
                    return SubscriptionDelta.empty();
                })
                .build();
        SubscriptionSurfaceProjection projection =
                new SubscriptionSurfaceProjection(processor, lifecycle);

        // when
        SubscriptionDelta delta = projection.projectInitial(
                new Node(),
                0L,
                order(0));
        Throwable closedFailure = FailureCapture.captureFailure(
                () -> projection.projectInitial(
                        new Node(),
                        0L,
                        order(0)));
        processor.close();

        // then
        assertTrue(delta.isEmpty());
        assertFalse(detachedInsideValidator.get());
        assertEquals(1, resources.cleared.get());
        assertTrue(resources.detached.get());
        assertTrue(closedFailure instanceof IllegalStateException);
        assertEquals("Document processor is closed",
                closedFailure.getMessage());
    }

    @Test
    void shouldFailClosedWithoutVerifiedSnapshotManager() {
        // given
        DocumentProcessor processor = DocumentProcessor.builder()
                .subscriptionSurfaceValidator(
                        context -> SubscriptionDelta.empty())
                .build();
        SubscriptionSurfaceProjection projection =
                new SubscriptionSurfaceProjection(
                        processor,
                        lifecycle(new TrackingResources()));

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> projection.projectInitial(
                        new Node(),
                        0L,
                        order(0)));
        processor.close();

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertEquals(
                "Subscription surface projection requires a verified "
                        + "ProcessingSnapshotManager",
                failure.getMessage());
    }

    @Test
    void shouldFailClosedWhenSnapshotGenerationIsStale() {
        // given
        AtomicBoolean validatorCalled = new AtomicBoolean();
        DocumentProcessor processor = DocumentProcessor.builder()
                .snapshotStore(new PassthroughSnapshotManager(false))
                .subscriptionSurfaceValidator(context -> {
                    validatorCalled.set(true);
                    return SubscriptionDelta.empty();
                })
                .build();
        SubscriptionSurfaceProjection projection =
                new SubscriptionSurfaceProjection(
                        processor,
                        lifecycle(new TrackingResources()));

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> projection.projectInitial(
                        new Node(),
                        0L,
                        order(0)));
        processor.close();

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertEquals(
                "Subscription surface projection snapshot generation is no longer current",
                failure.getMessage());
        assertFalse(validatorCalled.get());
    }

    @Test
    void shouldMarkRetainedIntervalsAndConservativeScopesForCustomValidator() {
        // given
        AtomicReference<SubscriptionSurfaceValidationContext> captured =
                new AtomicReference<>();
        DocumentProcessor processor = DocumentProcessor.builder()
                .snapshotStore(new PassthroughSnapshotManager())
                .subscriptionSurfaceValidator(context -> {
                    captured.set(context);
                    return SubscriptionDelta.empty();
                })
                .build();
        SubscriptionSurfaceProjection projection =
                new SubscriptionSurfaceProjection(
                        processor,
                        lifecycle(new TrackingResources()));
        Set<String> callerChanges = new LinkedHashSet<>(
                Collections.singleton(
                        EMBEDDED_CONTRACT_POINTER + "/paths"));
        SubscriptionDelta.Entry retainedChild =
                new SubscriptionDelta.Entry(
                        CHILD_SCOPE,
                        CHANNEL_KEY,
                        TEST_CHANNEL_TYPE,
                        Collections.singletonList("source-blue-id"),
                        0,
                        Collections.singletonList("old-topic"),
                        "checkpoint-domain-blue-id",
                        7L,
                        order(6),
                        null);

        // when
        projection.projectUpdate(
                new Node(),
                Collections.singletonList(retainedChild),
                callerChanges,
                8L,
                order(7));
        processor.close();

        // then
        SubscriptionSurfaceValidationContext context = captured.get();
        assertNotNull(context);
        assertTrue(context.usesRetainedIntervalInputSurface());
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        EMBEDDED_CONTRACT_POINTER + "/paths",
                        CHILD_SCOPE)),
                context.changedPaths());
        assertEquals(
                Collections.singleton(
                        EMBEDDED_CONTRACT_POINTER + "/paths"),
                callerChanges);
        assertEquals(
                context.inputRoot().toString(),
                context.tentativeRoot().toString());
        assertSame(
                context.inputSnapshot(),
                context.tentativeSnapshot());
    }

    private static Node rootWithSubscription(String key) {
        Node channel = subscriptionChannel(key);
        String channelBlueId =
                DirectBlueIdCalculator.calculateBlueId(channel);
        return new Node().contracts(
                new Node().properties(
                        CHANNEL_KEY,
                        new Node().blueId(channelBlueId)));
    }

    private static Node rootWithEmbeddedSubscription(String key) {
        Node channel = subscriptionChannel(key);
        return new Node()
                .properties(CHILD_KEY,
                        childWithSubscription(channel))
                .contracts(new Node().properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        processEmbeddedChildContract()));
    }

    private static Node referencedParentWithChild(
            Node parentType,
            Node channel) {
        return new Node()
                .type(new Node().blueId(
                        DirectBlueIdCalculator.calculateBlueId(
                                parentType)))
                .properties(CHILD_KEY,
                        childWithSubscription(channel));
    }

    private static Node childWithSubscription(Node channel) {
        String channelBlueId =
                DirectBlueIdCalculator.calculateBlueId(channel);
        return new Node().contracts(
                new Node().properties(
                        CHANNEL_KEY,
                        new Node().blueId(channelBlueId)));
    }

    private static Node processEmbeddedChildContract() {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        ProcessorContractConstants.KEY_PATHS,
                        new Node().items(
                                new Node().value(CHILD_SCOPE)));
    }

    private static Node subscriptionChannel(String key) {
        Node channel = new Node()
                .type(new Node().blueId(
                        TEST_CHANNEL_TYPE))
                .properties(
                        EVENT_TYPE_KEY,
                        new Node().value(key));
        return channel;
    }

    private static NodeProvider subscriptionProvider(
            String... subscriptionKeys) {
        Map<String, Node> channels = new LinkedHashMap<>();
        for (String key : subscriptionKeys) {
            Node channel = subscriptionChannel(key);
            channels.put(
                    DirectBlueIdCalculator.calculateBlueId(channel),
                    channel);
        }
        return blueId -> {
            Node channel = channels.get(blueId);
            return channel != null
                    ? Collections.singletonList(channel.clone())
                    : null;
        };
    }

    private static NodeProvider nodeProvider(Node... nodes) {
        Map<String, Node> nodesByBlueId = new LinkedHashMap<>();
        for (Node node : nodes) {
            nodesByBlueId.put(
                    DirectBlueIdCalculator.calculateBlueId(node),
                    node);
        }
        return blueId -> {
            Node node = nodesByBlueId.get(blueId);
            return node != null
                    ? Collections.singletonList(node.clone())
                    : null;
        };
    }

    private static SubscriptionDelta.Entry priorInterval() {
        return new SubscriptionDelta.Entry(
                "/",
                CHANNEL_KEY,
                TEST_CHANNEL_TYPE,
                Collections.singletonList("source-blue-id"),
                0,
                Collections.singletonList("old-topic"),
                "checkpoint-domain-blue-id",
                7L,
                order(6),
                null);
    }

    private static ExternalOrderKey order(int sequence) {
        return ExternalOrderKey.of(
                Arrays.asList(sequence, "timeline", 0));
    }

    private static DocumentProcessorLifecycle lifecycle(
            TrackingResources resources) {
        return new DocumentProcessorLifecycle(resources);
    }

    private static final class TrackingResources
            implements DocumentProcessorLifecycle.Resources {
        private final AtomicInteger cleared = new AtomicInteger();
        private final AtomicBoolean detached = new AtomicBoolean();

        @Override
        public void clearCaches() {
            cleared.incrementAndGet();
        }

        @Override
        public void detachRuntimeCollaborators() {
            detached.set(true);
        }
    }

    private static final class PassthroughSnapshotManager
            implements ProcessingSnapshotManager {

        private final boolean current;

        private PassthroughSnapshotManager() {
            this(true);
        }

        private PassthroughSnapshotManager(boolean current) {
            this.current = current;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            FrozenNode canonical = FrozenNode.fromNode(
                    document.clone());
            return new ResolvedSnapshot(
                    canonical,
                    FrozenNode.fromResolvedNode(document.clone()),
                    canonical.blueId());
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            throw new UnsupportedOperationException(
                    "Projection does not apply patches");
        }

        @Override
        public boolean isTransientStateCurrent() {
            return current;
        }
    }

    private static final class PortableExternalProcessor
            implements ChannelProcessor<TestEventChannel> {

        private final ExternalChannelSubscriptionFunctions<TestEventChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<TestEventChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestEventChannel immutableContractSnapshot) {
                        String eventType =
                                immutableContractSnapshot.getEventType();
                        return eventType != null
                                ? Collections.singletonList(eventType)
                                : Collections.<String>emptyList();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            TestEventChannel immutableContractSnapshot) {
                        return "subscription-projection-test-v1";
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
}
