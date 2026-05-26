package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.ApplyBatchPatchContractProcessor;
import blue.language.processor.model.TerminateScope;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorCapabilityTest {

    @Test
    void initializeDocumentFailsWithCapabilityFailureWhenProcessorMissing() {
        String yaml = "name: Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ\n" +
                "  handler:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);
        String originalJson = blue.nodeToJson(document.clone());

        DocumentProcessingResult result = blue.initializeDocument(document);
        assertTrue(result.capabilityFailure());
        assertEquals(0L, result.totalGas());
        assertTrue(result.triggeredEvents().isEmpty());
        assertEquals(originalJson, blue.nodeToJson(result.document()));
        assertNotNull(result.failureReason());
    }

    @Test
    void initializeDocumentFailsWithCapabilityFailureWhenContractHasNoType() {
        String yaml = "name: Doc\n" +
                "contracts:\n" +
                "  unclear:\n" +
                "    property: value\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);
        String originalJson = blue.nodeToJson(document.clone());

        DocumentProcessingResult result = blue.initializeDocument(document);

        assertTrue(result.capabilityFailure());
        assertEquals(0L, result.totalGas());
        assertTrue(result.triggeredEvents().isEmpty());
        assertEquals(originalJson, blue.nodeToJson(result.document()));
        assertTrue(result.failureReason().contains("must declare a type"));
    }

    @Test
    void initializeDocumentFailsWithCapabilityFailureWhenContractsIsNotObjectMap() {
        String yaml = "name: Doc\n" +
                "contracts:\n" +
                "  - bad\n";

        Blue blue = ProcessorTestSupport.blue();
        assertThrows(RuntimeException.class, () -> blue.yamlToNode(yaml));
    }

    @Test
    void processDocumentFailsWithCapabilityFailureWhenNewUnsupportedContractAppears() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new blue.language.processor.contracts.SetPropertyContractProcessor());

        String baseYaml = "name: Base\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ\n" +
                "  handler:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(baseYaml)).document().clone();
        Node contracts = initialized.getContracts();
        assertNotNull(contracts);

        TerminateScope scope = new TerminateScope();
        scope.setChannelKey("lifecycleChannel");
        scope.setMode("fatal");
        scope.setReason("test");
        Node unsupported = blue.objectToNode(scope);
        contracts.properties("unsupportedHandler", unsupported);

        Node event = new Node().value("event");
        DocumentProcessingResult result = blue.processDocument(initialized, event);

        assertTrue(result.capabilityFailure());
        assertEquals(0L, result.totalGas());
        assertTrue(result.triggeredEvents().isEmpty());
        Node resultDoc = result.document();
        assertNotNull(resultDoc);
        Node resultContracts = resultDoc.getContracts();
        assertNotNull(resultContracts);
        assertNotNull(resultContracts.getProperties().get("unsupportedHandler"));
        assertNotNull(result.failureReason());
    }

    @Test
    void processDocumentFailsWithCapabilityFailureWhenNewTypelessContractAppears() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new blue.language.processor.contracts.SetPropertyContractProcessor());

        String baseYaml = "name: Base\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ\n" +
                "  handler:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(baseYaml)).document().clone();
        Node contracts = initialized.getContracts();
        assertNotNull(contracts);
        contracts.properties("unclear", new Node().properties("property", new Node().value("value")));

        DocumentProcessingResult result = blue.processDocument(initialized, new Node().value("event"));

        assertTrue(result.capabilityFailure());
        assertEquals(0L, result.totalGas());
        assertTrue(result.triggeredEvents().isEmpty());
        assertTrue(result.failureReason().contains("must declare a type"));
    }

    @Test
    void unsupportedContractAddedByPatchCausesRuntimeFatalNotCapabilityFailure() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new ApplyBatchPatchContractProcessor());

        String yaml = "name: Runtime Unsupported\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "  addUnsupported:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: AjWAjR4NcDYJHMhkAkX9DZKqGbHs8vkCRpjXiHRkLPMw\n" +
                "    addUnsupportedContract: true\n";

        DocumentProcessingResult result = blue.initializeDocument(blue.yamlToNode(yaml));

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertTrue(result.totalGas() > 0L);
        Node contracts = result.document().getContracts();
        assertNotNull(contracts);
        Node terminated = contracts.getProperties().get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
    }

    @Test
    void unsupportedContractInsidePreExistingTerminatedEmbeddedScopeIsIgnored() {
        Blue blue = ProcessorTestSupport.blue();

        String yaml = "name: Root\n" +
                "child:\n" +
                "  name: Child\n" +
                "  contracts:\n" +
                "    terminated:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.PROCESSING_TERMINATED_MARKER + "\n" +
                "      cause: graceful\n" +
                "    unsupported:\n" +
                "      channel: missing\n" +
                "      type:\n" +
                "        blueId: AZNvNsADqpp7ZwAgpQyaQSz4cq3o3RMHZtB3sgDfudD4\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /child\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.processDocument(document, new Node().value("event"));

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertTrue(result.totalGas() > 0L);
        Node childContracts = result.document().getProperties().get("child").getContracts();
        assertNotNull(childContracts.getProperties().get("terminated"));
        assertNotNull(childContracts.getProperties().get("unsupported"));
    }

    @Test
    void invalidPreExistingTerminatedMarkerFailsInitialMustUnderstand() {
        Blue blue = ProcessorTestSupport.blue();

        String yaml = "name: Root\n" +
                "contracts:\n" +
                "  terminated:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    cause: graceful\n" +
                "  unsupported:\n" +
                "    channel: missing\n" +
                "    type:\n" +
                "      blueId: AZNvNsADqpp7ZwAgpQyaQSz4cq3o3RMHZtB3sgDfudD4\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.processDocument(document, new Node().value("event"));

        assertTrue(result.capabilityFailure());
        assertEquals(0L, result.totalGas());
        assertTrue(result.failureReason().contains("terminated"));
    }
}
