package blue.language.processor.closure;

import blue.language.api.BlueCachePolicy;
import blue.language.conformance.ConformanceEngine;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.ManagedProcessEmbeddedPath;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.runtime.BlueLanguageRuntime;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Focused acceptance proofs for closure-owned dynamic contract surfaces. */
final class ContractEvolutionClosureAcceptanceTest {

    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final long GAS_LIMIT = 100_000L;

    private static final Node HANDLER_TYPE =
            new Node().name("Contract evolution acceptance Handler");
    private static final String HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(HANDLER_TYPE);
    private static final Node EXTERNAL_TYPE =
            new Node().name("Contract evolution acceptance Channel");
    private static final String EXTERNAL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(EXTERNAL_TYPE);
    private static final Node OPERATION_TYPE =
            new Node().name("Contract evolution acceptance Operation");
    private static final String OPERATION_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(OPERATION_TYPE);
    private static final Node ACTOR_POLICY_TYPE =
            new Node().name("Contract evolution acceptance Actor Policy");
    private static final String ACTOR_POLICY_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(ACTOR_POLICY_TYPE);
    private static final Node UNSUPPORTED_TYPE =
            new Node().name("Unsupported contract evolution runtime");
    private static final String UNSUPPORTED_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(UNSUPPORTED_TYPE);

    private static final Node EVENT_ONE = event("one");
    private static final Node EVENT_CHILD_FIRST = event("child-first");
    private static final Node EVENT_CHILD_LATER = event("child-later");
    private static final Node EVENT_TENTATIVE = event("tentative");
    private static final String EVENT_ONE_BLUE_ID = blueId(EVENT_ONE);

    @Test
    void initializationFreezesLifecycleRoutesBeforeTheirSelfEvolution() {
        // given
        EvolutionProcessor probe = new EvolutionProcessor();
        try (DocumentProcessor owner = owner(probe, null)) {
            Node body = new Node()
                    .name("Frozen initialization lifecycle")
                    .properties("frozenRuns", number(0L))
                    .properties("addedRuns", number(0L))
                    .contracts(new Node()
                            .properties("aLifecycle", lifecycleChannel())
                            .properties("bLifecycle", lifecycleChannel())
                            .properties(
                                    "aMutateLifecycle",
                                    handler("aLifecycle", null))
                            .properties(
                                    "bFrozenLifecycle",
                                    handler("bLifecycle", null)));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true);

            // when
            ClosureProcessResult result = full(owner, input);

            // then
            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertEquals(BigInteger.ONE, admitted.get("/frozenRuns"));
            assertEquals(BigInteger.ZERO, admitted.get("/addedRuns"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/aLifecycle"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/bLifecycle"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/aMutateLifecycle"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/bFrozenLifecycle"));
            assertNotNull(admitted.get("/contracts/laterLifecycle"));
            assertNotNull(admitted.get("/contracts/zAddedLifecycle"));
            assertNotNull(admitted.get("/contracts/initialized"));
            assertEquals(Arrays.asList(
                            "aMutateLifecycle", "bFrozenLifecycle"),
                    probe.executions());
        }
    }

    @Test
    void triggeredRouteCompletesFrozenHandlersAndUsesAdditionOnlyForLaterWork() {
        // given
        EvolutionProcessor probe = new EvolutionProcessor();
        try (DocumentProcessor owner = owner(probe, null)) {
            Node body = new Node()
                    .name("Frozen Triggered route")
                    .properties("frozenRuns", number(0L))
                    .properties("addedRuns", number(0L))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "aTriggered",
                                    triggeredChannel(EVENT_ONE_BLUE_ID))
                            .properties(
                                    "bTriggered",
                                    triggeredChannel(EVENT_ONE_BLUE_ID))
                            .properties(
                                    "seedTriggered",
                                    handler("lifecycle", null))
                            .properties(
                                    "aMutateTriggered",
                                    handler("aTriggered", null))
                            .properties(
                                    "bFrozenTriggered",
                                    handler("bTriggered", null)));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true);

            // when
            ClosureProcessResult result = full(owner, input);

            // then
            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertEquals(BigInteger.ONE, admitted.get("/frozenRuns"));
            assertEquals(BigInteger.ONE, admitted.get("/addedRuns"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/aTriggered"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/bTriggered"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/aMutateTriggered"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/bFrozenTriggered"));
            assertNotNull(admitted.get("/contracts/laterTriggered"));
            assertNotNull(admitted.get("/contracts/zAddedTriggered"));
            assertEquals(Arrays.asList(
                            "seedTriggered",
                            "aMutateTriggered",
                            "bFrozenTriggered",
                            "zAddedTriggered"),
                    probe.executions());
            assertEquals(2, result.publicEvents().size());
        }
    }

    @Test
    void actorPolicyMutationChangesOnlyLaterEligibilityWhileFrozenWorkCompletes() {
        EvolutionProcessor probe = new EvolutionProcessor();
        try (DocumentProcessor owner = owner(probe, null)) {
            Node body = new Node()
                    .name("Frozen Actor Policy eligibility")
                    .properties("currentEligibilityRuns", number(0L))
                    .properties("laterEligibilityRuns", number(0L))
                    .contracts(new Node()
                            .properties(
                                    "actorPolicy",
                                    matrixContract(ACTOR_POLICY_BLUE_ID, 1))
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "policyTriggered",
                                    triggeredChannel(EVENT_ONE_BLUE_ID))
                            .properties(
                                    "seedPolicy",
                                    handler("lifecycle", null))
                            .properties(
                                    "aMutatePolicy",
                                    policyHandler(1))
                            .properties(
                                    "bCurrentPolicyEligible",
                                    policyHandler(1))
                            .properties(
                                    "zLaterPolicyEligible",
                                    policyHandler(2)));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true);

            ClosureProcessResult result = full(owner, input);

            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertEquals(BigInteger.ONE,
                    admitted.get("/currentEligibilityRuns"));
            assertEquals(BigInteger.ONE,
                    admitted.get("/laterEligibilityRuns"));
            assertEquals(BigInteger.valueOf(2L),
                    admitted.get("/contracts/actorPolicy/revision"));
            assertEquals(Arrays.asList(
                            "seedPolicy",
                            "aMutatePolicy",
                            "bCurrentPolicyEligible",
                            "zLaterPolicyEligible"),
                    probe.executions());
            assertEquals(2, result.publicEvents().size(),
                    "the policy mutation must enqueue one later event");
        }
    }

    @Test
    void embeddedRouteCompletesFrozenHandlersAndUsesAdditionOnlyForLaterWork() {
        // given
        EvolutionProcessor probe = new EvolutionProcessor();
        ExternalProcessor external = new ExternalProcessor();
        try (DocumentProcessor owner = owner(probe, external)) {
            Node child = new Node()
                    .name("Embedded event source")
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "emitChildPair",
                                    handler("lifecycle", null)));
            String childBlueId = blueId(child);
            Node parent = new Node()
                    .name("Frozen Embedded route")
                    .properties("child", new Node().blueId(childBlueId))
                    .properties("frozenRuns", number(0L))
                    .properties("addedRuns", number(0L))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child"))
                            .properties(
                                    "aEmbedded",
                                    embeddedChannel(
                                            "/child", EVENT_CHILD_FIRST))
                            .properties(
                                    "bEmbedded",
                                    embeddedChannel(
                                            "/child", EVENT_CHILD_FIRST))
                            .properties(
                                    "aMutateEmbedded",
                                    handler("aEmbedded", null))
                            .properties(
                                    "bFrozenEmbedded",
                                    handler("bEmbedded", null)));
            ClosureEnvironment environment = environment(owner);
            ManagedOccurrenceBinding binding = activeBinding(
                    environment, A, "/child", B, childBlueId);
            ClosureInvocationInput input = admission(
                    finalizedSnapshot(
                            bodies(A, parent, B, child),
                            Collections.singletonList(binding),
                            Collections.singletonList(A)),
                    environment);

            // when
            ClosureProcessResult result = full(owner, input);

            // then
            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertEquals(BigInteger.ONE, admitted.get("/frozenRuns"));
            assertEquals(BigInteger.ONE, admitted.get("/addedRuns"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/aEmbedded"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/bEmbedded"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/aMutateEmbedded"));
            assertNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/bFrozenEmbedded"));
            assertNotNull(admitted.get("/contracts/laterEmbedded"));
            assertNotNull(admitted.get("/contracts/zAddedEmbedded"));
            assertEquals(Arrays.asList(
                            "emitChildPair",
                            "aMutateEmbedded",
                            "bFrozenEmbedded",
                            "zAddedEmbedded"),
                    probe.executions());
            assertTrue(result.publicEvents().isEmpty(),
                    "Private child emissions remain internal");
        }
    }

    @Test
    void removingConcretePeerRetiresBindingAndEdgeWhileDeclarationRemains() {
        // given
        EvolutionProcessor probe = new EvolutionProcessor();
        try (DocumentProcessor owner = owner(probe, null)) {
            Node peer = new Node().name("Concrete peer");
            String peerBlueId = blueId(peer);
            Node parent = new Node()
                    .name("Peer retirement parent")
                    .properties("peer", new Node().blueId(peerBlueId))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/peer"))
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "removePeer",
                                    handler("lifecycle", null)));
            ClosureEnvironment environment = environment(owner);
            ManagedOccurrenceBinding binding = activeBinding(
                    environment, A, "/peer", B, peerBlueId);
            ClosureInvocationInput input = admission(
                    finalizedSnapshot(
                            bodies(A, parent, B, peer),
                            Collections.singletonList(binding),
                            Collections.singletonList(A)),
                    environment);

            // when
            ClosureProcessResult result = full(owner, input);

            // then
            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertNull(NodePathEditor.getOrNull(admitted, "/peer"));
            assertNotNull(NodePathEditor.getOrNull(
                    admitted, "/contracts/embedded"));
            ManagedOccurrenceBinding retired = onlyBinding(
                    result.occurrenceBindings(), A, "/peer");
            assertFalse(retired.active());
            assertEquals(2L, retired.activationGeneration());
            assertEquals(1, result.graphChanges().size());
            GraphChange removal = result.graphChanges().get(0);
            assertEquals(GraphChange.Kind.REMOVE, removal.changeKind());
            assertEquals(A, removal.sourceDocumentId());
            assertEquals("/peer", removal.sourcePath());
            assertFalse(ManagedDocumentGraph.fromBindings(
                    Arrays.asList(A, B), result.occurrenceBindings())
                    .hasEdge(A, B));
            assertEquals(result.occurrenceBindingSetIdentity(),
                    result.commitCompanion()
                            .occurrenceBindingSetIdentity());
            assertEquals(result.graphChangesIdentity(),
                    result.commitCompanion().graphChangesIdentity());
        }
    }

    @Test
    void failureAfterTentativeSurfaceChangesRollsBackWholeClosure() {
        // given
        EvolutionProcessor probe = new EvolutionProcessor();
        ExternalProcessor external = new ExternalProcessor();
        try (DocumentProcessor owner = owner(probe, external)) {
            Node later = new Node()
                    .name("Earlier tentatively changed member")
                    .properties("otherState", new Node().value("before"))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "earlierMemberPatch",
                                    handler("lifecycle", null)));
            String laterBlueId = blueId(later);
            Node first = new Node()
                    .name("Tentatively changed member")
                    .properties("peer", new Node().blueId(laterBlueId))
                    .properties("state", new Node().value("before"))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/peer"))
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "tentativeSurface",
                                    handler("lifecycle", null)));
            ClosureEnvironment environment = environment(owner);
            ManagedOccurrenceBinding binding = activeBinding(
                    environment, A, "/peer", B, laterBlueId);
            ClosureInvocationInput input = admission(
                    finalizedSnapshot(
                            bodies(A, first, B, later),
                            Collections.singletonList(binding),
                            Arrays.asList(A, B)),
                    environment);

            // when
            ClosureProcessResult failed = full(owner, input);
            ClosureProcessResult retry = full(owner, input);

            // then
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.status());
            assertEquals(ProcessorStatus.RUNTIME_FATAL, retry.status());
            assertEquals(Arrays.asList(
                            "earlierMemberPatch",
                            "tentativeSurface",
                            "earlierMemberPatch",
                            "tentativeSurface"),
                    probe.executions());
            assertLiteralRollback(input, failed);
            assertLiteralRollback(input, retry);
            assertEquals(input.snapshot().occurrences(),
                    failed.occurrenceBindings());
            assertTrue(onlyBinding(
                    failed.occurrenceBindings(), A, "/peer").active());
            assertTrue(ManagedDocumentGraph.fromBindings(
                    Arrays.asList(A, B), failed.occurrenceBindings())
                    .hasEdge(A, B));
            assertTrue(failed.totalGas() > 0L);
            assertFalse(failed.gasTrace().isEmpty());
            assertEquals(failed.totalGas(), retry.totalGas());
            assertEquals(failed.gasTraceIdentity(),
                    retry.gasTraceIdentity());
            assertEquals(failed.diagnostic().category(),
                    retry.diagnostic().category());
            assertEquals(failed.diagnostic().message(),
                    retry.diagnostic().message());
            assertNull(failed.rejectedCharge());
            assertNull(failed.commitCompanion());
        }
    }

    @Test
    void registeredApplicationRuntimeMatrixAcceptsActorPolicyEvolution() {
        // The matrix is intentionally discovered from the exact registry
        // generation. Adding another registered application runtime makes
        // this proof demand a test fixture for that runtime as well.
        ContractProcessorRegistry inventory = registry(
                new EvolutionProcessor(), new ExternalProcessor());
        List<String> registeredBlueIds =
                new ArrayList<String>(inventory.processors().keySet());
        Set<String> actorPolicyMutations = new LinkedHashSet<String>();

        for (String action : Arrays.asList("add", "replace", "remove")) {
            for (String runtimeBlueId : registeredBlueIds) {
                EvolutionProcessor probe = new EvolutionProcessor();
                ExternalProcessor external = new ExternalProcessor();
                ContractProcessorRegistry runtime = registry(probe, external);
                ContractProcessor<?> processor =
                        runtime.processors().get(runtimeBlueId);
                try (DocumentProcessor owner = owner(runtime)) {
                    Node body = matrixBody(
                            runtimeBlueId, processor, action);
                    ClosureInvocationInput input = simpleAdmission(
                            owner, A, body, true);

                    ClosureProcessResult result = full(owner, input);

                    assertSuccess(result);
                    assertMatrixMutation(
                            document(result, A).document(),
                            runtimeBlueId,
                            action);
                    assertContractTransitionEvidence(
                            result,
                            "/contracts/target",
                            action);
                    assertTransitionStepChainIsCrossBound(result, A);
                    if (ACTOR_POLICY_BLUE_ID.equals(runtimeBlueId)) {
                        actorPolicyMutations.add(action);
                    }
                }
            }
        }

        assertEquals(
                new LinkedHashSet<String>(Arrays.asList(
                        "add", "replace", "remove")),
                actorPolicyMutations,
                "the typed registered Actor Policy must remain mutable");
    }

    @Test
    void exposesGeneratedGeneralizationAndEffectiveTypeEvidence() {
        BasicNodeProvider applicationTypes = new BasicNodeProvider();
        applicationTypes.addSingleDocs(
                "name: Price\n"
                        + "amount:\n"
                        + "  type: Integer\n"
                        + "currency:\n"
                        + "  type: Text");
        applicationTypes.addSingleDocs(
                "name: Price in EUR\n"
                        + "type:\n"
                        + "  blueId: "
                        + applicationTypes.getBlueIdByName(
                                "Price")
                        + "\ncurrency: EUR");
        applicationTypes.addSingleDocs(
                "name: Global Product\n"
                        + "price:\n"
                        + "  type:\n"
                        + "    blueId: "
                        + applicationTypes.getBlueIdByName("Price"));
        applicationTypes.addSingleDocs(
                "name: European Product\n"
                        + "type:\n"
                        + "  blueId: "
                        + applicationTypes.getBlueIdByName(
                                "Global Product")
                        + "\nprice:\n"
                        + "  type:\n"
                        + "    blueId: "
                        + applicationTypes.getBlueIdByName(
                                "Price in EUR"));
        String requiredType = applicationTypes.getBlueIdByName(
                "European Product");
        String optionalType = applicationTypes.getBlueIdByName(
                "Global Product");
        String genericPriceType = applicationTypes.getBlueIdByName("Price");
        EvolutionProcessor probe = new EvolutionProcessor();
        ContractProcessorRegistry runtime = registry(probe, null);
        NodeProvider evidenceProvider = new SequentialNodeProvider(
                applicationTypes,
                new ExactEventProvider());
        try (BlueLanguageRuntime language = BlueLanguageRuntime.create(
                    evidenceProvider,
                    BlueCachePolicy.disabled(),
                    Collections.<String, String>emptyMap());
             ConformanceEngine conformance =
                     language.newConformanceEngine();
             DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(runtime)
                .nodeProvider(evidenceProvider)
                .conformanceEngine(conformance)
                .snapshotStore(new LanguageSnapshotManager(language, evidenceProvider))
                .matchingService(new ContractMatchingService(language))
                .build()) {
            Node body = new Node()
                    .name("Generated generalization evidence")
                    .type(new Node().blueId(requiredType))
                    .properties("price", new Node()
                            .properties("amount", new Node().value(150))
                            .properties("currency", new Node().value("EUR")))
                    .contracts(new Node()
                            .properties("generalization", typed(
                                    RuntimeBlueIds.TYPE_GENERALIZATION_POLICY)
                                    .properties("defaultMode", new Node()
                                            .value("nearest-valid-ancestor")))
                            .properties("lifecycle", lifecycleChannel())
                            .properties("generalizePrice",
                                    handler("lifecycle", null)));

            ClosureProcessResult result = full(
                    owner,
                    simpleAdmission(owner, A, body, true));

            assertSuccess(result);
            DocumentTransitionEvidence transition =
                    transitionWithGeneralizationWrite(result, "/type");
            assertEquals(requiredType,
                    transition.beforeEffectiveTypeBlueId().get());
            assertFalse(transition.beforeDocumentBlueId().equals(
                    transition.afterDocumentBlueId()));
            assertEquals(2,
                    transition.generatedGeneralizationWrites().size());
            DocumentTransitionEvidence.GeneratedGeneralizationWrite
                    nestedWrite = generalizationWrite(
                            transitionWithGeneralizationWrite(
                                    result, "/price/type"),
                            "/price/type");
            DocumentTransitionEvidence.GeneratedGeneralizationWrite
                    rootWrite = null;
            for (DocumentTransitionEvidence.GeneratedGeneralizationWrite write
                    : transition.generatedGeneralizationWrites()) {
                if ("/type".equals(write.path())) {
                    rootWrite = write;
                }
            }
            assertNotNull(rootWrite);
            assertEquals(genericPriceType, nestedWrite.valueBlueId());
            assertEquals(optionalType, rootWrite.valueBlueId());
            assertEquals(optionalType,
                    document(result, A).document().getType().getBlueId());
            assertEquals(0, nestedWrite.requiringPatchIndex());
            assertEquals(0, rootWrite.requiringPatchIndex());
            assertTransitionStepChainIsCrossBound(result, A);
        }
    }

    @Test
    void doesNotClassifyAnOrdinaryNestedContractsMemberAsContractSurface() {
        EvolutionProcessor probe = new EvolutionProcessor();
        try (DocumentProcessor owner = owner(probe, null)) {
            Node body = new Node()
                    .name("Ordinary nested contracts data")
                    .properties("payload", new Node()
                            .properties("contracts", new Node()
                                    .properties("target", new Node()
                                            .value("before"))))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("mutatePayloadContracts",
                                    handler("lifecycle", null)));

            ClosureProcessResult result = full(
                    owner,
                    simpleAdmission(owner, A, body, true));

            assertSuccess(result);
            assertEquals("after", document(result, A).document()
                    .getAsText("/payload/contracts/target"));
            assertFalse(result.documentTransitionEvidence().isEmpty());
            for (DocumentTransitionEvidence transition
                    : result.documentTransitionEvidence()) {
                assertTrue(transition.authoredContractPatches().isEmpty());
            }
        }
    }

    @Test
    void exposesExactEvidenceForANestedContractValuePatch() {
        EvolutionProcessor probe = new EvolutionProcessor();
        ContractProcessorRegistry runtime = registry(probe, null);
        try (DocumentProcessor owner = owner(runtime)) {
            Node body = new Node()
                    .name("Nested contract mutation evidence")
                    .contracts(new Node()
                            .properties("actorPolicy",
                                    matrixContract(ACTOR_POLICY_BLUE_ID, 1))
                            .properties("lifecycle", lifecycleChannel())
                            .properties("mutateNestedContractValue",
                                    handler("lifecycle", null)));

            ClosureProcessResult result = full(
                    owner,
                    simpleAdmission(owner, A, body, true));

            assertSuccess(result);
            assertEquals(2, document(result, A).document()
                    .getAsInteger("/contracts/actorPolicy/revision"));
            DocumentTransitionEvidence transition = transitionWithPatch(
                    result, "/contracts/actorPolicy/revision");
            assertEquals(1, transition.authoredContractPatches().size());
            DocumentTransitionEvidence.AuthoredContractPatch patch =
                    transition.authoredContractPatches().get(0);
            assertEquals(DocumentTransitionEvidence.Operation.REPLACE,
                    patch.operation());
            assertTrue(patch.beforeValueBlueId().isPresent());
            assertTrue(patch.afterValueBlueId().isPresent());
            assertFalse(patch.beforeValueBlueId().get().equals(
                    patch.afterValueBlueId().get()));
        }
    }

    @Test
    void legacyResultConstructorKeepsEvidenceEmptyAndIdentitySurfaceStable() {
        EvolutionProcessor probe = new EvolutionProcessor();
        try (DocumentProcessor owner = owner(probe, null)) {
            ClosureInvocationInput input = simpleAdmission(
                    owner,
                    A,
                    matrixBody(
                            ACTOR_POLICY_BLUE_ID,
                            new ActorPolicyProcessor(),
                            "replace"),
                    true);
            ClosureProcessResult actual = full(owner, input);

            ClosureProcessResult compatibility = new ClosureProcessResult(
                    input.snapshot(),
                    actual.status(),
                    actual.invocationIdentity(),
                    actual.outputClosureIdentity(),
                    actual.graphGeneration(),
                    actual.resultingDocuments(),
                    actual.resultingComponents(),
                    actual.occurrenceBindings(),
                    actual.occurrenceBindingSetIdentity(),
                    actual.graphChanges(),
                    actual.graphChangesIdentity(),
                    actual.subscriptionDeltas(),
                    actual.subscriptionDeltasIdentity(),
                    actual.checkpointWrites(),
                    actual.checkpointWritesIdentity(),
                    actual.publicEvents(),
                    actual.publicEventsIdentity(),
                    actual.totalGas(),
                    actual.gasTrace(),
                    actual.gasTraceIdentity(),
                    actual.rejectedCharge(),
                    actual.rejectedWorkOccurrence(),
                    actual.platformCommitCompanion(),
                    actual.diagnostic());

            assertFalse(actual.documentTransitionEvidence().isEmpty());
            DocumentTransitionEvidence first =
                    actual.documentTransitionEvidence().get(0);
            LocalDocumentStepResult legacyLocal =
                    new LocalDocumentStepResult(
                            first.documentId(),
                            first.workOccurrenceIdentity(),
                            first.beforeDocumentBlueId(),
                            document(actual, A).document(),
                            Collections.<Node>emptyList(),
                            Collections.<FrozenJsonPatch>emptyList(),
                            0L,
                            0L,
                            false);
            assertFalse(legacyLocal.transitionEvidence().isPresent());
            assertEquals(Collections.emptyList(),
                    compatibility.documentTransitionEvidence());
            assertEquals(actual.invocationIdentity(),
                    compatibility.invocationIdentity());
            assertEquals(actual.inputClosureIdentity(),
                    compatibility.inputClosureIdentity());
            assertEquals(actual.outputClosureIdentity(),
                    compatibility.outputClosureIdentity());
            assertEquals(actual.occurrenceBindingSetIdentity(),
                    compatibility.occurrenceBindingSetIdentity());
            assertEquals(actual.graphChangesIdentity(),
                    compatibility.graphChangesIdentity());
            assertEquals(actual.subscriptionDeltasIdentity(),
                    compatibility.subscriptionDeltasIdentity());
            assertEquals(actual.checkpointWritesIdentity(),
                    compatibility.checkpointWritesIdentity());
            assertEquals(actual.publicEventsIdentity(),
                    compatibility.publicEventsIdentity());
            assertEquals(actual.gasTraceIdentity(),
                    compatibility.gasTraceIdentity());
            assertEquals(actual.platformCommitCompanion(),
                    compatibility.platformCommitCompanion());
            assertThrows(UnsupportedOperationException.class,
                    () -> actual.documentTransitionEvidence().clear());
        }
    }

    @Test
    void laterRootDemandLeavesEarlierReconcilableRootWhollyUntouched() {
        EvolutionProcessor probe = new EvolutionProcessor();
        try (DocumentProcessor owner = owner(probe, null)) {
            DocumentId targetId = new DocumentId("c");
            Node target = new Node().name("Known prospective target");
            String targetBlueId = blueId(target);
            String missingBlueId = blueId(
                    new Node().name("Unavailable later-root target"));
            Node earlier = new Node()
                    .name("Earlier reconcilable Root")
                    .properties("peer", new Node().blueId(targetBlueId))
                    .contracts(new Node().properties(
                            "embedded", processEmbedded("/peer")));
            Node later = new Node()
                    .name("Later demanding Root")
                    .properties("peer", new Node().blueId(missingBlueId))
                    .contracts(new Node().properties(
                            "embedded", processEmbedded("/peer")));
            ClosureEnvironment environment = environment(owner);
            ManagedOccurrenceBinding prospective =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/peer", 1L),
                            targetId,
                            targetBlueId,
                            false,
                            null);
            LinkedHashMap<DocumentId, Node> bodies =
                    new LinkedHashMap<DocumentId, Node>();
            bodies.put(A, earlier);
            bodies.put(B, later);
            bodies.put(targetId, target);
            AffectedClosureSnapshot snapshot = finalizedSnapshot(
                    bodies,
                    Collections.singletonList(prospective),
                    Arrays.asList(A, B));
            ClosureInvocationInput input = admission(snapshot, environment);

            ProcessEmbeddedSurfaceReconciler.Reconciliation possible =
                    new ProcessEmbeddedSurfaceReconciler()
                            .reconcileProjected(
                                    A,
                                    snapshot.managedDocument(A).document(),
                                    Collections.singletonList(
                                            new ManagedProcessEmbeddedPath(
                                                    "/peer",
                                                    blueId(processEmbedded(
                                                            "/peer")))),
                                    snapshot.occurrences(),
                                    snapshot.managedDocuments(),
                                    Collections.<ProcessEmbeddedSurfaceReconciler
                                            .OccurrencePath>emptySet());
            assertTrue(onlyBinding(
                    possible.bindings(), A, "/peer").active(),
                    "the earlier Root must actually be reconcilable");
            assertTrue(ManagedDocumentGraph.fromBindings(
                    Arrays.asList(A, B, targetId), possible.bindings())
                    .hasEdge(A, targetId),
                    "reconciliation would activate the earlier graph edge");

            Map<DocumentId, Object> beforeDocuments =
                    snapshotDocuments(snapshot);
            List<ManagedOccurrenceBinding> beforeRows =
                    new ArrayList<ManagedOccurrenceBinding>(
                            snapshot.occurrences());
            String beforeClosureIdentity = snapshot.closureIdentity();
            String beforeBindingSetIdentity =
                    snapshot.occurrenceBindingSetIdentity();
            long beforeGraphGeneration = snapshot.graphGeneration();
            AtomicInteger publications = new AtomicInteger();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(
                                 owner,
                                 evidence -> publications.incrementAndGet())) {
                attempt = contracts.admitClosureWithLifecycleQueue(input);
            }

            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind());
            assertFalse(attempt.isComplete());
            assertNull(attempt.processResult());
            assertNull(attempt.totalGas(),
                    "resource suspension publishes no semantic gas");
            assertEquals(Collections.singletonList(missingBlueId),
                    attempt.requiredExactBlueIds());
            assertEquals(1, attempt.resourceDemands().size());
            ClosureResourceDemand demand =
                    attempt.resourceDemands().get(0);
            assertTrue(demand instanceof ExactNodeDemand);
            assertEquals(B, demand.sourceDocumentId());
            assertEquals("/peer", demand.sourcePath());
            assertEquals(missingBlueId, demand.suppliedValueBlueId());
            assertEquals(0, publications.get(),
                    "resource suspension publishes no completion evidence");

            assertEquals(beforeClosureIdentity,
                    input.snapshot().closureIdentity());
            assertEquals(beforeBindingSetIdentity,
                    input.snapshot().occurrenceBindingSetIdentity());
            assertEquals(beforeGraphGeneration,
                    input.snapshot().graphGeneration());
            assertEquals(beforeDocuments,
                    snapshotDocuments(input.snapshot()));
            assertEquals(beforeRows, input.snapshot().occurrences());
            assertFalse(onlyBinding(
                    input.snapshot().occurrences(), A, "/peer").active(),
                    "the earlier prospective row must remain inactive");
            assertFalse(ManagedDocumentGraph.fromBindings(
                    Arrays.asList(A, B, targetId),
                    input.snapshot().occurrences()).hasEdge(A, targetId),
                    "the earlier graph edge must remain unpublished");
        }
    }

    @Test
    void unsupportedResultingRuntimeRollsBackStateEventsLedgerAndCharge() {
        EvolutionProcessor probe = new EvolutionProcessor();
        try (DocumentProcessor owner = owner(probe, null)) {
            Node body = new Node()
                    .name("Unsupported resulting application runtime")
                    .properties("state", new Node().value("before"))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "installUnsupported",
                                    handler("lifecycle", null)));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true);

            ClosureProcessResult failed = full(owner, input);
            ClosureProcessResult retry = full(owner, input);

            assertEquals(ProcessorStatus.RUNTIME_FATAL,
                    failed.status());
            assertEquals(ProcessorErrorCategory.UnsupportedRuntimeType,
                    failed.diagnostic().category());
            assertLiteralRollback(input, failed);
            assertLiteralRollback(input, retry);
            assertEquals(Collections.emptyList(), failed.publicEvents());
            assertEquals(Collections.emptyList(), failed.checkpointWrites());
            assertEquals(Collections.emptyList(), failed.subscriptionDeltas());
            assertNull(failed.rejectedCharge(),
                    "a rolled-back runtime rejection cannot charge a ledger");
            assertNull(failed.commitCompanion(),
                    "a rolled-back runtime rejection has no durable companion");
            assertTrue(failed.totalGas() > 0L,
                    "attempt evidence retains admitted gas without charging it");
            assertEquals(failed.totalGas(), retry.totalGas());
            assertEquals(failed.gasTraceIdentity(),
                    retry.gasTraceIdentity());
            assertEquals(failed.diagnostic().category(),
                    retry.diagnostic().category());
        }
    }

    private static DocumentProcessor owner(
            EvolutionProcessor handler,
            ExternalProcessor external) {
        return owner(registry(handler, external));
    }

    private static ContractProcessorRegistry registry(
            EvolutionProcessor handler,
            ExternalProcessor external) {
        ContractProcessorRegistryBuilder registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                HANDLER_BLUE_ID,
                                HANDLER_TYPE,
                                handler)
                        .register(
                                OPERATION_BLUE_ID,
                                OPERATION_TYPE,
                                new OperationProcessor())
                        .register(
                                ACTOR_POLICY_BLUE_ID,
                                ACTOR_POLICY_TYPE,
                                new ActorPolicyProcessor());
        if (external != null) {
            registry.register(EXTERNAL_BLUE_ID, EXTERNAL_TYPE, external);
        }
        return registry.build();
    }

    private static DocumentProcessor owner(
            ContractProcessorRegistry runtime) {
        return DocumentProcessor.builder()
                .runtimeRegistry(runtime)
                .nodeProvider(new ExactEventProvider())
                .build();
    }

    private static ClosureInvocationInput simpleAdmission(
            DocumentProcessor owner,
            DocumentId documentId,
            Node body,
            boolean publicRoot) {
        ClosureEnvironment environment = environment(owner);
        return admission(
                finalizedSnapshot(
                        Collections.singletonMap(documentId, body),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        publicRoot
                                ? Collections.singletonList(documentId)
                                : Collections.<DocumentId>emptyList()),
                environment);
    }

    private static ClosureEnvironment environment(DocumentProcessor owner) {
        return ClosureEvidenceFactory.environment(
                owner,
                hash('a'),
                hash('b'),
                "contract-evolution-document-lineage-v1",
                "contract-evolution-binding-lineage-v1",
                "contract-evolution-provider-domain-v1",
                "contract-evolution-external-order-v1",
                "contract-evolution-portable-limits-v1",
                GasSchedule.contracts10().portableLimits());
    }

    private static ClosureInvocationInput admission(
            AffectedClosureSnapshot snapshot,
            ClosureEnvironment environment) {
        AdmissionCause cause = ClosureEvidenceFactory.admissionCause(
                AdmissionKind.TOP_LEVEL_ADMISSION,
                "contract-evolution-acceptance",
                null,
                null,
                "contract-evolution-admission-policy-v1");
        ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(
                GAS_LIMIT,
                Collections.<DocumentId, Long>emptyMap(),
                "contract-evolution-shared-gas-v1");
        return ClosureEvidenceFactory.admitClosure(
                snapshot, cause, null, policy, environment);
    }

    private static ClosureProcessResult full(
            DocumentProcessor owner,
            ClosureInvocationInput input) {
        ClosureAttemptResult attempt;
        try (BlueClosureContracts contracts =
                     new BlueClosureContracts(owner)) {
            attempt = contracts.admitClosureWithLifecycleQueue(input);
        }
        assertTrue(attempt.isComplete(),
                "The fixed test provider has every required exact node");
        return attempt.processResult();
    }

    private static AffectedClosureSnapshot finalizedSnapshot(
            Map<DocumentId, Node> sourceBodies,
            List<ManagedOccurrenceBinding> sourceBindings,
            List<DocumentId> publicRoots) {
        ArrayList<DocumentId> sourceOrder =
                new ArrayList<DocumentId>(sourceBodies.keySet());
        ArrayList<DocumentId> canonical =
                new ArrayList<DocumentId>(sourceOrder);
        Collections.sort(canonical);
        ArrayList<ManagedOccurrenceBinding> bindings =
                new ArrayList<ManagedOccurrenceBinding>(sourceBindings);
        Collections.sort(bindings);
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        for (DocumentId id : sourceOrder) {
            generations.put(id, Long.valueOf(1L));
            bodies.put(id, sourceBodies.get(id).clone());
        }
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                ManagedDocumentGraph.fromBindings(
                                        sourceOrder, bindings),
                                generations,
                                bodies,
                                bindings));
        Set<DocumentId> publicSet =
                new LinkedHashSet<DocumentId>(publicRoots);
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId id : canonical) {
            FinalizedDocumentEvidence exact = finalized.document(id);
            documents.add(new ManagedDocumentSnapshot(
                    id,
                    exact.blueId(),
                    exact.document(),
                    false,
                    false,
                    publicSet.contains(id),
                    0L,
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component : finalized.components()) {
            components.add(component.component());
        }
        return ClosureEvidenceFactory.affectedClosure(
                1L,
                documents,
                finalized.finalizedGraph().bindings(),
                components,
                publicRoots);
    }

    private static ManagedOccurrenceBinding activeBinding(
            ClosureEnvironment environment,
            DocumentId source,
            String path,
            DocumentId target,
            String targetBlueId) {
        return ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                source,
                ScopeAddress.embedded(path, 1L),
                target,
                targetBlueId,
                true,
                null);
    }

    private static LinkedHashMap<DocumentId, Node> bodies(
            DocumentId firstId,
            Node first,
            DocumentId secondId,
            Node second) {
        LinkedHashMap<DocumentId, Node> result =
                new LinkedHashMap<DocumentId, Node>();
        result.put(firstId, first);
        result.put(secondId, second);
        return result;
    }

    private static Map<DocumentId, Object> snapshotDocuments(
            AffectedClosureSnapshot snapshot) {
        LinkedHashMap<DocumentId, Object> result =
                new LinkedHashMap<DocumentId, Object>();
        for (ManagedDocumentSnapshot document
                : snapshot.managedDocuments()) {
            result.put(
                    document.documentId(),
                    NodeWireForm.get(
                            document.document(),
                            NodeWireForm.Strategy.SIMPLE));
        }
        return result;
    }

    private static Node lifecycleChannel() {
        return typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL);
    }

    private static Node triggeredChannel(String eventBlueId) {
        return typed(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
                .properties("event", new Node().blueId(eventBlueId));
    }

    private static Node embeddedChannel(String path, Node event) {
        return typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                .properties("sourcePath", new Node().value(path))
                .properties("event", event.clone());
    }

    private static Node processEmbedded(String path) {
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                .properties(
                        "paths",
                        new Node().items(new Node().value(path)));
    }

    private static Node externalChannel(String subscriptionKey) {
        return typed(EXTERNAL_BLUE_ID)
                .properties(
                        "subscriptionKey",
                        new Node().value(subscriptionKey));
    }

    private static Node handler(
            String channel,
            Node eventPattern) {
        Node result = typed(HANDLER_BLUE_ID)
                .properties("channel", new Node().value(channel));
        if (eventPattern != null) {
            result.properties("event", eventPattern.clone());
        }
        return result;
    }

    private static Node policyHandler(int eligibleRevision) {
        return typed(HANDLER_BLUE_ID)
                .properties("revision", number(eligibleRevision));
    }

    private static Node matrixBody(
            String runtimeBlueId,
            ContractProcessor<?> processor,
            String action) {
        Node contracts = new Node()
                .properties("lifecycle", lifecycleChannel())
                .properties(
                        "matrixMutation",
                        handler("lifecycle", null));
        if (!"add".equals(action)) {
            contracts.properties(
                    "target",
                    matrixContract(runtimeBlueId, processor, 1));
        }
        return new Node()
                .name("Registered runtime mutation matrix")
                .properties(
                        "matrixAction",
                        new Node().value(action))
                .properties(
                        "matrixTargetBlueId",
                        new Node().value(runtimeBlueId))
                .contracts(contracts);
    }

    private static Node matrixContract(
            String runtimeBlueId,
            ContractProcessor<?> processor,
            int revision) {
        if (processor instanceof HandlerProcessor
                && EvolutionHandler.class.equals(
                        processor.contractType())) {
            return handler("lifecycle", null)
                    .properties("revision", number(revision));
        }
        if (processor instanceof HandlerProcessor
                && EvolutionOperation.class.equals(
                        processor.contractType())) {
            return typed(runtimeBlueId)
                    .properties("channel", new Node().value("lifecycle"))
                    .properties("revision", number(revision));
        }
        if (processor instanceof ChannelProcessor
                && EvolutionExternalChannel.class.equals(
                        processor.contractType())) {
            return externalChannel("matrix-topic-" + revision);
        }
        if (MarkerContract.class.isAssignableFrom(
                processor.contractType())
                && ActorPolicy.class.equals(processor.contractType())) {
            return typed(runtimeBlueId)
                    .properties("revision", number(revision));
        }
        throw new AssertionError(
                "Missing mutation fixture for registered runtime "
                        + runtimeBlueId + " ("
                        + processor.contractType().getName() + ")");
    }

    private static Node matrixContract(
            String runtimeBlueId,
            int revision) {
        if (HANDLER_BLUE_ID.equals(runtimeBlueId)) {
            return handler("lifecycle", null)
                    .properties("revision", number(revision));
        }
        if (OPERATION_BLUE_ID.equals(runtimeBlueId)) {
            return typed(runtimeBlueId)
                    .properties("channel", new Node().value("lifecycle"))
                    .properties("revision", number(revision));
        }
        if (EXTERNAL_BLUE_ID.equals(runtimeBlueId)) {
            return externalChannel("matrix-topic-" + revision);
        }
        if (ACTOR_POLICY_BLUE_ID.equals(runtimeBlueId)) {
            return typed(runtimeBlueId)
                    .properties("revision", number(revision));
        }
        throw new AssertionError(
                "Missing mutation fixture for registered runtime "
                        + runtimeBlueId);
    }

    private static void assertMatrixMutation(
            Node document,
            String runtimeBlueId,
            String action) {
        Node target = NodePathEditor.getOrNull(
                document, "/contracts/target");
        if ("remove".equals(action)) {
            assertNull(target);
            return;
        }
        assertNotNull(target);
        assertEquals(runtimeBlueId, target.getType().getBlueId());
        if (EXTERNAL_BLUE_ID.equals(runtimeBlueId)) {
            assertEquals("matrix-topic-2",
                    target.getAsText("/subscriptionKey"));
        } else {
            assertEquals(BigInteger.valueOf(2L),
                    target.get("/revision"));
        }
    }

    private static void assertContractTransitionEvidence(
            ClosureProcessResult result,
            String path,
            String action) {
        DocumentTransitionEvidence transition = transitionWithPatch(
                result, path);
        DocumentTransitionEvidence.AuthoredContractPatch patch = null;
        for (DocumentTransitionEvidence.AuthoredContractPatch candidate
                : transition.authoredContractPatches()) {
            if (candidate.path().equals(path)) {
                patch = candidate;
                break;
            }
        }
        assertNotNull(patch);
        assertEquals(
                DocumentTransitionEvidence.Operation.valueOf(
                        action.toUpperCase(java.util.Locale.ROOT)),
                patch.operation());
        assertEquals(!"add".equals(action),
                patch.beforeValueBlueId().isPresent());
        assertEquals(!"remove".equals(action),
                patch.afterValueBlueId().isPresent());
        assertEquals(!"remove".equals(action),
                patch.authoredValueBlueId().isPresent());
        assertNotNull(transition.beforeDocumentBlueId());
        assertNotNull(transition.afterDocumentBlueId());
    }

    private static DocumentTransitionEvidence transitionWithPatch(
            ClosureProcessResult result,
            String path) {
        for (DocumentTransitionEvidence transition
                : result.documentTransitionEvidence()) {
            for (DocumentTransitionEvidence.AuthoredContractPatch patch
                    : transition.authoredContractPatches()) {
                if (patch.path().equals(path)) {
                    return transition;
                }
            }
        }
        throw new AssertionError(
                "Missing transition evidence for " + path);
    }

    private static DocumentTransitionEvidence transitionWithGeneralizationWrite(
            ClosureProcessResult result,
            String path) {
        for (DocumentTransitionEvidence transition
                : result.documentTransitionEvidence()) {
            for (DocumentTransitionEvidence.GeneratedGeneralizationWrite write
                    : transition.generatedGeneralizationWrites()) {
                if (write.path().equals(path)) {
                    return transition;
                }
            }
        }
        throw new AssertionError(
                "Missing generated generalization evidence for " + path);
    }

    private static DocumentTransitionEvidence.GeneratedGeneralizationWrite
    generalizationWrite(
            DocumentTransitionEvidence transition,
            String path) {
        for (DocumentTransitionEvidence.GeneratedGeneralizationWrite write
                : transition.generatedGeneralizationWrites()) {
            if (write.path().equals(path)) {
                return write;
            }
        }
        throw new AssertionError(
                "Missing generated generalization evidence for " + path);
    }

    private static void assertTransitionStepChainIsCrossBound(
            ClosureProcessResult result,
            DocumentId documentId) {
        String previousAfter = null;
        int observed = 0;
        for (DocumentTransitionEvidence transition
                : result.documentTransitionEvidence()) {
            if (!documentId.equals(transition.documentId())) {
                continue;
            }
            if (previousAfter != null) {
                assertEquals(previousAfter,
                        transition.beforeDocumentBlueId());
            }
            previousAfter = transition.afterDocumentBlueId();
            observed++;
        }
        assertTrue(observed > 0,
                "expected at least one transition for " + documentId.value());
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static Node event(String kind) {
        return new Node().properties("kind", new Node().value(kind));
    }

    private static Node number(long value) {
        return new Node().value(BigInteger.valueOf(value));
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static String hash(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void assertSuccess(ClosureProcessResult result) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(),
                diagnostic(result));
        assertTrue(result.commits());
        assertNotNull(result.commitCompanion());
    }

    private static void assertLiteralRollback(
            ClosureInvocationInput input,
            ClosureProcessResult result) {
        assertFalse(result.commits());
        assertTrue(result.rollbackToInput());
        assertEquals(input.snapshot().closureIdentity(),
                result.outputClosureIdentity());
        assertEquals(input.snapshot().graphGeneration(),
                result.graphGeneration());
        for (ManagedDocumentSnapshot before
                : input.snapshot().managedDocuments()) {
            ResultingDocument after = document(
                    result, before.documentId());
            assertEquals(before.blueId(), after.beforeBlueId());
            assertEquals(before.blueId(), after.afterBlueId());
            assertEquals(before.initialized(), after.initialized());
            assertEquals(before.terminated(), after.terminated());
            assertEquals(before.epoch(), after.epoch());
            assertEquals(
                    NodeWireForm.get(
                            before.document(), NodeWireForm.Strategy.SIMPLE),
                    NodeWireForm.get(
                            after.document(), NodeWireForm.Strategy.SIMPLE));
        }
        assertEquals(input.snapshot().occurrences(),
                result.occurrenceBindings());
        assertEquals(input.snapshot().occurrenceBindingSetIdentity(),
                result.occurrenceBindingSetIdentity());
        assertTrue(result.graphChanges().isEmpty());
        assertTrue(result.subscriptionDeltas().isEmpty());
        assertTrue(result.checkpointWrites().isEmpty());
        assertTrue(result.publicEvents().isEmpty());
        assertNull(result.commitCompanion());
    }

    private static ResultingDocument document(
            ClosureProcessResult result,
            DocumentId id) {
        for (ResultingDocument document : result.resultingDocuments()) {
            if (id.equals(document.documentId())) {
                return document;
            }
        }
        throw new AssertionError("Missing resulting document " + id.value());
    }

    private static ManagedOccurrenceBinding onlyBinding(
            List<ManagedOccurrenceBinding> bindings,
            DocumentId source,
            String path) {
        ManagedOccurrenceBinding match = null;
        for (ManagedOccurrenceBinding binding : bindings) {
            if (source.equals(binding.sourceDocumentId())
                    && path.equals(binding.sourcePath())) {
                if (match != null) {
                    throw new AssertionError("Duplicate occurrence " + path);
                }
                match = binding;
            }
        }
        if (match == null) {
            throw new AssertionError("Missing occurrence " + path);
        }
        return match;
    }

    private static String diagnostic(ClosureProcessResult result) {
        return result.diagnostic() == null
                ? null
                : result.diagnostic().category()
                        + ": " + result.diagnostic().message()
                        + " " + result.diagnostic().details();
    }

    /** Test-only Handler representation selected by exact contract key. */
    public static final class EvolutionHandler extends HandlerContract {
        private Integer revision;

        public Integer getRevision() {
            return revision;
        }

        public void setRevision(Integer value) {
            revision = value;
        }
    }

    /** Test-only external Channel used to prove tentative surface projection. */
    public static final class EvolutionExternalChannel
            extends ChannelContract {
        private String subscriptionKey;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String value) {
            subscriptionKey = value;
        }
    }

    /** Test-only distinct Operation runtime selected from the registry. */
    public static final class EvolutionOperation extends HandlerContract {
        private Integer revision;

        public Integer getRevision() {
            return revision;
        }

        public void setRevision(Integer value) {
            revision = value;
        }
    }

    /** Test-only typed application policy with no executable behavior. */
    public static final class ActorPolicy extends MarkerContract {
        private Integer revision;

        public Integer getRevision() {
            return revision;
        }

        public void setRevision(Integer value) {
            revision = value;
        }
    }

    private static final class EvolutionProcessor
            implements HandlerProcessor<EvolutionHandler> {
        private final List<String> executions = new ArrayList<String>();

        @Override
        public Class<EvolutionHandler> contractType() {
            return EvolutionHandler.class;
        }

        @Override
        public String deriveChannel(
                EvolutionHandler contract,
                HandlerRegistrationContext context) {
            String key = context.handlerKey();
            if (!"aMutatePolicy".equals(key)
                    && !"bCurrentPolicyEligible".equals(key)
                    && !"zLaterPolicyEligible".equals(key)) {
                return null;
            }
            ActorPolicy policy = context.contractAs(
                    "actorPolicy", ActorPolicy.class);
            if (policy == null) {
                throw new AssertionError(
                        "Actor Policy eligibility requires its typed header");
            }
            return contract.getRevision().equals(policy.getRevision())
                    ? "policyTriggered"
                    : "policyIneligible";
        }

        @Override
        public void execute(
                EvolutionHandler contract,
                ProcessorExecutionContext context) {
            String key = context.contractKey();
            executions.add(key);
            if ("aMutateLifecycle".equals(key)) {
                replaceLifecycleRoute(context);
            } else if ("bFrozenLifecycle".equals(key)) {
                increment(context, "/frozenRuns");
            } else if ("zAddedLifecycle".equals(key)) {
                increment(context, "/addedRuns");
            } else if ("seedTriggered".equals(key)) {
                context.emitEvent(EVENT_ONE.clone());
            } else if ("aMutateTriggered".equals(key)) {
                replaceTriggeredRoute(context);
                context.emitEvent(EVENT_ONE.clone());
            } else if ("bFrozenTriggered".equals(key)) {
                increment(context, "/frozenRuns");
            } else if ("zAddedTriggered".equals(key)) {
                increment(context, "/addedRuns");
            } else if ("seedPolicy".equals(key)) {
                context.emitEvent(EVENT_ONE.clone());
            } else if ("aMutatePolicy".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/contracts/actorPolicy",
                        matrixContract(ACTOR_POLICY_BLUE_ID, 2)));
                context.emitEvent(EVENT_ONE.clone());
            } else if ("bCurrentPolicyEligible".equals(key)) {
                increment(context, "/currentEligibilityRuns");
            } else if ("zLaterPolicyEligible".equals(key)) {
                increment(context, "/laterEligibilityRuns");
            } else if ("emitChildPair".equals(key)) {
                context.emitEvent(EVENT_CHILD_FIRST.clone());
                context.emitEvent(EVENT_CHILD_LATER.clone());
            } else if ("aMutateEmbedded".equals(key)) {
                replaceEmbeddedRoute(context);
            } else if ("bFrozenEmbedded".equals(key)) {
                increment(context, "/frozenRuns");
            } else if ("zAddedEmbedded".equals(key)) {
                increment(context, "/addedRuns");
            } else if ("removePeer".equals(key)) {
                context.applyPatch(JsonPatch.remove("/peer"));
            } else if ("earlierMemberPatch".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/otherState", new Node().value("tentative")));
            } else if ("tentativeSurface".equals(key)) {
                Node contracts = context.documentAt("/contracts");
                contracts.properties(
                        "tentativeExternal",
                        externalChannel("tentative-topic"));
                context.applyPatches(Arrays.asList(
                        JsonPatch.replace("/contracts", contracts),
                        JsonPatch.replace(
                                "/state", new Node().value("tentative")),
                        JsonPatch.remove("/peer")));
                context.emitEvent(EVENT_TENTATIVE.clone());
                throw new IllegalStateException(
                        "deterministic aggregate rollback failure");
            } else if ("matrixMutation".equals(key)) {
                mutateRegisteredRuntime(context);
            } else if ("installUnsupported".equals(key)) {
                context.emitEvent(EVENT_TENTATIVE.clone());
                context.applyPatches(Arrays.asList(
                        JsonPatch.replace(
                                "/state",
                                new Node().value("tentative")),
                        JsonPatch.add(
                                "/contracts/unsupported",
                                typed(UNSUPPORTED_BLUE_ID))));
            } else if ("generalizePrice".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/price/currency", new Node().value("USD")));
            } else if ("mutatePayloadContracts".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/payload/contracts/target",
                        new Node().value("after")));
            } else if ("mutateNestedContractValue".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/contracts/actorPolicy/revision",
                        new Node().value(2)));
            }
        }

        private static void mutateRegisteredRuntime(
                ProcessorExecutionContext context) {
            String action = String.valueOf(
                    context.documentAt("/matrixAction").getValue());
            String runtimeBlueId = String.valueOf(
                    context.documentAt("/matrixTargetBlueId").getValue());
            if ("add".equals(action)) {
                context.applyPatch(JsonPatch.add(
                        "/contracts/target",
                        matrixContract(runtimeBlueId, 2)));
            } else if ("replace".equals(action)) {
                context.applyPatch(JsonPatch.replace(
                        "/contracts/target",
                        matrixContract(runtimeBlueId, 2)));
            } else if ("remove".equals(action)) {
                context.applyPatch(JsonPatch.remove(
                        "/contracts/target"));
            } else {
                throw new AssertionError(
                        "Unknown matrix action " + action);
            }
        }

        private static void replaceLifecycleRoute(
                ProcessorExecutionContext context) {
            Node contracts = context.documentAt("/contracts");
            remove(contracts,
                    "aLifecycle",
                    "bLifecycle",
                    "aMutateLifecycle",
                    "bFrozenLifecycle");
            contracts.properties("laterLifecycle", lifecycleChannel());
            contracts.properties(
                    "zAddedLifecycle",
                    handler("laterLifecycle", null));
            context.applyPatch(JsonPatch.replace("/contracts", contracts));
        }

        private static void replaceTriggeredRoute(
                ProcessorExecutionContext context) {
            Node contracts = context.documentAt("/contracts");
            remove(contracts,
                    "aTriggered",
                    "bTriggered",
                    "aMutateTriggered",
                    "bFrozenTriggered");
            contracts.properties(
                    "laterTriggered",
                    triggeredChannel(EVENT_ONE_BLUE_ID));
            contracts.properties(
                    "zAddedTriggered",
                    handler("laterTriggered", null));
            context.applyPatch(JsonPatch.replace("/contracts", contracts));
        }

        private static void replaceEmbeddedRoute(
                ProcessorExecutionContext context) {
            Node contracts = context.documentAt("/contracts");
            remove(contracts,
                    "aEmbedded",
                    "bEmbedded",
                    "aMutateEmbedded",
                    "bFrozenEmbedded");
            contracts.properties(
                    "laterEmbedded",
                    embeddedChannel("/child", EVENT_CHILD_LATER));
            contracts.properties(
                    "zAddedEmbedded",
                    handler("laterEmbedded", null));
            context.applyPatch(JsonPatch.replace("/contracts", contracts));
        }

        private static void remove(Node contracts, String... keys) {
            for (String key : keys) {
                contracts.getProperties().remove(key);
            }
        }

        private static void increment(
                ProcessorExecutionContext context,
                String path) {
            BigInteger before = (BigInteger) context.documentAt(path)
                    .getValue();
            context.applyPatch(JsonPatch.replace(
                    path, new Node().value(before.add(BigInteger.ONE))));
        }

        private List<String> executions() {
            return Collections.unmodifiableList(
                    new ArrayList<String>(executions));
        }
    }

    private static final class ExternalProcessor
            implements ChannelProcessor<EvolutionExternalChannel> {
        private final AtomicInteger evaluations = new AtomicInteger();
        private final ExternalChannelSubscriptionFunctions<
                EvolutionExternalChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        EvolutionExternalChannel>() {
                    @Override
                    public List<String> channelKeys(
                            EvolutionExternalChannel contract) {
                        evaluations.incrementAndGet();
                        return Collections.singletonList(
                                contract.getSubscriptionKey());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            EvolutionExternalChannel contract) {
                        return "contract-evolution-acceptance";
                    }
                };

        @Override
        public Class<EvolutionExternalChannel> contractType() {
            return EvolutionExternalChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                EvolutionExternalChannel> externalSubscriptionFunctions() {
            return functions;
        }

        private int evaluations() {
            return evaluations.get();
        }
    }

    private static final class OperationProcessor
            implements HandlerProcessor<EvolutionOperation> {
        @Override
        public Class<EvolutionOperation> contractType() {
            return EvolutionOperation.class;
        }

        @Override
        public boolean isOperationRoute() {
            return true;
        }

        @Override
        public void execute(
                EvolutionOperation contract,
                ProcessorExecutionContext context) {
            // The matrix mutates the Operation declaration itself. A selected
            // pre-transition Operation still completes once with no effect.
        }
    }

    private static final class ActorPolicyProcessor
            implements ContractProcessor<ActorPolicy> {
        @Override
        public Class<ActorPolicy> contractType() {
            return ActorPolicy.class;
        }
    }

    private static final class ExactEventProvider implements NodeProvider {
        private final NodeProvider runtime =
                BlueRuntimeTypeRegistry.getDefault().asProvider();

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            if (EVENT_ONE_BLUE_ID.equals(blueId)) {
                return Collections.singletonList(EVENT_ONE.clone());
            }
            if (HANDLER_BLUE_ID.equals(blueId)) {
                return Collections.singletonList(HANDLER_TYPE.clone());
            }
            if (EXTERNAL_BLUE_ID.equals(blueId)) {
                return Collections.singletonList(EXTERNAL_TYPE.clone());
            }
            if (OPERATION_BLUE_ID.equals(blueId)) {
                return Collections.singletonList(OPERATION_TYPE.clone());
            }
            if (ACTOR_POLICY_BLUE_ID.equals(blueId)) {
                return Collections.singletonList(ACTOR_POLICY_TYPE.clone());
            }
            if (UNSUPPORTED_BLUE_ID.equals(blueId)) {
                return Collections.singletonList(
                        UNSUPPORTED_TYPE.clone());
            }
            return runtime.fetchByBlueId(blueId);
        }
    }

    private static final class LanguageSnapshotManager
            implements ProcessingSnapshotManager {
        private final BlueLanguageRuntime language;
        private final NodeProvider exactProvider;

        private LanguageSnapshotManager(BlueLanguageRuntime language, NodeProvider exactProvider) {
            this.language = language;
            this.exactProvider = exactProvider;
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(FrozenNode reference) {
            if (!reference.isReferenceOnly()) return reference;
            List<Node> content = exactProvider.fetchByBlueId(reference.getReferenceBlueId());
            if (content == null || content.isEmpty()) return null;
            assertEquals(1, content.size());
            Node exact = blue.language.identity.NodeToBlueIdInput.stripResolvedBlueIdMetadata(content.get(0));
            assertEquals(reference.getReferenceBlueId(), blueId(exact));
            return FrozenNode.fromNode(exact);
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return language.snapshots().resolve(document.clone());
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return language.snapshots().resolve(document.clone());
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                java.util.Collection<String> preservedPaths) {
            return language.snapshots().resolvePreservingPaths(
                    document.clone(), preservedPaths);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                Node document,
                java.util.Collection<String> preservedPaths) {
            return fromDocumentPreservingPaths(document, preservedPaths);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return language.patching().apply(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return language.snapshots().cache(snapshot);
        }
    }
}
