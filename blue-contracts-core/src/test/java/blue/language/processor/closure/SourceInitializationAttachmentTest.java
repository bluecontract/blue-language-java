package blue.language.processor.closure;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
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

/** Real creating workflow and canonical initialization interpreter, not constructed acceptance claims. */
class SourceInitializationAttachmentTest {
    private static final DocumentId ORDER = new DocumentId("order");
    private static final Node CHANNEL = new Node().name("Initialization attachment external channel");
    private static final Node HANDLER = new Node().name("Initialization attachment test handler");
    private static final String CHANNEL_ID = id(CHANNEL), HANDLER_ID = id(HANDLER);

    @Test
    void twoCreationSitesReplayTheSameInitializationOnlyForTheirOwnPlacements() {
        try (Fixture f = new Fixture(Action.ATTACH_TWICE)) {
            SameOriginProcessAttempt attempt = f.install(f.initialization);
            assertTrue(attempt.complete(), () -> "Unexpected needs: " + attempt.resourceDemands());
            assertEquals(1, attempt.operations().size());
            SameOriginOperationResult order = attempt.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, order.status(), () -> order.failure().map(Object::toString).orElse(""));
            Node body = order.resultingDocuments().get(0).document();
            assertEquals(BigInteger.valueOf(5), body.getProperties().get("creatorRead").getValue());
            assertEquals(BigInteger.valueOf(5), body.getProperties().get("secondRead").getValue());
            assertEquals(BigInteger.ONE, body.getProperties().get("childEventCount").getValue());
            assertEquals(BigInteger.ONE, body.getProperties().get("secondEventCount").getValue());
            List<AcceptedInitializationInstallation> facts = order.sourceProgram().get().acceptedInitializations();
            assertEquals(2, facts.size());
            assertEquals(facts.get(0).sourceInitializationOperationIdentity(), facts.get(1).sourceInitializationOperationIdentity());
            assertNotEquals(facts.get(0).creatorPatchSite(), facts.get(1).creatorPatchSite());
            assertNotEquals(facts.get(0).selection().occurrenceIdentity(), facts.get(1).selection().occurrenceIdentity());
            assertTrue(order.consumedSourceOperations().isEmpty());
            assertEquals(1, f.initializerExecutions, "The canonical source initializer is computed once, with two consumer observations");
        }
    }

    @Test
    void downstreamReplaysCreatorsBorrowedInitializationFromColdProgramWithoutRerunningEitherProducer() {
        try (Fixture f = new Fixture()) {
            SameOriginOperationResult created = f.install(f.initialization).operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, created.status());
            Map<String, byte[]> fragments = new HashMap<>();
            String root = SourceObservationProgramCodec.encode(created.sourceProgram().get(), fragments::put,
                    FrozenNodeEvidenceCodec.Limits.defaults());
            SourceObservationProgram retained = SourceObservationProgramCodec.decode(root, fragments::get,
                    FrozenNodeEvidenceCodec.Limits.defaults());
            int creatorRuns = f.creatorExecutions;
            DocumentId downstream = new DocumentId("downstream");
            Node d = new Node().name("Downstream observer").properties("a", new Node().blueId(f.input.snapshot().managedDocument(ORDER).blueId()))
                    .properties("seen", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                            .properties("paths", new Node().items(new Node().value("/a"))))
                            .properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/a/child")))
                            .properties("observeDownstream", handler("events")));
            d.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                    .properties("document", new Node().blueId(id(d))));
            ManagedDocumentSnapshot observer = new ManagedDocumentSnapshot(downstream, id(d), d, true, false, true, 0L, 0L);
            List<ManagedDocumentSnapshot> documents = new ArrayList<>(f.input.snapshot().managedDocuments());
            documents.add(observer);
            List<ManagedOccurrenceBinding> bindings = new ArrayList<>(f.input.snapshot().occurrences());
            bindings.add(ManagedOccurrenceBinding.derived(f.input.environment().managedBindingPolicyIdentity(), downstream,
                    ScopeAddress.embedded("/a", 1L), ORDER, f.input.snapshot().managedDocument(ORDER).blueId(), true, null));
            ClosureInvocationInput observing = ClosureEvidenceFactory.processClosure(snapshot(documents, bindings),
                    f.input.cause(), f.input.directDeliveries(), f.input.executionPolicy(), f.input.environment());
            SameOriginProcessAttempt attempt = f.contracts.processSameOrigin(observing, f.attachments,
                    Collections.singletonList(retained), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
            assertTrue(attempt.complete(), () -> "Unexpected needs: " + attempt.requiredExactBlueIds() + attempt.resourceDemands());
            assertEquals(1, attempt.operations().size());
            SameOriginOperationResult result = attempt.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, result.status(), () -> result.failure().map(Object::toString).orElse(""));
            assertEquals(Collections.singleton(downstream), result.ownedDocumentIds());
            assertEquals(BigInteger.valueOf(5), result.resultingDocuments().get(0).document().getProperties().get("seen").getValue());
            assertEquals(created.operationIdentity(), result.consumedSourceOperations().get(ORDER));
            assertFalse(result.consumedSourceOperations().containsKey(f.canonicalSourceHead.documentId()),
                    "A borrowed initialization is not a committed external dependency");
            assertEquals(creatorRuns, f.creatorExecutions);
            assertEquals(1, f.initializerExecutions);
        }
    }

    @Test
    void creatingThenRetiringAPlacementRetainsItsObservedFactButNoSurvivingImportLane() {
        try (Fixture f = new Fixture(Action.RETIRE_AFTER_ATTACH)) {
            SameOriginProcessAttempt attempt = f.install(f.initialization);
            assertTrue(attempt.complete());
            assertEquals(1, attempt.operations().size());
            SameOriginOperationResult order = attempt.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, order.status());
            assertEquals(Collections.singleton(ORDER), order.ownedDocumentIds());
            assertEquals(BigInteger.valueOf(5), order.resultingDocuments().get(0).document().getProperties().get("creatorRead").getValue());
            assertTrue(order.consumedSourceOperations().isEmpty());
            assertEquals(1, order.sourceProgram().get().acceptedInitializations().size(), "The completed observation remains an exact historical fact");
            assertTrue(order.occurrenceBindings().stream().noneMatch(value -> value.occurrenceIdentity().equals(f.binding.occurrenceIdentity())
                    && (value.active() || value.pendingHistoricalEpoch() != null)), "A retired occurrence cannot authorize a future import lane");
            assertEquals(1, f.initializerExecutions);
        }
    }

    @Test
    void newPlacementReplaysInit0WithoutRewindingAnOlderAliasOrDeliveringItInitializationAgain() {
        try (Fixture f = new Fixture(Action.ATTACH, true)) {
            SameOriginProcessAttempt attempt = f.install(f.initialization);
            assertTrue(attempt.complete());
            assertEquals(1, attempt.operations().size());
            SameOriginOperationResult order = attempt.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, order.status());
            assertEquals(Collections.singleton(ORDER), order.ownedDocumentIds());
            Node result = order.resultingDocuments().get(0).document();
            assertEquals(BigInteger.valueOf(5), result.getProperties().get("creatorRead").getValue());
            assertEquals(BigInteger.valueOf(77), result.getProperties().get("existingRead").getValue());
            assertEquals(BigInteger.ZERO, result.getProperties().get("oldEventCount").getValue());
            assertEquals(f.canonicalSourceHead.blueId(), result.getProperties().get("existing").getBlueId());
            SourceObservationProgram program = order.sourceProgram().orElseThrow(AssertionError::new);
            assertEquals(1, program.acceptedInitializations().size());
            program.acceptedInitializations().get(0).verifySourceInitialization(f.initialization);
            assertTrue(program.sourceResults().stream().noneMatch(value -> value.documentId().equals(f.canonicalSourceHead.documentId())),
                    "An unconsumed physical source head is not an asserted source epoch; exact alias references and read pins carry its required views");
            assertEquals(1, f.initializerExecutions);
        }
    }

    @Test
    void merelyOfferingInitializationDoesNotActivateOrBorrowIt() {
        try (Fixture f = new Fixture(Action.NO_ATTACH)) {
            SameOriginOperationResult without = f.contracts.processSameOrigin(f.input, f.attachments).operations().get(0);
            SameOriginOperationResult with = f.install(f.initialization).operations().get(0);
            assertEquals(without.operationIdentity(), with.operationIdentity());
            assertEquals(without.gasTraceIdentity(), with.gasTraceIdentity());
            assertTrue(with.sourceProgram().get().acceptedInitializations().isEmpty());
            assertTrue(with.sourceProgram().get().borrowedPrograms().isEmpty());
            assertEquals(1, f.initializerExecutions);
        }
    }

    @Test
    void failedCreatingWorkflowPublishesNeitherChildInitializationNorConsumerChanges() {
        try (Fixture f = new Fixture(Action.FAIL_AFTER_ATTACH)) {
            SameOriginProcessAttempt attempt = f.install(f.initialization);
            assertTrue(attempt.complete());
            assertEquals(1, attempt.operations().size());
            SameOriginOperationResult failed = attempt.operations().get(0);
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.status());
            assertEquals(Collections.singleton(ORDER), failed.ownedDocumentIds());
            assertFalse(failed.sourceProgram().isPresent());
            assertTrue(failed.consumedSourceOperations().isEmpty());
            assertEquals(f.input.snapshot().managedDocument(ORDER).blueId(), failed.resultingDocuments().get(0).afterBlueId());
            assertEquals(0L, failed.resultingDocuments().get(0).epoch());
            assertEquals(1, f.initializerExecutions);
        }
    }

    @Test
    void creationRequiresCanonicalInitializationThenReadsItsCompletedViewInTheSameWorkflow() {
        try (Fixture f = new Fixture()) {
            SameOriginProcessAttempt absent = f.contracts.processSameOrigin(f.input, f.attachments,
                    Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
            assertFalse(absent.complete());
            assertTrue(absent.operations().isEmpty());
            assertEquals(1, absent.resourceDemands().size());
            SourceInitializationDemand demand = assertInstanceOf(SourceInitializationDemand.class, absent.resourceDemands().get(0));
            assertEquals(f.binding.targetDocumentId(), demand.targetLineage());
            assertEquals(f.input.environment(), demand.environment());
            assertEquals(f.input.executionPolicy(), demand.executionPolicy());
            assertEquals(1, f.initializerExecutions);

            SameOriginProcessAttempt attempt = f.install(f.initialization);
            assertTrue(attempt.complete(), () -> "Unexpected needs: " + attempt.requiredExactBlueIds() + attempt.resourceDemands());
            assertEquals(1, attempt.operations().size());
            SameOriginOperationResult order = attempt.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, order.status(), () -> order.failure().map(Object::toString).orElse(""));
            assertEquals(Collections.singleton(ORDER), order.ownedDocumentIds());
            assertEquals(1, order.resultingDocuments().get(0).epoch());
            Node result = order.resultingDocuments().get(0).document();
            assertEquals(BigInteger.valueOf(5), result.getProperties().get("creatorRead").getValue());
            assertEquals(BigInteger.valueOf(5), result.getProperties().get("counterB").getValue());
            assertEquals(1, f.initializerExecutions, "Installing a retained program never executes the source handler again");
            assertTrue(order.consumedSourceOperations().isEmpty(), "Initialization is a candidate installation, not a committed external source dependency");
            SourceObservationProgram program = order.sourceProgram().orElseThrow(AssertionError::new);
            assertEquals(1, program.acceptedInitializations().size());
            AcceptedInitializationInstallation accepted = program.acceptedInitializations().get(0);
            accepted.verifySourceInitialization(f.initialization);
            assertEquals(demand.creatorSeedIdentity(), accepted.creatorSeedIdentity());
            assertEquals(demand.creatorPatchSite(), accepted.creatorPatchSite(), "Re-entry after evidence acquisition reaches the same semantic creation site");
            assertEquals(demand.selection().occurrenceIdentity(), accepted.selection().occurrenceIdentity());
            assertEquals(f.initialization.program().invocationIdentity(), accepted.sourceInitializationOperationIdentity());
            ManagedOccurrenceBinding binding = order.occurrenceBindings().stream()
                    .filter(value -> value.occurrenceIdentity().equals(f.binding.occurrenceIdentity())).findFirst().orElseThrow(AssertionError::new);
            assertFalse(binding.active());
            assertEquals(Long.valueOf(0L), binding.pendingHistoricalEpoch());
            assertEquals(f.initialization.program().sourceResults().get(0).blueId(), binding.expectedTargetBlueId());

            Map<String, byte[]> fragments = new HashMap<>();
            String root = SourceObservationProgramCodec.encode(f.initialization.program(), fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
            SourceInitialization cold = SourceInitialization.fromProgram(SourceObservationProgramCodec.decode(root, fragments::get,
                    FrozenNodeEvidenceCodec.Limits.defaults()));
            SameOriginOperationResult replay = f.install(cold).operations().get(0);
            assertEquals(order.operationIdentity(), replay.operationIdentity());
            assertEquals(order.gasTraceIdentity(), replay.gasTraceIdentity());
            assertEquals(order.totalGas(), replay.totalGas());
            assertEquals(1, f.initializerExecutions);
        }
    }

    private enum Action { ATTACH, NO_ATTACH, FAIL_AFTER_ATTACH, RETIRE_AFTER_ATTACH, ATTACH_TWICE }

    static final class Fixture implements AutoCloseable {
        int initializerExecutions;
        int creatorExecutions;
        final BlueLanguageRuntime language;
        final DocumentProcessor owner;
        final BlueClosureContracts contracts;
        final SourceInitialization initialization;
        final ClosureInvocationInput input;
        final SameOriginAttachmentPolicy attachments;
        final ManagedOccurrenceBinding binding;
        final ManagedDocumentSnapshot canonicalSourceHead;

        Fixture() { this(Action.ATTACH); }

        Fixture(Action action) { this(action, false); }

        Fixture(Action action, boolean olderAlias) {
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                    .register(CHANNEL_ID, CHANNEL, new TestChannelProcessor())
                    .register(HANDLER_ID, HANDLER, new HandlerProcessor<TestHandler>() {
                        @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                        @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                            if ("initialize".equals(context.contractKey())) {
                                initializerExecutions++;
                                context.applyPatch(JsonPatch.replace("/counter", new Node().value(BigInteger.valueOf(5))));
                                context.emitEvent(new Node().name("ready"));
                            } else if ("create".equals(context.contractKey())) {
                                creatorExecutions++;
                                if (action == Action.NO_ATTACH) return;
                                context.applyPatch(JsonPatch.add("/contracts/embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                        .properties("paths", new Node().items(olderAlias
                                                ? Arrays.asList(new Node().value("/existing"), new Node().value("/child"))
                                                : Collections.singletonList(new Node().value("/child"))))));
                            } else if ("read".equals(context.contractKey())) {
                                if (action == Action.NO_ATTACH) return;
                                context.applyPatch(JsonPatch.replace("/creatorRead", new Node().value(context.resolvedFrozenAt("/child/counter").getValue())));
                                if (olderAlias) context.applyPatch(JsonPatch.replace("/existingRead", new Node().value(context.resolvedFrozenAt("/existing/counter").getValue())));
                                if (action == Action.FAIL_AFTER_ATTACH) throw new ProcessorFailureException(
                                        ProcessorErrorCategory.InvalidProcessingDocument, "Deterministic creating workflow failure");
                            } else if ("observe".equals(context.contractKey())) {
                                context.applyPatch(JsonPatch.replace("/counterB", new Node().value(context.resolvedFrozenAt("/child/counter").getValue())));
                                BigInteger count = (BigInteger) context.resolvedFrozenAt("/childEventCount").getValue();
                                context.applyPatch(JsonPatch.replace("/childEventCount", new Node().value(count.add(BigInteger.ONE))));
                            } else if ("observeDownstream".equals(context.contractKey())) {
                                context.applyPatch(JsonPatch.replace("/seen", new Node().value(context.resolvedFrozenAt("/a/counterB").getValue())));
                            } else if ("createSecond".equals(context.contractKey())) {
                                context.applyPatch(JsonPatch.replace("/contracts/embedded/paths", new Node().items(
                                        new Node().value("/child"), new Node().value("/second"))));
                            } else if ("readSecond".equals(context.contractKey())) {
                                context.applyPatch(JsonPatch.replace("/secondRead", new Node().value(context.resolvedFrozenAt("/second/counter").getValue())));
                            } else if ("observeSecond".equals(context.contractKey())) {
                                BigInteger count = (BigInteger) context.resolvedFrozenAt("/secondEventCount").getValue();
                                context.applyPatch(JsonPatch.replace("/secondEventCount", new Node().value(count.add(BigInteger.ONE))));
                            } else if ("observeOld".equals(context.contractKey())) {
                                BigInteger count = (BigInteger) context.resolvedFrozenAt("/oldEventCount").getValue();
                                context.applyPatch(JsonPatch.replace("/oldEventCount", new Node().value(count.add(BigInteger.ONE))));
                            } else if ("retire".equals(context.contractKey()) && action == Action.RETIRE_AFTER_ATTACH) {
                                context.applyPatch(JsonPatch.remove("/contracts/embedded"));
                            }
                        }
                    }).build();
            NodeProvider provider = value -> {
                if (CHANNEL_ID.equals(value)) return Collections.singletonList(CHANNEL.clone());
                if (HANDLER_ID.equals(value)) return Collections.singletonList(HANDLER.clone());
                return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(value);
            };
            language = BlueLanguageRuntime.create(provider, BlueCachePolicy.disabled(), Collections.emptyMap());
            owner = DocumentProcessor.builder().nodeProvider(provider).runtimeRegistry(registry).snapshotStore(new Snapshots(language)).build();
            ClosureEnvironment environment = ClosureEvidenceFactory.environment(owner, hash('a'), hash('b'),
                    "source-identity", "source-bindings", "source-provider", "source-order", "source-limits", GasSchedule.contracts10().portableLimits());
            ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(100000L, Collections.emptyMap(), "source-policy");
            Node authored = new Node().name("Agreement").properties("counter", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("lifecycle", typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))
                            .properties("initialize", handler("lifecycle")));
            DocumentId source = new DocumentId(id(authored));
            ManagedDocumentSnapshot b = new ManagedDocumentSnapshot(source, source.value(), authored, false, false, true, 0, 0);
            ClosureInvocationInput admission = ClosureEvidenceFactory.admitClosure(snapshot(Collections.singletonList(b), Collections.emptyList()),
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION, "canonical-source:" + source.value(), null, null, "FULL_HISTORY"),
                    null, policy, environment);
            SourceObservationProgram[] captured = new SourceObservationProgram[1];
            try (BlueClosureContracts admitting = new BlueClosureContracts(owner, new ClosureExecutionObserver() {
                @Override public boolean capturesSourceObservationProgram() { return true; }
                @Override public void onSourceObservationProgram(SourceObservationProgram program) { captured[0] = program; }
                @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            })) {
                ClosureAttemptResult result = admitting.admitExternalScope(admission, Collections.singleton(source));
                assertTrue(result.isComplete());
                assertEquals(ProcessorStatus.SUCCESS, result.processResult().status());
            }
            initialization = SourceInitialization.fromProgram(captured[0]);
            assertEquals(1, initializerExecutions);
            Node later = initialization.program().sourceResults().get(0).document().properties("counter", new Node().value(BigInteger.valueOf(77)));
            canonicalSourceHead = olderAlias ? new ManagedDocumentSnapshot(source, id(later), later, true, false, true, 5L, 0L) : b;
            Node aBody = new Node().name("Order").properties("child", new Node().blueId(source.value()))
                    .properties("creatorRead", new Node().value(BigInteger.ZERO)).properties("counterB", new Node().value(BigInteger.ZERO))
                    .properties("childEventCount", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("external", typed(CHANNEL_ID))
                            .properties("create", handler("external").properties("order", new Node().value(BigInteger.ZERO)))
                            .properties("read", handler("external").properties("order", new Node().value(BigInteger.ONE)))
                            .properties("retire", handler("external").properties("order", new Node().value(BigInteger.valueOf(2))))
                            .properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/child")))
                            .properties("observe", handler("events")));
            if (action == Action.ATTACH_TWICE) {
                aBody.properties("second", new Node().blueId(source.value())).properties("secondRead", new Node().value(BigInteger.ZERO))
                        .properties("secondEventCount", new Node().value(BigInteger.ZERO));
                aBody.getContracts().properties("createSecond", handler("external").properties("order", new Node().value(BigInteger.valueOf(3))))
                        .properties("readSecond", handler("external").properties("order", new Node().value(BigInteger.valueOf(4))))
                        .properties("secondEvents", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/second")))
                        .properties("observeSecond", handler("secondEvents"));
            }
            if (olderAlias) {
                aBody.properties("existing", new Node().blueId(canonicalSourceHead.blueId()))
                        .properties("existingRead", new Node().value(BigInteger.ZERO)).properties("oldEventCount", new Node().value(BigInteger.ZERO));
                aBody.getContracts().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                        .properties("paths", new Node().items(new Node().value("/existing"))))
                        .properties("oldEvents", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/existing")))
                        .properties("observeOld", handler("oldEvents"));
            }
            aBody.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                    .properties("document", new Node().blueId(id(aBody))));
            ManagedDocumentSnapshot a = new ManagedDocumentSnapshot(ORDER, id(aBody), aBody, true, false, true, 0, 0);
            binding = ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), ORDER, ScopeAddress.embedded("/child", 1L),
                    source, source.value(), false, null);
            SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FULL_HISTORY,
                    ORDER, binding.occurrenceIdentity(), source, source.value());
            List<SameOriginAttachmentPolicy.Selection> selections = new ArrayList<>(Collections.singletonList(selection));
            Node event = new Node().name("Attach agreement");
            List<ManagedOccurrenceBinding> bindings = new ArrayList<>(Collections.singletonList(binding));
            if (action == Action.ATTACH_TWICE) {
                ManagedOccurrenceBinding second = ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), ORDER,
                        ScopeAddress.embedded("/second", 1L), source, source.value(), false, null);
                bindings.add(second);
                selections.add(new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FULL_HISTORY,
                        ORDER, second.occurrenceIdentity(), source, source.value()));
            }
            attachments = new SameOriginAttachmentPolicy(selections);
            if (olderAlias) bindings.add(ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), ORDER,
                    ScopeAddress.embedded("/existing", 1L), source, canonicalSourceHead.blueId(), true, null));
            input = ClosureEvidenceFactory.processClosure(snapshot(Arrays.asList(a, canonicalSourceHead), bindings,
                            olderAlias ? Collections.singletonList(ManagedReadPin.fromExactEvidence(source, source.value(), authored, null)) : Collections.emptyList()),
                    ClosureEvidenceFactory.externalCause(event, id(event), ExternalOrderKey.of(Arrays.asList(10L, id(event))), environment.externalOrderPolicyIdentity()),
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(ORDER), "external", "logical", 0)), policy, environment);
            contracts = new BlueClosureContracts(owner);
        }

        SameOriginProcessAttempt install(SourceInitialization value) {
            return contracts.processSameOrigin(input, attachments, Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(),
                    Collections.singletonList(value));
        }
        @Override public void close() { contracts.close(); owner.close(); language.close(); }
    }

    private static AffectedClosureSnapshot snapshot(List<ManagedDocumentSnapshot> documents, List<ManagedOccurrenceBinding> bindings) {
        return snapshot(documents, bindings, Collections.emptyList());
    }
    private static AffectedClosureSnapshot snapshot(List<ManagedDocumentSnapshot> documents, List<ManagedOccurrenceBinding> bindings, List<ManagedReadPin> pins) {
        Map<DocumentId, ManagedDocumentSnapshot> indexed = new TreeMap<>();
        for (ManagedDocumentSnapshot document : documents) indexed.put(document.documentId(), document);
        List<ComponentSnapshot> components = new ArrayList<>();
        for (List<DocumentId> component : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(indexed.keySet(), bindings)))
            components.add(ClosureEvidenceFactory.acyclicComponent(indexed.get(component.get(0))));
        return ClosureEvidenceFactory.affectedClosure(0L, documents, bindings, components, new ArrayList<>(indexed.keySet()), pins);
    }
    private static Node typed(String id) { return new Node().type(new Node().blueId(id)); }
    private static Node handler(String channel) { return typed(HANDLER_ID).properties("channel", new Node().value(channel)); }
    private static String id(Node value) { return DirectBlueIdCalculator.calculateBlueId(value); }
    private static String hash(char value) { char[] text = new char[64]; Arrays.fill(text, value); return "sha256:" + new String(text); }
    public static final class TestHandler extends HandlerContract { }
    public static final class TestChannel extends ChannelContract { }
    private static final class TestChannelProcessor implements ChannelProcessor<TestChannel> {
        @Override public Class<TestChannel> contractType() { return TestChannel.class; }
        @Override public ExternalChannelSubscriptionFunctions<TestChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<TestChannel>() {
                @Override public List<String> channelKeys(TestChannel value) { return Collections.singletonList("test"); }
                @Override public boolean preselects(TestChannel value, Node event, ExternalChannelFunctionContext context) { return true; }
                @Override public boolean accepts(TestChannel value, Node event, ExternalChannelFunctionContext context) { return true; }
                @Override public String logicalDeliveryKey(TestChannel value, Node event, Node payload, ExternalChannelFunctionContext context) { return "logical"; }
                @Override public String checkpointDomainDiscriminator(TestChannel value) { return "init-attachment-test"; }
            };
        }
    }
    private static final class Snapshots implements ProcessingSnapshotManager {
        private final BlueLanguageRuntime language;
        Snapshots(BlueLanguageRuntime language) { this.language = language; }
        @Override public blue.language.merge.ResolvedSnapshot fromDocument(Node document) { return language.snapshots().resolve(document.clone()); }
        @Override public blue.language.merge.ResolvedSnapshot fromDocumentPreservingPaths(Node document, Collection<String> paths) {
            return language.snapshots().resolvePreservingPaths(document.clone(), paths);
        }
        @Override public blue.language.merge.ResolvedSnapshot fromDocumentTransientPreservingPaths(Node document, Collection<String> paths) {
            return fromDocumentPreservingPaths(document, paths);
        }
        @Override public blue.language.merge.ResolvedSnapshot applyPatch(blue.language.merge.ResolvedSnapshot snapshot, JsonPatch patch) {
            return language.patching().apply(snapshot, patch);
        }
        @Override public blue.language.merge.ResolvedSnapshot cacheSnapshot(blue.language.merge.ResolvedSnapshot snapshot) { return language.snapshots().cache(snapshot); }
    }
}
