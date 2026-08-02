package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.BlueLanguageConstants;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeSourceProjectionTest {

    @Test
    void shouldVerifyExactSnapshotIdentityDoesNotInvokeStandaloneProjectionOrReresolution() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        ResolvedSnapshot authoritative = blue.resolveToSnapshot(new Node()
                .name("Authoritative Snapshot Scope")
                .properties("state", text("captured")));
        SuccessfulButAlteredSnapshotManager manager =
                new SuccessfulButAlteredSnapshotManager(authoritative);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                authoritative, null, manager);

        // when
        FrozenNode actual =
                runtime.capturePreInitializationScopeDocument("/");
        int transientCallsAfterCapture =
                manager.fromDocumentTransientCalls;
        ResolvedSnapshot altered =
                manager.fromDocumentTransient(
                        authoritative.canonicalRoot());

        // then
        assertTrue(authoritative.frozenCanonicalRoot()
                .sameResolvedStructure(actual));
        assertNull(manager.capturedSnapshot,
                "exact Node identity must not invoke the Content-BlueId projection hook");
        assertEquals(0, transientCallsAfterCapture,
                "canonical identity input must not be re-resolved merely to capture snapshot state");
        assertFalse(altered.frozenResolvedRoot()
                        .sameResolvedStructure(authoritative.frozenResolvedRoot()),
                "the forbidden re-resolution path is intentionally successful but different");
        assertEquals(1, manager.fromDocumentTransientCalls);
    }

    @Test
    void shouldVerifyInheritedListControlsProjectAsARealStandaloneSourceOverlay() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Controlled Scope Type\n"
                        + "list:\n"
                        + "  type: List\n"
                        + "  mergePolicy: positional\n"
                        + "  items:\n"
                        + "    - A\n"
                        + "    - B");
        String scopeTypeBlueId = provider.getBlueIdByName("Controlled Scope Type");
        Blue blue = ProcessorTestSupport.blue(provider);
        Node inheritedItems = blue.resolve(new Node().type(reference(scopeTypeBlueId)))
                .getAsNode("/list");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(inheritedItems.getItems());
        provider.addListAndItsItems(inheritedItems.getItems());
        Node source = blue.yamlToNode(
                "type:\n"
                        + "  blueId: " + scopeTypeBlueId + "\n"
                        + "list:\n"
                        + "  type: List\n"
                        + "  mergePolicy: positional\n"
                        + "  items:\n"
                        + "    - $previous:\n"
                        + "        blueId: " + previousBlueId + "\n"
                        + "    - $pos: 1\n"
                        + "      value: C");
        ResolvedSnapshot captured = blue.resolveToSnapshot(source.clone());
        String expected = blue.calculateSourceDocumentBlueId(source);

        // when
        ScopeSourceProjection nodeProjection = ScopeSourceProjection.project(
                "/",
                FrozenNode.fromResolvedNode(source),
                captured,
                blue.getDocumentProcessor().snapshotManager());
        ScopeSourceProjection snapshotProjection = ScopeSourceProjection.project(
                "/",
                captured.frozenCanonicalRoot(),
                captured,
                blue.getDocumentProcessor().snapshotManager());
        DocumentProcessingResult nodeResult =
                blue.initializeDocument(source.clone());
        DocumentProcessingResult snapshotResult =
                blue.initializeDocument(captured);
        List<FrozenNode> nodeItems =
                nodeProjection.standaloneSource()
                        .property("list").getItems();
        List<FrozenNode> snapshotItems =
                snapshotProjection.standaloneSource()
                        .property("list").getItems();

        // then
        assertEquals(expected, nodeProjection.contentBlueId());
        assertEquals(expected, snapshotProjection.contentBlueId());
        assertTrue(nodeProjection.standaloneSnapshot().frozenResolvedRoot()
                .sameResolvedStructure(captured.frozenResolvedRoot()));
        assertTrue(snapshotProjection.standaloneSnapshot().frozenResolvedRoot()
                .sameResolvedStructure(captured.frozenResolvedRoot()));
        assertNotNull(nodeItems);
        assertEquals(previousBlueId, nodeItems.get(0).getPreviousBlueId(),
                "Node-backed capture must retain the authored anchor provenance");
        assertEquals(1, snapshotItems.size(),
                "snapshot projection must not invent an external anchor dependency");
        assertEquals(Integer.valueOf(1), snapshotItems.get(0).getPosition());

        assertInitializationIdentity(nodeResult, expected);
        assertInitializationIdentity(snapshotResult, expected);
    }

    @Test
    void shouldVerifySnapshotProjectionDoesNotRequireSyntheticPreviousListProviderContent() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Embedded Positional List Scope Type\n"
                        + "list:\n"
                        + "  type: List\n"
                        + "  mergePolicy: positional\n"
                        + "  items:\n"
                        + "    - A\n"
                        + "    - B");
        String scopeTypeBlueId = provider.getBlueIdByName(
                "Embedded Positional List Scope Type");
        Blue blue = ProcessorTestSupport.blue(provider);
        Node source = blue.yamlToNode(
                "type:\n"
                        + "  blueId: " + scopeTypeBlueId + "\n"
                        + "list:\n"
                        + "  items:\n"
                        + "    - $pos: 1\n"
                        + "      value: C");
        ResolvedSnapshot captured = blue.resolveToSnapshot(source.clone());
        String expected = blue.calculateSourceDocumentBlueId(source);

        // when
        ScopeSourceProjection nodeProjection = ScopeSourceProjection.project(
                "/",
                FrozenNode.fromResolvedNode(source),
                captured,
                blue.getDocumentProcessor().snapshotManager());
        ScopeSourceProjection snapshotProjection = ScopeSourceProjection.project(
                "/",
                captured.frozenCanonicalRoot(),
                captured,
                blue.getDocumentProcessor().snapshotManager());
        DocumentProcessingResult nodeResult =
                blue.initializeDocument(source.clone());
        DocumentProcessingResult snapshotResult =
                blue.initializeDocument(captured);

        // then
        assertEquals(expected, nodeProjection.contentBlueId());
        assertEquals(expected, snapshotProjection.contentBlueId());
        assertTrue(snapshotProjection.standaloneSnapshot().frozenResolvedRoot()
                .sameResolvedStructure(captured.frozenResolvedRoot()));
        assertInitializationIdentity(nodeResult, captured.blueId());
        assertInitializationIdentity(snapshotResult, captured.blueId());
    }

    @Test
    void shouldVerifySnapshotProjectionRestoresPureReferenceInsideInheritedListReplacement() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Reference List Scope Type\n"
                        + "list:\n"
                        + "  type: List\n"
                        + "  mergePolicy: positional\n"
                        + "  items:\n"
                        + "    - A\n"
                        + "    - B");
        Node referenced = new Node()
                .name("Replacement Entry")
                .properties("payload", text("verified"));
        provider.addSingleNodes(referenced);
        String scopeTypeBlueId = provider.getBlueIdByName("Reference List Scope Type");
        String referencedBlueId = provider.getBlueIdByName("Replacement Entry");
        Blue blue = ProcessorTestSupport.blue(provider);
        Node inheritedList = blue.resolve(new Node().type(reference(scopeTypeBlueId)))
                .getAsNode("/list");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(inheritedList.getItems());
        provider.addListAndItsItems(inheritedList.getItems());
        Node source = blue.yamlToNode(
                "type:\n"
                        + "  blueId: " + scopeTypeBlueId + "\n"
                        + "list:\n"
                        + "  type: List\n"
                        + "  mergePolicy: positional\n"
                        + "  items:\n"
                        + "    - $previous:\n"
                        + "        blueId: " + previousBlueId + "\n"
                        + "    - $pos: 1\n"
                        + "      $replace:\n"
                        + "        blueId: " + referencedBlueId);
        ResolvedSnapshot captured = blue.resolveToSnapshot(source.clone());
        String expected = blue.calculateSourceDocumentBlueId(source);

        // when
        ScopeSourceProjection projection = ScopeSourceProjection.project(
                "/",
                captured.frozenCanonicalRoot(),
                captured,
                blue.getDocumentProcessor().snapshotManager());
        DocumentProcessingResult nodeResult =
                blue.initializeDocument(source.clone());
        DocumentProcessingResult snapshotResult =
                blue.initializeDocument(captured);
        FrozenNode replacement =
                projection.standaloneSource()
                        .property("list")
                        .getItems().get(0)
                        .property("$replace");

        // then
        assertEquals(expected, projection.contentBlueId());
        assertTrue(projection.standaloneSnapshot().frozenResolvedRoot()
                .sameResolvedStructure(captured.frozenResolvedRoot()));
        assertTrue(replacement.isReferenceOnly());
        assertEquals(referencedBlueId, replacement.getReferenceBlueId());

        assertInitializationIdentity(nodeResult, captured.blueId());
        assertInitializationIdentity(snapshotResult, captured.blueId());
    }

    @Test
    void shouldVerifyEmbeddedParentTypedScopeKeepsListsLabelsAndReferencesAcrossNodeAndSnapshotInputs() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node referenced = new Node()
                .name("Combined Projection Reference")
                .properties("payload", text("verified"));
        Node referencedContract = new Node()
                .name("Combined Projection Lifecycle Channel")
                .type(reference(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL));
        provider.addSingleNodes(referenced, referencedContract);
        String referencedBlueId = provider.getBlueIdByName(referenced.getName());
        String referencedContractBlueId = provider.getBlueIdByName(referencedContract.getName());

        Node childType = new Node()
                .name("Combined Embedded Child Type")
                .description("Combined embedded description")
                .properties("entries", new Node()
                        .type(reference(BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                        .mergePolicy("positional")
                        .items(Arrays.asList(
                                new Node()
                                        .name("Equal Nested Label")
                                        .properties("nested", new Node().items(Arrays.asList(
                                                text("inherited-a"), text("inherited-b")))),
                                text("inherited-tail"))));
        provider.addSingleNodes(childType);
        String childTypeBlueId = provider.getBlueIdByName(childType.getName());

        Node rootType = new Node()
                .name("Combined Embedded Root Type")
                .properties("child", new Node().type(reference(childTypeBlueId)));
        provider.addSingleNodes(rootType);
        String rootTypeBlueId = provider.getBlueIdByName(rootType.getName());

        Blue blue = ProcessorTestSupport.blue(provider);
        List<Node> inheritedItems = blue.resolve(new Node().type(reference(childTypeBlueId)))
                .getAsNode("/entries").getItems();
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(inheritedItems);
        provider.addListAndItsItems(inheritedItems);
        Node selectedChild = new Node()
                .properties("entries", new Node()
                        .type(reference(BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                        .mergePolicy("positional")
                        .items(Arrays.asList(
                        new Node().previousBlueId(previousBlueId),
                        new Node().position(0).properties("$replace", new Node()
                                .name("Equal Nested Label")
                                .properties("nested", new Node().items(Arrays.asList(
                                        text("inherited-a"), text("inherited-b"))))
                                .properties("selected", text("local"))),
                        reference(referencedBlueId))))
                .contracts(new Node().properties(
                        "referenceEvidence", reference(referencedContractBlueId)));
        Node source = new Node()
                .type(reference(rootTypeBlueId))
                .properties("child", selectedChild)
                .contracts(new Node().properties("embedded", new Node()
                        .type(reference(RuntimeBlueIds.PROCESS_EMBEDDED))
                        .properties("paths", new Node().items(Arrays.asList(text("/child"))))));
        Node standaloneChild = selectedChild.clone().type(reference(childTypeBlueId));
        String expected = blue.calculateSourceDocumentBlueId(standaloneChild);
        ResolvedSnapshot captured = blue.resolveToSnapshot(source.clone());

        // when
        ScopeSourceProjection snapshotProjection = ScopeSourceProjection.project(
                "/child",
                captured.canonicalAt("/child"),
                captured,
                blue.getDocumentProcessor().snapshotManager());
        DocumentProcessingResult nodeResult = blue.initializeDocument(source.clone());
        DocumentProcessingResult snapshotResult = blue.initializeDocument(captured);
        String exactChildIdentity =
                captured.canonicalAt("/child").blueId();

        // then
        assertEquals(expected, snapshotProjection.contentBlueId());
        assertTrue(snapshotProjection.standaloneSnapshot().frozenResolvedRoot()
                .sameResolvedStructure(captured.resolvedAt("/child")));
        assertScopeInitializationIdentity(
                nodeResult, "/child", exactChildIdentity);
        assertScopeInitializationIdentity(
                snapshotResult, "/child", exactChildIdentity);
    }

    @Test
    void shouldVerifyProviderBackedPureReferenceAtSelectedRootRetainsItsSourceProvenance() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node referencedScope = new Node()
                .name("Referenced Scope")
                .description("Provider-backed selected root")
                .properties("state", text("ready"));
        provider.addSingleNodes(referencedScope);
        String referencedBlueId = provider.getBlueIdByName(referencedScope.getName());
        Blue blue = ProcessorTestSupport.blue(provider);
        ResolvedSnapshot captured = blue.resolveToSnapshot(reference(referencedBlueId));

        // when
        ScopeSourceProjection projection = ScopeSourceProjection.project(
                "/",
                captured.frozenCanonicalRoot(),
                captured,
                blue.getDocumentProcessor().snapshotManager());
        DocumentProcessingResult nodeResult =
                blue.initializeDocument(reference(referencedBlueId));
        DocumentProcessingResult snapshotResult =
                blue.initializeDocument(captured);

        // then
        assertTrue(projection.standaloneSource().isReferenceOnly());
        assertEquals(referencedBlueId, projection.standaloneSource().getReferenceBlueId());
        assertEquals(referencedBlueId, projection.contentBlueId());
        assertTrue(projection.standaloneSnapshot().frozenResolvedRoot()
                .sameResolvedStructure(captured.frozenResolvedRoot()));

        assertInvalidProcessingDocument(nodeResult);
        assertInvalidProcessingDocument(snapshotResult);
    }

    @Test
    void shouldVerifyProtocolIdentityPreservesPureReferencesInPropertyListAndContracts() {
        // given
        Node referencedPayload = new Node()
                .name("Protocol Reference Payload")
                .properties("payload", text("verified"));
        Node referencedLifecycleChannel = new Node()
                .name("Protocol Reference Lifecycle Channel")
                .type(reference(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL));
        String payloadBlueId = DirectBlueIdCalculator.calculateBlueId(referencedPayload);
        String channelBlueId = DirectBlueIdCalculator.calculateBlueId(referencedLifecycleChannel);
        Node source = new Node()
                .properties("propertyReference", reference(payloadBlueId))
                .properties("list", new Node().items(Arrays.asList(
                        reference(payloadBlueId), text("tail"))))
                .contracts(new Node().properties(
                        "referencedLifecycle", reference(channelBlueId)));

        Blue oracle = ProcessorTestSupport.blue(referenceProvider(
                referencedPayload, referencedLifecycleChannel));
        String expected = oracle.calculateSourceDocumentBlueId(source.clone());

        Blue projectionBlue = ProcessorTestSupport.blue(referenceProvider(
                referencedPayload, referencedLifecycleChannel));
        ResolvedSnapshot captured = projectionBlue.resolveToSnapshot(source.clone());
        Blue nodeExecution = ProcessorTestSupport.blue(referenceProvider(
                referencedPayload, referencedLifecycleChannel));
        Blue snapshotProducer = ProcessorTestSupport.blue(referenceProvider(
                referencedPayload, referencedLifecycleChannel));
        ResolvedSnapshot snapshotInput =
                snapshotProducer.resolveToSnapshot(source.clone());
        Blue snapshotExecution = ProcessorTestSupport.blue(referenceProvider(
                referencedPayload, referencedLifecycleChannel));

        // when
        ScopeSourceProjection projection = ScopeSourceProjection.project(
                "/",
                captured.frozenCanonicalRoot(),
                captured,
                projectionBlue.getDocumentProcessor().snapshotManager());
        DocumentProcessingResult firstNodeResult =
                nodeExecution.initializeDocument(source.clone());
        DocumentProcessingResult secondNodeResult =
                nodeExecution.initializeDocument(source.clone());
        DocumentProcessingResult firstSnapshotResult =
                snapshotExecution.initializeDocument(snapshotInput);
        DocumentProcessingResult secondSnapshotResult =
                snapshotExecution.initializeDocument(snapshotInput);

        // then
        assertEquals(expected, projection.contentBlueId());
        assertTrue(projection.standaloneSnapshot().frozenResolvedRoot()
                .sameResolvedStructure(captured.frozenResolvedRoot()));
        assertTrue(projection.standaloneSource().property("propertyReference").isReferenceOnly());
        assertTrue(projection.standaloneSource().property("list")
                .getItems().get(0).isReferenceOnly());
        assertTrue(projection.standaloneSource().getContracts()
                .property("referencedLifecycle").isReferenceOnly());
        assertInitializationIdentity(
                firstNodeResult,
                captured.blueId());
        assertInitializationIdentity(
                secondNodeResult,
                captured.blueId());
        assertInitializationIdentity(
                firstSnapshotResult,
                snapshotInput.blueId());
        assertInitializationIdentity(
                secondSnapshotResult,
                snapshotInput.blueId());
    }

    @Test
    void shouldPropagateUnavailablePureReferenceContractBeforeInitiation() {
        // given
        Node referencedLifecycleChannel = new Node()
                .name("Unavailable Protocol Reference Lifecycle Channel")
                .type(reference(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL));
        String channelBlueId = DirectBlueIdCalculator.calculateBlueId(referencedLifecycleChannel);
        Node source = new Node().contracts(new Node().properties(
                "referencedLifecycle", reference(channelBlueId)));

        // when
        Throwable failure = captureFailure(
                () -> ProcessorTestSupport.blue(
                        blueId -> null).initializeDocument(source.clone()));
        BlueLanguageErrorCategory category =
                failure instanceof IllegalArgumentException
                        ? BlueLanguageErrorClassifier.classify(
                        (IllegalArgumentException) failure)
                        : null;
        String message =
                failure == null ? null : failure.getMessage();

        // then
        assertInstanceOf(
                IllegalArgumentException.class,
                failure);
        assertEquals(
                BlueLanguageErrorCategory.ProviderUnavailable,
                category);
        assertTrue(message.contains(channelBlueId), message);
        assertFalse(hasNode(source, "/contracts/initialized"));
        assertFalse(hasNode(source, "/contracts/terminated"));
    }

    @Test
    void shouldRejectMismatchedPureReferenceContractBeforeInitiation() {
        // given
        Node referencedLifecycleChannel = new Node()
                .name("Mismatched Protocol Reference Lifecycle Channel")
                .type(reference(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL));
        String channelBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        referencedLifecycleChannel);
        Node source = new Node().contracts(new Node().properties(
                "referencedLifecycle", reference(channelBlueId)));
        NodeProvider mismatchProvider = blueId -> channelBlueId.equals(blueId)
                ? Collections.singletonList(new Node().name("Wrong Contract Content"))
                : null;

        // when
        Throwable failure = captureFailure(
                () -> ProcessorTestSupport.blue(
                        mismatchProvider).initializeDocument(source.clone()));
        BlueLanguageErrorCategory category =
                failure instanceof IllegalArgumentException
                        ? BlueLanguageErrorClassifier.classify(
                        (IllegalArgumentException) failure)
                        : null;
        String message =
                failure == null ? null : failure.getMessage();

        // then
        assertInstanceOf(
                IllegalArgumentException.class,
                failure);
        assertEquals(
                BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                category);
        assertTrue(message.contains(channelBlueId), message);
        assertFalse(hasNode(source, "/contracts/initialized"));
        assertFalse(hasNode(source, "/contracts/terminated"));
    }

    @Test
    void shouldVerifyExactNodeInitializationIdentityDoesNotInvokeStandaloneProjectionProof() {
        // given
        Blue configured = ProcessorTestSupport.blue();
        DocumentProcessor configuredProcessor = configured.getDocumentProcessor();
        String proofChildBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Same BlueId Proof Child"));
        ProcessingSnapshotManager mismatchManager = new ProofMismatchSnapshotManager(
                configuredProcessor.snapshotManager(), proofChildBlueId);
        DocumentProcessor processor = DocumentProcessor.builder()
                .snapshotStore(mismatchManager)
                .conformanceEngine(configuredProcessor.conformanceEngine())
                .matchingService(configuredProcessor.matchingService())
                .build();
        Node source = configured.yamlToNode(
                "name: Structural Proof Mismatch\ncontracts: {}\n");
        String expectedBlueId =
                configured.resolveToSnapshot(source).blueId();

        // when
        DocumentProcessingResult result = processor.initializeDocument(source);

        // then
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(), diagnosticMessage(result));
        assertEquals(expectedBlueId,
                initializationDocumentBlueId(result.document(), ""));
        assertTrue(hasNode(result.document(), "/contracts/initialized"));
        assertFalse(hasNode(result.document(), "/contracts/terminated"));
        assertTrue(result.events().isEmpty(),
                "processor-generated lifecycle delivery is not a Root emission");
    }

    @Test
    void shouldVerifyProjectionPreservesReferencesPreprocessingAndFinalListControlSemantics() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node referenced = new Node()
                .name("Projection Reference")
                .properties("payload", text("verified"));
        provider.addSingleNodes(referenced);
        String referencedBlueId = provider.getBlueIdByName(referenced.getName());

        List<Node> previousItems = Arrays.asList(text("old-a"), text("old-b"));
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(previousItems);
        provider.addList(previousItems);

        Node replacement = new Node()
                .position(0)
                .properties("$replace", reference(referencedBlueId));
        Node controlledList = new Node()
                .type(reference(BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                .items(Arrays.asList(
                        new Node().previousBlueId(previousBlueId),
                        replacement,
                        new Node().items(Arrays.asList(text("nested-a"), text("nested-b")))));
        Node source = new Node()
                .properties("preprocessed", new Node().type("Text").value("alias-source"))
                .properties("propertyReference", reference(referencedBlueId))
                .properties("controlledList", controlledList)
                .contracts(new Node().properties("referenceEvidence", reference(referencedBlueId)));
        Blue blue = ProcessorTestSupport.blue(provider);
        ResolvedSnapshot captured = blue.resolveToSnapshot(source.clone());
        String expected = blue.calculateSourceDocumentBlueId(source);

        // when
        ScopeSourceProjection projection = ScopeSourceProjection.project(
                "/",
                captured.frozenCanonicalRoot(),
                captured,
                blue.getDocumentProcessor().snapshotManager());
        FrozenNode projectedSource = projection.standaloneSource();
        FrozenNode projectedList = projectedSource.property("controlledList");

        // then
        assertEquals(expected, projection.contentBlueId());
        assertTrue(projection.standaloneSnapshot().frozenResolvedRoot()
                .sameResolvedStructure(captured.frozenResolvedRoot()));
        assertTrue(projectedSource.property("propertyReference").isReferenceOnly());
        assertTrue(projectedSource.getContracts().property("referenceEvidence").isReferenceOnly());
        assertEquals(BlueLanguageConstants.TEXT_TYPE_BLUE_ID,
                projectedSource.property("preprocessed").getType().getReferenceBlueId());
        assertNotNull(projectedList);
        assertEquals(3, projectedList.getItems().size());
        assertTrue(projectedList.getItems().get(0).isReferenceOnly());
        assertEquals(referencedBlueId,
                projectedList.getItems().get(0).getReferenceBlueId());
        assertTrue(projectedList.getItems().get(2).hasItems());
        for (FrozenNode item : projectedList.getItems()) {
            assertFalse(item.getPreviousBlueId() != null || item.getPosition() != null,
                    "canonical standalone projection must preserve final list semantics, not controls");
        }
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void assertInitializationIdentity(DocumentProcessingResult result,
                                                     String expected) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), diagnosticMessage(result));
        assertEquals(expected,
                initializationDocumentBlueId(result.document(), ""));
        assertTrue(result.events().isEmpty(),
                "processor-generated lifecycle delivery is not a Root emission");
    }

    private static void assertScopeInitializationIdentity(DocumentProcessingResult result,
                                                          String scopePath,
                                                          String expected) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), diagnosticMessage(result));
        assertEquals(expected,
                initializationDocumentBlueId(
                        result.document(), scopePath));
    }

    private static String initializationDocumentBlueId(Node result,
                                                       String scopePath) {
        Node document = result.getAsNode(
                scopePath + "/contracts/initialized/document");
        return document != null
                ? DirectBlueIdCalculator.calculateBlueId(document)
                : null;
    }

    private static void assertInvalidProcessingDocument(DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status(), diagnosticMessage(result));
        assertEquals(ProcessorErrorCategory.InvalidProcessingDocument,
                diagnosticCategory(result), diagnosticMessage(result));
        assertEquals(0L, result.totalGas());
        assertTrue(result.events().isEmpty());
        assertTrue(result.document().isReferenceOnly());
    }

    private static Node text(String value) {
        return new Node().value(value);
    }

    private static BasicNodeProvider referenceProvider(Node... nodes) {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node[] copies = new Node[nodes.length];
        for (int index = 0; index < nodes.length; index++) {
            copies[index] = nodes[index].clone();
        }
        provider.addSingleNodes(copies);
        return provider;
    }

    private static boolean hasNode(Node document, String path) {
        try {
            return document.getAsNode(path) != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static final class ProofMismatchSnapshotManager
            implements ProcessingSnapshotManager {
        private final ProcessingSnapshotManager delegate;
        private final String proofChildBlueId;

        private ProofMismatchSnapshotManager(ProcessingSnapshotManager delegate,
                                             String proofChildBlueId) {
            this.delegate = delegate;
            this.proofChildBlueId = proofChildBlueId;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return withProofChild(delegate.fromDocument(document), "captured");
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return withProofChild(delegate.fromDocumentTransient(document), "captured");
        }

        @Override
        public String calculateScopeContentBlueId(String scopePath,
                                                  FrozenNode selectedScope,
                                                  ResolvedSnapshot capturedDocumentSnapshot) {
            return ScopeSourceProjection.project(
                    scopePath,
                    selectedScope,
                    capturedDocumentSnapshot,
                    new ProjectionMismatchSnapshotManager(delegate, proofChildBlueId))
                    .contentBlueId();
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            return delegate.applyPatch(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return delegate.cacheSnapshot(snapshot);
        }

        private ResolvedSnapshot withProofChild(ResolvedSnapshot snapshot, String state) {
            Node resolved = snapshot.resolvedRoot();
            resolved.properties("proofChild", new Node()
                    .blueId(proofChildBlueId)
                    .properties("state", text(state)));
            return new ResolvedSnapshot(
                    snapshot.frozenCanonicalRoot(),
                    FrozenNode.fromResolvedNode(resolved),
                    snapshot.blueId());
        }
    }

    private static final class SuccessfulButAlteredSnapshotManager
            implements ProcessingSnapshotManager {
        private final ResolvedSnapshot authoritative;
        private int fromDocumentTransientCalls;
        private ResolvedSnapshot capturedSnapshot;

        private SuccessfulButAlteredSnapshotManager(ResolvedSnapshot authoritative) {
            this.authoritative = authoritative;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return fromDocumentTransient(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            fromDocumentTransientCalls++;
            Node alteredResolved = authoritative.resolvedRoot();
            alteredResolved.properties("state", text("successfully-reresolved-differently"));
            return new ResolvedSnapshot(
                    authoritative.frozenCanonicalRoot(),
                    FrozenNode.fromResolvedNode(alteredResolved),
                    authoritative.blueId());
        }

        @Override
        public String calculateScopeContentBlueId(String scopePath,
                                                  FrozenNode selectedScope,
                                                  ResolvedSnapshot capturedDocumentSnapshot) {
            capturedSnapshot = capturedDocumentSnapshot;
            return capturedDocumentSnapshot.blueId();
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            throw new UnsupportedOperationException("not used by the capture regression");
        }
    }

    private static final class ProjectionMismatchSnapshotManager
            implements ProcessingSnapshotManager {
        private final ProcessingSnapshotManager delegate;
        private final String proofChildBlueId;

        private ProjectionMismatchSnapshotManager(ProcessingSnapshotManager delegate,
                                                  String proofChildBlueId) {
            this.delegate = delegate;
            this.proofChildBlueId = proofChildBlueId;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return mismatched(delegate.fromDocument(document));
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return mismatched(delegate.fromDocumentTransient(document));
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            return delegate.applyPatch(snapshot, patch);
        }

        private ResolvedSnapshot mismatched(ResolvedSnapshot snapshot) {
            Node resolved = snapshot.resolvedRoot();
            resolved.properties("proofChild", new Node()
                    .blueId(proofChildBlueId)
                    .properties("state", text("projected")));
            return new ResolvedSnapshot(
                    snapshot.frozenCanonicalRoot(),
                    FrozenNode.fromResolvedNode(resolved),
                    snapshot.blueId());
        }
    }
}
