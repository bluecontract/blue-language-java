package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalDeliveryPlanTrustBoundaryTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Plan External Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final Node TRACE_HANDLER_TYPE =
            new Node().name("Trace Handler");
    private static final String TRACE_HANDLER_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(TRACE_HANDLER_TYPE);
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.asList(7, "source", 11));

    @Test
    void shouldBuildStrictVerifierForSuccessorPlanDeriver() {
        // given
        Node root = rootWithChannels(
                channel("alpha", 0, true));
        Node event = event("topic");
        ExternalDeliveryPlan exactPlan =
                plan(snapshot(
                        "/",
                        "alpha",
                        root.getContracts()
                                .getProperties()
                                .get("alpha"),
                        event));
        AtomicInteger derivations = new AtomicInteger();
        DocumentProcessor processor =
                processor(null, null, null);
        ExternalDeliveryPlanDeriver originalDeriver =
                processor.externalDeliveryPlanDeriver();

        // when
        DocumentProcessor configured = DocumentProcessor.Builder
                .from(processor)
                .withExternalDeliveryPlanDeriver(
                        (suppliedRoot, suppliedEvent) -> {
                            derivations.incrementAndGet();
                            return exactPlan;
                        })
                .build();
        DocumentProcessingResult result =
                configured.processDocument(root, event);

        // then
        assertNotSame(processor, configured);
        assertSame(
                originalDeriver,
                processor.externalDeliveryPlanDeriver());
        assertEquals(1, derivations.get());
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
    }

    @Test
    void shouldVerifyExactPlanRejectsOmissionExtraOrderRevisionAndResourceForgery() {
        // given
        Node root = rootWithChannels(
                channel("alpha", 0, true),
                channel("beta", 1, true));
        Node event = event("topic");
        ExternalDeliverySnapshot alpha =
                snapshot("/", "alpha",
                        root.getContracts().getProperties().get("alpha"),
                        event);
        ExternalDeliverySnapshot beta =
                snapshot("/", "beta",
                        root.getContracts().getProperties().get("beta"),
                        event);
        ExternalDeliveryPlan canonical = plan(alpha, beta);
        DocumentProcessor processor = processor(
                canonical, null, null);
        List<VerifiedExecutionEvidence> forgedEvidence = Arrays.asList(
                evidence(root, event, 7L,
                        new ExternalDeliverySnapshot[]{alpha},
                        null),
                evidence(root, event, 7L,
                        new ExternalDeliverySnapshot[]{
                                beta, alpha
                        }, null),
                evidence(root, event, 7L,
                        new ExternalDeliverySnapshot[]{
                                alpha, beta, beta
                        }, null),
                evidence(root, event, 8L,
                        new ExternalDeliverySnapshot[]{
                                alpha, beta
                        }, null),
                evidence(root, event, 7L,
                        new ExternalDeliverySnapshot[]{
                                withExtraContribution(alpha), beta
                        }, null),
                evidence(root, event, 7L,
                        new ExternalDeliverySnapshot[]{
                                alpha, beta
                        }, "unexpected-resource"));

        // when
        List<DocumentProcessingResult> results =
                new ArrayList<>(forgedEvidence.size());
        for (VerifiedExecutionEvidence evidence : forgedEvidence) {
            results.add(processor.processDocument(root, event, evidence));
        }

        // then
        results.forEach(
                ExternalDeliveryPlanTrustBoundaryTest::assertInvalid);
    }

    @Test
    void shouldVerifyInheritedEffectiveChannelUsesExactAncestorContributionSequence() {
        // given
        Node inheritedChannel = channel("inherited", 0, true);
        Node base = new Node()
                .name("Inherited External Surface")
                .contracts(new Node().properties(
                        "inherited", inheritedChannel));
        String baseBlueId = BlueIdCalculator.calculateBlueId(base);
        Map<String, Node> providerNodes = new LinkedHashMap<>();
        providerNodes.put(baseBlueId, base);
        providerNodes.put(CHANNEL_TYPE_BLUE_ID, CHANNEL_TYPE);
        NodeProvider provider = blueId -> {
            Node supplied = providerNodes.get(blueId);
            return supplied != null
                    ? Collections.singletonList(supplied.clone())
                    : null;
        };

        try (Blue language = new Blue(provider)) {
            Node root = new Node().type(
                    new Node().blueId(baseBlueId));
            Node event = event("topic");
            ExternalDeliverySnapshot delivery =
                    snapshotWithContributions(
                            "/",
                            "inherited",
                            inheritedChannel,
                            event,
                            BlueIdCalculator.calculateBlueId(
                                    inheritedChannel));
            ExternalDeliveryPlan plan = plan(delivery);
            DocumentProcessor processor = processor(
                    plan,
                    language,
                    language.getDocumentProcessor()
                            .snapshotManager());
            ExternalDeliverySnapshot forged =
                    snapshotWithContributions(
                            "/",
                            "inherited",
                            inheritedChannel,
                            event,
                            BlueIdCalculator.calculateBlueId(
                                    inheritedChannel),
                            "forged-descendant-contribution");
            VerifiedExecutionEvidence forgedEvidence =
                    evidence(root, event, 7L,
                            new ExternalDeliverySnapshot[]{forged},
                            null);

            // when
            DocumentProcessingResult accepted =
                    processor.processDocument(root, event);
            DocumentProcessingResult rejected =
                    processor.processDocument(
                            root, event, forgedEvidence);

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    accepted.status(),
                    diagnosticMessage(accepted));
            assertInvalid(rejected);
        }
    }

    @Test
    void shouldVerifyDefaultDeriverAcceptsDirectEmptySurface() {
        // given
        DocumentProcessor processor =
                new DocumentProcessor();

        // when
        DocumentProcessingResult directEmpty =
                processor.processDocument(
                        new Node(), event("topic"));

        // then
        assertEquals(
                ProcessorStatus.NO_MATCH,
                directEmpty.status(),
                diagnosticMessage(directEmpty));
    }

    @Test
    void shouldVerifyDefaultDeriverRejectsUnprovenExternalSurface() {
        // given
        DocumentProcessor externalProcessor =
                processor(null, null, null);

        // when
        ExecutionEvidenceUnavailableException unavailable =
                captureFailure(
                        () -> externalProcessor.processDocument(
                        rootWithChannels(
                                channel("incoming", 0, true)),
                        event("topic")));

        // then
        assertEquals(ExecutionEvidenceUnavailableException.class,
                unavailable.getClass());
        assertTrue(unavailable.getMessage().contains(
                "subscription and activation state is unavailable"));
    }

    @Test
    void shouldVerifyDefaultDeriverAcceptsProviderProvenInheritedEmptySurface() {
        // given
        Node base = new Node().name(
                "Provider-Proven Empty Surface");
        String baseBlueId =
                BlueIdCalculator.calculateBlueId(base);
        DocumentProcessingResult result;

        // when
        try (Blue language = new Blue(blueId ->
                baseBlueId.equals(blueId)
                        ? Collections.singletonList(base.clone())
                        : null)) {
            DocumentProcessor inheritedEmpty = processor(
                    null,
                    language,
                    language.getDocumentProcessor()
                            .snapshotManager());
            result =
                    inheritedEmpty.processDocument(
                            new Node().type(
                                    new Node().blueId(baseBlueId)),
                            event("topic"));
        }

        // then
        assertEquals(
                ProcessorStatus.NO_MATCH,
                result.status(),
                diagnosticMessage(result));
    }

    @Test
    void shouldVerifyRetainedActiveSurfacePreventsOmittedTruePreselection() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        Node event = event("topic");
        ExternalDeliverySnapshot active =
                snapshot("/", "incoming", incoming, event);

        // when
        DocumentProcessingResult omitted =
                processor(planWithActive(active), null, null)
                        .processDocument(root, event);

        // then
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                omitted.status());
        assertEquals(
                ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                diagnosticCategory(omitted));
        assertTrue(diagnosticMessage(omitted).contains(
                "omitted a true preselection"));
    }

    @Test
    void shouldVerifyExactBitWithoutRetainedActivationCompanionSuspends() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        Node event = event("topic");
        ExternalDeliveryPlan incomplete =
                ExternalDeliveryPlan.builder()
                        .revisions(7L, 7L)
                        .eventOrderKey(EVENT_ORDER)
                        .exactRuntimeState()
                        .build();
        DocumentProcessor processor =
                processor(incomplete, null, null);

        // when
        ExecutionEvidenceUnavailableException unavailable =
                captureFailure(
                        () -> processor.processDocument(root, event));
        ProcessAttemptResult attempt =
                processor.processAttempt(root, event);

        // then
        assertEquals(ExecutionEvidenceUnavailableException.class,
                unavailable.getClass());
        assertTrue(unavailable.getMessage().contains(
                "retained external subscription and activation"));
        assertEquals(
                ProcessAttemptResult.Kind.NEEDS_RESOURCES,
                attempt.kind());
        assertNull(attempt.processResult());
        assertNull(attempt.portableGas());
    }

    @Test
    void shouldVerifyExactCorePreselectionProofAcceptsEmptyFalsePreselection() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        Node other = event("other-topic");
        ExternalDeliverySnapshot active =
                snapshot("/", "incoming", incoming, other);

        // when
        DocumentProcessingResult result =
                processor(planWithActive(active), null, null)
                        .processDocument(root, other);

        // then
        assertEquals(
                ProcessorStatus.NO_MATCH,
                result.status(),
                diagnosticMessage(result));
    }

    @Test
    void shouldVerifyRejectedAcceptanceDoesNotPermitOmittingTruePreselection() {
        // given
        Node rejecting = channel("incoming", 0, false);
        Node root = rootWithChannels(rejecting);
        Node event = event("topic");
        ExternalDeliverySnapshot active =
                snapshot("/", "incoming", rejecting, event);

        // when
        DocumentProcessingResult omitted =
                processor(planWithActive(active), null, null)
                        .processDocument(root, event);

        // then
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                omitted.status());
        assertTrue(diagnosticMessage(omitted).contains(
                "omitted a true preselection"));
    }

    @Test
    void shouldVerifyAttemptSuspendsBeforeProviderDependentCompletenessVerification() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        Node event = event("topic");
        String missing = BlueIdCalculator.calculateBlueId(
                new Node().name("Missing activation proof"));
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(
                                BlueIdCalculator.calculateBlueId(root),
                                BlueIdCalculator.calculateBlueId(event))
                        .revisions(7L, 7L)
                        .runtimeRegistryIdentity(
                                RuntimeBlueIds
                                        .REGISTRY_PACKAGE_IDENTITY)
                        .eventOrderKey(EVENT_ORDER)
                        .requiredExactNode(missing)
                        .build();

        // when
        ProcessAttemptResult attempt =
                processor(plan(), null, null)
                        .processAttempt(root, event, evidence);

        // then
        assertEquals(
                ProcessAttemptResult.Kind.NEEDS_RESOURCES,
                attempt.kind());
        assertEquals(
                Collections.singletonList(missing),
                attempt.requiredExactBlueIds());
        assertNull(attempt.processResult());
        assertNull(attempt.portableGas());
    }

    @Test
    void shouldVerifyTypedFeederAcquisitionSuspendsAttemptButNeverBecomesProcessStatus() {
        // given
        Node root = new Node();
        Node event = event("topic");
        String missing = BlueIdCalculator.calculateBlueId(
                new Node().name("Feeder snapshot evidence"));
        DocumentProcessor processor = DocumentProcessor.builder()
                .withExternalDeliveryPlanDeriver(
                        ExternalDeliveryPlanDeriver.needsResources(
                                Collections.singletonList(missing)))
                .build();

        // when
        ProcessAttemptResult attempt =
                processor.processAttempt(root, event);
        Throwable unavailable = captureFailure(
                () -> processor.processDocument(root, event));

        // then
        assertEquals(
                ProcessAttemptResult.Kind.NEEDS_RESOURCES,
                attempt.kind());
        assertEquals(
                Collections.singletonList(missing),
                attempt.requiredExactBlueIds());
        assertNull(attempt.processResult());
        assertNull(attempt.portableGas());

        assertTrue(unavailable instanceof ExecutionEvidenceUnavailableException);
        assertEquals(
                Collections.singletonList(missing),
                ((ExecutionEvidenceUnavailableException) unavailable)
                        .requiredExactBlueIds());
    }

    @Test
    void shouldVerifyScalarRootWithContractsExecutesItsPreselectedExternalChannel() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming)
                .value(0);
        Node event = event("topic");
        ExternalDeliveryPlan plan = plan(
                snapshot("/", "incoming", incoming, event));

        // when
        DocumentProcessingResult result =
                processor(plan, null, null)
                        .processDocument(root, event);

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
    }

    @Test
    void shouldVerifyAcceptedEvidenceUsesRunLocalManifestAndValidationProofMemos() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        Node event = event("topic");
        Node rejecting = channel("rejecting", 0, false);
        Node rejectingRoot = rootWithChannels(rejecting);

        // when
        ProcessingDebugResult accepted =
                processor(
                        plan(snapshot(
                                "/", "incoming", incoming, event)),
                        null,
                        null)
                        .processDocumentWithTrace(root, event);
        ProcessingDebugResult rejected =
                processor(
                        plan(snapshot(
                                "/", "rejecting", rejecting, event)),
                        null,
                        null)
                        .processDocumentWithTrace(
                                rejectingRoot, event);

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                accepted.processResult().status(),
                diagnosticMessage(accepted.processResult()));
        assertEquals(
                1L,
                accepted.trace().counterQuantity(
                        "semantic", "nodeManifestOpened"),
                "the accepted payload manifest is opened exactly once");
        assertEquals(
                1L,
                accepted.trace().counterQuantity(
                        "semantic", "validationProofReused"));
        assertEquals(
                ProcessorStatus.NO_MATCH,
                rejected.processResult().status(),
                diagnosticMessage(rejected.processResult()));
        assertEquals(
                0L,
                rejected.trace().counterQuantity(
                        "semantic", "nodeManifestOpened"),
                "rejected classification does not open payload manifests");
    }

    @Test
    void shouldVerifyEmittedOccurrencesAreDequeuedFifoBeforeCheckpointCommit() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        root.getContracts().properties(
                "emit",
                traceHandler("incoming"));
        Node event = event("topic");

        // when
        ProcessingDebugResult debug = traceProcessor(
                plan(snapshot("/", "incoming", incoming, event)))
                .processDocumentWithTrace(root, event);
        List<ProcessingTraceRecord> allDequeued =
                debug.trace().records(
                        ProcessingTraceRecord.Kind.EVENT_DEQUEUED);
        List<ProcessingTraceRecord> dequeued =
                new ArrayList<>();
        for (ProcessingTraceRecord record : allDequeued) {
            if (record.node() != null
                    && record.node().getProperties() != null
                    && record.node().getProperties()
                    .containsKey("id")) {
                dequeued.add(record);
            }
        }
        java.util.List<ProcessingTraceRecord> checkpoints =
                debug.trace().records(
                        ProcessingTraceRecord.Kind.CHECKPOINT_WRITE);

        // then
        assertEquals(ProcessorStatus.SUCCESS,
                debug.processResult().status(),
                diagnosticMessage(debug.processResult()));
        assertEquals(2, allDequeued.size());
        assertEquals(2, dequeued.size());
        assertEquals("A", dequeued.get(0).node()
                .getAsText("/id"));
        assertEquals("B", dequeued.get(1).node()
                .getAsText("/id"));
        for (ProcessingTraceRecord record : allDequeued) {
            assertEquals("invocation-event-fifo",
                    record.detail("drainOwner"));
            assertEquals("/",
                    record.detail("sourceScopePath"));
        }
        assertEquals(2, debug.processResult().events().size());
        assertEquals("A", debug.processResult().events()
                .get(0).getAsText("/id"));
        assertEquals("B", debug.processResult().events()
                .get(1).getAsText("/id"));
        assertEquals(1, checkpoints.size());
        assertTrue(checkpoints.get(0).sequence()
                > dequeued.get(1).sequence());
    }

    @Test
    void shouldVerifyAcceptedChildEvidenceBridgesItsEventToTheFrozenRootBeforeCheckpoint() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node child = rootWithChannels(incoming);
        child.getContracts().properties(
                "emitOne",
                traceHandler("incoming"));
        Node root = new Node()
                .properties(
                        "observedBridge",
                        new Node().value("none"))
                .properties("child", child)
                .contracts(new Node()
                        .properties(
                                "embedded",
                                new Node()
                                        .type(new Node().blueId(
                                                RuntimeBlueIds
                                                        .PROCESS_EMBEDDED))
                                        .properties(
                                                "paths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/child"))))
                        .properties(
                                "childBridge",
                                new Node()
                                        .type(new Node().blueId(
                                                RuntimeBlueIds
                                                        .EMBEDDED_NODE_CHANNEL))
                                        .properties(
                                                "sourcePath",
                                                new Node().value(
                                                        "/child")))
                        .properties(
                                "observeBridge",
                                traceHandler("childBridge")));
        Node event = event("topic");

        // when
        ProcessingDebugResult debug = traceProcessor(
                plan(snapshot(
                        "/child", "incoming", incoming, event)))
                .processDocumentWithTrace(root, event);
        String childEventBlueId =
                CheckpointIdentityCalculator.identity(
                        childApplicationEvent());
        ProcessingTraceRecord embeddedDelivery = null;
        for (ProcessingTraceRecord record
                : debug.trace().records(
                ProcessingTraceRecord.Kind.EVENT_DELIVERED)) {
            if ("/".equals(record.scopePath())
                    && "childBridge".equals(record.contractKey())
                    && record.node() != null
                    && record.node().getType() != null
                    && RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY.equals(
                    record.node().getType().getBlueId())
                    && childEventBlueId.equals(
                    record.node().getProperties().get("event")
                            .getBlueId())) {
                embeddedDelivery = record;
                break;
            }
        }
        List<ProcessingTraceRecord> checkpoints =
                debug.trace().records(
                        ProcessingTraceRecord.Kind.CHECKPOINT_WRITE);

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                debug.processResult().status(),
                diagnosticMessage(debug.processResult()));
        assertEquals(
                "child-event",
                debug.processResult().document()
                        .getAsText("/observedBridge"));
        assertTrue(debug.processResult().events().isEmpty(),
                "processor-generated lifecycle delivery is local and "
                        + "the child emission remains internal");
        assertTrue(embeddedDelivery != null,
                "the frozen Root ancestor must receive the child event");
        assertEquals(
                "/child",
                embeddedDelivery.detail("sourceScopePath"));
        assertEquals(
                "/child",
                embeddedDelivery.detail("sourcePath"));
        assertEmbeddedEventDelivery(
                embeddedDelivery.node(),
                "/child",
                childEventBlueId);
        assertEquals(1, checkpoints.size());
        assertTrue(
                checkpoints.get(0).sequence()
                        > embeddedDelivery.sequence(),
                "the child checkpoint must follow ancestor delivery");
    }

    @Test
    void shouldVerifyDocumentUpdateTraceDoesNotInventScopesFromObjectAncestors() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        root.properties("child",
                new Node().properties(
                        "x", new Node().value(0)));
        root.getContracts().properties(
                "update",
                traceHandler("incoming"));
        Node event = event("topic");

        // when
        ProcessingDebugResult debug = traceProcessor(
                plan(snapshot("/", "incoming", incoming, event)))
                .processDocumentWithTrace(root, event);
        java.util.List<ProcessingTraceRecord> updates =
                debug.trace().records(
                        ProcessingTraceRecord.Kind.DOCUMENT_UPDATE);

        // then
        assertEquals(ProcessorStatus.SUCCESS,
                debug.processResult().status(),
                diagnosticMessage(debug.processResult()));
        assertEquals(1, updates.size());
        assertEquals("/", updates.get(0).scopePath());
        assertEquals("/child/x", updates.get(0).logicalPath());
        assertEquals("true",
                updates.get(0).detail("beforePresent"));
        assertEquals("true",
                updates.get(0).detail("afterPresent"));
    }

    @Test
    void shouldVerifyInlineTypeCannotIntroduceProtectedCheckpointState() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        root.getContracts().properties(
                "protected",
                traceHandler("incoming"));
        Node event = event("topic");

        // when
        DocumentProcessingResult result = traceProcessor(
                plan(snapshot("/", "incoming", incoming, event)))
                .processDocument(root, event);

        // then
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertEquals(
                ProcessorErrorCategory
                        .ProtectedProcessorStateMutation,
                diagnosticCategory(result));
    }

    @Test
    void shouldVerifyCheckpointDomainDoesNotConfuseEffectiveNodeWithSourceContribution() {
        // given
        Node root = new Node();
        Node event = event("topic");
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder("/", "incoming")
                        .order(0)
                        .sourceContribution(
                                "selected-source-contribution")
                        .effectiveTypeBlueId(
                                CHANNEL_TYPE_BLUE_ID)
                        .subscriptionKey("topic")
                        .checkpointDomainBlueId(
                                "derived-checkpoint-domain")
                        .checkpointSubjectBlueId(
                                BlueIdCalculator.calculateBlueId(
                                        event))
                        .build();
        VerifiedExecutionEvidence evidence =
                evidence(root, event, 7L,
                        new ExternalDeliverySnapshot[]{delivery},
                        null);
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        new DocumentProcessor(),
                        root,
                        event,
                        evidence);
        PlanChannel contract = new PlanChannel();
        contract.setKey("incoming");
        contract.setTypeBlueId(
                CHANNEL_TYPE_BLUE_ID);
        // when
        ContractBundle.ChannelBinding effectiveBinding =
                new ContractBundle.ChannelBinding(
                        "incoming",
                        contract,
                        FrozenNode.fromResolvedNode(
                                new Node().name(
                                        "materialized-effective-contract")));
        String checkpointDomain =
                execution.checkpointDomain(effectiveBinding, "/");

        // then
        assertEquals(
                "derived-checkpoint-domain",
                checkpointDomain);
    }

    @Test
    void shouldVerifyCoreVerifierRejectsFeederCheckpointSubjectForgery() {
        // given
        Node incoming = channel("incoming", 0, true);
        Node root = rootWithChannels(incoming);
        Node event = event("topic");
        ExternalDeliverySnapshot forged =
                withCheckpointSubject(
                        snapshot("/", "incoming", incoming, event),
                        BlueIdCalculator.calculateBlueId(
                                new Node().value("forged-subject")));

        // when
        DocumentProcessingResult result =
                processor(plan(forged), null, null)
                        .processDocument(root, event);

        // then
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status());
        assertEquals(
                ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                diagnosticCategory(result));
        assertTrue(diagnosticMessage(result).contains(
                "checkpoint subject mismatch"));
    }

    @Test
    void shouldVerifyCoreVerifierRejectsNondeterministicCheckpointSubjectFunction() {
        // given
        Node incoming = channel("incoming", 0, true)
                .properties(
                        "nondeterministicSubject",
                        new Node().value(true));
        Node root = rootWithChannels(incoming);
        Node event = event("topic");

        // when
        DocumentProcessingResult result =
                processor(
                        plan(snapshot(
                                "/", "incoming", incoming, event)),
                        null,
                        null)
                        .processDocument(root, event);

        // then
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status());
        assertEquals(
                ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                diagnosticCategory(result));
        assertTrue(diagnosticMessage(result).contains(
                "functions are not deterministic"));
    }

    @Test
    void shouldVerifyPhaseBUsesRecomputedFrozenPayloadAndSubject() {
        // given
        Node incoming = channel("incoming", 0, true)
                .properties(
                        "payloadTag",
                        new Node().value("authoritative"))
                .properties(
                        "checkpointSubjectField",
                        new Node().value("subject"));
        Node root = rootWithChannels(incoming);
        root.properties(
                "observedPayload",
                new Node().value("unset"));
        root.getContracts().properties(
                "capture",
                traceHandler("incoming"));
        Node event = event("topic")
                .properties(
                        "subject",
                        new Node().value("subject-v1"));
        String authoritativeSubject =
                BlueIdCalculator.calculateBlueId(
                        event.getProperties().get("subject"));
        ExternalDeliverySnapshot forged =
                withCheckpointSubject(
                        snapshot("/", "incoming", incoming, event),
                        BlueIdCalculator.calculateBlueId(
                                new Node().value("feeder-forgery")));
        VerifiedExecutionEvidence evidence =
                evidence(
                        root,
                        event,
                        7L,
                        new ExternalDeliverySnapshot[]{forged},
                        null);
        DocumentProcessor processor =
                DocumentProcessor.builder()
                        .registerContractProcessor(
                                CHANNEL_TYPE_BLUE_ID,
                                CHANNEL_TYPE,
                                new PlanChannelProcessor())
                        .registerContractProcessor(
                                TRACE_HANDLER_TYPE_BLUE_ID,
                                TRACE_HANDLER_TYPE,
                                new TraceHandlerProcessor())
                        .withExternalDeliveryEvidenceVerifier(
                                (ignoredRoot,
                                 ignoredEvent,
                                 ignoredEvidence) -> {
                                    // Isolates the Phase-B trust boundary.
                                })
                        .build();

        // when
        DocumentProcessingResult result =
                processor.processDocument(root, event, evidence);
        Node checkpointSubject =
                result.document().getContracts()
                        .getProperties().get("checkpoint")
                        .getProperties().get("entries")
                        .getProperties().get("incoming")
                        .getProperties().get("subject");

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
        assertEquals(
                "authoritative",
                result.document().getAsText(
                        "/observedPayload"));
        assertEquals(
                authoritativeSubject,
                BlueIdCalculator.calculateBlueId(
                        checkpointSubject));
        assertEquals(
                "subject-v1",
                checkpointSubject.getValue());
    }

    @Test
    void shouldVerifyNodeAndResolvedSnapshotProcessOnlyPreselectedOccurrence() {
        // given
        Node rootChannel = channel("root", 0, true);
        Node childChannel = channel("child", 0, false);
        childChannel.getProperties().put(
                "subscriptionKey",
                new Node().value("child-topic"));
        Node root = rootWithChannels(rootChannel);
        root.getContracts().properties(
                "embedded",
                new Node()
                        .type(new Node().blueId(
                                RuntimeBlueIds.PROCESS_EMBEDDED))
                        .properties(
                                "paths",
                                new Node().items(
                                        new Node().value("/child"))));
        root.properties(
                "child",
                rootWithChannels(childChannel));
        Node event = event("topic");
        ExternalDeliveryPlan plan = plan(
                snapshot("/", "root", rootChannel, event));

        Map<String, Node> providerNodes = new LinkedHashMap<>();
        providerNodes.put(CHANNEL_TYPE_BLUE_ID, CHANNEL_TYPE);
        try (Blue language = new Blue(blueId -> {
            Node node = providerNodes.get(blueId);
            return node != null
                    ? Collections.singletonList(node.clone())
                    : null;
        })) {
            DocumentProcessor processor = processor(
                    plan,
                    language,
                    language.getDocumentProcessor()
                            .snapshotManager());

            // when
            DocumentProcessingResult nodeResult =
                    processor.processDocument(
                            root.clone(), event);
            ResolvedSnapshot snapshot =
                    language.resolveToSnapshot(root.clone());
            DocumentProcessingResult snapshotResult =
                    processor.processDocument(snapshot, event);

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    nodeResult.status(),
                    diagnosticMessage(nodeResult));
            assertFalse(hasInitializedMarker(
                    nodeResult.document(), "/child"));

            assertEquals(
                    ProcessorStatus.SUCCESS,
                    snapshotResult.status(),
                    diagnosticMessage(snapshotResult));
            assertFalse(hasInitializedMarker(
                    snapshotResult.document(), "/child"));
        }
    }

    private static DocumentProcessor processor(
            ExternalDeliveryPlan plan,
            Blue language,
            ProcessingSnapshotManager snapshotManager) {
        DocumentProcessor.Builder builder =
                DocumentProcessor.builder()
                        .registerContractProcessor(
                                CHANNEL_TYPE_BLUE_ID,
                                CHANNEL_TYPE,
                                new PlanChannelProcessor());
        if (language != null) {
            builder.withMatchingService(
                    new ContractMatchingService(language));
        }
        if (snapshotManager != null) {
            builder.withSnapshotManager(snapshotManager);
        }
        if (plan != null) {
            builder.withExternalDeliveryPlanDeriver(
                    (root, event) -> plan);
        }
        return builder.build();
    }

    private static DocumentProcessor traceProcessor(
            ExternalDeliveryPlan plan) {
        return DocumentProcessor.builder()
                .registerContractProcessor(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE,
                        new PlanChannelProcessor())
                .registerContractProcessor(
                        TRACE_HANDLER_TYPE_BLUE_ID,
                        TRACE_HANDLER_TYPE,
                        new TraceHandlerProcessor())
                .withExternalDeliveryPlanDeriver(
                        (root, event) -> plan)
                .build();
    }

    private static ExternalDeliveryPlan plan(
            ExternalDeliverySnapshot... deliveries) {
        ExternalDeliveryPlan.Builder builder =
                ExternalDeliveryPlan.builder()
                        .revisions(7L, 7L)
                        .eventOrderKey(EVENT_ORDER)
                        .activeSubscriptionIntervals(
                                Collections.<SubscriptionDelta.Entry>
                                        emptyList())
                        .exactRuntimeState();
        for (ExternalDeliverySnapshot delivery : deliveries) {
            builder.delivery(delivery);
            builder.activeSubscriptionInterval(
                    activeInterval(delivery));
        }
        return builder.build();
    }

    private static ExternalDeliveryPlan planWithActive(
            ExternalDeliverySnapshot... activeOccurrences) {
        ExternalDeliveryPlan.Builder builder =
                ExternalDeliveryPlan.builder()
                        .revisions(7L, 7L)
                        .eventOrderKey(EVENT_ORDER)
                        .activeSubscriptionIntervals(
                                Collections.<SubscriptionDelta.Entry>
                                        emptyList())
                        .exactRuntimeState();
        for (ExternalDeliverySnapshot occurrence
                : activeOccurrences) {
            builder.activeSubscriptionInterval(
                    activeInterval(occurrence));
        }
        return builder.build();
    }

    private static SubscriptionDelta.Entry activeInterval(
            ExternalDeliverySnapshot occurrence) {
        return new SubscriptionDelta.Entry(
                occurrence.scopePath(),
                occurrence.channelKey(),
                occurrence.effectiveTypeBlueId(),
                occurrence.sourceContributionNodeBlueIds(),
                occurrence.order(),
                occurrence.subscriptionKeys(),
                occurrence.checkpointDomainBlueId(),
                1L,
                occurrence.activationStartExclusive(),
                null);
    }

    private static VerifiedExecutionEvidence evidence(
            Node root,
            Node event,
            long revision,
            ExternalDeliverySnapshot[] deliveries,
            String availableResource) {
        VerifiedExecutionEvidence.Builder builder =
                VerifiedExecutionEvidence.builder(
                                BlueIdCalculator.calculateBlueId(root),
                                BlueIdCalculator.calculateBlueId(event))
                        .revisions(revision, revision)
                        .runtimeRegistryIdentity(
                                RuntimeBlueIds
                                        .REGISTRY_PACKAGE_IDENTITY)
                        .eventOrderKey(EVENT_ORDER);
        for (ExternalDeliverySnapshot delivery : deliveries) {
            builder.delivery(delivery);
        }
        if (availableResource != null) {
            builder.availableExactNode(availableResource);
        }
        return builder.build();
    }

    private static ExternalDeliverySnapshot snapshot(
            String scope,
            String key,
            Node channel,
            Node event) {
        return snapshotWithContributions(
                scope,
                key,
                channel,
                event,
                BlueIdCalculator.calculateBlueId(channel));
    }

    private static ExternalDeliverySnapshot snapshotWithContributions(
            String scope,
            String key,
            Node channel,
            Node event,
            String... contributions) {
        String domain = CheckpointDomain.derive(
                CHANNEL_TYPE_BLUE_ID,
                Arrays.asList(contributions),
                channel.getAsText("/checkpointDomain"));
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(scope, key)
                        .order(channel.getAsInteger("/order"))
                        .effectiveTypeBlueId(
                                CHANNEL_TYPE_BLUE_ID)
                        .subscriptionKey(
                                channel.getAsText(
                                        "/subscriptionKey"))
                        .checkpointDomainBlueId(domain)
                        .checkpointSubjectBlueId(
                                BlueIdCalculator.calculateBlueId(
                                        event));
        for (String contribution : contributions) {
            builder.sourceContribution(contribution);
        }
        return builder.build();
    }

    private static ExternalDeliverySnapshot withExtraContribution(
            ExternalDeliverySnapshot source) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                source.scopePath(),
                                source.channelKey())
                        .order(source.order())
                        .effectiveTypeBlueId(
                                source.effectiveTypeBlueId())
                        .checkpointSubjectBlueId(
                                source.checkpointSubjectBlueId());
        for (String contribution
                : source.sourceContributionNodeBlueIds()) {
            builder.sourceContribution(contribution);
        }
        builder.sourceContribution("forged-contribution");
        for (String key : source.subscriptionKeys()) {
            builder.subscriptionKey(key);
        }
        builder.checkpointDomainBlueId(
                CheckpointDomain.derive(
                        source.effectiveTypeBlueId(),
                        Arrays.asList(
                                source.sourceContributionNodeBlueIds()
                                        .get(0),
                                "forged-contribution"),
                        "plan-domain"));
        return builder.build();
    }

    private static ExternalDeliverySnapshot withCheckpointSubject(
            ExternalDeliverySnapshot source,
            String checkpointSubjectBlueId) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                source.scopePath(),
                                source.channelKey())
                        .order(source.order())
                        .effectiveTypeBlueId(
                                source.effectiveTypeBlueId())
                        .checkpointDomainBlueId(
                                source.checkpointDomainBlueId())
                        .checkpointSubjectBlueId(
                                checkpointSubjectBlueId);
        for (String contribution
                : source.sourceContributionNodeBlueIds()) {
            builder.sourceContribution(contribution);
        }
        for (String key : source.subscriptionKeys()) {
            builder.subscriptionKey(key);
        }
        if (source.activationStartExclusive() != null) {
            builder.activationStartExclusive(
                    source.activationStartExclusive());
        }
        if (source.activationEndInclusive() != null) {
            builder.activationEndInclusive(
                    source.activationEndInclusive());
        }
        return builder.build();
    }

    private static Node rootWithChannels(Node... channels) {
        Node contracts = new Node();
        for (Node channel : channels) {
            contracts.properties(
                    channel.getAsText("/key"), channel);
            channel.getProperties().remove("key");
        }
        return new Node().contracts(contracts);
    }

    private static Node channel(
            String key,
            int order,
            boolean enabled) {
        return new Node()
                .type(new Node().blueId(
                        CHANNEL_TYPE_BLUE_ID))
                .properties("key", new Node().value(key))
                .properties(
                        "order", new Node().value(order))
                .properties(
                        "subscriptionKey",
                        new Node().value("topic"))
                .properties(
                        "checkpointDomain",
                        new Node().value("plan-domain"))
                .properties(
                        "enabled", new Node().value(enabled));
    }

    private static Node event(String subscriptionKey) {
        return new Node().properties(
                "subscriptionKey",
                new Node().value(subscriptionKey));
    }

    private static Node childApplicationEvent() {
        return new Node().properties(
                "id", new Node().value("child-event"));
    }

    private static void assertEmbeddedEventDelivery(
            Node delivery,
            String expectedSourcePath,
            String expectedEventBlueId) {
        assertNotNull(delivery);
        assertNotNull(delivery.getType());
        assertEquals(RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY,
                delivery.getType().getBlueId());
        assertNotNull(delivery.getProperties());
        assertEquals(2, delivery.getProperties().size());
        assertEquals(expectedSourcePath,
                delivery.getAsText("/sourcePath"));
        assertFalse(delivery.getProperties()
                .containsKey("childPath"));
        Node eventReference =
                delivery.getProperties().get("event");
        assertNotNull(eventReference);
        assertTrue(eventReference.isReferenceOnly());
        assertEquals(expectedEventBlueId,
                eventReference.getBlueId());
    }

    private static Node traceHandler(String channelKey) {
        return new Node()
                .type(new Node().blueId(
                        TRACE_HANDLER_TYPE_BLUE_ID))
                .properties("channel",
                        new Node().value(channelKey));
    }

    private static boolean hasInitializedMarker(
            Node document,
            String scope) {
        Node current = "/".equals(scope)
                ? document
                : document.getProperties().get(
                        scope.substring(1));
        return current != null
                && current.getContracts() != null
                && current.getContracts().getProperties() != null
                && current.getContracts().getProperties()
                .containsKey("initialized");
    }

    private static void assertInvalid(
            DocumentProcessingResult result) {
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status());
        assertEquals(
                ProcessorErrorCategory
                        .InvalidExternalChannelSnapshot,
                diagnosticCategory(result));
    }

    public static final class PlanChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String checkpointDomain;
        private String payloadTag;
        private String checkpointSubjectField;
        private Boolean nondeterministicSubject;
        private Boolean enabled;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(String checkpointDomain) {
            this.checkpointDomain = checkpointDomain;
        }

        public String getPayloadTag() {
            return payloadTag;
        }

        public void setPayloadTag(String payloadTag) {
            this.payloadTag = payloadTag;
        }

        public String getCheckpointSubjectField() {
            return checkpointSubjectField;
        }

        public void setCheckpointSubjectField(
                String checkpointSubjectField) {
            this.checkpointSubjectField =
                    checkpointSubjectField;
        }

        public Boolean getNondeterministicSubject() {
            return nondeterministicSubject;
        }

        public void setNondeterministicSubject(
                Boolean nondeterministicSubject) {
            this.nondeterministicSubject =
                    nondeterministicSubject;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static final class TraceHandler
            extends HandlerContract {
    }

    private static final class TraceHandlerProcessor
            implements HandlerProcessor<TraceHandler> {
        @Override
        public Class<TraceHandler> contractType() {
            return TraceHandler.class;
        }

        @Override
        public boolean matches(
                TraceHandler contract,
                HandlerMatchContext context) {
            if (!"observeBridge".equals(
                    context.handlerKey())) {
                return true;
            }
            Node wireEvent = context.event();
            Node occurrenceEvent =
                    context.occurrenceEvent();
            return wireEvent != null
                    && wireEvent.getType() != null
                    && RuntimeBlueIds
                    .EMBEDDED_EVENT_DELIVERY.equals(
                            wireEvent.getType()
                                    .getBlueId())
                    && occurrenceEvent != null
                    && "child-event".equals(
                            occurrenceEvent.getAsText(
                                    "/id"));
        }

        @Override
        public void execute(TraceHandler contract,
                            ProcessorExecutionContext context) {
            if ("emit".equals(context.contractKey())) {
                context.emitEvent(new Node().properties(
                        "id", new Node().value("A")));
                context.emitEvent(new Node().properties(
                        "id", new Node().value("B")));
            } else if ("emitOne".equals(
                    context.contractKey())) {
                context.emitEvent(childApplicationEvent());
            } else if ("observeBridge".equals(
                    context.contractKey())) {
                Node wrapper = context.event();
                if (!"child-event".equals(
                        context.occurrenceEvent()
                                .getAsText("/id"))) {
                    return;
                }
                Node eventReference =
                        wrapper.getProperties() != null
                                ? wrapper.getProperties().get("event")
                                : null;
                if (wrapper.getType() == null
                        || !RuntimeBlueIds
                        .EMBEDDED_EVENT_DELIVERY.equals(
                        wrapper.getType().getBlueId())
                        || !"/child".equals(
                        wrapper.getAsText("/sourcePath"))
                        || eventReference == null
                        || !eventReference.isReferenceOnly()
                        || !CheckpointIdentityCalculator.identity(
                        childApplicationEvent())
                        .equals(eventReference.getBlueId())) {
                    return;
                }
                context.applyPatch(JsonPatch.replace(
                        "/observedBridge",
                        new Node().value("child-event")));
            } else if ("update".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace(
                        "/child/x", new Node().value(1)));
            } else if ("capture".equals(
                    context.contractKey())) {
                context.applyPatch(JsonPatch.replace(
                        "/observedPayload",
                        new Node().value(
                                context.event().getAsText(
                                        "/payloadSource"))));
            } else if ("protected".equals(
                    context.contractKey())) {
                context.applyPatch(JsonPatch.replace(
                        "/type",
                        new Node().contracts(
                                new Node().properties(
                                        "checkpoint",
                                        new Node()))));
            }
        }
    }

    private static final class PlanChannelProcessor
            implements ChannelProcessor<PlanChannel> {
        private static final java.util.concurrent.atomic.AtomicInteger
                NONDETERMINISTIC_SUBJECT_SEQUENCE =
                new java.util.concurrent.atomic.AtomicInteger();
        private static final
        ExternalChannelSubscriptionFunctions<PlanChannel>
                SUBSCRIPTION_FUNCTIONS =
                new ExternalChannelSubscriptionFunctions<PlanChannel>() {
                    @Override
                    public List<String> channelKeys(
                            PlanChannel immutableContractSnapshot) {
                        return Collections.singletonList(
                                immutableContractSnapshot
                                        .getSubscriptionKey());
                    }

                    @Override
                    public boolean accepts(
                            PlanChannel immutableContractSnapshot,
                            Node exactEvent) {
                        return !Boolean.FALSE.equals(
                                immutableContractSnapshot.getEnabled())
                                && preselects(
                                immutableContractSnapshot, exactEvent);
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            PlanChannel immutableContractSnapshot) {
                        return immutableContractSnapshot
                                .getCheckpointDomain();
                    }

                    @Override
                    public Node payload(
                            PlanChannel immutableContractSnapshot,
                            Node exactEvent) {
                        Node payload = exactEvent.clone();
                        if (immutableContractSnapshot
                                .getPayloadTag() != null) {
                            payload.properties(
                                    "payloadSource",
                                    new Node().value(
                                            immutableContractSnapshot
                                                    .getPayloadTag()));
                        }
                        return payload;
                    }

                    @Override
                    public Node checkpointSubject(
                            PlanChannel immutableContractSnapshot,
                            Node exactEvent,
                            Node exactPayload) {
                        if (Boolean.TRUE.equals(
                                immutableContractSnapshot
                                        .getNondeterministicSubject())) {
                            return new Node().value(
                                    "subject-"
                                            + NONDETERMINISTIC_SUBJECT_SEQUENCE
                                            .incrementAndGet());
                        }
                        String field =
                                immutableContractSnapshot
                                        .getCheckpointSubjectField();
                        if (field == null) {
                            return ExternalChannelSubscriptionFunctions
                                    .super.checkpointSubject(
                                            immutableContractSnapshot,
                                            exactEvent,
                                            exactPayload);
                        }
                        Node subject = exactPayload.getProperties() != null
                                ? exactPayload.getProperties().get(field)
                                : null;
                        if (subject == null) {
                            throw new IllegalArgumentException(
                                    "Missing checkpoint subject field: "
                                            + field);
                        }
                        return subject.clone();
                    }
                };

        @Override
        public Class<PlanChannel> contractType() {
            return PlanChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<PlanChannel>
        externalSubscriptionFunctions() {
            return SUBSCRIPTION_FUNCTIONS;
        }

        @Override
        public boolean matches(
                PlanChannel contract,
                ChannelEvaluationContext context) {
            Node key = context.event() != null
                    && context.event().getProperties() != null
                    ? context.event().getProperties()
                    .get("subscriptionKey")
                    : null;
            return !Boolean.FALSE.equals(contract.getEnabled())
                    && key != null
                    && contract.getSubscriptionKey().equals(
                    key.getValue());
        }

        @Override
        public ChannelEvaluation evaluate(
                PlanChannel contract,
                ChannelEvaluationContext context) {
            if (!matches(contract, context)) {
                return ChannelEvaluation.noMatch();
            }
            Node legacyPayload = context.event();
            if (contract.getPayloadTag() != null) {
                legacyPayload.properties(
                        "payloadSource",
                        new Node().value("legacy"));
            }
            return ChannelEvaluation.match(legacyPayload);
        }
    }
}
