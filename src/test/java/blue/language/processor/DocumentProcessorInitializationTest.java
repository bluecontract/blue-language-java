package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.RemovePropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorInitializationTest {

    @Test
    void initializesDocumentAndExecutesHandlersInOrder() {
        String yaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 5\n" +
                "  setXLater:\n" +
                "    order: 1\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 10\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);
        String expectedDocumentId = blue.calculateBlueId(original.clone());

        assertFalse(blue.isInitialized(original));

        assertThrows(IllegalStateException.class,
                () -> blue.processDocument(original, new Node().value("external")));

        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node initialized = initResult.document();

        assertTrue(blue.isInitialized(initialized));

        assertEquals(1, initResult.triggeredEvents().size());
        Node lifecycleEvent = initResult.triggeredEvents().get(0);
        Map<String, Node> lifecycleProps = lifecycleEvent.getProperties();
        assertEquals("Document Processing Initiated", lifecycleProps.get("type").getValue());
        Node lifecycleDocId = lifecycleProps.get("documentId");
        assertNotNull(lifecycleDocId);
        assertEquals(expectedDocumentId, lifecycleDocId.getValue());

        Map<String, Node> initializedProps = initialized.getProperties();
        assertNotNull(initializedProps);

        Node xNode = initializedProps.get("x");
        assertNotNull(xNode, "x should be present after initialization");
        assertEquals(new BigInteger("10"), xNode.getValue());

        Node contractsNode = initializedProps.get("contracts");
        assertNotNull(contractsNode);
        Node initializedNode = contractsNode.getProperties().get("initialized");
        assertNotNull(initializedNode, "Initialization marker should be present");
        Node initType = initializedNode.getType();
        assertNotNull(initType);
        assertEquals("InitializationMarker", initType.getBlueId());
        Node markerDocId = initializedNode.getProperties().get("documentId");
        assertNotNull(markerDocId);
        assertEquals(expectedDocumentId, markerDocId.getValue());

        Node checkpointNode = contractsNode.getProperties().get("checkpoint");
        assertNull(checkpointNode, "Checkpoint marker should not be present before any external event");

        assertThrows(IllegalStateException.class, () -> blue.initializeDocument(initialized));

        DocumentProcessingResult processResult = blue.processDocument(initialized, new Node().value("external"));
        Node processed = processResult.document();
        assertEquals(new BigInteger("10"), processed.getProperties().get("x").getValue());

        assertTrue(processResult.triggeredEvents().isEmpty());

        assertNull(original.getProperties().get("x"));
    }

    @Test
    void initializationHandlesCustomPaths() {
        String yaml = "name: Custom Path Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  setRoot:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 3\n" +
                "  setNested:\n" +
                "    order: 1\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    path: /nested/branch/\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: x\n" +
                "    propertyValue: 7\n" +
                "  setExplicit:\n" +
                "    order: 2\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    path: a/x\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: x\n" +
                "    propertyValue: 11\n";

        Blue blue = new Blue();
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
                "      blueId: LifecycleChannel\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 5\n";

        Blue blue = new Blue();
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
                "      blueId: NotInitializationMarker\n";

        Blue blue = new Blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.processDocument(document, new Node().value("event")));
        assertTrue(ex.getMessage().contains("Initialization Marker"));
    }

    @Test
    void initializeDocumentFailsWhenInitializationKeyOccupiedIncorrectly() {
        String yaml = "name: Bad Init Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: SomethingElse\n";

        Blue blue = new Blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.initializeDocument(document));
        assertTrue(ex.getMessage().contains("Initialization Marker"));
    }

    @Test
    void isInitializedThrowsWhenReservedKeyIsMisused() {
        String yaml = "name: Bad Check Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: WrongMarker\n";

        Blue blue = new Blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.isInitialized(document));
        assertTrue(ex.getMessage().contains("Initialization Marker"));
    }

    @Test
    void removePatchDeletesPropertyDuringInitialization() {
        String yaml = "name: Remove Doc\n" +
                "x:\n" +
                "  type:\n" +
                "    blueId: Text\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  removeX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: RemoveProperty\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: /x\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new RemovePropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        assertTrue(original.getProperties().containsKey("x"));

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        assertFalse(processed.getProperties().containsKey("x"));
        assertTrue(result.triggeredEvents().stream()
                .anyMatch(node -> {
                    Map<String, Node> props = node.getProperties();
                    return props != null && "Document Processing Initiated".equals(props.get("type").getValue());
                }));

        assertTrue(original.getProperties().containsKey("x"));
    }

    @Test
    void checkpointBeforeInitializationCausesFatal() {
        String yaml = "name: Invalid Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: ChannelEventCheckpoint\n";

        Blue blue = new Blue();
        Node document = blue.yamlToNode(yaml);

        assertThrows(IllegalStateException.class, () -> blue.initializeDocument(document));
    }

    @Test
    void initializationFailsWhenCheckpointHasWrongType() {
        String yaml = "name: Wrong Checkpoint Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: ProcessingFailureMarker\n";

        Blue blue = new Blue();
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
                "      blueId: ChannelEventCheckpoint\n" +
                "  extraCheckpoint:\n" +
                "    type:\n" +
                "      blueId: ChannelEventCheckpoint\n";

        Blue blue = new Blue();
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
                "      blueId: LifecycleChannel\n" +
                "  triggeredChannel:\n" +
                "    type:\n" +
                "      blueId: TriggeredEventChannel\n" +
                "  handleLifecycle:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    propertyKey: /lifecycle\n" +
                "    propertyValue: 1\n" +
                "  triggeredHandler:\n" +
                "    channel: triggeredChannel\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /triggered\n" +
                "    propertyValue: 1\n";

        Blue blue = new Blue();
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
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  childBridge:\n" +
                "    type:\n" +
                "      blueId: EmbeddedNodeChannel\n" +
                "    childPath: /child\n" +
                "  captureChildLifecycle:\n" +
                "    channel: childBridge\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /childLifecycle\n" +
                "    propertyValue: 1\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();

        Node childLifecycle = initialized.getProperties().get("childLifecycle");
        assertNotNull(childLifecycle, "Parent should observe child lifecycle through Embedded Node channel");
        assertEquals(new BigInteger("1"), childLifecycle.getValue());
    }
}
