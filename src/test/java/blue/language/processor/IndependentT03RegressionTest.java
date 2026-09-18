package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.wire.ParsedJsonPointer;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.BluePatchOperation;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.processor.DocumentProcessingResultTestSupport.diagnosticCategory;
import static blue.language.processor.DocumentProcessingResultTestSupport.diagnosticMessage;
import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.*;

class IndependentT03RegressionTest {

    @ParameterizedTest
    @EnumSource(Lane.class)
    void shouldRejectReplaceAtListSizeWithoutMutation(Lane lane) {
        // given
        try (Blue blue = new Blue()) {
            Node document = new Node().properties("arr", new Node().items(Arrays.asList(
                    new Node().value(10), new Node().value(20))));
            DocumentProcessingRuntime runtime = lane.runtime(blue, document);
            String before = blue.nodeToJson(runtime.document());

            // when
            Throwable failure = captureFailure(() -> runtime.applyPatch(
                    "/", JsonPatch.replace("/arr/2", new Node().value(99))));

            // then
            assertNotNull(failure, "REPLACE at list size must reject in " + lane);
            assertEquals(IllegalStateException.class, failure.getClass());
            assertEquals(before, blue.nodeToJson(runtime.document()));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidListTargets")
    void shouldRejectInvalidListTargetsInEveryLane(Lane lane, int size, String index,
                                                  JsonPatch.Op op) {
        // given
        try (Blue blue = new Blue()) {
            DocumentProcessingRuntime runtime = lane.runtime(blue, listDocument(size));
            String before = blue.nodeToJson(runtime.document());
            ResolvedSnapshot snapshot = runtime.snapshot();
            JsonPatch patch = patch(op, "/arr/" + index);

            // when
            Throwable failure = captureFailure(() -> runtime.applyPatch("/", patch));

            // then
            assertNotNull(failure, lane + " " + op + " " + patch.getPath());
            assertEquals(IllegalStateException.class, failure.getClass());
            assertEquals(before, blue.nodeToJson(runtime.document()));
            assertSame(snapshot, runtime.snapshot());
            assertTrue(runtime.changedPaths().isEmpty());
        }
    }

    private static Stream<Arguments> invalidListTargets() {
        List<Arguments> cases = new ArrayList<>();
        for (Lane lane : Lane.values()) {
            for (int size = 0; size <= 2; size++) {
                for (String index : Arrays.asList(String.valueOf(size),
                        String.valueOf(size + 1), "-")) {
                    cases.add(Arguments.of(lane, size, index, JsonPatch.Op.REPLACE));
                    cases.add(Arguments.of(lane, size, index, JsonPatch.Op.REMOVE));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @EnumSource(Lane.class)
    void shouldPreserveLegalListWritesAndObjectUpsert(Lane lane) {
        // given
        try (Blue blue = new Blue()) {
            for (int size = 0; size <= 2; size++) {
                for (String index : Arrays.asList(String.valueOf(size), "-")) {
                    DocumentProcessingRuntime runtime = lane.runtime(blue, listDocument(size));

                    // when
                    DocumentUpdateData update = runtime.applyPatch("/", patch(JsonPatch.Op.ADD, "/arr/" + index));

                    // then
                    assertEquals(size + 1, runtime.document().getAsNode("/arr").getItems().size());
                    assertEquals(99, runtime.document().getAsInteger("/arr/" + size));
                    assertEquals(JsonPatch.Op.ADD, update.op());
                    assertNull(update.before());
                }
                for (int index = 0; index < size; index++) {
                    DocumentProcessingRuntime runtime = lane.runtime(blue, listDocument(size));
                    DocumentUpdateData update = runtime.applyPatch("/", patch(JsonPatch.Op.REPLACE, "/arr/" + index));
                    assertEquals(size, runtime.document().getAsNode("/arr").getItems().size());
                    assertEquals(99, runtime.document().getAsInteger("/arr/" + index));
                    assertEquals(JsonPatch.Op.REPLACE, update.op());
                    assertEquals((index + 1) * 10, ((Number) update.before().getValue()).intValue());
                }
            }
            DocumentProcessingRuntime runtime = lane.runtime(blue,
                    new Node().properties("obj", Nodes.emptyObject()));
            DocumentUpdateData update = runtime.applyPatch("/", patch(JsonPatch.Op.REPLACE, "/obj/new"));
            assertEquals(99, runtime.document().getAsInteger("/obj/new"));
            assertEquals(JsonPatch.Op.ADD, update.op());
            assertNull(update.before());
        }
    }

    @ParameterizedTest
    @EnumSource(Lane.class)
    void shouldObserveEarlierListSizeChangesAndRollBackRejectedBatch(Lane lane) {
        // given
        try (Blue blue = new Blue()) {
            DocumentProcessingRuntime runtime = lane.runtime(blue, listDocument(2));
            String before = blue.nodeToJson(runtime.document());
            ResolvedSnapshot snapshot = runtime.snapshot();

            // when
            Throwable failure = captureFailure(() -> runtime.applyPatches("/", Arrays.asList(
                    JsonPatch.remove("/arr/1"), patch(JsonPatch.Op.REPLACE, "/arr/1"))));

            // then
            assertNotNull(failure);
            assertEquals(IllegalStateException.class, failure.getClass());
            assertEquals(before, blue.nodeToJson(runtime.document()));
            assertSame(snapshot, runtime.snapshot());
            assertTrue(runtime.changedPaths().isEmpty());

            List<DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                    JsonPatch.add("/arr/2", new Node().value(30)),
                    patch(JsonPatch.Op.REPLACE, "/arr/2")));
            assertEquals(3, runtime.document().getAsNode("/arr").getItems().size());
            assertEquals(99, runtime.document().getAsInteger("/arr/2"));
            assertEquals(Arrays.asList(JsonPatch.Op.ADD, JsonPatch.Op.REPLACE),
                    updates.stream().map(DocumentUpdateData::op).collect(Collectors.toList()));
            assertEquals(30, ((Number) updates.get(1).before().getValue()).intValue());
        }
    }

    @Test
    void shouldRejectAtEveryExactPlanningEntryPoint() {
        // given
        FrozenNode root = FrozenNode.fromNode(listDocument(2));
        ImmutablePatchPlanner planner = ImmutablePatchPlanner.forFrozen(root);
        JsonPatch patch = patch(JsonPatch.Op.REPLACE, "/arr/2");
        ImmutableJsonPatch immutable = ImmutableJsonPatch.from(patch, root, root);

        // when
        List<Throwable> failures = Arrays.asList(
                captureFailure(() -> new CanonicalOverlayPatchEngine(root)
                        .apply(BluePatchOperation.REPLACE, ParsedJsonPointer.parse("/arr/2"),
                                FrozenNode.fromNode(new Node().value(99)))),
                captureFailure(() -> planner.planWithExactReplacement("/", patch)),
                captureFailure(() -> planner.planWithExactReplacement("/", immutable)),
                captureFailure(() -> planner.applyMutationPreflight(
                        JsonPatch.Op.REPLACE, immutable.path(), immutable.valueFor(root), true)));

        // then
        for (Throwable failure : failures) {
            assertNotNull(failure);
            assertEquals(IllegalStateException.class, failure.getClass());
        }
    }

    @ParameterizedTest
    @MethodSource("processorCases")
    void shouldPreserveProcessorAtomicityAndReplay(int size, JsonPatch.Op op, String path,
                                                  boolean accepted, boolean inlineHandlerType) {
        // given
        try (Blue blue = ProcessorTestSupport.blue()) {
            blue.registerContractProcessor(DocumentProcessorExactFeederSupport.testEventChannelProcessor());
            blue.registerContractProcessor(new BoundaryHandler(patch(op, path)));
            DocumentProcessorExactFeederSupport.install(blue);
            Node document = listDocument(size).contracts(new Node().properties(
                    "events", channel(),
                    "watch", new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL))
                            .properties("path", new Node().value("/arr")),
                    "prior", handler("events", "prior", 0, inlineHandlerType),
                    "boundary", handler("events", "boundary", 1, inlineHandlerType))
                    .properties("update", handler("watch", "update", 0, inlineHandlerType)));
            DocumentProcessingResult initialized = blue.initializeDocument(document);
            ResolvedSnapshot input = blue.loadSnapshot(initialized.document());
            String before = blue.nodeToJson(input.canonicalRoot());
            Node event = new TestEvent().eventId("t03-boundary").toNode();
            DocumentProcessor processor = blue.getDocumentProcessor();

            // when
            ProcessingDebugResult snapshotRun = processor.processDocumentWithTrace(input, event);
            ProcessingDebugResult nodeRun = processor.processDocumentWithTrace(input.canonicalRoot(), event);
            DocumentProcessingResult result = snapshotRun.processResult();

            // then
            assertEquals(ProcessorStatus.SUCCESS, initialized.status(), diagnosticMessage(initialized));
            assertNotNull(processor.snapshotManager());
            assertNotNull(processor.conformanceEngine());
            System.out.println("T03 processor " + size + " " + op + " " + path
                    + " inline=" + inlineHandlerType + " status=" + result.status()
                    + " category=" + diagnosticCategory(result) + " input=" + input.blueId()
                    + " output=" + DirectBlueIdCalculator.calculateBlueId(result.document())
                    + " gas=" + result.totalGas() + " events=" + result.events().size());
            assertEquals(accepted ? ProcessorStatus.SUCCESS : ProcessorStatus.RUNTIME_FATAL,
                    result.status(), diagnosticMessage(result));
            assertEquals(result.status(), nodeRun.processResult().status());
            assertEquals(diagnosticCategory(result), diagnosticCategory(nodeRun.processResult()));
            assertEquals(diagnosticMessage(result), diagnosticMessage(nodeRun.processResult()));
            assertEquals(result.totalGas(), nodeRun.processResult().totalGas());
            assertEquals(gasProjection(snapshotRun), gasProjection(nodeRun));
            assertEquals(blue.nodeToJson(result.document()), blue.nodeToJson(nodeRun.processResult().document()));
            assertEquals(result.events().stream().map(blue::nodeToJson).collect(Collectors.toList()),
                    nodeRun.processResult().events().stream().map(blue::nodeToJson).collect(Collectors.toList()));
            assertEquals(before, blue.nodeToJson(input.canonicalRoot()));
            assertTrue(result.totalGas() > 0);
            if (!accepted) {
                assertEquals(path.startsWith("/missing/") ? ProcessorErrorCategory.InvalidPatch
                        : ProcessorErrorCategory.RuntimeExecutionFailure, diagnosticCategory(result));
                assertEquals(before, blue.nodeToJson(result.document()));
                assertSame(input, snapshotRun.resultingSnapshot());
                assertTrue(result.events().isEmpty());
                assertTrue(snapshotRun.trace().records(ProcessingTraceRecord.Kind.CHECKPOINT_WRITE).isEmpty());
                assertTrue(snapshotRun.trace().records(ProcessingTraceRecord.Kind.DOCUMENT_UPDATE).stream()
                        .noneMatch(record -> path.equals(record.logicalPath())));
                PlatformCommitCompanion companion = snapshotRun.platformCommitCompanion();
                if (companion != null) {
                    assertFalse(companion.commitsRootAndOutbox());
                    assertEquals(companion.expectedRootRevision(), companion.resultingRootRevision());
                    assertTrue(companion.subscriptionDelta().isEmpty());
                }
                ProcessingDebugResult retry = processor.processDocumentWithTrace(input, event);
                assertEquals(result.status(), retry.processResult().status());
                assertEquals(before, blue.nodeToJson(retry.processResult().document()));
                assertEquals(gasProjection(snapshotRun), gasProjection(retry));
                assertTrue(retry.processResult().events().isEmpty());
            } else {
                int expectedSize = op == JsonPatch.Op.ADD ? size + 1 : size;
                assertEquals(expectedSize, result.document().getAsNode("/arr").getItems().size());
                assertEquals(99, result.document().getAsInteger("/arr/" + (expectedSize - 1)));
                assertEquals(Arrays.asList("prior", "update", "boundary"), result.events().stream()
                        .map(Node::getValue).collect(Collectors.toList()));
                assertFalse(snapshotRun.trace().records(ProcessingTraceRecord.Kind.CHECKPOINT_WRITE).isEmpty());
                assertNotNull(snapshotRun.platformCommitCompanion());
                assertTrue(snapshotRun.platformCommitCompanion().commitsRootAndOutbox());
                assertFalse(snapshotRun.platformCommitCompanion().subscriptionDelta().isEmpty());
                ProcessingDebugResult replay = processor.processDocumentWithTrace(snapshotRun.resultingSnapshot(), event);
                assertEquals(ProcessorStatus.STALE, replay.processResult().status());
                assertTrue(replay.processResult().events().isEmpty());
                assertEquals(blue.nodeToJson(result.document()), blue.nodeToJson(replay.processResult().document()));
            }
        }
    }

    private static Stream<Arguments> processorCases() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean inline : new boolean[]{false, true}) {
            cases.add(Arguments.of(2, JsonPatch.Op.REPLACE, "/arr/2", false, inline));
            cases.add(Arguments.of(0, JsonPatch.Op.REPLACE, "/arr/0", false, inline));
            cases.add(Arguments.of(2, JsonPatch.Op.REPLACE, "/missing/0", false, inline));
            cases.add(Arguments.of(2, JsonPatch.Op.REMOVE, "/arr/2", false, inline));
            cases.add(Arguments.of(2, JsonPatch.Op.ADD, "/arr/2", true, inline));
            cases.add(Arguments.of(2, JsonPatch.Op.ADD, "/arr/-", true, inline));
            cases.add(Arguments.of(2, JsonPatch.Op.REPLACE, "/arr/1", true, inline));
        }
        return cases.stream();
    }

    private static List<String> gasProjection(ProcessingDebugResult run) {
        return run.trace().gas().stream().map(entry -> entry.namespace() + ":" + entry.counter()
                + ":" + entry.quantity() + ":" + entry.subtotal() + ":" + entry.logicalPath())
                .collect(Collectors.toList());
    }

    private static Node channel() {
        return new Node().type(new Node().blueId(ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL));
    }

    private static Node handler(String channel, String action, int order, boolean inlineType) {
        return new Node().type(inlineType ? new Node().name("SetProperty")
                : new Node().blueId(ProcessorTestTypeBlueIds.SET_PROPERTY)).properties(
                "channel", new Node().value(channel), "order", new Node().value(order),
                "propertyKey", new Node().value(action));
    }

    private static Node listDocument(int size) {
        List<Node> items = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            items.add(new Node().value((index + 1) * 10));
        }
        return new Node().properties("arr", new Node().items(items));
    }

    private static JsonPatch patch(JsonPatch.Op op, String path) {
        if (op == JsonPatch.Op.REMOVE) {
            return JsonPatch.remove(path);
        }
        return op == JsonPatch.Op.ADD ? JsonPatch.add(path, new Node().value(99))
                : JsonPatch.replace(path, new Node().value(99));
    }

    private static final class BoundaryHandler implements HandlerProcessor<SetProperty> {
        private final JsonPatch boundary;

        private BoundaryHandler(JsonPatch boundary) {
            this.boundary = boundary;
        }

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            String action = contract.getPropertyKey();
            if ("prior".equals(action)) {
                context.applyPatches(Arrays.asList(
                        JsonPatch.add("/tentative", new Node().value(true)),
                        JsonPatch.add("/contracts/extra", channel().properties("eventType",
                                new Node().value(ProcessorTestTypeBlueIds.SET_PROPERTY)))));
            } else if ("boundary".equals(action)) {
                context.applyPatch(boundary);
            }
            context.emitEvent(new Node().value(action));
        }
    }

    private enum Lane {
        NODE_BACKED,
        MANAGERLESS_SNAPSHOT,
        AUTHORITATIVE_SNAPSHOT;

        private DocumentProcessingRuntime runtime(Blue blue, Node document) {
            if (this == NODE_BACKED) {
                return new DocumentProcessingRuntime(document);
            }
            ResolvedSnapshot snapshot = blue.resolveToSnapshot(document);
            if (this == MANAGERLESS_SNAPSHOT) {
                return new DocumentProcessingRuntime(snapshot, null, null);
            }
            DocumentProcessor processor = blue.getDocumentProcessor();
            return new DocumentProcessingRuntime(snapshot,
                    processor.conformanceEngine(), processor.snapshotManager());
        }
    }
}
