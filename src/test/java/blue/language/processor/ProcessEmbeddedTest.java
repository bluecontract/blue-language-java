package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.contracts.CutOffProbeContractProcessor;
import blue.language.processor.contracts.MutateEmbeddedPathsContractProcessor;
import blue.language.processor.contracts.RemoveIfPresentContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.TestEvent;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEmbeddedTest {

    @Test
    void initializesEmbeddedChildDocument() {
        String yaml = "name: Sample Doc\n" +
                "x:\n" +
                "  name: Sample Sub Doc\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: DocumentProcessingInitiated\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /a\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /x\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);
        String rootId = blue.calculateBlueId(original.clone());
        Node originalChildNode = original.getProperties().get("x");
        String childId = blue.calculateBlueId(originalChildNode.clone());

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();

        Node child = initialized.getProperties().get("x");
        assertNotNull(child, "Embedded child should remain present");
        Node childContracts = child.getProperties().get("contracts");
        assertNotNull(childContracts, "Child contracts map should exist");
        assertTrue(childContracts.getProperties().containsKey("initialized"),
                "Child scope must record Initialization Marker");
        Node childMarker = childContracts.getProperties().get("initialized");
        Node childMarkerDocId = childMarker.getProperties().get("documentId");
        assertNotNull(childMarkerDocId);
        assertEquals(childId, childMarkerDocId.getValue());
        assertEquals(new BigInteger("1"), child.getProperties().get("a").getValue(),
                "Child property /x/a should be set by embedded handler");

        Node rootContracts = initialized.getProperties().get("contracts");
        assertNotNull(rootContracts, "Root contracts map should exist");
        assertTrue(rootContracts.getProperties().containsKey("initialized"),
                "Root scope must record Initialization Marker");
        Node rootMarker = rootContracts.getProperties().get("initialized");
        Node rootMarkerDocId = rootMarker.getProperties().get("documentId");
        assertNotNull(rootMarkerDocId);
        assertEquals(rootId, rootMarkerDocId.getValue());

        assertEquals(1, result.triggeredEvents().size(),
                "Root lifecycle emission should still occur exactly once");
        Map<String, Node> lifecycleProps = result.triggeredEvents().get(0).getProperties();
        assertNotNull(lifecycleProps, "Lifecycle event should expose properties");
        assertEquals("Document Processing Initiated", lifecycleProps.get("type").getValue());
        Node lifecycleDocId = lifecycleProps.get("documentId");
        assertNotNull(lifecycleDocId);
        assertEquals(rootId, lifecycleDocId.getValue());
    }

    @Test
    void rootScopeCannotModifyEmbeddedInterior() {
        String allowedYaml = "name: Sample Doc\n" +
                "x:\n" +
                "  name: Sample Sub Doc\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: DocumentProcessingInitiated\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /a\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  rootLife:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  setRootY:\n" +
                "    channel: rootLife\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /y\n" +
                "    propertyValue: 1\n";

        String forbiddenYaml = allowedYaml +
                "  setChildInterior:\n" +
                "    order: 1\n" +
                "    channel: rootLife\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x/b\n" +
                "    propertyValue: 1\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());

        Node allowed = blue.yamlToNode(allowedYaml);
        DocumentProcessingResult allowedResult = blue.initializeDocument(allowed);
        Node initializedAllowed = allowedResult.document();
        assertEquals(new BigInteger("1"), initializedAllowed.getProperties().get("y").getValue());

        Node forbidden = blue.yamlToNode(forbiddenYaml);
        DocumentProcessingResult forbiddenResult = blue.initializeDocument(forbidden);
        Node forbiddenDoc = forbiddenResult.document();
        Node rootTerminated = terminatedMarker(forbiddenDoc, "/");
        assertNotNull(rootTerminated);
        assertEquals("fatal", rootTerminated.getProperties().get("cause").getValue());
    }

    @Test
    void nestedEmbeddedScopesEnforceBoundaries() {
        String nestedYaml = "name: Nested Doc\n" +
                "x:\n" +
                "  name: X Doc\n" +
                "  y:\n" +
                "    name: Y Doc\n" +
                "    contracts:\n" +
                "      life:\n" +
                "        type:\n" +
                "          blueId: LifecycleChannel\n" +
                "      setY:\n" +
                "        channel: life\n" +
                "        event:\n" +
                "          type:\n" +
                "            blueId: DocumentProcessingInitiated\n" +
                "        type:\n" +
                "          blueId: SetProperty\n" +
                "        propertyKey: /a\n" +
                "        propertyValue: 1\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    embedded:\n" +
                "      type:\n" +
                "        blueId: ProcessEmbedded\n" +
                "      paths:\n" +
                "        - /y\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  life:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n";

        String rootViolationYaml = nestedYaml +
                "  setDeep:\n" +
                "    channel: life\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: DocumentProcessingInitiated\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /x/y/a\n" +
                "    propertyValue: 2\n";

        String parentScopeViolationYaml = "name: Nested Doc\n" +
                "x:\n" +
                "  name: X Doc\n" +
                "  y:\n" +
                "    name: Y Doc\n" +
                "    contracts:\n" +
                "      life:\n" +
                "        type:\n" +
                "          blueId: LifecycleChannel\n" +
                "      setY:\n" +
                "        channel: life\n" +
                "        event:\n" +
                "          type:\n" +
                "            blueId: DocumentProcessingInitiated\n" +
                "        type:\n" +
                "          blueId: SetProperty\n" +
                "        propertyKey: /a\n" +
                "        propertyValue: 1\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    embedded:\n" +
                "      type:\n" +
                "        blueId: ProcessEmbedded\n" +
                "      paths:\n" +
                "        - /y\n" +
                "    setIllegalFromX:\n" +
                "      channel: life\n" +
                "      order: 1\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: DocumentProcessingInitiated\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /y/a\n" +
                "      propertyValue: 2\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  life:\n" +
                "    type:\n" +
                "      blueId: LifecycleChannel\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());

        Node nested = blue.yamlToNode(nestedYaml);
        DocumentProcessingResult nestedResult = blue.initializeDocument(nested);
        Node initialized = nestedResult.document();

        Node xNode = initialized.getProperties().get("x");
        assertNotNull(xNode);
        Node xContracts = xNode.getProperties().get("contracts");
        assertNotNull(xContracts);
        assertTrue(xContracts.getProperties().containsKey("initialized"));

        Node yNode = xNode.getProperties().get("y");
        assertNotNull(yNode);
        Node yContracts = yNode.getProperties().get("contracts");
        assertNotNull(yContracts);
        assertTrue(yContracts.getProperties().containsKey("initialized"));
        assertEquals(new BigInteger("1"), yNode.getProperties().get("a").getValue());

        Node originalY = nested.getProperties().get("x").getProperties().get("y");
        assertNull(originalY.getProperties().get("a"));

        Node rootViolation = blue.yamlToNode(rootViolationYaml);
        DocumentProcessingResult rootResult = blue.initializeDocument(rootViolation);
        Node rootTerminated = terminatedMarker(rootResult.document(), "/");
        assertNotNull(rootTerminated);
        assertEquals("fatal", rootTerminated.getProperties().get("cause").getValue());

        Node parentScopeViolation = blue.yamlToNode(parentScopeViolationYaml);
        DocumentProcessingResult parentResult = blue.initializeDocument(parentScopeViolation);
        Node parentTerminated = terminatedMarker(parentResult.document(), "/x");
        assertNotNull(parentTerminated);
        assertEquals("fatal", parentTerminated.getProperties().get("cause").getValue());
        assertNull(terminatedMarker(parentResult.document(), "/"));
    }

    @Test
    void embeddedListUpdatesProcessNewChildAfterCurrentScopeFinishes() {
        String yaml = "name: Sample Doc\n" +
                "a:\n" +
                "  name: Doc A\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: DocumentProcessingInitiated\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "b:\n" +
                "  name: Doc B\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: DocumentProcessingInitiated\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "c:\n" +
                "  name: Doc C\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: LifecycleChannel\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: DocumentProcessingInitiated\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /a\n" +
                "      - /b\n" +
                "  updateA:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /a/x\n" +
                "  handleA:\n" +
                "    channel: updateA\n" +
                "    type:\n" +
                "      blueId: MutateEmbeddedPaths\n" +
                "  updateB:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /b/x\n" +
                "  flagB:\n" +
                "    channel: updateB\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /mustNotHappen\n" +
                "    propertyValue: 1\n" +
                "  updateC:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /c/x\n" +
                "  flagC:\n" +
                "    channel: updateC\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /itShouldHappen\n" +
                "    propertyValue: 1\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new MutateEmbeddedPathsContractProcessor());

        Node original = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node document = result.document();
        Node rootTerminated = terminatedMarker(document, "/");
        assertNotNull(rootTerminated);
        assertEquals("fatal", rootTerminated.getProperties().get("cause").getValue());
    }

    @Test
    void embeddedListUpdatesProcessNewChildDuringExternalEvent() {
        String yaml = "name: Sample Doc\n" +
                "a:\n" +
                "  name: Doc A\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: TestEventChannel\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "b:\n" +
                "  name: Doc B\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: TestEventChannel\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "c:\n" +
                "  name: Doc C\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: TestEventChannel\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: SetProperty\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /a\n" +
                "      - /b\n" +
                "  updateA:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /a/x\n" +
                "  mutatePaths:\n" +
                "    channel: updateA\n" +
                "    type:\n" +
                "      blueId: MutateEmbeddedPaths\n" +
                "  updateB:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /b/x\n" +
                "  flagB:\n" +
                "    channel: updateB\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /mustNotHappen\n" +
                "    propertyValue: 1\n" +
                "  updateC:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /c/x\n" +
                "  flagC:\n" +
                "    channel: updateC\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    propertyKey: /itShouldHappen\n" +
                "    propertyValue: 1\n";

        Blue blue = new Blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new MutateEmbeddedPathsContractProcessor());
        blue.registerContractProcessor(new TestEventChannelProcessor());

        Node original = blue.yamlToNode(yaml);
        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node initialized = initResult.document();

        Node initialContracts = initialized.getProperties().get("contracts");
        Node initialEmbedded = initialContracts.getProperties().get("embedded");
        Node initialPaths = initialEmbedded.getProperties().get("paths");
        assertNotNull(initialPaths);
        assertEquals(2, initialPaths.getItems().size());
        assertEquals("/a", initialPaths.getItems().get(0).getValue());
        assertEquals("/b", initialPaths.getItems().get(1).getValue());
        assertNull(initialized.getProperties().get("itShouldHappen"));
        assertNull(initialized.getProperties().get("mustNotHappen"));

        Node event = blue.objectToNode(new TestEvent());
        DocumentProcessingResult processResult = blue.processDocument(initialized, event);
        Node processed = processResult.document();
        Node rootTerminated = terminatedMarker(processed, "/");
        assertNotNull(rootTerminated);
        assertEquals("fatal", rootTerminated.getProperties().get("cause").getValue());
        // Document remains unchanged when reserved-key mutation is rejected.
        assertNull(processed.getProperties().get("itShouldHappen"));
        assertNull(processed.getProperties().get("mustNotHappen"));
    }

    @Test
    void removingEmbeddedChildCutsOffFurtherWorkWithinRun() {
        Blue blue = new Blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new CutOffProbeContractProcessor());
        blue.registerContractProcessor(new RemoveIfPresentContractProcessor());
        blue.registerContractProcessor(new SetPropertyOnEventContractProcessor());

        String yaml = "child:\n" +
                "  contracts:\n" +
                "    childChannel:\n" +
                "      type:\n" +
                "        blueId: TestEventChannel\n" +
                "    probe:\n" +
                "      channel: childChannel\n" +
                "      type:\n" +
                "        blueId: CutOffProbe\n" +
                "      emitBefore: true\n" +
                "      preEmitKind: pre\n" +
                "      patchPointer: /marker\n" +
                "      patchValue: 1\n" +
                "      emitAfter: true\n" +
                "      postEmitKind: post\n" +
                "      postPatchPointer: /resurrection\n" +
                "      postPatchValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  embeddedBridge:\n" +
                "    type:\n" +
                "      blueId: EmbeddedNodeChannel\n" +
                "    childPath: /child\n" +
                "  bridgePre:\n" +
                "    channel: embeddedBridge\n" +
                "    type:\n" +
                "      blueId: SetPropertyOnEvent\n" +
                "    expectedKind: pre\n" +
                "    propertyKey: /bridged\n" +
                "    propertyValue: 1\n" +
                "  bridgePost:\n" +
                "    channel: embeddedBridge\n" +
                "    type:\n" +
                "      blueId: SetPropertyOnEvent\n" +
                "    expectedKind: post\n" +
                "    propertyKey: /postSeen\n" +
                "    propertyValue: 1\n" +
                "  childUpdates:\n" +
                "    type:\n" +
                "      blueId: DocumentUpdateChannel\n" +
                "    path: /child\n" +
                "  cutChild:\n" +
                "    channel: childUpdates\n" +
                "    type:\n" +
                "      blueId: RemoveIfPresent\n" +
                "    propertyKey: /child\n";

        Node source = blue.yamlToNode(yaml);
        Node initialized = blue.initializeDocument(source).document();

        Node event = blue.objectToNode(new TestEvent().eventId("evt-1"));
        DocumentProcessingResult result = blue.processDocument(initialized, event);
        Node processed = result.document();

        assertNull(processed.getProperties().get("child"), "Child scope should remain removed after cut-off");

        assertNull(processed.getProperties().get("postSeen"), "No post-cut-off emission should be bridged");

        boolean postEmissionRecorded = result.triggeredEvents().stream()
                .map(Node::getProperties)
                .filter(props -> props != null && props.get("kind") != null)
                .anyMatch(props -> "post".equals(props.get("kind").getValue()));
        assertFalse(postEmissionRecorded, "Post-cut-off emission must not reach root events");
    }

    @Test
    void rejectsMultipleProcessEmbeddedMarkersWithinScope() {
        String yaml = "name: Multi Embedded Doc\n" +
                "x:\n" +
                "  name: X Doc\n" +
                "y:\n" +
                "  name: Y Doc\n" +
                "contracts:\n" +
                "  embeddedPrimary:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  embeddedSecondary:\n" +
                "    type:\n" +
                "      blueId: ProcessEmbedded\n" +
                "    paths:\n" +
                "      - /y\n";

        Blue blue = new Blue();
        Node document = blue.yamlToNode(yaml);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> blue.initializeDocument(document));
        assertTrue(ex.getMessage().contains("Process Embedded"));
    }

    private Node terminatedMarker(Node document, String scopePath) {
        String contractsPointer = ProcessorEngine.resolvePointer(scopePath, "/contracts");
        try {
            Node contracts = document.getAsNode(contractsPointer);
            if (contracts == null || contracts.getProperties() == null) {
                return null;
            }
            return contracts.getProperties().get("terminated");
        } catch (Exception ex) {
            return null;
        }
    }
}
