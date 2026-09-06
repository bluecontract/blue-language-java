package blue.language.processor.closure;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.*;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguageRuntime;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.bytes;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.json;

/** Actual producer lifecycle execution and cold interpretation, never fabricated terminal receipts. */
class FreshTerminationObservationTest {
    private static final DocumentId SOURCE = new DocumentId("a-source");
    private static final DocumentId OBSERVER = new DocumentId("b-observer");
    private static final DocumentId HEALTHY = new DocumentId("c-healthy");
    private static final Node CHANNEL = new Node().name("Fresh termination external channel");
    private static final Node HANDLER = new Node().name("Fresh termination handler");
    private static final String CHANNEL_ID = id(CHANNEL), HANDLER_ID = id(HANDLER);
    private static final String SOURCE_PLACEHOLDER = id(new Node().name("source-placeholder"));
    private static final String OBSERVER_PLACEHOLDER = id(new Node().name("observer-placeholder"));

    @Test
    void externalLifecyclePrecedesMarkerAndColdSourceReplayPreservesEveryObserverView() {
        verifySourceTermination(Mode.SOURCE_TERMINATES);
    }

    @Test
    void twoDirectChannelsRetainTheCutOffAcceptedIdentityWithoutExecutingASecondStep() {
        verifySourceTermination(Mode.TWO_DIRECT_CHANNELS);
    }

    private void verifySourceTermination(Mode mode) {
        try (Fixture fixture = new Fixture(mode)) {
            SameOriginProcessAttempt fresh = fixture.contracts.processSameOrigin(fixture.input);
            assertTrue(fresh.complete(), () -> "Needs " + fresh.resourceDemands());
            SameOriginOperationResult source = operation(fresh, SOURCE);
            SameOriginOperationResult observer = operation(fresh, OBSERVER);
            assertTrue(fixture.evidence.complete());
            assertEquals(mode == Mode.TWO_DIRECT_CHANNELS ? 1 : 0, fixture.evidence.skippedWorkTrace().size());
            assertEquals(ProcessorStatus.SUCCESS, source.status(), () -> source.failure().map(value -> value.diagnostic().message()).orElse("none"));
            assertEquals(ProcessorStatus.SUCCESS, observer.status(), () -> observer.failure().map(value -> value.diagnostic().message()).orElse("none"));
            assertTrue(document(source, SOURCE).terminated());
            assertEquals(1L, document(source, SOURCE).epoch());
            assertTrue(source.checkpointWrites().isEmpty(), "A terminated receiver writes no source checkpoint");
            Node seen = document(observer, OBSERVER).document();
            assertEquals(BigInteger.ONE, seen.getNode("/seen").getValue());
            assertEquals(Boolean.FALSE, seen.getNode("/markerAtEvent").getValue());
            assertEquals(Boolean.TRUE, seen.getNode("/markerAtRead").getValue());
            assertEquals(BigInteger.valueOf(2), seen.getNode("/valueAtEvent").getValue());
            SourceObservationProgram program = source.sourceProgram().orElseThrow(AssertionError::new);
            assertEquals(1L, program.steps().stream().flatMap(step -> step.actions().stream())
                    .filter(action -> action instanceof SourceObservationProgram.TerminationRequest).count());
            assertEquals(1L, program.steps().stream().filter(step -> step.kind() == WorkKind.LIFECYCLE).count());
            assertEquals(BigInteger.ZERO, document(source, SOURCE).document().getNode("/late").getValue());
            assertEquals(mode == Mode.TWO_DIRECT_CHANNELS ? 1 : 0, program.skippedWork().size());
            if (mode == Mode.TWO_DIRECT_CHANNELS) {
                SourceObservationProgram.SkippedWork skipped = program.skippedWork().get(0);
                assertEquals(WorkKind.EXTERNAL_DELIVERY, skipped.kind());
                assertEquals("zExternal", skipped.channelKey());
                assertFalse(program.steps().stream().anyMatch(step -> step.workIdentity().equals(skipped.workIdentity())));
            }
            int sourceExecutions = fixture.sourceExecutions;
            SourceObservationProgram cold = cold(program);

            // Real committed terminal source head with the consumer's exact prior read pin.
            ResultingDocument after = document(source, SOURCE);
            List<ManagedDocumentSnapshot> states = new ArrayList<>();
            states.add(new ManagedDocumentSnapshot(SOURCE, after.afterBlueId(), after.document(),
                    after.initialized(), after.terminated(), true, after.epoch(), after.componentGeneration()));
            states.add(fixture.input.snapshot().managedDocument(OBSERVER));
            ManagedDocumentSnapshot before = fixture.input.snapshot().managedDocument(SOURCE);
            AffectedClosureSnapshot laterSource = fixture.snapshot(states, fixture.input.snapshot().occurrences(),
                    Collections.singletonList(ManagedReadPin.fromExactEvidence(SOURCE, before.blueId(), before.document(), null)));
            ClosureInvocationInput replayInput = ClosureEvidenceFactory.processClosure(laterSource, fixture.input.cause(),
                    fixture.input.directDeliveries(), fixture.input.executionPolicy(), fixture.input.environment());
            SameOriginProcessAttempt replay = fixture.contracts.processSameOrigin(replayInput,
                    SameOriginAttachmentPolicy.empty(), Collections.singletonList(cold), Collections.emptyMap(), Collections.emptyList());
            assertTrue(replay.complete(), () -> "Needs " + replay.requiredExactBlueIds() + replay.resourceDemands());
            assertTrue(fixture.evidence.complete());
            assertEquals(1, replay.operations().size());
            SameOriginOperationResult replayed = operation(replay, OBSERVER);
            assertEquals(observer.operationIdentity(), replayed.operationIdentity());
            assertEquals(observer.gasTraceIdentity(), replayed.gasTraceIdentity());
            assertEquals(document(observer, OBSERVER).afterBlueId(), document(replayed, OBSERVER).afterBlueId());
            assertEquals(sourceExecutions, fixture.sourceExecutions, "Neither producer handler nor lifecycle handler reruns");
        }
    }

    @Test
    void joinedLifecycleFailureRollsBackTerminatingStateAndEveryOwnedMember() {
        try (Fixture fixture = new Fixture(Mode.JOINED_FATAL)) {
            SameOriginProcessAttempt failed = fixture.contracts.processSameOrigin(fixture.input);
            assertTrue(failed.complete());
            assertEquals(1, failed.operations().size());
            SameOriginOperationResult group = failed.operations().get(0);
            assertEquals(new TreeSet<>(Arrays.asList(SOURCE, OBSERVER)), group.ownedDocumentIds());
            assertEquals(ProcessorStatus.RUNTIME_FATAL, group.status());
            assertTrue(group.events().isEmpty());
            assertTrue(group.checkpointWrites().isEmpty());
            for (ResultingDocument result : group.resultingDocuments()) {
                assertEquals(fixture.input.snapshot().managedDocument(result.documentId()).blueId(), result.afterBlueId());
                assertEquals(0L, result.epoch());
                assertFalse(result.terminated());
            }
            assertEquals(2, fixture.sourceExecutions, "Real request and lifecycle work ran before consumer failure");
        }
    }

    @Test
    void terminatingObserverDoesNotTruncateIndependentSourceOrHealthyObserver() {
        verifyObserverTermination(Mode.OBSERVER_TERMINATES);
    }

    @Test
    void preacceptedEmbeddedChannelsCutOffOnlyTheTerminatingReceiverAndReplayTheExactSkippedIdentity() {
        verifyObserverTermination(Mode.TWO_EMBEDDED_CHANNELS);
    }

    private void verifyObserverTermination(Mode mode) {
        try (Fixture fixture = new Fixture(mode)) {
            SameOriginProcessAttempt attempt = fixture.contracts.processSameOrigin(fixture.input);
            assertTrue(attempt.complete());
            assertEquals(3, attempt.operations().size());
            SameOriginOperationResult source = operation(attempt, SOURCE);
            SameOriginOperationResult stopped = operation(attempt, OBSERVER);
            SameOriginOperationResult healthy = operation(attempt, HEALTHY);
            assertTrue(fixture.evidence.complete());
            assertEquals(mode == Mode.TWO_EMBEDDED_CHANNELS ? 1 : 0, fixture.evidence.skippedWorkTrace().size());
            assertEquals(ProcessorStatus.SUCCESS, source.status());
            assertFalse(document(source, SOURCE).terminated());
            assertTrue(document(stopped, OBSERVER).terminated());
            assertEquals(BigInteger.ONE, document(stopped, OBSERVER).document().getNode("/seen").getValue());
            assertEquals(BigInteger.valueOf(2), document(healthy, HEALTHY).document().getNode("/seen").getValue());
            assertEquals(BigInteger.ZERO, document(stopped, OBSERVER).document().getNode("/late").getValue());
            SourceObservationProgram stoppedProgram = stopped.sourceProgram().orElseThrow(AssertionError::new);
            assertEquals(mode == Mode.TWO_EMBEDDED_CHANNELS ? 1 : 0, stoppedProgram.skippedWork().size());
            int executions = fixture.sourceExecutions;
            SameOriginProcessAttempt reused = fixture.contracts.processSameOrigin(fixture.input,
                    SameOriginAttachmentPolicy.empty(), Collections.singletonList(cold(source.sourceProgram().orElseThrow(AssertionError::new))),
                    Collections.emptyMap(), Collections.emptyList());
            assertTrue(reused.complete());
            assertEquals(stopped.operationIdentity(), operation(reused, OBSERVER).operationIdentity());
            assertEquals(healthy.operationIdentity(), operation(reused, HEALTHY).operationIdentity());
            assertEquals(executions, fixture.sourceExecutions);
            if (mode == Mode.TWO_EMBEDDED_CHANNELS) {
                SameOriginProcessAttempt replayedBoth = fixture.contracts.processSameOrigin(fixture.input,
                        SameOriginAttachmentPolicy.empty(), Collections.singletonList(cold(stoppedProgram)),
                        Collections.emptyMap(), Collections.emptyList());
                assertTrue(replayedBoth.complete());
                assertTrue(fixture.evidence.complete());
                assertEquals(1, replayedBoth.operations().size());
                assertEquals(healthy.operationIdentity(), operation(replayedBoth, HEALTHY).operationIdentity());
                assertEquals(executions, fixture.sourceExecutions);
            }
        }
    }

    @Test
    void lifecycleDequeueIsChargedExactlyOnceAndExhaustionFailsItsInitiatingGroup() {
        try (Fixture fixture = new Fixture(Mode.SOURCE_TERMINATES)) {
            SameOriginOperationResult source = operation(fixture.contracts.processSameOrigin(fixture.input), SOURCE);
            SourceObservationProgram.Step lifecycle = source.sourceProgram().orElseThrow(AssertionError::new).steps().stream()
                    .filter(step -> step.kind() == WorkKind.LIFECYCLE).findFirst().orElseThrow(AssertionError::new);
            List<GasTraceEntry> dequeues = new ArrayList<>();
            for (GasTraceEntry entry : source.gasTrace()) if ("closureWorkOccurrenceDequeued".equals(entry.counter())
                    && lifecycle.workIdentity().equals(entry.workOccurrenceId())) dequeues.add(entry);
            assertEquals(1, dequeues.size(), "The grouped step owns its one lifecycle dequeue charge");
            long cap = prefix(source.gasTrace(), dequeues.get(0), SOURCE);
            SameOriginOperationResult failed = operation(fixture.contracts.processSameOrigin(withLocalCap(fixture.input, SOURCE, cap)), SOURCE);
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.status());
            SameOriginOperationResult.ChargeRejection rejected = failed.failure().orElseThrow(AssertionError::new)
                    .rejectedCharge().orElseThrow(AssertionError::new);
            assertEquals("closureWorkOccurrenceDequeued", rejected.counter());
            assertEquals(SOURCE.value(), rejected.localDocumentId());
            assertEquals(0L, rejected.remainingBeforeCharge());
            assertEquals(3, fixture.sourceExecutions, "The exhausted retry ran its request, not its lifecycle handler");
            assertFalse(document(failed, SOURCE).terminated());
            assertEquals(fixture.input.snapshot().managedDocument(SOURCE).blueId(), document(failed, SOURCE).afterBlueId());
        }
    }

    @Test
    void deferredConsumerMarkerExhaustionDoesNotFailTheOuterProducerOrHealthyObserver() {
        try (Fixture fixture = new Fixture(Mode.OBSERVER_TERMINATES)) {
            SameOriginOperationResult baseline = operation(fixture.contracts.processSameOrigin(fixture.input), OBSERVER);
            GasTraceEntry marker = baseline.gasTrace().stream().filter(entry -> "processorMarkerWritten".equals(entry.counter()))
                    .findFirst().orElseThrow(AssertionError::new);
            SameOriginProcessAttempt attempt = fixture.contracts.processSameOrigin(withLocalCap(fixture.input, OBSERVER,
                    prefix(baseline.gasTrace(), marker, OBSERVER)));
            assertTrue(attempt.complete());
            assertEquals(ProcessorStatus.SUCCESS, operation(attempt, SOURCE).status());
            assertEquals(ProcessorStatus.SUCCESS, operation(attempt, HEALTHY).status());
            assertEquals(BigInteger.valueOf(2), document(operation(attempt, HEALTHY), HEALTHY).document().getNode("/seen").getValue());
            SameOriginOperationResult failed = operation(attempt, OBSERVER);
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.status());
            SameOriginOperationResult.ChargeRejection rejected = failed.failure().orElseThrow(AssertionError::new)
                    .rejectedCharge().orElseThrow(AssertionError::new);
            assertEquals("processorMarkerWritten", rejected.counter());
            assertEquals(OBSERVER.value(), rejected.localDocumentId());
            assertEquals(OBSERVER.value(), rejected.chargeContext().documentId());
            assertFalse(document(failed, OBSERVER).terminated());
            assertEquals(fixture.input.snapshot().managedDocument(OBSERVER).blueId(), document(failed, OBSERVER).afterBlueId());
            assertTrue(failed.events().isEmpty());
        }
    }

    @Test
    void skippedWorkTransportRejectsMissingForeignDuplicateExtraAndPrematureSuppression() {
        try (Fixture fixture = new Fixture(Mode.TWO_DIRECT_CHANNELS)) {
            SourceObservationProgram program = operation(fixture.contracts.processSameOrigin(fixture.input), SOURCE)
                    .sourceProgram().orElseThrow(AssertionError::new);
            Map<String, byte[]> store = new HashMap<>();
            String identity = SourceObservationProgramCodec.encode(program, store::put, FrozenNodeEvidenceCodec.Limits.defaults());
            com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) json(store.get(identity));
            com.fasterxml.jackson.databind.node.ObjectNode missing = root.deepCopy(); missing.remove("skippedWork");
            assertThrows(InvalidExecutionEvidenceException.class, () -> decodeChanged(missing, store));
            com.fasterxml.jackson.databind.node.ObjectNode foreign = root.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) foreign.get("skippedWork").get(0)).put("target", HEALTHY.value());
            assertThrows(IllegalArgumentException.class, () -> decodeChanged(foreign, store));
            com.fasterxml.jackson.databind.node.ObjectNode duplicate = root.deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.get("skippedWork")).add(duplicate.get("skippedWork").get(0).deepCopy());
            assertThrows(IllegalArgumentException.class, () -> decodeChanged(duplicate, store));
            com.fasterxml.jackson.databind.node.ObjectNode omitted = root.deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode) omitted.get("skippedWork")).removeAll();
            assertThrows(IllegalArgumentException.class, () -> replay(fixture, decodeChanged(omitted, store)));
            com.fasterxml.jackson.databind.node.ObjectNode extra = root.deepCopy();
            com.fasterxml.jackson.databind.node.ObjectNode extraRow = ((com.fasterxml.jackson.databind.node.ObjectNode) extra.get("skippedWork").get(0)).deepCopy();
            extraRow.put("work", hash('e')); extraRow.put("channel", "never-selected");
            ((com.fasterxml.jackson.databind.node.ArrayNode) extra.get("skippedWork")).add(extraRow);
            assertThrows(IllegalArgumentException.class, () -> replay(fixture, decodeChanged(extra, store)));
            com.fasterxml.jackson.databind.node.ObjectNode premature = root.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) premature.get("skippedWork").get(0)).put("channel", "external");
            com.fasterxml.jackson.databind.node.ObjectNode later = ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("skippedWork").get(0)).deepCopy();
            later.put("work", hash('d'));
            ((com.fasterxml.jackson.databind.node.ArrayNode) premature.get("skippedWork")).add(later);
            com.fasterxml.jackson.databind.node.ObjectNode originalRequest = (com.fasterxml.jackson.databind.node.ObjectNode)
                    json(store.get(premature.get("steps").get(0).textValue()));
            originalRequest.put("channel", "not-selected");
            ((com.fasterxml.jackson.databind.node.ArrayNode) premature.get("steps")).set(0,
                    com.fasterxml.jackson.databind.node.TextNode.valueOf(retain(bytes(originalRequest), store)));
            IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                    () -> replay(fixture, decodeChanged(premature, store)));
            assertTrue(invalid.getMessage().contains("live document step"), invalid::getMessage);
        }
    }

    private static void replay(Fixture fixture, SourceObservationProgram source) {
        fixture.contracts.processSameOrigin(fixture.input, SameOriginAttachmentPolicy.empty(), Collections.singletonList(source),
                Collections.emptyMap(), Collections.emptyList());
    }
    private static SourceObservationProgram decodeChanged(com.fasterxml.jackson.databind.node.ObjectNode root, Map<String, byte[]> store) {
        return SourceObservationProgramCodec.decode(retain(bytes(root), store), store::get, FrozenNodeEvidenceCodec.Limits.defaults());
    }
    private static String retain(byte[] value, Map<String, byte[]> store) { String key = FrozenNodeEvidenceCodec.digest(value); store.put(key, value); return key; }
    private static long prefix(List<GasTraceEntry> trace, GasTraceEntry boundary, DocumentId owner) {
        long result = 0L;
        for (GasTraceEntry entry : trace) { if (entry == boundary) return result; if (owner.equals(entry.documentId())) result += entry.subtotal(); }
        throw new AssertionError("Boundary is outside the owning trace");
    }
    private static ClosureInvocationInput withLocalCap(ClosureInvocationInput input, DocumentId owner, long cap) {
        return ClosureEvidenceFactory.processClosure(input.snapshot(), input.cause(), input.directDeliveries(),
                ClosureEvidenceFactory.executionPolicy(100000L, Collections.singletonMap(owner, cap), "termination-policy"), input.environment());
    }

    private static SameOriginOperationResult operation(SameOriginProcessAttempt attempt, DocumentId owner) {
        return attempt.operations().stream().filter(value -> value.ownedDocumentIds().contains(owner)).findFirst().orElseThrow(AssertionError::new);
    }
    private static ResultingDocument document(SameOriginOperationResult result, DocumentId owner) {
        return result.resultingDocuments().stream().filter(value -> value.documentId().equals(owner)).findFirst().orElseThrow(AssertionError::new);
    }
    private static SourceObservationProgram cold(SourceObservationProgram program) {
        Map<String, byte[]> values = new HashMap<>();
        String root = SourceObservationProgramCodec.encode(program, values::put, FrozenNodeEvidenceCodec.Limits.defaults());
        return SourceObservationProgramCodec.decode(root, values::get, FrozenNodeEvidenceCodec.Limits.defaults());
    }
    private enum Mode { SOURCE_TERMINATES, TWO_DIRECT_CHANNELS, JOINED_FATAL, OBSERVER_TERMINATES, TWO_EMBEDDED_CHANNELS }

    private static final class Fixture implements AutoCloseable {
        final BlueLanguageRuntime language;
        final DocumentProcessor processor;
        final BlueClosureContracts contracts;
        final ClosureInvocationInput input;
        int sourceExecutions;
        ClosureImplementationEvidence evidence;

        Fixture(Mode mode) {
            boolean observerTerminates = mode == Mode.OBSERVER_TERMINATES || mode == Mode.TWO_EMBEDDED_CHANNELS;
            boolean sourceTerminates = mode == Mode.SOURCE_TERMINATES || mode == Mode.TWO_DIRECT_CHANNELS;
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                    .register(CHANNEL_ID, CHANNEL, new TestChannelProcessor())
                    .register(HANDLER_ID, HANDLER, new HandlerProcessor<TestHandler>() {
                        @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                        @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                            String key = context.contractKey();
                            if ("finish".equals(key)) {
                                sourceExecutions++;
                                if (observerTerminates) {
                                    context.emitEvent(new Node().name("first"));
                                    context.emitEvent(new Node().name("second"));
                                } else context.terminate("completed", "actual external operation");
                            } else if ("sourceLifecycle".equals(key)) {
                                sourceExecutions++;
                                context.applyPatch(JsonPatch.replace("/counter", new Node().value(BigInteger.valueOf(2))));
                                context.emitEvent(new Node().name("lifecycle event"));
                            } else if ("observe".equals(key)) {
                                if (mode == Mode.JOINED_FATAL) throw new ProcessorFailureException(
                                        ProcessorErrorCategory.InvalidProcessingDocument, "Exact joined lifecycle failure");
                                BigInteger count = (BigInteger) context.resolvedFrozenAt("/seen").getValue();
                                context.applyPatch(JsonPatch.replace("/seen", new Node().value(count.add(BigInteger.ONE))));
                                if (observerTerminates && "observer".equals(context.documentAt("/label").getValue()))
                                    context.terminate("completed", "observer cut-off");
                                else if (sourceTerminates) {
                                    context.applyPatch(JsonPatch.replace("/valueAtEvent", new Node().value(context.resolvedFrozenAt("/source/counter").getValue())));
                                    context.applyPatch(JsonPatch.replace("/markerAtEvent", new Node().value(
                                            context.resolvedFrozenAt("/source/contracts").getProperties().containsKey("terminated"))));
                                }
                            } else if ("late".equals(key)) context.applyPatch(JsonPatch.replace("/late", new Node().value(BigInteger.ONE)));
                            else if ("read".equals(key)) context.applyPatch(JsonPatch.replace("/markerAtRead", new Node().value(
                                    context.resolvedFrozenAt("/source/contracts").getProperties().containsKey("terminated"))));
                        }
                    }).build();
            NodeProvider provider = value -> CHANNEL_ID.equals(value) ? Collections.singletonList(CHANNEL.clone())
                    : HANDLER_ID.equals(value) ? Collections.singletonList(HANDLER.clone())
                    : BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(value);
            language = BlueLanguageRuntime.create(provider, BlueCachePolicy.disabled(), Collections.emptyMap());
            processor = DocumentProcessor.builder().nodeProvider(provider).runtimeRegistry(registry).snapshotStore(new Snapshots(language)).build();
            contracts = new BlueClosureContracts(processor, value -> evidence = value);
            ClosureEnvironment environment = ClosureEvidenceFactory.environment(processor, hash('a'), hash('b'),
                    "termination-identity", "termination-bindings", "termination-provider", "termination-order", "termination-limits", GasSchedule.contracts10().portableLimits());
            Node source = new Node().name("Source").properties("counter", new Node().value(BigInteger.ZERO)).properties("late", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("external", typed(CHANNEL_ID)).properties("finish", handler("external"))
                            .properties("lifecycle", typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))
                            .properties("sourceLifecycle", handler("lifecycle").properties("event", typed(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED))));
            Node observer = observer("observer", SOURCE_PLACEHOLDER);
            if (sourceTerminates) observer.getContracts().properties("external", typed(CHANNEL_ID)).properties("read", handler("external"));
            if (mode == Mode.TWO_DIRECT_CHANNELS) source.getContracts().properties("zExternal", typed(CHANNEL_ID)).properties("late", handler("zExternal"));
            if (mode == Mode.TWO_EMBEDDED_CHANNELS) observer.getContracts()
                    .properties("zEvents", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/source")))
                    .properties("late", handler("zEvents"));
            if (mode == Mode.JOINED_FATAL) source.properties("observer", new Node().blueId(OBSERVER_PLACEHOLDER))
                    .getContracts().properties("embedded", embedded("/observer"));
            source = initialized(source); observer = initialized(observer);
            Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(SOURCE, source); bodies.put(OBSERVER, observer);
            List<ManagedOccurrenceBinding> rows = new ArrayList<>();
            rows.add(binding(environment, OBSERVER, "/source", SOURCE, SOURCE_PLACEHOLDER));
            if (mode == Mode.JOINED_FATAL) rows.add(binding(environment, SOURCE, "/observer", OBSERVER, OBSERVER_PLACEHOLDER));
            if (observerTerminates) {
                bodies.put(HEALTHY, initialized(observer("healthy", SOURCE_PLACEHOLDER)));
                rows.add(binding(environment, HEALTHY, "/source", SOURCE, SOURCE_PLACEHOLDER));
            }
            Map<DocumentId, Long> generations = new LinkedHashMap<>(); bodies.keySet().forEach(value -> generations.put(value, 0L));
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                    ManagedDocumentGraph.fromBindings(bodies.keySet(), rows), generations, bodies, rows));
            List<ManagedDocumentSnapshot> states = new ArrayList<>();
            for (FinalizedDocumentEvidence result : finalized.documents().values()) states.add(new ManagedDocumentSnapshot(result.documentId(), result.blueId(),
                    result.document(), true, false, true, 0L, result.componentGeneration()));
            List<ComponentSnapshot> components = new ArrayList<>(); for (FinalizedComponentEvidence value : finalized.components()) components.add(value.component());
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(0L, states, finalized.finalizedGraph().bindings(), components, new ArrayList<>(bodies.keySet()));
            Node event = new Node().name("finish external input");
            List<DirectLogicalDelivery> deliveries = new ArrayList<>(); deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(SOURCE), "external", "logical", 0L));
            if (mode == Mode.TWO_DIRECT_CHANNELS) deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(SOURCE), "zExternal", "logical", 1L));
            if (sourceTerminates) deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(OBSERVER), "external", "logical", deliveries.size()));
            input = ClosureEvidenceFactory.processClosure(snapshot,
                    ClosureEvidenceFactory.externalCause(event, id(event), ExternalOrderKey.of(Arrays.asList(10L, id(event))), environment.externalOrderPolicyIdentity()),
                    deliveries, ClosureEvidenceFactory.executionPolicy(100000L, Collections.emptyMap(), "termination-policy"), environment);
        }
        AffectedClosureSnapshot snapshot(List<ManagedDocumentSnapshot> states, List<ManagedOccurrenceBinding> rows, List<ManagedReadPin> pins) {
            Map<DocumentId, ManagedDocumentSnapshot> indexed = new TreeMap<>(); states.forEach(value -> indexed.put(value.documentId(), value));
            List<ComponentSnapshot> components = new ArrayList<>();
            for (List<DocumentId> group : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(indexed.keySet(), rows)))
                components.add(ClosureEvidenceFactory.acyclicComponent(indexed.get(group.get(0))));
            return ClosureEvidenceFactory.affectedClosure(0L, states, rows, components, new ArrayList<>(indexed.keySet()), pins);
        }
        @Override public void close() { contracts.close(); processor.close(); language.close(); }
    }
    private static ManagedOccurrenceBinding binding(ClosureEnvironment environment, DocumentId owner, String path, DocumentId target, String ref) {
        return ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), owner, ScopeAddress.embedded(path, 1L), target, ref, true, null);
    }
    private static Node observer(String label, String sourceRef) {
        return new Node().name(label).properties("label", new Node().value(label)).properties("source", new Node().blueId(sourceRef))
                .properties("seen", new Node().value(BigInteger.ZERO)).properties("valueAtEvent", new Node().value(BigInteger.ZERO))
                .properties("late", new Node().value(BigInteger.ZERO))
                .properties("markerAtEvent", new Node().value(false)).properties("markerAtRead", new Node().value(false))
                .contracts(new Node().properties("embedded", embedded("/source"))
                        .properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/source")))
                        .properties("observe", handler("events")));
    }
    private static Node initialized(Node value) { value.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
            .properties("document", new Node().blueId(id(value)))); return value; }
    private static Node embedded(String path) { return typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value(path))); }
    private static Node handler(String channel) { return typed(HANDLER_ID).properties("channel", new Node().value(channel)); }
    private static Node typed(String blueId) { return new Node().type(new Node().blueId(blueId)); }
    private static String id(Node value) { return DirectBlueIdCalculator.calculateBlueId(value); }
    private static String hash(char value) { char[] chars = new char[64]; Arrays.fill(chars, value); return "sha256:" + new String(chars); }
    public static final class TestChannel extends ChannelContract { }
    public static final class TestHandler extends HandlerContract { }
    private static final class TestChannelProcessor implements ChannelProcessor<TestChannel> {
        @Override public Class<TestChannel> contractType() { return TestChannel.class; }
        @Override public ExternalChannelSubscriptionFunctions<TestChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<TestChannel>() {
                @Override public List<String> channelKeys(TestChannel value) { return Collections.singletonList("test"); }
                @Override public boolean preselects(TestChannel value, Node event, ExternalChannelFunctionContext context) { return true; }
                @Override public boolean accepts(TestChannel value, Node event, ExternalChannelFunctionContext context) { return true; }
                @Override public String logicalDeliveryKey(TestChannel value, Node event, Node payload, ExternalChannelFunctionContext context) { return "logical"; }
                @Override public String checkpointDomainDiscriminator(TestChannel value) { return "fresh-termination-test"; }
            };
        }
    }
    private static final class Snapshots implements ProcessingSnapshotManager {
        private final BlueLanguageRuntime language;
        Snapshots(BlueLanguageRuntime language) { this.language = language; }
        @Override public blue.language.merge.ResolvedSnapshot fromDocument(Node document) { return language.snapshots().resolve(document.clone()); }
        @Override public blue.language.merge.ResolvedSnapshot fromDocumentPreservingPaths(Node document, Collection<String> paths) { return language.snapshots().resolvePreservingPaths(document.clone(), paths); }
        @Override public blue.language.merge.ResolvedSnapshot fromDocumentTransientPreservingPaths(Node document, Collection<String> paths) { return fromDocumentPreservingPaths(document, paths); }
        @Override public blue.language.merge.ResolvedSnapshot applyPatch(blue.language.merge.ResolvedSnapshot snapshot, JsonPatch patch) { return language.patching().apply(snapshot, patch); }
        @Override public blue.language.merge.ResolvedSnapshot cacheSnapshot(blue.language.merge.ResolvedSnapshot snapshot) { return language.snapshots().cache(snapshot); }
    }
}
