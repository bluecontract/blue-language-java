package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TerminateScopeContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
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
        blue = ProcessorTestSupport.blue();
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
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  terminate:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: AZNvNsADqpp7ZwAgpQyaQSz4cq3o3RMHZtB3sgDfudD4\n" +
                "    mode: graceful\n" +
                "    emitAfter: true\n" +
                "    patchAfter: true\n");

        Node event = buildTestEvent("evt-1");
        Node initialized = blue.initializeDocument(document).document();
        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node processed = result.document();
        Node contracts = processed.getContracts();
        assertNotNull(contracts);
        Node terminated = contracts.getProperties().get("terminated");
        assertNotNull(terminated);
        assertEquals("graceful", terminated.getProperties().get("cause").getValue());
        Node afterTermination = processed.getProperties() != null ? processed.getProperties().get("afterTermination") : null;
        assertNotNull(afterTermination, "buffered patches apply before buffered termination");
        assertEquals("should-not-exist", afterTermination.getValue());

        List<Node> triggeredEvents = result.triggeredEvents();
        assertEquals(2, triggeredEvents.size(), "Buffered emitted event is recorded before termination lifecycle");
        assertEquals("ShouldNotEmit", triggeredEvents.get(0).getProperties().get("type").getValue());
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED, triggeredEvents.get(1).getType().getBlueId());
        assertEquals("graceful", stringProperty(triggeredEvents.get(1), "cause"));
    }

    @Test
    void rootFatalTerminationRecordsFatalOutbox() {
        Node document = blue.yamlToNode("name: Root Fatal\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  terminate:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: AZNvNsADqpp7ZwAgpQyaQSz4cq3o3RMHZtB3sgDfudD4\n" +
                "    mode: fatal\n" +
                "    reason: panic\n");

        Node event = buildTestEvent("evt-2");
        Node initialized = blue.initializeDocument(document).document();
        DocumentProcessingResult result = blue.processDocument(initialized, event);

        List<Node> triggeredEvents = result.triggeredEvents();
        assertEquals(2, triggeredEvents.size(), "Fatal run should emit terminated and fatal error events");
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED, triggeredEvents.get(0).getType().getBlueId());
        assertEquals("fatal", stringProperty(triggeredEvents.get(0), "cause"));
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR, triggeredEvents.get(1).getType().getBlueId());
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
                "        blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "    terminate:\n" +
                "      channel: testChannel\n" +
                "      type:\n" +
                "        blueId: AZNvNsADqpp7ZwAgpQyaQSz4cq3o3RMHZtB3sgDfudD4\n" +
                "      mode: graceful\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: 8FVc8MPz6DcTMgcY3RXU6EBpGa9arWPJ141K2H86yi8Q\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  childBridge:\n" +
                "    type:\n" +
                "      blueId: H6iUJp3GcLypsJDimMSVoxQQdxxuD8j6eqEUWWqCZ6i\n" +
                "    childPath: /child\n" +
                "  captureChild:\n" +
                "    channel: childBridge\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /fromChild\n" +
                "    propertyValue: 7\n");

        Node event = buildTestEvent("evt-3");
        Node initialized = blue.initializeDocument(document).document();
        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node processed = result.document();
        Node fromChild = processed.getProperties().get("fromChild");
        assertNotNull(fromChild, "Parent should capture bridged termination event");
        assertEquals(new BigInteger("7"), fromChild.getValue());

        Node childContracts = processed.getProperties().get("child").getContracts();
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
