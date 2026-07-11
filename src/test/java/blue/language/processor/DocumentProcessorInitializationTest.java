package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.RemovePropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorInitializationTest {

    @Test
    void initializeDocumentEmitsRootLifecycleEvent() {
        Blue blue = ProcessorTestSupport.blue();
        Node original = blue.yamlToNode("name: Minimal Doc\n" +
                "contracts: {}\n");

        DocumentProcessingResult result = blue.initializeDocument(original);

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertTrue(blue.isInitialized(result.document()));
        assertEquals(1, result.triggeredEvents().size());

        Node lifecycleEvent = result.triggeredEvents().get(0);
        assertNotNull(lifecycleEvent.getType());
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED, lifecycleEvent.getType().getBlueId());

        Node lifecycleDocId = lifecycleEvent.getProperties().get("documentId");
        Node markerDocId = result.document()
                .getContracts()
                .getProperties()
                .get("initialized")
                .getProperties()
                .get("documentId");
        assertNotNull(lifecycleDocId);
        assertEquals(markerDocId.getValue(), lifecycleDocId.getValue());
    }

    @Test
    void initializesDocumentAndExecutesHandlersInOrder() {
        String yaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 5\n" +
                "  setXLater:\n" +
                "    order: 1\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 10\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);
        assertFalse(blue.isInitialized(original));

        DocumentProcessingResult uninitializedProcessResult = blue.processDocument(original.clone(), new Node().value("external"));
        assertTrue(blue.isInitialized(uninitializedProcessResult.document()));

        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node initialized = initResult.document();

        assertTrue(blue.isInitialized(initialized));

        assertEquals(1, initResult.triggeredEvents().size());
        Node lifecycleEvent = initResult.triggeredEvents().get(0);
        Map<String, Node> lifecycleProps = lifecycleEvent.getProperties();
        assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED, lifecycleEvent.getType().getBlueId());
        Node lifecycleDocId = lifecycleProps.get("documentId");
        assertNotNull(lifecycleDocId);
        Node markerDocId = initialized.getContracts()
                .getProperties()
                .get("initialized")
                .getProperties()
                .get("documentId");
        assertEquals(markerDocId.getValue(), lifecycleDocId.getValue());

        Map<String, Node> initializedProps = initialized.getProperties();
        assertNotNull(initializedProps);

        Node xNode = initializedProps.get("x");
        assertNotNull(xNode, "x should be present after initialization");
        assertEquals(new BigInteger("10"), xNode.getValue());

        Node contractsNode = initialized.getContracts();
        assertNotNull(contractsNode);
        Node initializedNode = contractsNode.getProperties().get("initialized");
        assertNotNull(initializedNode, "Initialization marker should be present");
        Node initType = initializedNode.getType();
        assertNotNull(initType);
        assertEquals(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER, initType.getBlueId());
        Node initializedMarkerDocId = initializedNode.getProperties().get("documentId");
        assertNotNull(initializedMarkerDocId);

        Node checkpointNode = contractsNode.getProperties().get("checkpoint");
        assertNull(checkpointNode, "Checkpoint marker should not be present before any external event");

        assertThrows(IllegalStateException.class, () -> blue.initializeDocument(initialized));

        DocumentProcessingResult postInitProcessResult = blue.processDocument(initialized, new Node().value("external"));
        Node processed = postInitProcessResult.document();
        assertEquals(new BigInteger("10"), processed.getProperties().get("x").getValue());

        assertTrue(postInitProcessResult.triggeredEvents().isEmpty());

        assertNull(original.getProperties() != null ? original.getProperties().get("x") : null);
    }

    @Test
    void initializationHandlesCustomPaths() {
        String yaml = "name: Custom Path Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ\n" +
                "  setRoot:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 3\n" +
                "  setNested:\n" +
                "    order: 1\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    path: /nested/branch/\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL\n" +
                "    propertyKey: x\n" +
                "    propertyValue: 7\n" +
                "  setExplicit:\n" +
                "    order: 2\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    path: a/x\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL\n" +
                "    propertyKey: x\n" +
                "    propertyValue: 11\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node processed = initResult.document();

        assertEquals(new BigInteger("3"), processed.getProperties().get("x").getValue());

        Node nested = processed.getProperties().get("nested");
        assertNotNull(nested);
        Node branch = nested.getProperties().get("branch");
        assertNotNull(branch);
        Node nestedX = branch.getProperties().get("x");
        assertNotNull(nestedX);
        assertEquals(new BigInteger("7"), nestedX.getValue());

        Node aNode = processed.getProperties().get("a");
        assertNotNull(aNode);
        Node firstX = aNode.getProperties().get("x");
        assertNotNull(firstX);
        Node explicit = firstX.getProperties().get("x");
        assertNotNull(explicit);
        assertEquals(new BigInteger("11"), explicit.getValue());

    }


    @Test
    void capabilityFailureWhenContractProcessorMissing() {
        String yaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 5\n";

        Blue blue = ProcessorTestSupport.blue();
        Node original = blue.yamlToNode(yaml);
        String originalJson = blue.nodeToJson(original.clone());

        DocumentProcessingResult result = blue.initializeDocument(original);
        assertTrue(result.capabilityFailure(), "Initialization should fail with must-understand");
        assertEquals(0L, result.totalGas());
        assertTrue(result.triggeredEvents().isEmpty());
        assertEquals(originalJson, blue.nodeToJson(result.document()));
    }

    @Test
    void processDocumentFailsWhenInitializationMarkerIncompatible() {
        String yaml = "name: Bad Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.processDocument(document, new Node().value("event")));
        assertTrue(ex.getMessage().contains("Processing Initialized Marker"));
    }

    @Test
    void initializeDocumentFailsWhenInitializationKeyOccupiedIncorrectly() {
        String yaml = "name: Bad Init Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.initializeDocument(document));
        assertTrue(ex.getMessage().contains("Processing Initialized Marker"));
    }

    @Test
    void isInitializedThrowsWhenReservedKeyIsMisused() {
        String yaml = "name: Bad Check Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.isInitialized(document));
        assertTrue(ex.getMessage().contains("Processing Initialized Marker"));
    }

    @Test
    void removePatchDeletesPropertyDuringInitialization() {
        String yaml = "name: Remove Doc\n" +
                "x:\n" +
                "  type:\n" +
                "    blueId: GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ\n" +
                "  removeX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 2REa15BDY5EWq4tJsbUaBwhhTG2xSdk2ZyFL1aCpqTVF\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL\n" +
                "    propertyKey: /x\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new RemovePropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        assertTrue(original.getProperties().containsKey("x"));

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        assertFalse(processed.getProperties() != null && processed.getProperties().containsKey("x"));
        assertTrue(result.triggeredEvents().stream()
                .anyMatch(node -> {
                    return node.getType() != null
                            && RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED.equals(node.getType().getBlueId());
                }));

        assertTrue(original.getProperties().containsKey("x"));
    }

    @Test
    void checkpointBeforeInitializationCausesFatal() {
        String yaml = "name: Invalid Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: 9GEC24YbFG9hj4banjYh2oEnDpAob1wAPmhjuykJp8T1\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        assertThrows(IllegalStateException.class, () -> blue.initializeDocument(document));
    }

    @Test
    void initializationFailsWhenCheckpointHasWrongType() {
        String yaml = "name: Wrong Checkpoint Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: 33kfH8pfk7F1P5zMsuK1Jm3GcSdmTXoFHKjP16DesEco\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.initializeDocument(document));
        assertTrue(ex.getMessage().contains("Channel Event Checkpoint"));
    }

    @Test
    void initializationFailsWhenMultipleCheckpointsPresent() {
        String yaml = "name: Duplicate Checkpoint Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: 9GEC24YbFG9hj4banjYh2oEnDpAob1wAPmhjuykJp8T1\n" +
                "  extraCheckpoint:\n" +
                "    type:\n" +
                "      blueId: 9GEC24YbFG9hj4banjYh2oEnDpAob1wAPmhjuykJp8T1\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.initializeDocument(document));
        assertTrue(ex.getMessage().contains("Channel Event Checkpoint"));
    }

    @Test
    void lifecycleEventsDoNotDriveTriggeredHandlers() {
        String yaml = "name: Lifecycle Trigger Isolation\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2DXGQUiQBQ6CT89jwAsTAXaEPhLgiSXhKCGh9Q7Hv3MQ\n" +
                "  triggeredChannel:\n" +
                "    type:\n" +
                "      blueId: 5HwxfbwRBCxG8xYpowWkCPC9akqUSKV7So2M4QHEmLsZ\n" +
                "  handleLifecycle:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: Ht1o66MTLKf7JmnEiR27rRLSwdz8FUTgf2mGPNuLSDUL\n" +
                "    propertyKey: /lifecycle\n" +
                "    propertyValue: 1\n" +
                "  triggeredHandler:\n" +
                "    channel: triggeredChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /triggered\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();

        assertNotNull(initialized.getProperties().get("lifecycle"));
        assertNull(initialized.getProperties().get("triggered"),
                "Triggered handler should not run from lifecycle emission");
    }

    @Test
    void childLifecycleIsBridgedToParent() {
        String yaml = "name: Embedded Lifecycle\n" +
                "child:\n" +
                "  name: Inner\n" +
                "  contracts: {}\n" +
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
                "  captureChildLifecycle:\n" +
                "    channel: childBridge\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /childLifecycle\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();

        Node childLifecycle = initialized.getProperties().get("childLifecycle");
        assertNotNull(childLifecycle, "Parent should observe child lifecycle through Embedded Node channel");
        assertEquals(new BigInteger("1"), childLifecycle.getValue());
    }
}
