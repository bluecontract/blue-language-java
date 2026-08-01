package blue.language.processor;

import blue.language.Blue;
import blue.language.api.BlueOperationOutcome;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.conformance.MockExternalChannelProcessor;
import blue.language.processor.conformance.MockHandlerProcessor;
import blue.language.processor.conformance.MockTypeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.provider.DirectNodeManifest;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact provider-outcome matrix for fragmented PROCESS inputs and lazily
 * selected executable bodies.
 */
final class FragmentedProcessingFailureMatrixTest {

    private static final String CHANNEL = "incoming";
    private static final String SELECTED_HANDLER = "selected";
    private static final String UNSELECTED_HANDLER = "unselected";
    private static final String SUBSCRIPTION_KEY = "failure-matrix";
    private static final String DOMAIN_DISCRIMINATOR =
            "failure-matrix-domain";
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.<Object>asList(
                    9191, "failure-matrix", 1));

    @Test
    void shouldRejectMissingRootBeforePortableGasAdmission() {
        // given
        try (Fixture rootMissing = Fixture.create()) {
            rootMissing.provider.outcome(
                    rootMissing.rootBlueId,
                    NodeProviderResult.notFound());

            // when
            ProcessAttemptResult attempt =
                    rootMissing.attempt();

            // then
            assertPreGasInvalid(
                    attempt,
                    rootMissing.rootReference(),
                    rootMissing.rootBlueId);
            assertEquals(
                    Collections.singletonList(
                            rootMissing.rootBlueId),
                    rootMissing.provider.requests());
        }
    }

    @Test
    void shouldRejectMissingEventBeforePortableGasAdmission() {
        // given
        try (Fixture eventMissing = Fixture.create()) {
            eventMissing.provider.outcome(
                    eventMissing.eventBlueId,
                    NodeProviderResult.notFound());

            // when
            ProcessAttemptResult attempt =
                    eventMissing.attempt();

            // then
            assertPreGasInvalid(
                    attempt,
                    eventMissing.rootReference(),
                    eventMissing.rootBlueId);
            assertEquals(
                    Arrays.asList(
                            eventMissing.rootBlueId,
                            eventMissing.eventBlueId),
                    eventMissing.provider.requests());
        }
    }

    @Test
    void shouldRejectInvalidRootEvidenceBeforeSemanticAdmission() {
        // given
        try (Fixture invalidRoot = Fixture.create()) {
            invalidRoot.provider.forged(
                    invalidRoot.rootBlueId,
                    new Node().value("forged Root"));

            // when
            ProcessAttemptResult attempt =
                    invalidRoot.attempt();

            // then
            assertPreGasInvalid(
                    attempt,
                    invalidRoot.rootReference(),
                    invalidRoot.rootBlueId);
            assertTrue(
                    attempt.processResult()
                            .diagnostic()
                            .message()
                            .contains("BlueId"));
        }
    }

    @Test
    void shouldRejectInvalidEventEvidenceBeforeSemanticAdmission() {
        // given
        try (Fixture invalidEvent = Fixture.create()) {
            invalidEvent.provider.forged(
                    invalidEvent.eventBlueId,
                    new Node().value("forged Event"));

            // when
            ProcessAttemptResult attempt =
                    invalidEvent.attempt();

            // then
            assertPreGasInvalid(
                    attempt,
                    invalidEvent.rootReference(),
                    invalidEvent.rootBlueId);
            assertTrue(
                    attempt.processResult()
                            .diagnostic()
                            .message()
                            .contains("BlueId"));
        }
    }

    @Test
    void shouldRollBackWhenSelectedBodyIsMissing() {
        // given
        try (Fixture bodyMissing = Fixture.create()) {
            bodyMissing.provider.outcome(
                    bodyMissing.selectedBodyBlueId,
                    NodeProviderResult.notFound());

            // when
            ProcessAttemptResult missingAttempt =
                    bodyMissing.attempt();

            // then
            assertSelectedBodyFailure(
                    missingAttempt,
                    bodyMissing,
                    ProcessorStatus
                            .INVALID_PROCESSING_DOCUMENT);
            assertTrue(
                    missingAttempt.processResult()
                            .diagnostic()
                            .message()
                            .contains(
                                    bodyMissing
                                            .selectedBodyBlueId));
        }
    }

    @Test
    void shouldRollBackWhenSelectedBodyEvidenceIsInvalid() {
        // given
        try (Fixture bodyInvalid = Fixture.create()) {
            bodyInvalid.provider.forged(
                    bodyInvalid.selectedBodyBlueId,
                    new Node().value("forged selected body"));

            // when
            ProcessAttemptResult invalidAttempt =
                    bodyInvalid.attempt();

            // then
            assertSelectedBodyFailure(
                    invalidAttempt,
                    bodyInvalid,
                    ProcessorStatus
                            .INVALID_PROCESSING_DOCUMENT);
            assertTrue(
                    invalidAttempt.processResult()
                            .diagnostic()
                            .message()
                            .contains("BlueId"));
        }
    }

    @Test
    void shouldVerifySelectedBodyUnavailableSuspendsWithoutPortableGasAndRetryMatches() {
        // given
        DocumentProcessingResult available;
        try (Fixture baseline = Fixture.create()) {
            available = requireSuccess(
                    baseline.attempt(), baseline);
        }

        try (Fixture suspended = Fixture.create()) {
            suspended.provider.outcome(
                    suspended.selectedBodyBlueId,
                    NodeProviderResult.unavailable(
                            "selected body transport is transiently unavailable"));

            // when
            ProcessAttemptResult unavailable =
                    suspended.attempt();
            suspended.provider.clearOutcome(
                    suspended.selectedBodyBlueId);
            suspended.provider.clearRequests();
            DocumentProcessingResult retried =
                    requireSuccess(
                            suspended.attempt(),
                            suspended);
            int selectedBodyRequestCount =
                    Collections.frequency(
                            suspended.provider.requests(),
                            suspended.selectedBodyBlueId);

            // then
            assertEquals(
                    ProcessAttemptResult.Kind
                            .NEEDS_RESOURCES,
                    unavailable.kind());
            assertEquals(
                    Collections.singletonList(
                            suspended.selectedBodyBlueId),
                    unavailable.requiredExactBlueIds());
            assertNull(unavailable.processResult());
            assertNull(unavailable.portableGas());
            assertEquals(
                    suspended.rootBlueId,
                    BlueIdCalculator.calculateBlueId(
                            suspended.rootReference()));
            assertEquivalentSuccess(
                    available, retried);
            assertEquals(1, selectedBodyRequestCount);
        }
    }

    @Test
    void shouldVerifyUnavailableUnselectedBodyDoesNotAffectSuccess() {
        // given
        DocumentProcessingResult available;
        try (Fixture baseline = Fixture.create()) {
            available = requireSuccess(
                    baseline.attempt(), baseline);
        }

        try (Fixture unselectedUnavailable =
                     Fixture.create()) {
            unselectedUnavailable.provider.outcome(
                    unselectedUnavailable
                            .unselectedBodyBlueId,
                    NodeProviderResult.unavailable(
                            "unselected body must stay cold"));

            // when
            DocumentProcessingResult actual =
                    requireSuccess(
                            unselectedUnavailable.attempt(),
                            unselectedUnavailable);

            // then
            assertEquivalentSuccess(available, actual);
            assertFalse(
                    unselectedUnavailable
                            .provider
                            .requests()
                            .contains(
                                    unselectedUnavailable
                                            .unselectedBodyBlueId));
        }
    }

    @Test
    void shouldVerifyPartialDirectManifestCannotEstablishAbsentField() {
        // given
        Node knownDirectContent = new Node()
                .properties(
                        "known",
                        new Node().value("present"));
        Node referenced = new Node().properties(
                "child",
                new Node().blueId(
                        BlueIdCalculator.calculateBlueId(
                                new Node().value("child"))));

        // when
        BlueOperationOutcome partialOutcome =
                DirectNodeManifest
                        .partial(knownDirectContent)
                        .semanticSelect("/missing")
                        .outcome();
        BlueOperationOutcome completeOutcome =
                DirectNodeManifest
                        .complete(knownDirectContent)
                        .semanticSelect("/missing")
                        .outcome();
        BlueOperationOutcome referenceWrapperOutcome =
                DirectNodeManifest
                        .complete(referenced)
                        .semanticSelect("/child/blueId")
                        .outcome();

        // then
        assertEquals(
                BlueOperationOutcome.INCOMPLETE,
                partialOutcome);
        assertEquals(
                BlueOperationOutcome.ABSENT,
                completeOutcome);
        assertEquals(
                BlueOperationOutcome.ABSENT,
                referenceWrapperOutcome,
                "a pure reference wrapper's blueId is not "
                        + "a semantic child");
    }

    private static void assertPreGasInvalid(
            ProcessAttemptResult attempt,
            Node originalRoot,
            String rootBlueId) {
        assertEquals(
                ProcessAttemptResult.Kind.COMPLETE,
                attempt.kind());
        assertNotNull(attempt.processResult());
        assertEquals(
                ProcessorStatus
                        .INVALID_PROCESSING_DOCUMENT,
                attempt.processResult().status());
        assertFalse(attempt.processResult().commits());
        assertEquals(0L,
                attempt.processResult().totalGas());
        assertEquals(Long.valueOf(0L),
                attempt.portableGas());
        assertTrue(
                attempt.processResult().events().isEmpty());
        assertEquals(
                rootBlueId,
                BlueIdCalculator.calculateBlueId(
                        attempt.processResult()
                                .document()));
        assertEquals(
                rootBlueId,
                BlueIdCalculator.calculateBlueId(
                        originalRoot));
    }

    private static void assertSelectedBodyFailure(
            ProcessAttemptResult attempt,
            Fixture fixture,
            ProcessorStatus expectedStatus) {
        assertEquals(
                ProcessAttemptResult.Kind.COMPLETE,
                attempt.kind());
        DocumentProcessingResult result =
                attempt.processResult();
        assertNotNull(result);
        assertEquals(expectedStatus, result.status());
        assertFalse(result.commits());
        assertEquals(
                fixture.rootBlueId,
                BlueIdCalculator.calculateBlueId(
                        result.document()));
        assertEquals(
                "pending",
                textAt(result.document(), "/state"));
        assertTrue(result.events().isEmpty());
        assertNull(checkpoint(result.document()));
        assertTrue(
                result.totalGas() > 0L,
                "semantic work admitted before definitive "
                        + "selected-body failure stays charged");
        assertEquals(
                Long.valueOf(result.totalGas()),
                attempt.portableGas());
        assertEquals(
                1,
                Collections.frequency(
                        fixture.provider.requests(),
                        fixture.selectedBodyBlueId));
        assertFalse(
                fixture.provider.requests().contains(
                        fixture.unselectedBodyBlueId));
    }

    private static DocumentProcessingResult requireSuccess(
            ProcessAttemptResult attempt,
            Fixture fixture) {
        assertEquals(
                ProcessAttemptResult.Kind.COMPLETE,
                attempt.kind());
        DocumentProcessingResult result =
                attempt.processResult();
        assertNotNull(result);
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                result.diagnostic() == null
                        ? null
                        : result.diagnostic().message());
        assertTrue(result.commits());
        assertEquals(
                "processed",
                textAt(result.document(), "/state"));
        assertEquals(1, result.events().size());
        assertNotNull(checkpoint(result.document()));
        assertEquals(
                1,
                Collections.frequency(
                        fixture.provider.requests(),
                        fixture.selectedBodyBlueId));
        assertFalse(
                fixture.provider.requests().contains(
                        fixture.unselectedBodyBlueId));
        return result;
    }

    private static void assertEquivalentSuccess(
            DocumentProcessingResult expected,
            DocumentProcessingResult actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        expected.document()),
                BlueIdCalculator.calculateBlueId(
                        actual.document()));
        assertEquals(
                expected.document().toString(),
                actual.document().toString());
        assertEquals(
                nodeBlueIds(expected.events()),
                nodeBlueIds(actual.events()));
        assertEquals(
                expected.totalGas(),
                actual.totalGas());
        assertEquals(
                diagnostic(expected.diagnostic()),
                diagnostic(actual.diagnostic()));
    }

    private static String textAt(
            Node node,
            String path) {
        Node selected = node.getNode(path);
        return selected != null
                && selected.getValue() != null
                ? String.valueOf(selected.getValue())
                : null;
    }

    private static Node checkpoint(Node root) {
        return root.getContracts() != null
                && root.getContracts()
                        .getProperties() != null
                ? root.getContracts()
                        .getProperties()
                        .get("checkpoint")
                : null;
    }

    private static List<String> nodeBlueIds(
            List<Node> nodes) {
        List<String> blueIds =
                new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            blueIds.add(
                    BlueIdCalculator.calculateBlueId(
                            node));
        }
        return Collections.unmodifiableList(blueIds);
    }

    private static String diagnostic(
            ProcessorDiagnostic diagnostic) {
        return diagnostic == null
                ? null
                : diagnostic.category()
                + "|" + diagnostic.message()
                + "|" + diagnostic.details();
    }

    private static Node list(Node... nodes) {
        return new Node().items(
                new ArrayList<>(
                        Arrays.asList(nodes)));
    }

    private static final class Fixture
            implements AutoCloseable {
        private final OutcomeProvider provider;
        private final Blue blue;
        private final DocumentProcessor processor;
        private final String rootBlueId;
        private final String eventBlueId;
        private final String selectedBodyBlueId;
        private final String unselectedBodyBlueId;

        private Fixture(
                OutcomeProvider provider,
                Blue blue,
                DocumentProcessor processor,
                String rootBlueId,
                String eventBlueId,
                String selectedBodyBlueId,
                String unselectedBodyBlueId) {
            this.provider = provider;
            this.blue = blue;
            this.processor = processor;
            this.rootBlueId = rootBlueId;
            this.eventBlueId = eventBlueId;
            this.selectedBodyBlueId =
                    selectedBodyBlueId;
            this.unselectedBodyBlueId =
                    unselectedBodyBlueId;
        }

        private static Fixture create() {
            Node emitted = new Node()
                    .properties(
                            "kind",
                            new Node().value(
                                    "failure-matrix-result"));
            Node selectedBody = new Node()
                    .properties(
                            "patches",
                            list(new Node()
                                    .properties(
                                            "op",
                                            new Node().value(
                                                    "replace"))
                                    .properties(
                                            "path",
                                            new Node().value(
                                                    "/state"))
                                    .properties(
                                            "val",
                                            new Node().value(
                                                    "processed"))))
                    .properties(
                            "events",
                            list(emitted));
            String selectedBodyBlueId =
                    BlueIdCalculator.calculateBlueId(
                            selectedBody);
            Node unselectedBody = new Node()
                    .properties(
                            "patches",
                            list())
                    .properties(
                            "events",
                            list())
                    .properties(
                            "payload",
                            new Node().value(
                                    "must remain unavailable "
                                            + "and unselected"));
            String unselectedBodyBlueId =
                    BlueIdCalculator.calculateBlueId(
                            unselectedBody);

            Node channel = new Node()
                    .type(new Node().blueId(
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL))
                    .properties(
                            "order",
                            new Node().value(0))
                    .properties(
                            "subscriptionKey",
                            new Node().value(
                                    SUBSCRIPTION_KEY))
                    .properties(
                            "eventKey",
                            new Node().value(
                                    SUBSCRIPTION_KEY))
                    .properties(
                            "accept",
                            new Node().value(true))
                    .properties(
                            "checkpointDomain",
                            new Node().value(
                                    DOMAIN_DISCRIMINATOR));
            String contribution =
                    BlueIdCalculator.calculateBlueId(
                            channel);
            String domain = CheckpointDomain.derive(
                    MockTypeBlueIds
                            .MOCK_EXTERNAL_CHANNEL,
                    Collections.singletonList(
                            contribution),
                    DOMAIN_DISCRIMINATOR);

            Node contracts = new Node()
                    .properties(
                            "initialized",
                            new Node()
                                    .type(new Node().blueId(
                                            RuntimeBlueIds
                                                    .PROCESSING_INITIALIZED_MARKER))
                                    .properties(
                                            "document",
                                            new Node().value(
                                                    "failure-matrix")))
                    .properties(CHANNEL, channel)
                    .properties(
                            SELECTED_HANDLER,
                            handler(
                                    selectedBodyBlueId,
                                    null,
                                    0))
                    .properties(
                            UNSELECTED_HANDLER,
                            handler(
                                    unselectedBodyBlueId,
                                    "never-selected",
                                    1));
            Node root = new Node()
                    .properties(
                            "state",
                            new Node().value("pending"))
                    .contracts(contracts);
            String rootBlueId =
                    BlueIdCalculator.calculateBlueId(root);
            Node event = new Node()
                    .properties(
                            "subscriptionKey",
                            new Node().value(
                                    SUBSCRIPTION_KEY))
                    .properties(
                            "kind",
                            new Node().value("selected"))
                    .properties(
                            "eventId",
                            new Node().value(
                                    "failure-matrix-event"));
            String eventBlueId =
                    BlueIdCalculator.calculateBlueId(
                            event);

            Map<String, Node> exact =
                    new LinkedHashMap<>();
            exact.put(rootBlueId, root);
            exact.put(eventBlueId, event);
            exact.put(
                    selectedBodyBlueId,
                    selectedBody);
            exact.put(
                    unselectedBodyBlueId,
                    unselectedBody);
            OutcomeProvider provider =
                    new OutcomeProvider(exact);
            Blue blue = new Blue(provider);
            BlueRuntimeTypeRegistry runtimeTypes =
                    BlueRuntimeTypeRegistry.getDefault();
            ExternalDeliveryPlan plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(41L, 41L)
                            .eventOrderKey(EVENT_ORDER)
                            .delivery(
                                    ExternalDeliverySnapshot
                                            .builder(
                                                    "/",
                                                    CHANNEL)
                                            .order(0)
                                            .sourceContribution(
                                                    contribution)
                                            .effectiveTypeBlueId(
                                                    MockTypeBlueIds
                                                            .MOCK_EXTERNAL_CHANNEL)
                                            .subscriptionKey(
                                                    SUBSCRIPTION_KEY)
                                            .checkpointDomainBlueId(
                                                    domain)
                                            .checkpointSubjectBlueId(
                                                    eventBlueId)
                                            .build())
                            .activeSubscriptionInterval(
                                    new SubscriptionDelta.Entry(
                                            "/",
                                            CHANNEL,
                                            MockTypeBlueIds
                                                    .MOCK_EXTERNAL_CHANNEL,
                                            Collections.singletonList(
                                                    contribution),
                                            0,
                                            Collections.singletonList(
                                                    SUBSCRIPTION_KEY),
                                            domain,
                                            0L,
                                            null,
                                            null))
                            .exactRuntimeState()
                            .build();
            DocumentProcessor processor =
                    DocumentProcessor.builder()
                            .withMatchingService(
                                    new ContractMatchingService(
                                            blue))
                            .withConformanceEngine(
                                    blue.conformanceEngine())
                            .withSnapshotManager(
                                    blue.getDocumentProcessor()
                                            .snapshotManager())
                            .withGasSchedule(
                                    GasSchedule.contracts10())
                            .withRuntimeRegistryIdentity(
                                    RuntimeBlueIds
                                            .REGISTRY_PACKAGE_IDENTITY)
                            .registerContractProcessor(
                                    MockTypeBlueIds
                                            .MOCK_EXTERNAL_CHANNEL,
                                    runtimeTypes.node(
                                            RuntimeTypeKey
                                                    .SCRIPTED_EXTERNAL_CHANNEL),
                                    new MockExternalChannelProcessor())
                            .registerContractProcessor(
                                    MockTypeBlueIds.MOCK_HANDLER,
                                    runtimeTypes.node(
                                            RuntimeTypeKey
                                                    .SCRIPTED_HANDLER),
                                    new MockHandlerProcessor())
                            .withExternalDeliveryPlanDeriver(
                                    (ignoredRoot,
                                     ignoredEvent) -> plan)
                            .build();
            return new Fixture(
                    provider,
                    blue,
                    processor,
                    rootBlueId,
                    eventBlueId,
                    selectedBodyBlueId,
                    unselectedBodyBlueId);
        }

        private static Node handler(
                String bodyBlueId,
                String eventKind,
                int order) {
            Node handler = new Node()
                    .type(new Node().blueId(
                            MockTypeBlueIds
                                    .MOCK_HANDLER))
                    .properties(
                            "channel",
                            new Node().value(CHANNEL))
                    .properties(
                            "order",
                            new Node().value(order))
                    .properties(
                            "result",
                            new Node().blueId(
                                    bodyBlueId));
            if (eventKind != null) {
                handler.properties(
                        "event",
                        new Node().properties(
                                "kind",
                                new Node().value(
                                        eventKind)));
            }
            return handler;
        }

        private ProcessAttemptResult attempt() {
            return processor.processAttempt(
                    rootReference(),
                    new Node().blueId(eventBlueId));
        }

        private Node rootReference() {
            return new Node().blueId(rootBlueId);
        }

        @Override
        public void close() {
            processor.close();
            blue.close();
        }
    }

    private static final class OutcomeProvider
            implements NodeProvider {
        private final Map<String, Node> exact;
        private final Map<String, NodeProviderResult>
                outcomes = new LinkedHashMap<>();
        private final Map<String, Node>
                forged = new LinkedHashMap<>();
        private final List<String> requests =
                new ArrayList<>();

        private OutcomeProvider(
                Map<String, Node> exact) {
            this.exact = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry :
                    exact.entrySet()) {
                this.exact.put(
                        entry.getKey(),
                        entry.getValue().clone());
            }
        }

        @Override
        public synchronized List<Node> fetchByBlueId(
                String blueId) {
            NodeProviderResult result =
                    fetchResultByBlueId(blueId);
            if (result.outcome()
                    == NodeProviderOutcome.FOUND) {
                return result.nodes();
            }
            if (result.outcome()
                    == NodeProviderOutcome.UNAVAILABLE) {
                throw new IllegalStateException(
                        result.diagnostic().orElse(
                                "provider unavailable"));
            }
            if (result.outcome()
                    == NodeProviderOutcome.INVALID_EVIDENCE) {
                throw new IllegalArgumentException(
                        result.diagnostic().orElse(
                                "invalid provider evidence"));
            }
            return null;
        }

        @Override
        public synchronized NodeProviderResult
        fetchResultByBlueId(String blueId) {
            requests.add(blueId);
            Node forgedNode = forged.get(blueId);
            if (forgedNode != null) {
                return NodeProviderResult.found(
                        Collections.singletonList(
                                forgedNode));
            }
            NodeProviderResult outcome =
                    outcomes.get(blueId);
            if (outcome != null) {
                return outcome;
            }
            Node node = exact.get(blueId);
            return node != null
                    ? NodeProviderResult.found(
                            Collections.singletonList(
                                    node))
                    : NodeProviderResult.notFound();
        }

        private synchronized void outcome(
                String blueId,
                NodeProviderResult outcome) {
            outcomes.put(blueId, outcome);
        }

        private synchronized void forged(
                String blueId,
                Node node) {
            forged.put(blueId, node.clone());
        }

        private synchronized void clearOutcome(
                String blueId) {
            outcomes.remove(blueId);
            forged.remove(blueId);
        }

        private synchronized List<String> requests() {
            return Collections.unmodifiableList(
                    new ArrayList<>(requests));
        }

        private synchronized void clearRequests() {
            requests.clear();
        }
    }
}
