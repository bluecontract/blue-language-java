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

    @Test
    void shouldRetainAdmittedSourceIdentityAcrossInternalStepsForAnInlineTypedEvent() {
        // Given a valid Source event whose frozen representation has a different identity.
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
            assertNotEquals(admittedBlueId, FrozenNode.fromResolvedNode(original).blueId());
            ClosureInvocationInput input = fixture.external(
                    snapshot(document(), true), original, ROOT, admittedBlueId);

            // When the original event causes external, triggered and update deliveries.
            ClosureProcessResult result = fixture.process(input);

            // Then consumers retain the proved identity together with the unchanged Source cursor.
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered", "updated"), handlers(observations));
            assertAdmittedCause(observations, original, admittedBlueId);
            assertEquals("ping", observations.get(1).payload.getNode("/kind").getValue());
            assertEquals("/count", observations.get(2).payload.getNode("/path").getValue());
        }
    }

    @Test
    void shouldPreserveOriginalEventAnnotationsWithoutUsingThemAsTheAdmittedIdentity() {
        // Given an accepted expanded event carrying non-authoritative identity annotations.
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

            // When its external and internal handlers observe the original input.
            ClosureProcessResult result = fixture.process(input);

            // Then the exact Source is preserved while the verified identity stays independent.
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered"), handlers(observations));
            assertAdmittedCause(observations, original, admittedBlueId);
            assertNotEquals(annotation, observations.get(1).identityEvidence.eventBlueId());
        }
    }

    @Test
    void shouldRetainOriginalEventAcrossTriggeredAndDocumentUpdateSteps() {
        // Given an external handler that emits an event whose handler updates the document.
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

            // When the complete closure runs through all three real document-step boundaries.
            ClosureProcessResult result = fixture.process(input);

            // Then routing and immediate payloads stay distinct while the cause stays original.
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
        // Given an external handler that creates a child with an initialization handler.
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

            // When a resource retry reserves the child lineage and repeats the invocation.
            ClosureAttemptResult missing = fixture.attempt(input);
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, missing.kind());
            assertEquals(1, missing.resourceDemands().size());
            assertNull(missing.processResult());
            ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) missing.resourceDemands().get(0);
            ClosureInvocationInput retry = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(demand, new DocumentId("new-child"), child)));
            observations.clear();
            ClosureProcessResult result = fixture.process(retry);

            // Then the child sees the cause in lifecycle/internal work but receives no new direct delivery.
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "onInit", "triggered"), handlers(observations));
            assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                    observations.get(1).payload.getType().getBlueId());
            assertCause(observations, event);
        }
    }

    @Test
    void shouldHaveNoProcessingEventDuringSeparateAdmissionEvenAfterExternalProcessing() {
        // Given a reused processor that has already completed an external invocation.
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
            assertTrue(external.commits(), diagnostic(external));
            observations.clear();

            // When a separate admission has only a historical triggering-event identity.
            ClosureInvocationInput admission = ClosureEvidenceFactory.admitClosure(snapshot(document(), false),
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                            "processing-event-admission", id(event("go")), null, "processing-event-admission-policy"),
                    null, policy(), fixture.environment);
            ClosureProcessResult result;
            try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                result = contracts.admitClosureWithLifecycleQueue(admission).processResult();
            }

            // Then no prior event leaks into lifecycle or its caused internal work.
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
        // Given a caller-owned event and a second triggered hop caused by a document update.
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

            // When the closure completes four successive handler steps.
            ClosureProcessResult result = fixture.process(input);

            // Then detached mutable copies cannot change the original cause or matching payloads.
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered", "updated", "onPong"), handlers(observations));
            assertEquals("pong", observations.get(3).payload.getNode("/kind").getValue());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldKeepAnAdaptedExternalPayloadSeparateFromTheOriginalCause() {
        // Given an External Channel whose PAYLOAD function transforms its input.
        List<Observation> observations = new ArrayList<>();
        Node original = event("go");
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            }
        }, ignored -> event("adapted"))) {
            // When the external delivery and its triggered continuation execute.
            ClosureProcessResult result = fixture.process(fixture.external(snapshot(document(), true), original));

            // Then each handler gets its proper payload and both get the unadapted cause.
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered"), handlers(observations));
            assertEquals("adapted", observations.get(0).payload.getNode("/kind").getValue());
            assertEquals("ping", observations.get(1).payload.getNode("/kind").getValue());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldRetainOriginalCauseDuringTerminationLifecycle() {
        // Given an external handler that terminates its Root.
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
            // When the caused termination lifecycle runs.
            ClosureProcessResult result = fixture.process(fixture.external(snapshot(document, true), original));

            // Then termination remains a committing lifecycle effect with the original cause.
            assertTrue(result.commits(), diagnostic(result));
            assertTrue(result.resultingDocuments().get(0).terminated());
            assertEquals(Arrays.asList("external", "onTerminate"), handlers(observations));
            assertEquals(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
                    observations.get(1).payload.getType().getBlueId());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldRollBackAllEffectsWhenACausedHandlerFails() {
        // Given earlier document writes and emissions in the same external invocation.
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

            // When a later handler fails after accessing the original event.
            ClosureProcessResult result = fixture.process(input);

            // Then the entire closure rolls back while admitted gas remains visible.
            assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
            assertTrue(result.diagnostic().message().contains("processing-event-regression-failure"));
            assertTrue(handlers(observations).contains("triggered"));
            assertCause(observations, original);
            assertRollback(input, result);
            assertTrue(result.totalGas() > 0L);
        }
    }

    @Test
    void shouldKeepGasAndReplayIdenticalWhenTheOriginalEventIsReadRepeatedly() {
        // Given identical invocations, first without observing the cause and then with repeated reads.
        AtomicBoolean readCause = new AtomicBoolean();
        try (Fixture fixture = new Fixture(context -> {
            if (readCause.get()) {
                for (int read = 0; read < 5; read++) {
                    assertTrue(context.hasProcessEvent());
                    assertEquals("go", context.frozenProcessEvent().property("kind").toNode().getValue());
                }
            }
            if ("external".equals(context.contractKey())) {
                context.emitEvent(event("ping"));
            } else if ("triggered".equals(context.contractKey())) {
                context.applyPatch(JsonPatch.replace("/count", new Node().value(1L)));
            }
        })) {
            ClosureInvocationInput input = fixture.external(snapshot(document(), true), event("go"));
            ClosureProcessResult cold = fixture.process(input);

            // When the same input is replayed on the warmed processor and the cause is observed.
            readCause.set(true);
            ClosureProcessResult warm = fixture.process(input);

            // Then carrying/reading the immutable cursor adds no semantic gas or result changes.
            assertTrue(cold.commits(), diagnostic(cold));
            assertTrue(warm.commits(), diagnostic(warm));
            assertEquals(cold.totalGas(), warm.totalGas());
            assertEquals(cold.gasTraceIdentity(), warm.gasTraceIdentity());
            assertEquals(cold.resultingDocuments().get(0).afterBlueId(), warm.resultingDocuments().get(0).afterBlueId());
            assertEquals(cold.managedTransitionReceipts().get(0).transitionReceiptIdentity(),
                    warm.managedTransitionReceipts().get(0).transitionReceiptIdentity());
        }
    }

    @Test
    void shouldIsolateOriginalEventsInConcurrentInvocationsUsingTheSameProcessor() throws Exception {
        // Given two external invocations that overlap inside the same configured processor.
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
            assertEquals(expected, context.frozenProcessEvent().property("kind").toNode().getValue());
            observed.add(expected + ":" + context.contractKey());
        })) {
            ClosureInvocationInput first = fixture.external(snapshot(document()
                    .properties("expected", new Node().value("first")), true), event("first"));
            ClosureInvocationInput second = fixture.external(snapshot(document()
                    .properties("expected", new Node().value("second")), true), event("second"));

            // When both closures cross from external delivery into triggered work.
            Future<ClosureProcessResult> one = executor.submit(() -> fixture.process(first));
            Future<ClosureProcessResult> two = executor.submit(() -> fixture.process(second));
            ClosureProcessResult firstResult = one.get(30, TimeUnit.SECONDS);
            ClosureProcessResult secondResult = two.get(30, TimeUnit.SECONDS);

            // Then neither invocation can observe the other's cause.
            assertTrue(firstResult.commits(), diagnostic(firstResult));
            assertTrue(secondResult.commits(), diagnostic(secondResult));
            List<String> sorted = new ArrayList<>(observed);
            Collections.sort(sorted);
            assertEquals(Arrays.asList("first:external", "first:triggered", "second:external", "second:triggered"), sorted);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRetainCanonicalIdentityForAListBearingCauseAcrossInternalSteps() {
        // Given a Source event whose canonical identity differs from hashing a resolved cursor.
        Node original = event("go").properties("values", new Node().items(Arrays.asList(
                new Node().value("first"), new Node().value("second"))));
        assertNotEquals(id(original), FrozenNode.fromResolvedNode(original).blueId());
        List<Observation> observations = new ArrayList<>();
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) context.emitEvent(event("ping"));
        })) {
            // When the admitted event crosses the isolated triggered step boundary.
            ClosureProcessResult result = fixture.process(fixture.external(snapshot(document(), true), original));

            // Then the canonical cursor and its proved BlueId survive without reconstruction.
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered"), handlers(observations));
            assertTrue(observations.get(1).cause.isStrictCanonical());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldRetainTheCauseAcrossEmbeddedDeliveryAndItsDocumentUpdate() {
        // Given a managed child that emits a Ping and a parent that reacts through an Embedded Channel.
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
        Node original = event("go");
        try (Fixture fixture = new Fixture(context -> {
            observations.add(new Observation(context));
            if ("external".equals(context.contractKey())) {
                assertFalse(context.documentContains("/parentOnly"), "The child Root must remain isolated");
                context.emitEvent(event("ping"));
            } else if ("onChild".equals(context.contractKey())) {
                assertEquals("ping", context.occurrenceEvent().getNode("/kind").getValue());
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

            // When only the child receives the external delivery.
            ClosureProcessResult result = fixture.process(fixture.external(snapshot, original, childId));

            // Then embedded and update handlers keep their local payloads and the child's original cause.
            assertTrue(result.commits(), diagnostic(result));
            assertEquals(Arrays.asList("external", "triggered", "onChild", "updated"), handlers(observations));
            assertEquals("/child", observations.get(2).payload.getNode("/sourcePath").getValue());
            assertEquals("/count", observations.get(3).payload.getNode("/path").getValue());
            assertCause(observations, original);
        }
    }

    @Test
    void shouldNotReintroduceTheHistoricalExternalEventDuringManagedRevision() {
        // Given a real source receipt produced by an earlier external invocation on this processor.
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
            assertTrue(sourceResult.commits(), diagnostic(sourceResult));
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

            // When a separate managed revision replays the retained event to its consumer.
            ClosureProcessResult result = fixture.process(input);

            // Then the historical cause identity is audit evidence, not a live processing event.
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
        if (initialized) initialized(document);
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
                .properties("subscriptionKey", new Node().value("test"));
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
                public List<String> channelKeys(TestChannel channel) { return Collections.singletonList("test"); }
                public Node payload(TestChannel channel, Node event) { return payload.apply(event); }
                public String checkpointDomainDiscriminator(TestChannel channel) { return "processing-event-v1"; }
            };
        }
    }
}
