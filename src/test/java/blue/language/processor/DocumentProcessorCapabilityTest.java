package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.TerminateScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorCapabilityTest {

    @Test
    void initializeDocumentFailsWithCapabilityFailureWhenProcessorMissing() {
        String yaml = "name: Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  handler:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Blue blue = new Blue();
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

        Blue blue = new Blue();
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

        Blue blue = new Blue();
        Node document = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(document);

        assertTrue(result.capabilityFailure());
        assertEquals(0L, result.totalGas());
        assertTrue(result.triggeredEvents().isEmpty());
        assertTrue(result.failureReason().contains("Contracts must be an object map"));
    }

    @Test
    void processDocumentFailsWithCapabilityFailureWhenNewUnsupportedContractAppears() {
        Blue blue = new Blue();
        blue.registerContractProcessor(new blue.language.processor.contracts.SetPropertyContractProcessor());

        String baseYaml = "name: Base\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  handler:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(baseYaml)).document().clone();
        Node contracts = initialized.getProperties().get("contracts");
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
        Node resultContracts = resultDoc.getProperties().get("contracts");
        assertNotNull(resultContracts);
        assertNotNull(resultContracts.getProperties().get("unsupportedHandler"));
        assertNotNull(result.failureReason());
    }

    @Test
    void processDocumentFailsWithCapabilityFailureWhenNewTypelessContractAppears() {
        Blue blue = new Blue();
        blue.registerContractProcessor(new blue.language.processor.contracts.SetPropertyContractProcessor());

        String baseYaml = "name: Base\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  handler:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(baseYaml)).document().clone();
        Node contracts = initialized.getProperties().get("contracts");
        assertNotNull(contracts);
        contracts.properties("unclear", new Node().properties("property", new Node().value("value")));

        DocumentProcessingResult result = blue.processDocument(initialized, new Node().value("event"));

        assertTrue(result.capabilityFailure());
        assertEquals(0L, result.totalGas());
        assertTrue(result.triggeredEvents().isEmpty());
        assertTrue(result.failureReason().contains("must declare a type"));
    }
}
