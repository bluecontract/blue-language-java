package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorResolvedSnapshotParityTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Snapshot Parity External Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.asList(1, "snapshot-parity"));

    @Test
    void shouldVerifySnapshotAndNodeTraceEntriesAreEquivalentForSuccessAndRuntimeFailures() {
        // given
        for (FailureMode mode : FailureMode.values()) {
            Node root = root();
            Node event = event();
            ResolvedSnapshot snapshot = snapshot(root);
            DocumentProcessor processor = processor(
                    plan(root, event), mode, null);

            // when
            ProcessingDebugResult nodeResult =
                    processor.processDocumentWithTrace(
                            root.clone(), event.clone());
            ProcessingDebugResult snapshotResult =
                    processor.processDocumentWithTrace(
                            snapshot, event.clone());

            // then
            assertEquivalent(
                    nodeResult,
                    snapshotResult,
                    "mode=" + mode);
            assertEquals(
                    mode.expectedStatus,
                    snapshotResult.processResult().status(),
                    "mode=" + mode);
            assertEquals(
                    mode.expectedCategory,
                    diagnosticCategory(snapshotResult.processResult()),
                    "mode=" + mode);
            assertNotNull(
                    snapshotResult.resultingSnapshot(),
                    "mode=" + mode);
            if (!mode.expectedStatus.commits()) {
                assertSame(
                        snapshot,
                        snapshotResult.resultingSnapshot(),
                        "a noncommitting snapshot run must retain its exact input snapshot");
                assertEquals(
                        DirectBlueIdCalculator.calculateBlueId(root),
                        DirectBlueIdCalculator.calculateBlueId(
                                snapshotResult.processResult().document()),
                        "a noncommitting run must return the exact canonical input");
                assertTrue(
                        snapshotResult.processResult().events().isEmpty(),
                        "a noncommitting run must discard Root events");
            }
        }
    }

    @Test
    void shouldVerifyGasLimitFailureHasNodeAndSnapshotParityAndRetainsInputSnapshot() {
        // given
        Node root = root();
        Node event = event();
        ResolvedSnapshot snapshot = snapshot(root);
        DocumentProcessor processor = processor(
                plan(root, event), FailureMode.SUCCESS, 0L);

        // when
        ProcessingDebugResult nodeResult =
                processor.processDocumentWithTrace(
                        root.clone(), event.clone());
        ProcessingDebugResult snapshotResult =
                processor.processDocumentWithTrace(
                        snapshot, event.clone());

        // then
        assertEquivalent(nodeResult, snapshotResult, "gas limit");
        assertEquals(
                ProcessorStatus.GAS_LIMIT_EXCEEDED,
                snapshotResult.processResult().status());
        assertEquals(
                ProcessorErrorCategory.GasLimitExceeded,
                diagnosticCategory(snapshotResult.processResult()));
        assertEquals(0L, snapshotResult.processResult().totalGas());
        assertSame(snapshot, snapshotResult.resultingSnapshot());
        assertTrue(snapshotResult.trace().gas().isEmpty());
        assertTrue(snapshotResult.trace().records().isEmpty());
    }

    @Test
    void shouldVerifyInvalidExplicitEvidenceUsesCanonicalInputForBothSnapshotApis() {
        // given
        Node root = root();
        Node event = event();
        ResolvedSnapshot snapshot = snapshot(root);
        DocumentProcessor processor = processor(
                plan(root, event), FailureMode.SUCCESS, null);
        VerifiedExecutionEvidence forged =
                VerifiedExecutionEvidence.builder(
                                DirectBlueIdCalculator.calculateBlueId(
                                        new Node().properties(
                                                "different",
                                                new Node().value(true))),
                                DirectBlueIdCalculator.calculateBlueId(event))
                        .revisions(0L, 0L)
                        .runtimeRegistryIdentity(
                                RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY)
                        .eventOrderKey(EVENT_ORDER)
                        .build();

        // when
        ProcessingDebugResult nodeResult =
                processor.processDocumentWithTrace(
                        root.clone(), event.clone(), forged);
        ProcessingDebugResult snapshotResult =
                processor.processDocumentWithTrace(
                        snapshot, event.clone(), forged);
        DocumentProcessingResult snapshotWithoutTrace =
                processor.processDocument(
                        snapshot, event.clone(), forged);

        // then
        assertEquivalent(nodeResult, snapshotResult, "invalid evidence");
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                snapshotResult.processResult().status());
        assertEquals(
                ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                diagnosticCategory(snapshotResult.processResult()));
        assertSame(snapshot, snapshotResult.resultingSnapshot());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(root),
                DirectBlueIdCalculator.calculateBlueId(
                        snapshotWithoutTrace.document()));
        assertTrue(snapshotResult.trace().gas().isEmpty());
        assertTrue(snapshotResult.trace().records().isEmpty());
    }

    @Test
    void shouldVerifyPreExecutionValidationFailureReturnsCanonicalNotResolvedInput() {
        // given
        Node canonical = root();
        Node invalidResolved = canonical.clone()
                .blue(new Node().value("forbidden"));
        FrozenNode frozenCanonical = FrozenNode.fromNode(canonical);
        ResolvedSnapshot snapshot = new ResolvedSnapshot(
                frozenCanonical,
                FrozenNode.fromResolvedNode(invalidResolved),
                frozenCanonical.blueId());
        Node event = event();
        DocumentProcessor processor = processor(
                plan(canonical, event), FailureMode.SUCCESS, null);

        // when
        ProcessingDebugResult result =
                processor.processDocumentWithTrace(snapshot, event);

        // then
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.processResult().status());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(canonical),
                DirectBlueIdCalculator.calculateBlueId(
                        result.processResult().document()));
        assertSame(snapshot, result.resultingSnapshot());
        assertEquals(0L, result.processResult().totalGas());
        assertTrue(result.processResult().events().isEmpty());
        assertTrue(result.trace().gas().isEmpty());
        assertTrue(result.trace().records().isEmpty());
    }

    @Test
    void shouldMapInitializationSurfaceFailureEquallyForNodeAndSnapshot() {
        // given
        Node root = new Node()
                .properties("child", new Node().value("not-an-object"))
                .contracts(new Node().properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        new Node()
                                .type(new Node().blueId(
                                        RuntimeBlueIds.PROCESS_EMBEDDED))
                                .properties(
                                        ProcessorContractConstants.KEY_PATHS,
                                        new Node().items(
                                                Collections.singletonList(
                                                        new Node().value(
                                                                "/child"))))));
        ResolvedSnapshot snapshot = snapshot(root);
        DocumentProcessor processor = DocumentProcessor.builder()
                .snapshotStore(IdentitySnapshotManager.INSTANCE)
                .build();

        // when
        DocumentProcessingResult nodeResult;
        DocumentProcessingResult snapshotResult;
        try {
            nodeResult = processor.initializeDocument(root);
            snapshotResult = processor.initializeDocument(snapshot);
        } finally {
            processor.close();
        }

        // then
        assertEquals(ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                nodeResult.status());
        assertEquals(ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                snapshotResult.status());
        assertEquals(ProcessorErrorCategory.EmbeddedScopeNotObject,
                diagnosticCategory(nodeResult));
        assertEquals(ProcessorErrorCategory.EmbeddedScopeNotObject,
                diagnosticCategory(snapshotResult));
        assertEquals(nodeResult.totalGas(), snapshotResult.totalGas());
        assertTrue(nodeResult.totalGas() > 0L,
                "initialization must retain gas admitted before rejection");
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(root),
                DirectBlueIdCalculator.calculateBlueId(nodeResult.document()));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(root),
                DirectBlueIdCalculator.calculateBlueId(
                        snapshotResult.document()));
        assertTrue(nodeResult.events().isEmpty());
        assertTrue(snapshotResult.events().isEmpty());
    }

    private static void assertEquivalent(
            ProcessingDebugResult node,
            ProcessingDebugResult snapshot,
            String context) {
        DocumentProcessingResult left = node.processResult();
        DocumentProcessingResult right = snapshot.processResult();
        assertEquals(left.status(), right.status(), context);
        assertEquals(diagnosticCategory(left), diagnosticCategory(right), context);
        assertEquals(diagnosticMessage(left), diagnosticMessage(right), context);
        assertEquals(
                left.diagnostic() != null
                        ? left.diagnostic().details()
                        : Collections.emptyMap(),
                right.diagnostic() != null
                        ? right.diagnostic().details()
                        : Collections.emptyMap(),
                context);
        assertEquals(left.totalGas(), right.totalGas(), context);
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(left.document()),
                DirectBlueIdCalculator.calculateBlueId(right.document()),
                context);
        assertEquals(
                nodeIdentities(left.events()),
                nodeIdentities(right.events()),
                context);
        assertEquals(
                gasProjection(node.trace()),
                gasProjection(snapshot.trace()),
                context);
        assertEquals(
                recordProjection(node.trace()),
                recordProjection(snapshot.trace()),
                context);
        assertEquals(
                node.trace().semanticDemands(),
                snapshot.trace().semanticDemands(),
                context);
        assertEquals(
                contractSnapshotProjection(node.trace()),
                contractSnapshotProjection(snapshot.trace()),
                context);
    }

    private static List<String> nodeIdentities(List<Node> nodes) {
        List<String> identities = new ArrayList<>();
        for (Node node : nodes) {
            identities.add(DirectBlueIdCalculator.calculateBlueId(node));
        }
        return identities;
    }

    private static List<String> gasProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection = new ArrayList<>();
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
        return projection;
    }

    private static List<String> recordProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection = new ArrayList<>();
        for (ProcessingTraceRecord record : trace.records()) {
            Node node = record.node();
            projection.add(
                    record.sequence()
                            + "|" + record.kind()
                            + "|" + record.scopePath()
                            + "|" + record.contractKey()
                            + "|" + record.logicalPath()
                            + "|" + record.details()
                            + "|" + (node != null
                            ? ProcessorEngine.canonicalSignature(node)
                            : null));
        }
        return projection;
    }

    private static List<String> contractSnapshotProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection = new ArrayList<>();
        for (Map.Entry<String, EffectiveContractSnapshot> entry
                : trace.contractSnapshots().entrySet()) {
            EffectiveContractSnapshot snapshot = entry.getValue();
            projection.add(
                    entry.getKey()
                            + "|" + snapshot.scopePath()
                            + "|" + snapshot.key()
                            + "|" + snapshot.sourceContributionNodeBlueIds()
                            + "|" + snapshot.effectiveTypeBlueId()
                            + "|" + snapshot.role()
                            + "|" + snapshot.order()
                            + "|" + snapshot.dispatchFields()
                            + "|" + snapshot.executableBodyNodeBlueIds()
                            + "|" + snapshot.deterministicDependencyNodeBlueIds());
        }
        return projection;
    }

    private static DocumentProcessor processor(
            ExternalDeliveryPlan plan,
            FailureMode failureMode,
            Long gasLimit) {
        DocumentProcessor.Builder builder =
                DocumentProcessor.builder()
                        .registerContractProcessor(
                                CHANNEL_TYPE_BLUE_ID,
                                CHANNEL_TYPE,
                                new ParityChannelProcessor())
                        .snapshotStore(
                                IdentitySnapshotManager.INSTANCE)
                        .deliveryPlanDeriver(
                                (root, event) -> plan)
                        .evidenceVerifier(
                                (root, event, evidence) -> {
                                    // Binding is verified independently by the facade.
                                })
                        .subscriptionSurfaceValidator(
                                failureMode.validator());
        if (gasLimit != null) {
            builder.gasLimit(gasLimit);
        }
        return builder.build();
    }

    private static ExternalDeliveryPlan plan(
            Node root,
            Node event) {
        Node channel = root.getContracts()
                .getProperties().get("incoming");
        String contribution =
                DirectBlueIdCalculator.calculateBlueId(channel);
        String checkpointDomain = CheckpointDomain.derive(
                CHANNEL_TYPE_BLUE_ID,
                Collections.singletonList(contribution),
                "snapshot-parity-domain");
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder(
                                "/", "incoming")
                        .order(0)
                        .sourceContribution(contribution)
                        .effectiveTypeBlueId(
                                CHANNEL_TYPE_BLUE_ID)
                        .subscriptionKey("topic")
                        .checkpointDomainBlueId(
                                checkpointDomain)
                        .checkpointSubjectBlueId(
                                DirectBlueIdCalculator.calculateBlueId(
                                        event))
                        .build();
        return ExternalDeliveryPlan.builder()
                .revisions(0L, 0L)
                .eventOrderKey(EVENT_ORDER)
                .delivery(delivery)
                .activeSubscriptionInterval(
                        new SubscriptionDelta.Entry(
                                "/",
                                "incoming",
                                CHANNEL_TYPE_BLUE_ID,
                                Collections.singletonList(
                                        contribution),
                                0,
                                Collections.singletonList("topic"),
                                checkpointDomain,
                                0L,
                                null,
                                null))
                .exactRuntimeState()
                .build();
    }

    private static Node root() {
        Node channel = new Node()
                .type(new Node().blueId(
                        CHANNEL_TYPE_BLUE_ID))
                .properties("order", new Node().value(0))
                .properties(
                        "subscriptionKey",
                        new Node().value("topic"))
                .properties(
                        "checkpointDomain",
                        new Node().value(
                                "snapshot-parity-domain"))
                .properties(
                        "enabled",
                        new Node().value(true));
        return new Node()
                .properties(
                        "sentinel",
                        new Node().value("canonical-input"))
                .contracts(
                        new Node().properties(
                                "incoming", channel));
    }

    private static Node event() {
        return new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value("topic"))
                .properties(
                        "eventId",
                        new Node().value("evt-snapshot-parity"));
    }

    private static ResolvedSnapshot snapshot(Node document) {
        FrozenNode canonical =
                FrozenNode.fromNode(document);
        return new ResolvedSnapshot(
                canonical,
                FrozenNode.fromResolvedNode(document),
                canonical.blueId());
    }

    private enum FailureMode {
        SUCCESS(
                ProcessorStatus.SUCCESS,
                null),
        PORTABLE_LIMIT(
                ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                ProcessorErrorCategory.DirectNodeLimitExceeded),
        SUBSCRIPTION_SURFACE(
                ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                ProcessorErrorCategory.SubscriptionSurfaceInvalid),
        MUST_UNDERSTAND(
                ProcessorStatus.CAPABILITY_FAILURE,
                ProcessorErrorCategory.UnsupportedRuntimeType),
        RUNTIME(
                ProcessorStatus.RUNTIME_FATAL,
                ProcessorErrorCategory.RuntimeExecutionFailure);

        private final ProcessorStatus expectedStatus;
        private final ProcessorErrorCategory expectedCategory;

        FailureMode(
                ProcessorStatus expectedStatus,
                ProcessorErrorCategory expectedCategory) {
            this.expectedStatus = expectedStatus;
            this.expectedCategory = expectedCategory;
        }

        private SubscriptionSurfaceValidator validator() {
            switch (this) {
                case PORTABLE_LIMIT:
                    return context -> {
                        throw new PortableLimitExceededException(
                                "directObjectEntriesMaterializedOrRebuilt",
                                2L,
                                1L);
                    };
                case SUBSCRIPTION_SURFACE:
                    return context -> {
                        throw new SubscriptionSurfaceInvalidException(
                                "invalid test subscription surface",
                                "/",
                                "incoming");
                    };
                case MUST_UNDERSTAND:
                    return context -> {
                        throw new MustUnderstandFailureException(
                                "unsupported test runtime type",
                                ProcessorErrorCategory
                                        .UnsupportedRuntimeType);
                    };
                case RUNTIME:
                    return context -> {
                        throw new ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure,
                                "test runtime failure");
                    };
                default:
                    return context ->
                            SubscriptionDelta.empty();
            }
        }
    }

    public static final class ParityChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String checkpointDomain;
        private Boolean enabled;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(
                String checkpointDomain) {
            this.checkpointDomain = checkpointDomain;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    private static final class ParityChannelProcessor
            implements ChannelProcessor<ParityChannel> {
        private static final
        ExternalChannelSubscriptionFunctions<ParityChannel>
                SUBSCRIPTION_FUNCTIONS =
                new ExternalChannelSubscriptionFunctions<ParityChannel>() {
                    @Override
                    public java.util.List<String> channelKeys(
                            ParityChannel immutableContractSnapshot) {
                        return java.util.Collections.singletonList(
                                immutableContractSnapshot
                                        .getSubscriptionKey());
                    }

                    @Override
                    public boolean accepts(
                            ParityChannel immutableContractSnapshot,
                            Node exactEvent) {
                        return !Boolean.FALSE.equals(
                                immutableContractSnapshot.getEnabled())
                                && preselects(
                                immutableContractSnapshot, exactEvent);
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            ParityChannel immutableContractSnapshot) {
                        return immutableContractSnapshot
                                .getCheckpointDomain();
                    }
                };

        @Override
        public Class<ParityChannel> contractType() {
            return ParityChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<ParityChannel>
        externalSubscriptionFunctions() {
            return SUBSCRIPTION_FUNCTIONS;
        }

        @Override
        public boolean matches(
                ParityChannel contract,
                ChannelEvaluationContext context) {
            Node subscription = context.event() != null
                    && context.event().getProperties() != null
                    ? context.event().getProperties()
                    .get("subscriptionKey")
                    : null;
            return !Boolean.FALSE.equals(
                    contract.getEnabled())
                    && subscription != null
                    && contract.getSubscriptionKey().equals(
                    subscription.getValue());
        }
    }

    private enum IdentitySnapshotManager
            implements ProcessingSnapshotManager {
        INSTANCE;

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return snapshot(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            CanonicalPatchResult patched =
                    new CanonicalOverlayPatchEngine(
                            snapshot.frozenCanonicalRoot()).apply(patch);
            return new ResolvedSnapshot(
                    patched.root(),
                    FrozenNode.fromResolvedNode(
                            patched.root().toNode()),
                    patched.blueId());
        }
    }
}
