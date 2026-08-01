package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.EmitEventsContractProcessor;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.SetPropertyOnEvent;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEventChannelTest {

    @Test
    void shouldMatchOnlyTestEventsWithTestEventChannel() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        String documentYaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  testEventsChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";
        Node document = blue.yamlToNode(documentYaml);
        Node randomEvent = blue.yamlToNode(
                "type:\n  blueId: " + RuntimeBlueIds.FIXTURE_EVENT + "\n");
        Node testEvent = blue.objectToNode(
                new TestEvent().x(5).y(10));

        // when
        DocumentProcessingResult initResult = blue.initializeDocument(document);
        Node initialized = initResult.document();
        DocumentProcessingResult randomResult = blue.processDocument(initialized, randomEvent);
        Node afterRandom = randomResult.document();
        DocumentProcessingResult testResult = blue.processDocument(afterRandom, testEvent);
        Node afterTest = testResult.document();
        Node xNode = afterTest.getProperties().get("x");

        // then
        assertNull(initialized.getProperties() != null
                ? initialized.getProperties().get("x")
                : null);
        assertNull(afterRandom.getProperties() != null
                ? afterRandom.getProperties().get("x")
                : null);
        assertEquals(ProcessorStatus.SUCCESS, testResult.status(), diagnosticMessage(testResult));
        assertEquals(new BigInteger("1"), xNode.getValue());
    }

    @Test
    void shouldVerifyTriggeredAndEmbeddedChannelsPropagateChildEvents() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new EmitEventsContractProcessor());
        EmbeddedAwareSetPropertyOnEventProcessor eventProcessor =
                new EmbeddedAwareSetPropertyOnEventProcessor();
        blue.registerContractProcessor(eventProcessor);

        String yaml = "name: Cascade Doc\n" +
                "a:\n" +
                "  name: Child Doc\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "    triggered:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL + "\n" +
                "    emitOnInit:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.EMIT_EVENTS + "\n" +
                "      events:\n" +
                "        - type:\n" +
                "            blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT + "\n" +
                "          kind: first\n" +
                "    setLocalFirst:\n" +
                "      channel: triggered\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY_ON_EVENT + "\n" +
                "      expectedKind: first\n" +
                "      propertyKey: /localFirst\n" +
                "      propertyValue: 1\n" +
                "    emitSecond:\n" +
                "      channel: triggered\n" +
                "      order: 1\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.EMIT_EVENTS + "\n" +
                "      expectedKind: first\n" +
                "      events:\n" +
                "        - type:\n" +
                "            blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT + "\n" +
                "          kind: second\n" +
                "    setLocalSecond:\n" +
                "      channel: triggered\n" +
                "      order: 2\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY_ON_EVENT + "\n" +
                "      expectedKind: second\n" +
                "      propertyKey: /localSecond\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /a\n" +
                "  embeddedEvents:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.EMBEDDED_NODE_CHANNEL + "\n" +
                "    sourcePath: /a\n" +
                "  setRootFromChild:\n" +
                "    channel: embeddedEvents\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY_ON_EVENT + "\n" +
                "    expectedKind: second\n" +
                "    propertyKey: /fromChild\n" +
                "    propertyValue: 1\n";

        // when
        Node document = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(document);
        Node processed = result.document();
        Node child = processed.getProperties().get("a");
        Node localFirst = child.getProperties().get("localFirst");
        Node localSecond = child.getProperties().get("localSecond");
        Node rootFlag = processed.getProperties().get("fromChild");

        // then
        assertEquals(new BigInteger("1"), localFirst.getValue());
        assertEquals(new BigInteger("1"), localSecond.getValue());

        assertEquals(new BigInteger("1"), rootFlag.getValue());
        assertEmbeddedEventDelivery(
                eventProcessor.capturedSecondDelivery,
                "/a",
                CheckpointIdentityCalculator.identity(
                        new TestEvent().kind("second").toNode()));
        assertTrue(result.events().isEmpty(),
                "processor lifecycle and child emissions remain internal");
    }

    @Test
    void shouldVerifyCheckpointSkipsStaleEvents() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        String yaml = "name: Checkpoint Doc\n" +
                "contracts:\n" +
                "  testEventsChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  incrementX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "    propertyKey: /x\n";
        Node document = blue.yamlToNode(yaml);
        Node event1 = blue.objectToNode(
                new TestEvent().eventId("evt-1"));
        Node stale = blue.objectToNode(
                new TestEvent().eventId("evt-1"));
        Node fresh = blue.objectToNode(
                new TestEvent().eventId("evt-2"));

        // when
        DocumentProcessingResult init = blue.initializeDocument(document);
        Node initialized = init.document();
        Node afterFirst = blue.processDocument(initialized, event1).document();
        Node afterStale = blue.processDocument(afterFirst, stale).document();
        Node afterFresh = blue.processDocument(afterStale, fresh).document();

        // then
        assertNull(checkpointValue(initialized));
        assertEquals(new BigInteger("1"), afterFirst.getProperties().get("x").getValue());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(event1),
                checkpointValue(afterFirst));
        assertEquals(new BigInteger("1"), afterStale.getProperties().get("x").getValue());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(stale),
                checkpointValue(afterStale));
        assertEquals(new BigInteger("2"), afterFresh.getProperties().get("x").getValue());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(fresh),
                checkpointValue(afterFresh));
    }

    private String checkpointValue(Node document) {
        Node contracts = document.getContracts();
        Node checkpoint = contracts.getProperties().get("checkpoint");
        if (checkpoint == null) {
            return null;
        }
        Node entries = checkpoint.getProperties().get("entries");
        if (entries == null || entries.getProperties() == null) {
            return null;
        }
        Node entry = entries.getProperties().get("testEventsChannel");
        if (entry == null || entry.getProperties() == null) {
            return null;
        }
        Node subject = entry.getProperties().get("subject");
        return subject != null ? subject.getBlueId() : null;
    }

    @Test
    void shouldVerifyCheckpointStoresExactSubjectReferenceAndComparesPayload() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        String yaml = "name: Payload Checkpoint Doc\n" +
                "contracts:\n" +
                "  testEventsChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  incrementX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "    propertyKey: /x\n";
        Node firstEvent = blue.yamlToNode(
                "type:\n  blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT + "\nkind: alpha\n");
        Node identicalEvent = blue.yamlToNode(
                "type:\n  blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT + "\nkind: alpha\n");
        Node changedEvent = blue.yamlToNode(
                "type:\n  blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT + "\nkind: beta\n");

        // when
        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document();
        Node afterFirst = blue.processDocument(initialized, firstEvent).document();
        Node storedSubject = checkpointStoredSubject(afterFirst);
        Node afterSecond = blue.processDocument(afterFirst, identicalEvent).document();
        Node afterThird = blue.processDocument(afterSecond, changedEvent).document();
        Node updatedSubject = checkpointStoredSubject(afterThird);

        // then
        assertEquals(new BigInteger("1"), afterFirst.getProperties().get("x").getValue());
        assertNotNull(storedSubject);
        assertEquals(DirectBlueIdCalculator.calculateBlueId(firstEvent),
                storedSubject.getBlueId());
        assertEquals(new BigInteger("1"), afterSecond.getProperties().get("x").getValue(),
                "Identical payload should be gated by checkpoint");
        assertEquals(new BigInteger("2"), afterThird.getProperties().get("x").getValue(),
                "Changed payload should be processed");
        assertNotNull(updatedSubject);
        assertEquals(DirectBlueIdCalculator.calculateBlueId(changedEvent),
                updatedSubject.getBlueId());
    }

    private Node checkpointStoredSubject(Node document) {
        Node contracts = document.getContracts();
        Node checkpoint = contracts.getProperties().get("checkpoint");
        if (checkpoint == null) {
            return null;
        }
        Node entries = checkpoint.getProperties().get("entries");
        if (entries == null || entries.getProperties() == null) {
            return null;
        }
        Node entry = entries.getProperties().get("testEventsChannel");
        return entry != null && entry.getProperties() != null
                ? entry.getProperties().get("subject") : null;
    }

    private static void assertEmbeddedEventDelivery(
            Node delivery,
            String expectedSourcePath,
            String expectedEventBlueId) {
        assertNotNull(delivery);
        assertNotNull(delivery.getType());
        assertEquals(RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY,
                delivery.getType().getBlueId());
        assertNotNull(delivery.getProperties());
        assertEquals(2, delivery.getProperties().size());
        assertEquals(expectedSourcePath,
                delivery.getAsText("/sourcePath"));
        assertFalse(delivery.getProperties()
                .containsKey("childPath"));
        Node eventReference =
                delivery.getProperties().get("event");
        assertNotNull(eventReference);
        assertTrue(eventReference.isReferenceOnly());
        assertEquals(expectedEventBlueId,
                eventReference.getBlueId());
    }

    private static final class
    EmbeddedAwareSetPropertyOnEventProcessor
            implements HandlerProcessor<SetPropertyOnEvent> {

        private Node capturedSecondDelivery;

        @Override
        public Class<SetPropertyOnEvent> contractType() {
            return SetPropertyOnEvent.class;
        }

        @Override
        public void execute(
                SetPropertyOnEvent contract,
                ProcessorExecutionContext context) {
            Node event = context.event();
            if (!matches(contract, event)) {
                return;
            }
            if (event.getType() != null
                    && RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY
                    .equals(event.getType().getBlueId())
                    && "/fromChild".equals(
                    contract.getPropertyKey())) {
                capturedSecondDelivery = event.clone();
            }
            context.applyPatch(JsonPatch.add(
                    context.resolvePointer(
                            contract.getPropertyKey()),
                    new Node().value(
                            contract.getPropertyValue())));
        }

        private boolean matches(
                SetPropertyOnEvent contract,
                Node event) {
            if (event == null) {
                return false;
            }
            if (event.getType() != null
                    && RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY
                    .equals(event.getType().getBlueId())) {
                Node eventReference =
                        event.getProperties() != null
                                ? event.getProperties().get("event")
                                : null;
                if (eventReference == null
                        || !eventReference.isReferenceOnly()) {
                    return false;
                }
                Node expected = new TestEvent()
                        .kind(contract.getExpectedKind())
                        .toNode();
                return CheckpointIdentityCalculator.identity(
                        expected).equals(
                        eventReference.getBlueId());
            }
            if (event.getProperties() == null) {
                return false;
            }
            Node kind =
                    event.getProperties().get("kind");
            return kind != null
                    && kind.getValue() != null
                    && contract.getExpectedKind().equals(
                    String.valueOf(kind.getValue()));
        }
    }
}
