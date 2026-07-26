package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TerminateScopeContractProcessor;
import blue.language.processor.model.TestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorTerminationTest {

    private Blue blue;

    @BeforeEach
    void setUp() {
        blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(new TerminateScopeContractProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
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
        DocumentProcessingResult initialized = blue.initializeDocument(document);
        DocumentProcessingResult result = blue.processDocument(initialized.snapshot(), event);

        assertEquals(ProcessorStatus.SUCCESS,
                result.status());
        assertTrue(result.commits());
        Node processed = result.document();
        Node contracts = processed.getContracts();
        assertNotNull(contracts);
        Node terminated = contracts.getProperties().get("terminated");
        assertNotNull(terminated);
        assertEquals("graceful", terminated.getProperties().get("cause").getValue());
        Node afterTermination = processed.getProperties() != null ? processed.getProperties().get("afterTermination") : null;
        assertNotNull(afterTermination, "buffered patches apply before buffered termination");
        assertEquals("should-not-exist", afterTermination.getValue());

        List<Node> rootEvents = result.events();
        assertEquals(1, rootEvents.size(),
                "only the explicit application event emitted by Root enters the public outbox");
        assertEquals("ShouldNotEmit",
                rootEvents.get(0).getProperties().get("type").getValue());
    }

    @Test
    void fatalTerminationRequestRollsBackWithoutOutboxOrMarker() {
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
        String input = initialized.toString();
        DocumentProcessingResult result =
                blue.processDocument(initialized, event);

        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertEquals(input, result.document().toString(),
                "deterministic failure must return the exact input Root");
        assertTrue(result.events().isEmpty());
        assertFalse(result.document().getContracts()
                .getProperties().containsKey("terminated"));
    }

    @Test
    void childTerminationLifecycleRemainsLocal() {
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
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  childBridge:\n" +
                "    type:\n" +
                "      blueId: 7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN\n" +
                "    childPath: /child\n" +
                "  captureChild:\n" +
                "    channel: childBridge\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /fromChild\n" +
                "    propertyValue: 7\n");

        Node event = buildTestEvent("evt-3");
        DocumentProcessingResult initialized = blue.initializeDocument(document);
        ProcessingDebugResult debug = blue.getDocumentProcessor()
                .processDocumentWithTrace(initialized.snapshot(), event);
        DocumentProcessingResult result = debug.processResult();

        assertEquals(ProcessorStatus.SUCCESS,
                result.status(),
                debug.trace().records().stream()
                        .map(record -> record.kind() + ":"
                                + record.scopePath() + ":"
                                + record.contractKey())
                        .collect(java.util.stream.Collectors.joining(", ")));
        assertTrue(result.commits());
        Node processed = result.document();
        Node fromChild = processed.getProperties().get("fromChild");
        assertNull(fromChild,
                "processor-generated lifecycle delivery is local to its scope");

        Node childContracts = processed.getProperties().get("child").getContracts();
        assertNotNull(childContracts);
        Node childTerminated = childContracts.getProperties().get("terminated");
        assertNotNull(childTerminated);
        assertEquals("graceful", childTerminated.getProperties().get("cause").getValue());
        assertTrue(result.events().isEmpty(),
                "processor-generated embedded lifecycle events remain internal");
    }

    private Node buildTestEvent(String id) {
        TestEvent testEvent = new TestEvent().eventId(id).x(1);
        return blue.objectToNode(testEvent);
    }
}
