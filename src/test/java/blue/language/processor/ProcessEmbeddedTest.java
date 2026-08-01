package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.contracts.CutOffProbeContractProcessor;
import blue.language.processor.contracts.MutateEmbeddedPathsContractProcessor;
import blue.language.processor.contracts.RemoveIfPresentContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static blue.language.processor.util.ProcessorContractConstants.KEY_DOCUMENT;
import static blue.language.processor.util.ProcessorContractConstants.KEY_EMBEDDED;
import static blue.language.processor.util.ProcessorContractConstants.KEY_INITIALIZED;
import static blue.language.processor.util.ProcessorContractConstants.KEY_PATHS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEmbeddedTest {

    @Test
    void shouldInitializeEmbeddedChildDocument() {
        // given
        String yaml = "name: Sample Doc\n" +
                "x:\n" +
                "  name: Sample Sub Doc\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      propertyKey: /a\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /x\n";
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();
        Node child = initialized.getProperties().get("x");
        Node childContracts = child.getContracts();
        Node childMarker = childContracts.getProperties()
                .get(KEY_INITIALIZED);
        Node childMarkerDocument =
                childMarker.getProperties().get(KEY_DOCUMENT);
        Node rootContracts = initialized.getContracts();
        Node rootMarker = rootContracts.getProperties()
                .get(KEY_INITIALIZED);
        Node rootMarkerDocument =
                rootMarker.getProperties().get(KEY_DOCUMENT);

        // then
        assertNotNull(child, "Embedded child should remain present");
        assertNotNull(childContracts, "Child contracts map should exist");
        assertTrue(childContracts.getProperties().containsKey(KEY_INITIALIZED),
                "Child scope must record Initialization Marker");
        assertNotNull(childMarkerDocument);
        assertEquals(new BigInteger("1"), child.getProperties().get("a").getValue(),
                "Child property /x/a should be set by embedded handler");

        assertNotNull(rootContracts, "Root contracts map should exist");
        assertTrue(rootContracts.getProperties().containsKey(KEY_INITIALIZED),
                "Root scope must record Initialization Marker");
        assertNotNull(rootMarkerDocument);
        assertFalse(rootMarkerDocument.toString()
                .equals(childMarkerDocument.toString()));

        assertTrue(result.events().isEmpty(),
                "processor-generated initialization lifecycle is local");
    }

    @Test
    void shouldAllowRootScopeToModifyOutsideEmbeddedInterior() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node document = blue.yamlToNode(rootBoundaryYaml());

        // when
        DocumentProcessingResult result = blue.initializeDocument(document);

        // then
        assertEquals(
                new BigInteger("1"),
                result.document().getProperties().get("y").getValue());
    }

    @Test
    void shouldRejectRootScopeModificationInsideEmbeddedInterior() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node document = blue.yamlToNode(
                rootBoundaryYaml()
                        + "  setChildInterior:\n"
                        + "    order: 1\n"
                        + "    channel: rootLife\n"
                        + "    event:\n"
                        + "      type:\n"
                        + "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n"
                        + "    type:\n"
                        + "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                        + "    propertyKey: /x/b\n"
                        + "    propertyValue: 1\n");

        // when
        DocumentProcessingResult result = blue.initializeDocument(document);

        // then
        assertRolledBack(document, result);
    }

    @Test
    void shouldInitializeEveryNestedEmbeddedScopeWithoutMutatingSource() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node source = blue.yamlToNode(nestedEmbeddedYaml());

        // when
        DocumentProcessingResult result = blue.initializeDocument(source);
        Node initialized = result.document();
        Node xNode = initialized.getProperties().get("x");
        Node xContracts = xNode.getContracts();
        Node yNode = xNode.getProperties().get("y");
        Node yContracts = yNode.getContracts();
        Node originalY = source.getProperties()
                .get("x").getProperties().get("y");

        // then
        assertNotNull(xNode);
        assertNotNull(xContracts);
        assertTrue(xContracts.getProperties().containsKey(KEY_INITIALIZED));

        assertNotNull(yNode);
        assertNotNull(yContracts);
        assertTrue(yContracts.getProperties().containsKey(KEY_INITIALIZED));
        assertEquals(new BigInteger("1"), yNode.getProperties().get("a").getValue());

        assertNull(originalY.getProperties() != null ? originalY.getProperties().get("a") : null);
    }

    @Test
    void shouldRejectRootMutationAcrossNestedEmbeddedBoundary() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node document = blue.yamlToNode(
                nestedEmbeddedYaml()
                        + "  setDeep:\n"
                        + "    channel: life\n"
                        + "    event:\n"
                        + "      type:\n"
                        + "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n"
                        + "    type:\n"
                        + "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                        + "    propertyKey: /x/y/a\n"
                        + "    propertyValue: 2\n");

        // when
        DocumentProcessingResult result = blue.initializeDocument(document);

        // then
        assertRolledBack(document, result);
    }

    @Test
    void shouldRejectParentMutationAcrossNestedEmbeddedBoundary() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node document = blue.yamlToNode(parentScopeViolationYaml());

        // when
        DocumentProcessingResult result = blue.initializeDocument(document);

        // then
        assertRolledBack(document, result);
    }

    @Test
    void shouldVerifyEmbeddedListUpdatesProcessNewChildAfterCurrentScopeFinishes() {
        // given
        String yaml = "name: Sample Doc\n" +
                "a:\n" +
                "  name: Doc A\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "b:\n" +
                "  name: Doc B\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "c:\n" +
                "  name: Doc C\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /a\n" +
                "      - /b\n" +
                "  updateA:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /a/x\n" +
                "  handleA:\n" +
                "    channel: updateA\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.MUTATE_EMBEDDED_PATHS + "\n" +
                "  updateB:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /b/x\n" +
                "  flagB:\n" +
                "    channel: updateB\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /mustNotHappen\n" +
                "    propertyValue: 1\n" +
                "  updateC:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /c/x\n" +
                "  flagC:\n" +
                "    channel: updateC\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /itShouldHappen\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new MutateEmbeddedPathsContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);

        // then
        assertNull(terminatedMarker(result.document(), "/"));
    }

    @Test
    void shouldInitializeConfiguredEmbeddedMembership() {
        // given
        String currentEventId = "evt-current-membership";
        String laterEventId = "evt-later-membership";

        // when
        EmbeddedMembershipObservation observation =
                observeEmbeddedMembershipUpdates(
                        currentEventId, laterEventId);
        Node initialPaths = observation.initialized.getContracts()
                .getProperties().get(KEY_EMBEDDED)
                .getProperties().get(KEY_PATHS);

        // then
        assertNotNull(initialPaths);
        assertEquals(2, initialPaths.getItems().size());
        assertEquals("/a", initialPaths.getItems().get(0).getValue());
        assertEquals("/b", initialPaths.getItems().get(1).getValue());
        assertNull(observation.initialized.getProperties()
                .get("itShouldHappen"));
        assertNull(observation.initialized.getProperties()
                .get("mustNotHappen"));
    }

    @Test
    void shouldKeepCurrentExternalEventOnFrozenEmbeddedMembership() {
        // given
        String currentEventId = "evt-current-membership";
        String laterEventId = "evt-later-membership";

        // when
        EmbeddedMembershipObservation observation =
                observeEmbeddedMembershipUpdates(
                        currentEventId, laterEventId);
        Node afterFirst = observation.afterFirst;
        Node updatedPaths = afterFirst.getContracts()
                .getProperties().get(KEY_EMBEDDED)
                .getProperties().get(KEY_PATHS);
        Node cAfterFirst = afterFirst.getProperties().get("c");

        // then
        assertNull(terminatedMarker(afterFirst, "/"));
        assertEquals(1, updatedPaths.getItems().size());
        assertEquals("/c", updatedPaths.getItems().get(0).getValue());
        assertNull(afterFirst.getProperties().get("itShouldHappen"),
                "the new /c membership must not affect the current event");
        assertNull(afterFirst.getProperties().get("mustNotHappen"),
                "the removed /b scope cannot run after it is cut off");
        assertTrue(cAfterFirst.getProperties() == null
                        || cAfterFirst.getProperties().get("x") == null,
                "the new /c membership must not execute until the next event");
    }

    @Test
    void shouldApplyUpdatedEmbeddedMembershipToLaterExternalEvent() {
        // given
        String currentEventId = "evt-current-membership";
        String laterEventId = "evt-later-membership";

        // when
        EmbeddedMembershipObservation observation =
                observeEmbeddedMembershipUpdates(
                        currentEventId, laterEventId);
        Node afterSecond = observation.afterSecond;

        // then
        assertEquals(new BigInteger("1"),
                afterSecond.getProperties().get("c")
                        .getProperties().get("x").getValue());
        assertNotNull(afterSecond.getProperties().get("itShouldHappen"),
                observation.secondResult.status() + ": "
                        + diagnosticMessage(observation.secondResult)
                        + "\n" + observation.blue.nodeToYaml(afterSecond));
    }

    private EmbeddedMembershipObservation observeEmbeddedMembershipUpdates(
            String currentEventId,
            String laterEventId) {
        String yaml = "name: Sample Doc\n" +
                "a:\n" +
                "  name: Doc A\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "b:\n" +
                "  name: Doc B\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "c:\n" +
                "  name: Doc C\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /a\n" +
                "      - /b\n" +
                "  updateA:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /a/x\n" +
                "  mutatePaths:\n" +
                "    channel: updateA\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.MUTATE_EMBEDDED_PATHS + "\n" +
                "  updateB:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /b/x\n" +
                "  flagB:\n" +
                "    channel: updateB\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /mustNotHappen\n" +
                "    propertyValue: 1\n" +
                "  updateC:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /c/x\n" +
                "  flagC:\n" +
                "    channel: updateC\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /itShouldHappen\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new MutateEmbeddedPathsContractProcessor());
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        Node original = blue.yamlToNode(yaml);
        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node initialized = initResult.document();

        Node firstEvent = blue.objectToNode(
                new TestEvent().eventId(currentEventId));
        DocumentProcessingResult firstResult =
                blue.processDocument(initialized, firstEvent);
        Node afterFirst = firstResult.document();

        Node secondEvent = blue.objectToNode(
                new TestEvent().eventId(laterEventId));
        DocumentProcessingResult secondResult =
                blue.processDocument(afterFirst, secondEvent);
        Node afterSecond = secondResult.document();
        return new EmbeddedMembershipObservation(
                blue, initialized, afterFirst, afterSecond, secondResult);
    }

    @Test
    void shouldVerifyActualBalloonCutOffStillStopsFurtherEffects() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        blue.registerContractProcessor(new CutOffProbeContractProcessor());
        blue.registerContractProcessor(new RemoveIfPresentContractProcessor());
        blue.registerContractProcessor(new SetPropertyOnEventContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        String yaml = "child:\n" +
                "  contracts:\n" +
                "    childChannel:\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "    probe:\n" +
                "      channel: childChannel\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.CUT_OFF_PROBE + "\n" +
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
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  embeddedBridge:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.EMBEDDED_NODE_CHANNEL + "\n" +
                "    sourcePath: /child\n" +
                "  bridgePre:\n" +
                "    channel: embeddedBridge\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY_ON_EVENT + "\n" +
                "    expectedKind: pre\n" +
                "    propertyKey: /bridged\n" +
                "    propertyValue: 1\n" +
                "  bridgePost:\n" +
                "    channel: embeddedBridge\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY_ON_EVENT + "\n" +
                "    expectedKind: post\n" +
                "    propertyKey: /postSeen\n" +
                "    propertyValue: 1\n" +
                "  childUpdates:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n" +
                "    path: /child\n" +
                "  cutChild:\n" +
                "    channel: childUpdates\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.REMOVE_IF_PRESENT + "\n" +
                "    propertyKey: /child\n";

        Node source = blue.yamlToNode(yaml);
        Node initialized = blue.initializeDocument(source).document();

        // when
        Node event = blue.objectToNode(new TestEvent().eventId("evt-1"));
        DocumentProcessingResult result = blue.processDocument(initialized, event);
        Node processed = result.document();
        boolean postEmissionRecorded = result.events().stream()
                .map(Node::getProperties)
                .filter(props -> props != null && props.get("kind") != null)
                .anyMatch(props -> "post".equals(
                        props.get("kind").getValue()));

        // then
        assertNull(processed.getProperties() != null ? processed.getProperties().get("child") : null,
                "Child scope should remain removed after cut-off; status="
                        + result.status() + ", reason="
                        + diagnosticMessage(result) + "\n"
                        + blue.nodeToYaml(processed));

        assertNull(processed.getProperties() != null ? processed.getProperties().get("postSeen") : null,
                "No post-cut-off emission should be bridged");

        assertFalse(postEmissionRecorded, "Post-cut-off emission must not reach root events");
    }

    @Test
    void shouldVerifyEmbeddedPathSlashFailsAtomicallyWithoutACommittedTerminationMarker() {
        // given
        String yaml = "name: Self Embedded\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /\n";
        Blue blue = ProcessorTestSupport.blue();
        Node input = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(input);

        // then
        assertEquals(ProcessorStatus.CAPABILITY_FAILURE,
                result.status(), diagnosticMessage(result));
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result), diagnosticMessage(result));
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(input.toString(), result.document().toString());
        assertNull(terminatedMarker(result.document(), "/"));
    }

    @Test
    void shouldVerifyDuplicateEmbeddedPathsAreRejected() {
        // given
        String yaml = "name: Duplicate Embedded\n" +
                "child:\n" +
                "  name: Child\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /child\n" +
                "      - /child\n";
        Blue blue = ProcessorTestSupport.blue();
        Node input = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(input);

        // then
        assertTrue(isCapabilityFailure(result));
        assertTrue(diagnosticMessage(result).contains("Unique items"));
        assertEquals(input.toString(), result.document().toString());
    }

    @Test
    void shouldVerifyEmbeddedPathSelectingNonObjectFailsAtomically() {
        // given
        String yaml = "name: Scalar Embedded\n" +
                "child: scalar\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /child\n";
        Blue blue = ProcessorTestSupport.blue();
        Node input = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(input);

        // then
        assertRolledBack(input, result);
    }

    @Test
    void shouldVerifyEmbeddedPathSelectingPureReferenceIsBoundaryViolationBeforeInitialization() {
        // given
        Node childType = new Node()
                .name("Referenced Embedded Context Type")
                .properties("inherited", new Node().value("forces typed materialization"));
        Node referenced = new Node()
                .name("Referenced Object Is Not A Selected Object Node")
                .properties("payload", new Node().value("provider content"));
        BasicNodeProvider provider = new BasicNodeProvider(childType, referenced);
        String childTypeBlueId = provider.getBlueIdByName(childType.getName());
        Node parentType = new Node()
                .name("Referenced Embedded Parent Type")
                .properties("child", new Node().type(new Node().blueId(childTypeBlueId)));
        provider.addSingleNodes(parentType);
        String parentTypeBlueId = provider.getBlueIdByName(parentType.getName());
        String referencedBlueId = provider.getBlueIdByName(referenced.getName());
        String yaml = "name: Referenced Embedded\n" +
                "type:\n" +
                "  blueId: " + parentTypeBlueId + "\n" +
                "child:\n" +
                "  blueId: " + referencedBlueId + "\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /child\n";
        Blue blue = ProcessorTestSupport.blue(provider);
        Node input = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result =
                blue.initializeDocument(input);

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), diagnosticMessage(result));
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result), diagnosticMessage(result));
        assertTrue(result.document().getProperties().get("child").isReferenceOnly(),
                "the referenced child must not be initialized or mutated as an active scope");
        assertTrue(result.events().isEmpty());
        assertFalse(result.commits());
    }

    @Test
    void shouldRejectMultipleProcessEmbeddedMarkersWithinScope() {
        // given
        String yaml = "name: Multi Embedded Doc\n" +
                "x:\n" +
                "  name: X Doc\n" +
                "y:\n" +
                "  name: Y Doc\n" +
                "contracts:\n" +
                "  embeddedPrimary:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  embeddedSecondary:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /y\n";
        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(document);

        // then
        assertEquals(ProcessorStatus.CAPABILITY_FAILURE,
                result.status(), diagnosticMessage(result));
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result), diagnosticMessage(result));
        assertTrue(diagnosticMessage(result).contains("Process Embedded"));
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(document.toString(), result.document().toString());
    }

    private String rootBoundaryYaml() {
        return "name: Sample Doc\n"
                + "x:\n"
                + "  name: Sample Sub Doc\n"
                + "  contracts:\n"
                + "    life:\n"
                + "      type:\n"
                + "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n"
                + "    setX:\n"
                + "      channel: life\n"
                + "      event:\n"
                + "        type:\n"
                + "          blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n"
                + "      type:\n"
                + "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                + "      propertyKey: /a\n"
                + "      propertyValue: 1\n"
                + "contracts:\n"
                + "  rootLife:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n"
                + "  embedded:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "    paths:\n"
                + "      - /x\n"
                + "  setRootY:\n"
                + "    channel: rootLife\n"
                + "    event:\n"
                + "      type:\n"
                + "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n"
                + "    type:\n"
                + "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                + "    propertyKey: /y\n"
                + "    propertyValue: 1\n";
    }

    private String nestedEmbeddedYaml() {
        return "name: Nested Doc\n"
                + "x:\n"
                + "  name: X Doc\n"
                + "  y:\n"
                + "    name: Y Doc\n"
                + "    contracts:\n"
                + "      life:\n"
                + "        type:\n"
                + "          blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n"
                + "      setY:\n"
                + "        channel: life\n"
                + "        event:\n"
                + "          type:\n"
                + "            blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n"
                + "        type:\n"
                + "          blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                + "        propertyKey: /a\n"
                + "        propertyValue: 1\n"
                + "  contracts:\n"
                + "    life:\n"
                + "      type:\n"
                + "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n"
                + "    embedded:\n"
                + "      type:\n"
                + "        blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "      paths:\n"
                + "        - /y\n"
                + "contracts:\n"
                + "  embedded:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "    paths:\n"
                + "      - /x\n"
                + "  life:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";
    }

    private String parentScopeViolationYaml() {
        return "name: Nested Doc\n"
                + "x:\n"
                + "  name: X Doc\n"
                + "  y:\n"
                + "    name: Y Doc\n"
                + "    contracts:\n"
                + "      life:\n"
                + "        type:\n"
                + "          blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n"
                + "      setY:\n"
                + "        channel: life\n"
                + "        event:\n"
                + "          type:\n"
                + "            blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n"
                + "        type:\n"
                + "          blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                + "        propertyKey: /a\n"
                + "        propertyValue: 1\n"
                + "  contracts:\n"
                + "    life:\n"
                + "      type:\n"
                + "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n"
                + "    embedded:\n"
                + "      type:\n"
                + "        blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "      paths:\n"
                + "        - /y\n"
                + "    setIllegalFromX:\n"
                + "      channel: life\n"
                + "      order: 1\n"
                + "      event:\n"
                + "        type:\n"
                + "          blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n"
                + "      type:\n"
                + "        blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n"
                + "      propertyKey: /y/a\n"
                + "      propertyValue: 2\n"
                + "contracts:\n"
                + "  embedded:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "    paths:\n"
                + "      - /x\n"
                + "  life:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";
    }

    private static final class EmbeddedMembershipObservation {
        private final Blue blue;
        private final Node initialized;
        private final Node afterFirst;
        private final Node afterSecond;
        private final DocumentProcessingResult secondResult;

        private EmbeddedMembershipObservation(
                Blue blue,
                Node initialized,
                Node afterFirst,
                Node afterSecond,
                DocumentProcessingResult secondResult) {
            this.blue = blue;
            this.initialized = initialized;
            this.afterFirst = afterFirst;
            this.afterSecond = afterSecond;
            this.secondResult = secondResult;
        }
    }

    private void assertRolledBack(Node input, DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status(), diagnosticMessage(result));
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(input.toString(), result.document().toString());
        assertNull(terminatedMarker(result.document(), "/"));
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
