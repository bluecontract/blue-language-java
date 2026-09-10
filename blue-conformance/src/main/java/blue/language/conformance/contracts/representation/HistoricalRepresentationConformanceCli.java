package blue.language.conformance.contracts.representation;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


import blue.language.processor.closure.*;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.InputStream;
import java.util.Objects;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import org.erdtman.jcs.JsonCanonicalizer;
import blue.language.conformance.api.BlueContractsConformanceReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Executable proposed conformance for an authenticated X -> Y -> X history. */
public final class HistoricalRepresentationConformanceCli {
    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final Node CHANNEL_TYPE = new Node().name("Checkpoint ownership source Channel");
    private static final String CHANNEL_ID = blueId(CHANNEL_TYPE);
    private static final Node HANDLER_TYPE = new Node().name("Checkpoint ownership test Handler");
    private static final String HANDLER_ID = blueId(HANDLER_TYPE);
    private static final String CHECKPOINT = "/contracts/checkpoint/entries/ownerChannel";

    private HistoricalRepresentationConformanceCli() { }
    private static final String PACKAGE_IDENTITY = "sha256:14bebea9b179ff07c043cc9f250ca1377f13fff3e7459d956499ae4da2915dd1";
    private static final String ROOT = "/blue-contracts-representation-1.0/fixtures/";

    /**
     * Runs the explicit historical-representation sequence through public processor APIs.
     * @param args one output report path
     * @throws Exception if fixture evidence, processing or report writing fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output report path");
        verifyPackage();
        ObjectNode report = UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
        report.put(BlueLanguageConstants.OBJECT_SCHEMA, "blue-contracts-representation-conformance-report/1");
        report.put("packageIdentity", PACKAGE_IDENTITY);
        com.fasterxml.jackson.databind.node.ArrayNode cases = report.putArray("cases");
        for (String file : Arrays.asList("repeated-identity.json", "repeated-identity-next-revision.json")) {
            JsonNode input;
            try (InputStream stream = HistoricalRepresentationConformanceCli.class.getResourceAsStream(ROOT + file)) {
                if (stream == null) throw new IllegalStateException("Missing representation fixture " + file);
                input = UncheckedObjectMapper.JSON_MAPPER.readTree(stream);
            }
            assertEquals("blue-contracts-representation-sequence-fixture/1", input.path(BlueLanguageConstants.OBJECT_SCHEMA).textValue());
            assertTrue(Arrays.equals(new int[]{0, 1, 0, 2}, UncheckedObjectMapper.JSON_MAPPER.convertValue(
                    input.path("sourceValues"), int[].class)), "Unexpected authored source history");
            ObjectNode result = execute(input);
            JsonNode expected = input.path("expected");
            assertTrue(expected.isObject(), "Missing expected results");
            expected.fields().forEachRemaining(field -> assertEquals(field.getValue().toString(), result.path(field.getKey()).toString(),
                    "Fixture expectation " + field.getKey()));
            cases.add(result);
        }
        report.put("passed", cases.size());
        report.put("failed", 0);
        report.put("skipped", 0);
        Files.createDirectories(Paths.get(args[0]).toAbsolutePath().getParent());
        Files.write(Paths.get(args[0]), UncheckedObjectMapper.JSON_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(report));
        System.out.println(report.toString());
    }
    static ObjectNode execute(JsonNode fixtureInput) {
        try (Fixture fixture = new Fixture()) {
            Node b = fixture.root("b", "attach", false);
            b.getContracts().properties("embedded", embedded("/peer"));
            Node a = fixture.root("a", "assign", false);
            a.properties("peer", new Node().blueId(blueId(b)));
            a.getContracts().properties("embedded", embedded("/peer"));
            AffectedClosureSnapshot snapshot = fixture.snapshot(bodies(a, b),
                    Collections.singletonList(fixture.binding(A, "/peer", B, blueId(b))));
            List<Run> sourceHistory = new ArrayList<>();
            for (int value : UncheckedObjectMapper.JSON_MAPPER.convertValue(fixtureInput.path("sourceValues"), int[].class)) {
                Run run = fixture.external(snapshot, A,
                        event("assign-" + value).properties("business", new Node().value(value)),
                        sourceHistory.size() + 1L, null);
                assertSuccess(run);
                sourceHistory.add(run);
                snapshot = fixture.after(run.result);
            }
            String saved = result(sourceHistory.get(0), A).afterBlueId();
            assertEquals(saved, result(sourceHistory.get(2), A).afterBlueId(),
                    "Repeated external exact events are independently ordered; real state and checkpoint return exactly");
            assertNotEquals(saved, result(sourceHistory.get(1), A).afterBlueId());
            long savedEpoch = result(sourceHistory.get(0), A).epoch();
            Run attached = fixture.external(snapshot, B,
                    event("attach-saved").properties("target", new Node().blueId(saved)), 5L, savedEpoch);
            assertSuccess(attached);
            snapshot = fixture.after(attached.result);
            String x = snapshot.managedDocument(A).blueId();
            long epoch = snapshot.managedDocument(A).epoch();
            ManagedDocumentTransitionReceipt anchor = receipt(attached, A);
            String predecessor = anchor.transitionReceiptIdentity();
            List<ManagedRepresentationTransition> positions = new ArrayList<>();
            for (int index = 1; index <= 2; index++) {
                ManagedOccurrenceBinding pending = snapshot.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(B)).findFirst().get();
                Run historical = sourceHistory.get(index);
                ResultingDocument historicalA = result(historical, A);
                ManagedRevisionCause cause = ClosureEvidenceFactory.managedRevisionCause(
                        pending.occurrenceIdentity(), historicalA.epoch() - 1L, historicalA.epoch(),
                        historicalA.document(), receipt(historical, A), null);
                Run applied = fixture.process(snapshot, cause);
                assertSuccess(applied);
                assertEquals(epoch, result(applied, A).epoch());
                assertTrue(receipt(applied, A).emittedRootEvents().isEmpty());
                ManagedRepresentationTransition position = new ManagedRepresentationTransition(A, epoch,
                        anchor.transitionReceiptIdentity(), predecessor, applied.input, applied.result,
                        receipt(applied, A).transitionReceiptIdentity());
                positions.add(position);
                predecessor = position.positionIdentity();
                snapshot = fixture.after(applied.result);
            }
            assertNotEquals(x, positions.get(0).transitionReceipt().afterBlueId());
            assertEquals(x, positions.get(1).transitionReceipt().afterBlueId(),
                    "Two actual processor commits must establish X -> Y -> X at one source epoch");
            assertNotEquals(positions.get(0).positionIdentity(), positions.get(1).positionIdentity());
            assertEquals(positions.get(0).positionIdentity(), positions.get(1).predecessorPositionIdentity());
            Run following = null;
            if (fixtureInput.path("followingRevision").booleanValue()) {
                following = fixture.external(snapshot, A,
                        event("after-representation-return").properties("business", new Node().value(3)), 6L, null);
                assertSuccess(following);
                assertEquals(x, receipt(following, A).beforeBlueId());
                assertEquals(epoch + 1L, result(following, A).epoch());
                snapshot = fixture.after(following.result);
            }
            String authoritative = snapshot.managedDocument(A).blueId();
            long authoritativeEpoch = snapshot.managedDocument(A).epoch();
            String nextReceipt = following == null ? null : receipt(following, A).transitionReceiptIdentity();
            DocumentId consumerId = new DocumentId("historical-consumer");
            Node consumer = fixture.root("consumer", "noop", false)
                    .properties("peer", new Node().blueId(x))
                    .properties("observed", new Node().value(0));
            consumer.getContracts().properties("embedded", embedded("/peer"))
                    .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                            .properties("path", new Node().value("/peer")))
                    .properties("observe", handler("updates"));
            snapshot = fixture.withPendingConsumer(snapshot, consumerId, consumer, A, epoch, x);
            ManagedOccurrenceBinding occurrence = snapshot.occurrences().stream()
                    .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
            String targetPosition = positions.get(1).positionIdentity();
            final AffectedClosureSnapshot beforeTraversal = snapshot;
            ManagedRepresentationCause skipped = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                    positions.get(1), targetPosition, nextReceipt, null);
            assertThrows(IllegalArgumentException.class, () -> fixture.process(beforeTraversal, skipped));
            int callsBefore = fixture.probe.calls.size();
            List<Long> traversalGas = new ArrayList<>();
            for (int index = 0; index < positions.size(); index++) {
                ManagedRepresentationCause cause = new ManagedRepresentationCause(occurrence.occurrenceIdentity(),
                        positions.get(index), targetPosition, nextReceipt, null);
                ClosureInvocationInput rollbackInput = ClosureEvidenceFactory.processClosure(snapshot, cause,
                        Collections.emptyList(), ClosureEvidenceFactory.executionPolicy(1L, Collections.emptyMap(),
                                "representation-return-rollback"), fixture.environment);
                try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner)) {
                    ClosureAttemptResult rollback = contracts.processClosure(rollbackInput);
                    assertTrue(rollback.isComplete());
                    assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, rollback.processResult().status());
                    assertEquals(snapshot.closureIdentity(), rollback.processResult().outputClosureIdentity());
                }
                Run traversed = fixture.process(snapshot, cause);
                assertSuccess(traversed);
                assertTrue(traversed.result.totalGas() > 1L);
                traversalGas.add(traversed.result.totalGas());
                assertTrue(traversed.result.publicEvents().isEmpty());
                assertEquals(authoritative, result(traversed, A).afterBlueId());
                assertEquals(authoritativeEpoch, result(traversed, A).epoch());
                assertEquals(java.math.BigInteger.valueOf(index + 1L),
                        result(traversed, consumerId).document().get("/observed"));
                snapshot = fixture.after(traversed.result);
                occurrence = snapshot.occurrences().stream()
                        .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get();
                assertEquals(following == null && index == positions.size() - 1, occurrence.active());
                final AffectedClosureSnapshot afterTraversal = snapshot;
                assertThrows(IllegalArgumentException.class, () -> fixture.process(afterTraversal, cause));
            }
            if (following != null) {
                ResultingDocument next = result(following, A);
                ManagedRevisionCause revision = ClosureEvidenceFactory.managedRevisionCause(
                        occurrence.occurrenceIdentity(), epoch, epoch + 1L, next.document(), receipt(following, A), null);
                Run advanced = fixture.process(snapshot, revision);
                assertSuccess(advanced);
                assertEquals(authoritative, result(advanced, A).afterBlueId());
                assertEquals(authoritativeEpoch, result(advanced, A).epoch());
                assertEquals(java.math.BigInteger.valueOf(3), result(advanced, consumerId).document().get("/observed"));
                assertTrue(advanced.result.occurrenceBindings().stream()
                        .filter(row -> row.sourceDocumentId().equals(consumerId)).findFirst().get().active());
            }
            List<String> expectedCalls = new ArrayList<>(Arrays.asList("consumer:observe", "consumer:observe"));
            if (following != null) expectedCalls.add("consumer:observe");
            assertEquals(expectedCalls, fixture.probe.calls.subList(callsBefore, fixture.probe.calls.size()));
            ObjectNode evidence = UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
            evidence.put("id", fixtureInput.path("id").textValue());
            evidence.put("status", "PASS");
            evidence.put("sourceEpoch", epoch);
            evidence.put("initialBlueId", x);
            evidence.put("intermediateBlueId", positions.get(0).transitionReceipt().afterBlueId());
            evidence.set("positionIdentities", UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                    positions.stream().map(ManagedRepresentationTransition::positionIdentity).collect(Collectors.toList())));
            evidence.set("traversalGas", UncheckedObjectMapper.JSON_MAPPER.valueToTree(traversalGas));
            evidence.put("consumerUpdates", following == null ? 2 : 3);
            evidence.put("representationSteps", 2);
            evidence.put("followingRevisionApplied", following != null);
            evidence.put("sameEpoch", true);
            evidence.put("returnsToExactInitialIdentity", true);
            evidence.put("oneUnitGasFailure", true);
            evidence.put("terminalActivationOnlyAtCapturedPosition", true);
            evidence.put("sourceEvents", 0);
            evidence.put("sourceHeadPreserved", true);
            evidence.put("skippedPositionRejected", true);
            evidence.put("reappliedPositionsRejected", true);
            evidence.put("gasRollback", true);
            return evidence;
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

    

        private Run external(AffectedClosureSnapshot snapshot, DocumentId target,
                Node event, long order, Long historicalEpoch) {
            exact.put(blueId(event), event.clone());
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, blueId(event),
                    ExternalOrderKey.of(Arrays.<Object>asList(order, "checkpoint-ownership")),
                    environment.externalOrderPolicyIdentity());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, cause,
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(target),
                            "ownerChannel", "ownerChannel", 0L)),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "representation-return-v1"), environment);
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
                if (historicalEpoch != null) {
                    assertFalse(attempt.isComplete(), "Saved history must require explicit managed occurrence evidence");
                    assertEquals(1, attempt.resourceDemands().size());
                    ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) attempt.resourceDemands().get(0);
                    List<ManagedOccurrenceBinding> rows = new ArrayList<>(snapshot.occurrences());
                    rows.add(ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(),
                            demand.sourceDocumentId(), ScopeAddress.embedded(demand.sourcePath(), 1L),
                            A, demand.suppliedValueBlueId(), false, historicalEpoch));
                    AffectedClosureSnapshot expanded = ClosureEvidenceFactory.affectedClosure(
                            snapshot.graphGeneration(), snapshot.managedDocuments(), rows,
                            snapshot.components(), snapshot.publicRootDocumentIds());
                    input = ClosureEvidenceFactory.processClosure(expanded, cause, input.directDeliveries(),
                            input.executionPolicy(), environment);
                    attempt = contracts.processClosure(input);
                }
            }
            assertTrue(attempt.isComplete(), "Exact fixture resources must be retained");
            return new Run(input, attempt.processResult(), capture.evidence);
        }

        private Run process(AffectedClosureSnapshot snapshot, ProcessingCause cause) {
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, cause, Collections.emptyList(),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "representation-return-v1"), environment);
            Capture capture = new Capture();
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                ClosureAttemptResult attempt = contracts.processClosure(input);
                assertTrue(attempt.isComplete());
                return new Run(input, attempt.processResult(), capture.evidence);
            }
        }

        private AffectedClosureSnapshot after(ClosureProcessResult result) {
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            for (ResultingDocument value : result.resultingDocuments()) {
                exact.put(value.afterBlueId(), value.document());
                documents.add(new ManagedDocumentSnapshot(value.documentId(), value.afterBlueId(), value.document(),
                        value.initialized(), value.terminated(), value.publicRoot(), value.epoch(), value.componentGeneration()));
            }
            return ClosureEvidenceFactory.affectedClosure(result.graphGeneration(), documents,
                    result.occurrenceBindings(), result.resultingComponents(), Collections.singletonList(A));
        }

        private AffectedClosureSnapshot withPendingConsumer(AffectedClosureSnapshot source,
                DocumentId consumerId, Node consumer, DocumentId target, long epoch, String historicalBlueId) {
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            Map<DocumentId, Long> generations = new LinkedHashMap<>();
            source.managedDocuments().forEach(value -> {
                bodies.put(value.documentId(), value.document());
                generations.put(value.documentId(), value.componentGeneration());
            });
            bodies.put(consumerId, consumer);
            generations.put(consumerId, 1L);
            List<ManagedOccurrenceBinding> rows = new ArrayList<>(source.occurrences());
            rows.add(ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), consumerId,
                    ScopeAddress.embedded("/peer", 1L), target, historicalBlueId, false, epoch));
            ComponentFinalizationResult finalized = new ComponentFinalizationKernel().finalizeComponents(
                    new ComponentFinalizationInput(ManagedDocumentGraph.fromBindings(bodies.keySet(), rows),
                            generations, bodies, rows));
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            finalized.documents().forEach((id, value) -> documents.add(new ManagedDocumentSnapshot(id,
                    value.blueId(), value.document(), true, false, A.equals(id),
                    id.equals(consumerId) ? 0L : source.managedDocument(id).epoch(), value.componentGeneration())));
            return ClosureEvidenceFactory.affectedClosure(source.graphGeneration(), documents,
                    finalized.finalizedGraph().bindings(), finalized.components().stream()
                            .map(FinalizedComponentEvidence::component).collect(Collectors.toList()), Collections.singletonList(A));
        }

        @Override
        public void close() {
            owner.close();
        }
    }
    /** Fixture channel that exposes retained checkpoint and optional catalog dependencies. */
    public static final class Source extends ChannelContract {
        private Boolean catalog;
        /** Creates a fixture channel with no declared catalog dependency. */
        public Source() { }
        /**
         * Returns the fixture's same-scope channel catalog dependency setting.
         *
         * @return whether this fixture depends on its same-scope channel catalog
         */
        public Boolean getCatalog() { return catalog; }
        /**
         * Sets the fixture's same-scope channel catalog dependency setting.
         *
         * @param value whether the fixture should depend on its channel catalog
         */
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
    /** Fixture handler whose processor records real downstream execution. */
    public static final class Probe extends HandlerContract {
        /** Creates a fixture handler for recording downstream execution. */
        public Probe() { }
    }
    private static final class ProbeProcessor implements HandlerProcessor<Probe> {
        private final List<String> calls = new ArrayList<>();
        @Override public Class<Probe> contractType() { return Probe.class; }
        @Override public void execute(Probe contract, ProcessorExecutionContext context) {
            String key = context.contractKey();
            calls.add(context.documentAt("/label").getValue() + ":" + key);
            if ("assign".equals(key)) {
                context.applyPatch(JsonPatch.replace("/business", NodePathEditor.getOrNull(context.event(), "/business")));
            } else if ("attach".equals(key)) {
                context.applyPatch(JsonPatch.add("/peer", NodePathEditor.getOrNull(context.event(), "/target")));
            } else if ("observe".equals(key)) {
                java.math.BigInteger count = (java.math.BigInteger) context.documentAt("/observed").getValue();
                context.applyPatch(JsonPatch.replace("/observed", new Node().value(count.add(java.math.BigInteger.ONE))));
            } else if (!"noop".equals(key)) {
                throw new AssertionError("Unspecified fixture action: " + key);
            }
        }
    }
    private static ManagedDocumentTransitionReceipt receipt(Run run, DocumentId id) {
        return run.result.managedTransitionReceipts().stream()
                .filter(value -> value.documentId().equals(id)).findFirst().get();
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

    private static void assertSuccess(Run run) {
            assertEquals(ProcessorStatus.SUCCESS, run.result.status(),
                run.result.diagnostic() == null ? "No diagnostic" : run.result.diagnostic().message());
        assertTrue(run.result.commits());
    }
    private static void verifyPackage() throws Exception {
        ObjectNode manifest = (ObjectNode) UncheckedObjectMapper.JSON_MAPPER.readTree(
                resource("/blue-contracts-representation-1.0/manifest.json"));
        assertEquals("blue-contracts-representation-conformance-package/1", manifest.path(BlueLanguageConstants.OBJECT_SCHEMA).textValue());
        assertEquals("PROPOSED_NOT_RELEASED", manifest.path("status").textValue());
        assertEquals(PACKAGE_IDENTITY, manifest.path("packageIdentity").textValue());
        ObjectNode identityValue = manifest.deepCopy();
        identityValue.putNull("packageIdentity");
        assertEquals(PACKAGE_IDENTITY, "sha256:" + sha256(new JsonCanonicalizer(identityValue.toString()).getEncodedUTF8()));
        assertEquals(2, manifest.path("executableFixtureCount").intValue());
        assertEquals(2, manifest.path("files").size());
        List<String> expectedPaths = Arrays.asList("fixtures/repeated-identity-next-revision.json", "fixtures/repeated-identity.json");
        for (int i = 0; i < expectedPaths.size(); i++) {
            JsonNode entry = manifest.path("files").get(i);
            assertEquals(expectedPaths.get(i), entry.path("path").textValue());
            byte[] content = resource("/blue-contracts-representation-1.0/" + expectedPaths.get(i));
            assertEquals((long) content.length, entry.path("bytes").longValue());
            assertEquals(entry.path("sha256").textValue(), sha256(content));
        }
        assertEquals("/specifications/blue-contracts-and-processor-specification-1.0.md",
                manifest.path("specificationResource").textValue());
        assertEquals(manifest.path("specificationSha256").textValue(), sha256(resource(
                manifest.path("specificationResource").textValue())));
        JsonNode release = new ObjectMapper(new YAMLFactory()).readTree(resource(
                "/" + BlueContractsConformanceReport.RELEASE_MANIFEST_RESOURCE));
        assertEquals(BlueContractsConformanceReport.RELEASE_PACKAGE_IDENTITY,
                BlueContractsConformanceReport.computeReleasePackageIdentity());
        JsonNode components = release.path("components");
        assertEquals(PACKAGE_IDENTITY,
                components.path("contractsRepresentationFixturePackageIdentity").textValue());
        assertEquals(2, components.path("contractsRepresentationFixtureCount").intValue());
        assertEquals(components.path("contractsTotalExecutableFixtureCount").intValue() + 2,
                components.path("contractsAggregateExecutableFixtureCount").intValue());
    }
    private static byte[] resource(String path) throws Exception {
        try (InputStream stream = HistoricalRepresentationConformanceCli.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing exact conformance resource " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = stream.read(buffer)) != -1;) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static void assertTrue(boolean value, String... message) {
        if (!value) throw new AssertionError(message.length == 0 ? "Expected true" : message[0]);
    }
    private static void assertFalse(boolean value, String... message) { assertTrue(!value, message); }
    private static void assertEquals(Object expected, Object actual, String... message) {
        assertTrue(Objects.equals(expected, actual), "Expected " + expected + ", got " + actual
                + (message.length == 0 ? "" : ": " + message[0]));
    }
    private static void assertNotEquals(Object expected, Object actual, String... message) {
        assertTrue(!Objects.equals(expected, actual), message);
    }
    private static void assertThrows(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Unexpected failure", failure);
        }
        throw new AssertionError("Expected rejection: " + type.getName());
    }
}
