package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.EmitEventsContractProcessor;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.TestEvent;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestEventChannelTest {

    @Test
    void testEventChannelMatchesOnlyTestEvents() {
        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new TestEventChannelProcessor());

        String documentYaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  testEventsChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  setX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node document = blue.yamlToNode(documentYaml);
        DocumentProcessingResult initResult = blue.initializeDocument(document);
        Node initialized = initResult.document();

        assertNull(initialized.getProperties().get("x"));

        Node randomEvent = blue.yamlToNode("type:\n  blueId: RandomEvent\n");
        DocumentProcessingResult randomResult = blue.processDocument(initialized, randomEvent);
        Node afterRandom = randomResult.document();
        assertNull(afterRandom.getProperties().get("x"));

        Node testEvent = blue.objectToNode(new TestEvent().x(5).y(10));
        DocumentProcessingResult testResult = blue.processDocument(afterRandom, testEvent);
        Node afterTest = testResult.document();

        Node xNode = afterTest.getProperties().get("x");
        assertEquals(new BigInteger("1"), xNode.getValue());
    }

    @Test
    void triggeredAndEmbeddedChannelsPropagateChildEvents() {
        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new EmitEventsContractProcessor());
        blue.registerContractProcessor(new SetPropertyOnEventContractProcessor());

        String yaml = "name: Cascade Doc\n" +
                "a:\n" +
                "  name: Child Doc\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    triggered:\n" +
                "      type:\n" +
                "        blueId: TriggeredEventChannel\n" +
                "    emitOnInit:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: DocumentProcessingInitiated\n" +
                "      type:\n" +
                "        blueId: EmitEvents\n" +
                "      events:\n" +
                "        - type:\n" +
                "            blueId: TestEvent\n" +
                "          kind: first\n" +
                "    setLocalFirst:\n" +
                "      channel: triggered\n" +
                "      type:\n" +
                "        blueId: SetPropertyOnEvent\n" +
                "      expectedKind: first\n" +
                "      propertyKey: /localFirst\n" +
                "      propertyValue: 1\n" +
                "    emitSecond:\n" +
                "      channel: triggered\n" +
                "      order: 1\n" +
                "      type:\n" +
                "        blueId: EmitEvents\n" +
                "      expectedKind: first\n" +
                "      events:\n" +
                "        - type:\n" +
                "            blueId: TestEvent\n" +
                "          kind: second\n" +
                "    setLocalSecond:\n" +
                "      channel: triggered\n" +
                "      order: 2\n" +
                "      type:\n" +
                "        blueId: SetPropertyOnEvent\n" +
                "      expectedKind: second\n" +
                "      propertyKey: /localSecond\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /a\n" +
                "  embeddedEvents:\n" +
                "    type:\n" +
                "      blueId: EmbeddedNodeChannel\n" +
                "    childPath: /a\n" +
                "  setRootFromChild:\n" +
                "    channel: embeddedEvents\n" +
                "    type:\n" +
                "      blueId: SetPropertyOnEvent\n" +
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
    }

    @Test
    void checkpointSkipsStaleEvents() {
        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());
        blue.registerContractProcessor(new TestEventChannelProcessor());

        String yaml = "name: Checkpoint Doc\n" +
                "contracts:\n" +
                "  testEventsChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  incrementX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: IncrementProperty\n" +
                "    propertyKey: /x\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessingResult init = blue.initializeDocument(document);
        Node initialized = init.document();
        assertNull(checkpointValue(initialized));

        Node event1 = blue.objectToNode(new TestEvent().eventId("evt-1"));
        Node afterFirst = blue.processDocument(initialized, event1).document();
        assertEquals(new BigInteger("1"), afterFirst.getProperties().get("x").getValue());
        assertEquals("evt-1", checkpointValue(afterFirst));

        Node stale = blue.objectToNode(new TestEvent().eventId("evt-1"));
        Node afterStale = blue.processDocument(afterFirst, stale).document();
        assertEquals(new BigInteger("1"), afterStale.getProperties().get("x").getValue());
        assertEquals("evt-1", checkpointValue(afterStale));

        Node fresh = blue.objectToNode(new TestEvent().eventId("evt-2"));
        Node afterFresh = blue.processDocument(afterStale, fresh).document();
        assertEquals(new BigInteger("2"), afterFresh.getProperties().get("x").getValue());
        assertEquals("evt-2", checkpointValue(afterFresh));
    }

    private String checkpointValue(Node document) {
        Node contracts = document.getProperties().get("contracts");
        Node checkpoint = contracts.getProperties().get("checkpoint");
        if (checkpoint == null) {
            return null;
        }
        Node lastSignatures = checkpoint.getProperties().get("lastSignatures");
        if (lastSignatures != null && lastSignatures.getProperties() != null) {
            Node sigNode = lastSignatures.getProperties().get("testEventsChannel");
            if (sigNode != null && sigNode.getValue() != null) {
                return sigNode.getValue().toString();
            }
        }
        Node lastEvents = checkpoint.getProperties().get("lastEvents");
        if (lastEvents == null || lastEvents.getProperties() == null) {
            return null;
        }
        Node entry = lastEvents.getProperties().get("testEventsChannel");
        if (entry == null || entry.getProperties() == null) {
            return null;
        }
        Node eventIdNode = entry.getProperties().get("eventId");
        Object value = eventIdNode != null ? eventIdNode.getValue() : null;
        return value != null ? value.toString() : null;
    }

    @Test
    void checkpointStoresFullEventAndComparesPayload() {
        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());
        blue.registerContractProcessor(new TestEventChannelProcessor());

        String yaml = "name: Payload Checkpoint Doc\n" +
                "contracts:\n" +
                "  testEventsChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  incrementX:\n" +
                "    channel: testEventsChannel\n" +
                "    type:\n" +
                "      blueId: IncrementProperty\n" +
                "    propertyKey: /x\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document();

        Node firstEvent = blue.yamlToNode("type:\n  blueId: TestEvent\nkind: alpha\n");
        Node afterFirst = blue.processDocument(initialized, firstEvent).document();
        assertEquals(new BigInteger("1"), afterFirst.getProperties().get("x").getValue());
        Node storedEvent = checkpointStoredEvent(afterFirst);
        assertNotNull(storedEvent);
        assertEquals("alpha", storedEvent.getProperties().get("kind").getValue());

        Node identicalEvent = blue.yamlToNode("type:\n  blueId: TestEvent\nkind: alpha\n");
        Node afterSecond = blue.processDocument(afterFirst, identicalEvent).document();
        assertEquals(new BigInteger("1"), afterSecond.getProperties().get("x").getValue(),
                "Identical payload should be gated by checkpoint");

        Node changedEvent = blue.yamlToNode("type:\n  blueId: TestEvent\nkind: beta\n");
        Node afterThird = blue.processDocument(afterSecond, changedEvent).document();
        assertEquals(new BigInteger("2"), afterThird.getProperties().get("x").getValue(),
                "Changed payload should be processed");
        Node updatedEvent = checkpointStoredEvent(afterThird);
        assertNotNull(updatedEvent);
        assertEquals("beta", updatedEvent.getProperties().get("kind").getValue());
    }

    private Node checkpointStoredEvent(Node document) {
        Node contracts = document.getProperties().get("contracts");
        Node checkpoint = contracts.getProperties().get("checkpoint");
        if (checkpoint == null) {
            return null;
        }
        Node lastEvents = checkpoint.getProperties().get("lastEvents");
        if (lastEvents == null || lastEvents.getProperties() == null) {
            return null;
        }
        return lastEvents.getProperties().get("testEventsChannel");
    }
}
