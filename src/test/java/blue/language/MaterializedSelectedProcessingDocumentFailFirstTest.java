package blue.language;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

class MaterializedSelectedProcessingDocumentFailFirstTest {

    @Test
    void compactSelectedDocumentDoesNotExecuteTypeDerivedAudit() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);

        DocumentProcessingResult result = blue.processDocument(fixture.compact(), fixture.auditEvent());

        assertEquals(0, executions.get(), "a type-derived-only contract must not execute");
        assertFalse(hasContract(result.document(), "audit"));
        assertEquals("compact", result.document().getAsText("/selectedOnly"));
    }

    @Test
    void materializedSelectedContractExecutesExactlyOnce() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        Node selected = fixture.materializedSource();

        DocumentProcessingResult result = blue.processDocument(selected, fixture.auditEvent());

        assertEquals(1, executions.get());
        assertEquals(Boolean.TRUE, result.document().get("/auditRan"));
        assertTrue(hasContract(result.document(), "audit"));
        assertEquals("materialized", result.document().getAsText("/materializedField"));
    }

    @Test
    void selectedTypeOnlyAuditUsesInheritedEffectiveChannel() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        Node selected = fixture.materializedSource();
        selected.getContracts().properties("audit", new Node().type(reference(fixture.auditHandlerBlueId)));

        DocumentProcessingResult result = blue.processDocument(selected, fixture.auditEvent());

        assertEquals(1, executions.get(), "selected contract recognition must use its resolved effective content");
        assertEquals(Boolean.TRUE, result.document().get("/auditRan"));
        assertTrue(hasContract(result.document(), "audit"));
    }

    @Test
    void selectedTypeOnlyWorkflowUsesInheritedEffectiveStepsWithoutReversingSubtype() {
        SyntheticWorkflowProcessingFixture fixture = new SyntheticWorkflowProcessingFixture();
        Node selected = fixture.source.clone()
                .properties("materializedField", new Node().value("materialized"))
                .contracts(new Node()
                        .properties("lifecycle", new Node()
                                .type(reference(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)))
                        .properties("workflow", new Node()
                                .type(reference(fixture.workflowBlueId))));

        assertTrue(selected.getAsNode("/contracts/workflow/type").isReferenceOnly());
        assertTrue(selected.getAsNode("/contracts/workflow").getProperties() == null
                || selected.getAsNode("/contracts/workflow").getProperties().isEmpty());

        DocumentProcessingResult result = assertDoesNotThrow(() -> fixture.blue.initializeDocument(selected));

        assertFalse(result.capabilityFailure(), result.failureReason());
        assertEquals(1, fixture.handlerExecutions.get());
        assertEquals("after", result.document().getAsText("/probe"));
        assertTrue(hasContract(result.document(), "workflow"));
        assertEquals("materialized", result.document().getAsText("/materializedField"));
        Node concreteStep = result.resolvedDocument().getAsNode("/contracts/workflow/steps/0/type");
        assertEquals("Synthetic Compute Step", concreteStep.getName());
        assertTrue(fixture.blue.isNodeSubtypeOf(concreteStep, concreteStep.getType()));
    }

    @Test
    void initializationAndPatchPreserveSelectedContractsAndMaterializedFields() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        Node selected = fixture.materializedSource();

        DocumentProcessingResult initialized = blue.initializeDocument(selected);
        DocumentProcessingResult processed = blue.processDocument(initialized.document(), fixture.auditEvent());

        assertEquals(1, executions.get());
        assertSelectedMaterialization(initialized.document());
        assertSelectedMaterialization(processed.document());
        assertEquals(Boolean.TRUE, processed.document().get("/auditRan"));
    }

    @Test
    void clonedReturnedSelectedDocumentPreservesDiscoveryBehavior() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        DocumentProcessingResult initialized = blue.initializeDocument(fixture.materializedSource());

        DocumentProcessingResult processed = blue.processDocument(
                initialized.document().clone(), fixture.auditEvent());

        assertEquals(1, executions.get());
        assertSelectedMaterialization(processed.document());
    }

    @Test
    void freshBlueProcessesClonedMaterializedSelectionWithoutProducerIdentity() {
        AuditFixture fixture = new AuditFixture();
        Blue producer = fixture.newBlue(new AtomicInteger());
        Node selected = fixture.materializedSource().clone();
        AtomicInteger executions = new AtomicInteger();
        Blue consumer = fixture.newBlue(executions);

        DocumentProcessingResult result = consumer.processDocument(selected, fixture.auditEvent());

        assertEquals(1, executions.get());
        assertSelectedMaterialization(result.document());
    }

    @Test
    void compactSnapshotDoesNotSelectTypeDerivedAudit() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        ResolvedSnapshot compactSnapshot = blue.resolveToSnapshot(fixture.compact());

        DocumentProcessingResult result = blue.initializeDocument(compactSnapshot);

        assertEquals(0, executions.get());
        assertFalse(hasContract(result.document(), "audit"));
        assertNotNull(result.snapshot().resolvedNodeAt("/contracts/audit"));
    }

    @Test
    void genericSnapshotFromMaterializedInputDoesNotClaimSelectedMaterialization() {
        AuditFixture fixture = new AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(fixture.materializedSource());

        DocumentProcessingResult result = blue.initializeDocument(snapshot);

        assertEquals(0, executions.get());
        assertFalse(hasContract(result.document(), "audit"));
        assertNotNull(result.snapshot().resolvedNodeAt("/contracts/audit"));
    }

    private static void assertSelectedMaterialization(Node document) {
        assertTrue(hasContract(document, "audit"));
        assertEquals("materialized", document.getAsText("/materializedField"));
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
            return blue;
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
        @Override
        public Class<AuditChannel> contractType() {
            return AuditChannel.class;
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
