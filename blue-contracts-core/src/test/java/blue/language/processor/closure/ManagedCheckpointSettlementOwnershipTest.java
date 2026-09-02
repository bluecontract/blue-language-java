package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ManagedCheckpointCandidate;
import blue.language.processor.ManagedCheckpointSettlementEntry;
import blue.language.processor.ManagedDocumentStepRuntime;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checkpoint ownership is per actual Root work, not affected-cohort membership. */
final class ManagedCheckpointSettlementOwnershipTest {
    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final Node CHANNEL_TYPE = new Node().name("Checkpoint ownership source Channel");
    private static final String CHANNEL_ID = blueId(CHANNEL_TYPE);
    private static final Node HANDLER_TYPE = new Node().name("Checkpoint ownership test Handler");
    private static final String HANDLER_ID = blueId(HANDLER_TYPE);
    private static final String CHECKPOINT = "/contracts/checkpoint/entries/ownerChannel";

    @Test
    void untouchedRetainedSourceIsNotSettledOrCleaned() {
        try (Fixture fixture = new Fixture()) {
            Node b = fixture.staleCheckpointSource(false);
            Node a = fixture.root("a", "change", false);
            a.properties("peer", new Node().blueId(blueId(b)));
            a.getContracts().properties("embedded", embedded("/peer"));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b),
                    Collections.singletonList(fixture.binding(A, "/peer", B, blueId(b))));

            Run run = fixture.run(snapshot);

            assertSuccess(run);
            assertEquals(Collections.singletonList("a:change"), fixture.probe.calls);
            assertOnlyAWork(run);
            assertEquals(blueId(b), result(run, B).afterBlueId());
            assertEquals(3L, result(run, B).epoch());
            assertNodeEquals(b, result(run, B).document());
            assertTrue(writesFor(run, B).isEmpty());
            assertTrue(run.result.managedTransitionReceipts().stream()
                    .allMatch(receipt -> A.equals(receipt.documentId())));
        }
    }

    @Test
    void acceptedActualWorkSettlesDespiteEqualBusinessState() {
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.seedCheckpoint(fixture.root("a", "noop", false),
                    "ownerChannel", ignored -> { });
            AffectedClosureSnapshot snapshot = fixture.snapshot(
                    Collections.singletonMap(A, a), Collections.emptyList());

            Run run = fixture.run(snapshot);

            assertSuccess(run);
            assertEquals(Collections.singletonList("a:noop"), fixture.probe.calls);
            assertOnlyAWork(run);
            assertEquals("same", result(run, A).document().getAsText("/business"));
            assertEquals(4L, result(run, A).epoch(),
                    "Checkpoint-only settlement does not invent a business-work epoch");
            assertNotEquals(snapshot.managedDocument(A).blueId(), result(run, A).afterBlueId());
            assertEquals(1, run.result.documentTransitionEvidence().size());
            DocumentTransitionEvidence work = run.result.documentTransitionEvidence().get(0);
            assertEquals(work.beforeDocumentBlueId(), work.afterDocumentBlueId(),
                    "The accepted managed step is an exact no-op before settlement");
            assertEquals(1, writesFor(run, A).size());
            CheckpointWrite write = writesFor(run, A).get(0);
            assertTrue(write.beforePresent());
            assertTrue(write.afterPresent());
            assertEquals(write.beforeDomainBlueId(), write.afterDomainBlueId());
            assertNotEquals(write.beforeSubjectBlueId(), write.afterSubjectBlueId());
            assertEquals(((ExternalEventCause) run.input.cause()).eventBlueId(), write.afterSubjectBlueId());
        }
    }

    @Test
    void actualChannelRemovalLegitimatelyCleansItsCheckpoint() {
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.rootWithOtherCheckpoint("removeOther");
            AffectedClosureSnapshot snapshot = fixture.snapshot(
                    Collections.singletonMap(A, a), Collections.emptyList());

            Run run = fixture.run(snapshot);

            assertSuccess(run);
            assertOnlyAWork(run);
            assertEquals(Collections.singletonList("a:removeOther"), fixture.probe.calls);
            assertNull(NodePathEditor.getOrNull(result(run, A).document(), "/contracts/other"));
            assertNull(NodePathEditor.getOrNull(result(run, A).document(),
                    "/contracts/checkpoint/entries/other"));
            assertEquals(2, writesFor(run, A).size());
            CheckpointWrite cleanup = writesFor(run, A).stream()
                    .filter(write -> "other".equals(write.rawChannelKey())).findFirst().get();
            assertTrue(cleanup.beforePresent());
            assertFalse(cleanup.afterPresent());
            assertNotNull(NodePathEditor.getOrNull(result(run, A).document(), CHECKPOINT));
            assertEquals(5L, result(run, A).epoch());
            assertNodeEquals(a, snapshot.managedDocument(A).document());
        }
    }

    @Test
    void failedProcessingPublishesNoCheckpointChange() {
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.rootWithOtherCheckpoint("fail");
            AffectedClosureSnapshot snapshot = fixture.snapshot(
                    Collections.singletonMap(A, a), Collections.emptyList());

            Run run = fixture.run(snapshot);

            assertEquals(ProcessorStatus.RUNTIME_FATAL, run.result.status());
            assertFalse(run.result.commits());
            assertEquals(Collections.singletonList("a:fail"), fixture.probe.calls);
            assertOnlyAWork(run);
            assertEquals(snapshot.closureIdentity(), run.result.outputClosureIdentity());
            assertTrue(run.result.checkpointWrites().isEmpty());
            assertTrue(run.result.managedTransitionReceipts().isEmpty());
            assertEquals(snapshot.managedDocument(A).blueId(), result(run, A).afterBlueId());
            assertEquals(4L, result(run, A).epoch());
            assertNodeEquals(a, result(run, A).document());
            assertNotNull(NodePathEditor.getOrNull(result(run, A).document(),
                    "/contracts/checkpoint/entries/other"));
        }
    }

    @Test
    void representationOnlyCyclicRebindDoesNotInventCheckpointCleanup() {
        try (Fixture fixture = new Fixture()) {
            Node a = fixture.root("a", "change", false);
            a.properties("peer", new Node().blueId("this#1"));
            a.getContracts().properties("embedded", embedded("/peer"));
            Node b = fixture.staleCheckpointSource(true);
            b.properties("parent", new Node().blueId("this#0"));
            CyclicSetFinalization cycle = new CircularSetIdentityCalculator()
                    .finalizeCyclicSet(Arrays.asList(a, b));
            String aId = cycle.membersInInputOrder().get(0).finalBlueId();
            String bId = cycle.membersInInputOrder().get(1).finalBlueId();
            a = cycle.membersInInputOrder().get(0).canonicalMemberBody();
            b = cycle.membersInInputOrder().get(1).canonicalMemberBody();
            NodePathEditor.put(a, "/peer", new Node().blueId(bId));
            NodePathEditor.put(b, "/parent", new Node().blueId(aId));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b), Arrays.asList(
                    fixture.binding(A, "/peer", B, bId),
                    fixture.binding(B, "/parent", A, aId)));
            assertEquals(1, snapshot.components().size());
            assertEquals(ComponentKind.CYCLIC, snapshot.components().get(0).kind());
            Node before = snapshot.managedDocument(B).document();
            assertNotNull(NodePathEditor.getOrNull(before, CHECKPOINT));

            Run run = fixture.run(snapshot);

            assertSuccess(run);
            assertOnlyAWork(run);
            assertEquals(Collections.singletonList("a:change"), fixture.probe.calls);
            assertNotEquals(snapshot.managedDocument(B).blueId(), result(run, B).afterBlueId(),
                    "The untouched peer must actually be re-encoded by the cycle finalizer");
            assertTrue(run.result.documentTransitionEvidence().stream()
                    .noneMatch(work -> B.equals(work.documentId())));
            System.out.println("CYCLIC_CHECKPOINT_OWNERSHIP before="
                    + snapshot.managedDocument(B).blueId() + " after=" + result(run, B).afterBlueId()
                    + " beforeEpoch=" + snapshot.managedDocument(B).epoch()
                    + " afterEpoch=" + result(run, B).epoch()
                    + " peerCheckpointWrites=" + writesFor(run, B).stream()
                            .map(write -> write.rawChannelKey() + ":" + write.beforePresent()
                                    + "->" + write.afterPresent()).collect(Collectors.toList()));
            assertTrue(writesFor(run, B).isEmpty(),
                    "Representation-only cyclic rebind does not own checkpoint cleanup");
            assertNodeEquals(NodePathEditor.getOrNull(before, CHECKPOINT),
                    NodePathEditor.getOrNull(result(run, B).document(), CHECKPOINT));
            Node ownedBefore = before.clone();
            Node ownedAfter = result(run, B).document();
            NodePathEditor.put(ownedBefore, "/parent", new Node().value("managed-reference-slot"));
            NodePathEditor.put(ownedAfter, "/parent", new Node().value("managed-reference-slot"));
            assertNodeEquals(ownedBefore, ownedAfter);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Map<String, Node> exact = new LinkedHashMap<>();
        private final ProbeProcessor probe = new ProbeProcessor();
        private final DocumentProcessor owner;
        private final ClosureEnvironment environment;

        private Fixture() {
            exact.put(CHANNEL_ID, CHANNEL_TYPE.clone());
            exact.put(HANDLER_ID, HANDLER_TYPE.clone());
            NodeProvider runtime = BlueRuntimeTypeRegistry.getDefault().asProvider();
            NodeProvider provider = id -> exact.containsKey(id)
                    ? Collections.singletonList(exact.get(id).clone())
                    : runtime.fetchByBlueId(id);
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                    .register(CHANNEL_ID, CHANNEL_TYPE, new SourceProcessor())
                    .register(HANDLER_ID, HANDLER_TYPE, probe).build();
            owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider).build();
            environment = ClosureEvidenceFactory.environment(owner, hash('a'), hash('b'),
                    "checkpoint-ownership-document-v1", "checkpoint-ownership-binding-v1",
                    "checkpoint-ownership-provider-v1", "checkpoint-ownership-order-v1",
                    "checkpoint-ownership-limits-v1", GasSchedule.contracts10().portableLimits());
        }

        private Node root(String label, String handlerKey, boolean catalog) {
            Node root = new Node().name("Checkpoint ownership " + label)
                    .properties("label", new Node().value(label))
                    .properties("business", new Node().value("same"))
                    .contracts(new Node().properties("ownerChannel", source(catalog))
                            .properties(handlerKey, handler("ownerChannel")));
            String authored = blueId(root);
            exact.put(authored, root.clone());
            root.getContracts().properties("initialized",
                    typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                            .properties("document", new Node().blueId(authored)));
            return root;
        }

        private Node staleCheckpointSource(boolean cyclic) {
            Node source = root("b", "catalogChange", true);
            if (cyclic) {
                source.getContracts().properties("embedded", embedded("/parent"));
            }
            Node frozen = seedCheckpoint(source, "ownerChannel",
                    after -> after.getContracts().properties("never", handler("ownerChannel")));
            try (ManagedDocumentStepRuntime step = new ManagedDocumentStepRuntime(owner)) {
                String currentDomain = step.projectRootSubscriptionSurface(frozen)
                        .externalSubscriptions().get(0).checkpointDomainBlueId();
                assertNotEquals(blueId(frozen.getAsNode(CHECKPOINT + "/domain")), currentDomain,
                        "The historical checkpoint deliberately retains its pre-change catalog domain");
            }
            return frozen;
        }

        private Node rootWithOtherCheckpoint(String handlerKey) {
            Node a = root("a", handlerKey, false);
            a.getContracts().properties("other", source(false))
                    .properties("oldOther", handler("other"));
            return seedCheckpoint(a, "other", ignored -> { });
        }

        private Node seedCheckpoint(Node before, String channel, Consumer<Node> change) {
            Node event = event("previous-" + before.getAsText("/label") + "-" + channel);
            exact.put(blueId(event), event.clone());
            GasChargeContext context = GasChargeContext.closure(before.getAsText("/label"),
                    "/", 0L, 1L, channel, null, null, "fixture.seed-checkpoint");
            try (ManagedDocumentStepRuntime step = new ManagedDocumentStepRuntime(owner)) {
                ManagedCheckpointCandidate candidate = step.classifyExternalDelivery(
                        before,
                        channel,
                        ExactEventIdentityEvidence.verify(
                                null,
                                event,
                                blueId(event),
                                null),
                        context).candidate();
                assertNotNull(candidate);
                exact.put(candidate.domain().blueId(), candidate.domain().exactValue());
                Node after = before.clone();
                change.accept(after);
                Node seeded = step.settleCheckpoints(after, Collections.singletonList(
                        new ManagedCheckpointSettlementEntry(candidate, 0L, context)),
                        (key, ordinal) -> context, context).resultingBody();
                String referencedId = blueId(seeded);
                // The pure fixture has no Language snapshot manager. Keep the
                // complete immutable descriptor inline without changing its BlueId.
                NodePathEditor.put(seeded, "/contracts/checkpoint/entries/" + channel + "/domain",
                        candidate.domain().exactValue());
                assertEquals(referencedId, blueId(seeded));
                return seeded;
            }
        }

        private ManagedOccurrenceBinding binding(DocumentId source, String path,
                DocumentId target, String expectedId) {
            return ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(),
                    source, ScopeAddress.embedded(path, 1L), target, expectedId, true, null);
        }

        private AffectedClosureSnapshot snapshot(Map<DocumentId, Node> bodies,
                List<ManagedOccurrenceBinding> bindings) {
            Map<DocumentId, Long> generations = new LinkedHashMap<>();
            bodies.keySet().forEach(id -> generations.put(id, 1L));
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                    new ComponentFinalizationInput(ManagedDocumentGraph.fromBindings(bodies.keySet(), bindings),
                            generations, bodies, bindings));
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            finalized.documents().forEach((id, value) -> documents.add(new ManagedDocumentSnapshot(
                    id, value.blueId(), value.document(), true, false, A.equals(id),
                    A.equals(id) ? 4L : 3L, value.componentGeneration())));
            return ClosureEvidenceFactory.affectedClosure(1L, documents,
                    finalized.finalizedGraph().bindings(), finalized.components().stream()
                            .map(FinalizedComponentEvidence::component).collect(Collectors.toList()),
                    Collections.singletonList(A));
        }

        private Run run(AffectedClosureSnapshot snapshot) {
            Node event = event("next-a");
            exact.put(blueId(event), event.clone());
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, blueId(event),
                    ExternalOrderKey.of(Arrays.<Object>asList(1L, "checkpoint-ownership")),
                    environment.externalOrderPolicyIdentity());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, cause,
                    Collections.singletonList(new DirectLogicalDelivery(
                            ManagedScopeKey.root(A), "ownerChannel", "ownerChannel", 0L)),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(),
                            "checkpoint-ownership-v1"), environment);
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }
            assertTrue(attempt.isComplete(), () -> "Fixture resource boundary: " + attempt.kind()
                    + " exact=" + attempt.requiredExactBlueIds());
            assertNotNull(capture.evidence);
            return new Run(input, attempt.processResult(), capture.evidence);
        }

        @Override
        public void close() {
            owner.close();
        }
    }

    /** Fixture-only Channel with an optional Timeline-shaped catalog dependency. */
    public static final class Source extends ChannelContract {
        private Boolean catalog;
        public Boolean getCatalog() { return catalog; }
        public void setCatalog(Boolean value) { catalog = value; }
    }

    private static final class SourceProcessor implements ChannelProcessor<Source> {
        @Override
        public Class<Source> contractType() { return Source.class; }

        @Override
        public ExternalChannelSubscriptionFunctions<Source> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<Source>() {
                @Override
                public List<String> channelKeys(Source channel) {
                    return Collections.singletonList("checkpoint-ownership");
                }
                @Override
                public boolean preselects(Source channel, Node event) { return true; }
                @Override
                public boolean accepts(Source channel, Node event) { return true; }
                @Override
                public Node payload(Source channel, Node event) { return event.clone(); }
                @Override
                public String checkpointDomainDiscriminator(Source channel,
                        ExternalChannelFunctionContext context) {
                    if (Boolean.TRUE.equals(channel.getCatalog())) {
                        context.dependOnSameScopeChannelCatalog();
                    }
                    return "checkpoint-ownership-source-v1";
                }
            };
        }
    }

    /** Fixture-only Handler; its exact raw contract key chooses the action. */
    public static final class Probe extends HandlerContract { }

    private static final class ProbeProcessor implements HandlerProcessor<Probe> {
        private final List<String> calls = new ArrayList<>();
        @Override
        public Class<Probe> contractType() { return Probe.class; }
        @Override
        public void execute(Probe contract, ProcessorExecutionContext context) {
            String key = context.contractKey();
            calls.add(context.documentAt("/label").getValue() + ":" + key);
            if ("change".equals(key)) {
                context.applyPatch(JsonPatch.replace("/business", new Node().value("changed")));
            } else if ("removeOther".equals(key) || "fail".equals(key)) {
                context.applyPatch(JsonPatch.remove("/contracts/oldOther"));
                context.applyPatch(JsonPatch.remove("/contracts/other"));
                if ("fail".equals(key)) {
                    throw new IllegalStateException("checkpoint ownership fixture failure");
                }
            } else if ("catalogChange".equals(key)) {
                context.applyPatch(JsonPatch.add("/contracts/never", handler("ownerChannel")));
            } else if (!"noop".equals(key) && !"oldOther".equals(key)) {
                throw new AssertionError("Untouched retained source was processed: " + key);
            }
        }
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;
        @Override
        public void onExecutionEvidence(ClosureImplementationEvidence value) { evidence = value; }
    }

    private static final class Run {
        private final ClosureInvocationInput input;
        private final ClosureProcessResult result;
        private final ClosureImplementationEvidence evidence;
        private Run(ClosureInvocationInput input, ClosureProcessResult result,
                ClosureImplementationEvidence evidence) {
            this.input = input;
            this.result = result;
            this.evidence = evidence;
        }
    }

    private static Node source(boolean catalog) {
        return typed(CHANNEL_ID).properties("catalog", new Node().value(catalog));
    }
    private static Node handler(String channel) {
        return typed(HANDLER_ID).properties("channel", new Node().value(channel));
    }
    private static Node embedded(String path) {
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                .properties("paths", new Node().items(new Node().value(path)));
    }
    private static Node typed(String id) { return new Node().type(new Node().blueId(id)); }
    private static Node event(String label) {
        return new Node().name(label).properties("subscriptionKey", new Node().value("checkpoint-ownership"));
    }
    private static String blueId(Node value) { return DirectBlueIdCalculator.calculateBlueId(value); }
    private static String hash(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return "sha256:" + new String(chars);
    }
    private static Map<DocumentId, Node> bodies(Node a, Node b) {
        Map<DocumentId, Node> result = new LinkedHashMap<>();
        result.put(A, a);
        result.put(B, b);
        return result;
    }
    private static ResultingDocument result(Run run, DocumentId id) {
        return run.result.resultingDocuments().stream()
                .filter(document -> id.equals(document.documentId())).findFirst().get();
    }
    private static List<CheckpointWrite> writesFor(Run run, DocumentId id) {
        return run.result.checkpointWrites().stream()
                .filter(write -> id.equals(write.targetManagedScopeKey().documentId()))
                .collect(Collectors.toList());
    }
    private static void assertSuccess(Run run) {
        assertEquals(ProcessorStatus.SUCCESS, run.result.status(),
                () -> String.valueOf(run.result.diagnostic()));
        assertTrue(run.result.commits());
    }
    private static void assertOnlyAWork(Run run) {
        assertFalse(run.evidence.workTrace().isEmpty());
        assertTrue(run.evidence.workTrace().stream().allMatch(work -> A.equals(work.targetDocumentId())));
        assertTrue(run.evidence.workTrace().stream().noneMatch(work -> work.kind() == WorkKind.INITIALIZATION));
    }
    private static void assertNodeEquals(Node expected, Node actual) {
        assertNotNull(actual);
        assertEquals(NodeWireForm.get(expected, NodeWireForm.Strategy.SIMPLE),
                NodeWireForm.get(actual, NodeWireForm.Strategy.SIMPLE));
    }
}
