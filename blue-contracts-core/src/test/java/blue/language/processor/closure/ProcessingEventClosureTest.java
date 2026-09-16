package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.BlueContracts;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingEventClosureTest {
    private static final DocumentId ROOT = new DocumentId("processing-event-root");
    private static final Node CHANNEL = new Node().name("Processing event test channel");
    private static final Node HANDLER = new Node().name("Processing event test handler");
    private static final String CHANNEL_ID = id(CHANNEL);
    private static final String HANDLER_ID = id(HANDLER);
    private static final String SUBSCRIPTION_KEY = "test";

    @Test
    void shouldRetainAdmittedSourceIdentityAcrossInternalStepsForAnInlineTypedEvent() {
        // given
        Node original = event("go").type(new Node().name("Inline processing event type"))
                .properties("values", new Node().items(Arrays.asList(
                        new Node().value("first"), new Node().value("second"))));
        List<Observation> observations = new ArrayList<>();
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            } else if ("triggered".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
            }
        })) {
            String admittedBlueId = fixture.backingContracts.runtimeAccess()
                    .languageRuntime().calculateSourceDocumentBlueId(original.clone());
            ClosureInvocationInput input = fixture.external(
                    snapshot(document(), true), original, ROOT, admittedBlueId);

            // when
            ClosureProcessResult result = fixture.process(input);

            // then
            assertNotEquals(admittedBlueId, FrozenNode.fromResolvedNode(original).blueId());
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered", "updated"), handlers(observations));
            assertAdmittedCause(observations, original, admittedBlueId);
            assertEquals("ping", observations.get(1).payload.getNode("/kind").getValue());
            assertEquals("/count", observations.get(2).payload.getNode("/path").getValue());
        }
    }

    @Test
    void shouldPreserveOriginalEventAnnotationsWithoutUsingThemAsTheAdmittedIdentity() {
        // given
        Node original = event("go");
        String admittedBlueId = id(original);
        String annotation = id(new Node().value("representation annotation"));
        original.blueId(annotation);
        original.getProperties().get("kind").blueId(annotation);
        List<Observation> observations = new ArrayList<>();
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            }
        })) {
            ClosureInvocationInput input = fixture.external(
                    snapshot(document(), true), original, ROOT, admittedBlueId);

            // when
            ClosureProcessResult result = fixture.process(input);

            // then
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered"), handlers(observations));
            assertAdmittedCause(observations, original, admittedBlueId);
            assertNotEquals(annotation, observations.get(1).identityEvidence.eventBlueId());
        }
    }

    @Test
    void shouldRetainOriginalEventAcrossTriggeredAndDocumentUpdateSteps() {
        // given
        List<Observation> observations = new ArrayList<>();
        Node event = event("go");
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            } else if ("triggered".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
            }
        })) {
            ClosureInvocationInput input = fixture.external(snapshot(document(), true), event);

            // when
            ClosureProcessResult result = fixture.process(input);

            // then
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered", "updated"), handlers(observations));
            assertEquals("go", observations.get(0).payload.getNode("/kind").getValue());
            assertEquals("ping", observations.get(1).payload.getNode("/kind").getValue());
            assertEquals("/count", observations.get(2).payload.getNode("/path").getValue());
            assertCause(observations, event);
        }
    }

    @Test
    void shouldRetainExternalCauseWhenAChildIsBornAndInitializedAfterResourceRetry() {
        // given
        List<Observation> observations = new ArrayList<>();
        Node event = event("go");
        Node child = document().name("New child");
        Node parent = document();
        parent.getContracts().properties("embedded", processEmbedded("/child"));
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.add("/child", child));
            } else if ("onInit".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            }
        })) {
            ClosureInvocationInput input = fixture.external(snapshot(parent, true), event);

            // when
            ClosureAttemptResult missing = fixture.attempt(input);
            ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) missing.resourceDemands().get(0);
            ClosureInvocationInput retry = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(demand, new DocumentId("new-child"), child)));
            observations.clear();
            ClosureProcessResult result = fixture.process(retry);

            // then
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, missing.kind());
            assertEquals(1, missing.resourceDemands().size());
            assertNull(missing.processResult());
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "onInit", "triggered"), handlers(observations));
            assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                    observations.get(1).payload.getType().getBlueId());
            assertCause(observations, event);
        }
    }

    @Test
    void shouldRetainAdmittedCauseAcrossColdInvocationAndStoredBirthRetry() {
        Node original = event("go").type(new Node().name("Cold birth processing event type"));
        Node child = document().name("Cold child");
        Node parent = document();
        parent.getContracts().properties("embedded", processEmbedded("/child"));
        List<Observation> observations = new ArrayList<>();
        Consumer<ProcessorExecutionContext> action = context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.add("/child", child));
            } else if ("onInit".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            }
        };
        ClosureExecutionEvidenceStorageCodec storage = new ClosureExecutionEvidenceStorageCodec(16 * 1024 * 1024, 128);
        ClosureProcessResultStorageCodec results = new ClosureProcessResultStorageCodec(16 * 1024 * 1024, 128);
        byte[] invocationBytes, suspendedBytes, expectedBytes;
        String admittedBlueId;
        try (Fixture producer = new Fixture(action)) {
            admittedBlueId = producer.backingContracts.runtimeAccess().languageRuntime()
                    .calculateSourceDocumentBlueId(original.clone());
            ClosureInvocationInput input = producer.external(snapshot(parent, true), original, ROOT, admittedBlueId);
            invocationBytes = storage.encodeInvocation(input);
            ClosureAttemptResult missing = producer.attempt(input);
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, missing.kind());
            assertEquals(1, missing.resourceDemands().size());
            ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) missing.resourceDemands().get(0);
            suspendedBytes = storage.encodeAttempt(input, null, missing, demand);
            ClosureInvocationInput retry = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(demand, new DocumentId("cold-child"), child)));
            observations.clear();
            ClosureProcessResult resident = producer.process(retry);
            assertTrue(resident.commits(), diagnostic(resident));
            assertEquals(Arrays.asList("external", "onInit", "triggered"), handlers(observations));
            assertAdmittedCause(observations, original, admittedBlueId);
            expectedBytes = results.encode(resident);
        }
        observations.clear();
        // Neither the producer runtime nor its invocation/selected-demand objects survives this handoff.
        try (Fixture consumer = new Fixture(action)) {
            ClosureInvocationInput restored = storage.decodeInvocation(invocationBytes);
            ClosureExecutionEvidenceStorageCodec.StoredAttempt cold = storage.decodeAttempt(suspendedBytes);
            assertTrue(observations.isEmpty(), "Restoring an invocation or demand must not execute handlers");
            assertArrayEquals(invocationBytes, storage.encodeInvocation(restored));
            assertEquals(restored.invocationIdentity(), cold.input().invocationIdentity());
            assertSame(cold.attempt().resourceDemands().get(0), cold.selectedDemand());
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, consumer.attempt(restored).kind());
            assertAdmittedCause(observations, original, admittedBlueId);
            observations.clear();
            ClosureInvocationInput retry = ClosureEvidenceFactory.withProspectiveBirths(cold.input(),
                    Collections.singletonList(new ManagedDocumentBirth((ManagedOccurrenceEvidenceDemand) cold.selectedDemand(),
                            new DocumentId("cold-child"), child)));
            ClosureInvocationInput coldRetry = storage.decodeInvocation(storage.encodeInvocation(retry));
            assertTrue(observations.isEmpty(), "Preparing the exact birth retry must not execute lifecycle reactions");
            ClosureProcessResult actual = consumer.process(coldRetry);
            assertTrue(actual.commits(), diagnostic(actual));
            assertEquals(Arrays.asList("external", "onInit", "triggered"), handlers(observations));
            assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                    observations.get(1).payload.getType().getBlueId());
            assertAdmittedCause(observations, original, admittedBlueId);
            assertArrayEquals(expectedBytes, results.encode(actual), "Cold retry preserves complete result, event order and gas");
        }
    }

    @Test
    void shouldRetainAdmittedCauseAcrossColdEmbeddedDeliveryAndParentUpdate() {
        DocumentId childId = new DocumentId("cold-embedded-child");
        Node child = initialized(document().name("Cold embedded child"));
        Node parent = document().properties("child", new Node().blueId(id(child)));
        parent.getContracts().properties("embedded", processEmbedded("/child"))
                .properties("fromChild", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                        .properties("sourcePath", new Node().value("/child")))
                .properties("onChild", handler("fromChild").properties("event", event("ping")));
        initialized(parent);
        Node original = event("go").type(new Node().name("Cold embedded processing event type"))
                .properties("values", new Node().items(Arrays.asList(new Node().value("first"), new Node().value("second"))));
        List<Observation> observations = new ArrayList<>();
        Consumer<ProcessorExecutionContext> action = context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) context.emitEvent(event("ping"));
            else if ("onChild".equals(context.contractKey())) context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
        };
        ClosureExecutionEvidenceStorageCodec storage = new ClosureExecutionEvidenceStorageCodec(16 * 1024 * 1024, 128);
        ClosureProcessResultStorageCodec results = new ClosureProcessResultStorageCodec(16 * 1024 * 1024, 128);
        byte[] invocationBytes, expectedBytes;
        String admittedBlueId;
        try (Fixture producer = new Fixture(action)) {
            ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(
                    producer.environment.managedBindingPolicyIdentity(), ROOT, ScopeAddress.embedded("/child", 1L),
                    childId, id(child), true, null);
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            bodies.put(ROOT, parent); bodies.put(childId, child);
            admittedBlueId = producer.backingContracts.runtimeAccess().languageRuntime()
                    .calculateSourceDocumentBlueId(original.clone());
            ClosureInvocationInput input = producer.external(snapshot(bodies, Collections.singletonList(binding), ROOT,
                    Collections.emptyMap()), original, childId, admittedBlueId);
            invocationBytes = storage.encodeInvocation(input);
            ClosureProcessResult resident = producer.process(input);
            assertTrue(resident.commits(), diagnostic(resident));
            assertEquals(Arrays.asList("external", "triggered", "onChild", "updated"), handlers(observations));
            assertAdmittedCause(observations, original, admittedBlueId);
            expectedBytes = results.encode(resident);
        }
        observations.clear();
        try (Fixture consumer = new Fixture(action)) {
            ClosureInvocationInput restored = storage.decodeInvocation(invocationBytes);
            assertTrue(observations.isEmpty(), "Cold storage restoration must not execute embedded reactions");
            assertArrayEquals(invocationBytes, storage.encodeInvocation(restored));
            ClosureProcessResult actual = consumer.process(restored);
            assertTrue(actual.commits(), diagnostic(actual));
            assertEquals(Arrays.asList("external", "triggered", "onChild", "updated"), handlers(observations));
            assertEquals("/child", observations.get(2).payload.getNode("/sourcePath").getValue());
            assertEquals("/count", observations.get(3).payload.getNode("/path").getValue());
            assertAdmittedCause(observations, original, admittedBlueId);
            assertArrayEquals(expectedBytes, results.encode(actual), "Cold processing preserves complete result, event order and gas");
        }
    }

    @Test
    void shouldHaveNoProcessingEventDuringSeparateAdmissionEvenAfterExternalProcessing() {
        // given
        List<Observation> observations = new ArrayList<>();
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("onInit".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            } else if ("triggered".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
            }
        })) {
            ClosureProcessResult external = fixture.process(fixture.external(snapshot(document(), true), event("go")));
            observations.clear();

            // when
            ClosureInvocationInput admission = ClosureEvidenceFactory.admitClosure(snapshot(document(), false),
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                            "processing-event-admission", id(event("go")), null, "processing-event-admission-policy"),
                    null, policy(), fixture.environment);
            ClosureProcessResult result;
            try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                result = contracts.admitClosureWithLifecycleQueue(admission).processResult();
            }

            // then
            assertTrue(external.commits(), diagnostic(external));
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("onInit", "triggered", "updated"), handlers(observations));
            for (Observation observation : observations) {
                assertFalse(observation.present, observation.handler);
                assertNull(observation.cause, observation.handler);
                assertNull(observation.identityEvidence, observation.handler);
            }
        }
    }

    @Test
    void shouldKeepTheCauseImmutableAcrossMultipleInternalHops() {
        // given
        List<Observation> observations = new ArrayList<>();
        Node original = event("go");
        Node callerEvent = original.clone();
        Node document = document();
        document.getContracts().properties("onPong", handler("trigger").properties("event", event("pong")));
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            context.event().properties("kind", new Node().value("mutated payload copy"));
            context.frozenProcessEvent().toNode().properties("kind", new Node().value("mutated cause copy"));
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            } else if ("triggered".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
            } else if ("updated".equals(context.contractKey())) {
                context.emitEvent(event("pong"));
            }
        })) {
            ClosureInvocationInput input = fixture.external(snapshot(document, true), callerEvent);
            callerEvent.properties("kind", new Node().value("mutated caller input"));

            // when
            ClosureProcessResult result = fixture.process(input);

            // then
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered", "updated", "onPong"), handlers(observations));
            assertEquals("pong", observations.get(3).payload.getNode("/kind").getValue());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldKeepAnAdaptedExternalPayloadSeparateFromTheOriginalCause() {
        // given
        List<Observation> observations = new ArrayList<>();
        Node original = event("go");
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            }
        }, ignored -> event("adapted"))) {
            // when
            ClosureProcessResult result = fixture.process(fixture.external(snapshot(document(), true), original));

            // then
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered"), handlers(observations));
            assertEquals("adapted", observations.get(0).payload.getNode("/kind").getValue());
            assertEquals("ping", observations.get(1).payload.getNode("/kind").getValue());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldRetainOriginalCauseDuringTerminationLifecycle() {
        // given
        List<Observation> observations = new ArrayList<>();
        Node original = event("finish");
        Node document = document();
        document.getContracts().properties("onTerminate", handler("lifecycle")
                .properties("event", typed(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED)));
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.terminateGracefully("finished");
            }
        })) {
            // when
            ClosureProcessResult result = fixture.process(fixture.external(snapshot(document, true), original));

            // then
            assertTrue(result.commits(), diagnostic(result));
            assertTrue(result.resultingDocuments().get(0).terminated());
            assertEquals(Arrays.asList("external", "onTerminate"), handlers(observations));
            assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
                    observations.get(1).payload.getType().getBlueId());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldReplayARejectedClosureWithTheSameCauseDiagnosticGasAndRollback() {
        // given
        List<Observation> observations = new ArrayList<>();
        Node original = event("go");
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
                context.emitEvent(event("ping"));
            } else if ("triggered".equals(context.contractKey())) {
                throw new IllegalStateException("processing-event-regression-failure");
            }
        })) {
            ClosureInvocationInput input = fixture.external(snapshot(document(), true), original);

            // when
            ClosureProcessResult first = fixture.process(input);
            List<Observation> firstObservations = new ArrayList<>(observations);
            observations.clear();
            ClosureProcessResult replay = fixture.process(input);

            // then
            assertEquals(ProcessorStatus.RUNTIME_FATAL, first.status());
            assertEquals(first.status(), replay.status());
            assertTrue(first.diagnostic().message().contains("processing-event-regression-failure"));
            assertEquals(first.diagnostic().category(), replay.diagnostic().category());
            assertEquals(first.diagnostic().message(), replay.diagnostic().message());
            assertEquals(first.diagnostic().details(), replay.diagnostic().details());
            assertTrue(handlers(observations).contains("triggered"));
            assertEquals(handlers(firstObservations), handlers(observations));
            assertCause(firstObservations, original);
            assertCause(observations, original);
            assertRollback(input, first);
            assertRollback(input, replay);
            assertTrue(first.totalGas() > 0L);
            assertEquals(first.totalGas(), replay.totalGas());
            assertEquals(first.gasTraceIdentity(), replay.gasTraceIdentity());
        }
    }

    @Test
    void shouldKeepGasAndReplayIdenticalWhenTheOriginalEventIsReadRepeatedly() {
        // given
        AtomicBoolean readCause = new AtomicBoolean();
        List<Observation> observations = new ArrayList<>();
        Node original = event("go");
        try (Fixture fixture = new Fixture(context -> {
            if (readCause.get()) {
                for (int read = 0; read < 5; read++) {
                    observations.add(new Observation(context));
                }
            }
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            } else if ("triggered".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
            }
        })) {
            ClosureInvocationInput input = fixture.external(snapshot(document(), true), original);

            // when
            ClosureProcessResult cold = fixture.process(input);
            readCause.set(true);
            ClosureProcessResult warm = fixture.process(input);
            List<Observation> warmObservations = new ArrayList<>(observations);
            observations.clear();
            ClosureProcessResult replay = fixture.process(input);

            // then
            assertTrue(cold.commits(), diagnostic(cold));
            assertTrue(warm.commits(), diagnostic(warm));
            assertTrue(replay.commits(), diagnostic(replay));
            assertEquals(15, warmObservations.size());
            assertEquals(handlers(warmObservations), handlers(observations));
            assertCause(warmObservations, original);
            assertCause(observations, original);
            assertEquals(cold.totalGas(), warm.totalGas());
            assertEquals(cold.totalGas(), replay.totalGas());
            assertEquals(cold.gasTraceIdentity(), warm.gasTraceIdentity());
            assertEquals(cold.gasTraceIdentity(), replay.gasTraceIdentity());
            assertEquals(cold.resultingDocuments().get(0).afterBlueId(), warm.resultingDocuments().get(0).afterBlueId());
            assertEquals(cold.resultingDocuments().get(0).afterBlueId(), replay.resultingDocuments().get(0).afterBlueId());
            assertEquals(cold.managedTransitionReceipts().get(0).transitionReceiptIdentity(),
                    warm.managedTransitionReceipts().get(0).transitionReceiptIdentity());
            assertEquals(cold.managedTransitionReceipts().get(0).transitionReceiptIdentity(),
                    replay.managedTransitionReceipts().get(0).transitionReceiptIdentity());
        }
    }

    @Test
    void shouldUseEachOriginalEventInSequentialInvocationsOnTheSameProcessor() {
        // given
        List<Observation> observations = new ArrayList<>();
        Node firstEvent = event("go").properties("caller", new Node().value("first"));
        Node secondEvent = event("go").properties("caller", new Node().value("second"));
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            } else if ("triggered".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
            }
        })) {
            AffectedClosureSnapshot before = snapshot(document(), true);
            ClosureInvocationInput first = fixture.external(before, firstEvent);
            ClosureInvocationInput second = fixture.external(before, secondEvent);

            // when
            ClosureProcessResult firstResult = fixture.process(first);
            List<Observation> firstObservations = new ArrayList<>(observations);
            observations.clear();
            ClosureProcessResult secondResult = fixture.process(second);

            // then
            assertTrue(firstResult.commits(), diagnostic(firstResult));
            assertTrue(secondResult.commits(), diagnostic(secondResult));
            assertNotEquals(id(firstEvent), id(secondEvent));
            assertEquals(Arrays.asList("external", "triggered", "updated"), handlers(firstObservations));
            assertEquals(handlers(firstObservations), handlers(observations));
            assertCause(firstObservations, firstEvent);
            assertCause(observations, secondEvent);
            assertNotSame(firstObservations.get(0).identityEvidence, observations.get(0).identityEvidence);
        }
    }

    @Test
    void shouldIsolateOriginalEventsInConcurrentInvocationsUsingTheSameProcessor() throws Exception {
        // given
        CyclicBarrier overlap = new CyclicBarrier(2);
        ConcurrentLinkedQueue<String> observed = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Fixture fixture = new Fixture(context -> {
            if ("external".equals(context.contractKey())) {
                try {
                    overlap.await(20, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("Concurrent invocations did not overlap", failure);
                }
                context.emitEvent(event("ping"));
            }
            String expected = context.documentAt("/expected").getValue().toString();
            String actual = context.frozenProcessEvent().property("kind").toNode().getValue().toString();
            observed.add(expected + ":" + actual + ":" + context.contractKey());
        })) {
            ClosureInvocationInput first = fixture.external(snapshot(document()
                    .properties("expected", new Node().value("first")), true), event("first"));
            ClosureInvocationInput second = fixture.external(snapshot(document()
                    .properties("expected", new Node().value("second")), true), event("second"));

            // when
            Future<ClosureProcessResult> one = executor.submit(() -> fixture.process(first));
            Future<ClosureProcessResult> two = executor.submit(() -> fixture.process(second));
            ClosureProcessResult firstResult = one.get(30, TimeUnit.SECONDS);
            ClosureProcessResult secondResult = two.get(30, TimeUnit.SECONDS);

            // then
            assertTrue(firstResult.commits(), diagnostic(firstResult));
            assertTrue(secondResult.commits(), diagnostic(secondResult));
            List<String> sorted = new ArrayList<>(observed);
            Collections.sort(sorted);
            assertEquals(Arrays.asList("first:first:external", "first:first:triggered",
                    "second:second:external", "second:second:triggered"), sorted);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRetainCanonicalIdentityForAListBearingCauseAcrossInternalSteps() {
        // given
        Node original = event("go").properties("values", new Node().items(Arrays.asList(
                new Node().value("first"), new Node().value("second"))));
        List<Observation> observations = new ArrayList<>();
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            }
        })) {
            // when
            ClosureProcessResult result = fixture.process(fixture.external(snapshot(document(), true), original));

            // then
            assertNotEquals(id(original), FrozenNode.fromResolvedNode(original).blueId());
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered"), handlers(observations));
            assertTrue(observations.get(1).cause.isStrictCanonical());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldRetainTheCauseAcrossEmbeddedDeliveryAndItsDocumentUpdate() {
        // given
        DocumentId childId = new DocumentId("child");
        Node child = initialized(document().name("Child"));
        Node parent = document().properties("child", new Node().blueId(id(child)))
                .properties("parentOnly", new Node().value(true));
        parent.getContracts().properties("embedded", processEmbedded("/child"))
                .properties("fromChild", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                        .properties("sourcePath", new Node().value("/child")))
                .properties("onChild", handler("fromChild").properties("event", event("ping")));
        initialized(parent);
        List<Observation> observations = new ArrayList<>();
        List<Boolean> childSeesParent = new ArrayList<>();
        List<Object> occurrenceKinds = new ArrayList<>();
        Node original = event("go");
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                childSeesParent.add(context.documentContains("/parentOnly"));
                context.emitEvent(event("ping"));
            } else if ("onChild".equals(context.contractKey())) {
                occurrenceKinds.add(context.occurrenceEvent().getNode("/kind").getValue());
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
            }
        })) {
            ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(
                    fixture.environment.managedBindingPolicyIdentity(), ROOT, ScopeAddress.embedded("/child", 1L),
                    childId, id(child), true, null);
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            bodies.put(ROOT, parent);
            bodies.put(childId, child);
            AffectedClosureSnapshot snapshot = snapshot(bodies, Collections.singletonList(binding), ROOT,
                    Collections.emptyMap());

            // when
            ClosureProcessResult result = fixture.process(fixture.external(snapshot, original, childId));

            // then
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Collections.singletonList(false), childSeesParent, "The child Root must remain isolated");
            assertEquals(Collections.singletonList("ping"), occurrenceKinds);
            assertEquals(Arrays.asList("external", "triggered", "onChild", "updated"), handlers(observations));
            assertEquals("/child", observations.get(2).payload.getNode("/sourcePath").getValue());
            assertEquals("/count", observations.get(3).payload.getNode("/path").getValue());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldNotReintroduceTheHistoricalExternalEventDuringManagedRevision() {
        // given
        List<Observation> observations = new ArrayList<>();
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey()) || "onChild".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            }
        })) {
            AffectedClosureSnapshot sourceBefore = snapshot(document(), true);
            ClosureInvocationInput sourceInput = fixture.external(sourceBefore, event("go"));
            ClosureProcessResult sourceResult = fixture.process(sourceInput);
            ResultingDocument sourceAfter = sourceResult.resultingDocuments().get(0);
            fixture.exact.put(sourceBefore.managedDocument(ROOT).blueId(), sourceBefore.managedDocument(ROOT).document());
            fixture.exact.put(sourceAfter.afterBlueId(), sourceAfter.document());
            fixture.exact.put(id(event("ping")), event("ping"));
            DocumentId consumerId = new DocumentId("consumer");
            Node consumer = document().properties("child", new Node().blueId(sourceBefore.managedDocument(ROOT).blueId()));
            consumer.getContracts().properties("embedded", processEmbedded("/child"))
                    .properties("fromChild", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                            .properties("sourcePath", new Node().value("/child")))
                    .properties("onChild", handler("fromChild").properties("event", event("ping")));
            initialized(consumer);
            ManagedOccurrenceBinding historical = ManagedOccurrenceBinding.derived(
                    fixture.environment.managedBindingPolicyIdentity(), consumerId, ScopeAddress.embedded("/child", 1L),
                    ROOT, sourceBefore.managedDocument(ROOT).blueId(), false, 0L);
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            bodies.put(consumerId, consumer);
            bodies.put(ROOT, sourceAfter.document());
            // The host atomically retains the successful source receipt at its next durable epoch.
            AffectedClosureSnapshot retained = snapshot(bodies, Collections.singletonList(historical), consumerId,
                    Collections.singletonMap(ROOT, sourceAfter.epoch() + 1L));
            ManagedRevisionCause revision = ClosureEvidenceFactory.managedRevisionCause(historical.occurrenceIdentity(),
                    0L, 1L, sourceAfter.document(), sourceResult.managedTransitionReceipts().get(0));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(retained, revision,
                    Collections.emptyList(), policy(), fixture.environment);
            observations.clear();

            // when
            ClosureProcessResult result = fixture.process(input);

            // then
            assertTrue(sourceResult.commits(), diagnostic(sourceResult));
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("onChild", "triggered"), handlers(observations));
            assertEquals(sourceInput.cause().causeIdentity(), revision.originalSourceCauseIdentity());
            for (Observation observation : observations) {
                assertFalse(observation.present, observation.handler);
                assertNull(observation.cause, observation.handler);
                assertNull(observation.identityEvidence, observation.handler);
            }
        }
    }

    private static final class Observation {
        private final String handler;
        private final Node payload;
        private final boolean present;
        private final FrozenNode cause;
        private final ExactEventIdentityEvidence identityEvidence;

        private Observation(ProcessorExecutionContext context) {
            handler = context.contractKey();
            payload = context.event().clone();
            present = context.hasProcessEvent();
            cause = context.frozenProcessEvent();
            identityEvidence = context.exactProcessEventIdentityEvidence();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final DocumentProcessor owner;
        private final ClosureEnvironment environment;
        private final BlueLanguage language;
        private final BlueContracts backingContracts;
        private final Map<String, Node> exact = new LinkedHashMap<>();

        private Fixture(Consumer<ProcessorExecutionContext> action) {
            this(action, Node::clone);
        }

        private Fixture(Consumer<ProcessorExecutionContext> action, UnaryOperator<Node> payload) {
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                    .register(CHANNEL_ID, CHANNEL, new TestChannelProcessor(payload))
                    .register(HANDLER_ID, HANDLER, new HandlerProcessor<TestHandler>() {
                        public Class<TestHandler> contractType() { return TestHandler.class; }
                        public boolean matches(TestHandler contract, HandlerMatchContext context) {
                            return context.matchesEventPattern(contract.getEvent());
                        }
                        public void execute(TestHandler contract, ProcessorExecutionContext context) {
                            action.accept(context);
                        }
                    }).build();
            exact.put(CHANNEL_ID, CHANNEL);
            exact.put(HANDLER_ID, HANDLER);
            NodeProvider provider = new SequentialNodeProvider(
                    key -> exact.containsKey(key) ? Collections.singletonList(exact.get(key).clone()) : Collections.emptyList(),
                    BlueRuntimeTypeRegistry.getDefault().asProvider());
            language = BlueLanguage.builder().nodeProvider(provider).build();
            backingContracts = BlueContracts.builder(language.processing()).runtimeRegistry(registry).build();
            owner = DocumentProcessor.builder().runtimeRegistry(registry)
                    .runtimeRegistryIdentity(registry.generationIdentity())
                    .runtimeAccess(backingContracts.runtimeAccess()).build();
            environment = ClosureEvidenceFactory.environment(owner, hash('a'), hash('b'),
                    "processing-event-documents", "processing-event-bindings", "processing-event-provider",
                    "processing-event-order", "processing-event-limits", GasSchedule.contracts10().portableLimits());
        }

        private ClosureInvocationInput external(AffectedClosureSnapshot snapshot, Node event) {
            return external(snapshot, event, ROOT);
        }

        private ClosureInvocationInput external(AffectedClosureSnapshot snapshot, Node event, DocumentId target) {
            return external(snapshot, event, target, id(event));
        }

        private ClosureInvocationInput external(AffectedClosureSnapshot snapshot, Node event,
                DocumentId target, String eventBlueId) {
            return ClosureEvidenceFactory.processClosure(snapshot,
                    ClosureEvidenceFactory.externalCause(event, eventBlueId,
                            ExternalOrderKey.of(Arrays.<Object>asList(1L, "timeline", 0L)),
                            environment.externalOrderPolicyIdentity()),
                    Collections.singletonList(new DirectLogicalDelivery(
                            ManagedScopeKey.root(target), "source", "source", 0L)),
                    policy(),
                    environment);
        }

        private ClosureProcessResult process(ClosureInvocationInput input) {
            ClosureAttemptResult attempt = attempt(input);
            assertTrue(attempt.isComplete(), attempt.kind() + " " + attempt.requiredExactBlueIds());
            return attempt.processResult();
        }

        private ClosureAttemptResult attempt(ClosureInvocationInput input) {
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
                return contracts.processClosure(input);
            }
        }

        public void close() {
            owner.close();
            backingContracts.close();
            language.close();
        }
    }

    private static AffectedClosureSnapshot snapshot(Node document, boolean initialized) {
        if (initialized) {
            initialized(document);
        }
        return snapshot(Collections.singletonMap(ROOT, document), Collections.emptyList(), ROOT, Collections.emptyMap());
    }

    private static AffectedClosureSnapshot snapshot(Map<DocumentId, Node> bodies,
            List<ManagedOccurrenceBinding> bindings, DocumentId publicRoot, Map<DocumentId, Long> epochs) {
        Map<DocumentId, Long> generations = new LinkedHashMap<>();
        bodies.keySet().forEach(documentId -> generations.put(documentId, 1L));
        ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                new ComponentFinalizationInput(ManagedDocumentGraph.fromBindings(bodies.keySet(), bindings),
                        generations, bodies, bindings));
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (FinalizedDocumentEvidence exact : finalized.documents().values()) {
            documents.add(new ManagedDocumentSnapshot(exact.documentId(), exact.blueId(), exact.document(),
                    exact.document().getContracts().getProperties().containsKey("initialized"), false,
                    exact.documentId().equals(publicRoot), epochs.getOrDefault(exact.documentId(), 0L),
                    exact.componentGeneration()));
        }
        return ClosureEvidenceFactory.affectedClosure(1L, documents, finalized.finalizedGraph().bindings(),
                finalized.components().stream().map(value -> value.component()).collect(Collectors.toList()),
                Collections.singletonList(publicRoot));
    }

    private static Node initialized(Node document) {
        document.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                .properties("document", new Node().blueId(id(document))));
        return document;
    }

    private static Node document() {
        return new Node().properties("count", new Node().value(0L))
                .contracts(new Node().properties("source", typed(CHANNEL_ID))
                        .properties("external", handler("source"))
                        .properties("trigger", typed(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL))
                        .properties("triggered", handler("trigger").properties("event", event("ping")))
                        .properties("update", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                                .properties("path", new Node().value("/count")))
                        .properties("updated", handler("update"))
                        .properties("lifecycle", typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))
                        .properties("onInit", handler("lifecycle")
                                .properties("event", typed(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED))));
    }

    private static Node event(String kind) {
        return new Node().properties("kind", new Node().value(kind))
                .properties("subscriptionKey", new Node().value(SUBSCRIPTION_KEY));
    }
    private static Node handler(String channel) {
        return typed(HANDLER_ID).properties("channel", new Node().value(channel));
    }
    private static Node processEmbedded(String path) {
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths",
                new Node().items(Collections.singletonList(new Node().value(path))));
    }
    private static Node typed(String blueId) { return new Node().type(new Node().blueId(blueId)); }
    private static String id(Node node) { return DirectBlueIdCalculator.calculateBlueId(node); }
    private static String hash(char value) {
        return "sha256:" + String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
    private static String diagnostic(ClosureProcessResult result) {
        return result.diagnostic() == null ? result.status().toString() : result.diagnostic().message();
    }
    private static ExecutionPolicy policy() {
        return ClosureEvidenceFactory.executionPolicy(100000L, Collections.emptyMap(), "processing-event-gas");
    }
    private static List<String> handlers(List<Observation> observations) {
        return observations.stream().map(value -> value.handler).collect(Collectors.toList());
    }
    private static void assertCause(List<Observation> observations, Node event) {
        assertFalse(observations.isEmpty(), "Expected handlers to execute");
        for (Observation observation : observations) {
            assertTrue(observation.present, observation.handler + " lost the processing event");
            assertNotNull(observation.cause, observation.handler);
            assertEquals(NodeWireForm.get(event), NodeWireForm.get(observation.cause.toNode()), observation.handler);
            assertEquals(id(event), observation.cause.blueId(), observation.handler);
            assertSame(observations.get(0).cause, observation.cause,
                    "Every step must retain the same admitted immutable cursor");
        }
        assertAdmittedCause(observations, event, id(event));
    }

    private static void assertAdmittedCause(List<Observation> observations, Node event, String admittedBlueId) {
        assertFalse(observations.isEmpty(), "Expected handlers to execute");
        for (Observation observation : observations) {
            assertNotNull(observation.identityEvidence, observation.handler + " lost the admitted cause identity");
            assertEquals(admittedBlueId, observation.identityEvidence.eventBlueId(), observation.handler);
            assertEquals(NodeWireForm.get(event), NodeWireForm.get(observation.identityEvidence.event()),
                    observation.handler);
            assertSame(observation.cause, observation.identityEvidence.frozenEvent());
            assertSame(observations.get(0).identityEvidence, observation.identityEvidence,
                    "Every caused step must share the invocation-admitted identity capability");
        }
    }
    private static void assertRollback(ClosureInvocationInput input, ClosureProcessResult result) {
        assertFalse(result.commits());
        assertTrue(result.publicEvents().isEmpty());
        assertTrue(result.managedTransitionReceipts().isEmpty());
        assertNull(result.commitCompanion());
        for (ResultingDocument document : result.resultingDocuments()) {
            ManagedDocumentSnapshot before = input.snapshot().managedDocument(document.documentId());
            assertEquals(before.blueId(), document.afterBlueId());
            assertEquals(NodeWireForm.get(before.document()), NodeWireForm.get(document.document()));
        }
    }

    public static final class TestHandler extends HandlerContract { }
    public static final class TestChannel extends ChannelContract { }
    private static final class TestChannelProcessor implements ChannelProcessor<TestChannel> {
        private final UnaryOperator<Node> payload;
        private TestChannelProcessor(UnaryOperator<Node> payload) { this.payload = payload; }
        public Class<TestChannel> contractType() { return TestChannel.class; }
        public ExternalChannelSubscriptionFunctions<TestChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<TestChannel>() {
                public List<String> channelKeys(TestChannel channel) { return Collections.singletonList(SUBSCRIPTION_KEY); }
                public Node payload(TestChannel channel, Node event) { return payload.apply(event); }
                public String checkpointDomainDiscriminator(TestChannel channel) { return "processing-event-v1"; }
            };
        }
    }
}
