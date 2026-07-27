package blue.language.processor;

import blue.language.model.Node;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalChannelCatalogContextTest {

    private static final Node SOURCE_TYPE =
            new Node().name("Catalog Source Channel");
    private static final String SOURCE_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(SOURCE_TYPE);
    private static final Node TARGET_TYPE =
            new Node().name("Catalog Target Channel");
    private static final String TARGET_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(TARGET_TYPE);
    private static final Node NON_CHANNEL_TYPE =
            new Node()
                    .name("Catalog Non-Channel Handler")
                    .type(reference(RuntimeBlueIds.HANDLER));
    private static final String NON_CHANNEL_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(
                    NON_CHANNEL_TYPE);
    private static final String TARGET_DEPENDENCY_BLUE_ID =
            BlueIdCalculator.calculateBlueId(
                    new Node().value("target-header-dependency"));
    private static final Node EVENT = new Node()
            .properties(
                    "subscriptionKey",
                    new Node().value("catalog-topic"))
            .properties(
                    "payload",
                    new Node().value("exact-event"));

    @Test
    void declaredCatalogIncludesBothChannelRolesWithoutEvaluatingPeers() {
        TargetProcessor targetProcessor = new TargetProcessor();
        try (DocumentProcessor processor =
                     processor(targetProcessor)) {
            ContractBundle bundle = bundle(
                    true,
                    true,
                    "target",
                    true);

            ExternalChannelFunctionEvaluation evaluation =
                    evaluate(processor, bundle);

            assertTrue(evaluation.accepts());
            assertTrue(
                    evaluation.dependencies()
                            .wholeSameScopeChannelCatalog());
            assertEquals(
                    Arrays.asList(
                            "managed",
                            "target",
                            "source"),
                    channelKeys(
                            evaluation.dependencies()
                                    .channelEntries()));
            assertEquals(
                    Arrays.asList(
                            "handler",
                            "managed",
                            "source",
                            "target"),
                    evaluation.dependencies()
                            .channelCatalogContractKeys());
            assertEquals(
                    "processor-channel",
                    channelEntry(
                            evaluation.dependencies(),
                            "managed").role());
            assertEquals(
                    "external-channel",
                    channelEntry(
                            evaluation.dependencies(),
                            "target").role());
            assertEquals(
                    Collections.singletonList(
                            TARGET_DEPENDENCY_BLUE_ID),
                    channelEntry(
                            evaluation.dependencies(),
                            "target")
                            .deterministicDependencyNodeBlueIds());
            assertNull(
                    channelEntry(
                            evaluation.dependencies(),
                            "handler"));
            assertEquals(0, targetProcessor.headerEvaluations);

            ChannelMemberSnapshot routed =
                    evaluation.handlerChannel();
            assertNotNull(routed);
            assertEquals("target", routed.channelKey());
            assertEquals(2, routed.order());
            assertEquals(
                    TARGET_TYPE_BLUE_ID,
                    routed.effectiveTypeBlueId());
            assertTrue(routed.externalSource());
            assertEquals(
                    channelEntry(
                            evaluation.dependencies(),
                            "target")
                            .sourceContributionNodeBlueIds(),
                    routed.sourceContributionNodeBlueIds());
            assertEquals(
                    channelEntry(
                            evaluation.dependencies(),
                            "target").headerIdentityBlueId(),
                    routed.headerIdentityBlueId());
            assertEquals(
                    BlueIdCalculator.calculateBlueId(
                            new Node()
                                    .type(reference(
                                            TARGET_TYPE_BLUE_ID))
                                    .properties(
                                            "label",
                                            new Node().value(
                                                    "target-label"))),
                    routed.headerIdentityBlueId());
            assertEquals(
                    "target-label",
                    routed.contractNode().get("/label"));
            assertFalse(
                    routed.contractNode()
                            .getProperties()
                            .containsKey("program"));

            Node mutatedCopy = routed.contractNode();
            mutatedCopy.getProperties().put(
                    "label",
                    new Node().value("mutated"));
            assertEquals(
                    "target-label",
                    routed.contractNode().get("/label"));

            ExternalChannelFunctionEvaluation managedEvaluation =
                    evaluate(
                            processor,
                            bundle(
                                    true,
                                    true,
                                    "managed",
                                    true));
            ChannelMemberSnapshot managedTarget =
                    managedEvaluation.handlerChannel();
            assertNotNull(managedTarget);
            assertEquals("managed", managedTarget.channelKey());
            assertEquals(
                    "processor-channel",
                    managedTarget.role());
            assertFalse(managedTarget.externalSource());
            assertEquals(0, targetProcessor.headerEvaluations);
        }
    }

    @Test
    void eventLookupFailsClosedForUndeclaredAndNonChannelKeys() {
        TargetProcessor targetProcessor = new TargetProcessor();
        try (DocumentProcessor processor =
                     processor(targetProcessor)) {
            IllegalStateException undeclared =
                    assertThrows(
                            IllegalStateException.class,
                            () -> evaluate(
                                    processor,
                                    bundle(
                                            false,
                                            true,
                                            "target",
                                            false)));
            assertTrue(undeclared.getMessage().contains(
                    "undeclared same-scope Channel header"));

            ExternalChannelFunctionEvaluation absent =
                    evaluate(
                            processor,
                            bundle(
                                    true,
                                    true,
                                    "absent",
                                    false));
            assertFalse(absent.accepts());
            assertNull(absent.handlerChannel());

            IllegalStateException nonChannel =
                    assertThrows(
                            IllegalStateException.class,
                            () -> evaluate(
                                    processor,
                                    bundle(
                                            true,
                                            true,
                                            "handler",
                                            false)));
            assertTrue(nonChannel.getMessage().contains(
                    "not a Channel"));
        }
    }

    @Test
    void everyPeerRouteRequiresAnExactOrCatalogDependency() {
        TargetProcessor targetProcessor = new TargetProcessor();
        try (DocumentProcessor processor =
                     processor(targetProcessor)) {
            IllegalStateException undeclared =
                    assertThrows(
                            IllegalStateException.class,
                            () -> evaluate(
                                    processor,
                                    bundle(
                                            false,
                                            false,
                                            "target",
                                            true)));
            assertTrue(undeclared.getMessage().contains(
                    "was not declared"));

            IllegalStateException declared =
                    assertThrows(
                            IllegalStateException.class,
                            () -> evaluate(
                                    processor,
                                    bundle(
                                            true,
                                            false,
                                            "absent",
                                            true)));
            assertTrue(declared.getMessage().contains(
                    "absent from the same-scope Channel catalog"));
        }
    }

    @Test
    void genericChannelDependenciesRoundTripAndCoverExactHeaders() {
        ExternalChannelDependencySnapshot.ChannelEntry external =
                new ExternalChannelDependencySnapshot.ChannelEntry(
                        "target",
                        2,
                        TARGET_TYPE_BLUE_ID,
                        "external-channel",
                        Collections.singletonList("target-source"),
                        Collections.singletonList(
                                TARGET_DEPENDENCY_BLUE_ID),
                        "target-header");
        ExternalChannelDependencySnapshot.ChannelEntry managed =
                new ExternalChannelDependencySnapshot.ChannelEntry(
                        "managed",
                        2,
                        RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL,
                        "processor-channel",
                        Collections.singletonList("managed-source"),
                        Collections.<String>emptyList(),
                        "managed-header");
        ExternalChannelDependencySnapshot original =
                new ExternalChannelDependencySnapshot(
                        Collections.<String>emptyList(),
                        Collections
                                .<ExternalChannelDependencySnapshot.Entry>
                                emptyList(),
                        Collections
                                .<ExternalChannelDependencySnapshot.TypeFamily>
                                emptyList(),
                        false,
                        Arrays.asList(managed, external),
                        true,
                        Arrays.asList(
                                "handler",
                                "managed",
                                "target"));
        ExternalChannelDependencySnapshot reconstructed =
                new ExternalChannelDependencySnapshot(
                        original.intrinsicNodeBlueIds(),
                        original.entries(),
                        original.typeFamilies(),
                        original.wholeSameScopeExternalSurface(),
                        original.channelEntries(),
                        original.wholeSameScopeChannelCatalog(),
                        original.channelCatalogContractKeys());

        assertEquals(original, reconstructed);
        assertEquals(
                original.deterministicDependencyNodeBlueIds(),
                reconstructed
                        .deterministicDependencyNodeBlueIds());

        ExternalChannelDependencySnapshot exactDemand =
                channelDemand(
                        Collections.singletonList(external),
                        false);
        assertTrue(original.covers(exactDemand));

        ExternalChannelDependencySnapshot changedHeaderDemand =
                channelDemand(
                        Collections.singletonList(
                                new ExternalChannelDependencySnapshot
                                        .ChannelEntry(
                                        "target",
                                        2,
                                        TARGET_TYPE_BLUE_ID,
                                        "external-channel",
                                        Collections.singletonList(
                                                "target-source"),
                                        Collections.singletonList(
                                                TARGET_DEPENDENCY_BLUE_ID),
                                        "changed-header")),
                        false);
        assertFalse(original.covers(changedHeaderDemand));

        ExternalChannelDependencySnapshot exactOnly =
                channelDemand(
                        Arrays.asList(managed, external),
                        false);
        assertFalse(
                exactOnly.covers(
                        channelDemand(
                                Arrays.asList(
                                        managed,
                                        external),
                                true)));
    }

    @Test
    void catalogRemovalAndRetypingRotateTheOwningSubscription() {
        TargetProcessor targetProcessor = new TargetProcessor();
        try (Blue blue = ProcessorTestSupport.blue()) {
            blue.registerExternalContractType(
                    SOURCE_TYPE_BLUE_ID,
                    SOURCE_TYPE,
                    new SourceProcessor());
            blue.registerExternalContractType(
                    TARGET_TYPE_BLUE_ID,
                    TARGET_TYPE,
                    targetProcessor);
            blue.registerExternalContractType(
                    NON_CHANNEL_TYPE_BLUE_ID,
                    NON_CHANNEL_TYPE,
                    new NonChannelProcessor());
            Node before = catalogDocument(
                    new Node()
                            .type(reference(
                                    TARGET_TYPE_BLUE_ID))
                            .properties(
                                    "label",
                                    new Node().value(
                                            "target-label")));
            Node removed = before.clone();
            removed.getContracts()
                    .getProperties()
                    .remove("target");
            Node retyped = catalogDocument(
                    new Node()
                            .type(reference(
                                    NON_CHANNEL_TYPE_BLUE_ID))
                            .properties(
                                    "channel",
                                    new Node().value(
                                            "source")));

            SubscriptionDelta removal =
                    validateCatalogChange(
                            blue,
                            before,
                            removed);
            SubscriptionDelta retyping =
                    validateCatalogChange(
                            blue,
                            before,
                            retyped);

            assertNotNull(deltaEntry(
                    removal.removed(), "source"));
            assertNotNull(deltaEntry(
                    removal.added(), "source"));
            assertNotNull(deltaEntry(
                    retyping.removed(), "source"));
            assertNotNull(deltaEntry(
                    retyping.added(), "source"));
            assertEquals(
                    Arrays.asList("source", "target"),
                    deltaEntry(
                            removal.removed(),
                            "source")
                            .dependencies()
                            .channelCatalogContractKeys());
            assertEquals(
                    Collections.singletonList("source"),
                    deltaEntry(
                            removal.added(),
                            "source")
                            .dependencies()
                            .channelCatalogContractKeys());
            assertEquals(
                    Arrays.asList("source", "target"),
                    deltaEntry(
                            retyping.added(),
                            "source")
                            .dependencies()
                            .channelCatalogContractKeys());
            assertNull(channelEntry(
                    deltaEntry(
                            retyping.added(),
                            "source")
                            .dependencies(),
                    "target"));
        }
    }

    @Test
    void pureReferenceProcessorChannelRetypingRotatesWholeCatalog() {
        Node nonChannel = new Node()
                .type(reference(
                        NON_CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "channel",
                        new Node().value("source"));
        Node managedChannel = new Node()
                .type(reference(
                        RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL))
                .properties(
                        "event",
                        new Node().properties(
                                "kind",
                                new Node().value(
                                        "managed-event")));
        String nonChannelBlueId =
                BlueIdCalculator.calculateBlueId(
                        nonChannel);
        String managedChannelBlueId =
                BlueIdCalculator.calculateBlueId(
                        managedChannel);
        BasicNodeProvider provider =
                new BasicNodeProvider(
                        nonChannel,
                        managedChannel);
        try (Blue blue = ProcessorTestSupport.blue(provider)) {
            blue.registerExternalContractType(
                    SOURCE_TYPE_BLUE_ID,
                    SOURCE_TYPE,
                    new SourceProcessor());
            blue.registerExternalContractType(
                    NON_CHANNEL_TYPE_BLUE_ID,
                    NON_CHANNEL_TYPE,
                    new NonChannelProcessor());

            SubscriptionDelta retyping =
                    validateCatalogChange(
                            blue,
                            catalogDocument(
                                    reference(
                                            nonChannelBlueId)),
                            catalogDocument(
                                    reference(
                                            managedChannelBlueId)));
            SubscriptionDelta.Entry removed =
                    deltaEntry(
                            retyping.removed(),
                            "source");
            SubscriptionDelta.Entry added =
                    deltaEntry(
                            retyping.added(),
                            "source");

            assertNotNull(removed);
            assertNotNull(added);
            assertEquals(
                    Arrays.asList("source", "target"),
                    removed.dependencies()
                            .channelCatalogContractKeys());
            assertEquals(
                    removed.dependencies()
                            .channelCatalogContractKeys(),
                    added.dependencies()
                            .channelCatalogContractKeys());
            assertNull(
                    channelEntry(
                            removed.dependencies(),
                            "target"));
            assertEquals(
                    "processor-channel",
                    channelEntry(
                            added.dependencies(),
                            "target").role());
        }
    }

    @Test
    void retainedCatalogRehydratesThroughSparseVerifierWithoutBodyDemand() {
        String coldBodyBlueId =
                BlueIdCalculator.calculateBlueId(
                        new Node().value(
                                "catalog-cold-body"));
        AtomicInteger bodyDemands =
                new AtomicInteger();
        NodeProvider provider = blueId -> {
            if (coldBodyBlueId.equals(blueId)) {
                bodyDemands.incrementAndGet();
            }
            return null;
        };
        TargetProcessor targetProcessor = new TargetProcessor();
        try (Blue blue = ProcessorTestSupport.blue(provider)) {
            blue.registerExternalContractType(
                    SOURCE_TYPE_BLUE_ID,
                    SOURCE_TYPE,
                    new SourceProcessor());
            blue.registerExternalContractType(
                    TARGET_TYPE_BLUE_ID,
                    TARGET_TYPE,
                    targetProcessor);
            blue.registerExternalContractType(
                    NON_CHANNEL_TYPE_BLUE_ID,
                    NON_CHANNEL_TYPE,
                    new NonChannelProcessor());
            Node document = catalogDocument(
                    new Node()
                            .type(reference(
                                    TARGET_TYPE_BLUE_ID))
                            .properties(
                                    "label",
                                    new Node().value(
                                            "target-label")));
            document.getContracts().properties(
                    "unrelated",
                    new Node()
                            .type(reference(
                                    NON_CHANNEL_TYPE_BLUE_ID))
                            .properties(
                                    "channel",
                                    new Node().value(
                                            "source"))
                            .properties(
                                    "program",
                                    reference(
                                            coldBodyBlueId)));
            DocumentProcessor languageProcessor =
                    blue.getDocumentProcessor();
            SubscriptionDelta initial =
                    languageProcessor
                            .subscriptionSurfaceValidator()
                            .validate(
                                    SubscriptionSurfaceValidationContext
                                            .builder(
                                                    new Node(),
                                                    document,
                                                    Collections.singleton(
                                                            "/contracts"),
                                                    GasSchedule.contracts10())
                                            .snapshots(
                                                    languageProcessor
                                                            .snapshotManager()
                                                            .fromDocumentTransient(
                                                                    new Node()),
                                                    languageProcessor
                                                            .snapshotManager()
                                                            .fromDocumentTransientPreservingPaths(
                                                                    document,
                                                                    Collections.singleton(
                                                                            "/contracts/unrelated/program")))
                                            .build());
            ExternalOrderKey order =
                    ExternalOrderKey.of(
                            Collections.<Object>singletonList(
                                    "catalog-verifier-event"));
            ExternalDeliveryPlan.Builder plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(2L, 2L)
                            .eventOrderKey(order)
                            .exactRuntimeState();
            for (SubscriptionDelta.Entry entry
                    : initial.added()) {
                plan.activeSubscriptionInterval(
                        new SubscriptionDelta.Entry(
                                entry.scopePath(),
                                entry.channelKey(),
                                entry.effectiveTypeBlueId(),
                                entry.sourceContributionNodeBlueIds(),
                                entry.order(),
                                entry.subscriptionKeys(),
                                entry.checkpointDomainBlueId(),
                                entry.dependencies(),
                                1L,
                                null,
                                null));
            }
            ExternalDeliveryPlan exactPlan = plan.build();
            try (DocumentProcessor verifier =
                         DocumentProcessor.builder()
                                 .registerContractProcessor(
                                         SOURCE_TYPE_BLUE_ID,
                                         SOURCE_TYPE,
                                         new SourceProcessor())
                                 .registerContractProcessor(
                                         TARGET_TYPE_BLUE_ID,
                                         TARGET_TYPE,
                                         targetProcessor)
                                 .registerContractProcessor(
                                         NON_CHANNEL_TYPE_BLUE_ID,
                                         NON_CHANNEL_TYPE,
                                         new NonChannelProcessor())
                                 .withMatchingService(
                                         new ContractMatchingService(
                                                 blue))
                                 .withSnapshotManager(
                                         languageProcessor
                                                 .snapshotManager())
                                 .withExternalDeliveryPlanDeriver(
                                         (root, event) ->
                                                 exactPlan)
                                 .build()) {
                DocumentProcessingResult result =
                        verifier.processDocument(
                                document,
                                new Node().properties(
                                        "subscriptionKey",
                                        new Node().value(
                                                "no-match")));

                assertEquals(
                        ProcessorStatus.NO_MATCH,
                        result.status(),
                        result.diagnostic() != null
                                ? result.diagnostic().message()
                                : null);
                assertEquals(0, bodyDemands.get());
            }
        }
    }

    @Test
    void wholeCatalogObeysThePortableMemberLimit() {
        TargetProcessor targetProcessor = new TargetProcessor();
        try (DocumentProcessor processor =
                     processor(targetProcessor)) {
            Node sourceNode = sourceNode(
                    true,
                    false,
                    "source",
                    false);
            FrozenNode frozenSource =
                    FrozenNode.fromResolvedNode(
                            sourceNode);
            EffectiveContractSnapshot source =
                    snapshotBuilder(
                            "source",
                            SOURCE_TYPE_BLUE_ID,
                            "external-channel",
                            0,
                            frozenSource.blueId())
                            .headerField(
                                    "subscriptionKey",
                                    freezeProperty(
                                            sourceNode,
                                            "subscriptionKey"))
                            .headerField(
                                    "declareCatalog",
                                    freezeProperty(
                                            sourceNode,
                                            "declareCatalog"))
                            .headerField(
                                    "inspectCatalog",
                                    freezeProperty(
                                            sourceNode,
                                            "inspectCatalog"))
                            .headerField(
                                    "lookupKey",
                                    freezeProperty(
                                            sourceNode,
                                            "lookupKey"))
                            .headerField(
                                    "routeToLookup",
                                    freezeProperty(
                                            sourceNode,
                                            "routeToLookup"))
                            .build();
            ContractBundle.Builder bundle =
                    ContractBundle.builder()
                            .addChannel(
                                    "source",
                                    new CatalogSourceChannel(),
                                    frozenSource)
                            .addEffectiveContractSnapshot(
                                    source);
            long limit = GasSchedule.contracts10()
                    .portableLimit(
                            "effectiveContractsPerParticipatingScope");
            for (int index = 0; index < limit; index++) {
                String key = String.format(
                        "managed-%05d", index);
                bundle.addEffectiveContractSnapshot(
                        snapshotBuilder(
                                key,
                                RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL,
                                "processor-channel",
                                index + 1,
                                BlueIdCalculator.calculateBlueId(
                                        new Node().value(
                                                key)))
                                .build());
            }

            IllegalStateException exceeded =
                    assertThrows(
                            IllegalStateException.class,
                            () -> new ExternalChannelFunctionResolver(
                                    processor.registry(),
                                    processor
                                            .contractConverter(),
                                    bundle.build())
                                    .header(source));

            assertTrue(exceeded.getMessage().contains(
                    "catalog exceeds " + limit));
        }
    }

    private static DocumentProcessor processor(
            TargetProcessor targetProcessor) {
        return DocumentProcessor.builder()
                .registerContractProcessor(
                        SOURCE_TYPE_BLUE_ID,
                        SOURCE_TYPE,
                        new SourceProcessor())
                .registerContractProcessor(
                        TARGET_TYPE_BLUE_ID,
                        TARGET_TYPE,
                        targetProcessor)
                .build();
    }

    private static Node catalogDocument(
            Node target) {
        return new Node().contracts(
                new Node()
                        .properties(
                                "source",
                                sourceNode(
                                        true,
                                        true,
                                        "target",
                                        true))
                        .properties(
                                "target",
                                target));
    }

    private static SubscriptionDelta validateCatalogChange(
            Blue blue,
            Node before,
            Node after) {
        DocumentProcessor processor =
                blue.getDocumentProcessor();
        return processor.subscriptionSurfaceValidator()
                .validate(
                        SubscriptionSurfaceValidationContext
                                .builder(
                                        before,
                                        after,
                                        Collections.singleton(
                                                "/contracts/target"),
                                        GasSchedule.contracts10())
                                .snapshots(
                                        processor.snapshotManager()
                                                .fromDocumentTransient(
                                                        before),
                                        processor.snapshotManager()
                                                .fromDocumentTransient(
                                                        after))
                                .build());
    }

    private static SubscriptionDelta.Entry deltaEntry(
            List<SubscriptionDelta.Entry> entries,
            String key) {
        for (SubscriptionDelta.Entry entry : entries) {
            if (key.equals(entry.channelKey())) {
                return entry;
            }
        }
        return null;
    }

    private static ExternalChannelFunctionEvaluation evaluate(
            DocumentProcessor processor,
            ContractBundle bundle) {
        return ExternalChannelFunctionEvaluation.evaluate(
                processor.registry(),
                processor.contractConverter(),
                ExternalChannelFunctionEvaluation
                        .verifiedMatcherSessions(null),
                bundle,
                bundle.effectiveContractSnapshot("source"),
                EVENT);
    }

    private static ContractBundle bundle(
            boolean declareCatalog,
            boolean inspectCatalog,
            String lookupKey,
            boolean routeToLookup) {
        Node sourceNode = sourceNode(
                declareCatalog,
                inspectCatalog,
                lookupKey,
                routeToLookup);
        Node targetBody =
                new Node().value("must-not-enter-header");
        Node targetNode = new Node()
                .type(reference(TARGET_TYPE_BLUE_ID))
                .properties(
                        "label",
                        new Node().value("target-label"))
                .properties("program", targetBody);
        Node managedEvent =
                new Node().properties(
                        "kind",
                        new Node().value("managed-event"));
        Node managedNode = new Node()
                .type(reference(
                        RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL))
                .properties("event", managedEvent);

        FrozenNode frozenSource =
                FrozenNode.fromResolvedNode(sourceNode);
        FrozenNode frozenTarget =
                FrozenNode.fromResolvedNode(targetNode);
        FrozenNode frozenManaged =
                FrozenNode.fromResolvedNode(managedNode);

        EffectiveContractSnapshot sourceSnapshot =
                snapshotBuilder(
                        "source",
                        SOURCE_TYPE_BLUE_ID,
                        "external-channel",
                        5,
                        frozenSource.blueId())
                        .headerField(
                                "subscriptionKey",
                                freezeProperty(
                                        sourceNode,
                                        "subscriptionKey"))
                        .headerField(
                                "declareCatalog",
                                freezeProperty(
                                        sourceNode,
                                        "declareCatalog"))
                        .headerField(
                                "inspectCatalog",
                                freezeProperty(
                                        sourceNode,
                                        "inspectCatalog"))
                        .headerField(
                                "lookupKey",
                                freezeProperty(
                                        sourceNode,
                                        "lookupKey"))
                        .headerField(
                                "routeToLookup",
                                freezeProperty(
                                        sourceNode,
                                        "routeToLookup"))
                        .build();
        EffectiveContractSnapshot targetSnapshot =
                snapshotBuilder(
                        "target",
                        TARGET_TYPE_BLUE_ID,
                        "external-channel",
                        2,
                        frozenTarget.blueId())
                        .headerField(
                                "label",
                                freezeProperty(
                                        targetNode,
                                        "label"))
                        .executableBody(
                                "program",
                                BlueIdCalculator.calculateBlueId(
                                        targetBody))
                        .deterministicDependency(
                                TARGET_DEPENDENCY_BLUE_ID)
                        .build();
        EffectiveContractSnapshot managedSnapshot =
                snapshotBuilder(
                        "managed",
                        RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL,
                        "processor-channel",
                        2,
                        frozenManaged.blueId())
                        .headerField(
                                "event",
                                freezeProperty(
                                        managedNode,
                                        "event"))
                        .build();
        EffectiveContractSnapshot handlerSnapshot =
                EffectiveContractSnapshot.builder(
                                "/",
                                "handler")
                        .sourceContribution(
                                BlueIdCalculator.calculateBlueId(
                                        new Node().value(
                                                "handler-source")))
                        .effectiveTypeBlueId(
                                BlueIdCalculator.calculateBlueId(
                                        new Node().name(
                                                "Non-Channel Handler")))
                        .role("handler")
                        .order(1)
                        .build();

        return ContractBundle.builder()
                .addChannel(
                        "source",
                        new CatalogSourceChannel(),
                        frozenSource)
                .addChannel(
                        "target",
                        new CatalogTargetChannel(),
                        frozenTarget)
                .addChannel(
                        "managed",
                        new TriggeredEventChannel(),
                        frozenManaged)
                .addEffectiveContractSnapshot(sourceSnapshot)
                .addEffectiveContractSnapshot(handlerSnapshot)
                .addEffectiveContractSnapshot(targetSnapshot)
                .addEffectiveContractSnapshot(managedSnapshot)
                .build();
    }

    private static EffectiveContractSnapshot.Builder snapshotBuilder(
            String key,
            String effectiveTypeBlueId,
            String role,
            int order,
            String contributionBlueId) {
        return EffectiveContractSnapshot.builder("/", key)
                .sourceContribution(contributionBlueId)
                .effectiveTypeBlueId(effectiveTypeBlueId)
                .role(role)
                .order(order);
    }

    private static FrozenNode freezeProperty(
            Node owner,
            String field) {
        return FrozenNode.fromResolvedNode(
                owner.getProperties().get(field));
    }

    private static Node sourceNode(
            boolean declareCatalog,
            boolean inspectCatalog,
            String lookupKey,
            boolean routeToLookup) {
        return new Node()
                .type(reference(SOURCE_TYPE_BLUE_ID))
                .properties(
                        "subscriptionKey",
                        new Node().value("catalog-topic"))
                .properties(
                        "declareCatalog",
                        new Node().value(declareCatalog))
                .properties(
                        "inspectCatalog",
                        new Node().value(inspectCatalog))
                .properties(
                        "lookupKey",
                        new Node().value(lookupKey))
                .properties(
                        "routeToLookup",
                        new Node().value(routeToLookup));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static List<String> channelKeys(
            List<ExternalChannelDependencySnapshot.ChannelEntry>
                    entries) {
        java.util.ArrayList<String> keys =
                new java.util.ArrayList<>();
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : entries) {
            keys.add(entry.channelKey());
        }
        return keys;
    }

    private static ExternalChannelDependencySnapshot.ChannelEntry
    channelEntry(
            ExternalChannelDependencySnapshot snapshot,
            String key) {
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : snapshot.channelEntries()) {
            if (key.equals(entry.channelKey())) {
                return entry;
            }
        }
        return null;
    }

    private static ExternalChannelDependencySnapshot channelDemand(
            List<ExternalChannelDependencySnapshot.ChannelEntry>
                    entries,
            boolean wholeCatalog) {
        return new ExternalChannelDependencySnapshot(
                Collections.<String>emptyList(),
                Collections
                        .<ExternalChannelDependencySnapshot.Entry>
                        emptyList(),
                Collections
                        .<ExternalChannelDependencySnapshot.TypeFamily>
                        emptyList(),
                false,
                entries,
                wholeCatalog,
                wholeCatalog
                        ? channelKeys(entries)
                        : Collections.<String>emptyList());
    }

    public static final class CatalogSourceChannel
            extends ChannelContract {
        private String subscriptionKey;
        private Boolean declareCatalog;
        private Boolean inspectCatalog;
        private String lookupKey;
        private Boolean routeToLookup;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public Boolean getDeclareCatalog() {
            return declareCatalog;
        }

        public void setDeclareCatalog(
                Boolean declareCatalog) {
            this.declareCatalog = declareCatalog;
        }

        public Boolean getInspectCatalog() {
            return inspectCatalog;
        }

        public void setInspectCatalog(
                Boolean inspectCatalog) {
            this.inspectCatalog = inspectCatalog;
        }

        public String getLookupKey() {
            return lookupKey;
        }

        public void setLookupKey(String lookupKey) {
            this.lookupKey = lookupKey;
        }

        public Boolean getRouteToLookup() {
            return routeToLookup;
        }

        public void setRouteToLookup(
                Boolean routeToLookup) {
            this.routeToLookup = routeToLookup;
        }
    }

    public static final class CatalogTargetChannel
            extends ChannelContract {
        private String label;
        private Node program;

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Node getProgram() {
            return program;
        }

        public void setProgram(Node program) {
            this.program = program;
        }
    }

    private static final class SourceProcessor
            implements ChannelProcessor<CatalogSourceChannel> {
        private final ExternalChannelSubscriptionFunctions<
                CatalogSourceChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        CatalogSourceChannel>() {
                    @Override
                    public List<String> channelKeys(
                            CatalogSourceChannel contract,
                            ExternalChannelFunctionContext context) {
                        if (Boolean.TRUE.equals(
                                contract.getDeclareCatalog())) {
                            context.dependOnSameScopeChannelCatalog();
                        }
                        return Collections.singletonList(
                                contract.getSubscriptionKey());
                    }

                    @Override
                    public boolean accepts(
                            CatalogSourceChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        if (!contract.getSubscriptionKey()
                                .equals(exactEvent.get(
                                        "/subscriptionKey"))) {
                            return false;
                        }
                        if (!Boolean.TRUE.equals(
                                contract.getInspectCatalog())) {
                            return true;
                        }
                        Optional<ChannelMemberSnapshot> selected =
                                context.channel(
                                        contract.getLookupKey());
                        return selected.isPresent();
                    }

                    @Override
                    public String handlerChannelKey(
                            CatalogSourceChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return Boolean.TRUE.equals(
                                contract.getRouteToLookup())
                                ? contract.getLookupKey()
                                : context.channelKey();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            CatalogSourceChannel contract) {
                        return "catalog-source-v1";
                    }
                };

        @Override
        public Class<CatalogSourceChannel> contractType() {
            return CatalogSourceChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                CatalogSourceChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class TargetProcessor
            implements ChannelProcessor<CatalogTargetChannel> {
        private int headerEvaluations;
        private final ExternalChannelSubscriptionFunctions<
                CatalogTargetChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        CatalogTargetChannel>() {
                    @Override
                    public List<String> channelKeys(
                            CatalogTargetChannel contract) {
                        headerEvaluations++;
                        return Collections.singletonList(
                                "target-topic");
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            CatalogTargetChannel contract) {
                        headerEvaluations++;
                        return "target-v1";
                    }
                };

        @Override
        public Class<CatalogTargetChannel> contractType() {
            return CatalogTargetChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                CatalogTargetChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    public static final class NonChannelHandler
            extends HandlerContract {
        private Node program;

        public Node getProgram() {
            return program;
        }

        public void setProgram(Node program) {
            this.program = program;
        }
    }

    private static final class NonChannelProcessor
            implements HandlerProcessor<NonChannelHandler> {

        @Override
        public Class<NonChannelHandler> contractType() {
            return NonChannelHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList(
                    "program");
        }

        @Override
        public void execute(
                NonChannelHandler contract,
                ProcessorExecutionContext context) {
            // Subscription-surface validation never executes handlers.
        }
    }
}
