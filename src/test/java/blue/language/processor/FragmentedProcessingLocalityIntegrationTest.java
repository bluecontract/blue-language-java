package blue.language.processor;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.conformance.MockExternalChannelProcessor;
import blue.language.processor.conformance.MockHandler;
import blue.language.processor.conformance.MockHandlerProcessor;
import blue.language.processor.conformance.MockTypeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.provider.SequentialNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden proof that PROCESS is representation-blind while Root, Event,
 * selected executable body, and unrelated data are separate exact
 * content-addressed fragments.
 *
 * <p>The provider is deliberately hostile to accidental graph expansion: the
 * four unselected executable bodies, the Event message, and the large archive
 * are present in its backing store, but asking for any of them fails the test
 * immediately.</p>
 */
final class FragmentedProcessingLocalityIntegrationTest {

    private static final String SELECTED_CHANNEL = "incoming";
    private static final String REJECTED_CHANNEL = "rejected";
    private static final String SELECTED_HANDLER = "selectedWorkflow";
    private static final String SUBSCRIPTION_KEY = "fragmented-golden";
    private static final String CHECKPOINT_DISCRIMINATOR =
            "fragmented-golden-checkpoint-v1";
    private static final int LARGE_PAYLOAD_SIZE = 24_000;
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.<Object>asList(
                    8080, "fragmented-golden", 1));

    @Test
    void shouldVerifyExactRootAndEventFragmentsHaveIdenticalSemanticsAcrossMatrix() {
        // given
        Scenario scenario = Scenario.create();
        List<Variant> variants =
                Variant.requiredMatrix();

        // when
        List<Run> runs = new ArrayList<>(variants.size());
        List<SemanticProjection> projections =
                new ArrayList<>(variants.size());
        for (Variant variant : variants) {
            runs.add(execute(scenario, variant));
        }
        for (Run run : runs) {
            projections.add(
                    SemanticProjection.of(run.debug));
        }
        SemanticProjection baseline = projections.get(0);

        // then
        for (int index = 0; index < runs.size(); index++) {
            Run run = runs.get(index);
            assertGoldenLocality(run);
            if (index > 0) {
                assertEquals(
                        baseline,
                        projections.get(index),
                        "semantic drift for " + run.variant);
            }
        }
        assertNotNull(baseline);
        assertEquals(ProcessorStatus.SUCCESS, baseline.status);
        assertEquals(8, variants.size());
        SemanticLocalityEvidenceWriter.write(
                "fragmented-matrix.json",
                localityEvidence(runs));
    }

    private static Map<String, Object> localityEvidence(List<Run> runs) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", "blue-language-locality-evidence/1.0");
        List<Map<String, Object>> observations = new ArrayList<>();
        for (Run run : runs) {
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("variant", run.variant.toString());
            observation.put("requiredBlueIds", Arrays.asList(
                    run.scenario.rootBlueId,
                    run.scenario.eventBlueId,
                    run.scenario.selectedBodyBlueId));
            observation.put("forbiddenBlueIds",
                    new ArrayList<>(run.scenario.forbiddenBlueIds));
            observation.put("primaryRequestedBlueIds",
                    run.primaryMetrics.requestedBlueIds);
            observation.put("primarySemanticDemands",
                    run.debug.trace().semanticDemands());
            observation.put("primaryBackendLoadedBlueIds",
                    new ArrayList<>(run.primaryMetrics.backendLoadedBlueIds));
            observation.put("primaryBackendBytes",
                    run.primaryMetrics.backendBytes);
            observation.put("replayRequestedBlueIds",
                    run.replayMetrics.requestedBlueIds);
            observation.put("replaySemanticDemands",
                    run.replay.trace().semanticDemands());
            observation.put("replayBackendLoadedBlueIds",
                    new ArrayList<>(run.replayMetrics.backendLoadedBlueIds));
            observation.put("replayBackendBytes",
                    run.replayMetrics.backendBytes);
            observations.add(observation);
        }
        evidence.put("observations", observations);
        return evidence;
    }

    @Test
    void shouldVerifyResultingRootCollapsesAndExpandsThroughExactFragments() {
        // given
        Scenario scenario = Scenario.create();
        Run run = execute(
                scenario,
                Variant.requiredMatrix().get(3));
        Node resultingRoot =
                run.debug.processResult().document();
        String resultingRootBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        resultingRoot);
        ExactNodeGraphFragments resultingFragments =
                new ExactNodeGraphFragments(
                        resultingRoot);
        Map<String, Node> roundTripFragments =
                new LinkedHashMap<>();
        roundTripFragments.putAll(
                scenario.allowedFragments);
        roundTripFragments.putAll(
                scenario.forbiddenFragments);
        roundTripFragments.putAll(
                resultingFragments.fragments());
        // when
        Node domain = checkpointDomainNode(
                MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                Collections.singletonList(
                        DirectBlueIdCalculator.calculateBlueId(
                                scenario.inlineRoot
                                        .getContracts()
                                        .getProperties()
                                        .get(
                                                SELECTED_CHANNEL))),
                CHECKPOINT_DISCRIMINATOR);
        roundTripFragments.put(
                scenario.selectedCheckpointDomain,
                domain);
        NodeProvider roundTripProvider = blueId -> {
            Node fragment =
                    roundTripFragments.get(blueId);
            return fragment != null
                    ? Collections.singletonList(
                            fragment.clone())
                    : null;
        };
        boolean collapsedReferenceOnly;
        String collapsedBlueId;
        String expandedBlueId;
        String recollapsedBlueId;
        Object expandedValue;
        Object expandedFromRootValue;
        try (Blue roundTripBlue =
                     new Blue(roundTripProvider)) {
            Node collapsed =
                    roundTripBlue.collapse(
                            resultingRoot);
            Node expanded =
                    roundTripBlue.expand(collapsed);
            collapsedReferenceOnly = collapsed.isReferenceOnly();
            collapsedBlueId = collapsed.getBlueId();
            expandedBlueId =
                    DirectBlueIdCalculator.calculateBlueId(expanded);
            recollapsedBlueId =
                    roundTripBlue.collapse(expanded).getBlueId();
            expandedValue = NodeWireForm.get(expanded);
            expandedFromRootValue =
                    NodeWireForm.get(
                            roundTripBlue.expand(
                                    resultingRoot.clone()));
        }

        // then
        assertEquals(
                scenario.selectedCheckpointDomain,
                DirectBlueIdCalculator.calculateBlueId(domain));
        assertTrue(collapsedReferenceOnly);
        assertEquals(resultingRootBlueId, collapsedBlueId);
        assertEquals(resultingRootBlueId, expandedBlueId);
        assertEquals(resultingRootBlueId, recollapsedBlueId);
        assertEquals(expandedValue, expandedFromRootValue);
    }

    private static Run execute(
            Scenario scenario,
            Variant variant) {
        StrictFragmentProvider fragments =
                new StrictFragmentProvider(
                        scenario.allowedFragments,
                        scenario.forbiddenFragments,
                        variant.providerMode);
        if (variant.warm) {
            fragments.warmAllowed();
        }
        BlueRuntimeTypeRegistry runtimeTypes =
                BlueRuntimeTypeRegistry.getDefault();
        Blue blue = new Blue(new SequentialNodeProvider(
                runtimeTypes.asProvider(),
                fragments));
        ReadingMockHandlerProcessor handlers =
                new ReadingMockHandlerProcessor();
        DocumentProcessor processor = DocumentProcessor.builder()
                .withMatchingService(
                        new ContractMatchingService(blue))
                .withConformanceEngine(
                        blue.conformanceEngine())
                .withSnapshotManager(
                        blue.getDocumentProcessor()
                                .snapshotManager())
                .withGasSchedule(GasSchedule.contracts10())
                .withRuntimeRegistryIdentity(
                        RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY)
                .registerContractProcessor(
                        MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                        runtimeTypes.node(
                                RuntimeTypeKey
                                        .SCRIPTED_EXTERNAL_CHANNEL),
                        new MockExternalChannelProcessor())
                .registerContractProcessor(
                        MockTypeBlueIds.MOCK_HANDLER,
                        runtimeTypes.node(
                                RuntimeTypeKey.SCRIPTED_HANDLER),
                        handlers)
                .withExternalDeliveryPlanDeriver(
                        (root, event) -> scenario.plan)
                .build();
        try {
            fragments.resetMetrics();
            ProcessingDebugResult debug =
                    processor.processDocumentWithTrace(
                            variant.document(scenario),
                            variant.event(scenario));
            ProviderMetrics primaryMetrics =
                    fragments.metrics();
            int primaryReads = handlers.rootReads();

            /*
             * A replay from the returned exact Root must stop at the raw
             * source checkpoint before executable-body admission. The
             * snapshot-native result is the authoritative returned Root view
             * and preserves the unchanged fragment boundary.
             */
            fragments.resetMetrics();
            handlers.resetRootReads();
            ProcessingDebugResult replay =
                    processor.processDocumentWithTrace(
                            Objects.requireNonNull(
                                    debug.resultingSnapshot(),
                                    "successful run must return "
                                            + "its exact snapshot"),
                            scenario.inlineEvent.clone());
            ProviderMetrics replayMetrics =
                    fragments.metrics();
            int replayReads = handlers.rootReads();
            return new Run(
                    variant,
                    scenario,
                    debug,
                    replay,
                    primaryMetrics,
                    replayMetrics,
                    primaryReads,
                    replayReads);
        } finally {
            processor.close();
            blue.close();
        }
    }

    private static void assertGoldenLocality(Run run) {
        String context = run.variant.toString();
        DocumentProcessingResult result =
                run.debug.processResult();
        ProcessingConformanceTrace trace =
                run.debug.trace();

        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                context + ": "
                        + diagnosticProjection(
                        result.diagnostic()));
        assertEquals(
                "processed",
                textAt(result.document(), "/state"),
                context + ": selected patch did not commit");
        assertEquals(
                1,
                run.primaryRootReads,
                context + ": selected generic Handler did not read "
                        + "the small Root field exactly once");

        assertEquals(
                Collections.singletonList(
                        run.scenario.rootEventBlueId),
                nodeBlueIds(result.events()),
                context + ": Root outbox drift");
        assertEquals(
                2,
                trace.records(
                        ProcessingTraceRecord.Kind
                                .EXTERNAL_DELIVERY)
                        .size(),
                context + ": evidence occurrence count drift");
        assertEquals(
                SELECTED_CHANNEL,
                trace.records(
                        ProcessingTraceRecord.Kind
                                .EXTERNAL_DELIVERY)
                        .get(0).contractKey(),
                context);
        assertEquals(
                REJECTED_CHANNEL,
                trace.records(
                        ProcessingTraceRecord.Kind
                                .EXTERNAL_DELIVERY)
                        .get(1).contractKey(),
                context);

        assertEquals(
                run.variant.documentForm
                        == DocumentForm.INLINE
                        ? 0
                        : 1,
                frequency(
                        run.primaryMetrics.requestedBlueIds,
                        run.scenario.selectedBodyBlueId),
                context + ": selected executable body provider "
                        + "demand drift");
        assertEquals(
                1,
                frequency(
                        trace.semanticDemands(),
                        run.scenario.selectedBodyBlueId),
                context + ": selected executable body must be one "
                        + "logical demand");
        assertTrue(
                Collections.disjoint(
                        run.primaryMetrics.requestedBlueIds,
                        run.scenario.forbiddenBlueIds),
                context + ": forbidden physical demand "
                        + run.primaryMetrics.requestedBlueIds);
        assertTrue(
                Collections.disjoint(
                        trace.semanticDemands(),
                        run.scenario.forbiddenBlueIds),
                context + ": forbidden semantic demand "
                        + trace.semanticDemands());
        if (run.variant.documentForm
                == DocumentForm.INLINE) {
            assertTrue(
                    Collections.disjoint(
                            run.primaryMetrics
                                    .requestedBlueIds,
                            run.scenario
                                    .contractHeaderBlueIds),
                    context + ": inline contract headers "
                            + "were fetched");
        } else {
            assertTrue(
                    run.primaryMetrics
                            .requestedBlueIds
                            .containsAll(
                                    run.scenario
                                            .contractHeaderBlueIds),
                    context + ": separate exact contract "
                            + "headers were not acquired");
        }
        assertTrue(
                run.primaryMetrics.backendLoadedBlueIds
                        .stream()
                        .allMatch(
                                run.scenario.allowedFragments
                                        ::containsKey),
                context + ": batching escaped the allowed closure");
        assertEquals(
                canonicalBytes(
                        run.scenario.allowedFragments,
                        run.primaryMetrics
                                .backendLoadedBlueIds),
                run.primaryMetrics.backendBytes,
                context + ": backend byte diagnostic drift");
        assertEquals(
                0L,
                canonicalBytes(
                        run.scenario.forbiddenFragments,
                        run.primaryMetrics
                                .backendLoadedBlueIds),
                context + ": forbidden bytes were physically loaded");
        assertFalse(
                run.primaryMetrics.backendLoadedBlueIds
                        .contains(
                                run.scenario.archiveBlueId),
                context + ": large archive was physically loaded");

        assertSourceCheckpoint(
                result.document(),
                run.scenario,
                context);
        assertEquals(
                1,
                trace.records(
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE)
                        .size(),
                context + ": source checkpoint write count");
        assertEquals(
                SELECTED_CHANNEL,
                trace.records(
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE)
                        .get(0).contractKey(),
                context + ": checkpoint ownership moved away "
                        + "from the raw source");

        DocumentProcessingResult replay =
                run.replay.processResult();
        assertEquals(
                ProcessorStatus.STALE,
                replay.status(),
                context + " replay: "
                        + diagnosticProjection(
                        replay.diagnostic()));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        result.document()),
                DirectBlueIdCalculator.calculateBlueId(
                        replay.document()),
                context + ": replay mutated the checkpointed Root");
        assertEquals(
                "processed",
                textAt(replay.document(), "/state"),
                context + ": replay changed the selected value");
        assertTrue(
                replay.events().isEmpty(),
                context + ": replay emitted a duplicate Root event");
        assertTrue(
                run.replay.trace().records(
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE)
                        .isEmpty(),
                context + ": replay rewrote the source checkpoint");
        assertEquals(
                0,
                run.replayRootReads,
                context + ": replay admitted the selected body");
        assertFalse(
                run.replayMetrics.requestedBlueIds.contains(
                        run.scenario.selectedBodyBlueId),
                context + ": replay fetched the selected body");
        assertTrue(
                Collections.disjoint(
                        run.replayMetrics.requestedBlueIds,
                        run.scenario.forbiddenBlueIds),
                context + ": replay demanded forbidden content");
    }

    private static void assertSourceCheckpoint(
            Node result,
            Scenario scenario,
            String context) {
        Node checkpoint = result.getContracts()
                .getProperties().get("checkpoint");
        assertNotNull(checkpoint, context);
        assertEquals(
                RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT,
                checkpoint.getType().getBlueId(),
                context);
        Node entries = checkpoint.getProperties()
                .get("entries");
        assertNotNull(entries, context);
        Node selected = entries.getProperties()
                .get(SELECTED_CHANNEL);
        assertNotNull(selected, context);
        Node domain = selected.getProperties()
                .get("domain");
        Node subject = selected.getProperties()
                .get("subject");
        assertNotNull(domain, context);
        assertNotNull(subject, context);
        assertEquals(
                scenario.selectedCheckpointDomain,
                domain.getBlueId(),
                context);
        assertEquals(
                scenario.eventBlueId,
                DirectBlueIdCalculator.calculateBlueId(subject),
                context);
        assertNull(
                entries.getProperties()
                        .get(REJECTED_CHANNEL),
                context + ": rejected source acquired a checkpoint");
    }

    private static String diagnosticProjection(
            ProcessorDiagnostic diagnostic) {
        return diagnostic == null
                ? null
                : diagnostic.category()
                + "|" + diagnostic.message()
                + "|" + diagnostic.details();
    }

    private static int frequency(
            List<String> values,
            String expected) {
        int count = 0;
        for (String value : values) {
            if (expected.equals(value)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> nodeBlueIds(
            List<Node> nodes) {
        List<String> result =
                new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(
                    DirectBlueIdCalculator.calculateBlueId(node));
        }
        return Collections.unmodifiableList(result);
    }

    private static long canonicalBytes(
            Map<String, Node> fragments,
            Set<String> blueIds) {
        long bytes = 0L;
        for (String blueId : blueIds) {
            Node exact = fragments.get(blueId);
            if (exact != null) {
                bytes += NodeCanonicalizer
                        .canonicalSize(exact);
            }
        }
        return bytes;
    }

    private static Node checkpointDomainNode(
            String effectiveTypeBlueId,
            List<String> sourceContributionBlueIds,
            String discriminator) {
        List<Node> contributions =
                new ArrayList<>();
        for (String blueId :
                sourceContributionBlueIds) {
            contributions.add(
                    new Node().value(blueId));
        }
        return new Node()
                .properties(
                        "contractsVersion",
                        new Node().value("1.0"))
                .properties(
                        "effectiveTypeBlueId",
                        new Node().value(
                                effectiveTypeBlueId))
                .properties(
                        "sourceContributionNodeBlueIds",
                        new Node().items(
                                contributions))
                .properties(
                        "runtimeDiscriminator",
                        new Node().value(
                                discriminator));
    }

    private enum DocumentForm {
        INLINE,
        PURE_REFERENCE,
        PARTIAL
    }

    private enum EventForm {
        INLINE,
        PURE_REFERENCE,
        PARTIAL
    }

    private enum ProviderMode {
        DEFAULT_COLD,
        BATCHED_COLD,
        ONE_AT_A_TIME_COLD,
        WARM
    }

    private static final class Variant {
        private final String label;
        private final DocumentForm documentForm;
        private final EventForm eventForm;
        private final ProviderMode providerMode;
        private final boolean warm;

        private Variant(
                String label,
                DocumentForm documentForm,
                EventForm eventForm,
                ProviderMode providerMode,
                boolean warm) {
            this.label = label;
            this.documentForm = documentForm;
            this.eventForm = eventForm;
            this.providerMode = providerMode;
            this.warm = warm;
        }

        private static List<Variant> requiredMatrix() {
            return Arrays.asList(
                    new Variant(
                            "A inline/inline/cold",
                            DocumentForm.INLINE,
                            EventForm.INLINE,
                            ProviderMode.DEFAULT_COLD,
                            false),
                    new Variant(
                            "B Root-ref/inline/cold",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.INLINE,
                            ProviderMode.DEFAULT_COLD,
                            false),
                    new Variant(
                            "C inline/Event-ref/cold",
                            DocumentForm.INLINE,
                            EventForm.PURE_REFERENCE,
                            ProviderMode.DEFAULT_COLD,
                            false),
                    new Variant(
                            "D Root-ref/Event-ref/cold",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.PURE_REFERENCE,
                            ProviderMode.DEFAULT_COLD,
                            false),
                    new Variant(
                            "E partial/partial/cold",
                            DocumentForm.PARTIAL,
                            EventForm.PARTIAL,
                            ProviderMode.DEFAULT_COLD,
                            false),
                    new Variant(
                            "F Root-ref/Event-ref/warm",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.PURE_REFERENCE,
                            ProviderMode.WARM,
                            true),
                    new Variant(
                            "G Root-ref/Event-ref/batched",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.PURE_REFERENCE,
                            ProviderMode.BATCHED_COLD,
                            false),
                    new Variant(
                            "H Root-ref/Event-ref/one-at-a-time",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.PURE_REFERENCE,
                            ProviderMode.ONE_AT_A_TIME_COLD,
                            false));
        }

        private Node document(Scenario scenario) {
            switch (documentForm) {
                case INLINE:
                    return scenario.inlineRoot.clone();
                case PURE_REFERENCE:
                    return new Node().blueId(
                            scenario.rootBlueId);
                case PARTIAL:
                    return scenario.partialRoot.clone();
                default:
                    throw new IllegalStateException(
                            "Unhandled document form");
            }
        }

        private Node event(Scenario scenario) {
            switch (eventForm) {
                case INLINE:
                    return scenario.inlineEvent.clone();
                case PURE_REFERENCE:
                    return new Node().blueId(
                            scenario.eventBlueId);
                case PARTIAL:
                    return scenario.partialEvent.clone();
                default:
                    throw new IllegalStateException(
                            "Unhandled event form");
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class Scenario {
        private final Node inlineRoot;
        private final Node partialRoot;
        private final Node inlineEvent;
        private final Node partialEvent;
        private final String rootBlueId;
        private final String eventBlueId;
        private final String selectedBodyBlueId;
        private final String archiveBlueId;
        private final String rootEventBlueId;
        private final String selectedCheckpointDomain;
        private final Map<String, Node> allowedFragments;
        private final Map<String, Node> forbiddenFragments;
        private final Set<String> forbiddenBlueIds;
        private final Set<String> contractHeaderBlueIds;
        private final ExternalDeliveryPlan plan;

        private Scenario(
                Node inlineRoot,
                Node partialRoot,
                Node inlineEvent,
                Node partialEvent,
                String rootBlueId,
                String eventBlueId,
                String selectedBodyBlueId,
                String archiveBlueId,
                String rootEventBlueId,
                String selectedCheckpointDomain,
                Map<String, Node> allowedFragments,
                Map<String, Node> forbiddenFragments,
                Set<String> contractHeaderBlueIds,
                ExternalDeliveryPlan plan) {
            this.inlineRoot = inlineRoot;
            this.partialRoot = partialRoot;
            this.inlineEvent = inlineEvent;
            this.partialEvent = partialEvent;
            this.rootBlueId = rootBlueId;
            this.eventBlueId = eventBlueId;
            this.selectedBodyBlueId =
                    selectedBodyBlueId;
            this.archiveBlueId = archiveBlueId;
            this.rootEventBlueId =
                    rootEventBlueId;
            this.selectedCheckpointDomain =
                    selectedCheckpointDomain;
            this.allowedFragments =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    allowedFragments));
            this.forbiddenFragments =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    forbiddenFragments));
            this.forbiddenBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    forbiddenFragments.keySet()));
            this.contractHeaderBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    contractHeaderBlueIds));
            this.plan = plan;
        }

        private static Scenario create() {
            Map<String, Node> allowed =
                    new LinkedHashMap<>();
            Map<String, Node> forbidden =
                    new LinkedHashMap<>();

            Node emitted = new Node()
                    .properties(
                            "kind",
                            new Node().value(
                                    "fragmented-golden-result"))
                    .properties(
                            "id",
                            new Node().value("result-1"));
            String rootEventBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            emitted);
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
                    putExact(allowed, selectedBody);

            List<String> unselectedBodyBlueIds =
                    new ArrayList<>();
            List<Node> unselectedBodies =
                    new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                Node body = largeBody(
                        "unselected-" + index,
                        (char) ('a' + index));
                unselectedBodies.add(body.clone());
                unselectedBodyBlueIds.add(
                        putExact(forbidden, body));
            }

            Node archive = new Node()
                    .properties(
                            "kind",
                            new Node().value(
                                    "unrelated-archive"))
                    .properties(
                            "payload",
                            new Node().value(
                                    padding(
                                            LARGE_PAYLOAD_SIZE * 3,
                                            'z')))
                    .properties(
                            "nested",
                            largeBody(
                                    "unrelated-nested",
                                    'y'));
            String archiveBlueId =
                    putExact(forbidden, archive);

            Node contracts = new Node();
            contracts.properties(
                    "initialized",
                    new Node()
                            .type(new Node().blueId(
                                    RuntimeBlueIds
                                            .PROCESSING_INITIALIZED_MARKER))
                            .properties(
                                    "document",
                                    new Node().value(
                                            "fragmented-golden")));
            Node selectedChannel = channel(
                    0, true, CHECKPOINT_DISCRIMINATOR);
            Node rejectedChannel = channel(
                    1, false, "rejected-domain");
            contracts.properties(
                    SELECTED_CHANNEL,
                    selectedChannel);
            contracts.properties(
                    REJECTED_CHANNEL,
                    rejectedChannel);
            contracts.properties(
                    SELECTED_HANDLER,
                    handler(
                            SELECTED_CHANNEL,
                            0,
                            null,
                            selectedBodyBlueId));
            for (int index = 0; index < 4; index++) {
                contracts.properties(
                        "unselectedWorkflow" + index,
                        handler(
                                REJECTED_CHANNEL,
                                index + 1,
                                "never-" + index,
                                unselectedBodyBlueIds
                                        .get(index)));
            }
            String contractsBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            contracts);
            Node fragmentedContracts =
                    new Node();
            Set<String> contractHeaderBlueIds =
                    new LinkedHashSet<>();
            for (Map.Entry<String, Node> entry :
                    contracts.getProperties()
                            .entrySet()) {
                if ("initialized".equals(
                        entry.getKey())) {
                    fragmentedContracts.properties(
                            entry.getKey(),
                            entry.getValue()
                                    .clone());
                    continue;
                }
                String headerBlueId =
                        putExact(
                                allowed,
                                entry.getValue());
                contractHeaderBlueIds.add(
                        headerBlueId);
                fragmentedContracts.properties(
                        entry.getKey(),
                        new Node().blueId(
                                headerBlueId));
            }
            assertEquals(
                    contractsBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            fragmentedContracts),
                    "separate exact contract headers must "
                            + "preserve the Contracts-map identity");

            Node inlineContracts = contracts.clone();
            inlineContracts.getProperties()
                    .get(SELECTED_HANDLER)
                    .getProperties()
                    .put("result", selectedBody.clone());
            for (int index = 0; index < 4; index++) {
                inlineContracts.getProperties()
                        .get("unselectedWorkflow"
                                + index)
                        .getProperties()
                        .put(
                                "result",
                                unselectedBodies
                                        .get(index)
                                        .clone());
            }
            assertEquals(
                    contractsBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineContracts),
                    "inline executable bodies must preserve "
                            + "the Contracts-map identity");

            Node inlineRoot = new Node()
                    .properties(
                            "state",
                            new Node().value("pending"))
                    .properties(
                            "smallReadSentinel",
                            new Node().value(
                                    "must-remain-local"))
                    .properties(
                            "archive",
                            archive.clone())
                    .contracts(inlineContracts);
            String rootBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineRoot);
            Node partialRoot = inlineRoot.clone();
            partialRoot.getProperties().put(
                    "archive",
                    new Node().blueId(
                            archiveBlueId));
            partialRoot.contracts(
                    fragmentedContracts);
            assertEquals(
                    rootBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            partialRoot),
                    "contracts-map collapse must preserve Root identity");
            allowed.put(
                    rootBlueId, partialRoot.clone());

            Node message = new Node()
                    .properties(
                            "text",
                            new Node().value(
                                    padding(
                                            LARGE_PAYLOAD_SIZE,
                                            'm')))
                    .properties(
                            "unused",
                            new Node().value(true));
            String messageBlueId =
                    putExact(forbidden, message);
            Node inlineEvent = new Node()
                    .properties(
                            "subscriptionKey",
                            new Node().value(
                                    SUBSCRIPTION_KEY))
                    .properties(
                            "kind",
                            new Node().value("selected"))
                    .properties(
                            "id",
                            new Node().value(
                                    "fragmented-event-1"))
                    .properties(
                            "message",
                            message);
            String eventBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineEvent);
            Node partialEvent = inlineEvent.clone();
            partialEvent.getProperties().put(
                    "message",
                    new Node().blueId(messageBlueId));
            assertEquals(
                    eventBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            partialEvent),
                    "message collapse must preserve Event identity");
            allowed.put(
                    eventBlueId, partialEvent.clone());

            String selectedContribution =
                    DirectBlueIdCalculator.calculateBlueId(
                            selectedChannel);
            String rejectedContribution =
                    DirectBlueIdCalculator.calculateBlueId(
                            rejectedChannel);
            String selectedDomain =
                    CheckpointDomain.derive(
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL,
                            Collections.singletonList(
                                    selectedContribution),
                            CHECKPOINT_DISCRIMINATOR);
            String rejectedDomain =
                    CheckpointDomain.derive(
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL,
                            Collections.singletonList(
                                    rejectedContribution),
                            "rejected-domain");

            ExternalDeliverySnapshot selectedDelivery =
                    delivery(
                            SELECTED_CHANNEL,
                            0,
                            selectedContribution,
                            selectedDomain,
                            eventBlueId);
            ExternalDeliverySnapshot rejectedDelivery =
                    delivery(
                            REJECTED_CHANNEL,
                            1,
                            rejectedContribution,
                            rejectedDomain,
                            eventBlueId);
            ExternalDeliveryPlan plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(31L, 31L)
                            .eventOrderKey(EVENT_ORDER)
                            .delivery(selectedDelivery)
                            .delivery(rejectedDelivery)
                            .activeSubscriptionInterval(
                                    active(
                                            SELECTED_CHANNEL,
                                            0,
                                            selectedContribution,
                                            selectedDomain))
                            .activeSubscriptionInterval(
                                    active(
                                            REJECTED_CHANNEL,
                                            1,
                                            rejectedContribution,
                                            rejectedDomain))
                            .exactRuntimeState()
                            .build();

            assertEquals(
                    5,
                    countHandlers(contracts),
                    "golden fixture must retain five handlers");
            assertEquals(
                    4,
                    unselectedBodyBlueIds.size(),
                    "golden fixture must retain four unselected bodies");
            return new Scenario(
                    inlineRoot,
                    partialRoot,
                    inlineEvent,
                    partialEvent,
                    rootBlueId,
                    eventBlueId,
                    selectedBodyBlueId,
                    archiveBlueId,
                    rootEventBlueId,
                    selectedDomain,
                    allowed,
                    forbidden,
                    contractHeaderBlueIds,
                    plan);
        }

        private static int countHandlers(
                Node contracts) {
            int count = 0;
            for (Node contract :
                    contracts.getProperties().values()) {
                if (contract.getType() != null
                        && MockTypeBlueIds.MOCK_HANDLER
                        .equals(
                                contract.getType()
                                        .getBlueId())) {
                    count++;
                }
            }
            return count;
        }

        private static Node channel(
                int order,
                boolean accept,
                String domain) {
            return new Node()
                    .type(new Node().blueId(
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL))
                    .properties(
                            "order",
                            new Node().value(order))
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
                            new Node().value(accept))
                    .properties(
                            "checkpointDomain",
                            new Node().value(domain));
        }

        private static Node handler(
                String channel,
                int order,
                String eventKind,
                String bodyBlueId) {
            Node handler = new Node()
                    .type(new Node().blueId(
                            MockTypeBlueIds.MOCK_HANDLER))
                    .properties(
                            "channel",
                            new Node().value(channel))
                    .properties(
                            "order",
                            new Node().value(order))
                    .properties(
                            "result",
                            new Node().blueId(bodyBlueId));
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

        private static ExternalDeliverySnapshot delivery(
                String channel,
                int order,
                String contribution,
                String domain,
                String eventBlueId) {
            return ExternalDeliverySnapshot.builder(
                            "/", channel)
                    .order(order)
                    .sourceContribution(contribution)
                    .effectiveTypeBlueId(
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL)
                    .subscriptionKey(SUBSCRIPTION_KEY)
                    .checkpointDomainBlueId(domain)
                    .checkpointSubjectBlueId(
                            eventBlueId)
                    .build();
        }

        private static SubscriptionDelta.Entry active(
                String channel,
                int order,
                String contribution,
                String domain) {
            return new SubscriptionDelta.Entry(
                    "/",
                    channel,
                    MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                    Collections.singletonList(
                            contribution),
                    order,
                    Collections.singletonList(
                            SUBSCRIPTION_KEY),
                    domain,
                    0L,
                    null,
                    null);
        }

        private static Node largeBody(
                String tag,
                char padding) {
            return new Node()
                    .properties(
                            "patches",
                            new Node().items(
                                    Collections.<Node>
                                            emptyList()))
                    .properties(
                            "events",
                            new Node().items(
                                    Collections.<Node>
                                            emptyList()))
                    .properties(
                            "tag",
                            new Node().value(tag))
                    .properties(
                            "payload",
                            new Node().value(
                                    padding(
                                            LARGE_PAYLOAD_SIZE,
                                            padding)));
        }

        private static String putExact(
                Map<String, Node> target,
                Node exact) {
            String blueId =
                    DirectBlueIdCalculator.calculateBlueId(exact);
            target.put(blueId, exact.clone());
            return blueId;
        }
    }

    private static final class ReadingMockHandlerProcessor
            implements HandlerProcessor<MockHandler> {
        private final MockHandlerProcessor delegate =
                new MockHandlerProcessor();
        private final AtomicInteger rootReads =
                new AtomicInteger();

        @Override
        public Class<MockHandler> contractType() {
            return delegate.contractType();
        }

        @Override
        public List<String> executableBodyFields() {
            return delegate.executableBodyFields();
        }

        @Override
        public boolean matches(
                MockHandler contract,
                HandlerMatchContext context) {
            return delegate.matches(contract, context);
        }

        @Override
        public void execute(
                MockHandler contract,
                ProcessorExecutionContext context) {
            Node selected = context.documentAt("/state");
            assertNotNull(
                    selected,
                    "selected Handler must read /state");
            assertEquals(
                    "pending",
                    selected.getValue(),
                    "selected Handler observed the wrong Root");
            rootReads.incrementAndGet();
            delegate.execute(contract, context);
        }

        private int rootReads() {
            return rootReads.get();
        }

        private void resetRootReads() {
            rootReads.set(0);
        }
    }

    private static final class StrictFragmentProvider
            implements NodeProvider {
        private final Map<String, Node> allowed;
        private final Map<String, Node> forbidden;
        private final ProviderMode mode;
        private final Map<String, Node> cache =
                new LinkedHashMap<>();
        private final List<String> requests =
                new ArrayList<>();
        private final Set<String> backendLoaded =
                new LinkedHashSet<>();
        private long backendTrips;
        private long backendBytes;

        private StrictFragmentProvider(
                Map<String, Node> allowed,
                Map<String, Node> forbidden,
                ProviderMode mode) {
            this.allowed =
                    new LinkedHashMap<>(allowed);
            this.forbidden =
                    new LinkedHashMap<>(forbidden);
            this.mode = Objects.requireNonNull(
                    mode, "mode");
        }

        @Override
        public synchronized List<Node> fetchByBlueId(
                String blueId) {
            if (forbidden.containsKey(blueId)) {
                throw new AssertionError(
                        "PROCESS demanded forbidden fragment "
                                + blueId);
            }
            Node exact = allowed.get(blueId);
            if (exact == null) {
                throw new AssertionError(
                        "PROCESS escaped the strict exact-fragment "
                                + "allow-list: " + blueId);
            }
            requests.add(blueId);
            Node cached = cache.get(blueId);
            if (cached == null) {
                backendTrips++;
                load(blueId);
                if (mode == ProviderMode.BATCHED_COLD) {
                    for (String candidate :
                            allowed.keySet()) {
                        load(candidate);
                    }
                }
                cached = cache.get(blueId);
            }
            return Collections.singletonList(
                    cached.clone());
        }

        private void load(String blueId) {
            if (cache.containsKey(blueId)) {
                return;
            }
            Node exact = allowed.get(blueId);
            if (exact != null) {
                cache.put(blueId, exact.clone());
                backendLoaded.add(blueId);
                backendBytes += NodeCanonicalizer
                        .canonicalSize(exact);
            }
        }

        private synchronized void warmAllowed() {
            for (String blueId : allowed.keySet()) {
                load(blueId);
            }
        }

        private synchronized void resetMetrics() {
            requests.clear();
            backendLoaded.clear();
            backendTrips = 0L;
            backendBytes = 0L;
        }

        private synchronized ProviderMetrics metrics() {
            return new ProviderMetrics(
                    new ArrayList<>(requests),
                    new LinkedHashSet<>(
                            backendLoaded),
                    backendTrips,
                    backendBytes);
        }
    }

    private static final class ProviderMetrics {
        private final List<String> requestedBlueIds;
        private final Set<String> backendLoadedBlueIds;
        private final long backendTrips;
        private final long backendBytes;

        private ProviderMetrics(
                List<String> requestedBlueIds,
                Set<String> backendLoadedBlueIds,
                long backendTrips,
                long backendBytes) {
            this.requestedBlueIds =
                    Collections.unmodifiableList(
                            requestedBlueIds);
            this.backendLoadedBlueIds =
                    Collections.unmodifiableSet(
                            backendLoadedBlueIds);
            this.backendTrips = backendTrips;
            this.backendBytes = backendBytes;
        }
    }

    private static final class Run {
        private final Variant variant;
        private final Scenario scenario;
        private final ProcessingDebugResult debug;
        private final ProcessingDebugResult replay;
        private final ProviderMetrics primaryMetrics;
        private final ProviderMetrics replayMetrics;
        private final int primaryRootReads;
        private final int replayRootReads;

        private Run(
                Variant variant,
                Scenario scenario,
                ProcessingDebugResult debug,
                ProcessingDebugResult replay,
                ProviderMetrics primaryMetrics,
                ProviderMetrics replayMetrics,
                int primaryRootReads,
                int replayRootReads) {
            this.variant = variant;
            this.scenario = scenario;
            this.debug = debug;
            this.replay = replay;
            this.primaryMetrics = primaryMetrics;
            this.replayMetrics = replayMetrics;
            this.primaryRootReads = primaryRootReads;
            this.replayRootReads = replayRootReads;
        }
    }

    private static final class SemanticProjection {
        private final ProcessorStatus status;
        private final String rootValue;
        private final String resultingRootBlueId;
        private final List<String> rootEventBlueIds;
        private final String diagnostic;
        private final long totalGas;
        private final List<String> gas;
        private final List<String> trace;
        private final List<String> semanticDemands;

        private SemanticProjection(
                ProcessorStatus status,
                String rootValue,
                String resultingRootBlueId,
                List<String> rootEventBlueIds,
                String diagnostic,
                long totalGas,
                List<String> gas,
                List<String> trace,
                List<String> semanticDemands) {
            this.status = status;
            this.rootValue = rootValue;
            this.resultingRootBlueId =
                    resultingRootBlueId;
            this.rootEventBlueIds =
                    rootEventBlueIds;
            this.diagnostic = diagnostic;
            this.totalGas = totalGas;
            this.gas = gas;
            this.trace = trace;
            this.semanticDemands =
                    semanticDemands;
        }

        private static SemanticProjection of(
                ProcessingDebugResult debug) {
            DocumentProcessingResult result =
                    debug.processResult();
            return new SemanticProjection(
                    result.status(),
                    textAt(result.document(), "/state"),
                    DirectBlueIdCalculator.calculateBlueId(
                            result.document()),
                    nodeBlueIds(result.events()),
                    diagnosticProjection(
                            result.diagnostic()),
                    result.totalGas(),
                    gasProjection(debug.trace()),
                    traceProjection(debug.trace()),
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    debug.trace()
                                            .semanticDemands())));
        }

        private static List<String> gasProjection(
                ProcessingConformanceTrace trace) {
            List<String> projection =
                    new ArrayList<>();
            for (GasTraceEntry entry : trace.gas()) {
                projection.add(
                        entry.sequence()
                                + "|" + entry.namespace()
                                + "|" + entry.counter()
                                + "|" + entry.quantity()
                                + "|" + entry.weight()
                                + "|" + entry.subtotal()
                                + "|" + entry.scopePath()
                                + "|" + entry.contractKey()
                                + "|" + entry.logicalPath()
                                + "|" + entry.reason());
            }
            return Collections.unmodifiableList(
                    projection);
        }

        private static List<String> traceProjection(
                ProcessingConformanceTrace trace) {
            List<String> projection =
                    new ArrayList<>();
            for (ProcessingTraceRecord record :
                    trace.records()) {
                Node node = record.node();
                projection.add(
                        record.sequence()
                                + "|" + record.kind()
                                + "|" + record.scopePath()
                                + "|" + record.contractKey()
                                + "|" + record.logicalPath()
                                + "|" + record.details()
                                + "|" + (node != null
                                ? DirectBlueIdCalculator
                                .calculateBlueId(node)
                                : null));
            }
            return Collections.unmodifiableList(
                    projection);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other
                    instanceof SemanticProjection)) {
                return false;
            }
            SemanticProjection that =
                    (SemanticProjection) other;
            return status == that.status
                    && totalGas == that.totalGas
                    && Objects.equals(
                    rootValue, that.rootValue)
                    && resultingRootBlueId.equals(
                    that.resultingRootBlueId)
                    && rootEventBlueIds.equals(
                    that.rootEventBlueIds)
                    && Objects.equals(
                    diagnostic, that.diagnostic)
                    && gas.equals(that.gas)
                    && trace.equals(that.trace)
                    && semanticDemands.equals(
                    that.semanticDemands);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    status,
                    rootValue,
                    resultingRootBlueId,
                    rootEventBlueIds,
                    diagnostic,
                    totalGas,
                    gas,
                    trace,
                    semanticDemands);
        }

        @Override
        public String toString() {
            return "SemanticProjection{"
                    + "status=" + status
                    + ", rootValue=" + rootValue
                    + ", rootBlueId="
                    + resultingRootBlueId
                    + ", events="
                    + rootEventBlueIds
                    + ", diagnostic="
                    + diagnostic
                    + ", totalGas="
                    + totalGas
                    + '}';
        }
    }

    private static Node list(Node... values) {
        return new Node().items(
                Arrays.asList(values));
    }

    private static String textAt(
            Node root,
            String path) {
        Node value = "/state".equals(path)
                && root.getProperties() != null
                ? root.getProperties().get("state")
                : root.getAsNode(path);
        return value != null && value.getValue() != null
                ? String.valueOf(value.getValue())
                : null;
    }

    private static String padding(
            int length,
            char value) {
        char[] values = new char[length];
        Arrays.fill(values, value);
        return new String(values);
    }
}
