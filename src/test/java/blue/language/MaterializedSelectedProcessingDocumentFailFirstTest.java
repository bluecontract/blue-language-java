package blue.language;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

class MaterializedSelectedProcessingDocumentFailFirstTest {

    @Test
    void shouldResolveInheritedFieldsFromCompactSourceWithoutMutatingSourceShape() {
        // given
        AuditFixture fixture = new AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        Node source = fixture.compact();
        String sourceJson = blue.nodeToJson(source);

        // when
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(source);

        // then
        assertEquals(sourceJson, blue.nodeToJson(source));
        assertFalse(hasContract(source, "audit"));
        assertNull(source.getProperties().get("materializedField"));
        assertTrue(hasContract(snapshot.resolvedRoot(), "audit"));
        assertEquals("materialized",
                snapshot.resolvedRoot().getAsText("/materializedField"));
        assertEquals(snapshot.blueId(), blue.calculateSourceDocumentBlueId(source));
    }

    @Test
    void shouldGiveRedundantAuthoredMaterializationNoDistinctSemanticIdentity() {
        // given
        AuditFixture fixture = new AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());

        ResolvedSnapshot compact = blue.resolveToSnapshot(fixture.compact());
        // when
        ResolvedSnapshot materialized =
                blue.resolveToSnapshot(fixture.materializedSource());

        // then
        assertEquals(compact.blueId(), materialized.blueId());
        assertEquals(blue.nodeToJson(compact.canonicalRoot()),
                blue.nodeToJson(materialized.canonicalRoot()));
        assertEquals(blue.nodeToJson(compact.resolvedRoot()),
                blue.nodeToJson(materialized.resolvedRoot()));
    }

    @Test
    void shouldCloneJsonAndYamlTransportsResolveToTheSameMeaning() {
        // given
        AuditFixture fixture = new AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        Node source = fixture.compact();
        List<Node> forms = Arrays.asList(
                source,
                source.clone(),
                blue.jsonToNode(blue.nodeToJson(source)),
                blue.yamlToNode(blue.nodeToYaml(source)));
        ResolvedSnapshot expected = blue.resolveToSnapshot(source);

        // when
        for (Node form : forms) {
            ResolvedSnapshot actual = blue.resolveToSnapshot(form);
            // then
            assertEquals(expected.blueId(), actual.blueId());
            assertEquals(blue.nodeToJson(expected.resolvedRoot()),
                    blue.nodeToJson(actual.resolvedRoot()));
        }
    }

    @Test
    void shouldNotExposeMutableSelectionStateThroughResolvedSnapshotAccessors() {
        // given
        AuditFixture fixture = new AuditFixture();
        Blue blue = fixture.newBlue(new AtomicInteger());
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(fixture.compact());
        String identity = snapshot.blueId();

        Node returned = snapshot.resolvedRoot();
        // when
        returned.properties("materializedField", text("changed"));

        // then
        assertEquals(identity, snapshot.blueId());
        assertEquals("materialized",
                snapshot.resolvedRoot().getAsText("/materializedField"));
        assertTrue(hasContract(snapshot.resolvedRoot(), "audit"));
    }

    private static boolean hasContract(Node document, String key) {
        return document != null
                && document.getContracts() != null
                && document.getContracts().getProperties() != null
                && document.getContracts().getProperties().containsKey(key);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    static final class AuditFixture {
        final BasicNodeProvider provider = new BasicNodeProvider();
        final Node channelType;
        final Node auditHandlerType;
        final String channelBlueId;
        final String auditHandlerBlueId;
        final String rootTypeBlueId;

        AuditFixture() {
            channelType = new Node().name("Synthetic Audit Channel")
                    .type(reference(RuntimeBlueIds.CHANNEL));
            provider.addSingleNodes(channelType);
            channelBlueId = provider.getBlueIdByName(channelType.getName());

            auditHandlerType = new Node().name("Synthetic Audit Handler")
                    .type(reference(RuntimeBlueIds.HANDLER))
                    .properties("channel", text("incoming"));
            provider.addSingleNodes(auditHandlerType);
            auditHandlerBlueId = provider.getBlueIdByName(auditHandlerType.getName());

            Node rootType = new Node().name("Synthetic Audited Document")
                    .properties("materializedField", text("materialized"))
                    .contracts(new Node().properties("audit",
                            new Node().type(reference(auditHandlerBlueId))
                                    .properties("channel", text("incoming"))));
            provider.addSingleNodes(rootType);
            rootTypeBlueId = provider.getBlueIdByName(rootType.getName());
        }

        Blue newBlue(AtomicInteger executions) {
            return newBlue(executions, bool(true));
        }

        Blue newBlueWithoutHandlerPatch(AtomicInteger executions) {
            return newBlue(executions, null);
        }

        Blue newBlueWithHandlerPatch(AtomicInteger executions, Node patchValue) {
            return newBlue(executions, patchValue);
        }

        private Blue newBlue(AtomicInteger executions, Node patchValue) {
            Blue blue = new Blue(provider);
            blue.registerExternalContractType(channelBlueId, channelType, new AuditChannelProcessor());
            blue.registerExternalContractType(auditHandlerBlueId,
                    auditHandlerType,
                    new AuditHandlerProcessor(executions, patchValue));
            installExactAuditFeeder(blue);
            return blue;
        }

        private void installExactAuditFeeder(Blue blue) {
            DocumentProcessor current = blue.getDocumentProcessor();
            ProcessingSnapshotManager snapshotManager =
                    new ProcessingSnapshotManager() {
                        @Override
                        public ResolvedSnapshot fromDocument(Node document) {
                            return blue.resolveToSnapshot(document);
                        }

                        @Override
                        public ResolvedSnapshot fromDocumentPreservingPaths(
                                Node document,
                                java.util.Collection<String> preservedPaths) {
                            return blue.resolveToSnapshotPreservingPaths(
                                    document, preservedPaths);
                        }

                        @Override
                        public ResolvedSnapshot applyPatch(
                                ResolvedSnapshot snapshot,
                                JsonPatch patch) {
                            return blue.applyCanonicalPatch(snapshot, patch);
                        }
                    };
            DocumentProcessor exact = DocumentProcessor.builder()
                    .withRegistry(current.getContractRegistry())
                    .withContractTypeResolver(
                            current.getContractTypeResolver())
                    .withConformanceEngine(new ConformanceEngine(
                            blue.getNodeProvider(),
                            blue.getMergingProcessor()))
                    .withSnapshotManager(snapshotManager)
                    .withMatchingService(
                            new ContractMatchingService(blue))
                    .withProcessingMetricsSink(
                            current.processingMetricsSink())
                    .withExternalDeliveryPlanDeriver(
                            this::deriveExactAuditPlan)
                    .build();
            blue.documentProcessor(exact);
        }

        private ExternalDeliveryPlan deriveExactAuditPlan(
                Node root,
                Node event) {
            String eventBlueId =
                    BlueIdCalculator.calculateBlueId(event);
            ExternalOrderKey eventOrder =
                    ExternalOrderKey.of(
                            Collections.singletonList(eventBlueId));
            ExternalDeliveryPlan.Builder plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(1L, 1L)
                            .eventOrderKey(eventOrder)
                            .activeSubscriptionIntervals(
                                    Collections
                                            .<SubscriptionDelta.Entry>
                                                    emptyList())
                            .exactRuntimeState();
            Node contracts = root.getContracts();
            if (contracts == null
                    || contracts.getProperties() == null
                    || contracts.getProperties().containsKey(
                            "terminated")) {
                return plan.build();
            }
            Node channel = contracts.getProperties().get(
                    "incoming");
            if (channel == null) {
                return plan.build();
            }

            List<String> contributions =
                    Collections.singletonList(
                            BlueIdCalculator.calculateBlueId(
                                    channel));
            List<String> keys =
                    Collections.singletonList("audit");
            String checkpointDomain =
                    CheckpointDomain.derive(
                            channelBlueId,
                            contributions,
                            AuditChannelProcessor
                                    .CHECKPOINT_DISCRIMINATOR);
            SubscriptionDelta.Entry active =
                    new SubscriptionDelta.Entry(
                            "/",
                            "incoming",
                            channelBlueId,
                            contributions,
                            0,
                            keys,
                            checkpointDomain,
                            1L,
                            null,
                            null);
            plan.activeSubscriptionInterval(active);

            if (!"audit".equals(
                    event.getAsText("/kind"))) {
                return plan.build();
            }
            ExternalDeliverySnapshot.Builder delivery =
                    ExternalDeliverySnapshot.builder(
                                    "/", "incoming")
                            .order(0)
                            .effectiveTypeBlueId(
                                    channelBlueId)
                            .subscriptionKey("audit")
                            .checkpointDomainBlueId(
                                    checkpointDomain)
                            .checkpointSubjectBlueId(
                                    eventBlueId);
            for (String contribution : contributions) {
                delivery.sourceContribution(contribution);
            }
            return plan.delivery(delivery.build()).build();
        }

        Node compact() {
            return new Node()
                    .type(reference(rootTypeBlueId))
                    .properties("auditRan", bool(false))
                    .properties("selectedOnly", text("compact"))
                    .contracts(new Node().properties("incoming",
                            new Node().type(reference(channelBlueId))));
        }

        Node materializedSource() {
            Node selected = compact();
            selected.properties("materializedField", text("materialized"));
            selected.getContracts().properties("audit",
                    new Node().type(reference(auditHandlerBlueId))
                            .properties("channel", text("incoming")));
            return selected;
        }

        Node auditEvent() {
            return auditEvent("default");
        }

        Node auditEvent(String checkpointIdentity) {
            return new Node()
                    .properties("kind", new Node().value("audit"))
                    .properties("checkpointIdentity", new Node().value(checkpointIdentity));
        }
    }

    public static final class AuditChannel extends ChannelContract {
    }

    private static final class AuditChannelProcessor implements ChannelProcessor<AuditChannel> {
        private static final String CHECKPOINT_DISCRIMINATOR =
                "audit-kind-v1";
        private final ExternalChannelSubscriptionFunctions<
                AuditChannel> subscriptionFunctions =
                new ExternalChannelSubscriptionFunctions<
                        AuditChannel>() {
                    @Override
                    public List<String> channelKeys(
                            AuditChannel immutableContractSnapshot) {
                        return Collections.singletonList(
                                "audit");
                    }

                    @Override
                    public List<String> eventKeys(
                            Node exactEvent) {
                        String kind = exactEvent != null
                                ? exactEvent.getAsText("/kind")
                                : null;
                        return kind != null
                                ? Collections.singletonList(kind)
                                : Collections
                                        .<String>emptyList();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            AuditChannel immutableContractSnapshot) {
                        return CHECKPOINT_DISCRIMINATOR;
                    }
                };

        @Override
        public Class<AuditChannel> contractType() {
            return AuditChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<AuditChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
        }

        @Override
        public boolean matches(AuditChannel contract, ChannelEvaluationContext context) {
            return "audit".equals(context.event().getAsText("/kind"));
        }
    }

    public static final class AuditHandler extends HandlerContract {
    }

    private static final class AuditHandlerProcessor implements HandlerProcessor<AuditHandler> {
        private final AtomicInteger executions;
        private final Node patchValue;

        private AuditHandlerProcessor(AtomicInteger executions, Node patchValue) {
            this.executions = executions;
            this.patchValue = patchValue != null ? patchValue.clone() : null;
        }

        @Override
        public Class<AuditHandler> contractType() {
            return AuditHandler.class;
        }

        @Override
        public void execute(AuditHandler contract, ProcessorExecutionContext context) {
            executions.incrementAndGet();
            if (patchValue != null) {
                context.applyPatch(JsonPatch.replace("/auditRan", patchValue.clone()));
            }
        }
    }

    private static Node text(String value) {
        return new Node().type(reference(TEXT_TYPE_BLUE_ID)).value(value);
    }

    private static Node bool(boolean value) {
        return new Node().type(reference(BOOLEAN_TYPE_BLUE_ID)).value(value);
    }
}
