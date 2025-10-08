package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.EmitEventsContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.TestEvent;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DocumentProcessorGasTest {

    private Blue blue;

    @BeforeEach
    void setUp() {
        blue = new Blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new EmitEventsContractProcessor());
    }

    @Test
    void initializationGasMatchesExpectedCharges() {
        Node document = blue.yamlToNode("name: Doc\n");

        DocumentProcessingResult result = blue.initializeDocument(document.clone());

        Node initializedMarker = extractInitializedMarker(result.document());
        long markerSizeCharge = sizeCharge(initializedMarker);

        long expected = scopeEntryCharge("/")
                + 1_000L // initialization
                + 30L    // lifecycle delivery
                + 2L     // boundary check
                + (20L + markerSizeCharge) // patch add
                + 10L;   // cascade routing for root

        assertEquals(expected, result.totalGas());
    }

    @Test
    void processDocumentPatchGasMatchesExpectedCharges() {
        String yaml = "name: Base\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  setter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document().clone();
        Node event = blue.objectToNode(new TestEvent().eventId("evt-1"));

        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node valueNode = extractProperty(result.document(), "x");
        long valueSizeCharge = sizeCharge(valueNode);

        long expected = scopeEntryCharge("/")
                + 5L    // channel match attempt
                + 50L   // handler overhead
                + 2L    // boundary check
                + (20L + valueSizeCharge) // add/replace patch
                + 10L   // cascade routing (root only)
                + 20L;  // checkpoint update direct write

        assertEquals(expected, result.totalGas());
    }

    @Test
    void processDocumentEmitsTriggeredEventChargesEmitAndDrain() {
        String yaml = "name: Emit\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  emitter:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: EmitEvents\n" +
                "    events:\n" +
                "      - type:\n" +
                "          blueId: TestEvent\n" +
                "        kind: emitted\n" +
                "  triggered:\n" +
                "    type:\n" +
                "      blueId: TriggeredEventChannel\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(yaml)).document().clone();
        Node event = blue.objectToNode(new TestEvent().eventId("evt-emit"));

        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node emittedTemplate = extractEmitterEventTemplate(result.document());
        long emittedSizeCharge = sizeCharge(emittedTemplate);

        long expected = scopeEntryCharge("/")
                + 5L    // external channel match
                + 50L   // handler overhead
                + (20L + emittedSizeCharge) // emit event
                + 10L   // drain triggered FIFO
                + 20L;  // checkpoint update after successful channel

        assertEquals(expected, result.totalGas());
    }

    private Node extractInitializedMarker(Node document) {
        Map<String, Node> contracts = document.getProperties();
        assertNotNull(contracts);
        Node contractsNode = contracts.get("contracts");
        assertNotNull(contractsNode);
        return contractsNode.getProperties().get("initialized");
    }

    private Node extractProperty(Node document, String key) {
        Map<String, Node> props = document.getProperties();
        assertNotNull(props);
        return props.get(key);
    }

    private Node extractEmitterEventTemplate(Node document) {
        Node contracts = document.getProperties().get("contracts");
        assertNotNull(contracts);
        Node emitter = contracts.getProperties().get("emitter");
        assertNotNull(emitter);
        Node events = emitter.getProperties().get("events");
        assertNotNull(events);
        return events.getItems().get(0);
    }

    private long scopeEntryCharge(String scopePath) {
        int depth = scopeDepth(scopePath);
        return 50L + 10L * depth;
    }

    private int scopeDepth(String scopePath) {
        if (scopePath == null || scopePath.isEmpty() || "/".equals(scopePath)) {
            return 0;
        }
        String trimmed = scopePath;
        if (trimmed.charAt(0) == '/') {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return 0;
        }
        int depth = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private long sizeCharge(Node node) {
        long bytes = canonicalSize(node);
        return (bytes + 99L) / 100L;
    }

    private long canonicalSize(Node node) {
        Object canonical = NodeToMapListOrValue.get(node);
        try {
            String json = UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(canonical);
            String canonicalJson = new JsonCanonicalizer(json).getEncodedString();
            return canonicalJson.getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize node", ex);
        }
    }
}
