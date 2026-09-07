package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ManagedCheckpointSettlementBatch;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused execution proof for the concrete affected-closure processor. */
final class DefaultClosureProcessorTest {
    @Test
    void queuedOriginalSourceEventKeepsItsLiveContextWhileNewPlacementInterpretsInitialization() {
        List<String> calls = new ArrayList<>();
        int[] initializationRuns = {0};
        Node old1 = new Node().name("old-one").properties("kind", new Node().value("first"));
        Node old2 = new Node().name("old-two").properties("kind", new Node().value("second"));
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        String key = context.contractKey();
                        if ("initialize".equals(key)) {
                            initializationRuns[0]++; context.applyPatch(JsonPatch.replace("/counter", new Node().value(BigInteger.valueOf(5))));
                            context.emitEvent(new Node().name("ready"));
                        } else if ("emitOld".equals(key)) {
                            context.emitEvent(old1); context.emitEvent(old2);
                        } else if ("queuedRead".equals(key)) {
                            BigInteger value = (BigInteger) context.resolvedFrozenAt("/counter").getValue();
                            calls.add("queued:" + value);
                            context.applyPatch(JsonPatch.replace("/queuedSeen", new Node().value(value)));
                        } else if ("old".equals(key)) {
                            calls.add(context.occurrenceEvent().getName());
                            if ("old-one".equals(context.occurrenceEvent().getName())) context.applyPatch(JsonPatch.replace("/contracts/embedded/paths",
                                    new Node().items(new Node().value("/existing"), new Node().value("/child"))));
                        } else if ("child".equals(key)) {
                            calls.add("child:" + context.resolvedFrozenAt("/child/counter").getValue());
                            BigInteger count = (BigInteger) context.resolvedFrozenAt("/childEvents").getValue();
                            context.applyPatch(JsonPatch.replace("/childEvents", new Node().value(count.add(BigInteger.ONE))));
                        }
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(provider,
                     blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureInvocationInput template = invocation(owner, 100000L);
            Node authored = new Node().name("queued source").properties("counter", new Node().value(BigInteger.ZERO))
                    .properties("queuedSeen", new Node().value(BigInteger.ZERO)).contracts(new Node()
                            .properties("lifecycle", typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))
                            .properties("initialize", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("lifecycle")))
                            .properties("external", typed(CHANNEL_BLUE_ID))
                            .properties("emitOld", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("external")))
                            .properties("second", typed(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL).properties("event", old2))
                            .properties("queuedRead", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("second"))));
            DocumentId source = new DocumentId(DirectBlueIdCalculator.calculateBlueId(authored));
            ManagedDocumentSnapshot initial = new ManagedDocumentSnapshot(source, source.value(), authored, false, false, true, 0L, 0L);
            ClosureInvocationInput admission = ClosureEvidenceFactory.admitClosure(ClosureEvidenceFactory.affectedClosure(0L,
                            Collections.singletonList(initial), Collections.emptyList(), Collections.singletonList(ClosureEvidenceFactory.acyclicComponent(initial)),
                            Collections.singletonList(source)), ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                            "canonical-source:" + source.value(), null, null, "FULL_HISTORY"), null, template.executionPolicy(), template.environment());
            SourceObservationProgram[] captured = new SourceObservationProgram[1];
            try (BlueClosureContracts admitting = new BlueClosureContracts(owner, new ClosureExecutionObserver() {
                @Override public boolean capturesSourceObservationProgram() { return true; }
                @Override public void onSourceObservationProgram(SourceObservationProgram program) { captured[0] = program; }
                @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            })) { assertEquals(ProcessorStatus.SUCCESS, admitting.admitExternalScope(admission, Collections.singleton(source)).processResult().status()); }
            SourceInitialization initialization = SourceInitialization.fromProgram(captured[0]);
            assertTrue(calls.isEmpty(), "Only the exact second external event matches its Triggered channel");
            Node liveBody = captured[0].sourceResults().get(0).document().properties("counter", new Node().value(BigInteger.valueOf(77)));
            ManagedDocumentSnapshot live = new ManagedDocumentSnapshot(source, DirectBlueIdCalculator.calculateBlueId(liveBody), liveBody, true, false, true, 5L, 0L);
            Node parentBody = markInitialized(new Node().name("queued observer").properties("existing", new Node().blueId(live.blueId()))
                    .properties("child", new Node().blueId(source.value())).properties("childEvents", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/existing"))))
                            .properties("fromOld", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/existing")))
                            .properties("old", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("fromOld")))
                            .properties("fromChild", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/child")))
                            .properties("child", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("fromChild")))));
            ManagedDocumentSnapshot parent = new ManagedDocumentSnapshot(A, DirectBlueIdCalculator.calculateBlueId(parentBody), parentBody, true, false, true, 0L, 0L);
            ManagedOccurrenceBinding old = ManagedOccurrenceBinding.derived(template.environment().managedBindingPolicyIdentity(), A,
                    ScopeAddress.embedded("/existing", 1L), source, live.blueId(), true, null);
            ManagedOccurrenceBinding added = ManagedOccurrenceBinding.derived(template.environment().managedBindingPolicyIdentity(), A,
                    ScopeAddress.embedded("/child", 1L), source, source.value(), false, null);
            List<ManagedOccurrenceBinding> rows = Arrays.asList(old, added);
            Map<DocumentId, ManagedDocumentSnapshot> byId = new java.util.TreeMap<>(); byId.put(A, parent); byId.put(source, live);
            List<ComponentSnapshot> components = new ArrayList<>();
            for (List<DocumentId> component : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), rows)))
                components.add(ClosureEvidenceFactory.acyclicComponent(byId.get(component.get(0))));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(ClosureEvidenceFactory.affectedClosure(0L,
                            new ArrayList<>(byId.values()), rows, components, new ArrayList<>(byId.keySet()),
                            Collections.singletonList(ManagedReadPin.fromExactEvidence(source, source.value(), authored, null))), template.cause(),
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(source), "external", "logical", 0L)),
                    template.executionPolicy(), template.environment());
            SameOriginAttachmentPolicy policy = new SameOriginAttachmentPolicy(Collections.singletonList(new SameOriginAttachmentPolicy.Selection(
                    SameOriginAttachmentPolicy.Mode.FULL_HISTORY, A, added.occurrenceIdentity(), source, source.value())));
            SameOriginProcessAttempt result = contracts.processSameOrigin(input, policy, Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList(), Collections.singletonList(initialization));
            assertTrue(result.complete()); assertEquals(2, result.operations().size());
            for (SameOriginOperationResult operation : result.operations()) assertEquals(ProcessorStatus.SUCCESS, operation.status());
            assertEquals(Arrays.asList("old-one", "queued:77", "old-two", "child:5"), calls);
            assertEquals(1, initializationRuns[0]);
            SameOriginOperationResult observed = result.operations().stream().filter(operation -> operation.ownedDocumentIds().contains(A)).findFirst().get();
            assertEquals(BigInteger.ONE, observed.resultingDocuments().get(0).document().getProperties().get("childEvents").getValue());
            SourceObservationProgram producedSource = result.operations().stream().filter(operation -> operation.ownedDocumentIds().contains(source))
                    .findFirst().get().sourceProgram().get();
            calls.clear();
            SameOriginProcessAttempt reused = contracts.processSameOrigin(input, policy, Collections.singletonList(coldProgram(producedSource)),
                    Collections.emptyMap(), Collections.emptyList(), Collections.singletonList(initialization));
            assertTrue(reused.complete()); assertEquals(1, reused.operations().size());
            assertEquals(Arrays.asList("old-one", "old-two", "child:5"), calls, "The cold external source handler is not executed again");
            assertEquals(observed.operationIdentity(), reused.operations().get(0).operationIdentity());
            assertEquals(observed.gasTraceIdentity(), reused.operations().get(0).gasTraceIdentity());
            assertEquals(live.blueId(), observed.observedSource(A, source).get().blueId());
            assertEquals(5L, observed.observedSource(A, source).get().epoch());
            assertEquals(observed.observedSource(A, source).get().blueId(), reused.operations().get(0).observedSource(A, source).get().blueId());
            assertEquals(observed.observedSource(A, source).get().epoch(), reused.operations().get(0).observedSource(A, source).get().epoch());
            assertEquals(1, initializationRuns[0]);
        }
    }

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final DocumentId ROOT = new DocumentId("root");
    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final DocumentId C = new DocumentId("c");
    private static final DocumentId D = new DocumentId("d");
    private static final String C34_A_BLUE_ID =
            "8XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8";
    private static final String C34_B_BLUE_ID =
            "6ZEqCbcDrgozabAdvxqbUVsG8z8NGxFv86ot2Go55xRZ";
    private static final String C34_X_BLUE_ID =
            "EUX3vKa2wK4c1ZQvryFrytQWuVAwZ2vbCk4BvgMjzKAR";
    private static final String C34_Y_BLUE_ID =
            "9uncWU9V9UadZA5zV3We6KBkM1azpwz8RLnMb7bVWTzv";
    private static final Node CHANNEL_TYPE =
            new Node().name("Concrete Closure Test Channel");
    private static final String CHANNEL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final Node HANDLER_TYPE =
            new Node().name("Concrete Closure Test Handler");
    private static final String HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(HANDLER_TYPE);
    private static final Node DRAFT_HANDLER_TYPE =
            new Node().name("Managed Draft Test Handler");
    private static final String DRAFT_HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(DRAFT_HANDLER_TYPE);
    private static final Node INDEPENDENT_DRAFT_HANDLER_TYPE =
            new Node().name("Independent Managed Draft Test Handler");
    private static final String INDEPENDENT_DRAFT_HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    INDEPENDENT_DRAFT_HANDLER_TYPE);
    private static final Node ROUTING_HANDLER_TYPE =
            new Node().name("Latest Event Routing Test Handler");
    private static final String ROUTING_HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(ROUTING_HANDLER_TYPE);
    private static final Node FROZEN_TARGET_HANDLER_TYPE =
            new Node().name("Frozen Event Target Test Handler");
    private static final String FROZEN_TARGET_HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    FROZEN_TARGET_HANDLER_TYPE);
    private static final String ROUTE_SWITCH_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    routeEvent("switch"));
    private static final String ROUTE_TARGET_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    routeEvent("target"));
    private static final String ROUTE_OTHER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    routeEvent("other"));
    private static final String ACTIVATE_TARGET_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    routeEvent("activate-target"));
    private static final String OBSERVE_TARGET_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    routeEvent("observe-target"));

    @Test
    void sameOriginOriginalEventReachesEveryAncestorAliasInCanonicalBreadthFirstOrder() {
        List<String> calls = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        if ("handler".equals(context.contractKey())) {
                            calls.add("B emits"); context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                            context.emitEvent(new Node().name("original occurrence")); return;
                        }
                        calls.add(context.contractKey() + ":" + context.event().getProperties().get("sourcePath").getValue());
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                     provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot b = sourceState(B, initializedDocument().name("B"), 0);
            ManagedDocumentSnapshot a = sourceState(A, markInitialized(new Node().name("A")
                    .properties("left", new Node().blueId(b.blueId())).properties("right", new Node().blueId(b.blueId()))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths",
                            new Node().items(new Node().value("/left"), new Node().value("/right"))))
                            .properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                            .properties("A", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("events"))))), 0);
            ManagedDocumentSnapshot d = sourceState(D, markInitialized(new Node().name("D").properties("a", new Node().blueId(a.blueId()))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths",
                            new Node().items(new Node().value("/a"))))
                            .properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                            .properties("D", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("events"))))), 0);
            String policy = template.environment().managedBindingPolicyIdentity();
            List<ManagedOccurrenceBinding> bindings = Arrays.asList(c34Binding(A, "/left", B, b.blueId(), true, policy),
                    c34Binding(A, "/right", B, b.blueId(), true, policy), c34Binding(D, "/a", A, a.blueId(), true, policy));
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(a, b, d), bindings,
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a),
                            ClosureEvidenceFactory.acyclicComponent(d)), Arrays.asList(A, B, D));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, template.cause(),
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 0)),
                    template.executionPolicy(), template.environment());
            SameOriginProcessAttempt attempt = contracts.processSameOrigin(input);
            assertTrue(attempt.complete());
            assertEquals(Arrays.asList("B emits", "A:/left", "A:/right", "D:/a/left", "D:/a/right"), calls);
            assertEquals(Arrays.asList(Collections.singleton(B), Collections.singleton(A), Collections.singleton(D)), attempt.operations().stream()
                    .map(SameOriginOperationResult::ownedDocumentIds).collect(java.util.stream.Collectors.toList()));
            for (SameOriginOperationResult operation : attempt.operations()) assertEquals(ProcessorStatus.SUCCESS, operation.status());
            assertEquals(Collections.singletonMap(B, attempt.operations().get(0).operationIdentity()), attempt.operations().get(1).consumedSourceOperations());
            assertEquals(Collections.singletonMap(A, attempt.operations().get(1).operationIdentity()), attempt.operations().get(2).consumedSourceOperations());
            assertEquals(2, attempt.operations().get(2).sourceProgram().get().steps().size());
            calls.clear();
            SameOriginProcessAttempt cachedB = contracts.processSameOrigin(input, SameOriginAttachmentPolicy.empty(),
                    Collections.singletonList(coldProgram(attempt.operations().get(0).sourceProgram().get())),
                    Collections.emptyMap(), Collections.emptyList());
            assertTrue(cachedB.complete());
            assertEquals(Arrays.asList("A:/left", "A:/right", "D:/a/left", "D:/a/right"), calls);
            assertEquals(2, cachedB.operations().size(), "The committed producer is evidence, not another operation");
            for (int i = 0; i < cachedB.operations().size(); i++) {
                assertEquals(attempt.operations().get(i + 1).operationIdentity(), cachedB.operations().get(i).operationIdentity());
                assertEquals(attempt.operations().get(i + 1).gasTraceIdentity(), cachedB.operations().get(i).gasTraceIdentity());
                assertEquals(attempt.operations().get(i + 1).resultingDocuments().get(0).afterBlueId(),
                        cachedB.operations().get(i).resultingDocuments().get(0).afterBlueId());
            }
            calls.clear();
            SameOriginProcessAttempt cachedA = contracts.processSameOrigin(input, SameOriginAttachmentPolicy.empty(),
                    Collections.singletonList(coldProgram(attempt.operations().get(1).sourceProgram().get())),
                    Collections.emptyMap(), Collections.emptyList());
            assertTrue(cachedA.complete()); assertEquals(Arrays.asList("D:/a/left", "D:/a/right"), calls);
            assertEquals(1, cachedA.operations().size());
            assertEquals(attempt.operations().get(2).operationIdentity(), cachedA.operations().get(0).operationIdentity());
            assertEquals(attempt.operations().get(2).gasTraceIdentity(), cachedA.operations().get(0).gasTraceIdentity());
        }
    }

    @Test
    void sameOriginEventCyclesUseSimplePassiveRoutesButNewEmissionsRemainGasBounded() {
        runPassiveEventCycle(false);
        runPassiveEventCycle(true);
    }

    private void runPassiveEventCycle(final boolean emitAgain) {
        List<String> calls = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        calls.add(context.contractKey());
                        if ("start".equals(context.contractKey()) || emitAgain) context.emitEvent(new Node().name("cycle occurrence"));
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                     provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureInvocationInput template = invocation(owner, 4000);
            Node aBody = markInitialized(new Node().name("A").properties("b", new Node().blueId(C34_B_BLUE_ID))
                    .contracts(new Node().properties("source", typed(CHANNEL_BLUE_ID))
                            .properties("start", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")))
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/b"))))
                            .properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                            .properties("A", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("events")))));
            Node bBody = markInitialized(new Node().name("B").properties("a", new Node().blueId(C34_A_BLUE_ID))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                            .properties("paths", new Node().items(new Node().value("/a"))))
                            .properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                            .properties("B", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("events")))));
            List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                    c34Binding(A, "/b", B, C34_B_BLUE_ID, true, template.environment().managedBindingPolicyIdentity()),
                    c34Binding(B, "/a", A, C34_A_BLUE_ID, true, template.environment().managedBindingPolicyIdentity()));
            Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(A, aBody); bodies.put(B, bBody);
            Map<DocumentId, Long> generations = new LinkedHashMap<>(); generations.put(A, 0L); generations.put(B, 0L);
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                    ManagedDocumentGraph.fromBindings(Arrays.asList(A, B), bindings), generations, bodies, bindings));
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            for (DocumentId id : Arrays.asList(A, B)) {
                FinalizedDocumentEvidence exact = finalized.document(id);
                documents.add(new ManagedDocumentSnapshot(id, exact.blueId(), exact.document(), true, false, true, 0, exact.componentGeneration()));
            }
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1, documents, finalized.finalizedGraph().bindings(),
                    finalized.components().stream().map(FinalizedComponentEvidence::component).collect(java.util.stream.Collectors.toList()), Arrays.asList(A, B));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, template.cause(),
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0)),
                    template.executionPolicy(), template.environment());
            SameOriginProcessAttempt attempt = contracts.processSameOrigin(input);
            assertTrue(attempt.complete()); assertEquals(1, attempt.operations().size());
            SameOriginOperationResult operation = attempt.operations().get(0);
            assertEquals(new java.util.TreeSet<>(Arrays.asList(A, B)), operation.ownedDocumentIds());
            if (emitAgain) {
                assertTrue(calls.size() > 3, "Each new emission starts fresh routes, not global visited deduplication");
                assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, operation.status());
                assertTrue(operation.events().isEmpty()); assertFalse(operation.sourceProgram().isPresent());
                for (ResultingDocument result : operation.resultingDocuments()) {
                    assertEquals(snapshot.managedDocument(result.documentId()).blueId(), result.afterBlueId());
                    assertEquals(0L, result.epoch());
                }
            } else {
                assertEquals(Arrays.asList("start", "B"), calls, "The original emitter is excluded from a passive return through the cycle");
                assertEquals(ProcessorStatus.SUCCESS, operation.status()); assertEquals(1, operation.events().size());
            }
        }
    }

    @Test
    void sameOriginLocalWorkKeepsTransitiveBodiesAbsentAndExactReadsDemandOnlyTheirNextBody() {
        final List<String> applicationReads = new ArrayList<>();
        final java.util.Set<String> forbiddenProviderBodies = new java.util.HashSet<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        BigInteger next = BigInteger.ONE;
                        if ("read".equals(context.contractKey())) {
                            applicationReads.add("/b/c/count");
                            next = (BigInteger) context.resolvedFrozenAt("/b/c/count").getValue();
                        }
                        context.applyPatch(JsonPatch.replace("/counter", new Node().value(next)));
                    }
                }).build();
        NodeProvider provider = id -> {
            if (forbiddenProviderBodies.contains(id)) throw new AssertionError("Ambient managed body fetch: " + id);
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                     provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot c = sourceState(C, markInitialized(new Node().name("cold C")
                    .properties("count", new Node().value(BigInteger.valueOf(7))).contracts(new Node())), 3);
            ManagedDocumentSnapshot b = sourceState(B, markInitialized(new Node().name("cold B")
                    .properties("c", new Node().blueId(c.blueId())).contracts(new Node().properties("embedded",
                            typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/c")))))), 2);
            forbiddenProviderBodies.add(b.blueId()); forbiddenProviderBodies.add(c.blueId());
            for (boolean read : Arrays.asList(false, true)) {
                Node aBody = initializedDocument().properties("b", new Node().blueId(b.blueId()))
                        .properties("counter", new Node().value(BigInteger.ZERO));
                aBody.getContracts().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                        .properties("paths", new Node().items(new Node().value("/b"))));
                if (read) { aBody.getContracts().getProperties().remove("handler"); aBody.getContracts().properties("read",
                        typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source"))); }
                ManagedDocumentSnapshot a = sourceState(A, aBody, 0);
                AffectedClosureSnapshot full = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(a, b, c), Arrays.asList(
                        c34Binding(A, "/b", B, b.blueId(), true, template.environment().managedBindingPolicyIdentity()),
                        c34Binding(B, "/c", C, c.blueId(), true, template.environment().managedBindingPolicyIdentity())), Arrays.asList(
                        ClosureEvidenceFactory.acyclicComponent(c), ClosureEvidenceFactory.acyclicComponent(b),
                        ClosureEvidenceFactory.acyclicComponent(a)), Arrays.asList(A, B, C));
                List<RootChannelMetadata> metadata = new ArrayList<>();
                for (ManagedDocumentSnapshot state : full.managedDocuments()) metadata.add(RootChannelMetadata.fromVerifiedRoot(state, owner));
                AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.singleton(A), metadata);
                List<DirectLogicalDelivery> deliveries = Collections.singletonList(new DirectLogicalDelivery(
                        ManagedScopeKey.root(A), "source", "logical", 0));
                ClosureInvocationInput hotInput = ClosureEvidenceFactory.processClosure(full, template.cause(), deliveries,
                        template.executionPolicy(), template.environment());
                ClosureInvocationInput coldInput = ClosureEvidenceFactory.processClosure(sparse, template.cause(), deliveries,
                        template.executionPolicy(), template.environment());
                assertEquals(hotInput.invocationIdentity(), coldInput.invocationIdentity());
                SameOriginProcessAttempt hot = contracts.processSameOrigin(hotInput);
                assertTrue(hot.complete());
                SameOriginProcessAttempt cold = contracts.processSameOrigin(coldInput);
                if (read) {
                    assertFalse(cold.complete()); assertEquals(Collections.singletonList(b.blueId()), cold.requiredExactBlueIds());
                    sparse = sparse.withResidentBody(ManagedReadPin.fromExactEvidence(B, b.blueId(), b.document(), null));
                    coldInput = ClosureEvidenceFactory.processClosure(sparse, template.cause(), deliveries,
                            template.executionPolicy(), template.environment());
                    cold = contracts.processSameOrigin(coldInput);
                    assertFalse(cold.complete()); assertEquals(Collections.singletonList(c.blueId()), cold.requiredExactBlueIds());
                    sparse = sparse.withResidentBody(ManagedReadPin.fromExactEvidence(C, c.blueId(), c.document(), null));
                    coldInput = ClosureEvidenceFactory.processClosure(sparse, template.cause(), deliveries,
                            template.executionPolicy(), template.environment());
                    cold = contracts.processSameOrigin(coldInput);
                } else {
                    assertTrue(applicationReads.isEmpty());
                    assertFalse(sparse.managedDocument(B).hasResidentBody()); assertFalse(sparse.managedDocument(C).hasResidentBody());
                }
                assertTrue(cold.complete(), cold.requiredExactBlueIds().toString()); assertEquals(1, cold.operations().size());
                SameOriginOperationResult warmResult = hot.operations().get(0), coldResult = cold.operations().get(0);
                assertEquals(Collections.singleton(A), coldResult.ownedDocumentIds());
                assertEquals(ProcessorStatus.SUCCESS, coldResult.status());
                assertEquals(warmResult.operationIdentity(), coldResult.operationIdentity());
                assertEquals(warmResult.gasTraceIdentity(), coldResult.gasTraceIdentity());
                assertEquals(warmResult.resultingDocuments().get(0).afterBlueId(), coldResult.resultingDocuments().get(0).afterBlueId());
                Map<String, byte[]> warmBytes = new java.util.HashMap<>(), coldBytes = new java.util.HashMap<>();
                assertEquals(SourceObservationProgramCodec.encode(warmResult.sourceProgram().get(), warmBytes::put, FrozenNodeEvidenceCodec.Limits.defaults()),
                        SourceObservationProgramCodec.encode(coldResult.sourceProgram().get(), coldBytes::put, FrozenNodeEvidenceCodec.Limits.defaults()));
            }
        }
    }

    @Test
    void sameOriginUntouchedReadDependencyDoesNotBecomeAnOperation() {
        final List<BigInteger> reads = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        if ("reject".equals(context.contractKey()))
                            throw new blue.language.processor.ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure, "deterministic source rejection");
                        BigInteger value = (BigInteger) context.resolvedFrozenAt("/b/x").getValue();
                        reads.add(value); context.applyPatch(JsonPatch.replace("/seen", new Node().value(value)));
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                     provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot b = sourceState(B, markInitialized(new Node().name("Constant B")
                    .properties("x", new Node().value(BigInteger.valueOf(7))).contracts(new Node())), 9);
            ManagedDocumentSnapshot a = sourceState(A, initializedDocument().properties("b", new Node().blueId(b.blueId()))
                    .properties("seen", new Node().value(BigInteger.ZERO)), 0);
            a = sourceState(A, a.document().contracts(a.document().getContracts().clone().properties("embedded",
                    typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/b"))))), 0);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(a, b),
                    Collections.singletonList(c34Binding(A, "/b", B, b.blueId(), true, template.environment().managedBindingPolicyIdentity())),
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a)), Arrays.asList(A, B));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, template.cause(),
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0)),
                    template.executionPolicy(), template.environment());
            SameOriginProcessAttempt attempt = contracts.processSameOrigin(input);
            assertTrue(attempt.complete()); assertEquals(Collections.singletonList(BigInteger.valueOf(7)), reads);
            assertEquals(1, attempt.operations().size());
            SameOriginOperationResult operation = attempt.operations().get(0);
            assertEquals(Collections.singleton(A), operation.ownedDocumentIds());
            assertEquals(ProcessorStatus.SUCCESS, operation.status());
            assertTrue(operation.consumedSourceOperations().isEmpty());
            assertEquals(Collections.singleton(A), operation.sourceProgram().get().ownedDocumentIds());
            assertTrue(operation.sourceProgram().get().sourceResults().stream()
                    .noneMatch(source -> source.documentId().equals(B)), "A read-only exact view is not a source epoch assertion");
            assertEquals(b.blueId(), operation.sourceProgram().get().sourceAfterBindings().get(0).expectedTargetBlueId());

            ManagedDocumentSnapshot rejectingB = sourceState(B, b.document().contracts(b.document().getContracts().clone()
                    .properties("source", typed(CHANNEL_BLUE_ID))
                    .properties("reject", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")))), 9);
            ManagedDocumentSnapshot dependentA = sourceState(A, a.document().properties("b", new Node().blueId(rejectingB.blueId())), 0);
            AffectedClosureSnapshot failedSourceCut = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(dependentA, rejectingB),
                    Collections.singletonList(c34Binding(A, "/b", B, rejectingB.blueId(), true, template.environment().managedBindingPolicyIdentity())),
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(rejectingB), ClosureEvidenceFactory.acyclicComponent(dependentA)), Arrays.asList(A, B));
            ClosureInvocationInput failedInput = ClosureEvidenceFactory.processClosure(failedSourceCut, template.cause(), Arrays.asList(
                    new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 0),
                    new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 1)), template.executionPolicy(), template.environment());
            SameOriginProcessAttempt freshFailure = contracts.processSameOrigin(failedInput);
            assertTrue(freshFailure.complete()); assertEquals(2, freshFailure.operations().size());
            SameOriginOperationResult failedB = freshFailure.operations().get(0), successfulA = freshFailure.operations().get(1);
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failedB.status());
            assertEquals(ProcessorStatus.SUCCESS, successfulA.status());
            Map<String, byte[]> failureStore = new java.util.HashMap<>();
            blue.language.processor.closure.FrozenNodeEvidenceCodec.Limits limits = FrozenNodeEvidenceCodec.Limits.defaults();
            String failureIdentity = SourceOperationFailureCodec.encode(SourceOperationFailure.fromSameOrigin(failedB), failureStore::put, limits);
            SourceOperationFailure retainedFailure = SourceOperationFailureCodec.decode(failureIdentity, failureStore::get, limits);
            SameOriginProcessAttempt cachedFailure = contracts.processSameOrigin(failedInput, SameOriginAttachmentPolicy.empty(),
                    Collections.emptyList(), Collections.emptyMap(), Collections.singletonList(retainedFailure));
            assertTrue(cachedFailure.complete()); assertEquals(1, cachedFailure.operations().size());
            assertEquals(successfulA.operationIdentity(), cachedFailure.operations().get(0).operationIdentity());
            assertEquals(successfulA.gasTraceIdentity(), cachedFailure.operations().get(0).gasTraceIdentity());
            assertEquals(Collections.singletonMap(B, failedB.operationIdentity()), cachedFailure.operations().get(0).consumedSourceOperations());
            assertTrue(failedB.observedSources().isEmpty(), "A source's own failure does not fabricate an external observation");
            assertEquals(rejectingB.blueId(), successfulA.observedSource(A, B).get().blueId());
            assertEquals(9L, successfulA.observedSource(A, B).get().epoch());
            assertEquals(successfulA.observedSource(A, B).get().blueId(), cachedFailure.operations().get(0).observedSource(A, B).get().blueId());
            assertEquals(successfulA.observedSource(A, B).get().epoch(), cachedFailure.operations().get(0).observedSource(A, B).get().epoch());
            assertEquals(SourceObservationProgramCodec.encode(successfulA.sourceProgram().get(), (key, bytes) -> { }, limits),
                    SourceObservationProgramCodec.encode(cachedFailure.operations().get(0).sourceProgram().get(), (key, bytes) -> { }, limits),
                    "Fresh and retained failure dispositions do not invent a successful source-state inventory");
        }
    }

    @Test
    void sameOriginDriverUsesIndependentLiveRuntimeBudgetsForIndependentActualSeeds() {
        final int[] calls = {0};
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) { calls[0]++; }
                }).build();
        try (DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).build()) {
            ManagedDocumentSnapshot a = sourceState(A, initializedDocument().name("Order A"), 0);
            ManagedDocumentSnapshot b = sourceState(B, initializedDocument().name("Order B"), 0);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(a, b),
                    Collections.emptyList(), Arrays.asList(ClosureEvidenceFactory.acyclicComponent(a), ClosureEvidenceFactory.acyclicComponent(b)), Arrays.asList(A, B));
            ClosureInvocationInput template = invocation(owner);
            List<DirectLogicalDelivery> deliveries = Arrays.asList(
                    new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0),
                    new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 1));
            ClosureInvocationInput first = ClosureEvidenceFactory.processClosure(snapshot, template.cause(), deliveries,
                    template.executionPolicy(), template.environment());
            long aGas, bGas;
            try (ClosureExecutionSession driver = new ClosureExecutionSession(owner, first,
                    new ClosureExecutionRecorder(first.invocationIdentity()), ClosureExecutionSession.ExecutionMode.PROCESSING)) {
                SameOriginAttemptCoordinator groups = driver.useSameOriginGroups(owner);
                ClosureExecutionState state = driver.execute();
                aGas = groups.runtime(A).totalGas(); bGas = groups.runtime(B).totalGas();
                assertTrue(aGas > 0); assertTrue(bGas > 0);
                assertEquals(2, state.checkpointMutations().size(), "Both actual external deliveries reach their own checkpoint settlement");
                assertNotSame(groups.attempt(A), groups.attempt(B));
            }
            ExecutionPolicy provisional = new ExecutionPolicy(hash('f'), Math.max(aGas, bGas), Collections.emptyMap(), "independent-live-cap");
            ExecutionPolicy cap = new ExecutionPolicy(IDENTITIES.executionPolicyIdentity(provisional), provisional.sharedLimit(), provisional.localLimits(), provisional.label());
            ClosureInvocationInput exact = ClosureEvidenceFactory.processClosure(snapshot, template.cause(), deliveries, cap, template.environment());
            try (ClosureExecutionSession driver = new ClosureExecutionSession(owner, exact,
                    new ClosureExecutionRecorder(exact.invocationIdentity()), ClosureExecutionSession.ExecutionMode.PROCESSING)) {
                SameOriginAttemptCoordinator groups = driver.useSameOriginGroups(owner);
                ClosureExecutionState state = driver.execute();
                assertEquals(aGas, groups.runtime(A).totalGas()); assertEquals(bGas, groups.runtime(B).totalGas());
                assertTrue(aGas + bGas > cap.sharedLimit(), "A single shared closure meter would reject this valid independent pair");
                assertEquals(2, state.checkpointMutations().size());
                assertEquals(4, calls[0], "Each actual evaluation executes each source handler once");
            }
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
                SameOriginProcessAttempt attempt = contracts.processSameOrigin(exact);
                assertTrue(attempt.complete()); assertEquals(2, attempt.operations().size());
                assertEquals(Arrays.asList(Collections.singleton(A), Collections.singleton(B)), attempt.operations().stream()
                        .map(SameOriginOperationResult::ownedDocumentIds).collect(java.util.stream.Collectors.toList()));
                assertTrue(attempt.operations().stream().mapToLong(SameOriginOperationResult::totalGas).sum() > cap.sharedLimit());
                for (SameOriginOperationResult operation : attempt.operations()) {
                    assertEquals(ProcessorStatus.SUCCESS, operation.status());
                    assertEquals(1L, operation.resultingDocuments().get(0).epoch());
                    assertEquals(operation.ownedDocumentIds(), operation.sourceProgram().get().ownedDocumentIds());
                    assertEquals(operation.operationIdentity(), operation.sourceProgram().get().invocationIdentity());
                    assertTrue(operation.consumedSourceOperations().isEmpty());
                    assertEquals(1, operation.checkpointWrites().size());
                    assertEquals(0L, operation.checkpointWrites().get(0).checkpointWriteOrdinal());
                }
                assertEquals(6, calls[0]);
                ExecutionPolicy limited = new ExecutionPolicy(hash('f'), template.executionPolicy().sharedLimit(),
                        Collections.singletonMap(A, aGas - 1), "independent-checkpoint-local-failure");
                limited = new ExecutionPolicy(IDENTITIES.executionPolicyIdentity(limited), limited.sharedLimit(), limited.localLimits(), limited.label());
                SameOriginProcessAttempt checkpointFailure = contracts.processSameOrigin(ClosureEvidenceFactory.processClosure(
                        snapshot, template.cause(), deliveries, limited, template.environment()));
                assertTrue(checkpointFailure.complete()); assertEquals(2, checkpointFailure.operations().size());
                SameOriginOperationResult failed = checkpointFailure.operations().get(0), valid = checkpointFailure.operations().get(1);
                assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.status());
                assertEquals(a.blueId(), failed.resultingDocuments().get(0).afterBlueId());
                assertTrue(failed.checkpointWrites().isEmpty());
                assertTrue(failed.failure().get().rejectedCharge().isPresent());
                assertEquals(ProcessorStatus.SUCCESS, valid.status());
                assertEquals(bGas, valid.totalGas(), "Another scope's checkpoint failure cannot charge or roll back this valid source");
                assertEquals(1, valid.checkpointWrites().size());
                assertEquals(8, calls[0]);
            }
        }
    }

    @Test
    void sameOriginActualTopologyPatchJoinsLiveRuntimesBeforeCyclicFinalizationAndCallbacks() {
        try (DocumentProcessor owner = c34Owner()) {
            ClosureInvocationInput input = c34Invocation(owner);
            assertEquals(2, input.snapshot().components().size());
            ClosureExecutionRecorder recorder = new ClosureExecutionRecorder(input.invocationIdentity());
            try (ClosureExecutionSession driver = new ClosureExecutionSession(owner, input, recorder, ClosureExecutionSession.ExecutionMode.PROCESSING)) {
                SameOriginAttemptCoordinator groups = driver.useSameOriginGroups(owner);
                ClosureExecutionState state = driver.execute();
                assertEquals(1, groups.activeAttempts().size());
                assertSame(groups.attempt(A), groups.attempt(B));
                assertNull(groups.attempt(A).failure());
                assertEquals(new java.util.TreeSet<DocumentId>(Arrays.asList(A, B)), groups.attempt(A).members());
                assertEquals(1, groups.attempt(A).admissions().size(), "Only the actual merging patch admits a group");
                assertEquals(ComponentKind.CYCLIC, state.tentativeSnapshot().components().get(0).kind());
                assertEquals(groups.runtime(A).groupAdmittedGas(), groups.runtime(B).groupAdmittedGas());
                assertTrue(groups.runtime(A).groupAdmittedGas() > groups.runtime(A).totalGas());
                assertTrue(groups.runtime(A).groupAdmittedGas() > groups.runtime(B).totalGas());
                List<blue.language.processor.GasTraceEntry> chronological = groups.attempt(A).canonicalGasTrace();
                assertEquals(groups.runtime(A).groupAdmittedGas(), chronological.stream().mapToLong(blue.language.processor.GasTraceEntry::subtotal).sum());
                assertNotEquals(groups.admittedTraceByStableOwner(), chronological,
                        "A/B continuation charges preserve actual encounter order, not owner-concatenated traces");
            }
        }
    }

    @Test
    void sameOriginRejectedThreePartyJoinDiscardsEntireConditionalGroupAndReconstructsBothOwnSeeds() {
        long[] admitted = runThreePartyConditionalJoin(100000L, false, null);
        long rejectingCap = Math.max(admitted[0], admitted[1]);
        assertTrue(admitted[0] + admitted[1] > rejectingCap);
        runThreePartyConditionalJoin(rejectingCap, true, admitted);
    }

    @Test
    void sameOriginFailureAfterAcceptedThreePartyJoinRollsBackTheWholeJoinedGroup() {
        runThreePartyConditionalJoin(100000L, false, null, true);
    }

    @Test
    void sameOriginDoesNotPromoteAnOldAttachmentReferenceWithoutSelectedViewAuthority() {
        runThreePartyConditionalJoin(100000L, false, null, false, false);
    }

    private long[] runThreePartyConditionalJoin(long cap, boolean rejected, long[] expectedPrefix) {
        return runThreePartyConditionalJoin(cap, rejected, expectedPrefix, false);
    }

    private long[] runThreePartyConditionalJoin(long cap, boolean rejected, long[] expectedPrefix, boolean joinedFatal) {
        return runThreePartyConditionalJoin(cap, rejected, expectedPrefix, joinedFatal, true);
    }

    private long[] runThreePartyConditionalJoin(long cap, boolean rejected, long[] expectedPrefix, boolean joinedFatal, boolean authorizeViews) {
        List<String> calls = new ArrayList<String>();
        final String[] targetBlueIds = new String[2];
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        switch (context.contractKey()) {
                            case "producer":
                                calls.add("B own");
                                context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                                context.applyPatch(JsonPatch.add("/a", new Node().blueId(targetBlueIds[0])));
                                break;
                            case "joinThirdParty":
                                calls.add("A conditional joins C");
                                context.applyPatch(JsonPatch.add("/c", new Node().blueId(targetBlueIds[1])));
                                break;
                            case "aOwn":
                                calls.add("A own sees B" + context.resolvedFrozenAt("/b/x").getValue());
                                context.applyPatch(JsonPatch.replace("/seen", new Node().value(BigInteger.valueOf(5))));
                                break;
                            case "cOwn":
                                calls.add("C own sees A" + context.resolvedFrozenAt("/a/seen").getValue());
                                context.applyPatch(JsonPatch.replace("/seen", new Node().value(BigInteger.valueOf(5))));
                                break;
                            case "zzFailure":
                                calls.add("B joined failure");
                                throw new blue.language.processor.ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure, "joined producer rejection");
                            case "observeB":
                                calls.add("D observes B" + context.resolvedFrozenAt("/b/x").getValue());
                                context.applyPatch(JsonPatch.replace("/seen", new Node().value(BigInteger.ONE)));
                                break;
                            default: throw new AssertionError("Unexpected test handler");
                        }
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build()) {
            ClosureInvocationInput template = invocation(owner, cap);
            Node bBody = markInitialized(new Node().name("B").properties("x", new Node().value(BigInteger.ZERO)).contracts(new Node()
                    .properties("source", typed(CHANNEL_BLUE_ID))
                    .properties("producer", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")))
                    .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/a"))))));
            if (joinedFatal) bBody.getContracts().properties("zzFailure", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")));
            ManagedDocumentSnapshot b = sourceState(B, bBody, 0);
            Node aBody = markInitialized(new Node().name("A").properties("b", new Node().blueId(b.blueId()))
                    .properties("seen", new Node().value(BigInteger.ZERO)).contracts(new Node()
                            .properties("source", typed(CHANNEL_BLUE_ID))
                            .properties("aOwn", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")))
                            .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/b/x")))
                            .properties("joinThirdParty", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("updates")))
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/b"), new Node().value("/c"))))));
            ManagedDocumentSnapshot a = sourceState(A, aBody, 0);
            Node cBody = markInitialized(new Node().name("C").properties("a", new Node().blueId(a.blueId()))
                    .properties("seen", new Node().value(BigInteger.ZERO)).contracts(new Node()
                            .properties("source", typed(CHANNEL_BLUE_ID))
                            .properties("cOwn", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")))
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/a"))))));
            ManagedDocumentSnapshot c = sourceState(C, cBody, 0);
            targetBlueIds[0] = a.blueId(); targetBlueIds[1] = c.blueId();
            String bindingPolicy = template.environment().managedBindingPolicyIdentity();
            List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                    c34Binding(A, "/b", B, b.blueId(), true, bindingPolicy),
                    c34Binding(C, "/a", A, a.blueId(), true, bindingPolicy),
                    c34Binding(A, "/c", C, c.blueId(), false, bindingPolicy),
                    c34Binding(B, "/a", A, a.blueId(), false, bindingPolicy));
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(a, b, c), bindings,
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a), ClosureEvidenceFactory.acyclicComponent(c)), Arrays.asList(A, B, C));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, template.cause(), Arrays.asList(
                    new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 0),
                    new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 1),
                    new DirectLogicalDelivery(ManagedScopeKey.root(C), "source", "logical", 2)), template.executionPolicy(), template.environment());
            SameOriginAttachmentPolicy attachmentPolicy = new SameOriginAttachmentPolicy(Arrays.asList(
                    new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW,
                            A, bindings.get(2).occurrenceIdentity(), C, c.blueId()),
                    new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW,
                            B, bindings.get(3).occurrenceIdentity(), A, a.blueId())));
            if (authorizeViews && !rejected && !joinedFatal) {
                // A fresh producer cannot publish its own partial result before its returning
                // dependency has authorized the same input and the shared policy.
                java.util.Map<DocumentId, String> bases = new java.util.HashMap<>();
                for (DocumentId member : Arrays.asList(A, B, C)) bases.put(member,
                        SourceExecutionBasis.identity(member, input.environment(), input.executionPolicy()));
                SameOriginProcessAttempt missing = new DefaultClosureProcessor(owner).processSameOrigin(input, attachmentPolicy,
                        Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), bases, new java.util.HashSet<>(Arrays.asList(A, B)));
                assertFalse(missing.complete()); assertEquals(Collections.singleton(C), missing.requiredSourceAdmissions());
                assertTrue(missing.operations().isEmpty()); assertTrue(missing.requiredExactBlueIds().isEmpty());
                assertTrue(missing.resourceDemands().isEmpty());
                calls.clear();
                java.util.Map<DocumentId, String> returningBasis = new java.util.HashMap<>(bases);
                returningBasis.put(C, SourceExecutionBasis.identity(C, input.environment(),
                        ClosureEvidenceFactory.executionPolicy(cap + 1, Collections.emptyMap(), "returning C policy")));
                SameOriginProcessAttempt crossPolicyReturn = new DefaultClosureProcessor(owner).processSameOrigin(input, attachmentPolicy,
                        Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), returningBasis, new java.util.HashSet<>(Arrays.asList(A, B)));
                assertEquals(Collections.singleton(C), crossPolicyReturn.requiredSourceAdmissions());
                assertTrue(crossPolicyReturn.operations().isEmpty(), "A returning different-policy participant cannot leave an independently published prefix");
                calls.clear();
                java.util.Map<DocumentId, String> wrongBasis = new java.util.HashMap<>(bases);
                wrongBasis.put(B, SourceExecutionBasis.identity(B, input.environment(),
                        ClosureEvidenceFactory.executionPolicy(cap + 1, Collections.emptyMap(), "independent B policy")));
                assertThrows(IllegalArgumentException.class, () -> new DefaultClosureProcessor(owner)
                        .processSameOrigin(input, attachmentPolicy, Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(), wrongBasis),
                        "An explicit independent expected basis is checked even on a fresh compatibility invocation");
                assertTrue(calls.isEmpty(), "Wrong-policy source cannot execute its authored handler");
            }
            ClosureExecutionRecorder recorder = new ClosureExecutionRecorder(input.invocationIdentity()); recorder.captureSourceObservation();
            try (ClosureExecutionSession driver = new ClosureExecutionSession(owner, input, recorder, ClosureExecutionSession.ExecutionMode.PROCESSING)) {
                SameOriginAttemptCoordinator groups = driver.useSameOriginGroups(owner,
                        authorizeViews ? attachmentPolicy : SameOriginAttachmentPolicy.empty());
                driver.admittedFreshSources(new java.util.HashSet<>(Arrays.asList(A, B, C)));
                if (!authorizeViews) {
                    assertThrows(blue.language.processor.InvalidExecutionEvidenceException.class, driver::execute);
                    assertTrue(groups.attempt(B).admissions().isEmpty(), "Historical A0 does not authorize installing tentative A1");
                    return new long[0];
                }
                ClosureExecutionState state = driver.execute();
                if (joinedFatal) {
                    assertEquals(Arrays.asList("B own", "A conditional joins C", "B joined failure"), calls);
                    assertEquals(1, groups.activeAttempts().size());
                    assertEquals(new java.util.TreeSet<DocumentId>(Arrays.asList(A, B, C)), groups.attempt(B).members());
                    assertEquals(2, groups.attempt(B).admissions().size());
                    assertEquals("joined producer rejection", groups.attempt(B).failure().diagnostic.message());
                    assertEquals(ProcessorStatus.RUNTIME_FATAL, groups.attempt(B).failure().status);
                    assertTrue(groups.invalidations().isEmpty());
                    assertEquals(a.blueId(), state.tentativeSnapshot().managedDocument(A).blueId());
                    assertEquals(b.blueId(), state.tentativeSnapshot().managedDocument(B).blueId());
                    assertEquals(c.blueId(), state.tentativeSnapshot().managedDocument(C).blueId());
                    assertTrue(state.checkpointMutations().isEmpty());
                    assertTrue(state.publicEvents().isEmpty());
                    assertEquals(0, groups.attempt(B).failure().reservedGas);
                    assertEquals(groups.attempt(B).failure().admittedGas, groups.attempt(B).failure().canonicalGasTrace.stream()
                            .mapToLong(blue.language.processor.GasTraceEntry::subtotal).sum());
                    List<SameOriginOperationResult> operations = driver.sameOriginOperations();
                    assertEquals(1, operations.size());
                    assertEquals(new java.util.TreeSet<DocumentId>(Arrays.asList(A, B, C)), operations.get(0).ownedDocumentIds());
                    assertEquals(ProcessorStatus.RUNTIME_FATAL, operations.get(0).status());
                    assertFalse(operations.get(0).sourceProgram().isPresent());
                    return new long[0];
                }
                if (!rejected) {
                    assertEquals(1, groups.activeAttempts().size());
                    assertNull(groups.attempt(B).failure());
                    assertEquals(2, groups.attempt(B).admissions().size());
                    List<blue.language.processor.GasMeter.GroupContribution> contributions = groups.attempt(B).admissions().get(1).admittedContributions.contributions();
                    assertEquals(2, contributions.size());
                    List<SameOriginOperationResult> operations = driver.sameOriginOperations();
                    assertEquals(1, operations.size());
                    assertEquals(ProcessorStatus.SUCCESS, operations.get(0).status());
                    assertEquals(4, operations.get(0).sourceProgram().get().steps().size());
                    assertEquals(2, operations.get(0).admissions().size());
                    SourceObservationProgram program = operations.get(0).sourceProgram().get();
                    assertEquals(2, program.acceptedViews().size());
                    Node dBody = markInitialized(new Node().name("D").properties("b", new Node().blueId(b.blueId()))
                            .properties("seen", new Node().value(BigInteger.ZERO)).contracts(new Node()
                                    .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/b/x")))
                                    .properties("observeB", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("updates")))
                                    .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/b"))))));
                    ManagedDocumentSnapshot d = sourceState(D, dBody, 0);
                    List<ManagedOccurrenceBinding> observerBindings = new ArrayList<>(bindings);
                    observerBindings.add(c34Binding(D, "/b", B, b.blueId(), true, bindingPolicy));
                    AffectedClosureSnapshot observerSnapshot = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(a, b, c, d), observerBindings,
                            Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a),
                                    ClosureEvidenceFactory.acyclicComponent(c), ClosureEvidenceFactory.acyclicComponent(d)), Arrays.asList(A, B, C, D));
                    ClosureInvocationInput observerInput = ClosureEvidenceFactory.processClosure(observerSnapshot, input.cause(), input.directDeliveries(),
                            input.executionPolicy(), input.environment());
                    List<String> canonicalCalls = new ArrayList<>(calls);
                    String expectedGasIdentity = null;
                    String expectedGroupedIdentity = null, expectedGroupedGas = null;
                    for (boolean cold : new boolean[]{false, true}) {
                        calls.clear();
                        ClosureAttemptResult observed = new DefaultClosureProcessor(owner).processExternalScope(observerInput, Collections.singleton(D),
                                Collections.singletonList(cold ? coldProgram(program) : program));
                        assertEquals(ProcessorStatus.SUCCESS, observed.processResult().status(), diagnostic(observed));
                        assertEquals(Collections.singletonList("D observes B1"), calls,
                                "Source group and its canonical attachment choices are replayed without business reexecution");
                        if (expectedGasIdentity == null) expectedGasIdentity = observed.processResult().gasTraceIdentity();
                        else assertEquals(expectedGasIdentity, observed.processResult().gasTraceIdentity());
                        calls.clear();
                        SameOriginProcessAttempt grouped = new DefaultClosureProcessor(owner).processSameOrigin(observerInput,
                                SameOriginAttachmentPolicy.empty(), Collections.singletonList(cold ? coldProgram(program) : program),
                                Collections.emptyMap(), Collections.emptyList());
                        assertTrue(grouped.complete()); assertEquals(1, grouped.operations().size());
                        SameOriginOperationResult observer = grouped.operations().get(0);
                        assertEquals(Collections.singleton(D), observer.ownedDocumentIds());
                        assertEquals(ProcessorStatus.SUCCESS, observer.status());
                        assertEquals(Collections.singletonList("D observes B1"), calls);
                        assertEquals(Collections.singletonMap(B, operations.get(0).operationIdentity()), observer.consumedSourceOperations());
                        if (expectedGroupedIdentity == null) {
                            expectedGroupedIdentity = observer.operationIdentity(); expectedGroupedGas = observer.gasTraceIdentity();
                        } else {
                            assertEquals(expectedGroupedIdentity, observer.operationIdentity());
                            assertEquals(expectedGroupedGas, observer.gasTraceIdentity());
                        }
                    }
                    // A completed source may already reside at its final joined SCC.
                    // Replaying its original topology must not choose that later graph
                    // as the observer's starting view or execute any producer again.
                    List<ManagedDocumentSnapshot> completedSources = new ArrayList<>();
                    for (ResultingDocument document : operations.get(0).resultingDocuments()) {
                        completedSources.add(new ManagedDocumentSnapshot(document.documentId(), document.afterBlueId(),
                                document.document(), document.initialized(), document.terminated(), true,
                                document.epoch(), document.componentGeneration()));
                    }
                    completedSources.add(d);
                    List<ManagedOccurrenceBinding> completedBindings = new ArrayList<>(operations.get(0).occurrenceBindings());
                    completedBindings.add(observerBindings.get(observerBindings.size() - 1));
                    List<ComponentSnapshot> completedComponents = new ArrayList<>(operations.get(0).resultingComponents());
                    completedComponents.add(ClosureEvidenceFactory.acyclicComponent(d));
                    List<ManagedReadPin> originalPins = new ArrayList<>();
                    for (ManagedDocumentSnapshot predecessor : Arrays.asList(a, b, c))
                        originalPins.add(ManagedReadPin.fromExactEvidence(predecessor.documentId(), predecessor.blueId(), predecessor.document(), null));
                    AffectedClosureSnapshot completedCut = ClosureEvidenceFactory.affectedClosure(2, completedSources,
                            completedBindings, completedComponents, Arrays.asList(A, B, C, D), originalPins);
                    calls.clear();
                    SameOriginProcessAttempt afterResident = new DefaultClosureProcessor(owner).processSameOrigin(
                            ClosureEvidenceFactory.processClosure(completedCut, input.cause(), input.directDeliveries(),
                                    input.executionPolicy(), input.environment()), SameOriginAttachmentPolicy.empty(),
                            Collections.singletonList(coldProgram(program)), Collections.emptyMap(), Collections.emptyList());
                    assertTrue(afterResident.complete()); assertEquals(1, afterResident.operations().size());
                    assertEquals(Collections.singletonList("D observes B1"), calls);
                    assertEquals(expectedGroupedIdentity, afterResident.operations().get(0).operationIdentity());
                    assertEquals(expectedGroupedGas, afterResident.operations().get(0).gasTraceIdentity());
                    calls.clear(); calls.addAll(canonicalCalls);
                    return new long[] {contributions.get(0).admitted() + contributions.get(0).reserved(),
                            contributions.get(1).admitted() + contributions.get(1).reserved()};
                }
                assertEquals(Arrays.asList("B own", "A conditional joins C", "A own sees B0", "C own sees A5"), calls);
                assertEquals(1, groups.invalidations().size());
                assertEquals(new java.util.TreeSet<DocumentId>(Arrays.asList(A, C)), groups.invalidations().get(0).reconstructOwnSeeds);
                SameOriginAttemptCoordinator.Failure failure = groups.attempt(B).failure();
                assertEquals(ProcessorStatus.RUNTIME_FATAL, failure.status);
                assertEquals(ProcessorErrorCategory.AtomicScopeGasAdmissionFailure, failure.diagnostic.category());
                assertNotNull(failure.rejectedAdmission); assertNull(failure.rejectedCharge);
                List<blue.language.processor.GasMeter.GroupContribution> contributions = failure.rejectedAdmission.contributions();
                assertEquals(expectedPrefix[0], contributions.get(0).admitted() + contributions.get(0).reserved());
                assertEquals(expectedPrefix[1], contributions.get(1).admitted() + contributions.get(1).reserved());
                assertEquals(3, groups.activeAttempts().size());
                assertNull(groups.attempt(A).failure()); assertNull(groups.attempt(C).failure());
                assertTrue(groups.attempt(A).admissions().isEmpty()); assertTrue(groups.attempt(C).admissions().isEmpty());
                assertEquals(Collections.singleton(B), groups.attempt(A).consumedIndependentSources().keySet());
                assertEquals(Collections.singleton(A), groups.attempt(C).consumedIndependentSources().keySet(),
                        "Fresh C consumes fresh A, never inherits discarded AC's incidental B dependency");
                assertEquals(b.blueId(), state.tentativeSnapshot().managedDocument(B).blueId());
                assertEquals(BigInteger.valueOf(5), state.tentativeSnapshot().managedDocument(A).document().getProperties().get("seen").getValue());
                assertEquals(BigInteger.valueOf(5), state.tentativeSnapshot().managedDocument(C).document().getProperties().get("seen").getValue());
                assertNull(state.tentativeSnapshot().managedDocument(A).document().getProperties().get("c"));
                assertEquals(1, driver.captureSameOriginGroup(A).steps().size());
                assertEquals(1, driver.captureSameOriginGroup(C).steps().size());
                assertEquals(2, state.checkpointMutations().size());
                List<SameOriginOperationResult> operations = driver.sameOriginOperations();
                assertEquals(Arrays.asList(Collections.singleton(B), Collections.singleton(A), Collections.singleton(C)),
                        operations.stream().map(SameOriginOperationResult::ownedDocumentIds).collect(java.util.stream.Collectors.toList()));
                assertEquals(ProcessorStatus.RUNTIME_FATAL, operations.get(0).status());
                assertEquals(Collections.singletonMap(B, operations.get(0).operationIdentity()), operations.get(1).consumedSourceOperations());
                assertEquals(Collections.singletonMap(A, operations.get(1).operationIdentity()), operations.get(2).consumedSourceOperations());
                assertEquals(1L, operations.get(1).resultingDocuments().get(0).epoch());
                assertEquals(1L, operations.get(2).resultingDocuments().get(0).epoch());
                return expectedPrefix;
            }
        }
    }

    @Test
    void sameOriginIndependentFailureRollsBackItsActualPatchAndEventWithoutStoppingTheOtherSeed() {
        final List<String> calls = new ArrayList<String>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        calls.add(context.contractKey());
                        if ("fail".equals(context.contractKey())) {
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.valueOf(99))));
                            context.emitEvent(new Node().name("must be rolled back"));
                            throw new blue.language.processor.ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure, "authored independent failure");
                        }
                    }
                }).build();
        try (DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).build()) {
            Node aBody = initializedDocument().name("A").properties("x", new Node().value(BigInteger.ZERO));
            aBody.getContracts().getProperties().remove("handler");
            aBody.getContracts().properties("fail", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")));
            ManagedDocumentSnapshot a = sourceState(A, aBody, 0);
            ManagedDocumentSnapshot b = sourceState(B, initializedDocument().name("B"), 0);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(a, b), Collections.emptyList(),
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(a), ClosureEvidenceFactory.acyclicComponent(b)), Arrays.asList(A, B));
            ClosureInvocationInput template = invocation(owner);
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, template.cause(), Arrays.asList(
                    new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0),
                    new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 1)), template.executionPolicy(), template.environment());
            try (ClosureExecutionSession driver = new ClosureExecutionSession(owner, input,
                    new ClosureExecutionRecorder(input.invocationIdentity()), ClosureExecutionSession.ExecutionMode.PROCESSING)) {
                SameOriginAttemptCoordinator groups = driver.useSameOriginGroups(owner);
                ClosureExecutionState state = driver.execute();
                assertEquals(Arrays.asList("fail", "handler"), calls);
                assertEquals(a.blueId(), state.tentativeSnapshot().managedDocument(A).blueId());
                assertEquals(BigInteger.ZERO, state.tentativeSnapshot().managedDocument(A).document().getProperties().get("x").getValue());
                assertTrue(state.publicEvents().isEmpty());
                assertEquals(1, state.checkpointMutations().size());
                assertEquals(ProcessorStatus.RUNTIME_FATAL, groups.attempt(A).failure().status);
                assertNull(groups.attempt(B).failure());
                assertTrue(groups.attempt(A).failure().admittedGas > 0);
            }
        }
    }

    @Test
    void sameOriginActualSourceWorkEventsAndGasIgnoreUnrelatedSeedsVisibilityAndPhysicalGeneration() {
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        if ("emit".equals(context.contractKey())) {
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                            context.emitEvent(new Node().name("source event"));
                        }
                    }
                }).build();
        try (DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).build()) {
            Node sourceBody = initializedDocument().name("Source B").properties("x", new Node().value(BigInteger.ZERO));
            sourceBody.getContracts().getProperties().remove("handler");
            sourceBody.getContracts().properties("emit", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")));
            ClosureInvocationInput template = invocation(owner);
            List<java.util.Map<String, Object>> expectedGas = null;
            String expectedWork = null, expectedEvent = null, expectedBody = null;
            for (boolean withUnrelatedSeed : Arrays.asList(false, true)) {
                ManagedDocumentSnapshot b = new ManagedDocumentSnapshot(B, DirectBlueIdCalculator.calculateBlueId(sourceBody), sourceBody,
                        true, false, !withUnrelatedSeed, 0, withUnrelatedSeed ? 73 : 1);
                List<ManagedDocumentSnapshot> documents = new ArrayList<ManagedDocumentSnapshot>();
                List<ComponentSnapshot> components = new ArrayList<ComponentSnapshot>();
                List<DirectLogicalDelivery> deliveries = new ArrayList<DirectLogicalDelivery>();
                if (withUnrelatedSeed) {
                    ManagedDocumentSnapshot a = sourceState(A, initializedDocument().name("Unrelated A"), 0);
                    documents.add(a); components.add(ClosureEvidenceFactory.acyclicComponent(a));
                    deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0));
                }
                documents.add(b); components.add(ClosureEvidenceFactory.acyclicComponent(b));
                deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", withUnrelatedSeed ? 1 : 0));
                AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(withUnrelatedSeed ? 73 : 1, documents,
                        Collections.emptyList(), components, Collections.singletonList(withUnrelatedSeed ? A : B));
                ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, template.cause(), deliveries,
                        template.executionPolicy(), template.environment());
                ClosureExecutionRecorder recorder = new ClosureExecutionRecorder(input.invocationIdentity());
                try (ClosureExecutionSession driver = new ClosureExecutionSession(owner, input, recorder, ClosureExecutionSession.ExecutionMode.PROCESSING)) {
                    SameOriginAttemptCoordinator groups = driver.useSameOriginGroups(owner);
                    ClosureExecutionState state = driver.execute();
                    List<java.util.Map<String, Object>> gas = new ArrayList<java.util.Map<String, Object>>();
                    for (GasTraceEntry charge : ClosureResultAssemblySupport.gasTrace(groups.runtime(B).gasTrace())) gas.add(charge.identityValue());
                    String work = recorder.snapshot(null).workTrace().stream().filter(w -> w.targetDocumentId().equals(B)).findFirst().get().workIdentity();
                    String event = state.publicEvents().get(0).eventOccurrenceIdentity();
                    String body = state.tentativeSnapshot().managedDocument(B).blueId();
                    if (!withUnrelatedSeed) { expectedGas = gas; expectedWork = work; expectedEvent = event; expectedBody = body; }
                    else {
                        assertEquals(expectedWork, work); assertEquals(expectedEvent, event); assertEquals(expectedBody, body);
                        assertEquals(expectedGas, gas, "Exact admitted source trace, not merely total, is seed-intrinsic");
                    }
                }
            }
        }
    }

    @Test
    void sameOriginActualConditionalFailureIsDiscardedWhenItsStillRunningProducerLaterFails() {
        verifyConditionalFailureReconstruction(false);
    }

    @Test
    void sameOriginReconstructsStillDueOwnSeedAfterItsConditionalCallbackFailureWasInvalidated() {
        verifyConditionalFailureReconstruction(true);
    }

    @Test
    void sameOriginConditionalObserverImportGasFailureIsInvalidatedWhenIndependentProducerFailsLater() {
        long admittedBeforeObservation = verifyConditionalFailureReconstruction(false, null);
        assertTrue(admittedBeforeObservation > 0);
        verifyConditionalFailureReconstruction(false, Long.valueOf(admittedBeforeObservation));
    }

    private long verifyConditionalFailureReconstruction(boolean ownSeed) {
        return verifyConditionalFailureReconstruction(ownSeed, null);
    }

    private long verifyConditionalFailureReconstruction(boolean ownSeed, Long observerLocalCap) {
        final List<String> calls = new ArrayList<String>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        if ("own".equals(context.contractKey())) {
                            calls.add("A own seed sees " + context.resolvedFrozenAt("/b/x").getValue());
                            context.applyPatch(JsonPatch.replace("/seen", new Node().value(BigInteger.valueOf(5))));
                            return;
                        }
                        if ("rejectObserver".equals(context.contractKey())) {
                            assertEquals("/b/x", context.event().getProperties().get("path").getValue());
                            assertEquals(BigInteger.ZERO, context.event().getProperties().get("before").getValue());
                            assertEquals(BigInteger.ONE, context.event().getProperties().get("after").getValue());
                            calls.add("A sees " + context.resolvedFrozenAt("/b/x").getValue());
                            context.applyPatch(JsonPatch.replace("/seen", new Node().value(BigInteger.ONE)));
                            throw new blue.language.processor.ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure, "conditional observer rejection");
                        }
                        if ("handler".equals(context.contractKey())) {
                            calls.add("B starts");
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                            return;
                        }
                        calls.add("B continues after A candidate failure");
                        throw new blue.language.processor.ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure, "independent producer rejection");
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build()) {
            ClosureInvocationInput template = invocation(owner);
            Node bBody = initializedDocument().name("B").properties("x", new Node().value(BigInteger.ZERO));
            bBody.getContracts().properties("rejectProducer", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")));
            ManagedDocumentSnapshot b = sourceState(B, bBody, 0);
            Node aBody = markInitialized(new Node().name("A").properties("b", new Node().blueId(b.blueId()))
                    .properties("seen", new Node().value(BigInteger.ZERO)).contracts(new Node()
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/b"))))
                            .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/b/x")))
                            .properties("source", typed(CHANNEL_BLUE_ID))
                            .properties("own", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")))
                            .properties("rejectObserver", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("updates")))));
            ManagedDocumentSnapshot a = sourceState(A, aBody, 0);
            List<ManagedOccurrenceBinding> bindings = Collections.singletonList(c34Binding(A, "/b", B, b.blueId(), true,
                    template.environment().managedBindingPolicyIdentity()));
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(a, b), bindings,
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a)), Arrays.asList(A, B));
            List<DirectLogicalDelivery> deliveries = new ArrayList<DirectLogicalDelivery>();
            deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 0));
            if (ownSeed) deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 1));
            ExecutionPolicy policy = template.executionPolicy();
            if (observerLocalCap != null) {
                ExecutionPolicy provisional = new ExecutionPolicy(hash('f'), policy.sharedLimit(), Collections.singletonMap(A, observerLocalCap), "observer-import-local-cap");
                policy = new ExecutionPolicy(IDENTITIES.executionPolicyIdentity(provisional), provisional.sharedLimit(), provisional.localLimits(), provisional.label());
            }
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, template.cause(), deliveries, policy, template.environment());
            ClosureExecutionRecorder captured = new ClosureExecutionRecorder(input.invocationIdentity());
            captured.captureSourceObservation();
            try (ClosureExecutionSession driver = new ClosureExecutionSession(owner, input,
                    captured, ClosureExecutionSession.ExecutionMode.PROCESSING)) {
                SameOriginAttemptCoordinator groups = driver.useSameOriginGroups(owner);
                ClosureExecutionState state = driver.execute();
                List<String> expectedCalls = new ArrayList<String>(); expectedCalls.add("B starts");
                if (observerLocalCap == null) expectedCalls.add("A sees 1");
                expectedCalls.add("B continues after A candidate failure");
                if (ownSeed) expectedCalls.add("A own seed sees 0");
                assertEquals(expectedCalls, calls,
                        groups.invalidations().isEmpty() ? "no invalidated observer" : groups.invalidations().get(0).discardedAttempt.failure().diagnostic.message());
                assertEquals(BigInteger.valueOf(ownSeed ? 5 : 0), state.tentativeSnapshot().managedDocument(A).document().getProperties().get("seen").getValue());
                if (!ownSeed) assertEquals(a.blueId(), state.tentativeSnapshot().managedDocument(A).blueId());
                assertEquals(b.blueId(), state.tentativeSnapshot().managedDocument(B).blueId());
                assertEquals(1, groups.invalidations().size());
                SameOriginAttemptCoordinator.Attempt discarded = groups.invalidations().get(0).discardedAttempt;
                assertEquals(SameOriginAttemptCoordinator.State.INVALIDATED, discarded.state());
                assertEquals(observerLocalCap == null ? ProcessorStatus.RUNTIME_FATAL : ProcessorStatus.GAS_LIMIT_EXCEEDED, discarded.failure().status);
                if (observerLocalCap == null) assertEquals("conditional observer rejection", discarded.failure().diagnostic.message());
                else {
                    assertNotNull(discarded.failure().rejectedCharge);
                    assertEquals("processEmbeddedEdgeExamined", discarded.failure().rejectedCharge.counter());
                    assertEquals(A.value(), discarded.failure().rejectedCharge.chargeContext().documentId());
                    assertNull(discarded.failure().rejectedAdmission);
                }
                assertEquals(ownSeed ? 2 : 1, groups.activeAttempts().size(), "A's conditional failure is not a second terminal operation");
                if (ownSeed) assertNull(groups.attempt(A).failure(), "A's still-due seed uses a new successful attempt");
                if (ownSeed) {
                    SourceObservationRecorder.Captured surviving = driver.captureSameOriginGroup(A);
                    assertEquals(1, surviving.steps().size(), "The discarded callback, its partial actions and projections cannot enter the fresh program");
                    assertEquals(WorkKind.EXTERNAL_DELIVERY, surviving.steps().get(0).kind());
                    assertEquals(BigInteger.ZERO, surviving.steps().get(0).beforeBody().getProperties().get("seen").getValue());
                    assertEquals(BigInteger.valueOf(5), surviving.steps().get(0).resultingBody().getProperties().get("seen").getValue());
                    assertTrue(surviving.projections().isEmpty());
                }
                assertEquals("independent producer rejection", groups.attempt(B).failure().diagnostic.message());
                assertTrue(state.publicEvents().isEmpty()); assertEquals(ownSeed ? 1 : 0, state.checkpointMutations().size());
                long admission = 0;
                for (blue.language.processor.GasTraceEntry charge : discarded.failure().memberTraces.get(A)) {
                    if (charge.reason().startsWith("admission.")) admission += charge.subtotal();
                }
                List<SameOriginOperationResult> operations = driver.sameOriginOperations();
                assertEquals(ownSeed ? 2 : 1, operations.size());
                assertEquals(Collections.singleton(B), operations.get(0).ownedDocumentIds());
                assertEquals(ProcessorStatus.RUNTIME_FATAL, operations.get(0).status());
                if (ownSeed) {
                    assertEquals(Collections.singleton(A), operations.get(1).ownedDocumentIds());
                    assertEquals(ProcessorStatus.SUCCESS, operations.get(1).status());
                    assertEquals(Collections.singletonMap(B, operations.get(0).operationIdentity()), operations.get(1).consumedSourceOperations());
                }
                return admission;
            }
        }
    }

    @Test
    void retainedOriginalPatchVisitsAliasesSequentiallyAndPreservesDescendantUpdatePayload() {
        final List<String> reads = new ArrayList<String>();
        final int[] sourceCalls = {0};
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                        if ("notHistoricalDirect".equals(context.contractKey())) throw new AssertionError("A managed reaction redelivered the consumer's own external channel");
                        if ("handler".equals(context.contractKey())) {
                            sourceCalls[0]++;
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                            return;
                        }
                        Node event = context.event();
                        assertEquals("replace", event.getProperties().get(blue.language.processor.util.ProcessorContractConstants.KEY_OPERATION).getValue());
                        assertEquals(BigInteger.ZERO, event.getProperties().get("before").getValue());
                        assertEquals(BigInteger.ONE, event.getProperties().get("after").getValue());
                        reads.add(event.getProperties().get("path").getValue() + ":"
                                + context.resolvedFrozenAt("/left/x").getValue() + ":"
                                + context.resolvedFrozenAt("/right/x").getValue());
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        final SourceObservationProgram[] captured = {null};
        ClosureExecutionObserver capture = new ClosureExecutionObserver() {
            @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            @Override public boolean capturesSourceObservationProgram() { return true; }
            @Override public void onSourceObservationProgram(SourceObservationProgram program) { captured[0] = program; }
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot b = sourceState(B, initializedDocument().name("B").properties("x", new Node().value(BigInteger.ZERO)), 0);
            ClosureInvocationInput sourceInput = sourceInvocation(template, b, new Node().value("one patch"), 1);
            ClosureAttemptResult source = contracts.processExternalScope(sourceInput, Collections.singleton(B), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, source.processResult().status(), diagnostic(source));
            SourceObservationProgram program = captured[0];
            Node aBody = markInitialized(new Node().name("Aliases")
                    .properties("left", new Node().blueId(b.blueId())).properties("right", new Node().blueId(b.blueId()))
                    .contracts(new Node()
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths",
                                    new Node().items(new Node().value("/left"), new Node().value("/right"))))
                            .properties("leftChanges", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/left")))
                            .properties("rightChanges", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/right/x")))
                            .properties("source", typed(CHANNEL_BLUE_ID))
                            .properties("notHistoricalDirect", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")))
                            .properties("readLeft", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("leftChanges")))
                            .properties("readRight", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("rightChanges")))));
            ManagedDocumentSnapshot a = sourceState(A, aBody, 0);
            List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                    c34Binding(A, "/left", B, b.blueId(), true, template.environment().managedBindingPolicyIdentity()),
                    c34Binding(A, "/right", B, b.blueId(), true, template.environment().managedBindingPolicyIdentity()));
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(a, b), bindings,
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a)), Arrays.asList(A, B));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, sourceInput.cause(), sourceInput.directDeliveries(),
                    template.executionPolicy(), template.environment());
            List<Map<String, Object>> expectedGas = null;
            for (boolean cold : new boolean[]{false, true}) {
                reads.clear();
                ClosureAttemptResult observed = contracts.processExternalScope(input, Collections.singleton(A),
                        Collections.singletonList(cold ? coldProgram(program) : program));
                assertEquals(ProcessorStatus.SUCCESS, observed.processResult().status(), diagnostic(observed));
                assertEquals(Arrays.asList("/left/x:1:0", "/right/x:1:1"), reads,
                        "A broad /left watcher receives the original nested patch once, before /right advances");
                assertEquals(1, sourceCalls[0], "Alias observation and cold replay cannot rerun producer business work");
                List<Map<String, Object>> gas = new ArrayList<Map<String, Object>>();
                for (GasTraceEntry entry : observed.processResult().gasTrace()) gas.add(entry.identityValue());
                if (expectedGas == null) expectedGas = gas; else assertEquals(expectedGas, gas);
            }
            for (boolean pending : new boolean[]{false, true}) {
                ManagedOccurrenceBinding left = bindings.get(0);
                List<ManagedOccurrenceBinding> historyBindings = Arrays.asList(ManagedOccurrenceBinding.derived(left.bindingPolicyIdentity(),
                        A, left.sourceAddress(), B, b.blueId(), !pending, pending ? Long.valueOf(0) : null), bindings.get(1));
                AffectedClosureSnapshot historySnapshot = ClosureEvidenceFactory.affectedClosure(1, Arrays.asList(a, b), historyBindings,
                        Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a)), Arrays.asList(A, B));
                List<DirectLogicalDelivery> historyDeliveries = new ArrayList<>(sourceInput.directDeliveries());
                historyDeliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "consumer-own", 2));
                ClosureInvocationInput historyInput = ClosureEvidenceFactory.processClosure(historySnapshot, sourceInput.cause(), historyDeliveries,
                        template.executionPolicy(), template.environment());
                ManagedReactionContext reaction = new ManagedReactionContext(hash('1'), hash('2'), hash('3'),
                        ExternalOrderKey.of(Arrays.<Object>asList(BigInteger.valueOf(100), C34_X_BLUE_ID)), hash('4'),
                        Collections.singletonList(new ManagedReactionContext.DueOccurrence(left.occurrenceIdentity(), A, B,
                                program.invocationIdentity(), hash('5'))));
                String gasIdentity = null;
                for (boolean cold : new boolean[]{false, true}) {
                    reads.clear();
                    ClosureAttemptResult history = contracts.processManagedReaction(historyInput, Collections.singleton(A),
                            Collections.singletonList(cold ? coldProgram(program) : program), Collections.emptyMap(), Collections.emptyList(), reaction);
                    assertEquals(ProcessorStatus.SUCCESS, history.processResult().status(), diagnostic(history));
                    assertEquals(Collections.singletonList("/left/x:1:0"), reads, "Only the selected historical occurrence advances");
                    assertEquals(1, sourceCalls[0]);
                    ManagedOccurrenceBinding resultingLeft = history.processResult().occurrenceBindings().stream()
                            .filter(row -> row.occurrenceIdentity().equals(left.occurrenceIdentity())).findFirst().get();
                    assertEquals(!pending, resultingLeft.active());
                    assertEquals(pending ? Long.valueOf(1) : null, resultingLeft.pendingHistoricalEpoch());
                    assertEquals(reaction.identity(), coldProgram(captured[0]).managedReaction().get().identity());
                    if (gasIdentity == null) gasIdentity = history.processResult().gasTraceIdentity();
                    else assertEquals(gasIdentity, history.processResult().gasTraceIdentity());
                }
            }
        }
    }

    @Test
    void executesAndCommitsARealDirectSeedAsOneIsolatedDocumentStep() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_BLUE_ID,
                                CHANNEL_TYPE,
                                new TestChannelProcessor())
                        .register(
                                HANDLER_BLUE_ID,
                                HANDLER_TYPE,
                                new TestHandlerProcessor())
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .build()) {
            Capture capture = new Capture();
            ClosureInvocationInput input = invocation(owner);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(),
                    diagnostic(attempt));
            assertTrue(attempt.processResult().commits());
            assertNull(attempt.processResult().diagnostic());
            assertNotNull(attempt.processResult()
                    .platformCommitCompanion());
            assertEquals(1, attempt.processResult()
                    .platformCommitCompanion().resultingDocuments().size());
            ClosureCommitCompanion.DocumentDelta committedDocument =
                    attempt.processResult().platformCommitCompanion()
                            .resultingDocuments().get(0);
            ResultingDocument resultDocument = attempt.processResult()
                    .resultingDocuments().get(0);
            assertEquals(ROOT, committedDocument.documentId());
            assertEquals(resultDocument.beforeBlueId(),
                    committedDocument.beforeBlueId());
            assertEquals(resultDocument.afterBlueId(),
                    committedDocument.afterBlueId());
            assertEquals(1,
                    attempt.processResult().checkpointWrites().size());
            assertEquals(1,
                    attempt.processResult().subscriptionDeltas().size());
            // This seed only changes checkpoint state.  With no document-body
            // change at a WORK finalization boundary, the durable document
            // epoch is preserved.
            assertEquals(0L,
                    attempt.processResult().resultingDocuments()
                            .get(0).epoch());
            assertNotNull(capture.evidence);
            assertEquals(1, capture.evidence.workTrace().size());
            assertEquals(1,
                    capture.evidence.documentStepTrace().size());
            DocumentStepEvidence step = capture.evidence
                    .documentStepTrace().get(0);
            assertEquals(ROOT, step.targetDocumentId());
            assertEquals(ROOT, step.executionRootDocumentId());
            assertEquals("/", step.scopePath());
            assertEquals("ISOLATED_DOCUMENT", step.executionMode());
            assertEquals(Collections.emptyList(),
                    step.ambientContainingDocumentIds());
            assertTrue(capture.evidence.complete());
            assertTrue(attempt.totalGas().longValue() > 0L);
        }
    }

    @Test
    void retainedSourceActionsSubstituteTheRealHandlerWithoutRepeatingIt() {
        final int[] calls = {0};
        final List<Object> bufferedReads = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                        if ("observe".equals(context.contractKey())) {
                            bufferedReads.add(context.resolvedFrozenAt("/source/x").getValue());
                            context.applyPatch(JsonPatch.add("/seen", context.resolvedFrozenAt("/source/x").toNode()));
                            return;
                        }
                        calls[0]++;
                        context.applyPatch(JsonPatch.add("/x", new Node().value(BigInteger.ONE)));
                        context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.valueOf(2L))));
                        context.emitEvent(new Node().value("done"));
                        context.emitEvent(new Node().value("done-again"));
                    }
                }).build();
        final SourceObservationProgram[] retained = {null};
        ClosureExecutionObserver observer = new ClosureExecutionObserver() {
            @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            @Override public boolean capturesSourceObservationProgram() { return true; }
            @Override public void onSourceObservationProgram(SourceObservationProgram program) {
                retained[0] = program;
            }
        };
        NodeProvider observationProvider = blueId -> {
            if (CHANNEL_BLUE_ID.equals(blueId)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(blueId)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(blueId);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    observationProvider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry)
                     .nodeProvider(observationProvider).snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner, observer)) {
            ClosureInvocationInput input = invocation(owner);
            ClosureAttemptResult original = contracts.processClosure(input);
            assertEquals(ProcessorStatus.SUCCESS, original.processResult().status(), diagnostic(original));
            assertNotNull(retained[0]);
            SourceObservationProgram originalProgram = coldProgram(retained[0]);
            assertEquals(1, calls[0]);
            List<SourceObservationProgram.Action> actions = retained[0].steps().get(0).actions();
            assertEquals(4, actions.size());
            assertEquals(BigInteger.ONE, ((SourceObservationProgram.Patch) actions.get(0))
                    .document().getProperties().get("x").getValue());
            assertEquals(BigInteger.valueOf(2L), ((SourceObservationProgram.Patch) actions.get(1))
                    .document().getProperties().get("x").getValue());
            ClosureAttemptResult replay = contracts.processWithSourceObservation(input, originalProgram);
            assertEquals(ProcessorStatus.SUCCESS, replay.processResult().status(), diagnostic(replay));
            assertEquals(1, calls[0], "Retained execution must not call the source handler");
            assertEquals(original.processResult().resultingDocuments().get(0).afterBlueId(),
                    replay.processResult().resultingDocuments().get(0).afterBlueId(),
                    "original=" + blue.language.model.NodeWireForm.get(original.processResult().resultingDocuments().get(0).document())
                            + " replay=" + blue.language.model.NodeWireForm.get(replay.processResult().resultingDocuments().get(0).document()));
            assertEquals(original.processResult().publicEvents().get(0).eventOccurrenceIdentity(),
                    ((SourceObservationProgram.Enqueue) actions.get(2)).occurrenceIdentity());
            assertTrue(replay.processResult().publicEvents().isEmpty(),
                    "Import cannot republish the source's already authoritative public emission");

            ManagedDocumentSnapshot source = input.snapshot().managedDocument(ROOT);
            Node consumer = markInitialized(new Node().name("Consumer")
                    .properties("source", new Node().blueId(source.blueId()))
                    .contracts(new Node()
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                    .properties("paths", new Node().items(new Node().value("/source"))))
                            .properties("fromSource", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                                    .properties("sourcePath", new Node().value("/source")))
                            .properties("observe", typed(HANDLER_BLUE_ID)
                                    .properties("channel", new Node().value("fromSource")))));
            ManagedDocumentSnapshot parent = new ManagedDocumentSnapshot(A,
                    DirectBlueIdCalculator.calculateBlueId(consumer), consumer, true, false, true, 0L, 1L);
            ManagedOccurrenceBinding binding = c34Binding(A, "/source", ROOT, source.blueId(), true,
                    input.environment().managedBindingPolicyIdentity());
            AffectedClosureSnapshot consumerSnapshot = ClosureEvidenceFactory.affectedClosure(1L,
                    Arrays.asList(parent, source), Collections.singletonList(binding),
                    Arrays.asList(input.snapshot().components().get(0), ClosureEvidenceFactory.acyclicComponent(parent)),
                    Arrays.asList(A, ROOT));
            ClosureInvocationInput consumerInput = ClosureEvidenceFactory.processClosure(consumerSnapshot,
                    input.cause(), input.directDeliveries(), input.executionPolicy(), input.environment());
            ClosureAttemptResult imported = contracts.processWithSourceObservation(consumerInput, originalProgram);
            assertEquals(ProcessorStatus.SUCCESS, imported.processResult().status(), diagnostic(imported));
            assertEquals(1, calls[0], "Import must execute the consumer without invoking the source");
            assertEquals(BigInteger.valueOf(2L), resultingDocument(imported, A).document()
                    .getProperties().get("seen").getValue());
            assertEquals(Arrays.asList(BigInteger.valueOf(2L), BigInteger.valueOf(2L)), bufferedReads,
                    "Buffered effects complete both patches before either emitted event is delivered");
        }
    }

    @Test
    void failedIndependentProducerIsNotReexecutedWhileOtherProducerAndOwnSeedComplete() {
        final int[] failedCalls = {0}, successfulCalls = {0}, ownCalls = {0};
        final List<String> views = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                        if ("failSource".equals(context.contractKey())) {
                            failedCalls[0]++;
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.valueOf(99L))));
                            throw new blue.language.processor.ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure, "Source A fails");
                        } else if ("handler".equals(context.contractKey())) {
                            successfulCalls[0]++;
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                            context.emitEvent(new Node().value("B completed"));
                        } else {
                            views.add(context.contractKey() + ":" + context.resolvedFrozenAt("/a/x").getValue()
                                    + ":" + context.resolvedFrozenAt("/b/x").getValue());
                            if ("own".equals(context.contractKey())) {
                                ownCalls[0]++;
                                context.applyPatch(JsonPatch.add("/own", new Node().value(BigInteger.valueOf(5L))));
                            }
                        }
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        final SourceObservationProgram[] captured = {null};
        ClosureExecutionObserver capture = new ClosureExecutionObserver() {
            @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            @Override public boolean capturesSourceObservationProgram() { return true; }
            @Override public void onSourceObservationProgram(SourceObservationProgram value) { captured[0] = value; }
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
            ClosureInvocationInput template = invocation(owner);
            Node aBody = initializedDocument().name("A").properties("x", new Node().value(BigInteger.ZERO));
            aBody.getContracts().getProperties().remove("handler");
            aBody.getContracts().properties("failSource", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")));
            ManagedDocumentSnapshot a0 = sourceState(A, aBody, 0L);
            ManagedDocumentSnapshot b0 = sourceState(B, initializedDocument().name("B").properties("x", new Node().value(BigInteger.ZERO)), 0L);
            Node event = new Node().value("one origin");
            ClosureInvocationInput aInput = sourceInvocation(template, a0, event, 1L);
            ClosureAttemptResult aResult = contracts.processExternalScope(aInput, Collections.singleton(A), Collections.emptyList());
            assertEquals(ProcessorStatus.RUNTIME_FATAL, aResult.processResult().status(), diagnostic(aResult));
            SourceOperationFailure failed = SourceOperationFailure.fromProcessClosure(aInput, aResult.processResult(), Collections.singleton(A));
            java.util.Map<String, byte[]> fragments = new java.util.HashMap<>();
            String failureIdentity = SourceOperationFailureCodec.encode(failed, (id, bytes) -> fragments.put(id, bytes.clone()),
                    FrozenNodeEvidenceCodec.Limits.defaults());
            SourceOperationFailure retainedFailure = SourceOperationFailureCodec.decode(failureIdentity,
                    id -> fragments.get(id).clone(), FrozenNodeEvidenceCodec.Limits.defaults());
            ClosureInvocationInput bInput = sourceInvocation(template, b0, event, 1L);
            ClosureAttemptResult bResult = contracts.processExternalScope(bInput, Collections.singleton(B), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, bResult.processResult().status(), diagnostic(bResult));
            SourceObservationProgram bProgram = coldProgram(captured[0]);
            assertThrows(IllegalArgumentException.class, () -> SourceOperationFailure.fromProcessClosure(bInput,
                    bResult.processResult(), Collections.singleton(B)));
            Node rootBody = markInitialized(new Node().name("Root")
                    .properties("a", new Node().blueId(a0.blueId())).properties("b", new Node().blueId(b0.blueId()))
                    .contracts(new Node().properties("source", typed(CHANNEL_BLUE_ID))
                            .properties("own", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("source")))
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                    .properties("paths", new Node().items(new Node().value("/a"), new Node().value("/b"))))
                            .properties("fromB", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/b")))
                            .properties("observe", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("fromB")))));
            ManagedDocumentSnapshot root = sourceState(ROOT, rootBody, 0L);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(a0, b0, root),
                    Arrays.asList(c34Binding(ROOT, "/a", A, a0.blueId(), true, template.environment().managedBindingPolicyIdentity()),
                            c34Binding(ROOT, "/b", B, b0.blueId(), true, template.environment().managedBindingPolicyIdentity())),
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(a0), ClosureEvidenceFactory.acyclicComponent(b0),
                            ClosureEvidenceFactory.acyclicComponent(root)), Arrays.asList(A, B, ROOT));
            List<DirectLogicalDelivery> deliveries = Arrays.asList(
                    new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0L),
                    new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 1L),
                    new DirectLogicalDelivery(ManagedScopeKey.root(ROOT), "source", "logical", 2L));
            ClosureInvocationInput rootInput = ClosureEvidenceFactory.processClosure(snapshot, bInput.cause(), deliveries,
                    template.executionPolicy(), template.environment());
            ClosureAttemptResult result = contracts.processExternalScope(rootInput, Collections.singleton(ROOT), Collections.singletonList(bProgram),
                    Collections.emptyMap(), Collections.singletonList(retainedFailure));
            assertEquals(ProcessorStatus.SUCCESS, result.processResult().status(), diagnostic(result));
            assertEquals(Arrays.asList("observe:0:1", "own:0:1"), views);
            assertEquals(1, failedCalls[0]); assertEquals(1, successfulCalls[0]); assertEquals(1, ownCalls[0]);
            assertEquals(BigInteger.valueOf(5L), resultingDocument(result, ROOT).document().getProperties().get("own").getValue());
            assertEquals(a0.blueId(), resultingDocument(result, A).afterBlueId());
            assertEquals(bResult.processResult().resultingDocuments().get(0).afterBlueId(), resultingDocument(result, B).afterBlueId());
            assertEquals(1L, result.processResult().gasTrace().stream().filter(charge -> "deliverySnapshotEntry".equals(charge.counter()))
                    .mapToLong(GasTraceEntry::quantity).sum(), "Only Root's own direct seed is newly admitted");
            assertTrue(result.processResult().gasTrace().stream().noneMatch(charge -> A.equals(charge.documentId()) || B.equals(charge.documentId())),
                    () -> "Independent producer failure/success gas is not charged to Root: " + result.processResult().gasTrace().stream()
                            .filter(charge -> A.equals(charge.documentId()) || B.equals(charge.documentId())).map(charge -> charge.documentId() + ":" + charge.counter())
                            .collect(java.util.stream.Collectors.joining(",")));
        }
    }

    @Test
    void failedParentLaterAdvancesAtSourceEntryThenPatchWhileDirectSourceWasAlreadyAhead() {
        final int[] sourceCalls = {0};
        final List<String> reads = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                        if ("handler".equals(context.contractKey())) {
                            sourceCalls[0]++;
                            BigInteger value = (BigInteger) context.resolvedFrozenAt("/x").getValue();
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(value.add(BigInteger.ONE))));
                            if ("E1".equals(context.event().getValue())) context.emitEvent(new Node().value("reject-parent"));
                        } else if ("reject".equals(context.contractKey())) {
                            throw new blue.language.processor.ProcessorFailureException(
                                    ProcessorErrorCategory.RuntimeExecutionFailure, "Consumed P observation failure");
                        } else if ("observe".equals(context.contractKey())) {
                            reads.add(context.resolvedFrozenAt("/p/s/x").getValue() + ":"
                                    + context.resolvedFrozenAt("/s/x").getValue());
                        }
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        final SourceObservationProgram[] captured = {null};
        ClosureExecutionObserver capture = new ClosureExecutionObserver() {
            @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            @Override public boolean capturesSourceObservationProgram() { return true; }
            @Override public void onSourceObservationProgram(SourceObservationProgram value) { captured[0] = value; }
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot s0 = sourceState(B, initializedDocument().name("S")
                    .properties("x", new Node().value(BigInteger.ZERO)), 0L);
            ClosureInvocationInput sFirstInput = sourceInvocation(template, s0, new Node().value("E1"), 1L);
            ClosureAttemptResult sFirst = contracts.processExternalScope(sFirstInput, Collections.singleton(B), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, sFirst.processResult().status(), diagnostic(sFirst));
            SourceObservationProgram s1Program = coldProgram(captured[0]);
            ManagedDocumentSnapshot s1 = sourceState(B, resultingDocument(sFirst, B).document(), 1L);
            ClosureInvocationInput sSecondInput = sourceInvocation(template, s1, new Node().value("E2"), 2L);
            ClosureAttemptResult sSecond = contracts.processExternalScope(sSecondInput, Collections.singleton(B), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, sSecond.processResult().status(), diagnostic(sSecond));
            SourceObservationProgram s2Program = coldProgram(captured[0]);
            Node parentBody = markInitialized(new Node().name("P").properties("s", new Node().blueId(s0.blueId()))
                    .contracts(new Node()
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                    .properties("paths", new Node().items(new Node().value("/s"))))
                            .properties("sourceChanged", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/s")))
                            .properties("noOp", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("sourceChanged")))
                            .properties("fromSource", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/s")))
                            .properties("reject", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("fromSource")))));
            ManagedDocumentSnapshot p0 = sourceState(A, parentBody, 0L);
            List<ManagedOccurrenceBinding> parentBindings = Collections.singletonList(c34Binding(A, "/s", B,
                    s0.blueId(), true, template.environment().managedBindingPolicyIdentity()));
            AffectedClosureSnapshot parentSnapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(p0, s0),
                    parentBindings, Arrays.asList(ClosureEvidenceFactory.acyclicComponent(s0), ClosureEvidenceFactory.acyclicComponent(p0)), Arrays.asList(A, B));
            ClosureInvocationInput pFirstInput = ClosureEvidenceFactory.processClosure(parentSnapshot, sFirstInput.cause(),
                    sFirstInput.directDeliveries(), template.executionPolicy(), template.environment());
            ClosureAttemptResult pFirst = contracts.processExternalScope(pFirstInput, Collections.singleton(A), Collections.singletonList(s1Program));
            assertEquals(ProcessorStatus.RUNTIME_FATAL, pFirst.processResult().status(), diagnostic(pFirst));
            assertEquals(p0.blueId(), resultingDocument(pFirst, A).afterBlueId());
            SourceObservationGap gap = SourceObservationGap.fromManagedFailure(A, B, pFirstInput, pFirst.processResult(), s1Program);
            ClosureInvocationInput pSecondInput = ClosureEvidenceFactory.processClosure(parentSnapshot, sSecondInput.cause(),
                    sSecondInput.directDeliveries(), template.executionPolicy(), template.environment());
            ClosureAttemptResult pSecond = contracts.processExternalScope(pSecondInput, Collections.singleton(A), Collections.singletonList(s2Program),
                    Collections.singletonMap(B, Collections.singletonList(gap)));
            assertEquals(ProcessorStatus.SUCCESS, pSecond.processResult().status(), diagnostic(pSecond));
            SourceObservationProgram p2Program = coldProgram(captured[0]);
            assertEquals(2, p2Program.referenceProjections().size(), "P alignment and the next S patch are separate source sites");
            assertEquals(s2Program.steps().get(0).entrySiteIdentity(), p2Program.referenceProjections().get(0).siteIdentity());
            Node rootBody = markInitialized(new Node().name("Root")
                    .properties("p", new Node().blueId(p0.blueId())).properties("s", new Node().blueId(s1.blueId()))
                    .contracts(new Node()
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                    .properties("paths", new Node().items(new Node().value("/p"), new Node().value("/s"))))
                            .properties("parentChanged", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/p")))
                            .properties("observe", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("parentChanged")))));
            ManagedDocumentSnapshot root = sourceState(ROOT, rootBody, 0L);
            List<ManagedOccurrenceBinding> bindings = new ArrayList<>(parentBindings);
            bindings.add(c34Binding(ROOT, "/p", A, p0.blueId(), true, template.environment().managedBindingPolicyIdentity()));
            bindings.add(c34Binding(ROOT, "/s", B, s1.blueId(), true, template.environment().managedBindingPolicyIdentity()));
            AffectedClosureSnapshot rootSnapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(p0, s1, root), bindings,
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(s1), ClosureEvidenceFactory.acyclicComponent(p0),
                            ClosureEvidenceFactory.acyclicComponent(root)), Arrays.asList(A, B, ROOT),
                    Collections.singletonList(ManagedReadPin.fromExactEvidence(B, s0.blueId(), s0.document(), null)));
            ClosureInvocationInput rootInput = ClosureEvidenceFactory.processClosure(rootSnapshot, sSecondInput.cause(),
                    sSecondInput.directDeliveries(), template.executionPolicy(), template.environment());
            ClosureAttemptResult rootResult = contracts.processExternalScope(rootInput, Collections.singleton(ROOT), Arrays.asList(p2Program, s2Program));
            assertEquals(ProcessorStatus.SUCCESS, rootResult.processResult().status(), diagnostic(rootResult));
            assertEquals(Arrays.asList("1:1", "2:2"), reads, "Only P aligns at Entry; direct S remains at S1 until its actual patch");
            assertEquals(2, sourceCalls[0]);
            assertEquals(resultingDocument(pSecond, A).afterBlueId(), resultingDocument(rootResult, A).afterBlueId());
            assertEquals(resultingDocument(sSecond, B).afterBlueId(), resultingDocument(rootResult, B).afterBlueId());
        }
    }

    @Test
    void eventlessParentReferenceProjectionsReplayAtTheOriginalSourcePatchSites() {
        final int[] sourceCalls = {0};
        final List<String> reads = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                        if ("handler".equals(context.contractKey())) {
                            sourceCalls[0]++;
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.valueOf(2L))));
                        } else {
                            reads.add(context.resolvedFrozenAt("/p/s/x").getValue() + ":"
                                    + context.resolvedFrozenAt("/s/x").getValue());
                        }
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        final SourceObservationProgram[] captured = {null};
        ClosureExecutionObserver capture = new ClosureExecutionObserver() {
            @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            @Override public boolean capturesSourceObservationProgram() { return true; }
            @Override public void onSourceObservationProgram(SourceObservationProgram value) { captured[0] = value; }
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot source = sourceState(B, initializedDocument().name("S")
                    .properties("x", new Node().value(BigInteger.ZERO)), 0L);
            ClosureInvocationInput sourceInput = sourceInvocation(template, source, new Node().value("E"), 1L);
            ClosureAttemptResult sourceResult = contracts.processExternalScope(sourceInput, Collections.singleton(B), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, sourceResult.processResult().status(), diagnostic(sourceResult));
            SourceObservationProgram sourceProgram = coldProgram(captured[0]);
            Node parentBody = markInitialized(new Node().name("Eventless P").properties("s", new Node().blueId(source.blueId()))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                            .properties("paths", new Node().items(new Node().value("/s"))))));
            ManagedDocumentSnapshot parent = sourceState(A, parentBody, 0L);
            List<ManagedOccurrenceBinding> parentBindings = Collections.singletonList(c34Binding(A, "/s", B,
                    source.blueId(), true, template.environment().managedBindingPolicyIdentity()));
            AffectedClosureSnapshot parentSnapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(parent, source),
                    parentBindings, Arrays.asList(ClosureEvidenceFactory.acyclicComponent(source),
                            ClosureEvidenceFactory.acyclicComponent(parent)), Arrays.asList(A, B));
            ClosureInvocationInput parentInput = ClosureEvidenceFactory.processClosure(parentSnapshot, sourceInput.cause(),
                    sourceInput.directDeliveries(), template.executionPolicy(), template.environment());
            ClosureAttemptResult parentResult = contracts.processExternalScope(parentInput, Collections.singleton(A),
                    Collections.singletonList(sourceProgram));
            assertEquals(ProcessorStatus.SUCCESS, parentResult.processResult().status(), diagnostic(parentResult));
            SourceObservationProgram parentProgram = coldProgram(captured[0]);
            assertEquals(2, parentProgram.referenceProjections().size(), "Both intermediate source patches need retained reference projections");
            assertTrue(parentProgram.steps().stream().noneMatch(step -> step.targetDocumentId().equals(A)), "P is genuinely eventless");
            Node rootBody = markInitialized(new Node().name("Root")
                    .properties("p", new Node().blueId(parent.blueId())).properties("s", new Node().blueId(source.blueId()))
                    .contracts(new Node()
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                    .properties("paths", new Node().items(new Node().value("/p"), new Node().value("/s"))))
                            .properties("parentChanged", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/p")))
                            .properties("observe", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("parentChanged")))));
            ManagedDocumentSnapshot root = sourceState(ROOT, rootBody, 0L);
            List<ManagedOccurrenceBinding> rootBindings = new ArrayList<>(parentBindings);
            rootBindings.add(c34Binding(ROOT, "/p", A, parent.blueId(), true, template.environment().managedBindingPolicyIdentity()));
            rootBindings.add(c34Binding(ROOT, "/s", B, source.blueId(), true, template.environment().managedBindingPolicyIdentity()));
            AffectedClosureSnapshot rootSnapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(parent, source, root),
                    rootBindings, Arrays.asList(ClosureEvidenceFactory.acyclicComponent(source), ClosureEvidenceFactory.acyclicComponent(parent),
                            ClosureEvidenceFactory.acyclicComponent(root)), Arrays.asList(A, B, ROOT));
            ClosureInvocationInput rootInput = ClosureEvidenceFactory.processClosure(rootSnapshot, sourceInput.cause(),
                    sourceInput.directDeliveries(), template.executionPolicy(), template.environment());
            ClosureAttemptResult rootResult = contracts.processExternalScope(rootInput, Collections.singleton(ROOT),
                    Arrays.asList(parentProgram, sourceProgram));
            assertEquals(ProcessorStatus.SUCCESS, rootResult.processResult().status(), diagnostic(rootResult));
            assertEquals(Arrays.asList("1:1", "2:2"), reads, "P must project at each original patch, not at source final settlement");
            assertEquals(1, sourceCalls[0], "Shared source is executed once across the diamond");
            assertEquals(resultingDocument(parentResult, A).afterBlueId(), resultingDocument(rootResult, A).afterBlueId());
            assertEquals(resultingDocument(sourceResult, B).afterBlueId(), resultingDocument(rootResult, B).afterBlueId());

            SourceObservationProgram.ReferenceProjection originalProjection = parentProgram.referenceProjections().get(0);
            Node injectedBody = originalProjection.afterBody().toNode().properties("injectedBusinessField", new Node().value(Boolean.TRUE));
            List<SourceObservationProgram.ReferenceProjection> injected = new ArrayList<>(parentProgram.referenceProjections());
            injected.set(0, new SourceObservationProgram.ReferenceProjection(originalProjection.siteIdentity(), B, A,
                    originalProjection.beforeBlueId(), DirectBlueIdCalculator.calculateBlueId(injectedBody), originalProjection.beforeBody(),
                    blue.language.snapshot.FrozenNode.fromResolvedNode(injectedBody), originalProjection.changes()));
            SourceObservationProgram malicious = withReferenceProjections(parentProgram, injected);
            assertThrows(IllegalArgumentException.class, () -> contracts.processExternalScope(rootInput,
                    Collections.singleton(ROOT), Arrays.asList(malicious, sourceProgram)), "Reference replay cannot smuggle business mutations");
            List<SourceObservationProgram.ReferenceProjection> wrongSite = new ArrayList<>(parentProgram.referenceProjections());
            wrongSite.set(0, new SourceObservationProgram.ReferenceProjection(
                    IDENTITIES.observationPatchSiteIdentity(sourceProgram.steps().get(0).entrySiteIdentity(), 99L), B, A,
                    originalProjection.beforeBlueId(), originalProjection.afterBlueId(), originalProjection.beforeBody(),
                    originalProjection.afterBody(), originalProjection.changes()));
            assertThrows(IllegalArgumentException.class, () -> contracts.processExternalScope(rootInput,
                    Collections.singleton(ROOT), Arrays.asList(withReferenceProjections(parentProgram, wrongSite), sourceProgram)),
                    "A valid reference advance cannot be moved to another source boundary");
            assertEquals(1, sourceCalls[0]);
        }
    }

    private static SourceObservationProgram withReferenceProjections(SourceObservationProgram source,
            List<SourceObservationProgram.ReferenceProjection> projections) {
        return new SourceObservationProgram(source.invocationIdentity(), source.causeKind(), source.causeIdentity(),
                source.externalCause(), source.environment(), source.executionPolicy(), source.sourcePredecessors(), source.sourceResults(),
                source.ownedDocumentIds(), source.steps(), projections);
    }

    @Test
    void exactPinnedParentViewRemainsIndependentOfTheOneCurrentSourceCell() {
        final List<String> reads = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                        reads.add(context.resolvedFrozenAt("/p/s/x").getValue() + ":"
                                + context.resolvedFrozenAt("/s/x").getValue());
                        context.applyPatch(JsonPatch.add("/observed", new Node().value(reads.get(reads.size() - 1))));
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot s0 = sourceState(B, markInitialized(new Node().name("S")
                    .properties("x", new Node().value(BigInteger.ZERO)).contracts(new Node())), 0L);
            ManagedDocumentSnapshot s1 = sourceState(B, markInitialized(new Node().name("S")
                    .properties("x", new Node().value(BigInteger.ONE)).contracts(new Node())), 1L);
            ManagedReadPin oldSource = ManagedReadPin.fromExactEvidence(B, s0.blueId(), s0.document(), null);
            assertThrows(IllegalArgumentException.class, () -> ManagedReadPin.fromExactEvidence(B,
                    s0.blueId(), s1.document(), null));
            Node parentBody = markInitialized(new Node().name("P").properties("s", new Node().blueId(s0.blueId()))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                            .properties("paths", new Node().items(new Node().value("/s"))))));
            ManagedDocumentSnapshot parent = sourceState(A, parentBody, 0L);
            Node rootBody = initializedDocument().name("Root").properties("p", new Node().blueId(parent.blueId()))
                    .properties("s", new Node().blueId(s1.blueId()));
            rootBody.getContracts().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                    .properties("paths", new Node().items(new Node().value("/p"), new Node().value("/s"))));
            ManagedDocumentSnapshot root = sourceState(ROOT, rootBody, 0L);
            List<ManagedDocumentSnapshot> documents = Arrays.asList(parent, s1, root);
            List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                    c34Binding(A, "/s", B, s0.blueId(), true, template.environment().managedBindingPolicyIdentity()),
                    c34Binding(ROOT, "/p", A, parent.blueId(), true, template.environment().managedBindingPolicyIdentity()),
                    c34Binding(ROOT, "/s", B, s1.blueId(), true, template.environment().managedBindingPolicyIdentity()));
            List<ComponentSnapshot> components = Arrays.asList(ClosureEvidenceFactory.acyclicComponent(s1),
                    ClosureEvidenceFactory.acyclicComponent(parent), ClosureEvidenceFactory.acyclicComponent(root));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.affectedClosure(1L, documents,
                    bindings, components, Arrays.asList(A, B, ROOT)));
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L, documents,
                    bindings, components, Arrays.asList(A, B, ROOT), Collections.singletonList(oldSource));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, template.cause(),
                    template.directDeliveries(), template.executionPolicy(), template.environment());
            ClosureAttemptResult attempt = contracts.processExternalScope(input, Collections.singleton(ROOT), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, attempt.processResult().status(), diagnostic(attempt));
            assertEquals(Collections.singletonList("0:1"), reads);
            assertEquals(parent.blueId(), resultingDocument(attempt, A).afterBlueId(), "Read-only P must retain its exact S0 pin");
            assertEquals(s1.blueId(), resultingDocument(attempt, B).afterBlueId(), "There is only one current S cell, at S1");
            assertEquals("0:1", resultingDocument(attempt, ROOT).document().getProperties().get("observed").getValue());
        }
    }

    @Test
    void failedTwoProducerObservationAlignsEachSourceOnlyAtItsOwnEntry() {
        final int[] sourceCalls = {0};
        final List<String> reads = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                        if ("handler".equals(context.contractKey())) {
                            sourceCalls[0]++;
                            BigInteger before = (BigInteger) context.resolvedFrozenAt("/x").getValue();
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(before.add(BigInteger.ONE))));
                            if ("origin-1".equals(context.event().getValue())) context.emitEvent(new Node().value("reject-parent"));
                        } else if ("reject".equals(context.contractKey())) {
                            throw new blue.language.processor.ProcessorFailureException(
                                    ProcessorErrorCategory.RuntimeExecutionFailure, "Recognized managed observation failure");
                        } else {
                            reads.add(context.contractKey() + ":" + context.resolvedFrozenAt("/a/x").getValue()
                                    + ":" + context.resolvedFrozenAt("/b/x").getValue());
                        }
                    }
                }).build();
        NodeProvider provider = id -> {
            if (CHANNEL_BLUE_ID.equals(id)) return Collections.singletonList(CHANNEL_TYPE.clone());
            if (HANDLER_BLUE_ID.equals(id)) return Collections.singletonList(HANDLER_TYPE.clone());
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        };
        final SourceObservationProgram[] captured = {null};
        ClosureExecutionObserver capture = new ClosureExecutionObserver() {
            @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            @Override public boolean capturesSourceObservationProgram() { return true; }
            @Override public void onSourceObservationProgram(SourceObservationProgram value) { captured[0] = value; }
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
            ClosureInvocationInput template = invocation(owner);
            Node aBody = initializedDocument().name("Producer A").properties("x", new Node().value(BigInteger.ZERO));
            Node bBody = initializedDocument().name("Producer B").properties("x", new Node().value(BigInteger.ZERO));
            ManagedDocumentSnapshot a0 = sourceState(A, aBody, 0L);
            ManagedDocumentSnapshot b0 = sourceState(B, bBody, 0L);
            Node e1 = new Node().value("origin-1");
            Node e2 = new Node().value("origin-2");
            ClosureInvocationInput aFirst = sourceInvocation(template, a0, e1, 1L);
            ClosureAttemptResult aFirstResult = contracts.processExternalScope(aFirst, Collections.singleton(A), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, aFirstResult.processResult().status(), diagnostic(aFirstResult));
            SourceObservationProgram a1 = captured[0];
            ManagedDocumentSnapshot aBeforeSecond = sourceState(A, resultingDocument(aFirstResult, A).document(), 1L);
            ClosureAttemptResult aSecondResult = contracts.processExternalScope(sourceInvocation(template, aBeforeSecond, e2, 2L),
                    Collections.singleton(A), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, aSecondResult.processResult().status(), diagnostic(aSecondResult));
            SourceObservationProgram a2 = captured[0];
            ClosureAttemptResult bFirstResult = contracts.processExternalScope(sourceInvocation(template, b0, e1, 1L),
                    Collections.singleton(B), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, bFirstResult.processResult().status(), diagnostic(bFirstResult));
            SourceObservationProgram b1 = captured[0];
            ManagedDocumentSnapshot bBeforeSecond = sourceState(B, resultingDocument(bFirstResult, B).document(), 1L);
            ClosureAttemptResult bSecondResult = contracts.processExternalScope(sourceInvocation(template, bBeforeSecond, e2, 2L),
                    Collections.singleton(B), Collections.emptyList());
            assertEquals(ProcessorStatus.SUCCESS, bSecondResult.processResult().status(), diagnostic(bSecondResult));
            SourceObservationProgram b2 = captured[0];
            Node rootBody = markInitialized(new Node().name("Root")
                    .properties("a", new Node().blueId(a0.blueId())).properties("b", new Node().blueId(b0.blueId()))
                    .contracts(new Node()
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                    .properties("paths", new Node().items(new Node().value("/a"), new Node().value("/b"))))
                            .properties("aChanged", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/a")))
                            .properties("bChanged", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/b")))
                            .properties("onA", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("aChanged")))
                            .properties("onB", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("bChanged")))
                            .properties("fromSource", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                            .properties("reject", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("fromSource")))));
            ManagedDocumentSnapshot root = sourceState(ROOT, rootBody, 0L);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(a0, b0, root),
                    Arrays.asList(c34Binding(ROOT, "/a", A, a0.blueId(), true, template.environment().managedBindingPolicyIdentity()),
                            c34Binding(ROOT, "/b", B, b0.blueId(), true, template.environment().managedBindingPolicyIdentity())),
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(a0), ClosureEvidenceFactory.acyclicComponent(b0),
                            ClosureEvidenceFactory.acyclicComponent(root)), Arrays.asList(A, B, ROOT));
            List<DirectLogicalDelivery> deliveries = Arrays.asList(
                    new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0L),
                    new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 1L));
            ClosureInvocationInput failedInput = ClosureEvidenceFactory.processClosure(snapshot, aFirst.cause(), deliveries,
                    template.executionPolicy(), template.environment());
            ClosureAttemptResult failed = contracts.processExternalScope(failedInput, Collections.singleton(ROOT), Arrays.asList(a1, b1));
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.processResult().status(), diagnostic(failed));
            assertEquals(root.blueId(), resultingDocument(failed, ROOT).afterBlueId());
            SourceObservationGap gapA = SourceObservationGap.fromManagedFailure(ROOT, A, failedInput, failed.processResult(), a1);
            SourceObservationGap gapB = SourceObservationGap.fromManagedFailure(ROOT, B, failedInput, failed.processResult(), b1);
            assertThrows(IllegalArgumentException.class, () -> SourceObservationGap.fromManagedFailure(ROOT, A,
                    aFirst, aFirstResult.processResult(), a1), "Successful input is not a consumed failure gap");
            ClosureInvocationInput next = ClosureEvidenceFactory.processClosure(snapshot,
                    sourceInvocation(template, aBeforeSecond, e2, 2L).cause(), deliveries,
                    template.executionPolicy(), template.environment());
            assertThrows(IllegalArgumentException.class, () -> contracts.processExternalScope(next,
                    Collections.singleton(ROOT), Arrays.asList(b2, a2)), "An epoch jump alone cannot authorize alignment");
            reads.clear(); // Observation log only; handler behavior is fixed by exact E1/E2 payloads.
            java.util.Map<DocumentId, List<SourceObservationGap>> gaps = new LinkedHashMap<>();
            gaps.put(A, Collections.singletonList(gapA)); gaps.put(B, Collections.singletonList(gapB));
            ClosureAttemptResult recovered = contracts.processExternalScope(next, Collections.singleton(ROOT), Arrays.asList(b2, a2), gaps);
            assertEquals(ProcessorStatus.SUCCESS, recovered.processResult().status(), diagnostic(recovered));
            assertEquals(Arrays.asList("onA:1:0", "onA:2:0", "onB:2:1", "onB:2:2"), reads,
                    "B must remain at its successful pin until its own canonical entry, regardless of receipt arrival order");
            assertEquals(4, sourceCalls[0], "Neither failed observation nor later gap recovery executes source handlers again");
        }
    }

    private static ManagedDocumentSnapshot sourceState(DocumentId id, Node body, long epoch) {
        return new ManagedDocumentSnapshot(id, DirectBlueIdCalculator.calculateBlueId(body), body, true, false, true, epoch, 1L);
    }

    private static ClosureInvocationInput sourceInvocation(ClosureInvocationInput template, ManagedDocumentSnapshot source,
                                                          Node event, long order) {
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L, Collections.singletonList(source),
                Collections.emptyList(), Collections.singletonList(ClosureEvidenceFactory.acyclicComponent(source)),
                Collections.singletonList(source.documentId()));
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, DirectBlueIdCalculator.calculateBlueId(event),
                ExternalOrderKey.of(Arrays.asList(order, DirectBlueIdCalculator.calculateBlueId(event))),
                template.environment().externalOrderPolicyIdentity());
        return ClosureEvidenceFactory.processClosure(snapshot, cause, Collections.singletonList(
                new DirectLogicalDelivery(ManagedScopeKey.root(source.documentId()), "source", "logical", 0L)),
                template.executionPolicy(), template.environment());
    }

    @Test
    void sourceObservationRetainsTriggeredIntermediateReadsAndConsumerBeforeLaterSourceEnqueue() {
        final Node e1 = new Node().properties("id", new Node().value("E1"));
        final Node e2 = new Node().properties("id", new Node().value("E2"));
        final Node f2 = new Node().properties("id", new Node().value("F2"));
        final List<String> trace = new ArrayList<>();
        final int[] sourceCalls = {0};
        HandlerProcessor<TestHandler> handler = new HandlerProcessor<TestHandler>() {
            @Override public Class<TestHandler> contractType() { return TestHandler.class; }
            @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                String key = context.contractKey();
                if ("observe".equals(key)) {
                    String id = context.occurrenceEvent().getProperties().get("id").getValue().toString();
                    trace.add(id + ":" + context.resolvedFrozenAt("/source/x").getValue());
                    if ("E1".equals(id)) context.emitEvent(new Node().properties("id", new Node().value("P1")));
                } else {
                    sourceCalls[0]++;
                    if ("handler".equals(key)) { context.emitEvent(e1); context.emitEvent(e2); }
                    if ("first".equals(key)) context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                    if ("second".equals(key)) {
                        context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.valueOf(2L))));
                        context.emitEvent(f2);
                    }
                }
            }
        };
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, handler).build();
        NodeProvider provider = blueId -> {
            for (Node node : Arrays.asList(CHANNEL_TYPE, HANDLER_TYPE, e1, e2, f2)) {
                if (DirectBlueIdCalculator.calculateBlueId(node).equals(blueId)) return Collections.singletonList(node.clone());
            }
            return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(blueId);
        };
        final SourceObservationProgram[] program = {null};
        ClosureExecutionObserver observer = new ClosureExecutionObserver() {
            @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            @Override public boolean capturesSourceObservationProgram() { return true; }
            @Override public void onSourceObservationProgram(SourceObservationProgram value) { program[0] = value; }
        };
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner, observer)) {
            Node source = initializedDocument().properties("x", new Node().value(BigInteger.ZERO));
            source.getContracts()
                    .properties("onE1", typed(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
                            .properties("event", e1.clone()))
                    .properties("first", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("onE1")))
                    .properties("onE2", typed(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
                            .properties("event", e2.clone()))
                    .properties("second", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("onE2")));
            ClosureInvocationInput sourceInput = invocation(owner, 100000L, source);
            ClosureAttemptResult produced = contracts.processClosure(sourceInput);
            assertEquals(ProcessorStatus.SUCCESS, produced.processResult().status(), diagnostic(produced));
            assertEquals(3, sourceCalls[0]);
            SourceObservationProgram retained = coldProgram(program[0]);
            ManagedDocumentSnapshot sourceState = sourceInput.snapshot().managedDocument(ROOT);
            Node parentBody = markInitialized(new Node().name("Intermediate observer")
                    .properties("source", new Node().blueId(sourceState.blueId()))
                    .contracts(new Node()
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                    .properties("paths", new Node().items(new Node().value("/source"))))
                            .properties("fromSource", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                                    .properties("sourcePath", new Node().value("/source")))
                            .properties("observe", typed(HANDLER_BLUE_ID)
                                    .properties("channel", new Node().value("fromSource")))));
            ManagedDocumentSnapshot parent = new ManagedDocumentSnapshot(A,
                    DirectBlueIdCalculator.calculateBlueId(parentBody), parentBody, true, false, true, 0L, 1L);
            ManagedOccurrenceBinding binding = c34Binding(A, "/source", ROOT, sourceState.blueId(), true,
                    sourceInput.environment().managedBindingPolicyIdentity());
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L,
                    Arrays.asList(parent, sourceState), Collections.singletonList(binding),
                    Arrays.asList(sourceInput.snapshot().components().get(0), ClosureEvidenceFactory.acyclicComponent(parent)),
                    Arrays.asList(A, ROOT));
            ClosureInvocationInput consumerInput = ClosureEvidenceFactory.processClosure(snapshot, sourceInput.cause(),
                    sourceInput.directDeliveries(), sourceInput.executionPolicy(), sourceInput.environment());
            ClosureAttemptResult imported = contracts.processWithSourceObservation(consumerInput, retained);
            assertEquals(ProcessorStatus.SUCCESS, imported.processResult().status(), diagnostic(imported));
            assertEquals(3, sourceCalls[0], "All three source handlers must be substituted");
            assertEquals(Arrays.asList("E1:1", "E2:2", "F2:2"), trace);
            assertEquals(1, imported.processResult().publicEvents().size());
            assertEquals("P1", imported.processResult().publicEvents().get(0).event()
                    .getProperties().get("id").getValue());
            SourceObservationProgram consumed = program[0];
            List<String> enqueueOrder = new ArrayList<>();
            for (SourceObservationProgram.Step step : consumed.steps()) {
                for (SourceObservationProgram.Action action : step.actions()) {
                    if (action instanceof SourceObservationProgram.Enqueue) enqueueOrder.add(
                            ((SourceObservationProgram.Enqueue) action).event().getProperties().get("id").getValue().toString());
                }
            }
            assertTrue(enqueueOrder.indexOf("P1") < enqueueOrder.indexOf("F2"), enqueueOrder.toString());
        }
    }

    @Test
    void activatesOneDraftOnceAndRoutesItsEventThroughEveryOccurrence() {
        Node parent = managedDraftParentDocument();
        Node draft = managedDraftDocument(
                DirectBlueIdCalculator.calculateBlueId(parent));
        try (DocumentProcessor owner = managedDraftOwner(
                draft, true, false, false)) {
            Capture capture = new Capture();
            ClosureInvocationInput input = managedDraftInvocation(
                    owner, draft);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(), diagnostic(attempt));
            assertTrue(attempt.processResult().commits());
            assertEquals(Arrays.asList(
                            WorkKind.EXTERNAL_DELIVERY,
                            WorkKind.INITIALIZATION,
                            WorkKind.LIFECYCLE,
                            WorkKind.EMBEDDED_EVENT,
                            WorkKind.EMBEDDED_EVENT),
                    workKinds(capture.evidence.workTrace()));
            assertEquals(Arrays.asList(ROOT, B, B, ROOT, ROOT),
                    stepTargets(capture.evidence.documentStepTrace()));
            assertEquals(1L, capture.evidence.workTrace().stream()
                    .filter(work -> work.kind()
                            == WorkKind.INITIALIZATION)
                    .count());
            ResultingDocument child = resultingDocument(attempt, B);
            assertTrue(child.initialized());
            assertEquals(0L, child.epoch());
            Node marker = property(
                    child.document().getContracts(), "initialized");
            assertEquals(capture.evidence.tentativeFinalizations().get(0)
                            .memberBlueIds().get(B),
                    property(marker, "document").getBlueId());
            assertEquals(3, attempt.processResult()
                    .occurrenceBindings().size());
            int childOccurrences = 0;
            for (ManagedOccurrenceBinding binding
                    : attempt.processResult().occurrenceBindings()) {
                assertTrue(binding.active());
                if (binding.targetDocumentId().equals(B)) {
                    childOccurrences++;
                    assertEquals(child.afterBlueId(),
                            binding.expectedTargetBlueId());
                }
            }
            assertEquals(2, childOccurrences);
            assertEquals(Arrays.asList(
                            TentativeFinalization.Boundary.Kind.WORK,
                            TentativeFinalization.Boundary.Kind
                                    .INITIALIZATION_BATCH,
                            TentativeFinalization.Boundary.Kind
                                    .CHECKPOINT_SETTLEMENT),
                    finalizationKinds(capture.evidence
                            .tentativeFinalizations()));
            assertEquals(Long.valueOf(4L),
                    capture.evidence.tentativeFinalizations()
                            .get(1)
                            .boundary().afterWorkOrdinal());
        }
    }

    @Test
    void finalizesEachIndependentDraftComponentBeforeStartingTheNext() {
        List<Node> drafts = Arrays.asList(
                independentDraftDocument(B),
                independentDraftDocument(C),
                independentDraftDocument(D));
        try (DocumentProcessor owner = independentDraftOwner(drafts)) {
            Capture capture = new Capture();
            ClosureInvocationInput input = independentDraftInvocation(
                    owner, drafts);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(), diagnostic(attempt));
            assertTrue(attempt.processResult().commits());
            assertEquals(Arrays.asList(
                            WorkKind.EXTERNAL_DELIVERY,
                            WorkKind.INITIALIZATION,
                            WorkKind.LIFECYCLE,
                            WorkKind.EMBEDDED_EVENT,
                            WorkKind.INITIALIZATION,
                            WorkKind.LIFECYCLE,
                            WorkKind.EMBEDDED_EVENT,
                            WorkKind.INITIALIZATION,
                            WorkKind.LIFECYCLE,
                            WorkKind.EMBEDDED_EVENT),
                    workKinds(capture.evidence.workTrace()));
            assertEquals(Arrays.asList(
                            ROOT,
                            B, B, ROOT,
                            C, C, ROOT,
                            D, D, ROOT),
                    stepTargets(capture.evidence.documentStepTrace()));
            for (DocumentId documentId : Arrays.asList(B, C, D)) {
                ResultingDocument child = resultingDocument(
                        attempt, documentId);
                assertTrue(child.initialized());
                assertEquals(0L, child.epoch());
            }

            List<GasTraceEntry> gas = attempt.processResult().gasTrace();
            assertInitializationBarrier(gas, B, 3L, Long.valueOf(4L));
            assertInitializationBarrier(gas, C, 6L, Long.valueOf(7L));
            assertInitializationBarrier(gas, D, 9L, null);
        }
    }

    @Test
    void rejectsAProcessThatDoesNotActivateItsProspectiveDraft() {
        Node parent = managedDraftParentDocument();
        Node draft = managedDraftDocument(
                DirectBlueIdCalculator.calculateBlueId(parent));
        try (DocumentProcessor owner = managedDraftOwner(
                draft, false, false, false)) {
            Capture capture = new Capture();
            ClosureInvocationInput input = managedDraftInvocation(
                    owner, draft);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    attempt.processResult().status(), diagnostic(attempt));
            assertFalse(attempt.processResult().commits());
            assertEquals(
                    ProcessorErrorCategory.ManagedOccurrenceBindingMissing,
                    attempt.processResult().diagnostic().category());
            assertEquals(input.snapshot().closureIdentity(),
                    attempt.processResult().outputClosureIdentity());
            assertEquals(Collections.singletonList(
                            WorkKind.EXTERNAL_DELIVERY),
                    workKinds(capture.evidence.workTrace()));
        }
    }

    @Test
    void rejectsAProspectiveDraftWithTheWrongExactState() {
        Node parent = managedDraftParentDocument();
        Node draft = managedDraftDocument(
                DirectBlueIdCalculator.calculateBlueId(parent));
        try (DocumentProcessor owner = managedDraftOwner(
                draft, true, false, true)) {
            Capture capture = new Capture();
            ClosureInvocationInput input = managedDraftInvocation(
                    owner, draft);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    attempt.processResult().status(), diagnostic(attempt));
            assertFalse(attempt.processResult().commits());
            assertEquals(
                    ProcessorErrorCategory.ManagedOccurrenceBindingMissing,
                    attempt.processResult().diagnostic().category());
            assertTrue(attempt.processResult().diagnostic().message()
                    .contains("/children/first"));
            assertEquals(input.snapshot().closureIdentity(),
                    attempt.processResult().outputClosureIdentity());
            assertEquals(Collections.singletonList(
                            WorkKind.EXTERNAL_DELIVERY),
                    workKinds(capture.evidence.workTrace()));
        }
    }

    @Test
    void requestsEvidenceForAnActivatedUntrackedManagedOccurrence() {
        Node parent = managedDraftParentDocument();
        Node draft = managedDraftDocument(
                DirectBlueIdCalculator.calculateBlueId(parent));
        try (DocumentProcessor owner = managedDraftOwner(
                draft, true, true, false)) {
            Capture capture = new Capture();
            ClosureInvocationInput input = managedDraftInvocation(
                    owner, draft);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }

            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind());
            assertFalse(attempt.isComplete());
            assertNull(attempt.processResult());
            assertNull(attempt.totalGas());
            assertEquals(1, attempt.resourceDemands().size());
            ClosureResourceDemand demand =
                    attempt.resourceDemands().get(0);
            assertTrue(demand
                    instanceof ManagedOccurrenceEvidenceDemand);
            assertEquals(ROOT, demand.sourceDocumentId());
            assertEquals("/children/unexpected", demand.sourcePath());
            assertNull(capture.evidence,
                    "A resource demand must not publish completion evidence");
            assertEquals(3, input.snapshot().occurrences().size());
            assertNull(NodePathEditor.getOrNull(
                    input.snapshot().managedDocument(ROOT).document(),
                    "/children"));
        }
    }

    @Test
    void commitCompanionRetainsAnUnchangedParticipatingDocument() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureInvocationInput input = invocation(owner);
            AffectedClosureSnapshot snapshot = input.snapshot();
            LinkedHashMap<DocumentId, Long> generations =
                    new LinkedHashMap<DocumentId, Long>();
            LinkedHashMap<DocumentId, Node> bodies =
                    new LinkedHashMap<DocumentId, Node>();
            ArrayList<DocumentId> documentIds = new ArrayList<DocumentId>();
            for (ManagedDocumentSnapshot document
                    : snapshot.managedDocuments()) {
                documentIds.add(document.documentId());
                generations.put(document.documentId(),
                        Long.valueOf(document.componentGeneration()));
                bodies.put(document.documentId(), document.document());
            }
            ComponentFinalizationResult finalization =
                    new ComponentFinalizationKernel().finalizeComponents(
                            new ComponentFinalizationInput(
                                    ManagedDocumentGraph.fromBindings(
                                            documentIds,
                                            snapshot.occurrences()),
                                    generations,
                                    bodies,
                                    snapshot.occurrences()));
            ClosureExecutionState state = new ClosureExecutionState(
                    snapshot,
                    finalization,
                    Collections.<PublicEventOccurrence>emptyList(),
                    Collections
                            .<blue.language.processor.GasTraceEntry>emptyList(),
                    Collections
                            .<DocumentId, List<ManagedRootChannelOccurrence>>
                                    emptyMap(),
                    Collections
                            .<DocumentId, List<ManagedRootChannelOccurrence>>
                                    emptyMap(),
                    Collections
                            .<ManagedCheckpointSettlementBatch.Mutation>
                                    emptyList(),
                    Collections.<DocumentId>emptySet());

            ClosureProcessResult result =
                    ClosureSuccessResultAssembler.assemble(input, state);

            assertEquals(1, result.resultingDocuments().size());
            assertEquals(1,
                    result.platformCommitCompanion()
                            .resultingDocuments().size());
            ClosureCommitCompanion.DocumentDelta committed =
                    result.platformCommitCompanion()
                            .resultingDocuments().get(0);
            assertEquals(committed.beforeBlueId(),
                    committed.afterBlueId());
            assertEquals(result.resultingDocuments().get(0).beforeBlueId(),
                    committed.beforeBlueId());
        }
    }

    @Test
    void executesCclo34ShapeAsFourIndependentDocumentSteps() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                                BlueRuntimeTypeRegistry.getDefault().node(
                                        RuntimeTypeKey
                                                .SCRIPTED_EXTERNAL_CHANNEL),
                                new C34ChannelProcessor())
                        .register(
                                RuntimeBlueIds.SCRIPTED_HANDLER,
                                BlueRuntimeTypeRegistry.getDefault().node(
                                        RuntimeTypeKey.SCRIPTED_HANDLER),
                                new C34HandlerProcessor())
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(new NodeProvider() {
                    @Override
                    public List<Node> fetchByBlueId(String blueId) {
                        if (C34_X_BLUE_ID.equals(blueId)) {
                            return Collections.singletonList(
                                    c34Event("X"));
                        }
                        if (C34_Y_BLUE_ID.equals(blueId)) {
                            return Collections.singletonList(
                                    c34Event("Y"));
                        }
                        return BlueRuntimeTypeRegistry.getDefault()
                                .asProvider()
                                .fetchByBlueId(blueId);
                    }
                })
                .build()) {
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(c34Invocation(owner));
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(),
                    diagnostic(attempt));
            assertTrue(attempt.processResult().commits());
            assertEquals(2L,
                    attempt.processResult().graphGeneration());
            assertEquals(4, capture.evidence.workTrace().size());
            assertEquals(4,
                    capture.evidence.documentStepTrace().size());
            assertEquals(Arrays.asList(A, A, B, A),
                    stepTargets(capture.evidence.documentStepTrace()));
            for (DocumentStepEvidence step
                    : capture.evidence.documentStepTrace()) {
                assertEquals(step.targetDocumentId(),
                        step.executionRootDocumentId());
                assertEquals("/", step.scopePath());
                assertEquals("ISOLATED_DOCUMENT", step.executionMode());
                assertTrue(step.ambientContainingDocumentIds().isEmpty());
            }
            assertEquals(5,
                    capture.evidence.tentativeFinalizations().size());
            assertTrue(capture.evidence
                    .managedDocumentStepInclusiveNanos() > 0L);
            assertTrue(capture.evidence
                    .managedDocumentStepExclusiveNanos() >= 0L);
            assertTrue(capture.evidence
                    .componentFinalizationProofNanos() > 0L);
            assertTrue(capture.evidence
                    .successfulResultAssemblyNanos() > 0L);
            assertEquals(1,
                    attempt.processResult().resultingComponents().size());
            assertEquals(ComponentKind.CYCLIC,
                    attempt.processResult().resultingComponents()
                            .get(0).kind());
            assertEquals(2,
                    attempt.processResult().graphChanges().size());
            assertEquals(1,
                    attempt.processResult().checkpointWrites().size());
            assertEquals(1,
                    attempt.processResult().publicEvents().size());
            assertEquals(C34_X_BLUE_ID,
                    attempt.processResult().publicEvents()
                            .get(0).eventBlueId());
            assertEquals(BigInteger.ONE,
                    property(resultDocument(attempt, A),
                            "localXSeen").getValue());
            assertEquals("done",
                    property(resultDocument(attempt, A),
                            "result").getValue());
            assertEquals(BigInteger.ONE,
                    property(resultDocument(attempt, B),
                            "xHandled").getValue());
            assertEquals(1L,
                    resultingDocument(attempt, A).epoch());
            assertEquals(1L,
                    resultingDocument(attempt, B).epoch());
            assertTrue(capture.evidence.complete());
        }
    }

    @Test
    void discoversTriggeredHandlersFromTheLatestDocumentAtDequeue() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_BLUE_ID,
                                CHANNEL_TYPE,
                                new TestChannelProcessor())
                        .register(
                                ROUTING_HANDLER_BLUE_ID,
                                ROUTING_HANDLER_TYPE,
                                new RoutingHandlerProcessor())
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(routeEventProvider())
                .build()) {
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(invocation(
                        owner,
                        100000L,
                        latestRoutingDocument()));
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(), diagnostic(attempt));
            assertTrue(attempt.processResult().commits());
            Node result = resultDocument(attempt, ROOT);
            assertEquals(Boolean.FALSE,
                    property(result, "staleHandlerRan").getValue());
            assertEquals(Boolean.TRUE,
                    property(result, "latestHandlerRan").getValue());
            assertEquals(Arrays.asList(
                            "source", "switchRoute", "latestRoute"),
                    workChannels(capture.evidence.workTrace()));
        }
    }

    @Test
    void retainsRemovedTargetAndExcludesLaterTargetActivation() {
        Node child = frozenTargetChildDocument();
        String childBlueId = DirectBlueIdCalculator.calculateBlueId(child);
        Node parent = frozenTargetParentDocument(childBlueId);
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_BLUE_ID,
                                CHANNEL_TYPE,
                                new TestChannelProcessor())
                        .register(
                                FROZEN_TARGET_HANDLER_BLUE_ID,
                                FROZEN_TARGET_HANDLER_TYPE,
                                new FrozenTargetHandlerProcessor(
                                        childBlueId))
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(routeEventProvider())
                .build()) {
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(
                        frozenTargetInvocation(
                                owner, parent, child));
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(), diagnostic(attempt));
            assertTrue(attempt.processResult().commits());
            Node result = resultDocument(attempt, A);
            assertEquals(Boolean.FALSE,
                    property(result,
                            "staleEmbeddedHandlerRan").getValue());
            assertEquals(BigInteger.ONE,
                    property(result, "observedTargets").getValue());
            assertNull(NodePathEditor.getOrNull(
                    result, "/children/first"));
            assertNotNull(NodePathEditor.getOrNull(
                    result, "/children/second"));
            assertEquals(Arrays.asList(
                            WorkKind.EXTERNAL_DELIVERY,
                            WorkKind.EMBEDDED_EVENT,
                            WorkKind.EMBEDDED_EVENT),
                    workKinds(capture.evidence.workTrace()));
            assertEquals(Arrays.asList(
                            "source",
                            "activateTarget",
                            "latestObserveTarget"),
                    workChannels(capture.evidence.workTrace()));
        }
    }

    @Test
    void historicalCatchUpFormsJointComponentBeforeFirstUpdateHandlerReads() {
        // given
        List<String> selectedViews = new ArrayList<>();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                        if ("inspect".equals(context.contractKey())) {
                            selectedViews.add(context.canonicalFrozenAt("/a").blueId());
                            assertEquals(BigInteger.TEN, context.resolvedFrozenAt("/a/counter").getValue());
                            assertEquals(BigInteger.TEN, context.resolvedFrozenAt("/a/b/a/counter").getValue());
                        }
                    }
                }).build();
        NodeProvider provider = id -> CHANNEL_BLUE_ID.equals(id) ? Collections.singletonList(CHANNEL_TYPE.clone())
                : HANDLER_BLUE_ID.equals(id) ? Collections.singletonList(HANDLER_TYPE.clone())
                : BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot a9 = sourceState(A, markInitialized(new Node().name("historical source")
                    .properties("counter", new Node().value(BigInteger.valueOf(9))).contracts(new Node())), 9L);
            Node observer = markInitialized(new Node().name("catch-up observer").properties("a", new Node().blueId(a9.blueId()))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                            .properties("paths", new Node().items(new Node().value("/a"))))
                            .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/a")))
                            .properties("inspect", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("updates")))));
            ManagedDocumentSnapshot b0 = sourceState(B, observer, 0L);
            ManagedDocumentSnapshot a10 = sourceState(A, markInitialized(new Node().name("historical source")
                    .properties("counter", new Node().value(BigInteger.TEN)).properties("b", new Node().blueId(b0.blueId()))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                            .properties("paths", new Node().items(new Node().value("/b")))))), 10L);
            ManagedOccurrenceBinding pending = c34Binding(B, "/a", A, a9.blueId(), false,
                    template.environment().managedBindingPolicyIdentity(), Long.valueOf(9L));
            ManagedOccurrenceBinding active = c34Binding(A, "/b", B, b0.blueId(), true,
                    template.environment().managedBindingPolicyIdentity());
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(a10, b0),
                    Arrays.asList(active, pending), Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b0),
                            ClosureEvidenceFactory.acyclicComponent(a10)), Arrays.asList(A, B),
                    Collections.singletonList(ManagedReadPin.fromExactEvidence(A, a9.blueId(), a9.document(), null)));
            ManagedRevisionCause cause = ClosureEvidenceFactory.managedRevisionCause(pending.occurrenceIdentity(), A,
                    9L, 10L, a9.blueId(), a10.blueId(), a10.document(), template.cause().causeIdentity());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, cause, Collections.emptyList(),
                    template.executionPolicy(), template.environment());

            // when
            ClosureAttemptResult attempt = contracts.processClosure(input);

            // then
            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS, attempt.processResult().status(), diagnostic(attempt));
            String jointA = resultingDocument(attempt, A).afterBlueId();
            assertTrue(jointA.contains("#"));
            assertFalse(selectedViews.isEmpty(), "The real DocumentUpdate handler must observe the boundary");
            for (String selected : selectedViews) assertEquals(jointA, selected,
                    "No callback may observe a temporary acyclic source between catch-up and joint activation");
            assertFalse(attempt.processResult().gasTrace().stream().anyMatch(entry -> A.equals(entry.documentId())
                    && "managed-revision.acyclic-finalization".equals(entry.reason())),
                    "The pinned source is finalized once as part of the joint component, not first as a discarded acyclic identity");
        }
    }

    @Test
    void retirementAfterARealSourceAdvancePreservesItsActualSelectedView() {
        // given
        String[] selectedAtRetirement = {null};
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(CHANNEL_BLUE_ID, CHANNEL_TYPE, new TestChannelProcessor())
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, new HandlerProcessor<TestHandler>() {
                    @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                    @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                        if ("handler".equals(context.contractKey())) {
                            context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                            context.emitEvent(new Node().value("source advanced"));
                        } else if ("detach".equals(context.contractKey())) {
                            selectedAtRetirement[0] = context.canonicalFrozenAt("/b").blueId();
                            context.applyPatch(JsonPatch.add("/seen", context.resolvedFrozenAt("/b/x").toNode()));
                            context.applyPatch(JsonPatch.remove("/contracts/embedded"));
                            context.applyPatch(JsonPatch.remove("/b"));
                        }
                    }
                }).build();
        NodeProvider provider = id -> CHANNEL_BLUE_ID.equals(id) ? Collections.singletonList(CHANNEL_TYPE.clone())
                : HANDLER_BLUE_ID.equals(id) ? Collections.singletonList(HANDLER_TYPE.clone())
                : BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
        try (blue.language.runtime.BlueLanguageRuntime language = blue.language.runtime.BlueLanguageRuntime.create(
                    provider, blue.language.api.BlueCachePolicy.disabled(), Collections.emptyMap());
             DocumentProcessor owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider)
                     .snapshotStore(new ObservationSnapshots(language)).build();
             BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
            ClosureInvocationInput template = invocation(owner);
            ManagedDocumentSnapshot b0 = sourceState(B, initializedDocument().properties("x", new Node().value(BigInteger.ZERO)), 0L);
            Node parent = markInitialized(new Node().name("retiring observer").properties("b", new Node().blueId(b0.blueId()))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                            .properties("paths", new Node().items(new Node().value("/b"))))
                            .properties("fromB", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/b")))
                            .properties("detach", typed(HANDLER_BLUE_ID).properties("channel", new Node().value("fromB")))));
            ManagedDocumentSnapshot a0 = sourceState(A, parent, 0L);
            ManagedOccurrenceBinding original = c34Binding(A, "/b", B, b0.blueId(), true, template.environment().managedBindingPolicyIdentity());
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(1L, Arrays.asList(a0, b0),
                    Collections.singletonList(original), Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b0),
                            ClosureEvidenceFactory.acyclicComponent(a0)), Arrays.asList(A, B));
            ClosureInvocationInput sourceInput = sourceInvocation(template, b0, new Node().value("advance"), 1L);
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, sourceInput.cause(),
                    sourceInput.directDeliveries(), sourceInput.executionPolicy(), sourceInput.environment());

            // when
            ClosureAttemptResult attempt = contracts.processClosure(input);

            // then
            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS, attempt.processResult().status(), diagnostic(attempt));
            ClosureProcessResult result = attempt.processResult();
            assertNotNull(selectedAtRetirement[0]);
            assertNotEquals(b0.blueId(), selectedAtRetirement[0]);
            assertNotEquals(resultingDocument(attempt, B).afterBlueId(), selectedAtRetirement[0],
                    "The later checkpoint settlement cannot change an already retired placement selection");
            assertEquals(BigInteger.ONE, resultDocument(attempt, A).getNode("/seen").getValue());
            ManagedOccurrenceBinding retired = bindingAt(result.occurrenceBindings(), A, "/b");
            assertFalse(retired.active());
            assertEquals(selectedAtRetirement[0], retired.expectedTargetBlueId());
            assertEquals(b0.blueId(), result.graphChanges().get(0).before().targetBlueId(),
                    "The net graph retains its original input side, independently of the actual retirement selection");
            List<ManagedDocumentSnapshot> after = result.resultingDocuments().stream().map(ResultingDocument::asSnapshot)
                    .collect(java.util.stream.Collectors.toList());
            AffectedClosureSnapshot output = ClosureEvidenceFactory.affectedClosure(result.graphGeneration(), after,
                    result.occurrenceBindings(), result.resultingComponents(), Arrays.asList(A, B), result.readPins());
            IllegalArgumentException missingAuthority = assertThrows(IllegalArgumentException.class,
                    () -> ClosureEvidenceVerifier.verifyTransition(snapshot, output, result.resultingDocuments(), result.graphChanges(),
                            result.subscriptionDeltas(), result.checkpointWrites(), result.publicEvents(), result.gasTrace(), null));
            assertTrue(missingAuthority.getMessage().contains("removed occurrence's exact selection"));
        }
    }

    @Test
    void preservesDormantAndHistoricalSelectionsAcrossUnrelatedCyclicChurn() {
        try (DocumentProcessor owner = c34Owner()) {
            ClosureInvocationInput input = c34Invocation(
                    owner, 100000L, portableLimitPolicy(), true);
            ManagedOccurrenceBinding beforeProspective = bindingAt(
                    input.snapshot().occurrences(), B, "/future-a");
            ManagedOccurrenceBinding beforeHistorical = bindingAt(
                    input.snapshot().occurrences(), B, "/historical-a");

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(), diagnostic(attempt));
            ManagedOccurrenceBinding afterProspective = bindingAt(
                    attempt.processResult().occurrenceBindings(),
                    B,
                    "/future-a");
            ManagedOccurrenceBinding afterHistorical = bindingAt(
                    attempt.processResult().occurrenceBindings(),
                    B,
                    "/historical-a");
            assertNull(input.snapshot().managedDocument(B).document().getProperties().get("future-a"),
                    "This row is only a prospective reservation, not an authored selected reference");

            assertFalse(afterProspective.active());
            assertNull(afterProspective.pendingHistoricalEpoch());
            assertEquals(beforeProspective.occurrenceIdentity(),
                    afterProspective.occurrenceIdentity());
            assertEquals(beforeProspective.expectedTargetBlueId(),
                    afterProspective.expectedTargetBlueId());
            assertEquals(beforeProspective.bindingIdentity(), afterProspective.bindingIdentity());

            assertFalse(afterHistorical.active());
            assertEquals(Long.valueOf(0L),
                    afterHistorical.pendingHistoricalEpoch());
            assertEquals(beforeHistorical.occurrenceIdentity(),
                    afterHistorical.occurrenceIdentity());
            assertEquals(beforeHistorical.bindingIdentity(),
                    afterHistorical.bindingIdentity());
            assertEquals(beforeHistorical.expectedTargetBlueId(),
                    afterHistorical.expectedTargetBlueId());
            assertEquals(2L, attempt.processResult().graphGeneration());
        }
    }

    @Test
    void rejectsRecomputedPolicyThatOmitsOneFrozenLimitBeforeProcessing() {
        try (DocumentProcessor owner = c34Owner()) {
            Capture capture = new Capture();
            IllegalArgumentException rejection;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                rejection = assertThrows(
                        IllegalArgumentException.class,
                        () -> contracts.processClosure(c34Invocation(
                                owner,
                                100000L,
                                portableLimitPolicy(false))));
            }

            assertEquals(
                    "Invocation portable limits are not the frozen "
                            + "Contracts 1.0 policy",
                    rejection.getMessage());
            assertNull(capture.evidence);
        }
    }

    @Test
    void convertsDeterministicHandlerFailureToRuntimeFatalRollback() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_BLUE_ID,
                                CHANNEL_TYPE,
                                new TestChannelProcessor())
                        .register(
                                HANDLER_BLUE_ID,
                                HANDLER_TYPE,
                                new FailingHandlerProcessor())
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .build()) {
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(invocation(owner));
            }

            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
            assertFalse(result.commits());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertTrue(result.graphChanges().isEmpty());
            assertTrue(result.checkpointWrites().isEmpty());
            assertTrue(result.publicEvents().isEmpty());
            assertTrue(result.totalGas() > 0L);
            assertNotNull(result.diagnostic());
            assertEquals(
                    ProcessorErrorCategory.RuntimeExecutionFailure,
                    result.diagnostic().category());
            assertNull(result.rejectedCharge());
            assertNull(result.rejectedWorkOccurrence());
            assertNotNull(capture.evidence);
            assertEquals(1, capture.evidence.workTrace().size());
            assertEquals(1,
                    capture.evidence.documentStepTrace().size());
            assertTrue(capture.evidence
                    .managedDocumentStepInclusiveNanos() >= 0L);
            assertTrue(capture.evidence
                    .managedDocumentStepExclusiveNanos() >= 0L);
            assertTrue(capture.evidence
                    .componentFinalizationProofNanos() >= 0L);
            assertEquals(0L, capture.evidence
                    .successfulResultAssemblyNanos());
        }
    }

    @Test
    void rollsBackTentativeMutationAndPublishesExactRejectedWorkCharge() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_BLUE_ID,
                                CHANNEL_TYPE,
                                new TestChannelProcessor())
                        .register(
                                HANDLER_BLUE_ID,
                                HANDLER_TYPE,
                                new MutatingHandlerProcessor())
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .build()) {
            ClosureAttemptResult baseline;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                baseline = contracts.processClosure(invocation(owner));
            }
            long rejectingLimit = admittedBefore(
                    baseline.processResult().gasTrace(),
                    "rootEventRecorded");

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(
                        invocation(owner, rejectingLimit));
            }

            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            assertEquals(
                    ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    result.status());
            assertFalse(result.commits());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertEquals(rejectingLimit, result.totalGas());
            assertTrue(result.graphChanges().isEmpty());
            assertTrue(result.checkpointWrites().isEmpty());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(
                    ProcessorErrorCategory.GasLimitExceeded,
                    result.diagnostic().category());
            assertNotNull(result.rejectedCharge());
            assertEquals("rootEventRecorded",
                    result.rejectedCharge().counter());
            assertEquals(
                    RejectedCharge.Owner.Kind.WORK,
                    result.rejectedCharge().owner().kind());
            assertNotNull(result.rejectedWorkOccurrence());
            assertEquals(ROOT,
                    result.rejectedWorkOccurrence().targetDocumentId());
            assertEquals(
                    result.rejectedWorkOccurrence().workIdentity(),
                    result.rejectedCharge().owner()
                            .workOccurrenceIdentity());
            for (GasTraceEntry entry : result.gasTrace()) {
                assertFalse("rootEventRecorded".equals(entry.counter()));
            }
        }
    }

    @Test
    void suspendsForSortedExactMissingProviderEvidenceWithoutPortableGas() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_BLUE_ID,
                                CHANNEL_TYPE,
                                new MissingEvidenceChannelProcessor())
                        .register(
                                HANDLER_BLUE_ID,
                                HANDLER_TYPE,
                                new TestHandlerProcessor())
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .build()) {
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(invocation(owner));
            }

            assertFalse(attempt.isComplete());
            assertEquals(
                    ClosureAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind());
            assertNull(attempt.processResult());
            assertNull(attempt.totalGas());
            assertNull(capture.evidence);
            assertEquals(
                    Arrays.asList(C34_Y_BLUE_ID, C34_X_BLUE_ID),
                    attempt.requiredExactBlueIds());
            assertEquals(2, attempt.resourceDemands().size());
            assertEquals(C34_Y_BLUE_ID,
                    ((ExactNodeDemand) attempt.resourceDemands().get(0))
                            .blueId());
            assertEquals(C34_X_BLUE_ID,
                    ((ExactNodeDemand) attempt.resourceDemands().get(1))
                            .blueId());
        }
    }

    @Test
    void providerSuspensionDoesNotPublishCompletionEvidence() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                                BlueRuntimeTypeRegistry.getDefault().node(
                                        RuntimeTypeKey
                                                .SCRIPTED_EXTERNAL_CHANNEL),
                                new C34ChannelProcessor())
                        .register(
                                RuntimeBlueIds.SCRIPTED_HANDLER,
                                BlueRuntimeTypeRegistry.getDefault().node(
                                        RuntimeTypeKey.SCRIPTED_HANDLER),
                                new C34HandlerProcessor())
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(new NodeProvider() {
                    @Override
                    public List<Node> fetchByBlueId(String blueId) {
                        return null;
                    }

                    @Override
                    public NodeProviderResult fetchResultByBlueId(
                            String blueId) {
                        if (C34_X_BLUE_ID.equals(blueId)) {
                            return NodeProviderResult.unavailable(
                                    "Exact fixture event is temporarily unavailable");
                        }
                        if (C34_Y_BLUE_ID.equals(blueId)) {
                            return NodeProviderResult.found(
                                    Collections.singletonList(
                                            c34Event("Y")));
                        }
                        List<Node> nodes = BlueRuntimeTypeRegistry
                                .getDefault().asProvider()
                                .fetchByBlueId(blueId);
                        return nodes == null
                                ? NodeProviderResult.notFound()
                                : NodeProviderResult.found(nodes);
                    }
                })
                .build()) {
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(c34Invocation(owner));
            }

            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind());
            assertEquals(Collections.singletonList(C34_X_BLUE_ID),
                    attempt.requiredExactBlueIds());
            assertEquals(C34_X_BLUE_ID,
                    ((ExactNodeDemand) attempt.resourceDemands().get(0))
                            .blueId());
            assertNull(capture.evidence,
                    "A retryable provider suspension must not publish completion evidence");
        }
    }

    @Test
    void publishesExactFinalizationOwnerWhenCycleBoundaryRunsOutOfGas() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                                BlueRuntimeTypeRegistry.getDefault().node(
                                        RuntimeTypeKey
                                                .SCRIPTED_EXTERNAL_CHANNEL),
                                new C34ChannelProcessor())
                        .register(
                                RuntimeBlueIds.SCRIPTED_HANDLER,
                                BlueRuntimeTypeRegistry.getDefault().node(
                                        RuntimeTypeKey.SCRIPTED_HANDLER),
                                new C34HandlerProcessor())
                        .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(new NodeProvider() {
                    @Override
                    public List<Node> fetchByBlueId(String blueId) {
                        if (C34_X_BLUE_ID.equals(blueId)) {
                            return Collections.singletonList(c34Event("X"));
                        }
                        if (C34_Y_BLUE_ID.equals(blueId)) {
                            return Collections.singletonList(c34Event("Y"));
                        }
                        return BlueRuntimeTypeRegistry.getDefault()
                                .asProvider().fetchByBlueId(blueId);
                    }
                })
                .build()) {
            ClosureAttemptResult baseline;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                baseline = contracts.processClosure(c34Invocation(owner));
            }
            long rejectingLimit = admittedBefore(
                    baseline.processResult().gasTrace(),
                    "tentativeComponentFinalization");

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(
                        c34Invocation(owner, rejectingLimit));
            }

            ClosureProcessResult result = attempt.processResult();
            assertEquals(
                    ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    result.status());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertEquals(rejectingLimit, result.totalGas());
            assertEquals(
                    "tentativeComponentFinalization",
                    result.rejectedCharge().counter());
            assertEquals(
                    RejectedCharge.Owner.Kind.FINALIZATION,
                    result.rejectedCharge().owner().kind());
            assertEquals(Long.valueOf(0L),
                    result.rejectedCharge().owner()
                            .finalizationOrdinal());
            assertEquals(Long.valueOf(2L),
                    result.rejectedCharge().owner()
                            .componentGeneration());
            assertNotNull(result.rejectedCharge().owner()
                    .componentIdentity());
            assertNull(result.rejectedWorkOccurrence());
        }
    }

    private static String diagnostic(ClosureAttemptResult attempt) {
        if (attempt.processResult() == null
                || attempt.processResult().diagnostic() == null) {
            return "no diagnostic";
        }
        return attempt.processResult().diagnostic().category()
                + ": "
                + attempt.processResult().diagnostic().message()
                + " "
                + attempt.processResult().diagnostic().details();
    }

    private static DocumentProcessor independentDraftOwner(
            List<Node> drafts) {
        Node children = new Node();
        for (int index = 0; index < drafts.size(); index++) {
            DocumentId documentId = Arrays.asList(B, C, D).get(index);
            children.properties(
                    documentId.value(), drafts.get(index).clone());
        }
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_BLUE_ID,
                                CHANNEL_TYPE,
                                new TestChannelProcessor())
                        .register(
                                INDEPENDENT_DRAFT_HANDLER_BLUE_ID,
                                INDEPENDENT_DRAFT_HANDLER_TYPE,
                                new IndependentDraftHandlerProcessor(
                                        children))
                        .build();
        return DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .build();
    }

    private static ClosureInvocationInput independentDraftInvocation(
            DocumentProcessor owner,
            List<Node> drafts) {
        List<DocumentId> childIds = Arrays.asList(B, C, D);
        if (drafts.size() != childIds.size()) {
            throw new IllegalArgumentException(
                    "Independent draft fixture requires three children");
        }
        ClosureInvocationInput base = invocation(owner);
        Node parent = independentDraftParentDocument();
        String bindingPolicyIdentity = base.environment()
                .managedBindingPolicyIdentity();
        ArrayList<ManagedOccurrenceBinding> bindings =
                new ArrayList<ManagedOccurrenceBinding>();
        ArrayList<DocumentId> documentIds =
                new ArrayList<DocumentId>();
        documentIds.add(ROOT);
        documentIds.addAll(childIds);
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        generations.put(ROOT, Long.valueOf(1L));
        bodies.put(ROOT, parent);
        for (int index = 0; index < childIds.size(); index++) {
            DocumentId documentId = childIds.get(index);
            Node draft = drafts.get(index);
            generations.put(documentId, Long.valueOf(1L));
            bodies.put(documentId, draft);
            bindings.add(ManagedOccurrenceBinding.derived(
                    bindingPolicyIdentity,
                    ROOT,
                    ScopeAddress.embedded(
                            "/children/" + documentId.value(), 1L),
                    documentId,
                    DirectBlueIdCalculator.calculateBlueId(draft),
                    false,
                    null));
        }
        Collections.sort(bindings);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                documentIds, bindings);
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, bindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId documentId : Arrays.asList(B, C, D, ROOT)) {
            FinalizedDocumentEvidence exact = finalized.document(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    documentId,
                    exact.blueId(),
                    exact.document(),
                    documentId.equals(ROOT),
                    false,
                    documentId.equals(ROOT),
                    0L,
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            components.add(component.component());
        }
        List<ManagedOccurrenceBinding> exactBindings =
                finalized.finalizedGraph().bindings();
        String bindingSetIdentity = IDENTITIES
                .occurrenceBindingSetIdentity(exactBindings);
        AffectedClosureSnapshot provisionalSnapshot =
                new AffectedClosureSnapshot(
                        hash('0'),
                        1L,
                        documents,
                        exactBindings,
                        bindingSetIdentity,
                        components,
                        Collections.singletonList(ROOT));
        AffectedClosureSnapshot snapshot =
                new AffectedClosureSnapshot(
                        IDENTITIES.affectedClosureIdentity(
                                provisionalSnapshot),
                        provisionalSnapshot.graphGeneration(),
                        provisionalSnapshot.managedDocuments(),
                        provisionalSnapshot.occurrences(),
                        provisionalSnapshot
                                .occurrenceBindingSetIdentity(),
                        provisionalSnapshot.components(),
                        provisionalSnapshot.publicRootDocumentIds());
        ClosureInvocationInput provisional =
                ClosureInvocationInput.processClosure(
                        hash('f'),
                        snapshot,
                        base.cause(),
                        base.directDeliveries(),
                        base.directDeliverySnapshotIdentity(),
                        base.executionPolicy(),
                        base.environment());
        return ClosureInvocationInput.processClosure(
                IDENTITIES.invocationIdentity(provisional),
                snapshot,
                base.cause(),
                base.directDeliveries(),
                base.directDeliverySnapshotIdentity(),
                base.executionPolicy(),
                base.environment());
    }

    private static Node independentDraftParentDocument() {
        Node document = new Node()
                .name("Independent Draft Parent")
                .contracts(new Node()
                        .properties(
                                "embedded",
                                typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                        .properties(
                                                "collectionPaths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/children"))))
                        .properties("source", typed(CHANNEL_BLUE_ID))
                        .properties(
                                "createIndependentDrafts",
                                independentDraftHandler("source"))
                        .properties(
                                "fromIndependentChild",
                                typed(RuntimeBlueIds
                                        .EMBEDDED_NODE_CHANNEL))
                        .properties(
                                "observeIndependentChild",
                                independentDraftHandler(
                                        "fromIndependentChild")));
        return markInitialized(document);
    }

    private static Node independentDraftDocument(DocumentId documentId) {
        return new Node()
                .name("Independent Draft " + documentId.value())
                .properties(
                        "documentId",
                        new Node().value(documentId.value()))
                .contracts(new Node()
                        .properties(
                                "lifecycle",
                                typed(RuntimeBlueIds
                                        .LIFECYCLE_EVENT_CHANNEL))
                        .properties(
                                "emitIndependentLifecycle",
                                independentDraftHandler("lifecycle")));
    }

    private static Node independentDraftHandler(String channel) {
        return typed(INDEPENDENT_DRAFT_HANDLER_BLUE_ID).properties(
                "channel", new Node().value(channel));
    }

    private static DocumentProcessor managedDraftOwner(
            Node draft,
            boolean activate,
            boolean addUnexpectedOccurrence,
            boolean installWrongExactState) {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                CHANNEL_BLUE_ID,
                                CHANNEL_TYPE,
                                new TestChannelProcessor())
                        .register(
                                DRAFT_HANDLER_BLUE_ID,
                                DRAFT_HANDLER_TYPE,
                                new ManagedDraftHandlerProcessor(
                                        draft,
                                        activate,
                                        addUnexpectedOccurrence,
                                        installWrongExactState))
                        .build();
        return DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .build();
    }

    private static ClosureInvocationInput managedDraftInvocation(
            DocumentProcessor owner,
            Node draft) {
        ClosureInvocationInput base = invocation(owner);
        Node parent = managedDraftParentDocument();
        String draftBlueId =
                DirectBlueIdCalculator.calculateBlueId(draft);
        String bindingPolicyIdentity = base.environment()
                .managedBindingPolicyIdentity();
        ArrayList<ManagedOccurrenceBinding> bindings =
                new ArrayList<ManagedOccurrenceBinding>();
        bindings.add(ManagedOccurrenceBinding.derived(
                bindingPolicyIdentity,
                ROOT,
                ScopeAddress.embedded("/children/first", 1L),
                B,
                draftBlueId,
                false,
                null));
        bindings.add(ManagedOccurrenceBinding.derived(
                bindingPolicyIdentity,
                B,
                ScopeAddress.embedded("/parent", 1L),
                ROOT,
                DirectBlueIdCalculator.calculateBlueId(parent),
                true,
                null));
        bindings.add(ManagedOccurrenceBinding.derived(
                bindingPolicyIdentity,
                ROOT,
                ScopeAddress.embedded("/children/second", 1L),
                B,
                draftBlueId,
                false,
                null));
        Collections.sort(bindings);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                Arrays.asList(ROOT, B), bindings);
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        generations.put(ROOT, Long.valueOf(1L));
        generations.put(B, Long.valueOf(1L));
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        bodies.put(ROOT, parent);
        bodies.put(B, draft);
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, bindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId documentId : Arrays.asList(B, ROOT)) {
            FinalizedDocumentEvidence exact = finalized.document(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    documentId,
                    exact.blueId(),
                    exact.document(),
                    documentId.equals(ROOT),
                    false,
                    documentId.equals(ROOT),
                    0L,
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            components.add(component.component());
        }
        List<ManagedOccurrenceBinding> exactBindings =
                finalized.finalizedGraph().bindings();
        String bindingSetIdentity = IDENTITIES
                .occurrenceBindingSetIdentity(exactBindings);
        AffectedClosureSnapshot provisionalSnapshot =
                new AffectedClosureSnapshot(
                        hash('0'),
                        1L,
                        documents,
                        exactBindings,
                        bindingSetIdentity,
                        components,
                        Collections.singletonList(ROOT));
        AffectedClosureSnapshot snapshot =
                new AffectedClosureSnapshot(
                        IDENTITIES.affectedClosureIdentity(
                                provisionalSnapshot),
                        provisionalSnapshot.graphGeneration(),
                        provisionalSnapshot.managedDocuments(),
                        provisionalSnapshot.occurrences(),
                        provisionalSnapshot
                                .occurrenceBindingSetIdentity(),
                        provisionalSnapshot.components(),
                        provisionalSnapshot.publicRootDocumentIds());
        ClosureInvocationInput provisional =
                ClosureInvocationInput.processClosure(
                        hash('f'),
                        snapshot,
                        base.cause(),
                        base.directDeliveries(),
                        base.directDeliverySnapshotIdentity(),
                        base.executionPolicy(),
                        base.environment());
        return ClosureInvocationInput.processClosure(
                IDENTITIES.invocationIdentity(provisional),
                snapshot,
                base.cause(),
                base.directDeliveries(),
                base.directDeliverySnapshotIdentity(),
                base.executionPolicy(),
                base.environment());
    }

    private static Node managedDraftParentDocument() {
        Node document = new Node()
                .name("Managed Draft Parent")
                .contracts(new Node()
                        .properties(
                                "embedded",
                                typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                        .properties(
                                                "collectionPaths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/children"))))
                        .properties(
                                "source",
                                typed(CHANNEL_BLUE_ID))
                        .properties(
                                "createManagedDraft",
                                draftHandler("source"))
                        .properties(
                                "fromManagedChild",
                                typed(RuntimeBlueIds
                                        .EMBEDDED_NODE_CHANNEL))
                        .properties(
                                "observeManagedChild",
                                draftHandler("fromManagedChild")));
        return markInitialized(document);
    }

    private static Node managedDraftDocument(String parentBlueId) {
        return new Node()
                .name("Managed Draft Child")
                .properties("documentId", new Node().value("b"))
                .properties("parent", new Node().blueId(parentBlueId))
                .contracts(new Node()
                        .properties(
                                "embedded",
                                typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                        .properties(
                                                "paths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/parent"))))
                        .properties(
                                "lifecycle",
                                typed(RuntimeBlueIds
                                        .LIFECYCLE_EVENT_CHANNEL))
                        .properties(
                                "emitDraftLifecycle",
                                draftHandler("lifecycle")));
    }

    private static Node draftHandler(String channel) {
        return typed(DRAFT_HANDLER_BLUE_ID).properties(
                "channel", new Node().value(channel));
    }

    private static Node latestRoutingDocument() {
        Node document = new Node()
                .name("Latest Event Routing Root")
                .properties(
                        "staleHandlerRan",
                        new Node().value(Boolean.FALSE))
                .properties(
                        "latestHandlerRan",
                        new Node().value(Boolean.FALSE))
                .contracts(new Node()
                        .properties("source", typed(CHANNEL_BLUE_ID))
                        .properties(
                                "emitRoutes",
                                routingHandler("source"))
                        .properties(
                                "switchRoute",
                                typed(RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL)
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        ROUTE_SWITCH_BLUE_ID)))
                        .properties(
                                "switchHandler",
                                routingHandler("switchRoute"))
                        .properties(
                                "staleRoute",
                                typed(RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL)
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        ROUTE_TARGET_BLUE_ID)))
                        .properties(
                                "staleHandler",
                                routingHandler("staleRoute"))
                        .properties(
                                "latestRoute",
                                typed(RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL)
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        ROUTE_OTHER_BLUE_ID)))
                        .properties(
                                "latestHandler",
                                routingHandler("latestRoute")));
        return markInitialized(document);
    }

    private static Node routingHandler(String channel) {
        return typed(ROUTING_HANDLER_BLUE_ID)
                .properties("channel", new Node().value(channel))
                .properties(
                        "order", new Node().value(BigInteger.ZERO));
    }

    private static Node frozenTargetChildDocument() {
        Node document = new Node()
                .name("Frozen Target Event Source")
                .contracts(new Node()
                        .properties("source", typed(CHANNEL_BLUE_ID))
                        .properties(
                                "emitFrozenTargetEvents",
                                frozenTargetHandler("source")));
        return markInitialized(document);
    }

    private static Node frozenTargetParentDocument(String childBlueId) {
        Node document = new Node()
                .name("Frozen Target Event Receiver")
                .properties(
                        "observedTargets",
                        new Node().value(BigInteger.ZERO))
                .properties(
                        "staleEmbeddedHandlerRan",
                        new Node().value(Boolean.FALSE))
                .properties(
                        "children",
                        new Node().properties(
                                "first",
                                new Node().blueId(childBlueId)))
                .contracts(new Node()
                        .properties(
                                "embedded",
                                typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                        .properties(
                                                "collectionPaths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/children"))))
                        .properties(
                                "activateTarget",
                                typed(RuntimeBlueIds
                                        .EMBEDDED_NODE_CHANNEL)
                                        .properties(
                                                "sourcePath",
                                                new Node().value(
                                                        "/children/first"))
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        ACTIVATE_TARGET_BLUE_ID)))
                        .properties(
                                "activateSecondTarget",
                                frozenTargetHandler("activateTarget"))
                        .properties(
                                "staleObserveTarget",
                                typed(RuntimeBlueIds
                                        .EMBEDDED_NODE_CHANNEL)
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        OBSERVE_TARGET_BLUE_ID)))
                        .properties(
                                "staleObserveFrozenTarget",
                                frozenTargetHandler(
                                        "staleObserveTarget"))
                        .properties(
                                "latestObserveTarget",
                                typed(RuntimeBlueIds
                                        .EMBEDDED_NODE_CHANNEL)
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        ROUTE_OTHER_BLUE_ID)))
                        .properties(
                                "latestObserveFrozenTarget",
                                frozenTargetHandler(
                                        "latestObserveTarget")));
        return markInitialized(document);
    }

    private static Node frozenTargetHandler(String channel) {
        return typed(FROZEN_TARGET_HANDLER_BLUE_ID)
                .properties("channel", new Node().value(channel))
                .properties(
                        "order", new Node().value(BigInteger.ZERO));
    }

    private static Node routeEvent(String kind) {
        return new Node().properties(
                "kind", new Node().value(kind));
    }

    private static NodeProvider routeEventProvider() {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                if (ROUTE_SWITCH_BLUE_ID.equals(blueId)) {
                    return Collections.singletonList(
                            routeEvent("switch"));
                }
                if (ROUTE_TARGET_BLUE_ID.equals(blueId)) {
                    return Collections.singletonList(
                            routeEvent("target"));
                }
                if (ROUTE_OTHER_BLUE_ID.equals(blueId)) {
                    return Collections.singletonList(
                            routeEvent("other"));
                }
                if (ACTIVATE_TARGET_BLUE_ID.equals(blueId)) {
                    return Collections.singletonList(
                            routeEvent("activate-target"));
                }
                if (OBSERVE_TARGET_BLUE_ID.equals(blueId)) {
                    return Collections.singletonList(
                            routeEvent("observe-target"));
                }
                return BlueRuntimeTypeRegistry.getDefault()
                        .asProvider().fetchByBlueId(blueId);
            }
        };
    }

    private static ClosureInvocationInput frozenTargetInvocation(
            DocumentProcessor owner,
            Node parent,
            Node child) {
        ClosureInvocationInput base = invocation(owner);
        String childBlueId =
                DirectBlueIdCalculator.calculateBlueId(child);
        String bindingPolicyIdentity = base.environment()
                .managedBindingPolicyIdentity();
        ArrayList<ManagedOccurrenceBinding> bindings =
                new ArrayList<ManagedOccurrenceBinding>();
        bindings.add(ManagedOccurrenceBinding.derived(
                bindingPolicyIdentity,
                A,
                ScopeAddress.embedded("/children/first", 1L),
                B,
                childBlueId,
                true,
                null));
        bindings.add(ManagedOccurrenceBinding.derived(
                bindingPolicyIdentity,
                A,
                ScopeAddress.embedded("/children/second", 1L),
                B,
                childBlueId,
                false,
                null));
        Collections.sort(bindings);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B), bindings);
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        generations.put(A, Long.valueOf(1L));
        generations.put(B, Long.valueOf(1L));
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        bodies.put(A, parent);
        bodies.put(B, child);
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, bindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId documentId : Arrays.asList(A, B)) {
            FinalizedDocumentEvidence exact = finalized.document(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    documentId,
                    exact.blueId(),
                    exact.document(),
                    true,
                    false,
                    documentId.equals(B),
                    0L,
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            components.add(component.component());
        }
        List<ManagedOccurrenceBinding> exactBindings =
                finalized.finalizedGraph().bindings();
        String bindingSetIdentity = IDENTITIES
                .occurrenceBindingSetIdentity(exactBindings);
        AffectedClosureSnapshot provisionalSnapshot =
                new AffectedClosureSnapshot(
                        hash('0'),
                        1L,
                        documents,
                        exactBindings,
                        bindingSetIdentity,
                        components,
                        Collections.singletonList(B));
        AffectedClosureSnapshot snapshot =
                new AffectedClosureSnapshot(
                        IDENTITIES.affectedClosureIdentity(
                                provisionalSnapshot),
                        provisionalSnapshot.graphGeneration(),
                        provisionalSnapshot.managedDocuments(),
                        provisionalSnapshot.occurrences(),
                        provisionalSnapshot
                                .occurrenceBindingSetIdentity(),
                        provisionalSnapshot.components(),
                        provisionalSnapshot.publicRootDocumentIds());
        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(B),
                "source",
                "logical",
                0L);
        List<DirectLogicalDelivery> deliveries =
                Collections.singletonList(delivery);
        String deliveryIdentity = IDENTITIES
                .directDeliverySnapshotIdentity(deliveries);
        ClosureInvocationInput provisional =
                ClosureInvocationInput.processClosure(
                        hash('f'),
                        snapshot,
                        base.cause(),
                        deliveries,
                        deliveryIdentity,
                        base.executionPolicy(),
                        base.environment());
        return ClosureInvocationInput.processClosure(
                IDENTITIES.invocationIdentity(provisional),
                snapshot,
                base.cause(),
                deliveries,
                deliveryIdentity,
                base.executionPolicy(),
                base.environment());
    }

    private static DocumentProcessor c34Owner() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                                BlueRuntimeTypeRegistry.getDefault().node(
                                        RuntimeTypeKey
                                                .SCRIPTED_EXTERNAL_CHANNEL),
                                new C34ChannelProcessor())
                        .register(
                                RuntimeBlueIds.SCRIPTED_HANDLER,
                                BlueRuntimeTypeRegistry.getDefault().node(
                                        RuntimeTypeKey.SCRIPTED_HANDLER),
                                new C34HandlerProcessor())
                        .build();
        return DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(new NodeProvider() {
                    @Override
                    public List<Node> fetchByBlueId(String blueId) {
                        if (C34_X_BLUE_ID.equals(blueId)) {
                            return Collections.singletonList(c34Event("X"));
                        }
                        if (C34_Y_BLUE_ID.equals(blueId)) {
                            return Collections.singletonList(c34Event("Y"));
                        }
                        return BlueRuntimeTypeRegistry.getDefault()
                                .asProvider().fetchByBlueId(blueId);
                    }
                })
                .build();
    }

    private static ClosureInvocationInput c34Invocation(
            DocumentProcessor owner) {
        return c34Invocation(owner, 100000L);
    }

    private static ClosureInvocationInput c34Invocation(
            DocumentProcessor owner,
            long sharedLimit) {
        return c34Invocation(
                owner, sharedLimit, portableLimitPolicy());
    }

    private static ClosureInvocationInput c34Invocation(
            DocumentProcessor owner,
            long sharedLimit,
            ClosureEnvironment.PortableLimitPolicyEvidence
                    portableLimitPolicy) {
        return c34Invocation(
                owner, sharedLimit, portableLimitPolicy, false);
    }

    private static ClosureInvocationInput c34Invocation(
            DocumentProcessor owner,
            long sharedLimit,
            ClosureEnvironment.PortableLimitPolicyEvidence
                    portableLimitPolicy,
            boolean includeInactiveContinuityRows) {
        Node documentA = c34DocumentA();
        String blueA = DirectBlueIdCalculator.calculateBlueId(documentA);
        assertEquals(C34_A_BLUE_ID, blueA);
        Node documentB = c34DocumentB(blueA);
        String blueB = DirectBlueIdCalculator.calculateBlueId(documentB);
        assertEquals(C34_B_BLUE_ID, blueB);

        String bindingPolicyLabel = "exact-document-lineage";
        ClosureEnvironment.LabeledIdentityEvidence bindingPolicy = labeled(
                ClosureIdentityService.Constructor.MANAGED_BINDING_POLICY,
                bindingPolicyLabel);
        ManagedOccurrenceBinding bToA = c34Binding(
                B, "/a", A, blueA, true,
                bindingPolicy.identity());
        ManagedOccurrenceBinding aToB = c34Binding(
                A, "/b", B, blueB, false,
                bindingPolicy.identity());
        List<ManagedOccurrenceBinding> bindings =
                new ArrayList<ManagedOccurrenceBinding>();
        bindings.add(bToA);
        bindings.add(aToB);
        if (includeInactiveContinuityRows) {
            bindings.add(c34Binding(
                    B, "/future-a", A, blueA, false,
                    bindingPolicy.identity(), null));
            bindings.add(c34Binding(
                    B, "/historical-a", A, blueA, false,
                    bindingPolicy.identity(), Long.valueOf(0L)));
        }
        Collections.sort(bindings);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B), bindings);
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        generations.put(A, Long.valueOf(1L));
        generations.put(B, Long.valueOf(1L));
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        bodies.put(A, documentA);
        bodies.put(B, documentB);
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, bindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (DocumentId documentId : Arrays.asList(A, B)) {
            FinalizedDocumentEvidence exact = finalized.document(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    documentId,
                    exact.blueId(),
                    exact.document(),
                    true,
                    false,
                    documentId.equals(A),
                    0L,
                    exact.componentGeneration()));
        }
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            components.add(component.component());
        }
        List<ManagedOccurrenceBinding> exactBindings =
                finalized.finalizedGraph().bindings();
        String bindingsIdentity = IDENTITIES
                .occurrenceBindingSetIdentity(exactBindings);
        AffectedClosureSnapshot provisionalSnapshot =
                new AffectedClosureSnapshot(
                        hash('0'),
                        1L,
                        documents,
                        exactBindings,
                        bindingsIdentity,
                        components,
                        Collections.singletonList(A));
        AffectedClosureSnapshot snapshot = new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisionalSnapshot),
                provisionalSnapshot.graphGeneration(),
                provisionalSnapshot.managedDocuments(),
                provisionalSnapshot.occurrences(),
                provisionalSnapshot.occurrenceBindingSetIdentity(),
                provisionalSnapshot.components(),
                provisionalSnapshot.publicRootDocumentIds());

        Node event = c34Event("START-FINITE");
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        ClosureEnvironment.LabeledIdentityEvidence orderPolicyEvidence =
                labeled(
                        ClosureIdentityService.Constructor
                                .EXTERNAL_ORDER_POLICY,
                        "canonical-source-order-v1");
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.<Object>asList(
                        BigInteger.valueOf(100L),
                        "timeline-a",
                        BigInteger.ONE));
        ExternalEventCause provisionalCause = new ExternalEventCause(
                hash('1'),
                event,
                eventBlueId,
                order,
                orderPolicyEvidence.identity());
        ExternalEventCause cause = new ExternalEventCause(
                IDENTITIES.causeIdentity(provisionalCause),
                event,
                eventBlueId,
                order,
                orderPolicyEvidence.identity());
        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(A), "source", "start", 0L);
        List<DirectLogicalDelivery> deliveries =
                Collections.singletonList(delivery);
        ExecutionPolicy provisionalPolicy = new ExecutionPolicy(
                hash('2'),
                sharedLimit,
                Collections.<DocumentId, Long>emptyMap(),
                "release-default");
        ExecutionPolicy policy = new ExecutionPolicy(
                IDENTITIES.executionPolicyIdentity(provisionalPolicy),
                provisionalPolicy.sharedLimit(),
                provisionalPolicy.localLimits(),
                provisionalPolicy.label());
        ClosureRuntimeDescriptor runtime =
                ClosureRuntimeDescriptor.capture(owner);
        ClosureEnvironment environment = new ClosureEnvironment(
                hash('3'),
                hash('4'),
                runtime.runtimeRegistryIdentity(),
                runtime.gasManifestIdentity(),
                labeled(
                        ClosureIdentityService.Constructor
                                .MANAGED_DOCUMENT_IDENTITY_POLICY,
                        "nfc-document-lineage-v1"),
                bindingPolicy,
                labeled(
                        ClosureIdentityService.Constructor
                                .EXACT_NODE_PROVIDER_DOMAIN,
                        "fixture-exact-node-provider-v1"),
                orderPolicyEvidence,
                portableLimitPolicy,
                ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY,
                ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY);
        String deliveryIdentity = IDENTITIES
                .directDeliverySnapshotIdentity(deliveries);
        ClosureInvocationInput provisionalInput =
                ClosureInvocationInput.processClosure(
                        hash('5'),
                        snapshot,
                        cause,
                        deliveries,
                        deliveryIdentity,
                        policy,
                        environment);
        return ClosureInvocationInput.processClosure(
                IDENTITIES.invocationIdentity(provisionalInput),
                snapshot,
                cause,
                deliveries,
                deliveryIdentity,
                policy,
                environment);
    }

    private static SourceObservationProgram coldProgram(SourceObservationProgram captured) {
        java.util.Map<String, byte[]> fragments = new java.util.HashMap<>();
        String authenticatedSourceResultReference = SourceObservationProgramCodec.encode(captured,
                (id, bytes) -> fragments.put(id, bytes.clone()), FrozenNodeEvidenceCodec.Limits.defaults());
        SourceObservationProgram restored = SourceObservationProgramCodec.decode(authenticatedSourceResultReference,
                id -> fragments.get(id).clone(), FrozenNodeEvidenceCodec.Limits.defaults());
        assertEquals(captured.invocationIdentity(), restored.invocationIdentity());
        assertEquals(captured.ownedDocumentIds(), restored.ownedDocumentIds());
        return restored;
    }

    private static ManagedOccurrenceBinding c34Binding(
            DocumentId source,
            String path,
            DocumentId target,
            String expectedTargetBlueId,
            boolean active,
            String bindingPolicyIdentity) {
        return c34Binding(
                source,
                path,
                target,
                expectedTargetBlueId,
                active,
                bindingPolicyIdentity,
                null);
    }

    private static ManagedOccurrenceBinding c34Binding(
            DocumentId source,
            String path,
            DocumentId target,
            String expectedTargetBlueId,
            boolean active,
            String bindingPolicyIdentity,
            Long pendingHistoricalEpoch) {
        ScopeAddress address = ScopeAddress.embedded(path, 1L);
        return new ManagedOccurrenceBinding(
                IDENTITIES.managedOccurrenceIdentity(
                        source, address, target,
                        bindingPolicyIdentity),
                IDENTITIES.managedOccurrenceBindingIdentity(
                        source, address, target,
                        expectedTargetBlueId,
                        bindingPolicyIdentity),
                bindingPolicyIdentity,
                source,
                address,
                target,
                expectedTargetBlueId,
                active,
                pendingHistoricalEpoch);
    }

    private static ClosureInvocationInput invocation(
            DocumentProcessor owner) {
        return invocation(owner, 100000L);
    }

    private static ClosureInvocationInput invocation(
            DocumentProcessor owner,
            long sharedLimit) {
        return invocation(owner, sharedLimit, initializedDocument());
    }

    private static ClosureInvocationInput invocation(
            DocumentProcessor owner,
            long sharedLimit,
            Node exactDocument) {
        Node document = exactDocument.clone();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                Collections.singletonList(ROOT),
                Collections.<ManagedOccurrenceBinding>emptyList());
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        generations.put(ROOT, Long.valueOf(1L));
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        bodies.put(ROOT, document);
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                generations,
                                bodies,
                                Collections
                                        .<ManagedOccurrenceBinding>
                                                emptyList()));
        FinalizedDocumentEvidence exact = finalized.document(ROOT);
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                ROOT,
                exact.blueId(),
                exact.document(),
                true,
                false,
                true,
                0L,
                exact.componentGeneration());
        ComponentSnapshot component = finalized.components().get(0)
                .component();
        String bindingsIdentity = IDENTITIES
                .occurrenceBindingSetIdentity(Collections
                        .<ManagedOccurrenceBinding>emptyList());
        AffectedClosureSnapshot provisional =
                new AffectedClosureSnapshot(
                        hash('0'),
                        1L,
                        Collections.singletonList(managed),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        bindingsIdentity,
                        Collections.singletonList(component),
                        Collections.singletonList(ROOT));
        AffectedClosureSnapshot snapshot =
                new AffectedClosureSnapshot(
                        IDENTITIES.affectedClosureIdentity(provisional),
                        1L,
                        Collections.singletonList(managed),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        bindingsIdentity,
                        Collections.singletonList(component),
                        Collections.singletonList(ROOT));

        Node event = new Node().properties(
                "kind", new Node().value("start"));
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        ClosureEnvironment.LabeledIdentityEvidence documentPolicy =
                labeled(
                        ClosureIdentityService.Constructor
                                .MANAGED_DOCUMENT_IDENTITY_POLICY,
                        "test-document-lineage");
        ClosureEnvironment.LabeledIdentityEvidence bindingPolicy =
                labeled(
                        ClosureIdentityService.Constructor
                                .MANAGED_BINDING_POLICY,
                        "test-binding-lineage");
        ClosureEnvironment.LabeledIdentityEvidence providerDomain =
                labeled(
                        ClosureIdentityService.Constructor
                                .EXACT_NODE_PROVIDER_DOMAIN,
                        "test-exact-provider");
        ClosureEnvironment.LabeledIdentityEvidence orderPolicyEvidence =
                labeled(
                        ClosureIdentityService.Constructor
                                .EXTERNAL_ORDER_POLICY,
                        "test-external-order");
        String orderPolicy = orderPolicyEvidence.identity();
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.<Object>asList(
                        Long.valueOf(1L), "timeline", Long.valueOf(0L)));
        ExternalEventCause provisionalCause = new ExternalEventCause(
                hash('b'), event, eventBlueId, order, orderPolicy);
        ExternalEventCause cause = new ExternalEventCause(
                IDENTITIES.causeIdentity(provisionalCause),
                event, eventBlueId, order, orderPolicy);
        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(ROOT),
                "source",
                "logical",
                0L);
        List<DirectLogicalDelivery> deliveries =
                Collections.singletonList(delivery);
        ExecutionPolicy provisionalPolicy = new ExecutionPolicy(
                hash('c'),
                sharedLimit,
                new LinkedHashMap<DocumentId, Long>(),
                "test");
        ExecutionPolicy policy = new ExecutionPolicy(
                IDENTITIES.executionPolicyIdentity(provisionalPolicy),
                provisionalPolicy.sharedLimit(),
                provisionalPolicy.localLimits(),
                provisionalPolicy.label());
        ClosureRuntimeDescriptor runtime =
                ClosureRuntimeDescriptor.capture(owner);
        ClosureEnvironment environment = new ClosureEnvironment(
                hash('1'),
                hash('2'),
                runtime.runtimeRegistryIdentity(),
                runtime.gasManifestIdentity(),
                documentPolicy,
                bindingPolicy,
                providerDomain,
                orderPolicyEvidence,
                portableLimitPolicy(),
                ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY,
                ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY);
        String deliveryIdentity = IDENTITIES
                .directDeliverySnapshotIdentity(deliveries);
        ClosureInvocationInput provisionalInput =
                ClosureInvocationInput.processClosure(
                        hash('d'),
                        snapshot,
                        cause,
                        deliveries,
                        deliveryIdentity,
                        policy,
                        environment);
        return ClosureInvocationInput.processClosure(
                IDENTITIES.invocationIdentity(provisionalInput),
                snapshot,
                cause,
                deliveries,
                deliveryIdentity,
                policy,
                environment);
    }

    private static long admittedBefore(
            List<GasTraceEntry> trace,
            String counter) {
        long admitted = 0L;
        for (GasTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                return admitted;
            }
            admitted = Math.addExact(admitted, entry.subtotal());
        }
        throw new AssertionError("Missing baseline gas counter " + counter);
    }

    private static Node c34DocumentA() {
        Node document = new Node()
                .properties("documentId", new Node().value("a"))
                .properties("memberIdentity", new Node().value("a"))
                .properties("localXSeen",
                        new Node().value(BigInteger.ZERO))
                .properties("result", new Node().value("pending"))
                .contracts(new Node()
                        .properties(
                                "embedded",
                                typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                        .properties(
                                                "paths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/b"))))
                        .properties(
                                "source",
                                typed(RuntimeBlueIds
                                        .SCRIPTED_EXTERNAL_CHANNEL)
                                        .properties(
                                                "subscriptionKey",
                                                new Node().value(
                                                        "fixture"))
                                        .properties(
                                                "eventKey",
                                                new Node().value(
                                                        "fixture"))
                                        .properties(
                                                "accept",
                                                new Node().value(
                                                        Boolean.TRUE))
                                        .properties(
                                                "checkpointDomain",
                                                new Node().value(
                                                        "domain"))
                                        .properties(
                                                "logicalDeliveryKey",
                                                new Node().value(
                                                        "start")))
                        .properties(
                                "start",
                                handler("source"))
                        .properties(
                                "localX",
                                typed(RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL)
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        C34_X_BLUE_ID))
                                        .properties(
                                                "order",
                                                new Node().value(
                                                        BigInteger.ZERO)))
                        .properties(
                                "onLocalX",
                                handler("localX"))
                        .properties(
                                "fromB",
                                typed(RuntimeBlueIds
                                        .EMBEDDED_NODE_CHANNEL)
                                        .properties(
                                                "sourcePath",
                                                new Node().value("/b"))
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        C34_Y_BLUE_ID))
                                        .properties(
                                                "order",
                                                new Node().value(
                                                        BigInteger.ZERO)))
                        .properties("onY", handler("fromB")));
        return markInitialized(document);
    }

    private static Node c34DocumentB(String blueA) {
        Node document = new Node()
                .properties("documentId", new Node().value("b"))
                .properties("memberIdentity", new Node().value("b"))
                .properties("xHandled",
                        new Node().value(BigInteger.ZERO))
                .properties("a", new Node().blueId(blueA))
                .contracts(new Node()
                        .properties(
                                "embedded",
                                typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                        .properties(
                                                "paths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/a"))))
                        .properties(
                                "fromA",
                                typed(RuntimeBlueIds
                                        .EMBEDDED_NODE_CHANNEL)
                                        .properties(
                                                "sourcePath",
                                                new Node().value("/a"))
                                        .properties(
                                                "event",
                                                new Node().blueId(
                                                        C34_X_BLUE_ID))
                                        .properties(
                                                "order",
                                                new Node().value(
                                                        BigInteger.ZERO)))
                        .properties("onX", handler("fromA")));
        document.getContracts().properties(
                "initialized",
                typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                        .properties(
                                "document",
                                new Node().blueId(
                                        "6ynL7bPCMB5R5kQpqzVaNZs3YVmAopDLkdHneoeexjrm")));
        return document;
    }

    private static Node markInitialized(Node document) {
        Node before = document.clone();
        document.getContracts().properties(
                "initialized",
                typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                        .properties(
                                "document",
                                new Node().blueId(
                                        DirectBlueIdCalculator
                                                .calculateBlueId(before))));
        return document;
    }

    private static Node handler(String channel) {
        return typed(RuntimeBlueIds.SCRIPTED_HANDLER)
                .properties("channel", new Node().value(channel))
                .properties("order",
                        new Node().value(BigInteger.ZERO));
    }

    private static Node c34Event(String id) {
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.FIXTURE_EVENT))
                .properties("subscriptionKey",
                        new Node().value("fixture"))
                .properties("id", new Node().value(id));
    }

    private static List<DocumentId> stepTargets(
            List<DocumentStepEvidence> steps) {
        ArrayList<DocumentId> result = new ArrayList<DocumentId>();
        for (DocumentStepEvidence step : steps) {
            result.add(step.targetDocumentId());
        }
        return result;
    }

    private static List<WorkKind> workKinds(
            List<ClosureWorkOccurrence> workTrace) {
        ArrayList<WorkKind> result = new ArrayList<WorkKind>();
        for (ClosureWorkOccurrence work : workTrace) {
            result.add(work.kind());
        }
        return result;
    }

    private static List<String> workChannels(
            List<ClosureWorkOccurrence> workTrace) {
        ArrayList<String> result = new ArrayList<String>();
        for (ClosureWorkOccurrence work : workTrace) {
            result.add(work.channelKey());
        }
        return result;
    }

    private static List<TentativeFinalization.Boundary.Kind>
            finalizationKinds(
                    List<TentativeFinalization> finalizations) {
        ArrayList<TentativeFinalization.Boundary.Kind> result =
                new ArrayList<TentativeFinalization.Boundary.Kind>();
        for (TentativeFinalization finalization : finalizations) {
            result.add(finalization.boundary().kind());
        }
        return result;
    }

    private static void assertInitializationBarrier(
            List<GasTraceEntry> trace,
            DocumentId documentId,
            long lastCausedWorkOrdinal,
            Long nextInitializationOrdinal) {
        String markerReason = "initialization-batch.marker."
                + documentId.value();
        assertEquals(1L, trace.stream()
                .filter(entry -> "processorMarkerWritten".equals(
                        entry.counter()))
                .filter(entry -> markerReason.equals(entry.reason()))
                .count());
        long lastWork = firstGasSequence(
                trace,
                "closureWorkOccurrenceDequeued",
                "work." + lastCausedWorkOrdinal + ".dequeue",
                null,
                false);
        long marker = firstGasSequence(
                trace,
                "processorMarkerWritten",
                markerReason,
                documentId,
                false);
        long finalized = firstGasSequence(
                trace,
                null,
                "initialization-batch.acyclic-finalization",
                documentId,
                true);
        assertTrue(lastWork < marker);
        assertTrue(marker < finalized);
        if (nextInitializationOrdinal != null) {
            long nextInitialization = firstGasSequence(
                    trace,
                    "closureWorkOccurrenceEnqueued",
                    "work." + nextInitializationOrdinal
                            + ".initialization-enqueue",
                    null,
                    false);
            assertTrue(finalized < nextInitialization);
        }
    }

    private static long firstGasSequence(
            List<GasTraceEntry> trace,
            String counter,
            String reason,
            DocumentId documentId,
            boolean reasonPrefix) {
        for (GasTraceEntry entry : trace) {
            boolean counterMatches = counter == null
                    || counter.equals(entry.counter());
            boolean reasonMatches = reasonPrefix
                    ? entry.reason() != null
                            && entry.reason().startsWith(reason)
                    : reason.equals(entry.reason());
            boolean documentMatches = documentId == null
                    || documentId.equals(entry.documentId());
            if (counterMatches && reasonMatches && documentMatches) {
                return entry.sequence();
            }
        }
        throw new AssertionError(
                "Missing gas trace boundary " + reason + " for "
                        + documentId);
    }

    private static ResultingDocument resultingDocument(
            ClosureAttemptResult attempt,
            DocumentId documentId) {
        for (ResultingDocument document
                : attempt.processResult().resultingDocuments()) {
            if (document.documentId().equals(documentId)) {
                return document;
            }
        }
        throw new AssertionError("Missing resulting document "
                + documentId);
    }

    private static ManagedOccurrenceBinding bindingAt(
            List<ManagedOccurrenceBinding> bindings,
            DocumentId sourceDocumentId,
            String sourcePath) {
        for (ManagedOccurrenceBinding binding : bindings) {
            if (binding.sourceDocumentId().equals(sourceDocumentId)
                    && binding.sourcePath().equals(sourcePath)) {
                return binding;
            }
        }
        throw new AssertionError("Missing occurrence binding "
                + sourceDocumentId + ":" + sourcePath);
    }

    private static Node resultDocument(
            ClosureAttemptResult attempt,
            DocumentId documentId) {
        return resultingDocument(attempt, documentId).document();
    }

    private static Node property(Node node, String key) {
        Node value = node.getProperties() == null
                ? null : node.getProperties().get(key);
        assertNotNull(value, "Missing property " + key);
        return value;
    }

    private static Node initializedDocument() {
        Node document = new Node()
                .name("Concrete Root")
                .contracts(new Node()
                        .properties(
                                "source",
                                typed(CHANNEL_BLUE_ID))
                        .properties(
                                "handler",
                                typed(HANDLER_BLUE_ID).properties(
                                        "channel",
                                        new Node().value("source"))));
        return markInitialized(document);
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static ClosureEnvironment.LabeledIdentityEvidence labeled(
            ClosureIdentityService.Constructor constructor,
            String label) {
        return new ClosureEnvironment.LabeledIdentityEvidence(
                IDENTITIES.labeledIdentity(constructor, label),
                label);
    }

    private static ClosureEnvironment.PortableLimitPolicyEvidence
            portableLimitPolicy() {
        return portableLimitPolicy(true);
    }

    private static ClosureEnvironment.PortableLimitPolicyEvidence
            portableLimitPolicy(boolean includeCyclicCanonicalBytes) {
        LinkedHashMap<String, Long> limits =
                new LinkedHashMap<String, Long>(
                        GasSchedule.contracts10().portableLimits());
        if (!includeCyclicCanonicalBytes) {
            limits.remove("cyclicCanonicalBytesPerComponent");
        }
        ClosureEnvironment.PortableLimitPolicyEvidence provisional =
                new ClosureEnvironment.PortableLimitPolicyEvidence(
                        hash('6'), "test-portable-limits", limits);
        return new ClosureEnvironment.PortableLimitPolicyEvidence(
                IDENTITIES.portableLimitPolicyIdentity(provisional),
                provisional.label(),
                provisional.limits());
    }

    private static String hash(char digit) {
        StringBuilder value = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            value.append(digit);
        }
        return value.toString();
    }

    private static final class Capture
            implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence evidence) {
            this.evidence = evidence;
        }
    }

    private static final class ObservationSnapshots implements blue.language.processor.ProcessingSnapshotManager {
        private final blue.language.runtime.BlueLanguageRuntime language;
        ObservationSnapshots(blue.language.runtime.BlueLanguageRuntime language) { this.language = language; }
        @Override public blue.language.merge.ResolvedSnapshot fromDocument(Node document) {
            return language.snapshots().resolve(document.clone());
        }
        @Override public blue.language.merge.ResolvedSnapshot fromDocumentPreservingPaths(
                Node document, java.util.Collection<String> paths) {
            return language.snapshots().resolvePreservingPaths(document.clone(), paths);
        }
        @Override public blue.language.merge.ResolvedSnapshot fromDocumentTransientPreservingPaths(
                Node document, java.util.Collection<String> paths) {
            return fromDocumentPreservingPaths(document, paths);
        }
        @Override public blue.language.merge.ResolvedSnapshot applyPatch(
                blue.language.merge.ResolvedSnapshot snapshot, JsonPatch patch) {
            return language.patching().apply(snapshot, patch);
        }
        @Override public blue.language.merge.ResolvedSnapshot cacheSnapshot(blue.language.merge.ResolvedSnapshot snapshot) {
            return language.snapshots().cache(snapshot);
        }
    }

    /** Mutable application Channel model used only by this test. */
    public static final class TestChannel extends ChannelContract {
    }

    private static final class TestChannelProcessor
            implements ChannelProcessor<TestChannel> {
        private final ExternalChannelSubscriptionFunctions<TestChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<TestChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestChannel channel) {
                        return Collections.singletonList("test");
                    }

                    @Override
                    public boolean preselects(
                            TestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return true;
                    }

                    @Override
                    public boolean accepts(
                            TestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return true;
                    }

                    @Override
                    public String logicalDeliveryKey(
                            TestChannel channel,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return "logical";
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            TestChannel channel) {
                        return "closure-test-v1";
                    }
                };

        @Override
        public Class<TestChannel> contractType() {
            return TestChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<TestChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    /** Mutable application Handler model used only by this test. */
    public static final class TestHandler extends HandlerContract {
    }

    private static final class TestHandlerProcessor
            implements HandlerProcessor<TestHandler> {
        @Override
        public Class<TestHandler> contractType() {
            return TestHandler.class;
        }

        @Override
        public void execute(
                TestHandler contract,
                ProcessorExecutionContext context) {
            // The real runtime call is the assertion: no fixture projection.
        }
    }

    private static final class FailingHandlerProcessor
            implements HandlerProcessor<TestHandler> {
        @Override
        public Class<TestHandler> contractType() {
            return TestHandler.class;
        }

        @Override
        public void execute(
                TestHandler contract,
                ProcessorExecutionContext context) {
            throw new blue.language.processor.ProcessorFailureException(
                    ProcessorErrorCategory.RuntimeExecutionFailure,
                    "deterministic closure test handler failure");
        }
    }

    private static final class MutatingHandlerProcessor
            implements HandlerProcessor<TestHandler> {
        @Override
        public Class<TestHandler> contractType() {
            return TestHandler.class;
        }

        @Override
        public void execute(
                TestHandler contract,
                ProcessorExecutionContext context) {
            context.applyPatch(JsonPatch.add(
                    "/tentative", new Node().value("must-roll-back")));
            context.emitEvent(new Node().properties(
                    "kind", new Node().value("tentative-event")));
        }
    }

    /** Handler model for dequeue-time event-route discovery tests. */
    public static final class RoutingHandler extends HandlerContract {
    }

    private static final class RoutingHandlerProcessor
            implements HandlerProcessor<RoutingHandler> {
        @Override
        public Class<RoutingHandler> contractType() {
            return RoutingHandler.class;
        }

        @Override
        public void execute(
                RoutingHandler contract,
                ProcessorExecutionContext context) {
            String key = context.contractKey();
            if ("emitRoutes".equals(key)) {
                context.emitEvent(routeEvent("switch"));
                context.emitEvent(routeEvent("target"));
            } else if ("switchHandler".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/contracts/staleRoute/event",
                        new Node().blueId(ROUTE_OTHER_BLUE_ID)));
                context.applyPatch(JsonPatch.replace(
                        "/contracts/latestRoute/event",
                        new Node().blueId(ROUTE_TARGET_BLUE_ID)));
            } else if ("staleHandler".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/staleHandlerRan",
                        new Node().value(Boolean.TRUE)));
            } else if ("latestHandler".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/latestHandlerRan",
                        new Node().value(Boolean.TRUE)));
            }
        }
    }

    /** Handler model for frozen containing-target tests. */
    public static final class FrozenTargetHandler extends HandlerContract {
    }

    private static final class FrozenTargetHandlerProcessor
            implements HandlerProcessor<FrozenTargetHandler> {
        private final String childBlueId;

        private FrozenTargetHandlerProcessor(String childBlueId) {
            this.childBlueId = childBlueId;
        }

        @Override
        public Class<FrozenTargetHandler> contractType() {
            return FrozenTargetHandler.class;
        }

        @Override
        public void execute(
                FrozenTargetHandler contract,
                ProcessorExecutionContext context) {
            String key = context.contractKey();
            if ("emitFrozenTargetEvents".equals(key)) {
                context.emitEvent(routeEvent("activate-target"));
                context.emitEvent(routeEvent("observe-target"));
            } else if ("activateSecondTarget".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/contracts/staleObserveTarget/event",
                        new Node().blueId(ROUTE_OTHER_BLUE_ID)));
                context.applyPatch(JsonPatch.replace(
                        "/contracts/latestObserveTarget/event",
                        new Node().blueId(OBSERVE_TARGET_BLUE_ID)));
                context.applyPatch(JsonPatch.remove(
                        "/children/first"));
                context.applyPatch(JsonPatch.add(
                        "/children/second",
                        new Node().blueId(childBlueId)));
            } else if ("staleObserveFrozenTarget".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/staleEmbeddedHandlerRan",
                        new Node().value(Boolean.TRUE)));
            } else if ("latestObserveFrozenTarget".equals(key)) {
                Node current = context.documentAt("/observedTargets");
                BigInteger count = (BigInteger) current.getValue();
                context.applyPatch(JsonPatch.replace(
                        "/observedTargets",
                        new Node().value(count.add(BigInteger.ONE))));
            }
        }
    }

    /** Mutable managed-draft Handler model used only by this test. */
    public static final class ManagedDraftHandler extends HandlerContract {
    }

    private static final class ManagedDraftHandlerProcessor
            implements HandlerProcessor<ManagedDraftHandler> {
        private final Node draft;
        private final boolean activate;
        private final boolean addUnexpectedOccurrence;
        private final boolean installWrongExactState;

        private ManagedDraftHandlerProcessor(
                Node draft,
                boolean activate,
                boolean addUnexpectedOccurrence,
                boolean installWrongExactState) {
            this.draft = draft.clone();
            this.activate = activate;
            this.addUnexpectedOccurrence = addUnexpectedOccurrence;
            this.installWrongExactState = installWrongExactState;
        }

        @Override
        public Class<ManagedDraftHandler> contractType() {
            return ManagedDraftHandler.class;
        }

        @Override
        public void execute(
                ManagedDraftHandler contract,
                ProcessorExecutionContext context) {
            if ("createManagedDraft".equals(context.contractKey())) {
                if (activate) {
                    Node installed = draft.clone();
                    if (installWrongExactState) {
                        installed.properties(
                                "unexpectedState",
                                new Node().value(Boolean.TRUE));
                    }
                    context.applyPatch(JsonPatch.add(
                            "/children",
                            new Node()
                                    .properties("first", installed.clone())
                                    .properties("second", installed.clone())));
                    if (addUnexpectedOccurrence) {
                        context.applyPatch(JsonPatch.add(
                                "/children/unexpected",
                                draft.clone()));
                    }
                }
            } else if ("emitDraftLifecycle".equals(
                    context.contractKey())) {
                context.emitEvent(new Node().properties(
                        "kind",
                        new Node().value("managed-draft-initialized")));
            }
        }
    }

    /** Handler model for independent component initialization tests. */
    public static final class IndependentDraftHandler
            extends HandlerContract {
    }

    private static final class IndependentDraftHandlerProcessor
            implements HandlerProcessor<IndependentDraftHandler> {
        private final Node children;

        private IndependentDraftHandlerProcessor(Node children) {
            this.children = children.clone();
        }

        @Override
        public Class<IndependentDraftHandler> contractType() {
            return IndependentDraftHandler.class;
        }

        @Override
        public void execute(
                IndependentDraftHandler contract,
                ProcessorExecutionContext context) {
            if ("createIndependentDrafts".equals(
                    context.contractKey())) {
                context.applyPatch(JsonPatch.add(
                        "/children", children.clone()));
            } else if ("emitIndependentLifecycle".equals(
                    context.contractKey())) {
                context.emitEvent(new Node().properties(
                        "kind",
                        new Node().value(
                                "independent-draft-initialized")));
            }
        }
    }

    private static final class MissingEvidenceChannelProcessor
            implements ChannelProcessor<TestChannel> {
        private final ExternalChannelSubscriptionFunctions<TestChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<TestChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestChannel channel) {
                        return Collections.singletonList("test");
                    }

                    @Override
                    public boolean preselects(
                            TestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        throw new ExecutionEvidenceUnavailableException(
                                "Exact provider evidence is unavailable",
                                Arrays.asList(
                                        C34_X_BLUE_ID,
                                        C34_Y_BLUE_ID,
                                        C34_X_BLUE_ID));
                    }

                    @Override
                    public boolean accepts(
                            TestChannel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return true;
                    }

                    @Override
                    public String logicalDeliveryKey(
                            TestChannel channel,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return "logical";
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            TestChannel channel) {
                        return "closure-test-v1";
                    }
                };

        @Override
        public Class<TestChannel> contractType() {
            return TestChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<TestChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    /** C-CLO-34-shaped application Channel model. */
    public static final class C34Channel extends ChannelContract {
        private String subscriptionKey;
        private String eventKey;
        private Boolean accept;
        private String checkpointDomain;
        private String logicalDeliveryKey;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String value) {
            subscriptionKey = value;
        }

        public String getEventKey() {
            return eventKey;
        }

        public void setEventKey(String value) {
            eventKey = value;
        }

        public Boolean getAccept() {
            return accept;
        }

        public void setAccept(Boolean value) {
            accept = value;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(String value) {
            checkpointDomain = value;
        }

        public String getLogicalDeliveryKey() {
            return logicalDeliveryKey;
        }

        public void setLogicalDeliveryKey(String value) {
            logicalDeliveryKey = value;
        }
    }

    private static final class C34ChannelProcessor
            implements ChannelProcessor<C34Channel> {
        private final ExternalChannelSubscriptionFunctions<C34Channel>
                functions =
                new ExternalChannelSubscriptionFunctions<C34Channel>() {
                    @Override
                    public List<String> channelKeys(C34Channel channel) {
                        return Collections.singletonList(
                                channel.getSubscriptionKey());
                    }

                    @Override
                    public boolean preselects(
                            C34Channel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return channel.getSubscriptionKey().equals(
                                eventProperty(
                                        exactEvent,
                                        "subscriptionKey"));
                    }

                    @Override
                    public boolean accepts(
                            C34Channel channel,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return !Boolean.FALSE.equals(channel.getAccept())
                                && preselects(
                                        channel, exactEvent, context);
                    }

                    @Override
                    public String logicalDeliveryKey(
                            C34Channel channel,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return channel.getLogicalDeliveryKey();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            C34Channel channel) {
                        return channel.getCheckpointDomain();
                    }
                };

        @Override
        public Class<C34Channel> contractType() {
            return C34Channel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<C34Channel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    /** C-CLO-34-shaped application Handler model. */
    public static final class C34Handler extends HandlerContract {
    }

    private static final class C34HandlerProcessor
            implements HandlerProcessor<C34Handler> {
        @Override
        public Class<C34Handler> contractType() {
            return C34Handler.class;
        }

        @Override
        public void execute(
                C34Handler contract,
                ProcessorExecutionContext context) {
            String key = context.contractKey();
            if ("start".equals(key)) {
                context.applyPatch(JsonPatch.add(
                        "/b", new Node().blueId(C34_B_BLUE_ID)));
                context.emitEvent(c34Event("X"));
            } else if ("onLocalX".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/localXSeen",
                        new Node().value(BigInteger.ONE)));
            } else if ("onX".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/xHandled",
                        new Node().value(BigInteger.ONE)));
                context.emitEvent(c34Event("Y"));
            } else if ("onY".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/result", new Node().value("done")));
            }
        }
    }

    private static String eventProperty(Node event, String key) {
        Node value = event.getProperties() == null
                ? null : event.getProperties().get(key);
        return value == null || value.getValue() == null
                ? null : String.valueOf(value.getValue());
    }
}
