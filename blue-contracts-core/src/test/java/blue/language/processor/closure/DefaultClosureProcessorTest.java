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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused execution proof for the concrete affected-closure processor. */
final class DefaultClosureProcessorTest {

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
    void rejectsAnActivatedDraftWithAnUntrackedManagedOccurrence() {
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

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                    attempt.processResult().status());
            assertFalse(attempt.processResult().commits());
            assertEquals(
                    ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                    attempt.processResult().diagnostic().category());
            assertEquals(input.snapshot().closureIdentity(),
                    attempt.processResult().outputClosureIdentity());
            assertEquals(Collections.singletonList(
                            WorkKind.EXTERNAL_DELIVERY),
                    workKinds(capture.evidence.workTrace()));
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
    void rebindsOnlyNonHistoricalInactiveRowsAcrossCyclicChurn() {
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
            String finalizedA = resultingDocument(attempt, A).afterBlueId();

            assertFalse(afterProspective.active());
            assertNull(afterProspective.pendingHistoricalEpoch());
            assertEquals(beforeProspective.occurrenceIdentity(),
                    afterProspective.occurrenceIdentity());
            assertEquals(finalizedA,
                    afterProspective.expectedTargetBlueId());
            assertFalse(beforeProspective.bindingIdentity().equals(
                    afterProspective.bindingIdentity()));

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
            throw new IllegalStateException(
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
