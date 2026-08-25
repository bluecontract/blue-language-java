package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.TestEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordinary commit-boundary coverage for exact contract-surface evidence. */
final class ProcessingResultCoordinatorContractSurfaceTest {

    @Test
    void shouldReconcileNoOpAddReplaceAndRemoveAtSuccessfulBoundary() {
        // given
        Node withoutEmbedded = documentWithoutEmbedded();

        // when
        ContractSurfaceDelta noOp = run(
                withoutEmbedded.clone().properties(
                        "status", new Node().value("before")),
                JsonPatch.replace("/status", new Node().value("after")));
        ContractSurfaceDelta added = run(
                withoutEmbedded,
                JsonPatch.add(
                        "/contracts/embedded",
                        processEmbedded("/peer")));
        ContractSurfaceDelta replaced = run(
                documentWithEmbedded("/peer"),
                JsonPatch.replace(
                        "/contracts/embedded",
                        processEmbedded("/next")));
        ContractSurfaceDelta removed = run(
                documentWithEmbedded("/peer"),
                JsonPatch.remove("/contracts/embedded"));

        // then
        assertTrue(noOp.contracts().isEmpty());
        assertTrue(noOp.channels().isEmpty());
        assertTrue(noOp.operations().isEmpty());
        assertTrue(noOp.processEmbedded().isEmpty());
        assertNotEquals(noOp.beforeIdentity(), noOp.afterIdentity());
        assertSurfaceChange(added, ContractSurfaceDelta.Change.ADD);
        assertSurfaceChange(replaced, ContractSurfaceDelta.Change.REPLACE);
        assertSurfaceChange(removed, ContractSurfaceDelta.Change.REMOVE);
    }

    @Test
    void shouldUseCustomValidatorDeltaWithoutPublishingValidatorMutations() {
        // given
        AtomicInteger calls = new AtomicInteger();
        SubscriptionDelta expected = SubscriptionDelta.empty();
        try (Blue blue = ProcessorTestSupport.blue()) {
            DocumentProcessor base = blue.getDocumentProcessor();
            DocumentProcessor custom = DocumentProcessor.Builder.from(base)
                    .subscriptionSurfaceValidator(context -> {
                        calls.incrementAndGet();
                        context.inputRoot().properties(
                                "validatorInputMutation",
                                new Node().value(true));
                        context.tentativeRoot().properties(
                                "validatorTentativeMutation",
                                new Node().value(true));
                        return expected;
                    })
                    .build();
            try {
                // when
                ProcessorInvocationState execution = execute(
                        custom,
                        documentWithoutEmbedded().properties(
                                "status", new Node().value("before")),
                        JsonPatch.replace(
                                "/status", new Node().value("after")),
                        null,
                        null);
                ContractSurfaceReconciliation reconciliation =
                        execution.contractSurfaceReconciliation();

                // then
                assertEquals(1, calls.get());
                assertNotNull(reconciliation);
                assertSame(expected, reconciliation.subscriptionDelta());
                assertTrue(reconciliation.surfaceDelta().contracts().isEmpty());
                assertTrue(reconciliation.surfaceDelta()
                        .processEmbedded().isEmpty());
                assertNull(execution.runtime().document()
                        .getProperties()
                        .get("validatorTentativeMutation"));
            } finally {
                custom.close();
            }
        }
    }

    @Test
    void shouldPublishNoSurfaceReconciliationWhenValidationFails() {
        // given
        try (Blue blue = ProcessorTestSupport.blue()) {
            DocumentProcessor base = blue.getDocumentProcessor();
            DocumentProcessor rejecting = DocumentProcessor.Builder.from(base)
                    .subscriptionSurfaceValidator(context -> {
                        throw new SubscriptionSurfaceInvalidException(
                                "rejected by focused test");
                    })
                    .build();
            try {
                ProcessorInvocationState execution = prepare(
                        rejecting,
                        documentWithoutEmbedded().properties(
                                "status", new Node().value("before")),
                        JsonPatch.replace(
                                "/status", new Node().value("after")),
                        null,
                        null);

                // when
                SubscriptionSurfaceInvalidException failure =
                        FailureCapture.captureFailure(
                                execution::validateSubscriptionDelta);

                // then
                assertNotNull(failure);
                assertNull(execution.contractSurfaceReconciliation());
                execution.fail(
                        ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                        failure.diagnostic());
                assertFalse(execution.result().commits());
                assertNull(execution.contractSurfaceReconciliation());
            } finally {
                rejecting.close();
            }
        }
    }

    @Test
    void shouldReconcileTheAuthoritativeRetainedIntervalDelta() {
        // given
        CountingExternalProcessor channelProcessor =
                new CountingExternalProcessor();
        try (Blue blue = ProcessorTestSupport.blue()) {
            blue.registerContractProcessor(channelProcessor);
            DocumentProcessor processor = blue.getDocumentProcessor();
            Node channel = externalChannel("topic");
            Node document = new Node().contracts(
                    new Node().properties("incoming", channel));
            Node event = new Node().value("event");
            String contribution =
                    DirectBlueIdCalculator.calculateBlueId(channel);
            SubscriptionDelta.Entry retained = new SubscriptionDelta.Entry(
                    "/",
                    "incoming",
                    ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL,
                    Collections.singletonList(contribution),
                    0,
                    Collections.singletonList("topic"),
                    CheckpointDomain.derive(
                            ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL,
                            Collections.singletonList(contribution),
                            "coordinator-retained-test"),
                    4L,
                    ExternalOrderKey.of(Arrays.<Object>asList(
                            4, "timeline", 0)),
                    null);
            VerifiedExecutionEvidence evidence =
                    VerifiedExecutionEvidence.builder(
                                    DirectBlueIdCalculator.calculateBlueId(
                                            document),
                                    DirectBlueIdCalculator.calculateBlueId(
                                            event))
                            .revisions(6L, 6L)
                            .runtimeRegistryIdentity(
                                    processor.runtimeRegistryIdentity())
                            .eventOrderKey(ExternalOrderKey.of(
                                    Arrays.<Object>asList(
                                            7, "timeline", 0)))
                            .activeSubscriptionInterval(retained)
                            .build();

            // when
            ProcessorInvocationState execution = execute(
                    processor,
                    document,
                    JsonPatch.remove("/contracts/incoming"),
                    event,
                    evidence);
            ContractSurfaceReconciliation reconciliation =
                    execution.contractSurfaceReconciliation();
            SubscriptionDelta validated =
                    reconciliation.subscriptionDelta();

            // then
            assertTrue(validated.added().isEmpty());
            assertEquals(1, validated.removed().size());
            assertEquals(Long.valueOf(7L),
                    validated.removed().get(0).endAtRootRevision());
            assertEquals(ContractSurfaceDelta.Change.REMOVE,
                    reconciliation.surfaceDelta()
                            .subscriptions().get(0).change());
            assertEquals(0, channelProcessor.evaluations(),
                    "retained before-state and complete capture must not "
                            + "re-evaluate the removed channel");
        }
    }

    @Test
    void shouldClassifyOnlyExplicitOperationHandlersInRegistryGeneration() {
        // given
        Node canonicalType = new Node().name(
                "Contract surface operation-route metadata");
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                canonicalType);
        ContractProcessorRegistry generic = new ContractProcessorRegistry();
        generic.register(
                typeBlueId,
                canonicalType,
                handler(false));
        ContractProcessorRegistry operation =
                new ContractProcessorRegistry();
        operation.register(
                typeBlueId,
                canonicalType,
                handler(true));

        // when
        ContractProcessorRegistry genericSnapshot = generic.snapshot();
        ContractProcessorRegistry operationSnapshot = operation.snapshot();

        // then
        assertFalse(genericSnapshot.isOperationRoute(typeBlueId));
        assertTrue(operationSnapshot.isOperationRoute(typeBlueId));
        assertNotEquals(
                genericSnapshot.generationIdentity(),
                operationSnapshot.generationIdentity());
    }

    private static ContractSurfaceDelta run(
            Node document,
            JsonPatch patch) {
        try (Blue blue = ProcessorTestSupport.blue()) {
            ProcessorInvocationState execution = execute(
                    blue.getDocumentProcessor(),
                    document,
                    patch,
                    null,
                    null);
            ContractSurfaceReconciliation reconciliation =
                    execution.contractSurfaceReconciliation();
            assertNotNull(reconciliation);
            assertTrue(reconciliation.subscriptionDelta().isEmpty());
            return reconciliation.surfaceDelta();
        }
    }

    private static ProcessorInvocationState execute(
            DocumentProcessor processor,
            Node document,
            JsonPatch patch,
            Node event,
            VerifiedExecutionEvidence evidence) {
        ProcessorInvocationState execution = prepare(
                processor, document, patch, event, evidence);
        execution.validateSubscriptionDelta();
        assertTrue(execution.result().commits());
        return execution;
    }

    private static ProcessorInvocationState prepare(
            DocumentProcessor processor,
            Node document,
            JsonPatch patch,
            Node event,
            VerifiedExecutionEvidence evidence) {
        ProcessorInvocationState execution = evidence != null
                ? new ProcessorInvocationState(
                        processor, document, event, evidence)
                : event != null
                ? new ProcessorInvocationState(processor, document, event)
                : new ProcessorInvocationState(processor, document);
        execution.preflightScope("/");
        execution.handlePatches(
                "/",
                execution.bundleForScope("/"),
                Collections.singletonList(patch),
                false);
        execution.recordCompletedDelivery();
        execution.performFinalSoundnessValidation();
        return execution;
    }

    private static void assertSurfaceChange(
            ContractSurfaceDelta delta,
            ContractSurfaceDelta.Change change) {
        assertEquals(1, delta.contracts().size());
        assertEquals(change, delta.contracts().get(0).change());
        assertEquals("embedded", delta.contracts().get(0).key());
        assertEquals(1, delta.processEmbedded().size());
        assertEquals(change, delta.processEmbedded().get(0).change());
    }

    private static Node documentWithoutEmbedded() {
        return new Node().contracts(new Node());
    }

    private static Node documentWithEmbedded(String path) {
        return new Node().contracts(new Node().properties(
                "embedded", processEmbedded(path)));
    }

    private static Node processEmbedded(String path) {
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(new Node().value(path)));
    }

    private static Node externalChannel(String subscriptionKey) {
        return new Node()
                .type(new Node().blueId(
                        ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL))
                .properties(
                        "eventType",
                        new Node().value(subscriptionKey));
    }

    private static HandlerProcessor<HandlerContract> handler(
            boolean operationRoute) {
        return new HandlerProcessor<HandlerContract>() {
            @Override
            public boolean isOperationRoute() {
                return operationRoute;
            }

            @Override
            public Class<HandlerContract> contractType() {
                return HandlerContract.class;
            }

            @Override
            public void execute(
                    HandlerContract contract,
                    ProcessorExecutionContext context) {
                // Registry metadata is the only behavior under test.
            }
        };
    }

    private static final class CountingExternalProcessor
            implements ChannelProcessor<TestEventChannel> {

        private final AtomicInteger evaluations = new AtomicInteger();
        private final ExternalChannelSubscriptionFunctions<TestEventChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<TestEventChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestEventChannel immutableContractSnapshot) {
                        evaluations.incrementAndGet();
                        return Collections.singletonList(
                                immutableContractSnapshot.getEventType());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            TestEventChannel immutableContractSnapshot) {
                        return "coordinator-retained-test";
                    }
                };

        @Override
        public Class<TestEventChannel> contractType() {
            return TestEventChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<TestEventChannel>
        externalSubscriptionFunctions() {
            return functions;
        }

        private int evaluations() {
            return evaluations.get();
        }
    }
}
