package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.MergeReverser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-first coverage for the initialization identity of an embedded scope.
 *
 * <p>Every expected identity in this class is calculated from an explicitly
 * constructed standalone Source-equivalent document before processing begins.
 * The tests deliberately do not hash a child fragment from the parent's
 * Canonical Identity Input and do not reconstruct pre-initialization state from
 * a returned, already-mutated document.</p>
 */
class SelectedScopeContentBlueIdFailFirstTest {

    @Test
    void typeDerivedSelectedChildUsesStandaloneIdentityInsteadOfEmptyNodeIdentity() {
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(false);
        ResolvedSnapshot parentSnapshot = fixture.identityBlue().resolveToSnapshot(source.clone());

        assertNull(parentSnapshot.canonicalAt("/child"),
                "the selected child must be omitted from the parent identity as fully type-derived");

        ExpectedIdentities expected = fixture.expectedBeforeLifecycle(source);
        LifecycleRecorder recorder = new LifecycleRecorder();
        DocumentProcessingResult result = fixture.executionBlue(recorder).initializeDocument(source);

        assertSuccessful(result);
        assertScopeIdentity(result.document(), recorder, "/child", expected.child);
        assertNotEquals(emptyNodeBlueId(), expected.child,
                "an existing selected scope must never use the empty-node fallback");
    }

    @Test
    void contextualChildCanonicalFragmentDoesNotReplaceStandaloneInheritedTypeIdentity() {
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        Blue identityBlue = fixture.identityBlue();
        ResolvedSnapshot parentSnapshot = identityBlue.resolveToSnapshot(source.clone());
        FrozenNode contextualFragment = parentSnapshot.canonicalAt("/child");

        assertNotNull(contextualFragment);
        assertNull(contextualFragment.getType(),
                "the parent fragment intentionally omits the type supplied by parent field metadata");

        Node standaloneChild = fixture.standaloneChildBeforeLifecycle(source);
        assertEquals(fixture.childTypeBlueId, standaloneChild.getType().getBlueId());
        String expectedChild = identityBlue.calculateSemanticBlueId(standaloneChild);
        assertNotEquals(contextualFragment.blueId(), expectedChild,
                "the contextual parent fragment is not the standalone scope Content BlueId input");

        LifecycleRecorder recorder = new LifecycleRecorder();
        DocumentProcessingResult result = fixture.executionBlue(recorder).initializeDocument(source);

        assertSuccessful(result);
        assertScopeIdentity(result.document(), recorder, "/child", expectedChild);
    }

    @Test
    void explicitRootNameEqualToTypeNameRemainsIdentityBearing() {
        Node canonicalType = new Node().name("Same Label");
        BasicNodeProvider provider = new BasicNodeProvider(canonicalType);
        String typeBlueId = provider.getBlueIdByName(canonicalType.getName());
        Node source = new Node()
                .name(canonicalType.getName())
                .type(reference(typeBlueId));
        Blue identityBlue = ProcessorTestSupport.blue(provider);
        String expected = identityBlue.calculateSemanticBlueId(source.clone());
        String withoutExplicitName = identityBlue.calculateSemanticBlueId(
                new Node().type(reference(typeBlueId)));

        assertNotEquals(withoutExplicitName, expected);
        assertRootInitializationIdentity(identityBlue, source, expected);
    }

    @Test
    void explicitRootDescriptionEqualToTypeDescriptionRemainsIdentityBearing() {
        Node canonicalType = new Node()
                .name("Description Type")
                .description("Same Description");
        BasicNodeProvider provider = new BasicNodeProvider(canonicalType);
        String typeBlueId = provider.getBlueIdByName(canonicalType.getName());
        Node source = new Node()
                .description(canonicalType.getDescription())
                .type(reference(typeBlueId));
        Blue identityBlue = ProcessorTestSupport.blue(provider);
        String expected = identityBlue.calculateSemanticBlueId(source.clone());
        String withoutExplicitDescription = identityBlue.calculateSemanticBlueId(
                new Node().type(reference(typeBlueId)));

        assertNotEquals(withoutExplicitDescription, expected);
        assertRootInitializationIdentity(identityBlue, source, expected);
    }

    @Test
    void explicitRootLabelsDifferentFromTypeLabelsRemainIdentityBearing() {
        Node canonicalType = new Node()
                .name("Type Label")
                .description("Type Description");
        BasicNodeProvider provider = new BasicNodeProvider(canonicalType);
        String typeBlueId = provider.getBlueIdByName(canonicalType.getName());
        Node source = new Node()
                .name("Instance Label")
                .description("Instance Description")
                .type(reference(typeBlueId));
        Blue identityBlue = ProcessorTestSupport.blue(provider);
        String expected = identityBlue.calculateSemanticBlueId(source.clone());

        assertRootInitializationIdentity(identityBlue, source, expected);
    }

    @Test
    void lifecycleMutationIsAfterOwnCaptureAndChildMutationIsBeforeParentCapture() {
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        ExpectedIdentities expected = fixture.expectedBeforeLifecycle(source);
        LifecycleRecorder recorder = new LifecycleRecorder();

        DocumentProcessingResult result = fixture.executionBlue(recorder).initializeDocument(source);

        assertSuccessful(result);
        assertEquals(ScopeFixture.CHILD_MUTATION,
                result.document().getAsText("/child/lifecycleMutation"));
        assertEquals(ScopeFixture.ROOT_MUTATION,
                result.document().getAsText("/rootLifecycleMutation"));
        assertScopeIdentity(result.document(), recorder, "/child", expected.child);
        assertScopeIdentity(result.document(), recorder, "/", expected.rootAfterChildPhase1);
    }

    @Test
    void nodeAndResolvedSnapshotInputsUseTheSameStandaloneScopeIdentities() {
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        ExpectedIdentities expected = fixture.expectedBeforeLifecycle(source);

        LifecycleRecorder nodeRecorder = new LifecycleRecorder();
        Blue nodeBlue = fixture.executionBlue(nodeRecorder);
        DocumentProcessingResult nodeResult = nodeBlue.initializeDocument(source.clone());

        LifecycleRecorder snapshotRecorder = new LifecycleRecorder();
        Blue snapshotBlue = fixture.executionBlue(snapshotRecorder);
        ResolvedSnapshot inputSnapshot = snapshotBlue.resolveToSnapshot(source.clone());
        DocumentProcessingResult snapshotResult = snapshotBlue.initializeDocument(inputSnapshot);

        assertSuccessful(nodeResult);
        assertSuccessful(snapshotResult);
        Node nodeRootAtCapture = nodeRecorder.onlyScopeSource("/");
        Node snapshotRootAtCapture = snapshotRecorder.onlyScopeSource("/");
        Blue parityOracle = fixture.identityBlue();
        String nodeCapturedIdentity = parityOracle.calculateSemanticBlueId(nodeRootAtCapture);
        String snapshotCapturedIdentity = parityOracle.calculateSemanticBlueId(snapshotRootAtCapture);
        Node snapshotResolvedChild = inputSnapshot.resolvedNodeAt("/child");
        Node snapshotMinimizedChild = new MergeReverser()
                .reverseToMinimizedOverlay(snapshotResolvedChild.clone());

        assertEquals(nodeCapturedIdentity, snapshotCapturedIdentity,
                () -> "Node and snapshot selected roots must remain Source-equivalent at capture.\nnode="
                        + parityOracle.nodeToJson(nodeRootAtCapture)
                        + "\nsnapshot=" + parityOracle.nodeToJson(snapshotRootAtCapture));
        assertEquals(expected.rootAfterChildPhase1, nodeCapturedIdentity);
        assertScopeIdentity(nodeResult.document(), nodeRecorder, "/child", expected.child);
        assertScopeIdentity(snapshotResult.document(), snapshotRecorder, "/child", expected.child);
        assertScopeIdentity(nodeResult.document(), nodeRecorder, "/", expected.rootAfterChildPhase1);
        assertEquals(expected.rootAfterChildPhase1, snapshotRecorder.onlyId("/"),
                () -> "Snapshot root Lifecycle identity must use the captured selected root."
                        + "\ncaptured=" + parityOracle.nodeToJson(snapshotRootAtCapture)
                        + "\nresolvedChild=" + parityOracle.nodeToJson(snapshotResolvedChild)
                        + "\nminimizedChild=" + parityOracle.nodeToJson(snapshotMinimizedChild));
        assertEquals(expected.rootAfterChildPhase1,
                markerDocumentId(snapshotResult.document(), "/"));
    }

    @Test
    void coldAndWarmCachesKeepTheSameStandaloneScopeIdentities() {
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        ExpectedIdentities expected = fixture.expectedBeforeLifecycle(source);
        LifecycleRecorder recorder = new LifecycleRecorder();
        Blue blue = fixture.executionBlue(recorder);

        DocumentProcessingResult cold = blue.initializeDocument(source.clone());
        DocumentProcessingResult warm = blue.initializeDocument(source.clone());

        assertSuccessful(cold);
        assertSuccessful(warm);
        assertEquals(2, recorder.ids("/child").size());
        assertEquals(2, recorder.ids("/").size());
        assertScopeIdentity(cold.document(), recorder.id("/child", 0), "/child", expected.child);
        assertScopeIdentity(warm.document(), recorder.id("/child", 1), "/child", expected.child);
        assertScopeIdentity(cold.document(), recorder.id("/", 0), "/", expected.rootAfterChildPhase1);
        assertScopeIdentity(warm.document(), recorder.id("/", 1), "/", expected.rootAfterChildPhase1);
    }

    @Test
    void nestedListAndProviderReferenceUseStrictStandaloneContentIdentity() {
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        Node standaloneChild = fixture.standaloneChildBeforeLifecycle(source);
        ResolvedSnapshot expectedSnapshot = fixture.identityBlue().resolveToSnapshot(standaloneChild);
        String expectedChild = expectedSnapshot.blueId();
        String unchecked = BlueIdCalculator.calculateUncheckedBlueId(
                expectedSnapshot.frozenCanonicalRoot().toNode());
        FrozenNode canonicalReference = expectedSnapshot.frozenCanonicalRoot().property("providerPayload");

        assertNotEquals(unchecked, expectedChild,
                "the nested payload must distinguish unchecked hashing from Content BlueId");
        assertNotNull(canonicalReference);
        assertTrue(canonicalReference.isReferenceOnly(),
                "source pure-reference provenance must survive standalone canonicalization");
        assertEquals(fixture.providerPayloadBlueId, canonicalReference.getReferenceBlueId());

        LifecycleRecorder recorder = new LifecycleRecorder();
        DocumentProcessingResult result = fixture.executionBlue(recorder).initializeDocument(source);

        assertSuccessful(result);
        assertScopeIdentity(result.document(), recorder, "/child", expectedChild);
        assertNotEquals(unchecked, markerDocumentId(result.document(), "/child"));
    }

    @Test
    void missingProviderContentDuringScopeIdentityTerminatesFatallyBeforeInitiation() {
        assertIdentityFailureTerminatesBeforeInitiation(
                new IllegalArgumentException(
                        "No content found for blueId: scope-identity-missing"),
                ProcessorErrorCategory.ProviderUnavailable);
    }

    @Test
    void providerBlueIdMismatchDuringScopeIdentityTerminatesFatallyBeforeInitiation() {
        assertIdentityFailureTerminatesBeforeInitiation(
                new IllegalArgumentException(
                        "Provider returned content for requested blueId scope-identity-request "
                                + "but computed BlueId scope-identity-other"),
                ProcessorErrorCategory.ProviderBlueIdMismatch);
    }

    @Test
    void snapshotBackedScopeIdentityMissingProviderContentIsProviderUnavailable() {
        SnapshotProviderFailureFixture fixture = new SnapshotProviderFailureFixture();
        ResolvedSnapshot producerSnapshot = fixture.producerSnapshot();
        Blue consumer = new Blue(blueId -> null);

        DocumentProcessingResult result = consumer.initializeDocument(producerSnapshot);

        assertSnapshotProviderIdentityFailure(
                result, ProcessorErrorCategory.ProviderUnavailable, fixture.typeBlueId);
    }

    @Test
    void snapshotBackedScopeIdentityRejectsProviderBlueIdMismatch() {
        SnapshotProviderFailureFixture fixture = new SnapshotProviderFailureFixture();
        ResolvedSnapshot producerSnapshot = fixture.producerSnapshot();
        Node wrongType = new Node()
                .name("Wrong Snapshot Scope Type")
                .properties("fixed", text("wrong-provider-content"));
        NodeProvider wrongContentProvider = blueId -> fixture.typeBlueId.equals(blueId)
                ? Collections.singletonList(wrongType.clone())
                : null;
        Blue consumer = new Blue(wrongContentProvider);

        DocumentProcessingResult result = consumer.initializeDocument(producerSnapshot);

        assertSnapshotProviderIdentityFailure(
                result, ProcessorErrorCategory.ProviderBlueIdMismatch, fixture.typeBlueId);
    }

    private static void assertSnapshotProviderIdentityFailure(
            DocumentProcessingResult result,
            ProcessorErrorCategory expectedCategory,
            String requestedBlueId) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), result.failureReason());
        assertEquals(expectedCategory, result.errorCategory(), result.failureReason());
        assertTrue(result.failureReason().contains(requestedBlueId), result.failureReason());
        assertFalse(hasNode(result.document(), "/contracts/initialized"));
        assertTrue(hasNode(result.document(), "/contracts/terminated"),
                "the original provider failure must still produce the fatal termination marker");
        assertEquals("fatal", result.document().getAsText("/contracts/terminated/cause"));
        for (Node event : result.triggeredEvents()) {
            String eventType = event.getType() != null ? event.getType().getBlueId() : null;
            assertNotEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED, eventType,
                    "provider failure during the scope identity rerun must precede initiation");
        }
    }

    private static void assertIdentityFailureTerminatesBeforeInitiation(
            RuntimeException identityFailure,
            ProcessorErrorCategory expectedCategory) {
        ScopeFixture fixture = new ScopeFixture();
        LifecycleRecorder recorder = new LifecycleRecorder();
        IdentityFailureRuntime runtime = fixture.identityFailureRuntime(recorder, identityFailure);

        DocumentProcessingResult result = runtime.processor.initializeDocument(fixture.source(true));

        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), result.failureReason());
        assertEquals(expectedCategory, result.errorCategory(), result.failureReason());
        assertFalse(runtime.manager.requestedScopes.isEmpty());
        assertEquals("/child", runtime.manager.requestedScopes.get(0));
        assertTrue(recorder.ids("/child").isEmpty(),
                "Document Processing Initiated must not be delivered when identity calculation fails");
        assertTrue(recorder.ids("/").isEmpty(),
                "an ancestor must not initialize after its child identity calculation fails");
        assertFalse(hasNode(result.document(), "/child/contracts/initialized"));
        assertFalse(hasNode(result.document(), "/contracts/initialized"));
        for (Node event : result.triggeredEvents()) {
            String eventType = event.getType() != null ? event.getType().getBlueId() : null;
            assertNotEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED, eventType,
                    "no initiated event may be published after scope identity failure");
        }
    }

    private static void assertSuccessful(DocumentProcessingResult result) {
        assertFalse(result.capabilityFailure(), result.failureReason());
        assertNull(result.errorCategory(), result.failureReason());
    }

    private static void assertRootInitializationIdentity(Blue blue,
                                                         Node source,
                                                         String expected) {
        DocumentProcessingResult result = blue.initializeDocument(source.clone());

        assertSuccessful(result);
        assertEquals(expected, markerDocumentId(result.document(), "/"));
        boolean initiated = false;
        for (Node event : result.triggeredEvents()) {
            String eventType = event.getType() != null ? event.getType().getBlueId() : null;
            if (RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED.equals(eventType)
                    && expected.equals(event.getAsText("/documentId"))) {
                initiated = true;
                break;
            }
        }
        assertTrue(initiated,
                "Lifecycle and initialized marker must reuse the independently computed Content BlueId");
    }

    private static void assertScopeIdentity(Node document,
                                            LifecycleRecorder recorder,
                                            String scope,
                                            String expected) {
        assertScopeIdentity(document, recorder.onlyId(scope), scope, expected);
    }

    private static void assertScopeIdentity(Node document,
                                            String lifecycleId,
                                            String scope,
                                            String expected) {
        assertEquals(expected, lifecycleId, "Lifecycle documentId at " + scope);
        assertEquals(expected, markerDocumentId(document, scope),
                "initialized marker documentId at " + scope);
    }

    private static String markerDocumentId(Node document, String scope) {
        String prefix = "/".equals(scope) ? "" : scope;
        return document.getAsText(prefix + "/contracts/initialized/documentId");
    }

    private static boolean hasNode(Node document, String path) {
        try {
            return document.getAsNode(path) != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String emptyNodeBlueId() {
        return BlueIdCalculator.calculateBlueId(new Node());
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static Node text(String value) {
        return new Node().value(value);
    }

    private static final class ExpectedIdentities {
        private final String child;
        private final String rootAfterChildPhase1;

        private ExpectedIdentities(String child, String rootAfterChildPhase1) {
            this.child = child;
            this.rootAfterChildPhase1 = rootAfterChildPhase1;
        }
    }

    private static final class SnapshotProviderFailureFixture {
        private final BasicNodeProvider producerProvider = new BasicNodeProvider();
        private final String typeBlueId;

        private SnapshotProviderFailureFixture() {
            Node providerType = new Node()
                    .name("Snapshot Backed Scope Type")
                    .properties("fixed", text("resolved-by-producer"));
            producerProvider.addSingleNodes(providerType);
            typeBlueId = producerProvider.getBlueIdByName(providerType.getName());
        }

        private ResolvedSnapshot producerSnapshot() {
            Node source = new Node()
                    .type(reference(typeBlueId))
                    .properties("local", text("selected-state"));
            ResolvedSnapshot snapshot = new Blue(producerProvider).resolveToSnapshot(source);
            assertEquals("resolved-by-producer", snapshot.resolvedRoot().getAsText("/fixed"));
            assertEquals(typeBlueId,
                    snapshot.frozenCanonicalRoot().getType().getReferenceBlueId());
            return snapshot;
        }
    }

    private static final class ScopeFixture {
        private static final String CHILD_MUTATION = "child-after-capture";
        private static final String ROOT_MUTATION = "root-after-capture";

        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final Node lifecycleHandlerType;
        private final String lifecycleHandlerBlueId;
        private final String childTypeBlueId;
        private final String rootTypeBlueId;
        private final String providerPayloadBlueId;

        private ScopeFixture() {
            lifecycleHandlerType = YAML_MAPPER.readValue(
                    "name: Capture And Mutate Initialization Lifecycle\n"
                            + "type:\n"
                            + "  blueId: " + RuntimeBlueIds.HANDLER + "\n",
                    Node.class);
            provider.addSingleNodes(lifecycleHandlerType);
            lifecycleHandlerBlueId = provider.getBlueIdByName(lifecycleHandlerType.getName());

            Node providerPayload = YAML_MAPPER.readValue(
                    "name: Provider Backed Scope Payload\n"
                            + "payload: verified\n",
                    Node.class);
            provider.addSingleNodes(providerPayload);
            providerPayloadBlueId = provider.getBlueIdByName(providerPayload.getName());

            Node childType = YAML_MAPPER.readValue(
                    "name: Contextual Embedded Child Type\n"
                            + "fixed: from-child-type\n"
                            + lifecycleContractsYaml("/lifecycleMutation", CHILD_MUTATION),
                    Node.class);
            provider.addSingleNodes(childType);
            childTypeBlueId = provider.getBlueIdByName(childType.getName());

            Node rootType = YAML_MAPPER.readValue(
                    "name: Contextual Root Type\n"
                            + "child:\n"
                            + "  type:\n"
                            + "    blueId: " + childTypeBlueId + "\n",
                    Node.class);
            provider.addSingleNodes(rootType);
            rootTypeBlueId = provider.getBlueIdByName(rootType.getName());
        }

        private Blue identityBlue() {
            return ProcessorTestSupport.blue(provider);
        }

        private Blue executionBlue(LifecycleRecorder recorder) {
            Blue blue = ProcessorTestSupport.blue(provider);
            blue.registerExternalContractType(
                    lifecycleHandlerBlueId,
                    lifecycleHandlerType,
                    new CaptureAndMutateLifecycleProcessor(recorder));
            return blue;
        }

        private IdentityFailureRuntime identityFailureRuntime(
                LifecycleRecorder recorder,
                RuntimeException failure) {
            Blue configured = executionBlue(recorder);
            DocumentProcessor configuredProcessor = configured.getDocumentProcessor();
            IdentityFailingSnapshotManager manager = new IdentityFailingSnapshotManager(
                    configuredProcessor.snapshotManager(), failure);
            DocumentProcessor processor = DocumentProcessor.builder()
                    .withSnapshotManager(manager)
                    .withConformanceEngine(configuredProcessor.conformanceEngine())
                    .withMatchingService(configuredProcessor.matchingService())
                    .registerContractProcessor(
                            lifecycleHandlerBlueId,
                            new CaptureAndMutateLifecycleProcessor(recorder))
                    .build();
            return new IdentityFailureRuntime(configured, processor, manager);
        }

        private Node source(boolean withContextualPayload) {
            StringBuilder yaml = new StringBuilder()
                    .append("name: Selected Root\n")
                    .append("type:\n")
                    .append("  blueId: ").append(rootTypeBlueId).append('\n')
                    .append("child:\n")
                    .append("  fixed: from-child-type\n");
            if (withContextualPayload) {
                yaml.append("  payload:\n")
                        .append("    do:\n")
                        .append("      - - 1\n")
                        .append("        - [2, 3]\n")
                        .append("  providerPayload:\n")
                        .append("    blueId: ").append(providerPayloadBlueId).append('\n');
            }
            yaml.append(indent(lifecycleContractsYaml("/lifecycleMutation", CHILD_MUTATION), 2))
                    .append("contracts:\n")
                    .append("  embedded:\n")
                    .append("    type:\n")
                    .append("      blueId: ").append(RuntimeBlueIds.PROCESS_EMBEDDED).append('\n')
                    .append("    paths:\n")
                    .append("      - /child\n")
                    .append(indent(lifecycleContractsBodyYaml(
                            "/rootLifecycleMutation", ROOT_MUTATION), 2));
            return YAML_MAPPER.readValue(yaml.toString(), Node.class);
        }

        private Node standaloneChildBeforeLifecycle(Node source) {
            Node child = source.getAsNode("/child").clone();
            child.type(reference(childTypeBlueId));
            return child;
        }

        private ExpectedIdentities expectedBeforeLifecycle(Node source) {
            Blue identityBlue = identityBlue();
            String childId = identityBlue.calculateSemanticBlueId(
                    standaloneChildBeforeLifecycle(source));

            Node rootAfterChildPhase1 = source.clone();
            Node child = rootAfterChildPhase1.getAsNode("/child");
            child.properties("lifecycleMutation", text(CHILD_MUTATION));
            child.getContracts().properties("initialized", initializedMarker(childId));
            String rootId = identityBlue.calculateSemanticBlueId(rootAfterChildPhase1);
            return new ExpectedIdentities(childId, rootId);
        }

        private Node initializedMarker(String documentId) {
            return new Node()
                    .type(reference(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                    .properties("documentId", text(documentId));
        }

        private String lifecycleContractsYaml(String propertyKey, String propertyValue) {
            return "contracts:\n" + indent(lifecycleContractsBodyYaml(propertyKey, propertyValue), 2);
        }

        private String lifecycleContractsBodyYaml(String propertyKey, String propertyValue) {
            return "lifecycle:\n"
                    + "  type:\n"
                    + "    blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n"
                    + "captureAndMutate:\n"
                    + "  channel: lifecycle\n"
                    + "  type:\n"
                    + "    blueId: " + lifecycleHandlerBlueId + "\n"
                    + "  event:\n"
                    + "    type:\n"
                    + "      blueId: " + RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED + "\n"
                    + "  propertyKey: " + propertyKey + "\n"
                    + "  propertyValue: " + propertyValue + "\n";
        }

        private static String indent(String value, int spaces) {
            String prefix = String.format("%" + spaces + "s", "");
            return prefix + value.replace("\n", "\n" + prefix).replaceAll("\\s+$", "") + "\n";
        }
    }

    public static final class CaptureAndMutateLifecycle extends HandlerContract {
        private String propertyKey;
        private String propertyValue;

        public String getPropertyKey() {
            return propertyKey;
        }

        public void setPropertyKey(String propertyKey) {
            this.propertyKey = propertyKey;
        }

        public String getPropertyValue() {
            return propertyValue;
        }

        public void setPropertyValue(String propertyValue) {
            this.propertyValue = propertyValue;
        }
    }

    private static final class CaptureAndMutateLifecycleProcessor
            implements HandlerProcessor<CaptureAndMutateLifecycle> {
        private final LifecycleRecorder recorder;

        private CaptureAndMutateLifecycleProcessor(LifecycleRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public Class<CaptureAndMutateLifecycle> contractType() {
            return CaptureAndMutateLifecycle.class;
        }

        @Override
        public void execute(CaptureAndMutateLifecycle contract, ProcessorExecutionContext context) {
            Node documentId = context.event().getProperties().get("documentId");
            if (documentId == null) {
                // The same lifecycle channel also carries termination. This
                // observer is deliberately scoped to initiation identity.
                return;
            }
            recorder.record(context.scopePath(),
                    String.valueOf(documentId.getValue()),
                    context.documentAt(context.scopePath()));
            context.applyPatch(JsonPatch.replace(
                    context.resolvePointer(contract.getPropertyKey()),
                    text(contract.getPropertyValue())));
        }
    }

    private static final class LifecycleRecorder {
        private final Map<String, List<String>> idsByScope = new LinkedHashMap<>();
        private final Map<String, List<Node>> sourcesByScope = new LinkedHashMap<>();

        private synchronized void record(String scope, String documentId, Node scopeSource) {
            idsByScope.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(documentId);
            sourcesByScope.computeIfAbsent(scope, ignored -> new ArrayList<>())
                    .add(scopeSource != null ? scopeSource.clone() : null);
        }

        private synchronized List<String> ids(String scope) {
            List<String> ids = idsByScope.get(scope);
            return ids != null ? new ArrayList<>(ids) : new ArrayList<>();
        }

        private String onlyId(String scope) {
            List<String> ids = ids(scope);
            assertEquals(1, ids.size(), "captured Lifecycle events at " + scope);
            return ids.get(0);
        }

        private String id(String scope, int index) {
            return ids(scope).get(index);
        }

        private synchronized Node onlyScopeSource(String scope) {
            List<Node> sources = sourcesByScope.get(scope);
            assertNotNull(sources, "captured scope Source-equivalent inputs at " + scope);
            assertEquals(1, sources.size(), "captured scope Source-equivalent inputs at " + scope);
            return sources.get(0).clone();
        }
    }

    private static final class IdentityFailureRuntime {
        @SuppressWarnings("unused")
        private final Blue configuredBlueOwner;
        private final DocumentProcessor processor;
        private final IdentityFailingSnapshotManager manager;

        private IdentityFailureRuntime(Blue configuredBlueOwner,
                                       DocumentProcessor processor,
                                       IdentityFailingSnapshotManager manager) {
            this.configuredBlueOwner = configuredBlueOwner;
            this.processor = processor;
            this.manager = manager;
        }
    }

    private static final class IdentityFailingSnapshotManager implements ProcessingSnapshotManager {
        private final ProcessingSnapshotManager delegate;
        private final RuntimeException failure;
        private final List<String> requestedScopes = new ArrayList<>();

        private IdentityFailingSnapshotManager(ProcessingSnapshotManager delegate,
                                               RuntimeException failure) {
            this.delegate = delegate;
            this.failure = failure;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return delegate.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return delegate.fromDocumentTransient(document);
        }

        @Override
        public String calculateScopeContentBlueId(String scopePath,
                                                  FrozenNode selectedScope,
                                                  ResolvedSnapshot capturedDocumentSnapshot) {
            requestedScopes.add(scopePath);
            throw failure;
        }

        @Override
        public ConformanceEngine transientConformanceEngine(ConformanceEngine conformanceEngine) {
            return delegate.transientConformanceEngine(conformanceEngine);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            return delegate.applyPatch(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return delegate.cacheSnapshot(snapshot);
        }
    }
}
