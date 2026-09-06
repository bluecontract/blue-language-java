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

/** An exact historical cyclic read is distinct from eligibility for live source delivery. */
class PendingCyclicReadTest {
    private static final DocumentId A = new DocumentId("a"), B = new DocumentId("b"), P = new DocumentId("parent");
    private static final Node CHANNEL = new Node().name("Pending cyclic read external channel");
    private static final Node HANDLER = new Node().name("Pending cyclic read handler");
    private static final String CHANNEL_ID = id(CHANNEL), HANDLER_ID = id(HANDLER);

    @Test void finiteNestedReadDemandsOnlyTheMissingExactCyclicMemberThenMatchesWarmExecution() {
        try (Fixture fixture = new Fixture("/child/b/counter")) {
            AffectedClosureSnapshot full = fixture.snapshot(Collections.emptyList());
            AffectedClosureSnapshot sparse = full.retainResidentBodies(new HashSet<>(Arrays.asList(P, A)),
                    fixture.contracts.captureRootMetadata(full));
            assertFalse(sparse.managedDocument(B).hasResidentBody());
            assertFalse(sparse.component(A).hasResidentCyclicProof());
            SameOriginProcessAttempt missing = fixture.contracts.processSameOrigin(fixture.input(sparse, false));
            assertFalse(missing.complete());
            assertEquals(Collections.singletonList(full.managedDocument(B).blueId()), missing.requiredExactBlueIds());
            ManagedDocumentSnapshot b = full.managedDocument(B);
            AffectedClosureSnapshot hydrated = sparse.withResidentBody(ManagedReadPin.fromExactEvidence(B, b.blueId(), b.document(),
                    full.component(B).completeCyclicProof()));
            SameOriginOperationResult cold = onlyParent(fixture.contracts.processSameOrigin(fixture.input(hydrated, false)));
            SameOriginOperationResult warm = onlyParent(fixture.contracts.processSameOrigin(fixture.input(full, false)));
            assertEquals(BigInteger.valueOf(2), value(cold, "seen"));
            assertEquals(warm.operationIdentity(), cold.operationIdentity());
            assertEquals(warm.gasTraceIdentity(), cold.gasTraceIdentity());
            assertEquals(warm.resultingDocuments().get(0).afterBlueId(), cold.resultingDocuments().get(0).afterBlueId());
            assertPending(cold, full.managedDocument(A).blueId());
            assertTrue(fixture.calls.stream().noneMatch("observe"::equals));
        }
    }

    @Test void pendingHistoricalParentReadsOldCyclicPinWithoutReceivingLiveSourceEvents() {
        try (Fixture fixture = new Fixture("/child/counter")) {
            AffectedClosureSnapshot initial = fixture.snapshot(Collections.emptyList());
            List<ManagedReadPin> pins = new ArrayList<>();
            for (DocumentId member : Arrays.asList(A, B)) {
                ManagedDocumentSnapshot exact = initial.managedDocument(member);
                pins.add(ManagedReadPin.fromExactEvidence(member, exact.blueId(), exact.document(),
                        initial.component(member).completeCyclicProof()));
            }
            SameOriginProcessAttempt result = fixture.contracts.processSameOrigin(fixture.input(fixture.snapshot(pins), true));
            assertTrue(result.complete(), () -> result.requiredExactBlueIds().toString());
            assertEquals(2, result.operations().size());
            for (SameOriginOperationResult operation : result.operations()) assertEquals(ProcessorStatus.SUCCESS, operation.status());
            SameOriginOperationResult source = result.operations().stream().filter(value -> value.ownedDocumentIds().contains(A)).findFirst().get();
            assertEquals(new TreeSet<>(Arrays.asList(A, B)), source.ownedDocumentIds());
            assertEquals(BigInteger.valueOf(77), source.resultingDocuments().stream().filter(value -> value.documentId().equals(A))
                    .findFirst().get().document().getProperties().get("counter").getValue());
            assertEquals(1, source.events().size());
            SameOriginOperationResult parent = result.operations().stream().filter(value -> value.ownedDocumentIds().contains(P)).findFirst().get();
            assertEquals(BigInteger.ONE, value(parent, "seen"), "A pending read must not promote to the new live source head 77");
            assertEquals(BigInteger.ZERO, value(parent, "eventCount"));
            assertTrue(parent.consumedSourceOperations().isEmpty(), "No live source reaction was due for this pending lane");
            assertPending(parent, initial.managedDocument(A).blueId());
            assertEquals(Arrays.asList("emit", "read"), fixture.calls);
        }
    }

    private static SameOriginOperationResult onlyParent(SameOriginProcessAttempt attempt) {
        assertTrue(attempt.complete(), () -> attempt.requiredExactBlueIds().toString());
        assertEquals(1, attempt.operations().size());
        SameOriginOperationResult result = attempt.operations().get(0);
        assertEquals(Collections.singleton(P), result.ownedDocumentIds());
        assertEquals(ProcessorStatus.SUCCESS, result.status(), () -> result.failure().map(Object::toString).orElse(""));
        return result;
    }

    private static BigInteger value(SameOriginOperationResult result, String key) {
        return (BigInteger) result.resultingDocuments().get(0).document().getProperties().get(key).getValue();
    }

    private static void assertPending(SameOriginOperationResult parent, String selectedBlueId) {
        ManagedOccurrenceBinding pending = parent.sourceProgram().get().sourceAfterBindings().get(0);
        assertFalse(pending.active()); assertEquals(Long.valueOf(0), pending.pendingHistoricalEpoch());
        assertEquals(selectedBlueId, pending.expectedTargetBlueId());
        assertEquals(selectedBlueId, parent.resultingDocuments().get(0).document().getProperties().get("child").getBlueId());
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> calls = new ArrayList<>();
        final BlueLanguageRuntime language;
        final DocumentProcessor owner;
        final BlueClosureContracts contracts;
        final ClosureEnvironment environment;
        final ExecutionPolicy policy;
        final List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        final List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
        final List<ComponentSnapshot> components = new ArrayList<>();

        Fixture(String readPath) {
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                    .register(CHANNEL_ID, CHANNEL, new TestChannelProcessor())
                    .register(HANDLER_ID, HANDLER, new HandlerProcessor<TestHandler>() {
                        @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                        @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                            calls.add(context.contractKey());
                            if ("emit".equals(context.contractKey())) {
                                context.applyPatch(JsonPatch.replace("/counter", new Node().value(BigInteger.valueOf(77))));
                                context.emitEvent(new Node().name("source changed"));
                            } else if ("read".equals(context.contractKey())) {
                                context.applyPatch(JsonPatch.replace("/seen", new Node().value(context.resolvedFrozenAt(readPath).getValue())));
                            } else if ("observe".equals(context.contractKey())) {
                                context.applyPatch(JsonPatch.replace("/eventCount", new Node().value(BigInteger.ONE)));
                            }
                        }
                    }).build();
            NodeProvider provider = blueId -> {
                if (CHANNEL_ID.equals(blueId)) return Collections.singletonList(CHANNEL.clone());
                if (HANDLER_ID.equals(blueId)) return Collections.singletonList(HANDLER.clone());
                return BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(blueId);
            };
            language = BlueLanguageRuntime.create(provider, BlueCachePolicy.disabled(), Collections.emptyMap());
            owner = DocumentProcessor.builder().nodeProvider(provider).runtimeRegistry(registry).snapshotStore(new Snapshots(language)).build();
            contracts = new BlueClosureContracts(owner);
            environment = ClosureEvidenceFactory.environment(owner, hash('a'), hash('b'), "pending-source-identity", "pending-bindings",
                    "pending-provider", "pending-order", "pending-limits", GasSchedule.contracts10().portableLimits());
            policy = ClosureEvidenceFactory.executionPolicy(100000, Collections.emptyMap(), "pending-policy");
            String placeholderA = id(new Node().name("placeholder A")), placeholderB = id(new Node().name("placeholder B"));
            Node a = markInitialized(new Node().name("A").properties("counter", new Node().value(BigInteger.ONE))
                    .properties("b", new Node().blueId(placeholderB)).contracts(new Node()
                            .properties("external", typed(CHANNEL_ID)).properties("emit", handler("external"))
                            .properties("embedded", embedded("/b"))));
            Node b = markInitialized(new Node().name("B").properties("counter", new Node().value(BigInteger.valueOf(2)))
                    .properties("a", new Node().blueId(placeholderA)).contracts(new Node().properties("embedded", embedded("/a"))));
            List<ManagedOccurrenceBinding> original = Arrays.asList(binding(A, "/b", B, placeholderB, true, null),
                    binding(B, "/a", A, placeholderA, true, null));
            Map<DocumentId, Node> bodies = new LinkedHashMap<>(); bodies.put(A, a); bodies.put(B, b);
            Map<DocumentId, Long> generations = new LinkedHashMap<>(); generations.put(A, 0L); generations.put(B, 0L);
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                    ManagedDocumentGraph.fromBindings(Arrays.asList(A, B), original), generations, bodies, original));
            for (DocumentId member : Arrays.asList(A, B)) {
                FinalizedDocumentEvidence exact = finalized.document(member);
                documents.add(new ManagedDocumentSnapshot(member, exact.blueId(), exact.document(), true, false, true, 0, exact.componentGeneration()));
            }
            bindings.addAll(finalized.finalizedGraph().bindings());
            for (FinalizedComponentEvidence component : finalized.components()) components.add(component.component());
            String aId = finalized.document(A).blueId();
            Node p = markInitialized(new Node().name("Parent").properties("child", new Node().blueId(aId))
                    .properties("seen", new Node().value(BigInteger.ZERO)).properties("eventCount", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("external", typed(CHANNEL_ID)).properties("read", handler("external"))
                            .properties("embedded", embedded("/child"))
                            .properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL).properties("sourcePath", new Node().value("/child")))
                            .properties("observe", handler("events"))));
            ManagedDocumentSnapshot parent = new ManagedDocumentSnapshot(P, id(p), p, true, false, true, 0, 0);
            documents.add(parent); components.add(ClosureEvidenceFactory.acyclicComponent(parent));
            bindings.add(binding(P, "/child", A, aId, false, 0L));
        }

        ManagedOccurrenceBinding binding(DocumentId source, String path, DocumentId target, String ref, boolean active, Long pending) {
            return ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), source, ScopeAddress.embedded(path, 1),
                    target, ref, active, pending);
        }

        AffectedClosureSnapshot snapshot(List<ManagedReadPin> pins) {
            return ClosureEvidenceFactory.affectedClosure(1, documents, bindings, components, Arrays.asList(A, B, P), pins);
        }

        ClosureInvocationInput input(AffectedClosureSnapshot snapshot, boolean sourceToo) {
            Node event = new Node().name("pending cyclic test cause");
            List<DirectLogicalDelivery> direct = new ArrayList<>();
            if (sourceToo) direct.add(new DirectLogicalDelivery(ManagedScopeKey.root(A), "external", "logical", 0));
            direct.add(new DirectLogicalDelivery(ManagedScopeKey.root(P), "external", "logical", sourceToo ? 1 : 0));
            return ClosureEvidenceFactory.processClosure(snapshot, ClosureEvidenceFactory.externalCause(event, id(event),
                    ExternalOrderKey.of(Arrays.asList(10L, id(event))), environment.externalOrderPolicyIdentity()), direct, policy, environment);
        }

        @Override public void close() { contracts.close(); owner.close(); language.close(); }
    }

    private static Node embedded(String path) { return typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value(path))); }
    private static Node handler(String channel) { return typed(HANDLER_ID).properties("channel", new Node().value(channel)); }
    private static Node typed(String blueId) { return new Node().type(new Node().blueId(blueId)); }
    private static Node markInitialized(Node value) { return value.contracts(value.getContracts().properties("initialized",
            typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER).properties("document", new Node().blueId(id(value))))); }
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
                @Override public String checkpointDomainDiscriminator(TestChannel value) { return "pending-cyclic-test"; }
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
