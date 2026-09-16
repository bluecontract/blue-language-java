package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TerminateScopeContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Ordinary PROCESS must finish its transaction after successful termination. */
final class IndependentT08RegressionTest {

    @Test
    void shouldRetireActiveSubscriptionAfterRootTermination() {
        // given
        try (Blue blue = processor()) {
            Node input = initialized(blue, true);
            Node event = new TestEvent().eventId("termination").toNode();
            VerifiedExecutionEvidence evidence = evidence(input, event);
            // when
            ProcessingDebugResult debug = ProcessorEngine.processDocumentWithTrace(
                    blue.getDocumentProcessor(), input, event, evidence);

            // then
            assertEquals(ProcessorStatus.SUCCESS, debug.processResult().status());
            assertNotNull(ProcessorMarkerStore.terminationMarker(
                    debug.processResult().document(), "/"));
            assertTrue(debug.processResult().events().isEmpty());
            assertNotNull(debug.platformCommitCompanion());
            SubscriptionDelta delta = debug.platformCommitCompanion().subscriptionDelta();
            assertEquals(1, evidence.activeSubscriptionIntervals().size());
            assertEquals(1, delta.removed().size(),
                    "The committing termination must retire the active subscription");
            assertTrue(delta.added().isEmpty());
            assertEquals(1, debug.trace().records(
                    ProcessingTraceRecord.Kind.SUBSCRIPTION_DELTA).size());
        }
    }

    @Test
    void shouldFinalizeOrdinaryDeliveryAndKeepItsActiveSubscription() {
        // given
        try (Blue blue = processor()) {
            Node input = initialized(blue, false);
            Node event = new TestEvent().eventId("control").toNode();
            // when
            ProcessingDebugResult debug = ProcessorEngine.processDocumentWithTrace(
                    blue.getDocumentProcessor(), input, event, evidence(input, event));

            // then
            assertEquals(ProcessorStatus.SUCCESS, debug.processResult().status());
            assertNull(ProcessorMarkerStore.terminationMarker(
                    debug.processResult().document(), "/"));
            assertTrue(debug.platformCommitCompanion().subscriptionDelta().isEmpty());
            assertEquals(1, debug.trace().records(
                    ProcessingTraceRecord.Kind.SUBSCRIPTION_DELTA).size());
        }
    }

    @Test
    void shouldRetireAllDescendantsAndStopLaterHandlersAfterRootTermination() {
        // given
        try (Blue blue = facadeProcessor()) {
            Node input = initialize(blue, tree(true));
            String retainedInput = input.toString();
            // when
            ProcessingDebugResult debug = blue.getDocumentProcessor()
                    .processDocumentWithTrace(input, event("root-tree"));

            // then
            assertEquals(ProcessorStatus.SUCCESS, debug.processResult().status(),
                    DocumentProcessingResultTestSupport.diagnosticMessage(debug.processResult()));
            assertEquals(Arrays.asList("/:events", "/child:events", "/child/grandchild:events"),
                    debug.platformCommitCompanion().subscriptionDelta().removed().stream()
                            .map(entry -> entry.scopePath() + ":" + entry.channelKey())
                            .collect(Collectors.toList()));
            assertTrue(debug.platformCommitCompanion().subscriptionDelta().added().isEmpty());
            for (SubscriptionDelta.Entry removed
                    : debug.platformCommitCompanion().subscriptionDelta().removed()) {
                assertEquals(Long.valueOf(1L), removed.activationRootRevision());
                assertEquals(Long.valueOf(2L), removed.endAtRootRevision());
            }
            assertFalse(hasLaterEffect(debug.processResult().document()));
            assertFalse(hasLaterEffect(debug.processResult().document().getAsNode("/child")));
            assertFalse(hasLaterEffect(debug.processResult().document().getAsNode("/child/grandchild")));
            assertTrue(debug.trace().records(ProcessingTraceRecord.Kind.CHECKPOINT_WRITE).isEmpty(),
                    "Terminated deliveries must not create redundant checkpoints");
            assertEquals(retainedInput, input.toString());
            assertEquals(1, debug.trace().records(ProcessingTraceRecord.Kind.SUBSCRIPTION_DELTA).size());
        }
    }

    @Test
    void shouldRetireOnlyTerminatedChildSubtreeAndPreserveRootSubscription() {
        // given
        try (Blue blue = facadeProcessor()) {
            Node input = initialize(blue, tree(false));
            // when
            ProcessingDebugResult debug = blue.getDocumentProcessor()
                    .processDocumentWithTrace(input, event("child-tree"));

            // then
            assertEquals(ProcessorStatus.SUCCESS, debug.processResult().status(),
                    DocumentProcessingResultTestSupport.diagnosticMessage(debug.processResult()));
            assertNull(ProcessorMarkerStore.terminationMarker(debug.processResult().document(), "/"));
            assertNotNull(ProcessorMarkerStore.terminationMarker(debug.processResult().document(), "/child"));
            assertEquals(Arrays.asList("/child", "/child/grandchild"),
                    debug.platformCommitCompanion().subscriptionDelta().removed().stream()
                            .map(SubscriptionDelta.Entry::scopePath).collect(Collectors.toList()));
            assertTrue(debug.platformCommitCompanion().subscriptionDelta().added().isEmpty());
            assertFalse(hasLaterEffect(debug.processResult().document().getAsNode("/child")));
            assertFalse(hasLaterEffect(debug.processResult().document().getAsNode("/child/grandchild")));
        }
    }

    @Test
    void shouldPublishFinalizedSnapshotAndAvoidRepeatedRetirementOnReplay() {
        // given
        try (Blue blue = facadeProcessor()) {
            ResolvedSnapshot input = blue.loadSnapshot(initialized(blue, true));
            // when
            ProcessingDebugResult debug = blue.getDocumentProcessor()
                    .processDocumentWithTrace(input, event("snapshot"));

            // then
            assertEquals(ProcessorStatus.SUCCESS, debug.processResult().status());
            assertEquals(1, debug.platformCommitCompanion().subscriptionDelta().removed().size());
            assertTrue(debug.platformCommitCompanion().commitsRootAndOutbox());
            assertEquals(2L, debug.platformCommitCompanion().resultingRootRevision());

            ResolvedSnapshot terminated = blue.loadSnapshot(debug.processResult().document());
            ProcessingDebugResult replay = blue.getDocumentProcessor()
                    .processDocumentWithTrace(terminated, event("snapshot"));
            assertEquals(ProcessorStatus.TERMINATED, replay.processResult().status());
            assertFalse(replay.processResult().commits());
            assertEquals(terminated.canonicalRoot().toString(), replay.processResult().document().toString());
            assertTrue(replay.processResult().events().isEmpty());
            assertTrue(replay.trace().records(ProcessingTraceRecord.Kind.SUBSCRIPTION_DELTA).isEmpty());
            assertTrue(replay.platformCommitCompanion() == null
                    || replay.platformCommitCompanion().subscriptionDelta().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRollBackFinalValidationRejectionAndAllowRetry(boolean useSnapshot) {
        // given
        try (Blue blue = facadeProcessor()) {
            Node authored = authored(true);
            authored.getAsNode("/contracts/terminate")
                    .properties("emitAfter", new Node().value(true))
                    .properties("patchAfter", new Node().value(true));
            Node input = initialize(blue, authored);
            ResolvedSnapshot snapshot = blue.loadSnapshot(input);
            String retainedInput = input.toString();
            String retainedIdentity = snapshot.blueId();
            AtomicInteger validations = new AtomicInteger();
            AtomicReference<Node> tentative = new AtomicReference<>();
            try (DocumentProcessor rejecting = DocumentProcessor.Builder.from(blue.getDocumentProcessor())
                    .subscriptionSurfaceValidator(context -> {
                        validations.incrementAndGet();
                        tentative.set(context.tentativeRoot().clone());
                        throw new SubscriptionSurfaceInvalidException("T08 final validation rejection");
                    }).build()) {
                // when
                ProcessingDebugResult debug = useSnapshot
                        ? rejecting.processDocumentWithTrace(snapshot, event("reject"))
                        : rejecting.processDocumentWithTrace(input, event("reject"));

                // then
                assertEquals(1, validations.get());
                assertNotNull(ProcessorMarkerStore.terminationMarker(tentative.get(), "/"));
                assertNotNull(tentative.get().getNode("/afterTermination"));
                assertEquals(ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID, debug.processResult().status(),
                        DocumentProcessingResultTestSupport.diagnosticMessage(debug.processResult()));
                assertRolledBack(input, debug);
            }
            assertEquals(retainedInput, input.toString());
            assertEquals(retainedIdentity, snapshot.blueId());
            ProcessingDebugResult retry = useSnapshot
                    ? blue.getDocumentProcessor().processDocumentWithTrace(snapshot, event("reject"))
                    : blue.getDocumentProcessor().processDocumentWithTrace(input, event("reject"));
            assertEquals(ProcessorStatus.SUCCESS, retry.processResult().status());
            assertEquals(1, retry.processResult().events().size());
            assertEquals(1, retry.platformCommitCompanion().subscriptionDelta().removed().size());
        }
    }

    @Test
    void shouldBypassFinalValidationAfterTerminationFailure() {
        // given
        try (Blue blue = facadeProcessor()) {
            Node authored = authored(true);
            authored.getAsNode("/contracts/terminate").properties("mode", new Node().value("fatal"));
            Node input = initialize(blue, authored);
            try (DocumentProcessor rejecting = DocumentProcessor.Builder.from(blue.getDocumentProcessor())
                    .subscriptionSurfaceValidator(context -> {
                        unexpectedWork("A failed execution must not enter commit validation");
                        return SubscriptionDelta.empty();
                    }).build()) {
                // when
                ProcessingDebugResult debug = rejecting.processDocumentWithTrace(input, event("fatal"));

                // then
                assertEquals(ProcessorStatus.RUNTIME_FATAL, debug.processResult().status());
                assertRolledBack(input, debug);
            }
        }
    }

    @Test
    void shouldRetireMultipleRootSubscriptionsWithoutExecutingLaterDelivery() {
        // given
        try (Blue blue = facadeProcessor()) {
            Node authored = authored(true);
            authored.getContracts().properties("laterEvents", new Node()
                    .type(new Node().blueId(ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL))
                    .properties("order", new Node().value(20)));
            authored.getContracts().properties("laterHandler", handler("laterEvents", "/later"));
            Node input = initialize(blue, authored);
            // when
            ProcessingDebugResult debug = blue.getDocumentProcessor()
                    .processDocumentWithTrace(input, event("multiple"));

            // then
            assertEquals(ProcessorStatus.SUCCESS, debug.processResult().status());
            assertEquals(Arrays.asList("events", "laterEvents"),
                    debug.platformCommitCompanion().subscriptionDelta().removed().stream()
                            .map(SubscriptionDelta.Entry::channelKey).collect(Collectors.toList()));
            assertFalse(hasLaterEffect(debug.processResult().document()));
            assertTrue(debug.trace().records(ProcessingTraceRecord.Kind.CHECKPOINT_WRITE).isEmpty());
        }
    }

    @Test
    void shouldDrainNestedEventsOnceBeforeRootTerminationAndFinalization() {
        // given
        try (Blue blue = facadeProcessor()) {
            blue.registerContractProcessor(new HandlerProcessor<SetProperty>() {
                @Override
                public Class<SetProperty> contractType() {
                    return SetProperty.class;
                }

                @Override
                public void execute(SetProperty contract, ProcessorExecutionContext context) {
                    String action = contract.getPropertyKey();
                    if ("/childEmit".equals(action)) {
                        context.emitEvent(new Node().properties("kind", new Node().value("child")));
                        context.terminateGracefully("child complete");
                    } else if ("/rootEmit".equals(action)) {
                        context.emitEvent(new Node().properties("kind", new Node().value("root")));
                        context.terminateGracefully("root complete");
                    } else {
                        unexpectedWork("Later ordinary handler must not execute: " + action);
                    }
                }
            });
            Node child = authored(false);
            child.getContracts().properties("emit", handler("events", "/childEmit"));
            Node authored = new Node().name("Nested T08").properties("child", child)
                    .contracts(new Node()
                            .properties("childEvents", new Node()
                                    .type(new Node().blueId(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                                    .properties("sourcePath", new Node().value("/child")))
                            .properties("emit", handler("childEvents", "/rootEmit"))
                            .properties("triggered", new Node()
                                    .type(new Node().blueId(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)))
                            .properties("later", handler("triggered", "/mustNotRun")));
            embed(authored, "/child");
            Node input = initialize(blue, authored);
            // when
            ProcessingDebugResult debug = blue.getDocumentProcessor()
                    .processDocumentWithTrace(input, event("nested"));

            // then
            assertEquals(ProcessorStatus.SUCCESS, debug.processResult().status(),
                    DocumentProcessingResultTestSupport.diagnosticMessage(debug.processResult()));
            assertNotNull(ProcessorMarkerStore.terminationMarker(debug.processResult().document(), "/"));
            assertNotNull(ProcessorMarkerStore.terminationMarker(debug.processResult().document(), "/child"));
            assertEquals(1, debug.processResult().events().size());
            assertEquals("root", debug.processResult().events().get(0).getAsText("/kind"));
            List<ProcessingTraceRecord> drained = debug.trace().records(ProcessingTraceRecord.Kind.EVENT_DEQUEUED);
            assertEquals(Arrays.asList("child", "root"), drained.stream()
                    .map(row -> row.node().getAsText("/kind")).collect(Collectors.toList()));
            assertEquals(1, debug.platformCommitCompanion().subscriptionDelta().removed().size());
            assertEquals("/child", debug.platformCommitCompanion().subscriptionDelta().removed().get(0).scopePath());
            List<ProcessingTraceRecord> finalized = debug.trace().records(ProcessingTraceRecord.Kind.SUBSCRIPTION_DELTA);
            assertEquals(1, finalized.size());
            assertTrue(drained.get(1).sequence() < finalized.get(0).sequence());
            assertEquals(2L, debug.trace().gas().stream()
                    .filter(row -> "processorMarkerWritten".equals(row.counter())).count());
        }
    }

    @Test
    void shouldSettleEarlierDescendantCheckpointsExactlyOnceAfterRootTermination() {
        // given
        try (Blue blue = facadeProcessor()) {
            Node input = initialize(blue, treeWithEarlierDeliveries());
            // when
            ProcessingDebugResult debug = blue.getDocumentProcessor()
                    .processDocumentWithTrace(input, event("earlier-deliveries"));

            // then
            assertEquals(ProcessorStatus.SUCCESS, debug.processResult().status());
            assertTrue(hasLaterEffect(debug.processResult().document().getAsNode("/child")));
            assertTrue(hasLaterEffect(debug.processResult().document().getAsNode("/child/grandchild")));
            assertFalse(hasLaterEffect(debug.processResult().document()));
            assertEquals(Arrays.asList("/child", "/child/grandchild"),
                    debug.trace().records(ProcessingTraceRecord.Kind.CHECKPOINT_WRITE).stream()
                            .map(ProcessingTraceRecord::scopePath).collect(Collectors.toList()));
            assertEquals(3, debug.platformCommitCompanion().subscriptionDelta().removed().size());
            assertTrue(debug.platformCommitCompanion().subscriptionDelta().added().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"processorMarkerWritten", "checkpointWritten"})
    void shouldRollBackGasExhaustionAtMarkerOrFinalSettlement(String counter) {
        // given
        try (Blue blue = facadeProcessor()) {
            Node input = "processorMarkerWritten".equals(counter)
                    ? initialized(blue, true) : initialize(blue, treeWithEarlierDeliveries());
            Node event = event("gas-" + counter);
            ProcessingDebugResult control = blue.getDocumentProcessor().processDocumentWithTrace(input, event);
            long prefix = prefixBeforeCharge(control, counter);
            try (DocumentProcessor limited = DocumentProcessor.Builder.from(blue.getDocumentProcessor())
                    .gasLimit(prefix).build()) {
                // when
                ProcessingDebugResult debug = limited.processDocumentWithTrace(input, event);

                // then
                assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, debug.processResult().status(),
                        DocumentProcessingResultTestSupport.diagnosticMessage(debug.processResult()));
                assertEquals(prefix, debug.processResult().totalGas());
                assertRolledBack(input, debug);
            }
        }
    }

    private static long prefixBeforeCharge(ProcessingDebugResult control, String counter) {
        assertEquals(ProcessorStatus.SUCCESS, control.processResult().status());
        long prefix = 0L;
        for (GasTraceEntry charge : control.trace().gas()) {
            if (counter.equals(charge.counter())) {
                return prefix;
            }
            prefix += charge.subtotal();
        }
        throw new AssertionError("The control did not reach charge " + counter);
    }

    private static void unexpectedWork(String message) {
        fail(message);
    }

    private static boolean hasLaterEffect(Node scope) {
        return scope.getProperties() != null && scope.getProperties().containsKey("later");
    }

    private static Node treeWithEarlierDeliveries() {
        Node tree = tree(true);
        tree.getAsNode("/child/contracts/events").getProperties().remove("eventType");
        tree.getAsNode("/child/grandchild/contracts/events").getProperties().remove("eventType");
        return tree;
    }

    private static Node handler(String channel, String action) {
        return new Node().type(new Node().blueId(ProcessorTestTypeBlueIds.SET_PROPERTY))
                .properties("channel", new Node().value(channel))
                .properties("propertyKey", new Node().value(action))
                .properties("propertyValue", new Node().value(1));
    }

    private static void assertRolledBack(Node input, ProcessingDebugResult debug) {
        assertFalse(debug.processResult().commits());
        assertEquals(input.toString(), debug.processResult().document().toString());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(input),
                DirectBlueIdCalculator.calculateBlueId(debug.processResult().document()));
        assertTrue(debug.processResult().events().isEmpty());
        assertNotNull(debug.platformCommitCompanion());
        assertFalse(debug.platformCommitCompanion().commitsRootAndOutbox());
        assertEquals(1L, debug.platformCommitCompanion().resultingRootRevision());
        assertTrue(debug.platformCommitCompanion().subscriptionDelta().isEmpty());
    }

    private static Node event(String id) {
        return new TestEvent().eventId(id).toNode();
    }

    private static Blue facadeProcessor() {
        Blue blue = processor();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
        return blue;
    }

    private static Node tree(boolean terminateRoot) {
        Node root = authored(terminateRoot);
        Node child = authored(!terminateRoot);
        Node grandchild = authored(false);
        grandchild.getAsNode("/contracts/events").properties("eventType", new Node().value("other-topic"));
        if (terminateRoot) {
            child.getAsNode("/contracts/events").properties("eventType", new Node().value("other-topic"));
        }
        root.properties("child", child);
        child.properties("grandchild", grandchild);
        embed(root, "/child");
        embed(child, "/grandchild");
        for (Node scope : Arrays.asList(root, child, grandchild)) {
            scope.getContracts().properties("later", new Node()
                    .type(new Node().blueId(ProcessorTestTypeBlueIds.SET_PROPERTY))
                    .properties("channel", new Node().value("events"))
                    .properties("order", new Node().value(10))
                    .properties("propertyKey", new Node().value("/later"))
                    .properties("propertyValue", new Node().value(1)));
        }
        return root;
    }

    private static void embed(Node scope, String path) {
        scope.getContracts().properties("embedded", new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", new Node().items(new Node().value(path))));
    }

    private static Blue processor() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new TerminateScopeContractProcessor());
        return blue;
    }

    private static Node initialized(Blue blue, boolean terminate) {
        return initialize(blue, authored(terminate));
    }

    private static Node authored(boolean terminate) {
        Node contracts = new Node().properties("events", new Node().type(
                new Node().blueId(ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL)));
        if (terminate) {
            contracts.properties("terminate", new Node().type(new Node().blueId(
                            ProcessorTestTypeBlueIds.TERMINATE_SCOPE))
                    .properties("channel", new Node().value("events"))
                    .properties("mode", new Node().value("graceful"))
                    .properties("reason", new Node().value("complete")));
        }
        return new Node().name("T08 termination").contracts(contracts);
    }

    private static Node initialize(Blue blue, Node authored) {
        DocumentProcessingResult result = blue.initializeDocument(authored);
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                String.valueOf(result.diagnostic()));
        return result.document();
    }

    private static VerifiedExecutionEvidence evidence(Node document, Node event) {
        Node channel = document.getContracts().getProperties().get("events");
        String contribution = DirectBlueIdCalculator.calculateBlueId(channel);
        String domain = CheckpointDomain.derive(ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL,
                Collections.singletonList(contribution), null);
        String eventId = DirectBlueIdCalculator.calculateBlueId(event);
        return VerifiedExecutionEvidence.builder(
                        DirectBlueIdCalculator.calculateBlueId(document), eventId)
                .revisions(1L, 1L)
                .runtimeRegistryIdentity(RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY)
                .eventOrderKey(ExternalOrderKey.of(Collections.singletonList(eventId)))
                .delivery(ExternalDeliverySnapshot.builder("/", "events")
                        .order(0).sourceContribution(contribution)
                        .effectiveTypeBlueId(ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL)
                        .subscriptionKey(ProcessorTestTypeBlueIds.TEST_EVENT)
                        .checkpointDomainBlueId(domain).checkpointSubjectBlueId(eventId).build())
                .activeSubscriptionInterval(new SubscriptionDelta.Entry("/", "events",
                        ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL,
                        Collections.singletonList(contribution), 0,
                        Collections.singletonList(ProcessorTestTypeBlueIds.TEST_EVENT),
                        domain, 1L, null, null))
                .build();
    }
}
