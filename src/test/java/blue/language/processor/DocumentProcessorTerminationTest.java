package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TerminateScopeContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.TestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorTerminationTest {

    private Blue blue;

    @BeforeEach
    void setUp() {
        blue = new Blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new TerminateScopeContractProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
    }

    @Test
    void rootGracefulTerminationStopsFurtherWork() {
        Node document = blue.yamlToNode("name: Root Doc\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  terminate:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: TerminateScope\n" +
                "    mode: graceful\n" +
                "    emitAfter: true\n" +
                "    patchAfter: true\n");

        Node event = buildTestEvent("evt-1");
        Node initialized = blue.initializeDocument(document).document();
        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node processed = result.document();
        Node contracts = processed.getProperties().get("contracts");
        assertNotNull(contracts);
        Node terminated = contracts.getProperties().get("terminated");
        assertNotNull(terminated);
        assertEquals("graceful", terminated.getProperties().get("cause").getValue());
        assertNull(processed.getProperties().get("afterTermination"), "patch after termination must be ignored");

        List<Node> triggeredEvents = result.triggeredEvents();
        assertEquals(1, triggeredEvents.size(), "Only the terminated lifecycle event should be present");
        assertEquals("Document Processing Terminated", stringProperty(triggeredEvents.get(0), "type"));
        assertEquals("graceful", stringProperty(triggeredEvents.get(0), "cause"));
    }

    @Test
    void rootFatalTerminationRecordsFatalOutbox() {
        Node document = blue.yamlToNode("name: Root Fatal\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  terminate:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: TerminateScope\n" +
                "    mode: fatal\n" +
                "    reason: panic\n");

        Node event = buildTestEvent("evt-2");
        Node initialized = blue.initializeDocument(document).document();
        DocumentProcessingResult result = blue.processDocument(initialized, event);

        List<Node> triggeredEvents = result.triggeredEvents();
        assertEquals(2, triggeredEvents.size(), "Fatal run should emit terminated and fatal error events");
        assertEquals("Document Processing Terminated", stringProperty(triggeredEvents.get(0), "type"));
        assertEquals("fatal", stringProperty(triggeredEvents.get(0), "cause"));
        assertEquals("Document Processing Fatal Error", stringProperty(triggeredEvents.get(1), "type"));
        assertEquals("/", stringProperty(triggeredEvents.get(1), "domain"));
        assertEquals("RuntimeFatal", stringProperty(triggeredEvents.get(1), "code"));
        assertEquals("panic", stringProperty(triggeredEvents.get(1), "reason"));
    }

    @Test
    void childTerminationBridgesToParent() {
        Node document = blue.yamlToNode("name: Parent\n" +
                "child:\n" +
                "  name: Child\n" +
                "  contracts:\n" +
                "    testChannel:\n" +
                "      type:\n" +
                "        blueId: TestEventChannel\n" +
                "    terminate:\n" +
                "      channel: testChannel\n" +
                "      type:\n" +
                "        blueId: TerminateScope\n" +
                "      mode: graceful\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  childBridge:\n" +
                "    type:\n" +
                "      blueId: EmbeddedNodeChannel\n" +
                "    childPath: /child\n" +
                "  captureChild:\n" +
                "    channel: childBridge\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /fromChild\n" +
                "    propertyValue: 7\n");

        Node event = buildTestEvent("evt-3");
        Node initialized = blue.initializeDocument(document).document();
        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node processed = result.document();
        Node fromChild = processed.getProperties().get("fromChild");
        assertNotNull(fromChild, "Parent should capture bridged termination event");
        assertEquals(new BigInteger("7"), fromChild.getValue());

        Node childContracts = processed.getProperties().get("child").getProperties()
                .get("contracts");
        assertNotNull(childContracts);
        Node childTerminated = childContracts.getProperties().get("terminated");
        assertNotNull(childTerminated);
        assertEquals("graceful", childTerminated.getProperties().get("cause").getValue());
    }

    private Node buildTestEvent(String id) {
        TestEvent testEvent = new TestEvent().eventId(id).x(1);
        return blue.objectToNode(testEvent);
    }

    private String stringProperty(Node node, String key) {
        Map<String, Node> properties = node.getProperties();
        if (properties == null) {
            return null;
        }
        Node value = properties.get(key);
        if (value == null) {
            return null;
        }
        Object raw = value.getValue();
        return raw != null ? raw.toString() : null;
    }
}
