package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.EmitEventsContractProcessor;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetPropertyOnEvent;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEventChannelTest {

    @Test
    void testEventChannelMatchesOnlyTestEvents() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        String documentYaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  testEventsChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node document = blue.yamlToNode(documentYaml);
        DocumentProcessingResult initResult = blue.initializeDocument(document);
        Node initialized = initResult.document();

        assertNull(initialized.getProperties() != null ? initialized.getProperties().get("x") : null);

        Node randomEvent = blue.yamlToNode(
                "type:\n  blueId: " + RuntimeBlueIds.FIXTURE_EVENT + "\n");
        DocumentProcessingResult randomResult = blue.processDocument(initialized, randomEvent);
        Node afterRandom = randomResult.document();
        assertNull(afterRandom.getProperties() != null ? afterRandom.getProperties().get("x") : null);

        Node testEvent = blue.objectToNode(new TestEvent().x(5).y(10));
        DocumentProcessingResult testResult = blue.processDocument(afterRandom, testEvent);
        Node afterTest = testResult.document();

        assertEquals(ProcessorStatus.SUCCESS, testResult.status(), testResult.failureReason());
        Node xNode = afterTest.getProperties().get("x");
        assertEquals(new BigInteger("1"), xNode.getValue());
    }

    @Test
    void triggeredAndEmbeddedChannelsPropagateChildEvents() {
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
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    triggered:\n" +
                "      type:\n" +
                "        blueId: DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf\n" +
                "    emitOnInit:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "      type:\n" +
                "        blueId: 8L41csGU9GJkoza1159y2pYbJ6yGAi4huvgmu44Ah2d5\n" +
                "      events:\n" +
                "        - type:\n" +
                "            blueId: Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf\n" +
                "          kind: first\n" +
                "    setLocalFirst:\n" +
                "      channel: triggered\n" +
                "      type:\n" +
                "        blueId: H1qKGon7JWgUU9P8oUiHjxoR5hWbkAzVWWNukXf4cHz\n" +
                "      expectedKind: first\n" +
                "      propertyKey: /localFirst\n" +
                "      propertyValue: 1\n" +
                "    emitSecond:\n" +
                "      channel: triggered\n" +
                "      order: 1\n" +
                "      type:\n" +
                "        blueId: 8L41csGU9GJkoza1159y2pYbJ6yGAi4huvgmu44Ah2d5\n" +
                "      expectedKind: first\n" +
                "      events:\n" +
                "        - type:\n" +
                "            blueId: Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf\n" +
                "          kind: second\n" +
                "    setLocalSecond:\n" +
                "      channel: triggered\n" +
                "      order: 2\n" +
                "      type:\n" +
                "        blueId: H1qKGon7JWgUU9P8oUiHjxoR5hWbkAzVWWNukXf4cHz\n" +
                "      expectedKind: second\n" +
                "      propertyKey: /localSecond\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /a\n" +
                "  embeddedEvents:\n" +
                "    type:\n" +
                "      blueId: 7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN\n" +
                "    childPath: /a\n" +
                "  setRootFromChild:\n" +
                "    channel: embeddedEvents\n" +
                "    type:\n" +
                "      blueId: H1qKGon7JWgUU9P8oUiHjxoR5hWbkAzVWWNukXf4cHz\n" +
                "    expectedKind: second\n" +
                "    propertyKey: /fromChild\n" +
                "    propertyValue: 1\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(document);
        Node processed = result.document();

        Node child = processed.getProperties().get("a");
        Node localFirst = child.getProperties().get("localFirst");
        Node localSecond = child.getProperties().get("localSecond");
        assertEquals(new BigInteger("1"), localFirst.getValue());
        assertEquals(new BigInteger("1"), localSecond.getValue());

        Node rootFlag = processed.getProperties().get("fromChild");
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
    void checkpointSkipsStaleEvents() {
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
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  incrementX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "    propertyKey: /x\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessingResult init = blue.initializeDocument(document);
        Node initialized = init.document();
        assertNull(checkpointValue(initialized));

        Node event1 = blue.objectToNode(new TestEvent().eventId("evt-1"));
        Node afterFirst = blue.processDocument(initialized, event1).document();
        assertEquals(new BigInteger("1"), afterFirst.getProperties().get("x").getValue());
        assertEquals(BlueIdCalculator.calculateBlueId(event1),
                checkpointValue(afterFirst));

        Node stale = blue.objectToNode(new TestEvent().eventId("evt-1"));
        Node afterStale = blue.processDocument(afterFirst, stale).document();
        assertEquals(new BigInteger("1"), afterStale.getProperties().get("x").getValue());
        assertEquals(BlueIdCalculator.calculateBlueId(stale),
                checkpointValue(afterStale));

        Node fresh = blue.objectToNode(new TestEvent().eventId("evt-2"));
        Node afterFresh = blue.processDocument(afterStale, fresh).document();
        assertEquals(new BigInteger("2"), afterFresh.getProperties().get("x").getValue());
        assertEquals(BlueIdCalculator.calculateBlueId(fresh),
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
    void checkpointStoresExactSubjectReferenceAndComparesPayload() {
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
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  incrementX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "    propertyKey: /x\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document();

        Node firstEvent = blue.yamlToNode("type:\n  blueId: Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf\nkind: alpha\n");
        Node afterFirst = blue.processDocument(initialized, firstEvent).document();
        assertEquals(new BigInteger("1"), afterFirst.getProperties().get("x").getValue());
        Node storedSubject = checkpointStoredSubject(afterFirst);
        assertNotNull(storedSubject);
        assertEquals(BlueIdCalculator.calculateBlueId(firstEvent),
                storedSubject.getBlueId());

        Node identicalEvent = blue.yamlToNode("type:\n  blueId: Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf\nkind: alpha\n");
        Node afterSecond = blue.processDocument(afterFirst, identicalEvent).document();
        assertEquals(new BigInteger("1"), afterSecond.getProperties().get("x").getValue(),
                "Identical payload should be gated by checkpoint");

        Node changedEvent = blue.yamlToNode("type:\n  blueId: Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf\nkind: beta\n");
        Node afterThird = blue.processDocument(afterSecond, changedEvent).document();
        assertEquals(new BigInteger("2"), afterThird.getProperties().get("x").getValue(),
                "Changed payload should be processed");
        Node updatedSubject = checkpointStoredSubject(afterThird);
        assertNotNull(updatedSubject);
        assertEquals(BlueIdCalculator.calculateBlueId(changedEvent),
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
