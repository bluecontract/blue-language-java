package blue.language.processor;

import blue.language.model.wire.JsonPointer;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.model.Node;
import blue.language.processor.conformance.MockExternalChannelProcessor;
import blue.language.processor.conformance.MockHandlerProcessor;
import blue.language.processor.conformance.MockTypeBlueIds;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.provider.SequentialNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-normative integration proof for physical locality in a deep Contracts
 * graph. Provider counters in this test are host observations only: they are
 * deliberately absent from the semantic gas and trace projections.
 */
class DeepGraphPhysicalLocalityIntegrationTest {

    private static final int SPINE_SCOPE_COUNT = 7;
    private static final int DECOY_HANDLERS_PER_SCOPE = 4;
    private static final int UNRELATED_BODY_PAYLOAD_BYTES = 32_000;
    private static final int SELECTED_BODY_PAYLOAD_BYTES = 8_000;
    private static final int BOUNDED_BATCH_SIZE = 3;
    private static final long MIN_UNRELATED_GRAPH_BYTES =
            2L * 1024L * 1024L;

    private static final String SELECTED_SEGMENT = "selected";
    private static final String LEFT_SEGMENT = "left";
    private static final String RIGHT_SEGMENT = "right";
    private static final String SELECTED_CHANNEL = "incoming";
    private static final String SELECTED_HANDLER = "selectedWorkflow";
    private static final String RELAY_CHANNEL = "selectedChildEvents";
    private static final String RELAY_HANDLER = "relaySelectedChildEvents";
    private static final String SUBSCRIPTION_KEY = "deep-locality";
    private static final String CHECKPOINT_DISCRIMINATOR =
            "deep-locality-checkpoint-v1";

    private static final Node RELAY_HANDLER_TYPE = new Node()
            .name("Deep Graph Locality Relay Handler")
            .type(new Node().blueId(RuntimeBlueIds.HANDLER));
    private static final String RELAY_HANDLER_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(RELAY_HANDLER_TYPE);
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.<Object>asList(
                    91, "deep-locality", 1));

    @Test
    void shouldVerifyDeepGraphHasSemanticParityAndPhysicalLocalityAcrossRepresentationsAndProviders() {
        // given
        List<Variant> variants = Variant.requiredMatrix();

        // when
        List<Run> runs = new ArrayList<>(variants.size());
        List<SemanticProjection> projections =
                new ArrayList<>(variants.size());
        for (Variant variant : variants) {
            runs.add(execute(variant));
        }
        for (Run run : runs) {
            projections.add(SemanticProjection.of(run.debug));
        }
        SemanticProjection baseline = projections.get(0);

        // then
        for (int index = 0; index < runs.size(); index++) {
            Run run = runs.get(index);
            assertDefinitiveLocalityProof(run);
            if (index > 0) {
                assertEquals(
                        baseline,
                        projections.get(index),
                        "semantic drift for " + run.variant);
            }
        }
        assertEquals(32, variants.size());
        assertNotNull(baseline);
        assertEquals(ProcessorStatus.SUCCESS, baseline.status);
        assertEquals(2, baseline.rootEventBlueIds.size());
        assertNotEquals(
                baseline.rootEventBlueIds.get(0),
                baseline.rootEventBlueIds.get(1),
                "the Root event ordering proof must contain distinct identities");
        SemanticLocalityEvidenceWriter.write(
                "deep-graph-matrix.json",
                localityEvidence(runs));
    }

    private static Map<String, Object> localityEvidence(List<Run> runs) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", "blue-language-locality-evidence/1.0");
        List<Map<String, Object>> observations = new ArrayList<>();
        for (Run run : runs) {
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("variant", run.variant.toString());
            observation.put("selectedClosureBlueIds",
                    new ArrayList<>(run.scenario.selectedClosureBlueIds));
            observation.put("forbiddenBlueIds",
                    new ArrayList<>(run.scenario.unrelatedBodyBlueIds));
            observation.put("requestedBlueIds",
                    new ArrayList<>(run.providerMetrics.requestedBlueIds));
            observation.put("semanticDemands",
                    run.debug.trace().semanticDemands());
            observation.put("backendLoadedBlueIds",
                    new ArrayList<>(run.providerMetrics.backendLoadedBlueIds));
            observation.put("backendBytes", run.providerMetrics.backendBytes);
            observations.add(observation);
        }
        evidence.put("observations", observations);
        return evidence;
    }

    @Test
    void shouldVerifyRootOnlyPureReferenceEventDoesNotDemandAnyEmbeddedScope() {
        // given
        Scenario scenario =
                Scenario.forForm(
                        BodyForm.REFERENCE);
        String rootChannel = "rootIncoming";
        String rootHandler = "rootSelectedWorkflow";
        Node rootBody = new Node()
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
                                                "/localState"))
                                .properties(
                                        "val",
                                        new Node().value(
                                                "root-processed"))))
                .properties(
                        "events",
                        new Node().items(
                                Collections.<Node>
                                        emptyList()));
        String rootBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        rootBody);
        Node root = scenario.root.clone();
        Node rootChannelNode = new Node()
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
                                "root-only-domain"));
        root.getContracts()
                .properties(
                        rootChannel,
                        rootChannelNode)
                .properties(
                        rootHandler,
                        new Node()
                                .type(new Node().blueId(
                                        MockTypeBlueIds
                                                .MOCK_HANDLER))
                                .properties(
                                        "channel",
                                        new Node().value(
                                                rootChannel))
                                .properties(
                                        "order",
                                        new Node().value(0))
                                .properties(
                                        "result",
                                        new Node().blueId(
                                                rootBodyBlueId)));

        Node rootFragment = root.clone();
        Map<String, Node> providerContent =
                new LinkedHashMap<>(
                        scenario.providerBodies);
        Set<String> embeddedChildBlueIds =
                new LinkedHashSet<>();
        for (String segment :
                Arrays.asList(
                        SELECTED_SEGMENT,
                        LEFT_SEGMENT,
                        RIGHT_SEGMENT)) {
            String child = childPath("/", segment);
            Node exactChild =
                    Scenario.rootAt(root, child);
            String childBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            exactChild);
            embeddedChildBlueIds.add(childBlueId);
            providerContent.put(
                    childBlueId,
                    exactChild.clone());
            NodePathEditor.put(
                    rootFragment,
                    child,
                    new Node().blueId(
                            childBlueId));
        }
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(root);
        String rootFragmentBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        rootFragment);
        providerContent.put(
                rootBlueId,
                rootFragment);
        providerContent.put(
                rootBodyBlueId,
                rootBody);
        Set<String> allowed =
                new LinkedHashSet<>(
                        Arrays.asList(
                                rootBlueId,
                                scenario.eventBlueId,
                                rootBodyBlueId));
        MeasuredBodyProvider measured =
                new MeasuredBodyProvider(
                        providerContent,
                        allowed,
                        BatchMode.UNBATCHED,
                        1);
        NodeProvider relayTypeProvider = blueId ->
                RELAY_HANDLER_TYPE_BLUE_ID.equals(
                        blueId)
                        ? Collections.singletonList(
                        RELAY_HANDLER_TYPE.clone())
                        : null;
        BlueRuntimeTypeRegistry runtimeTypes =
                BlueRuntimeTypeRegistry.getDefault();
        Blue blue = new Blue(
                new SequentialNodeProvider(
                        runtimeTypes.asProvider(),
                        relayTypeProvider,
                        measured));
        Set<String> preserved =
                new LinkedHashSet<>(
                        scenario.physicallyDeferredPaths);
        preserved.addAll(
                scenario.executableBodyPaths);
        preserved.add(
                contractPath(
                        "/", rootHandler)
                        + "/result");
        preserved.add(
                childPath(
                        "/", SELECTED_SEGMENT));
        preserved.add(
                childPath("/", LEFT_SEGMENT));
        preserved.add(
                childPath("/", RIGHT_SEGMENT));
        ProcessingSnapshotManager snapshots =
                new LocalitySnapshotManager(
                        blue.getDocumentProcessor()
                                .snapshotManager(),
                        preserved);
        String contribution =
                DirectBlueIdCalculator.calculateBlueId(
                        rootChannelNode);
        String checkpointDomain =
                CheckpointDomain.derive(
                        MockTypeBlueIds
                                .MOCK_EXTERNAL_CHANNEL,
                        Collections.singletonList(
                                contribution),
                        "root-only-domain");
        ExternalDeliveryPlan rootDelivery =
                ExternalDeliveryPlan.builder()
                        .revisions(18L, 18L)
                        .eventOrderKey(EVENT_ORDER)
                        .delivery(
                                ExternalDeliverySnapshot
                                        .builder(
                                                "/",
                                                rootChannel)
                                        .order(0)
                                        .sourceContribution(
                                                contribution)
                                        .effectiveTypeBlueId(
                                                MockTypeBlueIds
                                                        .MOCK_EXTERNAL_CHANNEL)
                                        .subscriptionKey(
                                                SUBSCRIPTION_KEY)
                                        .checkpointDomainBlueId(
                                                checkpointDomain)
                                        .checkpointSubjectBlueId(
                                                scenario
                                                        .eventBlueId)
                                        .build())
                        .activeSubscriptionInterval(
                                new SubscriptionDelta.Entry(
                                        "/",
                                        rootChannel,
                                        MockTypeBlueIds
                                                .MOCK_EXTERNAL_CHANNEL,
                                        Collections.singletonList(
                                                contribution),
                                        0,
                                        Collections.singletonList(
                                                SUBSCRIPTION_KEY),
                                        checkpointDomain,
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
                        .withSnapshotManager(snapshots)
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
                        .registerContractProcessor(
                                RELAY_HANDLER_TYPE_BLUE_ID,
                                RELAY_HANDLER_TYPE,
                                new RelayHandlerProcessor())
                        .withExternalDeliveryPlanDeriver(
                                (ignoredRoot, ignoredEvent) ->
                                        rootDelivery)
                        .withExternalDeliveryEvidenceVerifier(
                                (ignoredRoot, ignoredEvent, evidence) -> {
                                    // Exact delivery/bundle checks still run
                                    // inside the generic processor.
                                })
                        .build();
        ProcessingDebugResult debug;
        ProviderMetrics providerMetrics;
        try {
            // when
            debug =
                    processor.processDocumentWithTrace(
                            new Node().blueId(
                            rootBlueId),
                            new Node().blueId(
                                    scenario.eventBlueId));
            providerMetrics = measured.snapshotMetrics();
        } finally {
            processor.close();
            blue.close();
        }

        // then
        assertEquals(
                rootBlueId,
                rootFragmentBlueId);
        assertEquals(
                ProcessorStatus.SUCCESS,
                debug.processResult().status(),
                debug.processResult()
                                .diagnostic() != null
                        ? debug.processResult()
                                .diagnostic()
                                .message()
                        : null);
        assertEquals(
                "root-processed",
                debug.processResult()
                        .document()
                        .getAsText(
                                "/localState"));
        assertEquals(
                allowed,
                providerMetrics.requestedBlueIds);
        assertTrue(
                Collections.disjoint(
                        providerMetrics.requestedBlueIds,
                        embeddedChildBlueIds));
        assertTrue(
                Collections.disjoint(
                        providerMetrics.requestedBlueIds,
                        scenario.unrelatedBodyBlueIds));
        assertEquals(
                allowed,
                providerMetrics.backendLoadedBlueIds);
        assertEquals(
                scenario.providerBytes(
                        Arrays.asList(
                                scenario.eventBlueId))
                        + NodeCanonicalizer
                                .canonicalSize(
                                        rootFragment)
                        + NodeCanonicalizer
                                .canonicalSize(
                                        rootBody),
                providerMetrics.backendBytes);
        assertEquals(
                1,
                Collections.frequency(
                        debug.trace()
                                .semanticDemands(),
                        rootBodyBlueId));
        assertEquals(
                1,
                debug.trace()
                        .records(
                                ProcessingTraceRecord.Kind
                                        .EXTERNAL_DELIVERY)
                        .size());
        assertEquals(
                "/",
                debug.trace()
                        .records(
                                ProcessingTraceRecord.Kind
                                        .EXTERNAL_DELIVERY)
                        .get(0)
                        .scopePath());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", "blue-language-locality-evidence/1.0");
        evidence.put("requiredBlueIds", new ArrayList<>(allowed));
        Set<String> forbidden = new LinkedHashSet<>(embeddedChildBlueIds);
        forbidden.addAll(scenario.unrelatedBodyBlueIds);
        evidence.put("forbiddenBlueIds", new ArrayList<>(forbidden));
        evidence.put("requestedBlueIds",
                new ArrayList<>(providerMetrics.requestedBlueIds));
        evidence.put("semanticDemands",
                debug.trace().semanticDemands());
        evidence.put("backendLoadedBlueIds",
                new ArrayList<>(providerMetrics.backendLoadedBlueIds));
        evidence.put("backendBytes", providerMetrics.backendBytes);
        SemanticLocalityEvidenceWriter.write(
                "root-only-event.json", evidence);
    }

    private static Run execute(Variant variant) {
        BenchmarkInvocation invocation =
                prepareBenchmark(variant);
        try {
            ProcessingDebugResult debug =
                    invocation.process();
            return new Run(
                    variant,
                    invocation.scenario,
                    invocation.inputSnapshot,
                    debug,
                    invocation.providerMetrics());
        } finally {
            invocation.close();
        }
    }

    static BenchmarkInvocation prepareBenchmark(
            String bodyForm,
            String entryMode,
            String cacheMode,
            String batchMode) {
        return prepareBenchmark(new Variant(
                BodyForm.valueOf(bodyForm),
                EntryMode.valueOf(entryMode),
                CacheMode.valueOf(cacheMode),
                BatchMode.valueOf(batchMode)));
    }

    private static BenchmarkInvocation prepareBenchmark(
            Variant variant) {
        Scenario scenario =
                Scenario.forForm(variant.bodyForm);
        MeasuredBodyProvider measuredProvider =
                new MeasuredBodyProvider(
                        scenario.providerBodies,
                        scenario.selectedClosureBlueIds,
                        variant.batchMode,
                        BOUNDED_BATCH_SIZE);
        NodeProvider relayTypeProvider = blueId ->
                RELAY_HANDLER_TYPE_BLUE_ID.equals(blueId)
                        ? Collections.singletonList(
                        RELAY_HANDLER_TYPE.clone())
                        : null;
        BlueRuntimeTypeRegistry runtimeTypes =
                BlueRuntimeTypeRegistry.getDefault();
        Blue blue = new Blue(new SequentialNodeProvider(
                runtimeTypes.asProvider(),
                relayTypeProvider,
                measuredProvider));
        /*
         * Use the Language runtime's native manager. Its transient sequence
         * and dependency-proven incremental patch path are part of the
         * locality boundary being proved; a conservative wrapper would
         * intentionally fall back to resolving the entire Root.
         */
        ProcessingSnapshotManager snapshots =
                new LocalitySnapshotManager(
                        blue.getDocumentProcessor()
                                .snapshotManager(),
                        scenario.physicallyDeferredPaths);
        DocumentProcessor processor = DocumentProcessor.builder()
                .withMatchingService(
                        new ContractMatchingService(blue))
                .withConformanceEngine(blue.conformanceEngine())
                .withSnapshotManager(snapshots)
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
                        new MockHandlerProcessor())
                .registerContractProcessor(
                        RELAY_HANDLER_TYPE_BLUE_ID,
                        RELAY_HANDLER_TYPE,
                        new RelayHandlerProcessor())
                .withExternalDeliveryPlanDeriver(
                        (root, event) -> scenario.plan)
                .build();

        boolean prepared = false;
        try {
            /*
             * Keep an exact immutable input companion for the structural
             * sharing assertions in both entry modes. Preserved executable
             * paths ensure this setup cannot demand any body.
             */
            Node snapshotInput =
                    variant.entryMode
                            == EntryMode.PURE_REFERENCES
                            || variant.entryMode
                            == EntryMode
                            .ROOT_REFERENCE_EVENT_INLINE
                            || variant.entryMode
                            == EntryMode.PARTIAL
                            ? scenario.fragmentedRoot
                            : variant.entryMode
                            == EntryMode
                            .MIXED_FRAGMENT_BOUNDARIES
                            ? scenario.mixedFragmentedRoot
                            : scenario.root;
            ResolvedSnapshot inputSnapshot =
                    snapshots.fromDocumentPreservingPaths(
                            snapshotInput,
                            scenario.executableBodyPaths);
            assertTrue(
                    measuredProvider.requestedBlueIds().isEmpty(),
                    "input snapshot preparation demanded an executable body");

            if (variant.cacheMode == CacheMode.WARM) {
                measuredProvider.warmSelectedClosure();
            }
            measuredProvider.resetMetrics();
            BenchmarkInvocation invocation =
                    new BenchmarkInvocation(
                    variant,
                    scenario,
                    inputSnapshot,
                    measuredProvider,
                    blue,
                    processor);
            prepared = true;
            return invocation;
        } finally {
            if (!prepared) {
                processor.close();
                blue.close();
            }
        }
    }

    private static void assertDefinitiveLocalityProof(Run run) {
        String context = run.variant.toString();
        DocumentProcessingResult result =
                run.debug.processResult();
        ProcessingConformanceTrace trace = run.debug.trace();

        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                context + ": unexpected status"
                        + (result.diagnostic() != null
                        ? " (" + result.diagnostic().message() + ")"
                        : ""));
        assertEquals(
                "processed",
                result.document().getAsText(
                        run.scenario.leafPath + "/localState"),
                context + ": handlers="
                        + selectedScopeHandlerOrder(trace)
                        + ", demands="
                        + trace.semanticDemands()
                        + ", records="
                        + SemanticProjection.recordProjection(trace));
        assertEquals(
                1,
                trace.records(
                                ProcessingTraceRecord.Kind
                                        .EXTERNAL_DELIVERY)
                        .size(),
                context + ": more than one external delivery");
        assertEquals(
                run.scenario.leafPath,
                trace.records(
                                ProcessingTraceRecord.Kind
                                        .EXTERNAL_DELIVERY)
                        .get(0).scopePath(),
                context + ": delivery did not target the selected leaf");

        List<String> publicEvents =
                nodeBlueIds(result.events());
        List<String> traceRootEvents =
                recordNodeBlueIds(trace.records(
                        ProcessingTraceRecord.Kind.ROOT_EVENT));
        assertEquals(2, publicEvents.size(), context);
        assertEquals(
                publicEvents,
                traceRootEvents,
                context + ": Root trace/outbox order drift");

        List<String> selectedOrder =
                selectedScopeHandlerOrder(trace);
        assertEquals(
                1,
                frequency(
                        selectedOrder,
                        run.scenario.leafPath + ":"
                                + SELECTED_HANDLER),
                context + ": selected leaf body executed more than once");
        for (String ancestor : run.scenario.ancestorPaths) {
            assertEquals(
                    2,
                    frequency(
                            selectedOrder,
                            ancestor + ":" + RELAY_HANDLER),
                    context + ": each emitted leaf event must be relayed once per ancestor");
        }

        assertTrue(
                trace.semanticDemands().contains(
                        run.scenario.selectedBodyBlueId),
                context + ": selected executable body was not a semantic demand");
        assertTrue(
                Collections.disjoint(
                        trace.semanticDemands(),
                        run.scenario.unrelatedBodyBlueIds),
                context + ": semantic trace demanded an unrelated body");
        assertTrue(
                Collections.disjoint(
                        run.providerMetrics.requestedBlueIds,
                        run.scenario.unrelatedBodyBlueIds),
                context + ": provider was asked for an unrelated body: "
                        + run.providerMetrics.requestedBlueIds);
        assertTrue(
                run.scenario.selectedClosureBlueIds.containsAll(
                        run.providerMetrics.requestedBlueIds),
                context + ": provider requests escaped the selected closure: "
                        + run.providerMetrics.requestedBlueIds);
        assertTrue(
                run.scenario.selectedClosureBlueIds.containsAll(
                        run.providerMetrics.backendLoadedBlueIds),
                context + ": backend reads escaped the selected closure: "
                        + run.providerMetrics.backendLoadedBlueIds);
        assertTrue(
                run.providerMetrics.requestCount
                        <= run.scenario.selectedClosureBlueIds.size() * 2L,
                context + ": request count is not closure-bounded");
        assertTrue(
                run.providerMetrics.backendBytes
                        <= run.scenario.selectedClosureBytes,
                context + ": backend bytes are not closure-bounded");

        Set<String> expectedRequests =
                new LinkedHashSet<>();
        if (run.variant.entryMode
                == EntryMode.PURE_REFERENCES
                || run.variant.entryMode
                == EntryMode
                .ROOT_REFERENCE_EVENT_INLINE) {
            expectedRequests.add(
                    run.scenario.rootBlueId);
        }
        if (run.variant.entryMode
                == EntryMode.PURE_REFERENCES
                || run.variant.entryMode
                == EntryMode
                .ROOT_INLINE_EVENT_REFERENCE) {
            expectedRequests.add(
                    run.scenario.eventBlueId);
        }
        if (run.variant.bodyForm
                == BodyForm.REFERENCE) {
            expectedRequests.add(
                    run.scenario.selectedBodyBlueId);
        }
        assertEquals(
                expectedRequests,
                run.providerMetrics.requestedBlueIds,
                context);
        assertEquals(
                expectedRequests.size(),
                run.providerMetrics.requestCount,
                context + ": an exact fragment was requested more than once");

        if (run.variant.cacheMode == CacheMode.WARM
                || expectedRequests.isEmpty()) {
            assertEquals(
                    0L,
                    run.providerMetrics.backendBytes,
                    context);
            assertEquals(
                    0L,
                    run.providerMetrics.backendTrips,
                    context);
        } else {
            assertEquals(
                    run.scenario.providerBytes(
                            run.providerMetrics.backendLoadedBlueIds),
                    run.providerMetrics.backendBytes,
                    context);
            assertTrue(
                    run.providerMetrics.backendTrips > 0L
                            && run.providerMetrics.backendTrips
                            <= expectedRequests.size(),
                    context + ": backend trips do not match exact acquisition");
        }

        assertTrue(
                run.scenario.unrelatedBodyBlueIds.size() >= 40,
                "scenario no longer contains a large unrelated graph");
        assertTrue(
                run.scenario.unrelatedBodyBytes
                        > run.scenario.selectedBodyBytes * 50L,
                "unrelated physical graph must dominate the selected closure");
        assertTrue(
                run.scenario.unrelatedBodyBytes
                        >= MIN_UNRELATED_GRAPH_BYTES,
                "configured unrelated graph must be at least 2 MiB; actual="
                        + run.scenario.unrelatedBodyBytes);
        assertChangedSpineOnly(run, context);
    }

    private static void assertChangedSpineOnly(
            Run run,
            String context) {
        ResolvedSnapshot resulting =
                run.debug.resultingSnapshot();
        assertNotNull(
                resulting,
                context + ": snapshot-native result is required");
        FrozenNode before =
                run.inputSnapshot.frozenResolvedRoot();
        FrozenNode after =
                resulting.frozenResolvedRoot();

        for (String scopePath : run.scenario.spinePaths) {
            assertNotSame(
                    before.at(scopePath),
                    after.at(scopePath),
                    context + ": changed spine node was not rebuilt at "
                            + scopePath);
        }
        for (String ancestor : run.scenario.ancestorPaths) {
            assertSame(
                    before.at(childPath(
                            ancestor, LEFT_SEGMENT)),
                    after.at(childPath(
                            ancestor, LEFT_SEGMENT)),
                    context + ": unchanged left sibling rebuilt at "
                            + ancestor);
            assertSame(
                    before.at(childPath(
                            ancestor, RIGHT_SEGMENT)),
                    after.at(childPath(
                            ancestor, RIGHT_SEGMENT)),
                    context + ": unchanged right sibling rebuilt at "
                            + ancestor);
            assertSame(
                    before.at(contractPath(
                            ancestor, "embedded")),
                    after.at(contractPath(
                            ancestor, "embedded")),
                    context + ": unchanged workflow header rebuilt at "
                            + ancestor);
        }
        assertSame(
                before.at(contractPath(
                        run.scenario.leafPath,
                        SELECTED_HANDLER) + "/result"),
                after.at(contractPath(
                        run.scenario.leafPath,
                        SELECTED_HANDLER) + "/result"),
                context + ": selected immutable body should be shared");
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(resulting.canonicalRoot()),
                DirectBlueIdCalculator.calculateBlueId(
                        run.debug.processResult().document()),
                context + ": resulting snapshot/result Root identity drift");
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

    private static List<String> selectedScopeHandlerOrder(
            ProcessingConformanceTrace trace) {
        List<String> order = new ArrayList<>();
        for (GasTraceEntry entry : trace.gas()) {
            if ("processor".equals(entry.namespace())
                    && "handlerCall".equals(entry.counter())) {
                order.add(entry.scopePath() + ":"
                        + entry.contractKey());
            }
        }
        return Collections.unmodifiableList(order);
    }

    private static List<String> nodeBlueIds(
            List<Node> nodes) {
        List<String> result = new ArrayList<>();
        for (Node node : nodes) {
            result.add(DirectBlueIdCalculator.calculateBlueId(node));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> recordNodeBlueIds(
            List<ProcessingTraceRecord> records) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record : records) {
            result.add(DirectBlueIdCalculator.calculateBlueId(
                    record.node()));
        }
        return Collections.unmodifiableList(result);
    }

    private static String childPath(
            String scopePath,
            String segment) {
        return "/".equals(scopePath)
                ? "/" + segment
                : scopePath + "/" + segment;
    }

    private static String contractPath(
            String scopePath,
            String key) {
        return ("/".equals(scopePath) ? "" : scopePath)
                + "/contracts/" + key;
    }

    private enum BodyForm {
        INLINE,
        REFERENCE
    }

    private enum EntryMode {
        EAGER_SNAPSHOT,
        LAZY_NODE,
        PURE_REFERENCES,
        ROOT_REFERENCE_EVENT_INLINE,
        ROOT_INLINE_EVENT_REFERENCE,
        PARTIAL,
        MIXED_FRAGMENT_BOUNDARIES
    }

    private enum CacheMode {
        COLD,
        WARM
    }

    private enum BatchMode {
        UNBATCHED,
        BOUNDED_BATCH
    }

    private static final class Variant {
        private final BodyForm bodyForm;
        private final EntryMode entryMode;
        private final CacheMode cacheMode;
        private final BatchMode batchMode;

        private Variant(
                BodyForm bodyForm,
                EntryMode entryMode,
                CacheMode cacheMode,
                BatchMode batchMode) {
            this.bodyForm = bodyForm;
            this.entryMode = entryMode;
            this.cacheMode = cacheMode;
            this.batchMode = batchMode;
        }

        private static List<Variant> requiredMatrix() {
            List<Variant> result = new ArrayList<>();
            EntryMode[] fullProviderMatrix = {
                    EntryMode.EAGER_SNAPSHOT,
                    EntryMode.LAZY_NODE,
                    EntryMode.PURE_REFERENCES
            };
            for (BodyForm bodyForm : BodyForm.values()) {
                for (EntryMode entryMode :
                        fullProviderMatrix) {
                    for (CacheMode cacheMode :
                            CacheMode.values()) {
                        for (BatchMode batchMode :
                                BatchMode.values()) {
                            result.add(new Variant(
                                    bodyForm,
                                    entryMode,
                                    cacheMode,
                                    batchMode));
                        }
                    }
                }
                for (EntryMode entryMode :
                        Arrays.asList(
                                EntryMode
                                        .ROOT_REFERENCE_EVENT_INLINE,
                                EntryMode
                                        .ROOT_INLINE_EVENT_REFERENCE,
                                EntryMode.PARTIAL,
                                EntryMode
                                        .MIXED_FRAGMENT_BOUNDARIES)) {
                    result.add(new Variant(
                            bodyForm,
                            entryMode,
                            CacheMode.COLD,
                            BatchMode.UNBATCHED));
                }
            }
            return Collections.unmodifiableList(result);
        }

        @Override
        public String toString() {
            return bodyForm + "/" + entryMode + "/"
                    + cacheMode + "/" + batchMode;
        }
    }

    static final class BenchmarkInvocation
            implements AutoCloseable {
        private final Variant variant;
        private final Scenario scenario;
        private final ResolvedSnapshot inputSnapshot;
        private final MeasuredBodyProvider provider;
        private final Blue blue;
        private final DocumentProcessor processor;
        private boolean processed;
        private boolean closed;

        private BenchmarkInvocation(
                Variant variant,
                Scenario scenario,
                ResolvedSnapshot inputSnapshot,
                MeasuredBodyProvider provider,
                Blue blue,
                DocumentProcessor processor) {
            this.variant = variant;
            this.scenario = scenario;
            this.inputSnapshot = inputSnapshot;
            this.provider = provider;
            this.blue = blue;
            this.processor = processor;
        }

        ProcessingDebugResult process() {
            if (closed) {
                throw new IllegalStateException(
                        "benchmark invocation is closed");
            }
            if (processed) {
                throw new IllegalStateException(
                        "benchmark invocation is single-use");
            }
            processed = true;
            if (variant.entryMode
                    == EntryMode.EAGER_SNAPSHOT) {
                return processor.processDocumentWithTrace(
                        inputSnapshot,
                        scenario.event.clone());
            }
            if (variant.entryMode
                    == EntryMode.PURE_REFERENCES) {
                return processor.processDocumentWithTrace(
                        new Node().blueId(
                                scenario.rootBlueId),
                        new Node().blueId(
                                scenario.eventBlueId));
            }
            if (variant.entryMode
                    == EntryMode
                    .ROOT_REFERENCE_EVENT_INLINE) {
                return processor.processDocumentWithTrace(
                        new Node().blueId(
                                scenario.rootBlueId),
                        scenario.event.clone());
            }
            if (variant.entryMode
                    == EntryMode
                    .ROOT_INLINE_EVENT_REFERENCE) {
                return processor.processDocumentWithTrace(
                        scenario.root.clone(),
                        new Node().blueId(
                                scenario.eventBlueId));
            }
            if (variant.entryMode
                    == EntryMode.PARTIAL) {
                return processor.processDocumentWithTrace(
                        scenario.fragmentedRoot.clone(),
                        scenario.partialEvent.clone());
            }
            if (variant.entryMode
                    == EntryMode
                    .MIXED_FRAGMENT_BOUNDARIES) {
                return processor.processDocumentWithTrace(
                        scenario.mixedFragmentedRoot.clone(),
                        scenario.partialEvent.clone());
            }
            return processor.processDocumentWithTrace(
                    scenario.root.clone(),
                    scenario.event.clone());
        }

        long providerRequestCount() {
            return provider.snapshotMetrics()
                    .requestCount;
        }

        long providerBackendTrips() {
            return provider.snapshotMetrics()
                    .backendTrips;
        }

        long providerBackendBytes() {
            return provider.snapshotMetrics()
                    .backendBytes;
        }

        private ProviderMetrics providerMetrics() {
            return provider.snapshotMetrics();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            processor.close();
            blue.close();
        }
    }

    private static final class Run {
        private final Variant variant;
        private final Scenario scenario;
        private final ResolvedSnapshot inputSnapshot;
        private final ProcessingDebugResult debug;
        private final ProviderMetrics providerMetrics;

        private Run(
                Variant variant,
                Scenario scenario,
                ResolvedSnapshot inputSnapshot,
                ProcessingDebugResult debug,
                ProviderMetrics providerMetrics) {
            this.variant = variant;
            this.scenario = scenario;
            this.inputSnapshot = inputSnapshot;
            this.debug = debug;
            this.providerMetrics = providerMetrics;
        }
    }

    private static final class Scenario {
        private static final Scenario INLINE_SCENARIO =
                create(BodyForm.INLINE);
        private static final Scenario REFERENCE_SCENARIO =
                create(BodyForm.REFERENCE);

        private final Node root;
        private final Node fragmentedRoot;
        private final Node mixedFragmentedRoot;
        private final Node event;
        private final Node partialEvent;
        private final String rootBlueId;
        private final String eventBlueId;
        private final String leafPath;
        private final List<String> spinePaths;
        private final List<String> ancestorPaths;
        private final Set<String> executableBodyPaths;
        private final Set<String> physicallyDeferredPaths;
        private final Map<String, Node> providerBodies;
        private final Set<String> selectedClosureBlueIds;
        private final Set<String> unrelatedBodyBlueIds;
        private final String selectedBodyBlueId;
        private final long selectedBodyBytes;
        private final long selectedClosureBytes;
        private final long unrelatedBodyBytes;
        private final ExternalDeliveryPlan plan;

        private Scenario(
                Node root,
                Node fragmentedRoot,
                Node mixedFragmentedRoot,
                Node event,
                Node partialEvent,
                String rootBlueId,
                String eventBlueId,
                String leafPath,
                List<String> spinePaths,
                List<String> ancestorPaths,
                Set<String> executableBodyPaths,
                Set<String> physicallyDeferredPaths,
                Map<String, Node> providerBodies,
                Set<String> selectedClosureBlueIds,
                Set<String> unrelatedBodyBlueIds,
                String selectedBodyBlueId,
                long selectedBodyBytes,
                long selectedClosureBytes,
                long unrelatedBodyBytes,
                ExternalDeliveryPlan plan) {
            this.root = root;
            this.fragmentedRoot = fragmentedRoot;
            this.mixedFragmentedRoot =
                    mixedFragmentedRoot;
            this.event = event;
            this.partialEvent = partialEvent;
            this.rootBlueId = rootBlueId;
            this.eventBlueId = eventBlueId;
            this.leafPath = leafPath;
            this.spinePaths = spinePaths;
            this.ancestorPaths = ancestorPaths;
            this.executableBodyPaths = executableBodyPaths;
            this.physicallyDeferredPaths =
                    physicallyDeferredPaths;
            this.providerBodies = providerBodies;
            this.selectedClosureBlueIds =
                    selectedClosureBlueIds;
            this.unrelatedBodyBlueIds =
                    unrelatedBodyBlueIds;
            this.selectedBodyBlueId =
                    selectedBodyBlueId;
            this.selectedBodyBytes =
                    selectedBodyBytes;
            this.selectedClosureBytes =
                    selectedClosureBytes;
            this.unrelatedBodyBytes =
                    unrelatedBodyBytes;
            this.plan = plan;
        }

        private long providerBytes(
                Collection<String> blueIds) {
            long total = 0L;
            for (String blueId : blueIds) {
                Node exact = providerBodies.get(
                        blueId);
                if (exact == null) {
                    throw new AssertionError(
                            "Missing provider fixture for "
                                    + blueId);
                }
                total += NodeCanonicalizer
                        .canonicalSize(exact);
            }
            return total;
        }

        private static Scenario forForm(
                BodyForm bodyForm) {
            return bodyForm == BodyForm.INLINE
                    ? INLINE_SCENARIO
                    : REFERENCE_SCENARIO;
        }

        private static Scenario create(BodyForm bodyForm) {
            Map<String, Node> providerBodies =
                    new LinkedHashMap<>();
            Set<String> unrelatedBodyBlueIds =
                    new LinkedHashSet<>();
            Set<String> executableBodyPaths =
                    new LinkedHashSet<>();
            Set<String> physicallyDeferredPaths =
                    new LinkedHashSet<>();
            List<String> spinePaths =
                    new ArrayList<>();
            List<String> ancestorPaths =
                    new ArrayList<>();

            String leafPath = "/";
            for (int level = 1;
                 level < SPINE_SCOPE_COUNT;
                 level++) {
                leafPath = childPath(
                        leafPath, SELECTED_SEGMENT);
            }
            String firstAssetBlueId =
                    addProviderBody(
                            providerBodies,
                            selectedAsset("first"));
            String secondAssetBlueId =
                    addProviderBody(
                            providerBodies,
                            selectedAsset("second"));
            Node selectedBody =
                    selectedBody(
                            leafPath,
                            firstAssetBlueId,
                            secondAssetBlueId);
            String selectedBodyBlueId =
                    addProviderBody(
                            providerBodies, selectedBody);
            Set<String> selectedClosure =
                    new LinkedHashSet<>();
            selectedClosure.add(selectedBodyBlueId);
            selectedClosure.add(firstAssetBlueId);
            selectedClosure.add(secondAssetBlueId);

            Node root = buildSpineScope(
                    0,
                    "/",
                    leafPath,
                    bodyForm,
                    selectedBody,
                    selectedBodyBlueId,
                    providerBodies,
                    unrelatedBodyBlueIds,
                    executableBodyPaths,
                    physicallyDeferredPaths,
                    spinePaths,
                    ancestorPaths);
            physicallyDeferredPaths.addAll(
                    executableBodyPaths);
            Node eventMetadata = new Node()
                    .properties(
                            "kind",
                            new Node().value(
                                    "deep-locality-metadata"))
                    .properties(
                            "hostPayload",
                            new Node().value(
                                    padding(
                                            UNRELATED_BODY_PAYLOAD_BYTES,
                                            'm')));
            String eventMetadataBlueId =
                    addProviderBody(
                            providerBodies,
                            eventMetadata);
            unrelatedBodyBlueIds.add(
                    eventMetadataBlueId);
            Node event = new Node()
                    .properties(
                            "subscriptionKey",
                            new Node().value(
                                    SUBSCRIPTION_KEY))
                    .properties(
                            "eventId",
                            new Node().value(
                                    "deep-locality-event"))
                    .properties(
                            "kind",
                            new Node().value("selected"))
                    .properties(
                            "metadata",
                            eventMetadata);
            Node partialEvent = event.clone();
            partialEvent.getProperties().put(
                    "metadata",
                    new Node().blueId(
                            eventMetadataBlueId));
            Node fragmentedRoot = root.clone();
            Node mixedFragmentedRoot = root.clone();
            int ancestorIndex = 0;
            for (String ancestorPath : ancestorPaths) {
                for (String siblingSegment :
                        Arrays.asList(
                                LEFT_SEGMENT,
                                RIGHT_SEGMENT)) {
                    String siblingPath =
                            childPath(
                                    ancestorPath,
                                    siblingSegment);
                    Node sibling =
                            rootAt(root, siblingPath);
                    String siblingBlueId =
                            addProviderBody(
                                    providerBodies,
                                    sibling);
                    unrelatedBodyBlueIds.add(
                            siblingBlueId);
                    physicallyDeferredPaths.add(
                            siblingPath);
                    NodePathEditor.put(
                            fragmentedRoot,
                            siblingPath,
                            new Node().blueId(
                                    siblingBlueId));
                    String mixedBoundary =
                            ancestorIndex % 2 == 0
                                    ? LEFT_SEGMENT
                                    : RIGHT_SEGMENT;
                    if (mixedBoundary.equals(
                            siblingSegment)) {
                        NodePathEditor.put(
                                mixedFragmentedRoot,
                                siblingPath,
                                new Node().blueId(
                                        siblingBlueId));
                    }
                }
                ancestorIndex++;
            }
            String rootBlueId =
                    DirectBlueIdCalculator.calculateBlueId(root);
            if (!rootBlueId.equals(
                    DirectBlueIdCalculator.calculateBlueId(
                            fragmentedRoot))) {
                throw new IllegalStateException(
                        "Deep locality Root fragmentation changed identity");
            }
            if (!rootBlueId.equals(
                    DirectBlueIdCalculator.calculateBlueId(
                            mixedFragmentedRoot))) {
                throw new IllegalStateException(
                        "Mixed deep fragment boundaries changed Root identity");
            }
            providerBodies.put(
                    rootBlueId,
                    fragmentedRoot.clone());
            selectedClosure.add(rootBlueId);

            Node selectedChannel =
                    rootAt(root, contractPath(
                            leafPath, SELECTED_CHANNEL));
            String contribution =
                    DirectBlueIdCalculator.calculateBlueId(
                            selectedChannel);
            String checkpointDomain =
                    CheckpointDomain.derive(
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL,
                            Collections.singletonList(
                                    contribution),
                            CHECKPOINT_DISCRIMINATOR);
            String eventBlueId =
                    DirectBlueIdCalculator.calculateBlueId(event);
            if (!eventBlueId.equals(
                    DirectBlueIdCalculator.calculateBlueId(
                            partialEvent))) {
                throw new IllegalStateException(
                        "Partial Event fragmentation changed identity");
            }
            providerBodies.put(
                    eventBlueId,
                    partialEvent.clone());
            selectedClosure.add(eventBlueId);
            ExternalDeliverySnapshot delivery =
                    ExternalDeliverySnapshot.builder(
                                    leafPath,
                                    SELECTED_CHANNEL)
                            .order(0)
                            .sourceContribution(contribution)
                            .effectiveTypeBlueId(
                                    MockTypeBlueIds
                                            .MOCK_EXTERNAL_CHANNEL)
                            .subscriptionKey(
                                    SUBSCRIPTION_KEY)
                            .checkpointDomainBlueId(
                                    checkpointDomain)
                            .checkpointSubjectBlueId(
                                    eventBlueId)
                            .build();
            SubscriptionDelta.Entry active =
                    new SubscriptionDelta.Entry(
                            leafPath,
                            SELECTED_CHANNEL,
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL,
                            Collections.singletonList(
                                    contribution),
                            0,
                            Collections.singletonList(
                                    SUBSCRIPTION_KEY),
                            checkpointDomain,
                            0L,
                            null,
                            null);
            ExternalDeliveryPlan plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(17L, 17L)
                            .eventOrderKey(EVENT_ORDER)
                            .delivery(delivery)
                            .activeSubscriptionInterval(
                                    active)
                            .exactRuntimeState()
                            .build();

            long unrelatedBytes = 0L;
            for (String blueId : unrelatedBodyBlueIds) {
                unrelatedBytes += NodeCanonicalizer
                        .canonicalSize(
                                providerBodies.get(blueId));
            }
            long selectedClosureBytes = 0L;
            for (String blueId : selectedClosure) {
                selectedClosureBytes +=
                        NodeCanonicalizer.canonicalSize(
                                providerBodies.get(blueId));
            }
            long selectedBodyBytes =
                    NodeCanonicalizer.canonicalSize(
                            selectedBody);

            return new Scenario(
                    root,
                    fragmentedRoot,
                    mixedFragmentedRoot,
                    event,
                    partialEvent,
                    rootBlueId,
                    eventBlueId,
                    leafPath,
                    Collections.unmodifiableList(
                            new ArrayList<>(spinePaths)),
                    Collections.unmodifiableList(
                            new ArrayList<>(ancestorPaths)),
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    executableBodyPaths)),
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    physicallyDeferredPaths)),
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    providerBodies)),
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    selectedClosure)),
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    unrelatedBodyBlueIds)),
                    selectedBodyBlueId,
                    selectedBodyBytes,
                    selectedClosureBytes,
                    unrelatedBytes,
                    plan);
        }

        private static Node buildSpineScope(
                int level,
                String scopePath,
                String leafPath,
                BodyForm bodyForm,
                Node selectedBody,
                String selectedBodyBlueId,
                Map<String, Node> providerBodies,
                Set<String> unrelatedBodyBlueIds,
                Set<String> executableBodyPaths,
                Set<String> physicallyDeferredPaths,
                List<String> spinePaths,
                List<String> ancestorPaths) {
            spinePaths.add(scopePath);
            Node scope = new Node()
                    .properties(
                            "level",
                            new Node().value(level))
                    .properties(
                            "localState",
                            new Node().value(
                                    level == SPINE_SCOPE_COUNT - 1
                                            ? "pending"
                                            : "unchanged"));
            Node contracts = new Node();
            scope.contracts(contracts);
            addPreinitializedMarker(
                    contracts, "spine-" + level);
            addDecoyWorkflows(
                    scopePath,
                    "spine-" + level,
                    contracts,
                    providerBodies,
                    unrelatedBodyBlueIds,
                    executableBodyPaths);

            if (level == SPINE_SCOPE_COUNT - 1) {
                Node incoming = new Node()
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
                                        CHECKPOINT_DISCRIMINATOR));
                Node selected = new Node()
                        .type(new Node().blueId(
                                MockTypeBlueIds.MOCK_HANDLER))
                        .properties(
                                "channel",
                                new Node().value(
                                        SELECTED_CHANNEL))
                        .properties(
                                "order",
                                new Node().value(0))
                        .properties(
                                "result",
                                bodyForm == BodyForm.INLINE
                                        ? selectedBody.clone()
                                        : new Node().blueId(
                                        selectedBodyBlueId));
                contracts.properties(
                        SELECTED_CHANNEL, incoming);
                contracts.properties(
                        SELECTED_HANDLER, selected);
                executableBodyPaths.add(
                        contractPath(
                                scopePath,
                                SELECTED_HANDLER)
                                + "/result");
                return scope;
            }

            ancestorPaths.add(scopePath);
            contracts.properties(
                    "embedded",
                    new Node()
                            .type(new Node().blueId(
                                    RuntimeBlueIds
                                            .PROCESS_EMBEDDED))
                            .properties(
                                    "paths",
                                    list(
                                            "/" + SELECTED_SEGMENT,
                                            "/" + LEFT_SEGMENT,
                                            "/" + RIGHT_SEGMENT)));
            contracts.properties(
                    RELAY_CHANNEL,
                    new Node()
                            .type(new Node().blueId(
                                    RuntimeBlueIds
                                            .EMBEDDED_NODE_CHANNEL))
                            .properties(
                                    "order",
                                    new Node().value(0))
                            .properties(
                                    "sourcePath",
                                    new Node().value(
                                            "/" + SELECTED_SEGMENT)));
            contracts.properties(
                    RELAY_HANDLER,
                    new Node()
                            .type(new Node().blueId(
                                    RELAY_HANDLER_TYPE_BLUE_ID))
                            .properties(
                                    "channel",
                                    new Node().value(
                                            RELAY_CHANNEL))
                            .properties(
                                    "order",
                                    new Node().value(0)));

            String selectedPath =
                    childPath(scopePath, SELECTED_SEGMENT);
            scope.properties(
                    SELECTED_SEGMENT,
                    buildSpineScope(
                            level + 1,
                            selectedPath,
                            leafPath,
                            bodyForm,
                            selectedBody,
                            selectedBodyBlueId,
                            providerBodies,
                            unrelatedBodyBlueIds,
                            executableBodyPaths,
                            physicallyDeferredPaths,
                            spinePaths,
                            ancestorPaths));
            scope.properties(
                    LEFT_SEGMENT,
                    siblingScope(
                            childPath(
                                    scopePath,
                                    LEFT_SEGMENT),
                            "left-" + level,
                            providerBodies,
                            unrelatedBodyBlueIds,
                            executableBodyPaths,
                            physicallyDeferredPaths));
            scope.properties(
                    RIGHT_SEGMENT,
                    siblingScope(
                            childPath(
                                    scopePath,
                                    RIGHT_SEGMENT),
                            "right-" + level,
                            providerBodies,
                            unrelatedBodyBlueIds,
                            executableBodyPaths,
                            physicallyDeferredPaths));
            return scope;
        }

        private static Node siblingScope(
                String scopePath,
                String tag,
                Map<String, Node> providerBodies,
                Set<String> unrelatedBodyBlueIds,
                Set<String> executableBodyPaths,
                Set<String> physicallyDeferredPaths) {
            Node contracts = new Node();
            Node sibling = new Node()
                    .properties(
                            "tag",
                            new Node().value(tag))
                    .properties(
                            "unchanged",
                            new Node().value(true))
                    .contracts(contracts);
            addPreinitializedMarker(contracts, tag);
            addDecoyWorkflows(
                    scopePath,
                    tag,
                    contracts,
                    providerBodies,
                    unrelatedBodyBlueIds,
                    executableBodyPaths);
            Node archive =
                    largeSiblingSubgraph(tag);
            String archiveBlueId =
                    addProviderBody(
                            providerBodies, archive);
            unrelatedBodyBlueIds.add(
                    archiveBlueId);
            sibling.properties(
                    "archive",
                    new Node().blueId(
                            archiveBlueId));
            physicallyDeferredPaths.add(
                    childPath(scopePath, "archive"));
            return sibling;
        }

        private static void addPreinitializedMarker(
                Node contracts,
                String documentName) {
            Node exactDocument = new Node().value(
                    "preinitialized-" + documentName);
            contracts.properties(
                    "initialized",
                    new Node()
                            .type(new Node().blueId(
                                    RuntimeBlueIds
                                            .PROCESSING_INITIALIZED_MARKER))
                            .properties(
                                    "document",
                                    new Node().blueId(
                                            DirectBlueIdCalculator.calculateBlueId(
                                                    exactDocument))));
        }

        private static void addDecoyWorkflows(
                String scopePath,
                String tag,
                Node contracts,
                Map<String, Node> providerBodies,
                Set<String> unrelatedBodyBlueIds,
                Set<String> executableBodyPaths) {
            String channelKey = "never_" + tag;
            contracts.properties(
                    channelKey,
                    new Node()
                            .type(new Node().blueId(
                                    RuntimeBlueIds
                                            .TRIGGERED_EVENT_CHANNEL))
                            .properties(
                                    "order",
                                    new Node().value(100))
                            .properties(
                                    "event",
                                    new Node().properties(
                                            "kind",
                                            new Node().value(
                                                    "never-" + tag))));
            for (int index = 0;
                 index < DECOY_HANDLERS_PER_SCOPE;
                 index++) {
                String handlerKey =
                        "workflow_" + index + "_" + tag;
                Node body = unrelatedBody(
                        tag + "-" + index);
                String bodyBlueId =
                        addProviderBody(
                                providerBodies, body);
                unrelatedBodyBlueIds.add(bodyBlueId);
                contracts.properties(
                        handlerKey,
                        new Node()
                                .type(new Node().blueId(
                                        MockTypeBlueIds
                                                .MOCK_HANDLER))
                                .properties(
                                        "channel",
                                        new Node().value(
                                                channelKey))
                                .properties(
                                        "order",
                                        new Node().value(
                                                100 + index))
                                .properties(
                                        "event",
                                        new Node().properties(
                                                "kind",
                                                new Node().value(
                                                        "never-"
                                                                + tag)))
                                .properties(
                                        "result",
                                        new Node().blueId(
                                                bodyBlueId)));
                executableBodyPaths.add(
                        contractPath(
                                scopePath, handlerKey)
                                + "/result");
            }
        }

        private static Node selectedBody(
                String leafPath,
                String firstAssetBlueId,
                String secondAssetBlueId) {
            Node patch = new Node()
                    .properties(
                            "op",
                            new Node().value("replace"))
                    .properties(
                            "path",
                            new Node().value(
                                    leafPath + "/localState"))
                    .properties(
                            "val",
                            new Node().value(
                                    "processed"));
            Node firstEvent = new Node()
                    .properties(
                            "kind",
                            new Node().value(
                                    "deep-locality-result"))
                    .properties(
                            "ordinal",
                            new Node().value(1))
                    .properties(
                            "eventId",
                            new Node().value("root-a"));
            Node secondEvent = new Node()
                    .properties(
                            "kind",
                            new Node().value(
                                    "deep-locality-result"))
                    .properties(
                            "ordinal",
                            new Node().value(2))
                    .properties(
                            "eventId",
                            new Node().value("root-b"));
            return new Node()
                    .properties(
                            "patches",
                            list(patch))
                    .properties(
                            "events",
                            list(firstEvent, secondEvent))
                    .properties(
                            "selectedAssets",
                            list(
                                    new Node().blueId(
                                            firstAssetBlueId),
                                    new Node().blueId(
                                            secondAssetBlueId)))
                    .properties(
                            "hostPayload",
                            new Node().value(
                                    padding(
                                            SELECTED_BODY_PAYLOAD_BYTES,
                                            's')));
        }

        private static Node selectedAsset(
                String tag) {
            return new Node()
                    .properties(
                            "tag",
                            new Node().value(
                                    "selected-" + tag))
                    .properties(
                            "hostPayload",
                            new Node().value(
                                    padding(2_000, 'c')));
        }

        private static Node unrelatedBody(String tag) {
            return new Node()
                    .properties(
                            "patches",
                            new Node().items(
                                    Collections.<Node>emptyList()))
                    .properties(
                            "events",
                            new Node().items(
                                    Collections.<Node>emptyList()))
                    .properties(
                            "tag",
                            new Node().value(tag))
                    .properties(
                            "hostPayload",
                            new Node().value(
                                    padding(
                                            UNRELATED_BODY_PAYLOAD_BYTES,
                                            (char) ('a'
                                                    + Math.abs(
                                                    tag.hashCode())
                                                    % 26))));
        }

        private static Node largeSiblingSubgraph(
                String tag) {
            Node root = new Node()
                    .properties(
                            "tag",
                            new Node().value(tag))
                    .properties(
                            "hostPayload",
                            new Node().value(
                                    padding(
                                            UNRELATED_BODY_PAYLOAD_BYTES,
                                            'u')));
            Node cursor = root;
            for (int level = 0; level < 6; level++) {
                Node child = new Node()
                        .properties(
                                "level",
                                new Node().value(level))
                        .properties(
                                "sentinel",
                                new Node().value(
                                        tag + "-" + level));
                cursor.properties(
                        "nested_" + level, child);
                cursor = child;
            }
            return root;
        }

        private static String addProviderBody(
                Map<String, Node> providerBodies,
                Node body) {
            String blueId =
                    DirectBlueIdCalculator.calculateBlueId(body);
            providerBodies.put(blueId, body.clone());
            return blueId;
        }

        private static Node rootAt(
                Node root,
                String pointer) {
            Node current = root;
            for (String segment :
                    blue.language.model.wire.JsonPointer.split(
                            pointer)) {
                if ("contracts".equals(segment)) {
                    current = current.getContracts();
                } else {
                    current = current.getProperties()
                            .get(segment);
                }
                if (current == null) {
                    throw new IllegalStateException(
                            "Missing scenario path "
                                    + pointer);
                }
            }
            return current;
        }
    }

    private static Node list(Node... values) {
        return new Node().items(
                Arrays.asList(values));
    }

    private static Node list(String... values) {
        List<Node> nodes =
                new ArrayList<>(values.length);
        for (String value : values) {
            nodes.add(new Node().value(value));
        }
        return new Node().items(nodes);
    }

    private static String padding(
            int size,
            char value) {
        char[] chars = new char[size];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    public static final class RelayHandler
            extends HandlerContract {
    }

    private static final class RelayHandlerProcessor
            implements HandlerProcessor<RelayHandler> {
        @Override
        public Class<RelayHandler> contractType() {
            return RelayHandler.class;
        }

        @Override
        public boolean matches(
                RelayHandler contract,
                HandlerMatchContext context) {
            return true;
        }

        @Override
        public void execute(
                RelayHandler contract,
                ProcessorExecutionContext context) {
            context.emitEvent(context.event());
        }
    }

    /**
     * Test-host policy which keeps the scenario's known cold subgraphs
     * collapsed whenever the kernel asks the Language runtime to refresh a
     * snapshot. It delegates every cache-generation and incremental capability
     * to Blue's native manager.
     */
    private static final class LocalitySnapshotManager
            implements ProcessingSnapshotManager {
        private final ProcessingSnapshotManager delegate;
        private final Set<String> alwaysDeferredPaths;
        private final FrozenNode.ResolvedStructuralInterner
                structuralInterner;
        private final Map<FrozenNode.ResolvedStructuralKey, FrozenNode>
                internedNodes;

        private LocalitySnapshotManager(
                ProcessingSnapshotManager delegate,
                Collection<String> alwaysDeferredPaths) {
            this(
                    delegate,
                    alwaysDeferredPaths,
                    new LinkedHashMap<FrozenNode.ResolvedStructuralKey, FrozenNode>());
        }

        private LocalitySnapshotManager(
                ProcessingSnapshotManager delegate,
                Collection<String> alwaysDeferredPaths,
                Map<FrozenNode.ResolvedStructuralKey, FrozenNode>
                        internedNodes) {
            this.delegate = delegate;
            this.alwaysDeferredPaths =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    alwaysDeferredPaths));
            this.internedNodes = internedNodes;
            this.structuralInterner =
                    (key, candidate) -> {
                        synchronized (this.internedNodes) {
                            FrozenNode existing =
                                    this.internedNodes.get(key);
                            if (existing != null) {
                                return existing;
                            }
                            this.internedNodes.put(
                                    key, candidate);
                            return candidate;
                        }
                    };
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return intern(delegate
                    .fromDocumentPreservingPaths(
                            document,
                            alwaysDeferredPaths));
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(
                Node document) {
            return intern(delegate
                    .fromDocumentTransientPreservingPaths(
                            document,
                            alwaysDeferredPaths));
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return intern(delegate
                    .fromDocumentPreservingPaths(
                            document,
                            union(preservedPaths)));
        }

        @Override
        public ResolvedSnapshot
        fromDocumentTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return intern(delegate
                    .fromDocumentTransientPreservingPaths(
                            document,
                            union(preservedPaths)));
        }

        @Override
        public String calculateScopeContentBlueId(
                String scopePath,
                FrozenNode selectedScope,
                ResolvedSnapshot capturedDocumentSnapshot) {
            return delegate.calculateScopeContentBlueId(
                    scopePath,
                    selectedScope,
                    capturedDocumentSnapshot);
        }

        @Override
        public FrozenNode materializeVerifiedReference(
                FrozenNode reference) {
            return delegate.materializeVerifiedReference(
                    reference);
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            return delegate
                    .materializeVerifiedExactReference(
                            reference);
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            return new LocalitySnapshotManager(
                    delegate.transientSequence(),
                    alwaysDeferredPaths,
                    internedNodes);
        }

        @Override
        public ProcessingSnapshotManager
        forkTransientSequence() {
            return new LocalitySnapshotManager(
                    delegate.forkTransientSequence(),
                    alwaysDeferredPaths,
                    internedNodes);
        }

        @Override
        public void retainTransientState(
                FrozenNode canonicalRoot,
                FrozenNode resolvedRoot) {
            delegate.retainTransientState(
                    canonicalRoot, resolvedRoot);
        }

        @Override
        public void releaseTransientState() {
            delegate.releaseTransientState();
        }

        @Override
        public boolean isTransientStateCurrent() {
            return delegate.isTransientStateCurrent();
        }

        @Override
        public boolean supportsIncrementalValueResolution() {
            return delegate
                    .supportsIncrementalValueResolution();
        }

        @Override
        public boolean supportsIncrementalValueResolution(
                IncrementalValueResolutionRequest request) {
            return delegate
                    .supportsIncrementalValueResolution(
                            request);
        }

        @Override
        public ConformanceEngine transientConformanceEngine(
                ConformanceEngine conformanceEngine) {
            return delegate.transientConformanceEngine(
                    conformanceEngine);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return intern(delegate.applyPatch(
                    snapshot, patch));
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(
                ResolvedSnapshot snapshot) {
            return intern(delegate.cacheSnapshot(
                    snapshot));
        }

        private Set<String> union(
                Collection<String> requested) {
            Set<String> result =
                    new LinkedHashSet<>(
                            alwaysDeferredPaths);
            if (requested != null) {
                result.addAll(requested);
            }
            return result;
        }

        private ResolvedSnapshot intern(
                ResolvedSnapshot snapshot) {
            FrozenNode resolved =
                    FrozenNode.fromResolvedNode(
                            snapshot.resolvedRoot(),
                            structuralInterner);
            if (snapshot.isResolutionComplete()) {
                return new ResolvedSnapshot(
                        snapshot.frozenCanonicalRoot(),
                        resolved,
                        snapshot.blueId());
            }
            return ResolvedSnapshot.withDeferredResolution(
                    snapshot.frozenCanonicalRoot(),
                    resolved);
        }
    }

    private static final class MeasuredBodyProvider
            implements NodeProvider {
        private final Map<String, Node> backing;
        private final List<String> selectedClosureOrder;
        private final Set<String> selectedClosure;
        private final BatchMode batchMode;
        private final int batchSize;
        private final Map<String, Node> cache =
                new LinkedHashMap<>();
        private final List<String> requests =
                new ArrayList<>();
        private final Set<String> backendLoaded =
                new LinkedHashSet<>();
        private long backendTrips;
        private long backendBytes;

        private MeasuredBodyProvider(
                Map<String, Node> backing,
                Set<String> selectedClosure,
                BatchMode batchMode,
                int batchSize) {
            this.backing =
                    new LinkedHashMap<>(backing);
            this.selectedClosureOrder =
                    new ArrayList<>(selectedClosure);
            this.selectedClosure =
                    new LinkedHashSet<>(
                            selectedClosure);
            this.batchMode = batchMode;
            this.batchSize = batchSize;
        }

        @Override
        public synchronized List<Node> fetchByBlueId(
                String blueId) {
            requests.add(blueId);
            Node cached = cache.get(blueId);
            if (cached != null) {
                return Collections.singletonList(
                        cached.clone());
            }
            Node exact = backing.get(blueId);
            if (exact == null) {
                return null;
            }
            if (!selectedClosure.contains(blueId)) {
                throw new AssertionError(
                        "Provider request escaped the strict selected closure: "
                                + blueId);
            }
            backendTrips++;
            load(blueId);
            if (batchMode == BatchMode.BOUNDED_BATCH) {
                int loaded = 1;
                for (String candidate :
                        selectedClosureOrder) {
                    if (loaded >= batchSize) {
                        break;
                    }
                    if (!cache.containsKey(candidate)
                            && backing.containsKey(
                            candidate)) {
                        load(candidate);
                        loaded++;
                    }
                }
            }
            return Collections.singletonList(
                    cache.get(blueId).clone());
        }

        private void load(String blueId) {
            Node exact = backing.get(blueId);
            if (exact == null
                    || cache.containsKey(blueId)) {
                return;
            }
            cache.put(blueId, exact.clone());
            backendLoaded.add(blueId);
            backendBytes +=
                    NodeCanonicalizer.canonicalSize(
                            exact);
        }

        private synchronized void warmSelectedClosure() {
            for (String blueId :
                    selectedClosureOrder) {
                Node exact = backing.get(blueId);
                if (exact != null) {
                    cache.put(blueId, exact.clone());
                }
            }
        }

        private synchronized void resetMetrics() {
            requests.clear();
            backendLoaded.clear();
            backendTrips = 0L;
            backendBytes = 0L;
        }

        private synchronized Set<String>
        requestedBlueIds() {
            return Collections.unmodifiableSet(
                    new LinkedHashSet<>(requests));
        }

        private synchronized ProviderMetrics
        snapshotMetrics() {
            return new ProviderMetrics(
                    requests.size(),
                    new LinkedHashSet<>(requests),
                    new LinkedHashSet<>(
                            backendLoaded),
                    backendTrips,
                    backendBytes);
        }
    }

    private static final class ProviderMetrics {
        private final long requestCount;
        private final Set<String> requestedBlueIds;
        private final Set<String> backendLoadedBlueIds;
        private final long backendTrips;
        private final long backendBytes;

        private ProviderMetrics(
                long requestCount,
                Set<String> requestedBlueIds,
                Set<String> backendLoadedBlueIds,
                long backendTrips,
                long backendBytes) {
            this.requestCount = requestCount;
            this.requestedBlueIds =
                    Collections.unmodifiableSet(
                            requestedBlueIds);
            this.backendLoadedBlueIds =
                    Collections.unmodifiableSet(
                            backendLoadedBlueIds);
            this.backendTrips = backendTrips;
            this.backendBytes = backendBytes;
        }
    }

    private static final class SemanticProjection {
        private final ProcessorStatus status;
        private final String resultingRootBlueId;
        private final List<String> rootEventBlueIds;
        private final long totalGas;
        private final List<String> namedGasTrace;
        private final List<String> traceRecords;
        private final List<String> semanticDemands;
        private final List<String> selectedScopeHandlerOrder;

        private SemanticProjection(
                ProcessorStatus status,
                String resultingRootBlueId,
                List<String> rootEventBlueIds,
                long totalGas,
                List<String> namedGasTrace,
                List<String> traceRecords,
                List<String> semanticDemands,
                List<String> selectedScopeHandlerOrder) {
            this.status = status;
            this.resultingRootBlueId =
                    resultingRootBlueId;
            this.rootEventBlueIds =
                    rootEventBlueIds;
            this.totalGas = totalGas;
            this.namedGasTrace = namedGasTrace;
            this.traceRecords = traceRecords;
            this.semanticDemands = semanticDemands;
            this.selectedScopeHandlerOrder =
                    selectedScopeHandlerOrder;
        }

        private static SemanticProjection of(
                ProcessingDebugResult debug) {
            DocumentProcessingResult result =
                    debug.processResult();
            return new SemanticProjection(
                    result.status(),
                    DirectBlueIdCalculator.calculateBlueId(
                            result.document()),
                    nodeBlueIds(result.events()),
                    result.totalGas(),
                    gasProjection(debug.trace()),
                    recordProjection(debug.trace()),
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    debug.trace()
                                            .semanticDemands())),
                    selectedScopeHandlerOrder(
                            debug.trace()));
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

        private static List<String> recordProjection(
                ProcessingConformanceTrace trace) {
            List<String> projection =
                    new ArrayList<>();
            for (ProcessingTraceRecord record :
                    trace.records()) {
                projection.add(
                        record.sequence()
                                + "|" + record.kind()
                                + "|" + record.scopePath()
                                + "|" + record.contractKey()
                                + "|" + record.logicalPath()
                                + "|" + record.details()
                                + "|" + (record.node() != null
                                ? DirectBlueIdCalculator
                                .calculateBlueId(
                                        record.node())
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
                    && resultingRootBlueId.equals(
                    that.resultingRootBlueId)
                    && rootEventBlueIds.equals(
                    that.rootEventBlueIds)
                    && namedGasTrace.equals(
                    that.namedGasTrace)
                    && traceRecords.equals(
                    that.traceRecords)
                    && semanticDemands.equals(
                    that.semanticDemands)
                    && selectedScopeHandlerOrder.equals(
                    that.selectedScopeHandlerOrder);
        }

        @Override
        public int hashCode() {
            int result = status.hashCode();
            result = 31 * result
                    + resultingRootBlueId.hashCode();
            result = 31 * result
                    + rootEventBlueIds.hashCode();
            result = 31 * result
                    + (int) (totalGas
                    ^ (totalGas >>> 32));
            result = 31 * result
                    + namedGasTrace.hashCode();
            result = 31 * result
                    + traceRecords.hashCode();
            result = 31 * result
                    + semanticDemands.hashCode();
            return 31 * result
                    + selectedScopeHandlerOrder.hashCode();
        }

        @Override
        public String toString() {
            return "SemanticProjection{"
                    + "status=" + status
                    + ", root=" + resultingRootBlueId
                    + ", events=" + rootEventBlueIds
                    + ", gas=" + totalGas
                    + ", handlers="
                    + selectedScopeHandlerOrder
                    + '}';
        }
    }
}
