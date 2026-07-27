package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.TypeBlueId;
import blue.language.provider.BasicNodeProvider;
import blue.language.processor.contracts.RemovePropertyContractProcessor;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorInitializationTest {

    private static final String CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID =
            "n1dTwJjYLh4mvRbrBiQ56fLj8skq8pGo8eyPhmTtBJH";

    @Test
    void initializeDocumentKeepsProcessorLifecycleLocalAndWritesMarker() {
        Blue blue = ProcessorTestSupport.blue();
        Node original = blue.yamlToNode("name: Minimal Doc\n" +
                "contracts: {}\n");
        String expectedDocumentId =
                blue.resolveToSnapshot(original.clone())
                        .blueId();

        DocumentProcessingResult result = blue.initializeDocument(original);

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertNull(diagnosticCategory(result), diagnosticMessage(result));
        assertTrue(blue.isInitialized(result.document()));
        assertProcessorLifecycleIsLocal(result);

        Node markerDocId = result.document()
                .getContracts()
                .getProperties()
                .get("initialized")
                .getProperties()
                .get("documentId");
        assertNotNull(markerDocId);
        assertEquals(expectedDocumentId,
                markerDocId.getValue());
    }

    @Test
    void initializationMarkerUsesDirectWriteWithoutApplicationPatchMetrics() {
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        Node original = blue.yamlToNode("name: Minimal Doc\n" +
                "contracts: {}\n");
        ResolvedSnapshot preInitialization = blue.resolveToSnapshot(original.clone());
        String expectedDocumentId = preInitialization.frozenCanonicalRoot().blueId();

        DocumentProcessingResult result = blue.initializeDocument(original);

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        Node initialized = result.document()
                .getContracts()
                .getProperties()
                .get("initialized");
        assertEquals(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER,
                initialized.getType().getBlueId());
        assertEquals(expectedDocumentId,
                initialized.getProperties().get("documentId").getValue());
        assertProcessorLifecycleIsLocal(result);

        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(0L, snapshot.counter(
                "mutablePatchValuesFrozenBySource.PROCESSOR_INITIALIZATION_MARKER"), snapshot.toString());
        assertEquals(0L, snapshot.counter("frozenPatchValuesAccepted"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerIncrementalResolutions"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbackReason.CONTRACTS_CHANGED"), snapshot.toString());
        assertEquals(1L, snapshot.counter("initializationDocumentIdContentBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdCanonicalMaterializations"), snapshot.toString());
    }

    @Test
    void snapshotBackedInitializationMarkerUsesDirectWriteWithoutPatchResolution() {
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        ResolvedSnapshot preInitialization = blue.resolveToSnapshot(blue.yamlToNode(
                "name: Snapshot Minimal Doc\n" +
                        "contracts: {}\n"));

        DocumentProcessingResult result = blue.initializeDocument(preInitialization);

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertProcessorLifecycleIsLocal(result);
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(0L, snapshot.counter("frozenPatchValuesAccepted"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerIncrementalResolutions"), snapshot.toString());
        assertEquals(0L, snapshot.counter("incrementalSnapshotResolutions"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbacks"), snapshot.toString());
        assertEquals(1L, snapshot.counter("initializationDocumentIdContentBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdCanonicalMaterializations"), snapshot.toString());
    }

    @Test
    void initializationDocumentIdUsesContentBlueIdWhenUncheckedIdentityDiffers() {
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        Node original = blue.yamlToNode(
                "name: Nested List Divergence\n" +
                        "bex:\n" +
                        "  do:\n" +
                        "    - - 1\n" +
                        "      - 2\n" +
                        "contracts: {}\n");
        ResolvedSnapshot preInitialization = blue.resolveToSnapshot(original.clone());
        String canonical = preInitialization.frozenCanonicalRoot().blueId();
        String unchecked = uncheckedInitializationId(preInitialization.frozenCanonicalRoot());
        assertNotEquals(canonical, unchecked,
                "canonical=" + canonical + ", unchecked=" + unchecked);

        DocumentProcessingResult result = blue.initializeDocument(original);

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        String markerDocumentId = markerDocumentId(result.document(), "/");
        assertEquals(canonical, markerDocumentId,
                "canonical=" + canonical + ", unchecked=" + unchecked);
        assertProcessorLifecycleIsLocal(result);
        assertNotEquals(unchecked, markerDocumentId);
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(0L, snapshot.counter("frozenPatchValuesAccepted"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbackReason.CONTRACTS_CHANGED"), snapshot.toString());
    }

    @Test
    void initializationDocumentIdUsesContentBlueIdAcrossIdentityShapes() {
        Blue blue = ProcessorTestSupport.blue();
        List<String> fixtures = new ArrayList<>(Arrays.asList(
                "name: Simple Object Shape\n" +
                        "status: draft\n" +
                        "contracts: {}\n",
                "name: Simple Scalar Fields Shape\n" +
                        "count: 7\n" +
                        "active: true\n" +
                        "label: text\n" +
                        "contracts: {}\n",
                "name: Payload Only List Shape\n" +
                        "payload:\n" +
                        "  - alpha\n" +
                        "  - beta\n" +
                        "contracts: {}\n",
                "name: Nested Payload Only List Shape\n" +
                        "payload:\n" +
                        "  - - alpha\n" +
                        "    - beta\n" +
                        "  - gamma\n" +
                        "contracts: {}\n",
                "name: Metadata Bearing List Shape\n" +
                        "payload:\n" +
                        "  name: Metadata Bearing List\n" +
                        "  type:\n" +
                        "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                        "  items:\n" +
                        "    - alpha\n" +
                        "    - beta\n" +
                        "contracts: {}\n",
                "name: Object Elements List Shape\n" +
                        "rows:\n" +
                        "  - id: one\n" +
                        "    amount: 1\n" +
                        "  - id: two\n" +
                        "    amount: 2\n" +
                        "contracts: {}\n",
                "name: Scalar Elements List Shape\n" +
                        "scalars: [one, 2, true]\n" +
                        "contracts: {}\n",
                "name: Typed Scalar Elements List Shape\n" +
                        "typedScalars:\n" +
                        "  items:\n" +
                        "    - type: Integer\n" +
                        "      value: 1\n" +
                        "    - type: Text\n" +
                        "      value: two\n" +
                        "contracts: {}\n",
                "name: Empty List Control Shape\n" +
                        "emptyControl:\n" +
                        "  items:\n" +
                        "    - $empty: true\n" +
                        "    - value: tail\n" +
                        "contracts: {}\n",
                "name: BEX Operator Map Shape\n" +
                        "bex:\n" +
                        "  do:\n" +
                        "    - \"$get\": [/invoice/status]\n" +
                        "    - \"$literal\":\n" +
                        "        - [accepted, pending]\n" +
                        "contracts: {}\n",
                "name: Contracts Containing Lists Shape\n" +
                        "contracts:\n" +
                        "  lifecycleWithList:\n" +
                        "    type:\n" +
                        "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                        "    values:\n" +
                        "      - [a, b]\n" +
                        "      - {kind: c}\n",
                "name: Embedded Documents Containing Lists Shape\n" +
                        "child:\n" +
                        "  name: Embedded List Child\n" +
                        "  values:\n" +
                        "    - [a, b]\n" +
                        "  contracts: {}\n" +
                        "contracts:\n" +
                        "  embedded:\n" +
                        "    type:\n" +
                        "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                        "    paths:\n" +
                        "      - /child\n"));

        for (String yaml : fixtures) {
            assertInitializationUsesContentBlueIdAndReloads(blue, yaml);
        }

        BasicNodeProvider provider = new BasicNodeProvider();
        Blue previousBlue = ProcessorTestSupport.blue(provider);
        Node previous = previousBlue.yamlToNode(
                "items:\n" +
                        "  - previous-a\n" +
                        "  - previous-b\n");
        String previousBlueId = BlueIdCalculator.calculateBlueId(previous.getItems());
        provider.addListAndItsItems(previous.getItems());
        assertInitializationUsesContentBlueIdAndReloads(previousBlue,
                "name: Previous List Control Shape\n" +
                        "history:\n" +
                        "  type:\n" +
                        "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                        "  mergePolicy: append-only\n" +
                        "  items:\n" +
                        "    - $previous:\n" +
                        "        blueId: " + previousBlueId + "\n" +
                        "    - after\n" +
                        "contracts: {}\n");
    }

    @Test
    void bexShapedNestedListsUseContentBlueIdInitializationIdentity() {
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

        for (String yaml : fixtures) {
            assertInitializationUsesContentBlueIdAndReloads(blue, yaml);
        }
    }

    @Test
    void embeddedScopeInitializationDocumentIdsUseTheirOwnContentPreInitializationIdentity() {
        BasicNodeProvider identityProvider = new BasicNodeProvider();
        identityProvider.addSingleNodes(new Node().name("CaptureLifecycleDocumentId"));
        Blue blue = ProcessorTestSupport.blue(identityProvider);
        blue.registerExternalContractType(CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID,
                new Node().name("CaptureLifecycleDocumentId"),
                new CaptureLifecycleDocumentIdProcessor());
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
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
                        "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                        "    captureChildId:\n" +
                        "      channel: lifecycle\n" +
                        "      type:\n" +
                        "        blueId: " + CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID + "\n" +
                        "      propertyKey: /childLifecycleDocumentId\n" +
                        "contracts:\n" +
                        "  embedded:\n" +
                        "    type:\n" +
                        "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                        "    paths:\n" +
                        "      - /child\n" +
                        "  lifecycle:\n" +
                        "    type:\n" +
                        "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                        "  captureRootId:\n" +
                        "    channel: lifecycle\n" +
                        "    type:\n" +
                        "      blueId: " + CAPTURE_LIFECYCLE_DOCUMENT_ID_BLUE_ID + "\n" +
                        "    propertyKey: /rootLifecycleDocumentId\n");
        Node standaloneChildBeforeLifecycle = original.getAsNode("/child").clone();
        ResolvedSnapshot childPreInitialization = blue.resolveToSnapshot(standaloneChildBeforeLifecycle);
        String childContentBlueId = childPreInitialization.blueId();
        String childUnchecked = uncheckedInitializationId(childPreInitialization.frozenCanonicalRoot());
        assertNotEquals(childContentBlueId, childUnchecked,
                "canonical=" + childContentBlueId + ", unchecked=" + childUnchecked);

        Node rootAfterChildPhase1 = original.clone();
        Node childAfterPhase1 = rootAfterChildPhase1.getAsNode("/child");
        childAfterPhase1.properties("childLifecycleDocumentId", new Node().value(childContentBlueId));
        childAfterPhase1.getContracts().properties(
                "initialized", ProcessorMarkerFactory.initialized(childContentBlueId).toNode());
        String rootContentBlueId = blue.calculateSemanticBlueId(rootAfterChildPhase1);

        DocumentProcessingResult result = blue.initializeDocument(original);

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        Node initialized = result.document();
        assertEquals(rootContentBlueId, markerDocumentId(initialized, "/"));
        assertEquals(childContentBlueId, markerDocumentId(initialized, "/child"));
        assertEquals(rootContentBlueId, initialized.getAsText("/rootLifecycleDocumentId"));
        assertEquals(childContentBlueId, initialized.getAsText("/child/childLifecycleDocumentId"));
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(2L, snapshot.counter("frozenPatchValuesAccepted"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("fullSnapshotFallbackReason.CONTRACTS_CHANGED"), snapshot.toString());
        assertEquals(2L, snapshot.counter("initializationDocumentIdContentBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("initializationDocumentIdCanonicalMaterializations"), snapshot.toString());
    }

    @Test
    void nonObjectEmbeddedChildTerminatesDuringPhase1WithoutInitialization() {
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        Node original = blue.yamlToNode(
                "name: Non Object Embedded Child\n" +
                        "payload:\n" +
                        "  - - 1\n" +
                        "    - 2\n" +
                        "child: scalar\n" +
                        "contracts:\n" +
                        "  embedded:\n" +
                        "    type:\n" +
                        "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                        "    paths:\n" +
                        "      - /child\n");
        String exactInput = original.toString();
        DocumentProcessingResult result = blue.initializeDocument(original);

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertFalse(result.commits());
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result));
        assertEquals(exactInput,
                result.document().toString());
        assertNull(result.document().getContracts().getProperties().get("initialized"));
        assertTrue(result.events().isEmpty());
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.counter("mutablePatchValuesFrozen"), snapshot.toString());
        assertEquals(0L, snapshot.counter("patchImpactProcessorManagedState"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorManagedMarkerPatches"), snapshot.toString());
    }

    @Test
    void initializesDocumentAndExecutesHandlersInOrder() {
        String yaml = "name: Sample Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  setX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 5\n" +
                "  setXLater:\n" +
                "    order: 1\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 10\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);
        assertFalse(blue.isInitialized(original));

        DocumentProcessingResult uninitializedProcessResult =
                blue.processDocument(
                        original.clone(),
                        new Node().value("external"));
        assertEquals(ProcessorStatus.NO_MATCH,
                uninitializedProcessResult.status());
        assertFalse(uninitializedProcessResult.commits());
        assertFalse(blue.isInitialized(
                uninitializedProcessResult.document()));
        assertTrue(uninitializedProcessResult.events().isEmpty());
        assertEquals(original.toString(),
                uninitializedProcessResult.document().toString());

        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node initialized = initResult.document();

        assertTrue(blue.isInitialized(initialized));

        assertProcessorLifecycleIsLocal(initResult);
        Node markerDocId = initialized.getContracts()
                .getProperties()
                .get("initialized")
                .getProperties()
                .get("documentId");
        assertNotNull(markerDocId);

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

        DocumentProcessingResult postInitProcessResult =
                blue.processDocument(
                        initialized,
                        new Node().value("external"));
        assertEquals(ProcessorStatus.NO_MATCH,
                postInitProcessResult.status());
        assertFalse(postInitProcessResult.commits());
        Node processed = postInitProcessResult.document();
        assertEquals(new BigInteger("10"), processed.getProperties().get("x").getValue());
        assertEquals(initialized.toString(),
                processed.toString());

        assertTrue(postInitProcessResult.events().isEmpty());

        assertNull(original.getProperties() != null ? original.getProperties().get("x") : null);
    }

    @Test
    void initializationHandlesCustomPaths() {
        String yaml = "name: Custom Path Doc\n" +
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  setRoot:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
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
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
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
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
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
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
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
        assertTrue(isCapabilityFailure(result), "Initialization should fail with must-understand");
        assertEquals(0L, result.totalGas());
        assertTrue(result.events().isEmpty());
        assertEquals(originalJson, blue.nodeToJson(result.document()));
    }

    @Test
    void incompatibleInitializationMarkerOutsideParticipatingClosureKeepsNoMatch() {
        String yaml = "name: Bad Doc\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        DocumentProcessingResult result =
                blue.processDocument(
                        document,
                        new Node().value("event"));

        assertEquals(ProcessorStatus.NO_MATCH,
                result.status());
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(document.toString(),
                result.document().toString());
        assertNull(diagnosticMessage(result));
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
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  removeX:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 2REa15BDY5EWq4tJsbUaBwhhTG2xSdk2ZyFL1aCpqTVF\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    propertyKey: /x\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new RemovePropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);

        assertTrue(original.getProperties().containsKey("x"));

        DocumentProcessingResult result = blue.initializeDocument(original);
        Node processed = result.document();

        assertFalse(processed.getProperties() != null && processed.getProperties().containsKey("x"));
        assertProcessorLifecycleIsLocal(result);

        assertTrue(original.getProperties().containsKey("x"));
    }

    @Test
    void checkpointBeforeInitializationIsRejected() {
        String yaml = "name: Invalid Doc\n" +
                "contracts:\n" +
                "  checkpoint:\n" +
                "    type:\n" +
                "      blueId: 9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR\n";

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
                "      blueId: 9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR\n" +
                "  extraCheckpoint:\n" +
                "    type:\n" +
                "      blueId: 9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR\n";

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
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  triggeredChannel:\n" +
                "    type:\n" +
                "      blueId: DRxc8GkSGPbdENdB8ZK976i1Jzc6M1QdG8UsVMHcqQcf\n" +
                "  handleLifecycle:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
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
    void processorGeneratedChildLifecycleIsNotBridgedToParent() {
        String yaml = "name: Embedded Lifecycle\n" +
                "child:\n" +
                "  name: Inner\n" +
                "  contracts: {}\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  childBridge:\n" +
                "    type:\n" +
                "      blueId: 7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN\n" +
                "    sourcePath: /child\n" +
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
        assertNull(childLifecycle,
                "processor-generated child lifecycle delivery is local");
    }

    private static void assertInitializationUsesContentBlueIdAndReloads(Blue blue, String yaml) {
        Node original = blue.yamlToNode(yaml);
        String contentBlueId = independentRootInitializationContentBlueId(blue, original);

        DocumentProcessingResult result = blue.initializeDocument(original);

        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        Node canonicalDocument = result.document();
        assertNotNull(canonicalDocument, yaml);
        assertEquals(contentBlueId, markerDocumentId(canonicalDocument, "/"), yaml);
        assertProcessorLifecycleIsLocal(result);
        ResolvedSnapshot finalSnapshot = blue.resolveToSnapshot(canonicalDocument.clone());
        ResolvedSnapshot reloaded = blue.resolveToSnapshot(
                blue.jsonToNode(blue.nodeToJson(canonicalDocument)));
        assertEquals(finalSnapshot.blueId(), reloaded.blueId(), yaml);
        assertEquals(blue.nodeToJson(finalSnapshot.canonicalRoot()),
                blue.nodeToJson(reloaded.canonicalRoot()), yaml);
        assertEquals(blue.nodeToJson(finalSnapshot.resolvedRoot()),
                blue.nodeToJson(reloaded.resolvedRoot()), yaml);
    }

    private static String independentRootInitializationContentBlueId(Blue blue, Node original) {
        Node preRootLifecycle = original.clone();
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
                Node childSource = original.getAsNode(childPath).clone();
                String childContentBlueId = blue.calculateSemanticBlueId(childSource);
                Node childAfterPhase1 = preRootLifecycle.getAsNode(childPath);
                if (childAfterPhase1.getContracts() == null) {
                    childAfterPhase1.contracts(new Node());
                }
                childAfterPhase1.getContracts().properties(
                        "initialized", ProcessorMarkerFactory.initialized(childContentBlueId).toNode());
            }
        }
        return blue.calculateSemanticBlueId(preRootLifecycle);
    }

    private static String uncheckedInitializationId(FrozenNode node) {
        return BlueIdCalculator.calculateUncheckedBlueId(node.toNode());
    }

    private static String markerDocumentId(Node document, String scope) {
        String prefix = "/".equals(scope) ? "" : scope;
        return document.getAsText(prefix + "/contracts/initialized/documentId");
    }

    private static String lifecycleDocumentId(Node event) {
        Node documentId = event != null && event.getProperties() != null
                ? event.getProperties().get("documentId")
                : null;
        Object value = documentId != null ? documentId.getValue() : null;
        return value != null ? String.valueOf(value) : null;
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
                throw new IllegalStateException("Lifecycle event missing documentId");
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
