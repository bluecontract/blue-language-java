package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentStepRuntimeGapException;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ManagedDocumentStepRoute;
import blue.language.processor.ManagedDocumentStepRuntime;
import blue.language.processor.ManagedDocumentWorkKind;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused characterization of the isolated managed-document runtime seam. */
final class ManagedDocumentStepProcessorTest {

    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final Node PROBE_TYPE =
            new Node().name("Managed Document Step Probe Handler");
    private static final String PROBE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(PROBE_TYPE);
    private static final Node EXTERNAL_TYPE =
            new Node().name("Managed Document Step External Channel");
    private static final String EXTERNAL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(EXTERNAL_TYPE);

    @Test
    void executesContainingAndContainedDocumentsAsIndependentRootsWithSharedGas() {
        AffectedClosureSnapshot state = acyclicContainingState();
        try (Fixture fixture = new Fixture(Action.OBSERVE)) {
            DocumentStepInput inputA = input(
                    state, A, WorkKind.TRIGGERED_EVENT, "trigger", "A");
            DocumentStepInput inputB = input(
                    state, B, WorkKind.TRIGGERED_EVENT, "trigger", "B");

            LocalDocumentStepResult resultA = fixture.stepper.process(inputA);
            LocalDocumentStepResult resultB = fixture.stepper.process(inputB);

            assertEquals(resultA.gasAfter(), resultB.gasBefore());
            assertTrue(resultA.gasAfter() > resultA.gasBefore());
            assertTrue(resultB.gasAfter() > resultB.gasBefore());
            assertEquals(Arrays.asList("A", "B"), fixture.probe.labels());
            assertEquals(Arrays.asList("/", "/"), fixture.probe.scopes());
            assertEquals(Arrays.asList(true, false),
                    fixture.probe.declaredChildVisible());
            assertEquals(Arrays.asList(false, false),
                    fixture.probe.ambientParentVisible());
            assertEquals(Arrays.asList(false, false),
                    fixture.probe.processEventVisible());
            assertNotSame(
                    fixture.probe.contexts.get(0),
                    fixture.probe.contexts.get(1));

            assertEquals("A", resultA.resultingBody().getAsText("/label"));
            assertEquals("B", resultB.resultingBody().getAsText("/label"));
            assertTrue(resultA.resultingBody().getAsNode("/managedChild")
                    != null);
            assertNull(resultB.resultingBody().getProperties().get(
                    "ambientParent"));
            assertEquals(Collections.emptyList(),
                    inputB.ambientContainingDocumentIds());
            DocumentStepEvidence evidence = new DocumentStepEvidence(inputB);
            assertEquals(B, evidence.executionRootDocumentId());
            assertEquals("ISOLATED_DOCUMENT", evidence.executionMode());
            assertEquals(Collections.emptyList(),
                    evidence.ambientContainingDocumentIds());
        }
    }

    @Test
    void usesTheSameAdapterFunctionAndContextShapeForCyclicAndAcyclicMembers() {
        AffectedClosureSnapshot acyclic = acyclicContainingState();
        AffectedClosureSnapshot cyclic = cyclicState();
        try (Fixture fixture = new Fixture(Action.OBSERVE)) {
            DocumentStepInput acyclicInput = input(
                    acyclic, A, WorkKind.TRIGGERED_EVENT, "trigger", "acyclic");
            DocumentStepInput cyclicInput = input(
                    cyclic, A, WorkKind.TRIGGERED_EVENT, "trigger", "cyclic");

            fixture.stepper.process(acyclicInput);
            fixture.stepper.process(cyclicInput);

            assertEquals(2, fixture.probe.contexts.size());
            assertEquals(Arrays.asList("/", "/"), fixture.probe.scopes());
            assertNull(acyclicInput.resolutionContext().cyclicProofIdentity());
            assertEquals(hash('5'),
                    cyclicInput.resolutionContext().cyclicProofIdentity());
            assertEquals(
                    acyclicInput.getClass(),
                    cyclicInput.getClass());
        }
    }

    @Test
    void recordsExactPatchesInOrderAtSynchronousClosureBarriers() {
        AffectedClosureSnapshot state = acyclicContainingState();
        try (Fixture fixture = new Fixture(Action.PATCH)) {
            LocalDocumentStepResult result = fixture.stepper.process(input(
                    state, B, WorkKind.TRIGGERED_EVENT, "trigger", "patch"));

            assertEquals(Arrays.asList("/first", "/second"),
                    fixture.hook.patchPaths);
            assertEquals(Arrays.asList("/first", "/second"),
                    patchPaths(result.orderedPatches()));
            assertEquals(Arrays.asList(true, true),
                    fixture.hook.firstVisibleAtPatch);
            assertEquals(Arrays.asList(false, true),
                    fixture.hook.secondVisibleAtPatch);
            assertEquals(Arrays.asList(1, 1), fixture.hook.updateCounts);
            assertEquals(Arrays.asList("/", "/"),
                    fixture.hook.patchScopes);
            assertEquals("B-first", result.resultingBody()
                    .getAsText("/first"));
            assertEquals("B-second", result.resultingBody()
                    .getAsText("/second"));
            assertTrue(result.identityAffecting());
            assertThrows(UnsupportedOperationException.class,
                    () -> result.orderedPatches().clear());
            for (Method method : LocalDocumentStepResult.class.getMethods()) {
                assertFalse(method.getName().equals("afterBlueId"));
            }
        }
    }

    @Test
    void transfersApplicationEventsBeforeAnyLocalFifoOrPublicClassification() {
        AffectedClosureSnapshot state = acyclicContainingState();
        try (Fixture fixture = new Fixture(Action.EMIT)) {
            LocalDocumentStepResult result = fixture.stepper.process(input(
                    state, B, WorkKind.TRIGGERED_EVENT, "trigger", "emit"));

            assertEquals(1, fixture.hook.events.size());
            EventCapture captured = fixture.hook.events.get(0);
            assertEquals("/", captured.scopePath);
            assertEquals("probe", captured.originContractKey);
            assertEquals("B", captured.event.getAsText("/from"));
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(captured.event),
                    captured.eventBlueId);
            assertEquals(1, result.emittedEvents().size());
            assertEquals("B", result.emittedEvents().get(0)
                    .getAsText("/from"));
            assertEquals(Collections.emptyList(), result.orderedPatches());
            assertEquals(1, fixture.probe.contexts.size());
        }
    }

    @Test
    void failsClosedWithMachineReasonsWhenContinuationIsMissing() {
        assertDefaultContinuationGap(
                Action.PATCH,
                DocumentStepRuntimeGapException.Reason
                        .PATCH_CONTINUATION_HOOK_REQUIRED);
        assertDefaultContinuationGap(
                Action.EMIT,
                DocumentStepRuntimeGapException.Reason
                        .APPLICATION_EVENT_CONTINUATION_HOOK_REQUIRED);
        assertDefaultContinuationGap(
                Action.TERMINATE,
                DocumentStepRuntimeGapException.Reason
                        .TERMINATION_CONTINUATION_HOOK_REQUIRED);
    }

    @Test
    void executesEveryRemainingClosedWorkKindWithItsExactEvidenceShape() {
        AffectedClosureSnapshot state = acyclicContainingState();
        try (Fixture fixture = new Fixture(Action.OBSERVE)) {
            fixture.stepper.process(input(
                    state, B, WorkKind.EXTERNAL_DELIVERY,
                    "external", "external"));
            fixture.stepper.process(input(
                    state, B, WorkKind.DOCUMENT_UPDATE,
                    "update", "update"));
            Node occurrenceEvent = new Node().properties(
                    "kind", new Node().value("origin"));
            String occurrenceBlueId =
                    DirectBlueIdCalculator.calculateBlueId(occurrenceEvent);
            Node wrapper = embeddedWrapper(
                    "/child", occurrenceBlueId);
            fixture.stepper.process(input(
                    state,
                    B,
                    WorkKind.EMBEDDED_EVENT,
                    "embeddedEvent",
                    wrapper,
                    occurrenceEvent,
                    null));
            fixture.stepper.process(input(
                    state, B, WorkKind.LIFECYCLE,
                    "lifecycle", "lifecycle"));
            FrozenJsonPatch rewrite = FrozenJsonPatch.add(
                    "/managedReference",
                    FrozenNode.fromNode(
                            new Node().value("rewritten")));
            LocalDocumentStepResult containing = fixture.stepper.process(
                    input(
                            state,
                            B,
                            WorkKind.CONTAINING_REFERENCE_UPDATE,
                            "managed-revision",
                            new Node().properties(
                                    "kind",
                                    new Node().value("rewrite")),
                            null,
                            rewrite));

            assertEquals(4, fixture.probe.contexts.size());
            assertEquals(Arrays.asList(true, false, false, false),
                    fixture.probe.processEventVisible());
            assertEquals("/child",
                    fixture.probe.deliveredEvents.get(2)
                            .getAsText("/sourcePath"));
            assertEquals("origin",
                    fixture.probe.occurrenceEvents.get(2)
                            .getAsText("/kind"));
            assertEquals("rewritten", containing.resultingBody()
                    .getAsText("/managedReference"));
            assertEquals(Collections.singletonList(
                            "/managedReference"),
                    patchPaths(containing.orderedPatches()));
        }
    }

    @Test
    void classifiesCanonicalTriggeredEmbeddedAndUpdateRoutesWithoutCharging() {
        AffectedClosureSnapshot state = acyclicContainingState();
        try (Fixture fixture = new Fixture(Action.PATCH);
             ManagedDocumentStepRuntime classifier =
                     new ManagedDocumentStepRuntime(
                             fixture.owner, fixture.hook)) {
            Node event = new Node().properties(
                    "kind", new Node().value("route"));
            String eventBlueId =
                    DirectBlueIdCalculator.calculateBlueId(event);
            long before = classifier.totalGas();

            List<ManagedDocumentStepRoute> triggered =
                    classifier.classifyTriggeredEventRoutes(
                            state.managedDocument(B).document(), event);
            List<ManagedDocumentStepRoute> embedded =
                    classifier.classifyEmbeddedEventRoutes(
                            state.managedDocument(B).document(),
                            "/child",
                            event,
                            eventBlueId);
            LocalDocumentStepResult patched = fixture.stepper.process(input(
                    state, B, WorkKind.TRIGGERED_EVENT,
                    "trigger", "patch"));
            DocumentUpdateOccurrence update =
                    fixture.hook.updates.get(0);
            List<ManagedDocumentStepRoute> updates =
                    classifier.classifyDocumentUpdateRoutes(
                            patched.resultingBody(), update);

            assertEquals(Arrays.asList("firstTrigger", "trigger"),
                    routeKeys(triggered));
            assertEquals(Collections.singletonList("embeddedEvent"),
                    routeKeys(embedded));
            assertEquals(ManagedDocumentWorkKind.EMBEDDED_EVENT,
                    embedded.get(0).workKind());
            assertEquals("/child", embedded.get(0).exactPayload()
                    .getAsText("/sourcePath"));
            assertEquals("route", embedded.get(0).occurrenceEvent()
                    .getAsText("/kind"));
            assertEquals(Collections.singletonList("update"),
                    routeKeys(updates));
            assertEquals(ManagedDocumentWorkKind.DOCUMENT_UPDATE,
                    updates.get(0).workKind());
            assertEquals(before, classifier.totalGas());
        }
    }

    @Test
    void initializationStepOnlyPreflightsAndChargesItsSelectedRoot() {
        AffectedClosureSnapshot state = uninitializedState();
        try (Fixture fixture = new Fixture(Action.OBSERVE)) {
            LocalDocumentStepResult result = fixture.stepper.process(input(
                    state, A, WorkKind.INITIALIZATION, "", "initialize"));

            assertFalse(result.resultingBody().getContracts()
                    .getProperties().containsKey("initialized"));
            assertTrue(result.gasAfter() > result.gasBefore());
            assertEquals(Collections.emptyList(), result.orderedPatches());
            assertEquals(Collections.emptyList(), result.emittedEvents());
            assertFalse(result.identityAffecting());
            assertEquals(0, fixture.probe.contexts.size());
        }
    }

    private static void assertDefaultContinuationGap(
            Action action,
            DocumentStepRuntimeGapException.Reason reason) {
        AffectedClosureSnapshot state = acyclicContainingState();
        try (Fixture fixture = new Fixture(action)) {
            ManagedDocumentStepProcessor failClosed =
                    new ManagedDocumentStepProcessor(
                            fixture.owner);
            DocumentStepRuntimeGapException failure = assertThrows(
                    DocumentStepRuntimeGapException.class,
                    () -> failClosed.process(input(
                            state,
                            B,
                            WorkKind.TRIGGERED_EVENT,
                            "trigger",
                            action.name())));
            assertEquals(reason, failure.reason());
        }
    }

    private static List<String> patchPaths(List<FrozenJsonPatch> patches) {
        List<String> paths = new ArrayList<String>();
        for (FrozenJsonPatch patch : patches) {
            paths.add(patch.getPath());
        }
        return paths;
    }

    private static List<String> routeKeys(
            List<ManagedDocumentStepRoute> routes) {
        List<String> keys = new ArrayList<String>();
        for (ManagedDocumentStepRoute route : routes) {
            keys.add(route.channelKey());
        }
        return keys;
    }

    private static Node embeddedWrapper(
            String sourcePath,
            String eventBlueId) {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY))
                .properties(
                        "sourcePath", new Node().value(sourcePath))
                .properties(
                        "event", new Node().blueId(eventBlueId));
    }

    private static DocumentStepInput input(
            AffectedClosureSnapshot state,
            DocumentId target,
            WorkKind kind,
            String channelKey,
            String payloadKind) {
        Node payload = new Node().properties(
                "kind", new Node().value(payloadKind));
        ClosureInvocationInput invocation = invocation(state);
        ClosureWorkOccurrence work = new ClosureWorkOccurrence(
                0L,
                kind,
                target,
                channelKey,
                DirectBlueIdCalculator.calculateBlueId(payload),
                Long.valueOf(0L),
                hash('7'),
                hash('8'),
                hash('9'));
        return new DocumentStepInput(
                0L,
                work,
                state.managedDocument(target),
                payload,
                TentativeResolutionContext.from(
                        invocation, state, target));
    }

    private static DocumentStepInput input(
            AffectedClosureSnapshot state,
            DocumentId target,
            WorkKind kind,
            String channelKey,
            Node payload,
            Node occurrenceEvent,
            FrozenJsonPatch processorPatch) {
        ClosureInvocationInput invocation = invocation(state);
        ClosureWorkOccurrence work = new ClosureWorkOccurrence(
                0L,
                kind,
                target,
                channelKey,
                DirectBlueIdCalculator.calculateBlueId(
                        occurrenceEvent != null
                                ? occurrenceEvent
                                : payload),
                Long.valueOf(0L),
                hash('7'),
                hash('8'),
                hash('9'));
        return new DocumentStepInput(
                0L,
                work,
                state.managedDocument(target),
                payload,
                occurrenceEvent,
                processorPatch,
                TentativeResolutionContext.from(
                        invocation, state, target));
    }

    private static AffectedClosureSnapshot acyclicContainingState() {
        Node documentB = initializedDocument("B");
        Node preA = baseDocument("A")
                .properties("managedChild", documentB.clone());
        preA.getContracts().properties(
                "embedded",
                typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                        .properties(
                                "paths",
                                new Node().items(
                                        new Node().value(
                                                "/managedChild"))));
        Node documentA = markInitialized(preA);
        String blueA = DirectBlueIdCalculator.calculateBlueId(documentA);
        String blueB = DirectBlueIdCalculator.calculateBlueId(documentB);
        ManagedDocumentSnapshot managedA = new ManagedDocumentSnapshot(
                A, blueA, documentA, true, false, true, 0L, 1L);
        ManagedDocumentSnapshot managedB = new ManagedDocumentSnapshot(
                B, blueB, documentB, true, false, false, 0L, 1L);
        ManagedOccurrenceBinding occurrence = new ManagedOccurrenceBinding(
                hash('1'),
                hash('2'),
                hash('3'),
                A,
                ScopeAddress.embedded("/managedChild", 1L),
                B,
                blueB,
                true,
                null);
        ComponentSnapshot componentB = new ComponentSnapshot(
                hash('1'), hash('2'), 1L, ComponentKind.ACYCLIC,
                Collections.singletonList(B),
                Collections.singletonList(blueB),
                null, null, null);
        ComponentSnapshot componentA = new ComponentSnapshot(
                hash('3'), hash('4'), 1L, ComponentKind.ACYCLIC,
                Collections.singletonList(A),
                Collections.singletonList(blueA),
                null, null, null);
        return new AffectedClosureSnapshot(
                hash('0'),
                1L,
                Arrays.asList(managedA, managedB),
                Collections.singletonList(occurrence),
                hash('e'),
                Arrays.asList(componentB, componentA),
                Collections.singletonList(A));
    }

    private static AffectedClosureSnapshot cyclicState() {
        Node documentA = initializedDocument("A");
        Node documentB = initializedDocument("B");
        ManagedDocumentSnapshot managedA = new ManagedDocumentSnapshot(
                A, "master#0", documentA, true, false, true, 0L, 2L);
        ManagedDocumentSnapshot managedB = new ManagedDocumentSnapshot(
                B, "master#1", documentB, true, false, false, 0L, 2L);
        List<ManagedOccurrenceBinding> occurrences = Arrays.asList(
                new ManagedOccurrenceBinding(
                        hash('1'), hash('2'), hash('3'),
                        A, ScopeAddress.embedded("/b", 1L),
                        B, "master#1", true, null),
                new ManagedOccurrenceBinding(
                        hash('4'), hash('5'), hash('6'),
                        B, ScopeAddress.embedded("/a", 1L),
                        A, "master#0", true, null));
        CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                Arrays.asList(
                        new Node().name("a-proof"),
                        new Node().name("b-proof")));
        ComponentSnapshot component = new ComponentSnapshot(
                hash('3'), hash('4'), 2L, ComponentKind.CYCLIC,
                Arrays.asList(A, B),
                Arrays.asList("master#0", "master#1"),
                "master", proof, hash('5'));
        return new AffectedClosureSnapshot(
                hash('6'),
                2L,
                Arrays.asList(managedA, managedB),
                occurrences,
                hash('f'),
                Collections.singletonList(component),
                Collections.singletonList(A));
    }

    private static AffectedClosureSnapshot uninitializedState() {
        Node document = baseDocument("A");
        String blueId = DirectBlueIdCalculator.calculateBlueId(document);
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                A, blueId, document, false, false, true, 0L, 1L);
        ComponentSnapshot component = new ComponentSnapshot(
                hash('1'), hash('2'), 1L, ComponentKind.ACYCLIC,
                Collections.singletonList(A),
                Collections.singletonList(blueId),
                null, null, null);
        return new AffectedClosureSnapshot(
                hash('0'),
                1L,
                Collections.singletonList(managed),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                hash('e'),
                Collections.singletonList(component),
                Collections.singletonList(A));
    }

    private static Node initializedDocument(String label) {
        return markInitialized(baseDocument(label));
    }

    private static Node baseDocument(String label) {
        return new Node()
                .name("Managed " + label)
                .properties("label", new Node().value(label))
                .contracts(new Node()
                        .properties(
                                "external",
                                typed(EXTERNAL_BLUE_ID))
                        .properties(
                                "firstTrigger",
                                typed(RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL)
                                        .properties(
                                                "order",
                                                new Node().value(-1)))
                        .properties(
                                "trigger",
                                typed(RuntimeBlueIds
                                        .TRIGGERED_EVENT_CHANNEL))
                        .properties(
                                "embeddedEvent",
                                typed(RuntimeBlueIds
                                        .EMBEDDED_NODE_CHANNEL)
                                        .properties(
                                                "sourcePath",
                                                new Node().value("/child")))
                        .properties(
                                "update",
                                typed(RuntimeBlueIds
                                        .DOCUMENT_UPDATE_CHANNEL)
                                        .properties(
                                                "path",
                                                new Node().value("/")))
                        .properties(
                                "lifecycle",
                                typed(RuntimeBlueIds
                                        .LIFECYCLE_EVENT_CHANNEL))
                        .properties(
                                "probe",
                                typed(PROBE_BLUE_ID).properties(
                                        "channel",
                                        new Node().value("trigger")))
                        .properties(
                                "probeExternal",
                                typed(PROBE_BLUE_ID).properties(
                                        "channel",
                                        new Node().value("external")))
                        .properties(
                                "probeEmbedded",
                                typed(PROBE_BLUE_ID).properties(
                                        "channel",
                                        new Node().value("embeddedEvent")))
                        .properties(
                                "probeUpdate",
                                typed(PROBE_BLUE_ID).properties(
                                        "channel",
                                        new Node().value("update")))
                        .properties(
                                "probeLifecycle",
                                typed(PROBE_BLUE_ID).properties(
                                        "channel",
                                        new Node().value("lifecycle"))));
    }

    private static Node markInitialized(Node document) {
        Node preInitialization = document.clone();
        document.getContracts().properties(
                "initialized",
                typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                        .properties(
                                "document",
                                new Node().blueId(
                                        DirectBlueIdCalculator.calculateBlueId(
                                                preInitialization))));
        return document;
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static ClosureInvocationInput invocation(
            AffectedClosureSnapshot state) {
        String orderPolicy = hash('a');
        ExternalEventCause cause = new ExternalEventCause(
                hash('b'),
                new Node().name("event"),
                "event-blue",
                ExternalOrderKey.of(Arrays.asList(
                        1L, "timeline", 1L)),
                orderPolicy);
        return ClosureInvocationInput.processClosure(
                hash('c'),
                state,
                cause,
                Collections.<DirectLogicalDelivery>emptyList(),
                hash('d'),
                new ExecutionPolicy(
                        hash('1'),
                        1000000L,
                        new LinkedHashMap<DocumentId, Long>(),
                        "policy"),
                new ClosureEnvironment(
                        hash('2'), hash('3'), hash('4'), hash('5'),
                        hash('6'), hash('7'), hash('8'), orderPolicy,
                        hash('9'), hash('0'), hash('1')));
    }

    private static String hash(char digit) {
        StringBuilder value = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            value.append(digit);
        }
        return value.toString();
    }

    enum Action {
        OBSERVE,
        PATCH,
        EMIT,
        TERMINATE
    }

    public static final class ProbeHandler extends HandlerContract {
    }

    private static final class ProbeProcessor
            implements HandlerProcessor<ProbeHandler> {

        private final Action action;
        private final List<ProcessorExecutionContext> contexts =
                new ArrayList<ProcessorExecutionContext>();
        private final List<Observation> observations =
                new ArrayList<Observation>();
        private final List<Node> deliveredEvents =
                new ArrayList<Node>();
        private final List<Node> occurrenceEvents =
                new ArrayList<Node>();

        private ProbeProcessor(Action action) {
            this.action = action;
        }

        @Override
        public Class<ProbeHandler> contractType() {
            return ProbeHandler.class;
        }

        @Override
        public void execute(
                ProbeHandler contract,
                ProcessorExecutionContext context) {
            contexts.add(context);
            deliveredEvents.add(context.event().clone());
            occurrenceEvents.add(context.occurrenceEvent().clone());
            String label = context.documentAt("/label").getValue().toString();
            observations.add(new Observation(
                    label,
                    context.scopePath(),
                    context.documentAt("/managedChild") != null,
                    context.documentAt("/ambientParent") != null,
                    context.hasProcessEvent()));
            if (action == Action.PATCH) {
                context.applyPatches(Arrays.asList(
                        JsonPatch.add(
                                "/first",
                                new Node().value(label + "-first")),
                        JsonPatch.add(
                                "/second",
                                new Node().value(label + "-second"))));
            } else if (action == Action.EMIT) {
                context.emitEvent(new Node().properties(
                        "from", new Node().value(label)));
            } else if (action == Action.TERMINATE) {
                context.terminate("probe-stop", "requested");
            }
        }

        private List<String> labels() {
            List<String> values = new ArrayList<String>();
            for (Observation observation : observations) {
                values.add(observation.label);
            }
            return values;
        }

        private List<String> scopes() {
            List<String> values = new ArrayList<String>();
            for (Observation observation : observations) {
                values.add(observation.scopePath);
            }
            return values;
        }

        private List<Boolean> declaredChildVisible() {
            List<Boolean> values = new ArrayList<Boolean>();
            for (Observation observation : observations) {
                values.add(Boolean.valueOf(
                        observation.declaredChildVisible));
            }
            return values;
        }

        private List<Boolean> ambientParentVisible() {
            List<Boolean> values = new ArrayList<Boolean>();
            for (Observation observation : observations) {
                values.add(Boolean.valueOf(
                        observation.ambientParentVisible));
            }
            return values;
        }

        private List<Boolean> processEventVisible() {
            List<Boolean> values = new ArrayList<Boolean>();
            for (Observation observation : observations) {
                values.add(Boolean.valueOf(
                        observation.processEventVisible));
            }
            return values;
        }
    }

    private static final class Observation {
        private final String label;
        private final String scopePath;
        private final boolean declaredChildVisible;
        private final boolean ambientParentVisible;
        private final boolean processEventVisible;

        private Observation(
                String label,
                String scopePath,
                boolean declaredChildVisible,
                boolean ambientParentVisible,
                boolean processEventVisible) {
            this.label = label;
            this.scopePath = scopePath;
            this.declaredChildVisible = declaredChildVisible;
            this.ambientParentVisible = ambientParentVisible;
            this.processEventVisible = processEventVisible;
        }
    }

    private static final class CapturingContinuation
            implements ManagedDocumentStepContinuation {

        private final List<String> patchPaths = new ArrayList<String>();
        private final List<Boolean> firstVisibleAtPatch =
                new ArrayList<Boolean>();
        private final List<Boolean> secondVisibleAtPatch =
                new ArrayList<Boolean>();
        private final List<Integer> updateCounts = new ArrayList<Integer>();
        private final List<String> patchScopes =
                new ArrayList<String>();
        private final List<EventCapture> events =
                new ArrayList<EventCapture>();
        private final List<DocumentUpdateOccurrence> updates =
                new ArrayList<DocumentUpdateOccurrence>();

        @Override
        public void afterPatch(
                String scopePath,
                Node currentDocument,
                FrozenJsonPatch patch,
                List<DocumentUpdateOccurrence> updates) {
            patchPaths.add(patch.getPath());
            firstVisibleAtPatch.add(Boolean.valueOf(
                    currentDocument.getProperties().containsKey("first")));
            secondVisibleAtPatch.add(Boolean.valueOf(
                    currentDocument.getProperties().containsKey("second")));
            updateCounts.add(Integer.valueOf(updates.size()));
            this.updates.addAll(updates);
            patchScopes.add(scopePath);
            assertEquals("/", scopePath);
            assertNotNull(currentDocument);
        }

        @Override
        public void onApplicationEvent(
                String scopePath,
                String originContractKey,
                Node event,
                String eventBlueId) {
            events.add(new EventCapture(
                    scopePath,
                    originContractKey,
                    event.clone(),
                    eventBlueId));
        }

        @Override
        public void onTerminationRequested(
                String scopePath,
                String cause,
                String reason) {
            // The focused adapter test does not orchestrate lifecycle work.
        }
    }

    private static final class EventCapture {
        private final String scopePath;
        private final String originContractKey;
        private final Node event;
        private final String eventBlueId;

        private EventCapture(
                String scopePath,
                String originContractKey,
                Node event,
                String eventBlueId) {
            this.scopePath = scopePath;
            this.originContractKey = originContractKey;
            this.event = event;
            this.eventBlueId = eventBlueId;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ProbeProcessor probe;
        private final DocumentProcessor owner;
        private final CapturingContinuation hook;
        private final ManagedDocumentStepProcessor stepper;

        private Fixture(Action action) {
            this.probe = new ProbeProcessor(action);
            ContractProcessorRegistry registry =
                    ContractProcessorRegistryBuilder.create()
                            .register(
                                    EXTERNAL_BLUE_ID,
                                    EXTERNAL_TYPE,
                                    new TestExternalChannelProcessor())
                            .register(
                                    PROBE_BLUE_ID,
                                    PROBE_TYPE,
                                    probe)
                            .build();
            this.owner = DocumentProcessor.builder()
                    .runtimeRegistry(registry)
                    .build();
            this.hook = new CapturingContinuation();
            this.stepper = new ManagedDocumentStepProcessor(
                    owner, hook);
        }

        @Override
        public void close() {
            stepper.close();
            owner.close();
        }
    }

    /** Mutable application Channel model used by the focused adapter test. */
    public static final class TestExternalChannel extends ChannelContract {
    }

    private static final class TestExternalChannelProcessor
            implements ChannelProcessor<TestExternalChannel> {

        @Override
        public Class<TestExternalChannel> contractType() {
            return TestExternalChannel.class;
        }
    }
}
