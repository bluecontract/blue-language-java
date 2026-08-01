package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.TypeBlueId;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.processor.contracts.RemovePropertyContractProcessor;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.BlueLanguageConstants;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static blue.language.processor.util.ProcessorContractConstants.KEY_CHECKPOINT;
import static blue.language.processor.util.ProcessorContractConstants.KEY_DOCUMENT;
import static blue.language.processor.util.ProcessorContractConstants.KEY_INITIALIZED;
import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorInitializationTest {

    private static final String CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID =
            "n1dTwJjYLh4mvRbrBiQ56fLj8skq8pGo8eyPhmTtBJH";

    @Test
    void shouldKeepProcessorLifecycleLocalAndWriteMarkerWhenInitializingDocument() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node original = blue.yamlToNode("name: Minimal Doc\n" +
                "contracts: {}\n");
        String expectedDocumentId =
                blue.resolveToSnapshot(original.clone())
                        .blueId();

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node markerDocument = result.document()
                .getContracts()
                .getProperties()
                .get(KEY_INITIALIZED)
                .getProperties()
                .get(KEY_DOCUMENT);

        // then
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertNull(diagnosticCategory(result), diagnosticMessage(result));
        assertTrue(blue.isInitialized(result.document()));
        assertProcessorLifecycleIsLocal(result);
        assertNotNull(markerDocument);
        assertEquals(expectedDocumentId,
                DirectBlueIdCalculator.calculateBlueId(markerDocument));
    }

    @Test
    void shouldVerifyInitializationMarkerUsesDirectWriteWithoutApplicationPatchMetrics() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        blue.processingObserver(metrics);
        Node original = blue.yamlToNode("name: Minimal Doc\n" +
                "contracts: {}\n");
        ResolvedSnapshot preInitialization = blue.resolveToSnapshot(original.clone());
        String expectedDocumentId = preInitialization.frozenCanonicalRoot().blueId();

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document()
                .getContracts()
                .getProperties()
                .get(KEY_INITIALIZED);
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();

        // then
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertEquals(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER,
                initialized.getType().getBlueId());
        assertEquals(expectedDocumentId,
                DirectBlueIdCalculator.calculateBlueId(
                        initialized.getProperties().get(KEY_DOCUMENT)));
        assertProcessorLifecycleIsLocal(result);
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(0L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.PROCESSOR_INITIALIZATION_MARKER"), snapshot.toString());
        assertEquals(0L, snapshot.counter("frozenPatchValuesAccepted"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerIncrementalResolutions"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbackReason.CONTRACTS_CHANGED"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdContentBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdCanonicalMaterializations"), snapshot.toString());
    }

    @Test
    void shouldVerifySnapshotBackedInitializationMarkerUsesDirectWriteWithoutPatchResolution() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        blue.processingObserver(metrics);
        ResolvedSnapshot preInitialization = blue.resolveToSnapshot(blue.yamlToNode(
                "name: Snapshot Minimal Doc\n" +
                        "contracts: {}\n"));

        // when
        DocumentProcessingResult result = blue.initializeDocument(preInitialization);
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();

        // then
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertProcessorLifecycleIsLocal(result);
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(0L, snapshot.counter("frozenPatchValuesAccepted"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerIncrementalResolutions"), snapshot.toString());
        assertEquals(0L, snapshot.counter("incrementalSnapshotResolutions"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbacks"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdContentBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdCanonicalMaterializations"), snapshot.toString());
    }

    @Test
    void shouldVerifyInitializationDocumentUsesVerifiedExactIdentityWhenUncheckedIdentityDiffers() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        blue.processingObserver(metrics);
        Node original = blue.yamlToNode(
                "name: Nested List Divergence\n" +
                        "bex:\n" +
                        "  do:\n" +
                        "    - - 1\n" +
                        "      - 2\n" +
                        "contracts: {}\n");
        ResolvedSnapshot preInitialization = blue.resolveToSnapshot(original.clone());
        String canonical = preInitialization.frozenCanonicalRoot().blueId();

        // when
        String unchecked = uncheckedInitializationId(preInitialization.frozenCanonicalRoot());
        DocumentProcessingResult result = blue.initializeDocument(original);
        String markerDocumentId = markerDocumentId(result.document(), "/");
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();

        // then
        assertNotEquals(canonical, unchecked,
                "canonical=" + canonical + ", unchecked=" + unchecked);
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertEquals(canonical, markerDocumentId,
                "canonical=" + canonical + ", unchecked=" + unchecked);
        assertProcessorLifecycleIsLocal(result);
        assertNotEquals(unchecked, markerDocumentId);
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(0L, snapshot.counter("frozenPatchValuesAccepted"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbackReason.CONTRACTS_CHANGED"), snapshot.toString());
    }

    @Test
    void shouldVerifyInitializationIdentityForScalarAndPayloadShapes() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        List<String> fixtures =
                identityShapeFixtures().subList(0, 4);

        // when
        List<InitializationIdentityObservation> observations =
                initializeAndReload(blue, fixtures);

        // then
        assertInitializationIdentities(observations);
    }

    @Test
    void shouldVerifyInitializationIdentityForTypedAndObjectListShapes() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        List<String> fixtures =
                identityShapeFixtures().subList(4, 9);

        // when
        List<InitializationIdentityObservation> observations =
                initializeAndReload(blue, fixtures);

        // then
        assertInitializationIdentities(observations);
    }

    @Test
    void shouldVerifyInitializationIdentityForContractAndEmbeddedShapes() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        List<String> fixtures =
                identityShapeFixtures().subList(9, 12);

        // when
        List<InitializationIdentityObservation> observations =
                initializeAndReload(blue, fixtures);

        // then
        assertInitializationIdentities(observations);
    }

    @Test
    void shouldVerifyInitializationIdentityForPreviousListShape() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Blue previousBlue = ProcessorTestSupport.blue(provider);
        Node previous = previousBlue.yamlToNode(
                "items:\n" +
                        "  - previous-a\n" +
                        "  - previous-b\n");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(previous.getItems());
        String fixture =
                "name: Previous List Control Shape\n"
                        + "history:\n"
                        + "  type:\n"
                        + "    blueId: " + BlueLanguageConstants.LIST_TYPE_BLUE_ID + "\n"
                        + "  mergePolicy: append-only\n"
                        + "  items:\n"
                        + "    - $previous:\n"
                        + "        blueId: " + previousBlueId + "\n"
                        + "    - after\n"
                        + "contracts: {}\n";

        // when
        provider.addListAndItsItems(previous.getItems());
        InitializationIdentityObservation observation =
                initializeAndReload(previousBlue, fixture);

        // then
        assertInitializationIdentity(observation);
    }

    @Test
    void shouldVerifyBexShapedNestedListsUseVerifiedExactInitializationIdentity() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        List<String> fixtures = Arrays.asList(
                "name: Compute Do Payload List\n" +
                        "compute:\n" +
                        "  do:\n" +
                        "    - - 1\n" +
                        "      - 2\n" +
                        "contracts: {}\n",
                "name: Nested Operand Lists\n" +
                        "compute:\n" +
                        "  expr:\n" +
                        "    - \"$add\":\n" +
                        "        - [1, 2]\n" +
                        "        - [3, [4, 5]]\n" +
                        "contracts: {}\n",
                "name: Operation Maps In List\n" +
                        "compute:\n" +
                        "  do:\n" +
                        "    - \"$set\": [/status, confirmed]\n" +
                        "    - \"$emit\": [{kind: done}]\n" +
                        "contracts: {}\n",
                "name: List Containing Payload List\n" +
                        "operands:\n" +
                        "  - before\n" +
                        "  - - nested\n" +
                        "    - [operand]\n" +
                        "contracts: {}\n",
                "name: Mixed Operands\n" +
                        "operands:\n" +
                        "  - 1\n" +
                        "  - {kind: object}\n" +
                        "  - [a, {b: c}, [d]]\n" +
                        "contracts: {}\n",
                "name: Empty Operand List\n" +
                        "operands: []\n" +
                        "contracts: {}\n",
                "name: Single Element List\n" +
                        "operands:\n" +
                        "  - [only]\n" +
                        "contracts: {}\n",
                "name: Multiple Nested Levels\n" +
                        "operands:\n" +
                        "  - - - - deep\n" +
                        "contracts: {}\n");

        // when
        List<InitializationIdentityObservation> observations =
                initializeAndReload(blue, fixtures);

        // then
        assertInitializationIdentities(observations);
    }

    @Test
    void shouldVerifyEmbeddedScopeInitializationDocumentsUseTheirOwnExactPreInitializationIdentity() {
        // given
        BasicNodeProvider identityProvider = new BasicNodeProvider();
        identityProvider.addSingleNodes(new Node().name("CaptureLifecycleDocumentId"));
        Blue blue = ProcessorTestSupport.blue(identityProvider);
        blue.registerExternalContractType(CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID,
                new Node().name("CaptureLifecycleDocumentId"),
                new CaptureLifecycleDocumentIdProcessor());
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        Node original = blue.yamlToNode(
                "name: Embedded Nested List\n" +
                        "child:\n" +
                        "  name: Child Nested List\n" +
                        "  payload:\n" +
                        "    do:\n" +
                        "      - - 1\n" +
                        "        - [2, 3]\n" +
                        "  contracts:\n" +
                        "    lifecycle:\n" +
                        "      type:\n" +
                        "        blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                        "    captureChildId:\n" +
                        "      channel: lifecycle\n" +
                        "      type:\n" +
                        "        blueId: " + CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID + "\n" +
                        "      propertyKey: /childLifecycleDocumentId\n" +
                        "contracts:\n" +
                        "  embedded:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                        "    paths:\n" +
                        "      - /child\n" +
                        "  lifecycle:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                        "  captureRootId:\n" +
                        "    channel: lifecycle\n" +
                        "    type:\n" +
                        "      blueId: " + CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID + "\n" +
                        "    propertyKey: /rootLifecycleDocumentId\n");
        Node standaloneChildBeforeLifecycle = original.getAsNode("/child").clone();
        ResolvedSnapshot childPreInitialization = blue.resolveToSnapshot(standaloneChildBeforeLifecycle);
        String childContentBlueId = childPreInitialization.blueId();
        String rootDocumentBlueId =
                rootDocumentIdentityAtInitialization(blue, original);
        blue.processingObserver(metrics);

        // when
        String childUnchecked = uncheckedInitializationId(childPreInitialization.frozenCanonicalRoot());
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();

        // then
        assertNotEquals(childContentBlueId, childUnchecked,
                "canonical=" + childContentBlueId + ", unchecked=" + childUnchecked);
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertEquals(rootDocumentBlueId, markerDocumentId(initialized, "/"));
        assertEquals(childContentBlueId, markerDocumentId(initialized, "/child"));
        assertEquals(rootDocumentBlueId, initialized.getAsText("/rootLifecycleDocumentId"));
        assertEquals(childContentBlueId, initialized.getAsText("/child/childLifecycleDocumentId"));
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(2L, snapshot.counter("frozenPatchValuesAccepted"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbackReason.CONTRACTS_CHANGED"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdContentBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdCanonicalMaterializations"), snapshot.toString());
    }

    @Test
    void shouldVerifyNonObjectEmbeddedChildTerminatesDuringPhase1WithoutInitialization() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingObserver metrics = new RecordingProcessingObserver();
        blue.processingObserver(metrics);
        Node original = blue.yamlToNode(
                "name: Non Object Embedded Child\n" +
                        "payload:\n" +
                        "  - - 1\n" +
                        "    - 2\n" +
                        "child: scalar\n" +
                        "contracts:\n" +
                        "  embedded:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                        "    paths:\n" +
                        "      - /child\n");
        String exactInput = original.toString();
        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();

        // then
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertFalse(result.commits());
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result));
        assertEquals(exactInput,
                result.document().toString());
        assertNull(result.document().getContracts().getProperties()
                .get(KEY_INITIALIZED));
        assertTrue(result.events().isEmpty());
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
    }

    @Test
    void shouldRejectProcessingBeforeInitialization() {
        // given
        Blue blue = orderedInitializationBlue();
        Node original = orderedInitializationDocument(blue);

        // when
        DocumentProcessingResult uninitializedProcessResult =
                blue.processDocument(
                        original.clone(),
                        new Node().value("external"));

        // then
        assertFalse(blue.isInitialized(original));
        assertEquals(ProcessorStatus.NO_MATCH,
                uninitializedProcessResult.status());
        assertFalse(uninitializedProcessResult.commits());
        assertFalse(blue.isInitialized(
                uninitializedProcessResult.document()));
        assertTrue(uninitializedProcessResult.events().isEmpty());
        assertEquals(original.toString(),
                uninitializedProcessResult.document().toString());
    }

    @Test
    void shouldExecuteInitializationHandlersInOrder() {
        // given
        Blue blue = orderedInitializationBlue();
        Node original = orderedInitializationDocument(blue);

        // when
        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node initialized = initResult.document();
        Node markerDocument = initialized.getContracts()
                .getProperties()
                .get(KEY_INITIALIZED)
                .getProperties()
                .get(KEY_DOCUMENT);
        Map<String, Node> initializedProps = initialized.getProperties();
        Node xNode = initializedProps.get("x");
        Node contractsNode = initialized.getContracts();
        Node initializedNode = contractsNode.getProperties()
                .get(KEY_INITIALIZED);
        Node initType = initializedNode.getType();
        Node initializedMarkerDocument =
                initializedNode.getProperties().get(KEY_DOCUMENT);
        Node checkpointNode = contractsNode.getProperties()
                .get(KEY_CHECKPOINT);

        // then
        assertTrue(blue.isInitialized(initialized));
        assertProcessorLifecycleIsLocal(initResult);
        assertNotNull(markerDocument);
        assertNotNull(initializedProps);
        assertNotNull(xNode, "x should be present after initialization");
        assertEquals(new BigInteger("10"), xNode.getValue());
        assertNotNull(contractsNode);
        assertNotNull(initializedNode, "Initialization marker should be present");
        assertNotNull(initType);
        assertEquals(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER, initType.getBlueId());
        assertNotNull(initializedMarkerDocument);
        assertNull(checkpointNode, "Checkpoint marker should not be present before any external event");
        assertNull(original.getProperties() != null ? original.getProperties().get("x") : null);
    }

    @Test
    void shouldRejectInitializingAnAlreadyInitializedDocument() {
        // given
        Blue blue = orderedInitializationBlue();
        Node initialized =
                blue.initializeDocument(orderedInitializationDocument(blue))
                        .document();

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> blue.initializeDocument(initialized));

        // then
        assertTrue(failure instanceof IllegalStateException);
    }

    @Test
    void shouldKeepInitializedDocumentUnchangedWhenExternalEventDoesNotMatch() {
        // given
        Blue blue = orderedInitializationBlue();
        Node initialized =
                blue.initializeDocument(orderedInitializationDocument(blue))
                        .document();

        // when
        DocumentProcessingResult postInitProcessResult =
                blue.processDocument(
                        initialized,
                        new Node().value("external"));
        Node processed = postInitProcessResult.document();

        // then
        assertEquals(ProcessorStatus.NO_MATCH,
                postInitProcessResult.status());
        assertFalse(postInitProcessResult.commits());
        assertEquals(new BigInteger("10"), processed.getProperties().get("x").getValue());
        assertEquals(initialized.toString(),
                processed.toString());

        assertTrue(postInitProcessResult.events().isEmpty());
    }

    @Test
    void shouldVerifyInitializationHandlesCustomPaths() {
        // given
        String yaml = "name: Custom Path Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "  setRoot:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 3\n" +
                "  setNested:\n" +
                "    order: 1\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    path: /nested/branch/\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "    propertyKey: x\n" +
                "    propertyValue: 7\n" +
                "  setExplicit:\n" +
                "    order: 2\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    path: a/x\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "    propertyKey: x\n" +
                "    propertyValue: 11\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node processed = initResult.document();
        Node nested = processed.getProperties().get("nested");
        Node branch = nested.getProperties().get("branch");
        Node nestedX = branch.getProperties().get("x");
        Node aNode = processed.getProperties().get("a");
        Node firstX = aNode.getProperties().get("x");
        Node explicit = firstX.getProperties().get("x");

        // then
        assertEquals(new BigInteger("3"), processed.getProperties().get("x").getValue());
        assertNotNull(nested);
        assertNotNull(branch);
        assertNotNull(nestedX);
        assertEquals(new BigInteger("7"), nestedX.getValue());
        assertNotNull(aNode);
        assertNotNull(firstX);
        assertNotNull(explicit);
        assertEquals(new BigInteger("11"), explicit.getValue());

    }


    @Test
    void shouldVerifyCapabilityFailureWhenContractProcessorMissing() {
        // given
        String yaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 5\n";

        Blue blue = ProcessorTestSupport.blue();
        Node original = blue.yamlToNode(yaml);
        String originalJson = blue.nodeToJson(original.clone());

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);

        // then
        assertTrue(isCapabilityFailure(result), "Initialization should fail with must-understand");
        assertEquals(0L, result.totalGas());
        assertTrue(result.events().isEmpty());
        assertEquals(originalJson, blue.nodeToJson(result.document()));
    }

    @Test
    void shouldVerifyIncompatibleInitializationMarkerOutsideParticipatingClosureKeepsNoMatch() {
        // given
        String yaml = "name: Bad Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result =
                blue.processDocument(
                        document,
                        new Node().value("event"));

        // then
        assertEquals(ProcessorStatus.NO_MATCH,
                result.status());
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(document.toString(),
                result.document().toString());
        assertNull(diagnosticMessage(result));
    }

    @Test
    void shouldFailInitializationWhenInitializationKeyIsOccupiedIncorrectly() {
        // given
        String yaml = "name: Bad Init Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> blue.initializeDocument(document));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertTrue(failure.getMessage().contains("Processing Initialized Marker"));
    }

    @Test
    void shouldVerifyIsInitializedThrowsWhenReservedKeyIsMisused() {
        // given
        String yaml = "name: Bad Check Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> blue.isInitialized(document));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertTrue(failure.getMessage().contains("Processing Initialized Marker"));
    }

    @Test
    void shouldDeletePropertyWithRemovePatchDuringInitialization() {
        // given
        String yaml = "name: Remove Doc\n" +
                "x:\n" +
                "  type:\n" +
                "    blueId: " + BlueLanguageConstants.TEXT_TYPE_BLUE_ID + "\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "  removeX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.REMOVE_PROPERTY + "\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "    propertyKey: /x\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new RemovePropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);
        boolean hadPropertyBeforeInitialization =
                original.getProperties().containsKey("x");

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        // then
        assertTrue(hadPropertyBeforeInitialization);
        assertFalse(processed.getProperties() != null && processed.getProperties().containsKey("x"));
        assertProcessorLifecycleIsLocal(result);

        assertTrue(original.getProperties().containsKey("x"));
    }

    @Test
    void shouldVerifyCheckpointBeforeInitializationIsRejected() {
        // given
        String yaml = "name: Invalid Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> blue.initializeDocument(document));

        // then
        assertTrue(failure instanceof IllegalStateException);
    }

    @Test
    void shouldVerifyInitializationFailsWhenCheckpointHasWrongType() {
        // given
        String yaml = "name: Wrong Checkpoint Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.PROCESSING_FAILURE_MARKER + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> blue.initializeDocument(document));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertTrue(failure.getMessage().contains("Channel Event Checkpoint"));
    }

    @Test
    void shouldVerifyInitializationFailsWhenMultipleCheckpointsPresent() {
        // given
        String yaml = "name: Duplicate Checkpoint Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT + "\n" +
                "  extraCheckpoint:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> blue.initializeDocument(document));

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertTrue(failure.getMessage().contains("Channel Event Checkpoint"));
    }

    @Test
    void shouldVerifyLifecycleEventsDoNotDriveTriggeredHandlers() {
        // given
        String yaml = "name: Lifecycle Trigger Isolation\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "  triggeredChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL + "\n" +
                "  handleLifecycle:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                "    propertyKey: /lifecycle\n" +
                "    propertyValue: 1\n" +
                "  triggeredHandler:\n" +
                "    channel: triggeredChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /triggered\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();

        // then
        assertNotNull(initialized.getProperties().get("lifecycle"));
        assertNull(initialized.getProperties().get("triggered"),
                "Triggered handler should not run from lifecycle emission");
    }

    @Test
    void shouldVerifyProcessorGeneratedChildLifecycleIsNotBridgedToParent() {
        // given
        String yaml = "name: Embedded Lifecycle\n" +
                "child:\n" +
                "  name: Inner\n" +
                "  contracts: {}\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  childBridge:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.EMBEDDED_NODE_CHANNEL + "\n" +
                "    sourcePath: /child\n" +
                "  captureChildLifecycle:\n" +
                "    channel: childBridge\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /childLifecycle\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        // when
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();
        Node childLifecycle = initialized.getProperties()
                .get("childLifecycle");

        // then
        assertNull(childLifecycle,
                "processor-generated child lifecycle delivery is local");
    }

    private static Blue orderedInitializationBlue() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        return blue;
    }

    private static Node orderedInitializationDocument(Blue blue) {
        return blue.yamlToNode(
                "name: Sample Doc\n" +
                        "contracts:\n" +
                        "  lifecycleChannel:\n" +
                        "    type:\n" +
                        "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                        "  setX:\n" +
                        "    channel: lifecycleChannel\n" +
                        "    type:\n" +
                        "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                        "    event:\n" +
                        "      type:\n" +
                        "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                        "    propertyKey: /x\n" +
                        "    propertyValue: 5\n" +
                        "  setXLater:\n" +
                        "    order: 1\n" +
                        "    channel: lifecycleChannel\n" +
                        "    type:\n" +
                        "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                        "    event:\n" +
                        "      type:\n" +
                        "        blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n" +
                        "    propertyKey: /x\n" +
                        "    propertyValue: 10\n");
    }

    private static List<String> identityShapeFixtures() {
        return Arrays.asList(
                "name: Simple Object Shape\n"
                        + "status: draft\n"
                        + "contracts: {}\n",
                "name: Simple Scalar Fields Shape\n"
                        + "count: 7\n"
                        + "active: true\n"
                        + "label: text\n"
                        + "contracts: {}\n",
                "name: Payload Only List Shape\n"
                        + "payload:\n"
                        + "  - alpha\n"
                        + "  - beta\n"
                        + "contracts: {}\n",
                "name: Nested Payload Only List Shape\n"
                        + "payload:\n"
                        + "  - - alpha\n"
                        + "    - beta\n"
                        + "  - gamma\n"
                        + "contracts: {}\n",
                "name: Metadata Bearing List Shape\n"
                        + "payload:\n"
                        + "  name: Metadata Bearing List\n"
                        + "  type:\n"
                        + "    blueId: " + BlueLanguageConstants.LIST_TYPE_BLUE_ID + "\n"
                        + "  items:\n"
                        + "    - alpha\n"
                        + "    - beta\n"
                        + "contracts: {}\n",
                "name: Object Elements List Shape\n"
                        + "rows:\n"
                        + "  - id: one\n"
                        + "    amount: 1\n"
                        + "  - id: two\n"
                        + "    amount: 2\n"
                        + "contracts: {}\n",
                "name: Scalar Elements List Shape\n"
                        + "scalars: [one, 2, true]\n"
                        + "contracts: {}\n",
                "name: Typed Scalar Elements List Shape\n"
                        + "typedScalars:\n"
                        + "  items:\n"
                        + "    - type: Integer\n"
                        + "      value: 1\n"
                        + "    - type: Text\n"
                        + "      value: two\n"
                        + "contracts: {}\n",
                "name: Empty List Control Shape\n"
                        + "emptyControl:\n"
                        + "  items:\n"
                        + "    - $empty: true\n"
                        + "    - value: tail\n"
                        + "contracts: {}\n",
                "name: BEX Operator Map Shape\n"
                        + "bex:\n"
                        + "  do:\n"
                        + "    - \"$get\": [/invoice/status]\n"
                        + "    - \"$literal\":\n"
                        + "        - [accepted, pending]\n"
                        + "contracts: {}\n",
                "name: Contracts Containing Lists Shape\n"
                        + "contracts:\n"
                        + "  lifecycleWithList:\n"
                        + "    type:\n"
                        + "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n"
                        + "    values:\n"
                        + "      - [a, b]\n"
                        + "      - {kind: c}\n",
                "name: Embedded Documents Containing Lists Shape\n"
                        + "child:\n"
                        + "  name: Embedded List Child\n"
                        + "  values:\n"
                        + "    - [a, b]\n"
                        + "  contracts: {}\n"
                        + "contracts:\n"
                        + "  embedded:\n"
                        + "    type:\n"
                        + "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                        + "    paths:\n"
                        + "      - /child\n");
    }

    private static List<InitializationIdentityObservation>
    initializeAndReload(
            Blue blue,
            List<String> fixtures) {
        List<InitializationIdentityObservation> observations =
                new ArrayList<>(fixtures.size());
        for (String fixture : fixtures) {
            observations.add(initializeAndReload(blue, fixture));
        }
        return observations;
    }

    private static InitializationIdentityObservation initializeAndReload(
            Blue blue,
            String yaml) {
        Node original = blue.yamlToNode(yaml);
        String documentBlueId =
                rootDocumentIdentityAtInitialization(blue, original);

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node canonicalDocument = result.document();
        if (canonicalDocument == null
                || isCapabilityFailure(result)) {
            return new InitializationIdentityObservation(
                    yaml,
                    documentBlueId,
                    result,
                    canonicalDocument,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        ResolvedSnapshot finalSnapshot =
                blue.resolveToSnapshot(canonicalDocument.clone());
        ResolvedSnapshot reloaded =
                blue.resolveToSnapshot(
                        blue.jsonToNode(
                                blue.nodeToJson(canonicalDocument)));
        return new InitializationIdentityObservation(
                yaml,
                documentBlueId,
                result,
                canonicalDocument,
                markerDocumentId(canonicalDocument, "/"),
                finalSnapshot.blueId(),
                reloaded.blueId(),
                blue.nodeToJson(finalSnapshot.canonicalRoot()),
                blue.nodeToJson(reloaded.canonicalRoot()),
                blue.nodeToJson(finalSnapshot.resolvedRoot()),
                blue.nodeToJson(reloaded.resolvedRoot()));
    }

    private static void assertInitializationIdentities(
            List<InitializationIdentityObservation> observations) {
        for (InitializationIdentityObservation observation :
                observations) {
            assertInitializationIdentity(observation);
        }
    }

    private static void assertInitializationIdentity(
            InitializationIdentityObservation observation) {
        assertFalse(
                isCapabilityFailure(observation.result),
                diagnosticMessage(observation.result));
        assertNotNull(
                observation.canonicalDocument,
                observation.yaml);
        assertEquals(
                observation.expectedDocumentBlueId,
                observation.markerDocumentBlueId,
                observation.yaml);
        assertProcessorLifecycleIsLocal(observation.result);
        assertEquals(
                observation.finalBlueId,
                observation.reloadedBlueId,
                observation.yaml);
        assertEquals(
                observation.finalCanonicalJson,
                observation.reloadedCanonicalJson,
                observation.yaml);
        assertEquals(
                observation.finalResolvedJson,
                observation.reloadedResolvedJson,
                observation.yaml);
    }

    private static String uncheckedInitializationId(FrozenNode node) {
        return DirectBlueIdCalculator.calculateUncheckedBlueId(node.toNode());
    }

    private static String rootDocumentIdentityAtInitialization(
            Blue blue,
            Node original) {
        Node immediatelyBeforeRootInitialization = original.clone();
        Node contracts = original.getContracts();
        Node embedded = contracts != null && contracts.getProperties() != null
                ? contracts.getProperties().get("embedded")
                : null;
        Node paths = embedded != null && embedded.getProperties() != null
                ? embedded.getProperties().get("paths")
                : null;
        if (paths != null && paths.getItems() != null) {
            for (Node pathNode : paths.getItems()) {
                String childPath = String.valueOf(pathNode.getValue());
                Node child = original.getAsNode(childPath);
                if (child == null) {
                    continue;
                }
                DocumentProcessingResult childInitialization =
                        blue.initializeDocument(child.clone());
                if (isCapabilityFailure(childInitialization)) {
                    throw new IllegalStateException(
                            diagnosticMessage(childInitialization));
                }
                Node selectedChild =
                        immediatelyBeforeRootInitialization.getAsNode(childPath);
                if (selectedChild == null) {
                    throw new IllegalStateException(
                            "Embedded initialization path is absent: "
                                    + childPath);
                }
                selectedChild.replaceWith(childInitialization.document());
            }
        }
        return blue.resolveToSnapshot(immediatelyBeforeRootInitialization)
                .frozenCanonicalRoot()
                .blueId();
    }

    private static final class InitializationIdentityObservation {
        private final String yaml;
        private final String expectedDocumentBlueId;
        private final DocumentProcessingResult result;
        private final Node canonicalDocument;
        private final String markerDocumentBlueId;
        private final String finalBlueId;
        private final String reloadedBlueId;
        private final String finalCanonicalJson;
        private final String reloadedCanonicalJson;
        private final String finalResolvedJson;
        private final String reloadedResolvedJson;

        private InitializationIdentityObservation(
                String yaml,
                String expectedDocumentBlueId,
                DocumentProcessingResult result,
                Node canonicalDocument,
                String markerDocumentBlueId,
                String finalBlueId,
                String reloadedBlueId,
                String finalCanonicalJson,
                String reloadedCanonicalJson,
                String finalResolvedJson,
                String reloadedResolvedJson) {
            this.yaml = yaml;
            this.expectedDocumentBlueId =
                    expectedDocumentBlueId;
            this.result = result;
            this.canonicalDocument = canonicalDocument;
            this.markerDocumentBlueId = markerDocumentBlueId;
            this.finalBlueId = finalBlueId;
            this.reloadedBlueId = reloadedBlueId;
            this.finalCanonicalJson = finalCanonicalJson;
            this.reloadedCanonicalJson =
                    reloadedCanonicalJson;
            this.finalResolvedJson = finalResolvedJson;
            this.reloadedResolvedJson = reloadedResolvedJson;
        }
    }

    private static String markerDocumentId(Node document, String scope) {
        String prefix = "/".equals(scope) ? "" : scope;
        Node initialDocument = document.getAsNode(
                prefix + "/contracts/initialized/document");
        return initialDocument != null
                ? DirectBlueIdCalculator.calculateBlueId(initialDocument)
                : null;
    }

    private static String lifecycleDocumentId(Node event) {
        Node document = event != null && event.getProperties() != null
                ? event.getProperties().get("document")
                : null;
        return document != null
                ? DirectBlueIdCalculator.calculateBlueId(document)
                : null;
    }

    private static void assertProcessorLifecycleIsLocal(
            DocumentProcessingResult result) {
        assertTrue(result.events().isEmpty(),
                "processor-generated initialization lifecycle is local");
    }

    @TypeBlueId(CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID)
    public static final class CaptureLifecycleDocumentId extends HandlerContract {
        private String propertyKey;

        public String getPropertyKey() {
            return propertyKey;
        }

        public void setPropertyKey(String propertyKey) {
            this.propertyKey = propertyKey;
        }
    }

    private static final class CaptureLifecycleDocumentIdProcessor
            implements HandlerProcessor<CaptureLifecycleDocumentId> {
        @Override
        public Class<CaptureLifecycleDocumentId> contractType() {
            return CaptureLifecycleDocumentId.class;
        }

        @Override
        public void execute(CaptureLifecycleDocumentId contract, ProcessorExecutionContext context) {
            String documentId = lifecycleDocumentId(context.event());
            if (documentId == null) {
                throw new IllegalStateException(
                        "Lifecycle event missing exact document");
            }
            String propertyKey = contract.getPropertyKey() != null
                    ? contract.getPropertyKey()
                    : "/capturedDocumentId";
            context.applyFrozenPatch(FrozenJsonPatch.add(
                    context.resolvePointer(propertyKey),
                    FrozenNode.fromNode(new Node().value(documentId))));
        }
    }
}
