package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-first coverage for the exact initialization identity of a scope.
 *
 * <p>Contracts 1.0 §9.2 records the direct Node BlueId of the exact selected
 * scope immediately before initialization effects. It explicitly does not
 * calculate Content BlueId or consult a provider. Every expectation below is
 * therefore derived from an immutable copy of the selected exact node at that
 * protocol capture point.</p>
 */
class SelectedScopeContentBlueIdFailFirstTest {

    @Test
    void shouldVerifySelectedChildUsesItsExactDirectIdentityInsteadOfEmptyNodeIdentity() {
        // given
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(false);
        ExpectedIdentities expected = fixture.expectedBeforeLifecycle(source);
        LifecycleRecorder recorder = new LifecycleRecorder();

        // when
        DocumentProcessingResult result = fixture.executionBlue(recorder).initializeDocument(source);

        // then
        assertSuccessful(result);
        assertScopeIdentity(result.document(), recorder, "/child", expected.child);
        assertNotEquals(emptyNodeBlueId(), expected.child,
                "an existing selected scope must never use the empty-node fallback");
    }

    @Test
    void shouldVerifyResolvedRepresentationDoesNotReplaceTheSelectedExactCanonicalNodeIdentity() {
        // given
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        Blue identityBlue = fixture.identityBlue();
        ResolvedSnapshot parentSnapshot = identityBlue.resolveToSnapshot(source.clone());
        FrozenNode contextualFragment = parentSnapshot.canonicalAt("/child");
        String expectedChild = contextualFragment != null
                ? contextualFragment.blueId()
                : null;
        LifecycleRecorder recorder = new LifecycleRecorder();

        // when
        DocumentProcessingResult result =
                fixture.executionBlue(recorder)
                        .initializeDocument(source);

        // then
        assertNotNull(contextualFragment);
        assertNotEquals(parentSnapshot.resolvedAt("/child").blueId(), expectedChild,
                "a materialized Resolved Form is not the selected exact canonical node");
        assertSuccessful(result);
        assertScopeIdentity(result.document(), recorder, "/child", expectedChild);
    }

    @Test
    void shouldVerifyExplicitRootNameEqualToTypeNameRemainsIdentityBearing() {
        // given
        Node canonicalType = new Node().name("Same Label");
        BasicNodeProvider provider = new BasicNodeProvider(canonicalType);
        String typeBlueId = provider.getBlueIdByName(canonicalType.getName());
        Node source = new Node()
                .name(canonicalType.getName())
                .type(reference(typeBlueId));
        Blue identityBlue = ProcessorTestSupport.blue(provider);
        String expected = identityBlue.resolveToSnapshot(
                source.clone()).blueId();

        // when
        String withoutExplicitName = identityBlue.resolveToSnapshot(
                new Node().type(reference(typeBlueId))).blueId();
        DocumentProcessingResult result =
                identityBlue.initializeDocument(source.clone());

        // then
        assertNotEquals(withoutExplicitName, expected);
        assertRootInitializationIdentity(result, expected);
    }

    @Test
    void shouldVerifyExplicitRootDescriptionEqualToTypeDescriptionRemainsIdentityBearing() {
        // given
        Node canonicalType = new Node()
                .name("Description Type")
                .description("Same Description");
        BasicNodeProvider provider = new BasicNodeProvider(canonicalType);
        String typeBlueId = provider.getBlueIdByName(canonicalType.getName());
        Node source = new Node()
                .description(canonicalType.getDescription())
                .type(reference(typeBlueId));
        Blue identityBlue = ProcessorTestSupport.blue(provider);
        String expected = identityBlue.resolveToSnapshot(
                source.clone()).blueId();

        // when
        String withoutExplicitDescription = identityBlue.resolveToSnapshot(
                new Node().type(reference(typeBlueId))).blueId();
        DocumentProcessingResult result =
                identityBlue.initializeDocument(source.clone());

        // then
        assertNotEquals(withoutExplicitDescription, expected);
        assertRootInitializationIdentity(result, expected);
    }

    @Test
    void shouldVerifyExplicitRootLabelsDifferentFromTypeLabelsRemainIdentityBearing() {
        // given
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
        String expected = identityBlue.resolveToSnapshot(
                source.clone()).blueId();

        // when
        DocumentProcessingResult result =
                identityBlue.initializeDocument(source.clone());

        // then
        assertRootInitializationIdentity(result, expected);
    }

    @Test
    void shouldVerifyLifecycleMutationIsAfterOwnCaptureAndChildMutationIsBeforeParentCapture() {
        // given
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        ExpectedIdentities expected = fixture.expectedBeforeLifecycle(source);
        LifecycleRecorder recorder = new LifecycleRecorder();

        // when
        DocumentProcessingResult result = fixture.executionBlue(recorder).initializeDocument(source);

        // then
        assertSuccessful(result);
        assertEquals(ScopeFixture.CHILD_MUTATION,
                result.document().getAsText("/child/lifecycleMutation"));
        assertEquals(ScopeFixture.ROOT_MUTATION,
                result.document().getAsText("/rootLifecycleMutation"));
        assertScopeIdentity(result.document(), recorder, "/child", expected.child);
        assertScopeIdentity(result.document(), recorder, "/", expected.rootAfterChildPhase1);
    }

    @Test
    void shouldVerifyNodeAndSnapshotInputsEachUseTheirOwnExactSelectedRepresentation() {
        // given
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        ExpectedIdentities nodeExpected = fixture.expectedBeforeLifecycle(source);

        LifecycleRecorder nodeRecorder = new LifecycleRecorder();
        Blue nodeBlue = fixture.executionBlue(nodeRecorder);
        LifecycleRecorder snapshotRecorder = new LifecycleRecorder();
        Blue snapshotBlue = fixture.executionBlue(snapshotRecorder);
        ResolvedSnapshot inputSnapshot = snapshotBlue.resolveToSnapshot(source.clone());
        ExpectedIdentities snapshotExpected =
                fixture.expectedBeforeLifecycle(inputSnapshot.canonicalRoot());

        // when
        DocumentProcessingResult nodeResult =
                nodeBlue.initializeDocument(source.clone());
        DocumentProcessingResult snapshotResult = snapshotBlue.initializeDocument(inputSnapshot);

        // then
        assertSuccessful(nodeResult);
        assertSuccessful(snapshotResult);
        assertScopeIdentity(nodeResult.document(), nodeRecorder, "/child", nodeExpected.child);
        assertScopeIdentity(snapshotResult.document(), snapshotRecorder,
                "/child", snapshotExpected.child);
        assertScopeIdentity(nodeResult.document(), nodeRecorder,
                "/", nodeExpected.rootAfterChildPhase1);
        assertEquals(snapshotExpected.rootAfterChildPhase1,
                snapshotRecorder.onlyId("/"));
        assertEquals(snapshotExpected.rootAfterChildPhase1,
                markerDocumentId(snapshotResult.document(), "/"));
    }

    @Test
    void shouldVerifyColdAndWarmCachesKeepTheSameStandaloneScopeIdentities() {
        // given
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        ExpectedIdentities expected = fixture.expectedBeforeLifecycle(source);
        LifecycleRecorder recorder = new LifecycleRecorder();
        Blue blue = fixture.executionBlue(recorder);

        // when
        DocumentProcessingResult cold = blue.initializeDocument(source.clone());
        DocumentProcessingResult warm = blue.initializeDocument(source.clone());

        // then
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
    void shouldVerifyNestedListAndProviderReferenceRemainPartOfTheExactDirectIdentity() {
        // given
        ScopeFixture fixture = new ScopeFixture();
        Node source = fixture.source(true);
        Node exactChild = fixture.exactChildBeforeLifecycle(
                source);
        String expectedChild = fixture.identityBlue()
                .resolveToSnapshot(source.clone())
                .canonicalAt("/child")
                .blueId();
        String unchecked = DirectBlueIdCalculator.calculateUncheckedBlueId(
                exactChild);
        Node providerReference = exactChild.getProperties().get("providerPayload");
        LifecycleRecorder recorder = new LifecycleRecorder();

        // when
        DocumentProcessingResult result =
                fixture.executionBlue(recorder)
                        .initializeDocument(source);

        // then
        assertNotEquals(unchecked, expectedChild,
                "unchecked object hashing must not replace direct BlueId rules");
        assertNotNull(providerReference);
        assertTrue(providerReference.isReferenceOnly());
        assertEquals(fixture.providerPayloadBlueId, providerReference.getBlueId());
        assertSuccessful(result);
        assertScopeIdentity(result.document(), recorder, "/child", expectedChild);
        assertNotEquals(unchecked, markerDocumentId(result.document(), "/child"));
    }

    @Test
    void shouldVerifyExactScopeIdentityDoesNotInvokeTheLegacyContentIdentityManager() {
        // given
        ScopeFixture fixture = new ScopeFixture();
        LifecycleRecorder recorder = new LifecycleRecorder();
        IdentityFailureRuntime runtime = fixture.identityFailureRuntime(
                recorder,
                new IllegalStateException("Content identity manager must not be invoked"));

        // when
        DocumentProcessingResult result =
                runtime.processor.initializeDocument(fixture.source(true));

        // then
        assertSuccessful(result);
        assertTrue(runtime.manager.requestedScopes.isEmpty());
    }

    @Test
    void shouldVerifySnapshotBackedRootIdentityUsesTheExactCanonicalNodeWithoutProviderLookup() {
        // given
        SnapshotProviderFailureFixture fixture = new SnapshotProviderFailureFixture();
        ResolvedSnapshot producerSnapshot = fixture.producerSnapshot();
        IdentityFailingSnapshotManager manager =
                new IdentityFailingSnapshotManager(
                        fixture.producerManager(),
                        new IllegalStateException("Content identity manager must not be invoked"));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(producerSnapshot, null, manager);

        // when
        FrozenNode document =
                runtime.capturePreInitializationScopeDocument("/");

        // then
        assertTrue(producerSnapshot.frozenCanonicalRoot()
                .sameResolvedStructure(document));
        assertTrue(manager.requestedScopes.isEmpty());
    }

    private static void assertSuccessful(DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), diagnosticMessage(result));
        assertFalse(isCapabilityFailure(result), diagnosticMessage(result));
        assertNull(diagnosticCategory(result), diagnosticMessage(result));
    }

    private static void assertRootInitializationIdentity(
            DocumentProcessingResult result,
            String expected) {
        assertSuccessful(result);
        assertEquals(expected, markerDocumentId(result.document(), "/"));
        assertTrue(result.events().isEmpty(),
                "processor-generated lifecycle delivery is not a Root handler emission");
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
        Node initialDocument = document.getAsNode(
                prefix + "/contracts/initialized/document");
        return initialDocument != null
                ? DirectBlueIdCalculator.calculateBlueId(initialDocument)
                : null;
    }

    private static String emptyNodeBlueId() {
        return DirectBlueIdCalculator.calculateBlueId(new Node());
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
            ResolvedSnapshot snapshot = producerBlue().resolveToSnapshot(source);
            assertEquals("resolved-by-producer", snapshot.resolvedRoot().getAsText("/fixed"));
            assertEquals(typeBlueId,
                    snapshot.frozenCanonicalRoot().getType().getReferenceBlueId());
            return snapshot;
        }

        private ProcessingSnapshotManager producerManager() {
            return producerBlue().getDocumentProcessor().snapshotManager();
        }

        private Blue producerBlue() {
            return new Blue(producerProvider);
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
                    .snapshotStore(manager)
                    .conformanceEngine(configuredProcessor.conformanceEngine())
                    .matchingService(configuredProcessor.matchingService())
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

        private Node exactChildBeforeLifecycle(Node exactRoot) {
            return exactRoot.getAsNode("/child").clone();
        }

        private ExpectedIdentities expectedBeforeLifecycle(Node exactRoot) {
            ResolvedSnapshot snapshot =
                    identityBlue().resolveToSnapshot(exactRoot.clone());
            FrozenNode canonicalChild = snapshot.canonicalAt("/child");
            String childId = canonicalChild != null
                    ? canonicalChild.blueId()
                    : DirectBlueIdCalculator.calculateBlueId(
                    exactChildBeforeLifecycle(exactRoot));

            Node rootAfterChildPhase1 = exactRoot.clone();
            Node child = rootAfterChildPhase1.getAsNode("/child");
            child.properties("lifecycleMutation", text(CHILD_MUTATION));
            if (child.getContracts() == null) {
                child.contracts(new Node());
            }
            child.getContracts().properties(
                    "initialized",
                    initializedMarker(
                            canonicalChild != null
                                    ? canonicalChild.toNode()
                                    : exactChildBeforeLifecycle(
                                    exactRoot)));
            String rootId = identityBlue()
                    .resolveToSnapshot(rootAfterChildPhase1)
                    .blueId();
            return new ExpectedIdentities(childId, rootId);
        }

        private Node initializedMarker(Node document) {
            return new Node()
                    .type(reference(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                    .properties("document", document.clone());
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
            Node document = context.event().getProperties().get("document");
            if (document == null) {
                // The same lifecycle channel also carries termination. This
                // observer is deliberately scoped to initiation identity.
                return;
            }
            recorder.record(context.scopePath(),
                    DirectBlueIdCalculator.calculateBlueId(document),
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
