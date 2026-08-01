package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InternalEventOccurrenceFifoTest {

    private static final String TEST_EVENT_CHANNEL =
            ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;
    private static final Node PROBE_HANDLER_TYPE =
            new Node().name("Internal Event FIFO Probe Handler");
    private static final String PROBE_HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(PROBE_HANDLER_TYPE);

    private static final Node EVENT_A = applicationEvent("A");
    private static final Node EVENT_B = applicationEvent("B");
    private static final Node EVENT_C = applicationEvent("C");
    private static final Node EVENT_D = applicationEvent("D");

    private static final String EVENT_A_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(EVENT_A);
    private static final String EVENT_B_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(EVENT_B);
    private static final String EVENT_C_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(EVENT_C);
    private static final String EVENT_D_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(EVENT_D);

    @Test
    void shouldPreserveGlobalFifoWhenAppendingDuringDeliveryAndContinuePastTerminatingAncestor() {
        // given
        ProbeProcessor probe = new ProbeProcessor();
        try (Blue blue = configuredBlue(probe)) {
            Node initialized = blue.initializeDocument(
                    threeLevelDocument()).document();
            probe.clear();

            // when
            DocumentProcessingResult result = blue.processDocument(
                    initialized,
                    new TestEvent().eventId("drive-fifo").toNode());

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    result.status(),
                    diagnosticMessage(result));
            assertEquals(
                    Arrays.asList(
                            "leaf:T:A",
                            "mid:E:A",
                            "root:E:A",
                            "leaf:T:B",
                            "root:E:B",
                            "leaf:T:C",
                            "root:E:C"),
                    probe.order);
            assertTrue(
                    probe.middleTerminationMarkerAbsentAtRootB,
                    "the middle termination marker must wait for FIFO quiescence");
            assertTrue(
                    result.events().isEmpty(),
                    "descendant application events remain internal");

            Node terminationMarker = result.document()
                    .getAsNode("/mid/contracts/terminated");
            assertNotNull(terminationMarker);
            assertEquals(
                    RuntimeBlueIds.PROCESSING_TERMINATED_MARKER,
                    terminationMarker.getType().getBlueId());
            assertEquals(
                    "middle-stop",
                    terminationMarker.getAsText("/cause"));

            assertEquals(4, probe.embeddedDeliveries.size());
            assertEmbeddedDelivery(
                    probe.embeddedDeliveries.get(0),
                    "/mid",
                    "/leaf",
                    EVENT_A_BLUE_ID);
            assertEmbeddedDelivery(
                    probe.embeddedDeliveries.get(1),
                    "/",
                    "/mid/leaf",
                    EVENT_A_BLUE_ID);
            assertEmbeddedDelivery(
                    probe.embeddedDeliveries.get(2),
                    "/",
                    "/mid/leaf",
                    EVENT_B_BLUE_ID);
            assertEmbeddedDelivery(
                    probe.embeddedDeliveries.get(3),
                    "/",
                    "/mid/leaf",
                    EVENT_C_BLUE_ID);

            long middleBOrC = probe.embeddedDeliveries.stream()
                    .filter(delivery -> "/mid".equals(
                            delivery.receivingScope))
                    .filter(delivery ->
                            EVENT_B_BLUE_ID.equals(
                                    delivery.eventBlueId)
                                    || EVENT_C_BLUE_ID.equals(
                                    delivery.eventBlueId))
                    .count();
            assertEquals(
                    0L,
                    middleBOrC,
                    "later occurrences skip a terminating ancestor");
        }
    }

    @Test
    void shouldDeliverAlreadyEmittedOccurrenceToFrozenActiveAncestorsAfterRootTerminates() {
        // given
        ProbeProcessor probe = new ProbeProcessor();
        try (Blue blue = configuredBlue(probe)) {
            ProcessorInvocationState execution =
                    new ProcessorInvocationState(
                            blue.getDocumentProcessor(),
                            frozenRootTerminationDocument());
            execution.preflightScope("/");
            execution.preflightScope("/top");
            execution.preflightScope("/top/mid");
            execution.preflightScope("/top/mid/leaf");
            execution.runtime().attachScopeOccurrence(
                    "/", "/top");
            execution.runtime().attachScopeOccurrence(
                    "/top", "/top/mid");
            execution.runtime().attachScopeOccurrence(
                    "/top/mid", "/top/mid/leaf");
            ScopeRuntimeContext source = execution.runtime()
                    .existingScope("/top/mid/leaf");
            execution.runtime().enqueueEventOccurrence(
                    new EventOccurrence(
                            EVENT_A.clone(),
                            EVENT_A_BLUE_ID,
                            source,
                            source.freezeAncestorChain(),
                            EventOccurrence.SourceMode.TRIGGERED,
                            "alreadyEmitted"));
            execution.runtime().existingScope("/")
                    .finalizeTermination("root-finished");

            // when
            execution.drainInternalEvents();

            // then
            assertEquals(
                    Arrays.asList(
                            "nested-mid:E:A",
                            "top:E:A"),
                    probe.order);
            assertEquals(2, probe.embeddedDeliveries.size());
            assertEmbeddedDelivery(
                    probe.embeddedDeliveries.get(0),
                    "/top/mid",
                    "/leaf",
                    EVENT_A_BLUE_ID);
            assertEmbeddedDelivery(
                    probe.embeddedDeliveries.get(1),
                    "/top",
                    "/mid/leaf",
                    EVENT_A_BLUE_ID);
        }
    }

    @Test
    void shouldExposeRootApplicationEventsPubliclyInOrderWithMultiplicity() {
        // given
        ProbeProcessor probe = new ProbeProcessor();

        // when
        DocumentProcessingResult result;
        try (Blue blue = configuredBlue(probe)) {
            result =
                    blue.initializeDocument(rootMultiplicityDocument());
        }
        List<Node> publicEvents = result.events();

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
        assertEquals(
                Arrays.asList("root:T:D", "root:T:D"),
                probe.order);
        assertEquals(2, publicEvents.size());
        assertEquals(
                EVENT_D_BLUE_ID,
                DirectBlueIdCalculator.calculateBlueId(
                        publicEvents.get(0)));
        assertEquals(
                EVENT_D_BLUE_ID,
                DirectBlueIdCalculator.calculateBlueId(
                        publicEvents.get(1)));
        assertNotSame(
                publicEvents.get(0),
                publicEvents.get(1),
                "equal Root emissions retain multiplicity as distinct snapshots");
    }

    private static Blue configuredBlue(ProbeProcessor probe) {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerExternalContractType(
                PROBE_HANDLER_BLUE_ID,
                PROBE_HANDLER_TYPE,
                probe);
        DocumentProcessorExactFeederSupport.install(blue);
        return blue;
    }

    private static Node threeLevelDocument() {
        Node leaf = new Node()
                .name("FIFO Leaf")
                .contracts(new Node()
                        .properties(
                                "incoming",
                                typed(TEST_EVENT_CHANNEL))
                        .properties(
                                "triggered",
                                typed(RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL))
                        .properties(
                                "leafEmit",
                                handler("incoming"))
                        .properties(
                                "leafObserve",
                                handler("triggered")));

        Node middle = new Node()
                .name("FIFO Middle")
                .properties("leaf", leaf)
                .contracts(new Node()
                        .properties(
                                "embedded",
                                processEmbedded("/leaf"))
                        .properties(
                                "descendantEvents",
                                embeddedChannel("/leaf"))
                        .properties(
                                "middleObserve",
                                handler("descendantEvents")));

        return new Node()
                .name("FIFO Root")
                .properties("mid", middle)
                .contracts(new Node()
                        .properties(
                                "embedded",
                                processEmbedded("/mid"))
                        .properties(
                                "descendantEvents",
                                embeddedChannel("/mid/leaf"))
                        .properties(
                                "rootObserve",
                                handler("descendantEvents")));
    }

    private static Node rootMultiplicityDocument() {
        return new Node()
                .name("Root Event Multiplicity")
                .contracts(new Node()
                        .properties(
                                "lifecycle",
                                typed(RuntimeBlueIds
                                        .LIFECYCLE_EVENT_CHANNEL))
                        .properties(
                                "triggered",
                                typed(RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL))
                        .properties(
                                "emitDuplicates",
                                handler("lifecycle"))
                        .properties(
                                "observeDuplicates",
                                handler("triggered")));
    }

    private static Node frozenRootTerminationDocument() {
        Node leaf = new Node().name("Frozen Leaf");
        Node middle = new Node()
                .name("Frozen Middle")
                .properties("leaf", leaf)
                .contracts(new Node()
                        .properties(
                                "descendantEvents",
                                embeddedChannel("/leaf"))
                        .properties(
                                "middleObserve",
                                handler("descendantEvents")));
        Node top = new Node()
                .name("Frozen Top")
                .properties("mid", middle)
                .contracts(new Node()
                        .properties(
                                "descendantEvents",
                                embeddedChannel("/mid/leaf"))
                        .properties(
                                "middleObserve",
                                handler("descendantEvents")));
        return new Node()
                .name("Frozen Root")
                .properties("top", top);
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static Node handler(String channel) {
        return typed(PROBE_HANDLER_BLUE_ID)
                .properties(
                        "channel",
                        new Node().value(channel));
    }

    private static Node processEmbedded(String path) {
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                .properties(
                        "paths",
                        new Node().items(
                                new Node().value(path)));
    }

    private static Node embeddedChannel(String sourcePath) {
        return typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                .properties(
                        "sourcePath",
                        new Node().value(sourcePath));
    }

    private static Node applicationEvent(String id) {
        return new Node().properties(
                "id", new Node().value(id));
    }

    private static void assertEmbeddedDelivery(
            EmbeddedDelivery delivery,
            String receivingScope,
            String sourcePath,
            String eventBlueId) {
        assertEquals(receivingScope, delivery.receivingScope);
        assertEquals(sourcePath, delivery.sourcePath);
        assertEquals(eventBlueId, delivery.eventBlueId);
        assertEquals(
                RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY,
                delivery.wrapper.getType().getBlueId());
        assertEquals(
                new LinkedHashSet<String>(
                        Arrays.asList("sourcePath", "event")),
                delivery.wrapper.getProperties().keySet());
        assertFalse(
                delivery.wrapper.getProperties()
                        .containsKey("childPath"));
        Node eventReference = delivery.wrapper
                .getProperties().get("event");
        assertNotNull(eventReference);
        assertTrue(eventReference.isReferenceOnly());
        assertEquals(eventBlueId, eventReference.getBlueId());
    }

    public static final class ProbeHandler
            extends HandlerContract {
    }

    private static final class ProbeProcessor
            implements HandlerProcessor<ProbeHandler> {

        private final Map<String, String> labelsByBlueId =
                new LinkedHashMap<>();
        private final List<String> order = new ArrayList<>();
        private final List<EmbeddedDelivery>
                embeddedDeliveries = new ArrayList<>();
        private boolean middleTerminationMarkerAbsentAtRootB;

        private ProbeProcessor() {
            labelsByBlueId.put(EVENT_A_BLUE_ID, "A");
            labelsByBlueId.put(EVENT_B_BLUE_ID, "B");
            labelsByBlueId.put(EVENT_C_BLUE_ID, "C");
            labelsByBlueId.put(EVENT_D_BLUE_ID, "D");
        }

        @Override
        public Class<ProbeHandler> contractType() {
            return ProbeHandler.class;
        }

        @Override
        public void execute(
                ProbeHandler contract,
                ProcessorExecutionContext context) {
            String key = context.contractKey();
            if ("leafEmit".equals(key)) {
                context.emitEvent(EVENT_A.clone());
                context.emitEvent(EVENT_B.clone());
                return;
            }
            if ("leafObserve".equals(key)) {
                String label = context.event()
                        .getAsText("/id");
                order.add("leaf:T:" + label);
                if ("A".equals(label)) {
                    context.emitEvent(EVENT_C.clone());
                }
                return;
            }
            if ("middleObserve".equals(key)
                    || "rootObserve".equals(key)) {
                observeEmbedded(context);
                return;
            }
            if ("emitDuplicates".equals(key)) {
                if (context.event().getType() != null
                        && RuntimeBlueIds
                        .DOCUMENT_PROCESSING_INITIATED
                        .equals(context.event().getType()
                                .getBlueId())) {
                    context.emitEvent(EVENT_D.clone());
                    context.emitEvent(EVENT_D.clone());
                }
                return;
            }
            if ("observeDuplicates".equals(key)) {
                order.add("root:T:"
                        + context.event().getAsText("/id"));
            }
        }

        private void observeEmbedded(
                ProcessorExecutionContext context) {
            Node wrapper = context.event();
            Node eventReference = wrapper.getProperties() != null
                    ? wrapper.getProperties().get("event")
                    : null;
            String eventBlueId = eventReference != null
                    ? eventReference.getBlueId()
                    : null;
            String label = labelsByBlueId.get(eventBlueId);
            if (label == null) {
                return;
            }
            String sourcePath =
                    wrapper.getAsText("/sourcePath");
            embeddedDeliveries.add(new EmbeddedDelivery(
                    context.scopePath(),
                    sourcePath,
                    eventBlueId,
                    wrapper.clone()));
            if ("/mid".equals(context.scopePath())) {
                order.add("mid:E:" + label);
                if ("A".equals(label)) {
                    context.terminate(
                            "middle-stop",
                            "after A");
                }
                return;
            }
            if ("/top/mid".equals(context.scopePath())) {
                order.add("nested-mid:E:" + label);
                return;
            }
            if ("/top".equals(context.scopePath())) {
                order.add("top:E:" + label);
                return;
            }
            order.add("root:E:" + label);
            if ("B".equals(label)) {
                middleTerminationMarkerAbsentAtRootB =
                        !context.documentContains(
                                "/mid/contracts/terminated");
            }
        }

        private void clear() {
            order.clear();
            embeddedDeliveries.clear();
            middleTerminationMarkerAbsentAtRootB = false;
        }
    }

    private static final class EmbeddedDelivery {
        private final String receivingScope;
        private final String sourcePath;
        private final String eventBlueId;
        private final Node wrapper;

        private EmbeddedDelivery(
                String receivingScope,
                String sourcePath,
                String eventBlueId,
                Node wrapper) {
            this.receivingScope = receivingScope;
            this.sourcePath = sourcePath;
            this.eventBlueId = eventBlueId;
            this.wrapper = wrapper;
        }
    }
}
